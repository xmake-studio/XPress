package com.xpress.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeParserTest {

    /** Trimmed ffprobe output of a portrait Xiaomi HDR clip with a cover image stream in front. */
    private val phoneHdr = """
        {
          "streams": [
            { "index": 0, "codec_name": "mjpeg", "codec_type": "video", "width": 320, "height": 240,
              "disposition": { "attached_pic": 1 } },
            { "index": 1, "codec_name": "hevc", "codec_type": "video", "width": 3840, "height": 2160,
              "sample_aspect_ratio": "1:1", "pix_fmt": "yuv420p10le", "color_range": "tv",
              "color_space": "bt2020nc", "color_transfer": "arib-std-b67", "color_primaries": "bt2020",
              "avg_frame_rate": "60000/1001", "r_frame_rate": "60/1", "bit_rate": "48000000",
              "side_data_list": [ { "side_data_type": "Display Matrix", "rotation": -90 },
                                  { "side_data_type": "DOVI configuration record", "dv_profile": 8 } ] },
            { "index": 2, "codec_name": "aac", "codec_type": "audio", "sample_rate": "48000", "channels": 2 }
          ],
          "format": { "duration": "12.345000", "size": "74000000" }
        }
    """.trimIndent()

    @Test
    fun skipsCoverArtAndAppliesRotation() {
        val info = ProbeParser.parse(phoneHdr)
        assertEquals("hevc", info.videoCodec)
        assertEquals(2160, info.width)
        assertEquals(3840, info.height)
        assertEquals(59.94, info.fps, 0.01)
        assertEquals(12_345, info.durationMs)
        assertEquals(74_000_000, info.sizeBytes)
        assertEquals(8, info.dolbyVisionProfile)
        assertTrue(info.isHdr)
        assertTrue(info.is10Bit)
        assertTrue(info.hasAudio)
        assertEquals(2, info.audioChannels)
    }

    @Test
    fun anamorphicSilentVideoWithFallbacks() {
        val json = """
            { "streams": [ { "codec_name": "mpeg2video", "codec_type": "video", "width": 720, "height": 576,
                             "sample_aspect_ratio": "16:15", "avg_frame_rate": "0/0", "r_frame_rate": "25/1",
                             "duration": "3.5", "tags": { "rotate": "180" } } ],
              "format": {} }
        """.trimIndent()
        val info = ProbeParser.parse(json, fallbackSize = 1234)
        assertEquals(768, info.width)
        assertEquals(576, info.height)
        assertEquals(25.0, info.fps, 0.001)
        assertEquals(3_500, info.durationMs)
        assertEquals(1234, info.sizeBytes)
        assertFalse(info.hasAudio)
        assertEquals(0, info.audioChannels)
    }

    @Test(expected = ProbeException::class)
    fun audioOnlyIsRejected() {
        ProbeParser.parse("""{ "streams": [ { "codec_type": "audio" } ], "format": { "duration": "1" } }""")
    }

    @Test
    fun previewFrameIsTonemappedAndKeyframeSeekWhenScrubbing() {
        val info = ProbeParser.parse(phoneHdr)
        val fast = FfmpegCommands.previewFrame(info, "in.mp4", 5_000, exact = false)
        assertTrue(fast.indexOf("-noaccurate_seek") < fast.indexOf("-i"))
        val filter = fast[fast.indexOf("-vf") + 1]
        assertTrue(filter, filter.contains("tonemap"))
        assertTrue(filter, filter.contains("w=720:h=1280"))
        assertFalse(FfmpegCommands.previewFrame(info, "in.mp4", 5_000, exact = true).contains("-noaccurate_seek"))
    }
}
