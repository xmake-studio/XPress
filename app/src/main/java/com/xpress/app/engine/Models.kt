package com.xpress.app.engine

import kotlin.math.max
import kotlin.math.min

enum class VideoCodec { H264, HEVC, AV1 }

enum class SizeMode { TARGET_SIZE, BITRATE, QUALITY }

enum class EncodeSpeed { FAST, BALANCED, BEST }

data class CompressSettings(
    val mode: SizeMode = SizeMode.TARGET_SIZE,
    val targetSizeMb: Double = 10.0,
    val videoBitrateKbps: Int = 2500,
    /** CRF-like value, lower is better. */
    val quality: Int = 23,
    val codec: VideoCodec = VideoCodec.H264,
    /** Output short side in px, or [AUTO] / [ORIGINAL]. */
    val resolution: Int = AUTO,
    /** Output frame rate cap, or [AUTO] / [ORIGINAL]. */
    val fps: Int = AUTO,
    /** Audio bitrate in kbps, or [AUTO] / [NONE]. */
    val audioKbps: Int = AUTO,
    val speed: EncodeSpeed = EncodeSpeed.BALANCED,
    /** Drop the audio track entirely, its whole budget goes to the picture. */
    val removeAudio: Boolean = false,
) {
    companion object {
        const val AUTO = 0
        const val ORIGINAL = -1
        const val NONE = -2
    }
}

/** What we learned about the source file. Width/height are as displayed, rotation already applied. */
data class VideoInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Double,
    val videoCodec: String,
    val pixelFormat: String? = null,
    val colorTransfer: String? = null,
    val colorPrimaries: String? = null,
    val videoBitrate: Long? = null,
    val hasAudio: Boolean = true,
    val audioCodec: String? = null,
    val audioChannels: Int = 2,
    val audioSampleRate: Int = 48000,
    val sizeBytes: Long = 0,
    val dolbyVisionProfile: Int? = null,
) {
    val isHdr: Boolean get() = colorTransfer == "smpte2084" || colorTransfer == "arib-std-b67"
    val shortSide: Int get() = min(width, height)
    val longSide: Int get() = max(width, height)
    val is10Bit: Boolean get() = pixelFormat?.contains("10") == true
}

data class TrimRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

data class EncodePlan(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val fps: Double,
    /** True when [fps] is lower than the source and a fps filter is needed. */
    val reduceFps: Boolean,
    /** Target video bitrate for two-pass modes, null in quality mode. */
    val videoKbps: Int?,
    val crf: Int?,
    /** 0 means no audio track in the output. */
    val audioKbps: Int,
    val audioChannels: Int,
    val audioSampleRate: Int,
    /** Hard size limit in target-size mode. */
    val targetBytes: Long?,
    val startMs: Long,
    val durationMs: Long,
    val clipEnd: Boolean,
    /** Source HDR transfer (smpte2084 / arib-std-b67) when tonemapping to SDR is needed. */
    val hdrTransfer: String?,
    val scale: Boolean,
    val speed: EncodeSpeed,
    val warning: String? = null,
) {
    val twoPass: Boolean get() = videoKbps != null
    val hasAudio: Boolean get() = audioKbps > 0
    val tonemap: Boolean get() = hdrTransfer != null
}
