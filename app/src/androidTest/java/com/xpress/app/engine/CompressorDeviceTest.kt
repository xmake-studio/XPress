package com.xpress.app.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end runs of the real FFmpeg build on a device: synthetic phone-like sources in,
 * finished files out, checked with ffprobe.
 */
@RunWith(AndroidJUnit4::class)
class CompressorDeviceTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "test-src").apply { mkdirs() }
    private val saved = mutableListOf<Uri>()

    @After
    fun cleanUp() {
        saved.forEach { ctx.contentResolver.delete(it, null, null) }
        dir.deleteRecursively()
    }

    private fun ffmpeg(vararg args: String) {
        val s = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-y", *args))
        assertTrue(s.allLogsAsString.takeLast(3000), ReturnCode.isSuccess(s.returnCode))
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
    ): File {
        val raw = File(dir, "raw_$name.mp4")
        val args = mutableListOf(
            "-f", "lavfi", "-i", "testsrc2=s=$size:r=$fps:d=$seconds",
        )
        if (audio) args += listOf("-f", "lavfi", "-i", "sine=f=440:d=$seconds:sample_rate=48000")
        args += listOf("-vf", "format=yuv420p,noise=alls=14:allf=t+u")
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

    private fun compress(src: File, settings: CompressSettings, trim: TrimRange? = null): Pair<CompressResult, JSONObject> {
        val uri = Uri.fromFile(src)
        val info = VideoProbe.probe(ctx, uri)
        val request = CompressRequest(uri, src.name, info, trim ?: TrimRange(0, info.durationMs), settings)
        var lastProgress = 0f
        val result = try {
            runBlocking { Compressor(ctx).compress(request) { lastProgress = it.fraction } }
        } catch (e: CompressException) {
            throw AssertionError("${e.message}\n${e.details}", e)
        }
        saved += result.outputUri
        assertEquals(1f, lastProgress, 0.001f)
        val out = File(dir, "check.mp4")
        ctx.contentResolver.openInputStream(result.outputUri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
        assertEquals(result.sizeBytes, out.length())
        val probe = FFprobeKit.getMediaInformation(out.absolutePath).mediaInformation
        Log.i("XPressTest", "${src.name}: ${result.sizeBytes} B, ${result.attempts} attempt(s), kbps=${result.finalVideoKbps}, ${result.plan}")
        return result to probe.allProperties
    }

    private fun JSONObject.stream(type: String): JSONObject? {
        val arr = getJSONArray("streams")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.getString("codec_type") == type }
    }

    private fun JSONObject.duration() = getJSONObject("format").getString("duration").toDouble()

    @Test
    fun hdrHevcPortraitFillsDiscordLimit() {
        // What a Xiaomi records in HDR mode held upright: HEVC 10-bit HLG, 60 fps, rotation in metadata.
        val src = source("hdr", seconds = 20, hdr = true, rotate = 90)
        val info = VideoProbe.probe(ctx, Uri.fromFile(src))
        assertTrue(info.isHdr)
        assertTrue("portrait expected: ${info.width}x${info.height}", info.width < info.height)

        val (result, json) = compress(src, CompressSettings())
        assertTrue("size ${result.sizeBytes}", result.sizeBytes <= 10_000_000)
        assertTrue("size ${result.sizeBytes} wastes the budget", result.sizeBytes >= 9_400_000)

        val v = json.stream("video")!!
        assertEquals("h264", v.getString("codec_name"))
        assertEquals("yuv420p", v.getString("pix_fmt"))
        assertEquals("bt709", v.optString("color_transfer"))
        assertEquals("bt709", v.optString("color_space"))
        assertTrue(v.getInt("width") < v.getInt("height"))
        assertEquals(result.plan.width, v.getInt("width"))
        assertEquals(result.plan.height, v.getInt("height"))
        assertEquals("aac", json.stream("audio")!!.getString("codec_name"))
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
        assertEquals("bt709", json.stream("video")!!.optString("color_space"))
    }

    @Test
    fun hevcOutputIsTaggedForApple() {
        val src = source("hevc", seconds = 8, size = "1280x720", fps = 30)
        val (result, json) = compress(src, CompressSettings(targetSizeMb = 2.0, codec = VideoCodec.HEVC, speed = EncodeSpeed.FAST))
        assertTrue(result.sizeBytes <= 2_000_000)
        val v = json.stream("video")!!
        assertEquals("hevc", v.getString("codec_name"))
        assertEquals("hvc1", v.getString("codec_tag_string"))
        assertEquals("bt709", v.optString("color_space"))
    }

    @Test
    fun av1ShortClip() {
        val src = source("av1", seconds = 4, size = "854x480", fps = 30)
        val (result, json) = compress(src, CompressSettings(targetSizeMb = 1.0, codec = VideoCodec.AV1, speed = EncodeSpeed.FAST))
        assertTrue(result.sizeBytes <= 1_000_000)
        assertTrue("size ${result.sizeBytes}", result.sizeBytes >= 940_000)
        assertEquals("av1", json.stream("video")!!.getString("codec_name"))
    }

    @Test
    fun qualityModeAndVideoWithoutAudio() {
        val src = source("silent", seconds = 5, size = "1280x720", fps = 30, audio = false)
        val (result, json) = compress(src, CompressSettings(mode = SizeMode.QUALITY, quality = 28, speed = EncodeSpeed.FAST))
        assertNull(result.plan.targetBytes)
        assertNull(json.stream("audio"))
        assertEquals(1280, json.stream("video")!!.getInt("width"))
    }
}
