package com.xpress.app

import com.xpress.app.engine.CompressException
import com.xpress.app.engine.CompressRequest
import com.xpress.app.engine.CompressResult
import com.xpress.app.engine.CompressSettings
import com.xpress.app.engine.Compressor
import com.xpress.app.engine.DesktopEncoder
import com.xpress.app.engine.DesktopMedia
import com.xpress.app.engine.EncodeSpeed
import com.xpress.app.engine.Ffmpeg
import com.xpress.app.engine.MediaRef
import com.xpress.app.engine.SizeMode
import com.xpress.app.engine.TrimRange
import com.xpress.app.engine.VideoCodec
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end runs of the bundled Windows FFmpeg: synthetic phone-like sources in, finished
 * files out, checked with ffprobe. The same scenarios as the Android device test.
 */
class DesktopCompressorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val outDir by lazy { tmp.newFolder("out") }
    private val encoder by lazy { DesktopEncoder(outputDir = { outDir }, workDir = File(tmp.root, "work")) }

    private fun ffmpeg(vararg args: String) {
        val r = Ffmpeg.capture(listOf(Ffmpeg.ffmpeg, "-hide_banner", "-y") + args, timeoutSeconds = 300)
        assertEquals(r.stderr.takeLast(3000), 0, r.exitCode)
    }

    /** Noisy moving test pattern: hard enough content that the bitrate actually matters. */
    private fun source(
        name: String,
        seconds: Int,
        size: String = "1920x1080",
        fps: Int = 60,
        hdr: Boolean = false,
        rotate: Int = 0,
        audio: Boolean = true,
        dir: File = tmp.root,
    ): File {
        val raw = File(dir, "raw_$name.mp4")
        val args = mutableListOf("-f", "lavfi", "-i", "testsrc2=s=$size:r=$fps:d=$seconds")
        if (audio) args += listOf("-f", "lavfi", "-i", "sine=f=440:d=$seconds:sample_rate=48000")
        // FFmpeg 8 takes colour tags from the frames, so tag them in the graph, not only on the encoder.
        val tags = if (hdr) "color_primaries=bt2020:color_trc=arib-std-b67:colorspace=bt2020nc" else "color_primaries=bt709:color_trc=bt709:colorspace=bt709"
        args += listOf("-vf", "format=yuv420p,noise=alls=14:allf=t+u,setparams=$tags")
        if (hdr) {
            args += listOf(
                "-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le",
                "-color_primaries", "bt2020", "-color_trc", "arib-std-b67", "-colorspace", "bt2020nc",
                "-x265-params", "log-level=error", "-b:v", "40M", "-tag:v", "hvc1",
            )
        } else {
            args += listOf(
                "-c:v", "libx264", "-preset", "ultrafast", "-b:v", "30M",
                "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
            )
        }
        if (audio) args += listOf("-c:a", "aac", "-b:a", "256k", "-ac", "2")
        args += listOf("-shortest", raw.absolutePath)
        ffmpeg(*args.toTypedArray())
        if (rotate == 0) return raw
        val rotated = File(dir, "$name.mp4")
        ffmpeg("-display_rotation", rotate.toString(), "-i", raw.absolutePath, "-map", "0", "-c", "copy", rotated.absolutePath)
        return rotated
    }

    private fun compress(src: File, settings: CompressSettings, trim: TrimRange? = null): Pair<CompressResult, JsonObject> {
        val info = DesktopMedia.probe(src)
        val request = CompressRequest(MediaRef(src.absolutePath), src.name, info, trim ?: TrimRange(0, info.durationMs), settings)
        var lastProgress = 0f
        val result = try {
            runBlocking { Compressor(encoder).compress(request) { lastProgress = it.fraction } }
        } catch (e: CompressException) {
            throw AssertionError("${e.message}\n${e.details}", e)
        }
        assertEquals(1f, lastProgress, 0.001f)
        val out = File(result.output.value)
        assertEquals(outDir, out.parentFile)
        assertEquals(result.sizeBytes, out.length())
        assertFalse("work dir left behind", File(tmp.root, "work").exists())
        println("${src.name}: ${result.sizeBytes} B, ${result.attempts} attempt(s), kbps=${result.finalVideoKbps}, ${result.plan}")
        return result to ffprobe(out)
    }

    private fun ffprobe(file: File): JsonObject {
        val r = Ffmpeg.capture(listOf(Ffmpeg.ffprobe, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", file.absolutePath))
        return Json.parseToJsonElement(r.stdout.decodeToString()).jsonObject
    }

    private fun JsonObject.stream(type: String): JsonObject? =
        getValue("streams").jsonArray.map { it.jsonObject }.firstOrNull { it.str("codec_type") == type }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.int(key: String): Int = str(key)!!.toInt()

    private fun JsonObject.duration() = getValue("format").jsonObject.str("duration")!!.toDouble()

    @Test
    fun hdrHevcPortraitFillsDiscordLimit() {
        // What a Xiaomi records in HDR mode held upright: HEVC 10-bit HLG, 60 fps, rotation in metadata.
        val src = source("hdr", seconds = 20, hdr = true, rotate = 90)
        val info = DesktopMedia.probe(src)
        assertTrue(info.isHdr)
        assertTrue("portrait expected: ${info.width}x${info.height}", info.width < info.height)

        val (result, json) = compress(src, CompressSettings())
        assertTrue("size ${result.sizeBytes}", result.sizeBytes <= 20_000_000)
        assertTrue("size ${result.sizeBytes} wastes the budget", result.sizeBytes >= 18_800_000)

        val v = json.stream("video")!!
        assertEquals("h264", v.str("codec_name"))
        assertEquals("yuv420p", v.str("pix_fmt"))
        assertEquals("bt709", v.str("color_transfer"))
        assertEquals("bt709", v.str("color_space"))
        assertTrue(v.int("width") < v.int("height"))
        assertEquals(result.plan.width, v.int("width"))
        assertEquals(result.plan.height, v.int("height"))
        assertEquals("aac", json.stream("audio")!!.str("codec_name"))
        assertEquals(20.0, json.duration(), 0.15)
    }

    @Test
    fun trimAndMuteAtCustomSize() {
        val src = source("sdr", seconds = 12)
        val (result, json) = compress(
            src,
            CompressSettings(targetSizeMb = 3.0, removeAudio = true, speed = EncodeSpeed.FAST),
            TrimRange(2_500, 9_500),
        )
        assertTrue(result.sizeBytes <= 3_000_000)
        assertTrue("size ${result.sizeBytes}", result.sizeBytes >= 2_800_000)
        assertNull(json.stream("audio"))
        assertEquals(7.0, json.duration(), 0.1)
        assertEquals("bt709", json.stream("video")!!.str("color_space"))
    }

    @Test
    fun hevcOutputIsTaggedForApple() {
        // Two-pass x265 keeps its stats path in a ':'-separated option: the drive letter must not reach it.
        val src = source("hevc", seconds = 8, size = "1280x720", fps = 30)
        val (result, json) = compress(src, CompressSettings(targetSizeMb = 2.0, codec = VideoCodec.HEVC, speed = EncodeSpeed.FAST))
        assertTrue(result.sizeBytes <= 2_000_000)
        val v = json.stream("video")!!
        assertEquals("hevc", v.str("codec_name"))
        assertEquals("hvc1", v.str("codec_tag_string"))
        assertEquals("bt709", v.str("color_space"))
    }

    @Test
    fun av1ShortClip() {
        val src = source("av1", seconds = 4, size = "854x480", fps = 30)
        val (result, json) = compress(src, CompressSettings(targetSizeMb = 1.0, codec = VideoCodec.AV1, speed = EncodeSpeed.FAST))
        assertTrue(result.sizeBytes <= 1_000_000)
        assertTrue("size ${result.sizeBytes}", result.sizeBytes >= 940_000)
        assertEquals("av1", json.stream("video")!!.str("codec_name"))
    }

    @Test
    fun qualityModeAndVideoWithoutAudio() {
        val src = source("silent", seconds = 5, size = "1280x720", fps = 30, audio = false)
        val (result, json) = compress(src, CompressSettings(mode = SizeMode.QUALITY, quality = 28, speed = EncodeSpeed.FAST))
        assertNull(result.plan.targetBytes)
        assertNull(json.stream("audio"))
        assertEquals(1280, json.stream("video")!!.int("width"))
    }

    @Test
    fun cyrillicPathWithSpacesAndPreviews() {
        val dir = File(tmp.root, "Мои видео 2026").apply { mkdirs() }
        val src = source("отпуск на море", seconds = 6, size = "1280x720", fps = 30, dir = dir)
        val info = DesktopMedia.probe(src)
        assertEquals(1280, info.width)

        assertTrue(DesktopMedia.thumbnails(src, info.durationMs).size >= 3)
        assertNotNull(DesktopMedia.frame(src, info, 3_000, exact = true))
        assertNotNull(DesktopMedia.frame(src, info, 3_000, exact = false))

        val (result, _) = compress(src, CompressSettings(targetSizeMb = 1.5, speed = EncodeSpeed.FAST))
        assertEquals("${src.nameWithoutExtension}_xpress.mp4", result.outputName)
        assertNotNull(DesktopMedia.frame(File(result.output.value), null, 0, exact = true))

        // A second run never overwrites the first file.
        val (second, _) = compress(src, CompressSettings(targetSizeMb = 1.5, speed = EncodeSpeed.FAST))
        assertEquals("${src.nameWithoutExtension}_xpress (2).mp4", File(second.output.value).name)
    }

    @Test
    fun cancellingStopsFfmpeg() = runBlocking {
        val src = source("long", seconds = 30, size = "1920x1080", fps = 60)
        val info = DesktopMedia.probe(src)
        val request = CompressRequest(MediaRef(src.absolutePath), src.name, info, TrimRange(0, info.durationMs), CompressSettings(speed = EncodeSpeed.BEST))
        // The test JVM may own unrelated children (its conhost.exe), so look at new ones only.
        val before = ProcessHandle.current().children().map { it.pid() }.toList().toSet()
        fun ours() = ProcessHandle.current().children().filter { it.isAlive && it.pid() !in before }.toList()
        val job = async { Compressor(encoder).compress(request) {} }
        delay(3_000)
        assertTrue("ffmpeg should be running", ours().isNotEmpty())
        job.cancel()
        runCatching { job.await() }
        delay(500)
        assertTrue("ffmpeg outlived the job", ours().isEmpty())
        assertFalse("work dir left behind: ${File(tmp.root, "work").walk().toList()}", File(tmp.root, "work").exists())
        assertTrue("nothing should be saved", outDir.listFiles().isNullOrEmpty())
    }
}
