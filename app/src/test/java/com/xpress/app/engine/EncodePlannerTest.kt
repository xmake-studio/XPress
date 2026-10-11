package com.xpress.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodePlannerTest {

    private val phone4k = VideoInfo(
        durationMs = 60_000, width = 3840, height = 2160, fps = 60.0, videoCodec = "hevc",
        pixelFormat = "yuv420p10le", colorTransfer = "arib-std-b67", colorPrimaries = "bt2020",
        audioChannels = 2, audioSampleRate = 48000, sizeBytes = 400_000_000,
    )
    private val portrait = VideoInfo(durationMs = 15_000, width = 1080, height = 2400, fps = 30.0, videoCodec = "h264")

    private fun full(info: VideoInfo) = TrimRange(0, info.durationMs)

    @Test
    fun discordMinuteOf4k60GetsDownscaledAndHalvedFrameRate() {
        val plan = EncodePlanner.plan(phone4k, full(phone4k), CompressSettings())
        assertEquals(10_000_000L, plan.targetBytes)
        assertEquals(96, plan.audioKbps)
        assertEquals(29.0, plan.fps, 1.5)
        assertTrue(plan.reduceFps)
        assertTrue("short side ${plan.height}", plan.height in 360..720)
        assertEquals(plan.width.toDouble() / plan.height, 16.0 / 9, 0.02)
        assertTrue(plan.tonemap)
        assertTrue(plan.twoPass)
    }

    @Test
    fun budgetLeavesRoomForAudioAndContainer() {
        val plan = EncodePlanner.plan(phone4k, full(phone4k), CompressSettings())
        val seconds = plan.durationMs / 1000.0
        val total = (plan.videoKbps!! + plan.audioKbps * 1.03) * 1000 / 8 * seconds +
            EncodePlanner.containerOverheadBytes(seconds, 60.0, 48000, true)
        assertTrue("estimated $total", total <= 10_000_000 * EncodePlanner.FIRST_TRY_FILL + 1)
        assertTrue("estimated $total", total >= 10_000_000 * 0.97)
    }

    @Test
    fun removingAudioGivesItsBitsToVideo() {
        val with = EncodePlanner.plan(phone4k, full(phone4k), CompressSettings())
        val without = EncodePlanner.plan(phone4k, full(phone4k), CompressSettings(removeAudio = true))
        assertFalse(without.hasAudio)
        assertTrue(without.videoKbps!! > with.videoKbps!! + 90)
    }

    @Test
    fun shortClipKeeps1080pAndFullFrameRate() {
        val clip = phone4k.copy(durationMs = 60_000)
        val plan = EncodePlanner.plan(clip, TrimRange(10_000, 15_000), CompressSettings())
        assertEquals(1080, plan.height)
        assertEquals(1920, plan.width)
        assertEquals(60.0, plan.fps, 0.01)
        assertEquals(128, plan.audioKbps)
        assertEquals(10_000, plan.startMs)
        assertEquals(5_000, plan.durationMs)
        assertTrue(plan.clipEnd)
    }

    @Test
    fun portraitKeepsOrientationAndEvenSizes() {
        val plan = EncodePlanner.plan(portrait, full(portrait), CompressSettings())
        assertTrue(plan.width < plan.height)
        assertEquals(0, plan.width % 2)
        assertEquals(0, plan.height % 2)
        assertEquals(1080.0 / 2400, plan.width.toDouble() / plan.height, 0.01)
    }

    @Test
    fun neverUpscales() {
        val small = portrait.copy(width = 480, height = 854)
        val plan = EncodePlanner.plan(small, full(small), CompressSettings(resolution = 1080))
        assertEquals(480, plan.width)
        assertFalse(plan.scale)
    }

    @Test
    fun explicitFpsCapUsesDivisor() {
        val ntsc = phone4k.copy(fps = 59.94)
        val plan = EncodePlanner.plan(ntsc, full(ntsc), CompressSettings(fps = 30))
        assertEquals(29.97, plan.fps, 0.01)
        assertEquals("30000/1001", FfmpegCommands.fpsExpr(plan.fps))
    }

    @Test
    fun qualityModeIsSinglePass() {
        val plan = EncodePlanner.plan(phone4k, full(phone4k), CompressSettings(mode = SizeMode.QUALITY, quality = 20))
        assertNull(plan.videoKbps)
        assertFalse(plan.twoPass)
        assertEquals(20, plan.crf)
        assertEquals(1080, plan.height)
    }

    @Test
    fun hopelessBudgetWarns() {
        val long = phone4k.copy(durationMs = 3_600_000)
        val plan = EncodePlanner.plan(long, full(long), CompressSettings())
        assertTrue(plan.warning != null)
    }

    @Test
    fun firstPassHasNoAudioAndWritesNothing() {
        val plan = EncodePlanner.plan(phone4k, TrimRange(1_500, 20_000), CompressSettings())
        val args = FfmpegCommands.encode(plan, "in.mp4", "out.mp4", FfmpegCommands.Pass.FIRST, "log")
        assertTrue(args.containsAll(listOf("-pass", "1", "-an", "-f", "null")))
        assertFalse(args.contains("out.mp4"))
        assertEquals("1.500", args[args.indexOf("-ss") + 1])
        assertEquals("18.500", args[args.indexOf("-t") + 1])
        assertTrue(args.indexOf("-ss") < args.indexOf("-i"))
    }

    @Test
    fun secondPassMapsAudioAndTonemaps() {
        val plan = EncodePlanner.plan(phone4k, full(phone4k), CompressSettings())
        val args = FfmpegCommands.encode(plan, "in.mp4", "out.mp4", FfmpegCommands.Pass.SECOND, "log", videoKbps = 1234)
        assertEquals("1234k", args[args.indexOf("-b:v") + 1])
        assertTrue(args.contains("0:a:0?"))
        assertTrue(args.contains("libx264"))
        val vf = args[args.indexOf("-vf") + 1]
        assertTrue(vf, vf.startsWith("setparams=range=tv:color_primaries=bt2020:color_trc=arib-std-b67:colorspace=bt2020nc,fps=30"))
        assertTrue(vf, vf.contains("tin=arib-std-b67"))
        assertTrue(vf, vf.contains("tonemap=tonemap=hable"))
        assertTrue(vf, vf.endsWith("format=yuv420p"))
        assertEquals("out.mp4", args.last())
        assertFalse(args.contains("-ss"))
    }

    @Test
    fun hevcUsesX265StatsAndAppleTag() {
        val plan = EncodePlanner.plan(portrait, full(portrait), CompressSettings(codec = VideoCodec.HEVC))
        val args = FfmpegCommands.encode(plan, "in.mp4", "out.mp4", FfmpegCommands.Pass.SECOND, "log")
        val params = args[args.indexOf("-x265-params") + 1]
        assertTrue(params, params.contains("pass=2") && params.contains("stats=log.x265"))
        assertEquals("hvc1", args[args.indexOf("-tag:v") + 1])
    }
}
