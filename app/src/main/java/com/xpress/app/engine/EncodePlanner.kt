package com.xpress.app.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Turns source info + user settings into concrete encoder parameters.
 *
 * In target-size mode the whole budget goes to the bitstream: audio gets a fixed bitrate,
 * the MP4 index gets an estimated overhead, everything else is video. Resolution and frame
 * rate are then chosen so each pixel still gets enough bits — a sharp 540p looks better than
 * a blocky 1080p at the same size.
 */
object EncodePlanner {

    /** Decimal megabytes: 10 MB = 10 000 000 bytes, which is below Discord's limit either way. */
    const val BYTES_PER_MB = 1_000_000L

    /** Share of the target we aim at on the first try; the compressor refines it afterwards. */
    const val FIRST_TRY_FILL = 0.985

    private const val AUTO_MAX_SHORT_SIDE = 1080
    private val STANDARD_SHORT_SIDES = intArrayOf(2160, 1440, 1080, 720, 540, 480, 360, 240)
    private const val MIN_VIDEO_KBPS = 24

    /** Bits per pixel per frame below which a codec starts to visibly fall apart. */
    fun bppThreshold(codec: VideoCodec): Double = when (codec) {
        VideoCodec.H264 -> 0.065
        VideoCodec.HEVC -> 0.045
        VideoCodec.AV1 -> 0.038
    }

    fun plan(info: VideoInfo, trim: TrimRange, s: CompressSettings): EncodePlan {
        val durationMs = trim.durationMs.coerceAtLeast(100)
        val seconds = durationMs / 1000.0
        val srcFps = info.fps.takeIf { it in 1.0..480.0 } ?: 30.0

        val audioChannels = if (info.hasAudio) info.audioChannels.coerceIn(1, 2) else 0
        val audioSampleRate = min(info.audioSampleRate.takeIf { it > 0 } ?: 48000, 48000)
        val targetBytes = if (s.mode == SizeMode.TARGET_SIZE) (s.targetSizeMb * BYTES_PER_MB).roundToLong() else null

        val audioKbps = when {
            !info.hasAudio || s.removeAudio || s.audioKbps == CompressSettings.NONE -> 0
            s.audioKbps > 0 -> s.audioKbps
            targetBytes != null -> autoAudioKbps(targetBytes * 8.0 / seconds / 1000.0, audioChannels)
            else -> if (audioChannels == 1) 96 else 128
        }

        var warning: String? = null
        val videoKbps: Int? = when (s.mode) {
            SizeMode.TARGET_SIZE -> {
                val budget = videoBudgetKbps(targetBytes!!, seconds, audioKbps, srcFps, audioSampleRate)
                if (budget < MIN_VIDEO_KBPS) {
                    warning = "Размер слишком мал для такой длительности. Обрежьте видео или увеличьте размер."
                }
                max(budget, MIN_VIDEO_KBPS)
            }
            SizeMode.BITRATE -> max(s.videoBitrateKbps, MIN_VIDEO_KBPS)
            SizeMode.QUALITY -> null
        }

        val thr = bppThreshold(s.codec)
        val fps = chooseFps(info, srcFps, videoKbps, s, thr)
        val shortSide = chooseShortSide(info, videoKbps, fps, s, thr)
        val (w, h) = dimensions(info, shortSide)

        if (warning == null && videoKbps != null && bpp(videoKbps, w, h, fps) < thr * 0.45) {
            warning = "Очень мало бит на кадр — качество будет низким. Попробуйте обрезать видео."
        }
        if (warning == null && info.dolbyVisionProfile == 5) {
            warning = "Dolby Vision профиля 5 может сжаться с искажёнными цветами."
        }

        return EncodePlan(
            codec = s.codec,
            width = w,
            height = h,
            fps = fps,
            reduceFps = fps < srcFps - 0.01,
            videoKbps = videoKbps,
            crf = if (s.mode == SizeMode.QUALITY) s.quality else null,
            audioKbps = audioKbps,
            audioChannels = if (audioKbps > 0) audioChannels else 0,
            audioSampleRate = audioSampleRate,
            targetBytes = targetBytes,
            startMs = trim.startMs,
            durationMs = durationMs,
            clipEnd = trim.endMs < info.durationMs - 1,
            hdrTransfer = info.colorTransfer.takeIf { info.isHdr },
            sourceColor = sourceColor(info),
            outputColor = if (info.isHdr) ColorTags("bt709", "bt709", "bt709", false) else sourceColor(info).copy(fullRange = false),
            scale = w != even(info.width.toDouble()) || h != even(info.height.toDouble()),
            speed = s.speed,
            warning = warning,
        )
    }

    private val MATRICES = setOf("bt709", "smpte170m", "bt470bg", "bt2020nc", "bt2020c", "smpte240m", "fcc")
    private val PRIMARIES = setOf("bt709", "smpte170m", "bt470bg", "bt470m", "bt2020", "smpte240m", "smpte431", "smpte432")
    private val TRANSFERS = setOf(
        "bt709", "smpte170m", "bt470bg", "bt470m", "smpte240m", "iec61966-2-1",
        "bt2020-10", "bt2020-12", "smpte2084", "arib-std-b67", "linear",
    )

    /**
     * Colour tags with sane fallbacks: untagged HD is BT.709, untagged SD is BT.601, HDR is BT.2020.
     * "gbr" never applies to a YUV phone video, it only shows up as a bogus default.
     */
    fun sourceColor(info: VideoInfo): ColorTags {
        val sd = info.shortSide < 720
        val fallback = when {
            info.isHdr -> "bt2020"
            sd -> "smpte170m"
            else -> "bt709"
        }
        return ColorTags(
            matrix = info.colorSpace?.takeIf { it in MATRICES } ?: if (info.isHdr) "bt2020nc" else fallback,
            primaries = info.colorPrimaries?.takeIf { it in PRIMARIES } ?: fallback,
            transfer = info.colorTransfer?.takeIf { it in TRANSFERS } ?: if (sd) "smpte170m" else "bt709",
            fullRange = info.colorRange == "pc",
        )
    }

    /** Audio gets less when the whole file is starved: every audio kbit is taken from video. */
    fun autoAudioKbps(totalKbps: Double, channels: Int): Int {
        val stereo = when {
            totalKbps >= 1500 -> 128
            totalKbps >= 600 -> 96
            totalKbps >= 250 -> 64
            else -> 48
        }
        return if (channels == 1) min(stereo, 64) else stereo
    }

    /** Estimated MP4 container cost: sample tables grow with every video and audio frame. */
    fun containerOverheadBytes(seconds: Double, fps: Double, audioSampleRate: Int, hasAudio: Boolean): Long {
        val videoFrames = seconds * fps
        val audioFrames = if (hasAudio) seconds * audioSampleRate / 1024.0 else 0.0
        return (4096 + videoFrames * 14 + audioFrames * 9).roundToLong()
    }

    /** Native FFmpeg AAC tends to land a few percent above the requested bitrate. */
    fun audioBytes(audioKbps: Int, seconds: Double): Long = (audioKbps * 1000 / 8.0 * seconds * 1.03).roundToLong()

    fun videoBudgetKbps(targetBytes: Long, seconds: Double, audioKbps: Int, fps: Double, audioSampleRate: Int): Int {
        val usable = targetBytes * FIRST_TRY_FILL -
            containerOverheadBytes(seconds, fps, audioSampleRate, audioKbps > 0) -
            audioBytes(audioKbps, seconds)
        return (usable * 8 / seconds / 1000).toInt()
    }

    fun bpp(videoKbps: Int, w: Int, h: Int, fps: Double): Double = videoKbps * 1000.0 / (w.toDouble() * h * fps)

    private fun chooseFps(info: VideoInfo, srcFps: Double, videoKbps: Int?, s: CompressSettings, thr: Double): Double {
        when {
            s.fps == CompressSettings.ORIGINAL -> return srcFps
            s.fps > 0 -> return reduceTo(srcFps, s.fps.toDouble())
        }
        var fps = srcFps
        while (fps > 61) fps /= 2
        if (videoKbps == null) return fps

        // Halve high frame rates while even a modest 720p frame would be starved.
        val (rw, rh) = dimensions(info, min(720, info.shortSide))
        while (fps > 32 && bpp(videoKbps, rw, rh, fps) < thr) fps /= 2

        val (lw, lh) = dimensions(info, min(360, info.shortSide))
        if (bpp(videoKbps, lw, lh, fps) < thr) fps = reduceTo(fps, 24.0)
        val (tw, th) = dimensions(info, min(240, info.shortSide))
        if (bpp(videoKbps, tw, th, fps) < thr * 0.7) fps = reduceTo(fps, 15.0)
        return fps
    }

    /** Never raises the rate; prefers exact divisors so motion stays even (60 -> 30, not 24). */
    private fun reduceTo(srcFps: Double, cap: Double): Double {
        if (srcFps <= cap + 0.01) return srcFps
        var f = srcFps
        while (f / 2 >= cap - 0.5) f /= 2
        return if (abs(f - cap) < 1.0) f else cap
    }

    private fun chooseShortSide(info: VideoInfo, videoKbps: Int?, fps: Double, s: CompressSettings, thr: Double): Int {
        val src = info.shortSide
        return when {
            s.resolution == CompressSettings.ORIGINAL -> src
            s.resolution > 0 -> min(src, s.resolution)
            videoKbps == null -> min(src, AUTO_MAX_SHORT_SIDE)
            else -> {
                val top = min(src, AUTO_MAX_SHORT_SIDE)
                val candidates = listOf(top) + STANDARD_SHORT_SIDES.filter { it < top }
                candidates.firstOrNull { side ->
                    val (w, h) = dimensions(info, side)
                    bpp(videoKbps, w, h, fps) >= thr
                } ?: candidates.last()
            }
        }
    }

    /** Output size for a given short side, keeping the aspect ratio and even dimensions. */
    fun dimensions(info: VideoInfo, shortSide: Int): Pair<Int, Int> {
        if (shortSide >= info.shortSide) return even(info.width.toDouble()) to even(info.height.toDouble())
        val k = shortSide.toDouble() / info.shortSide
        return even(info.width * k) to even(info.height * k)
    }

    private fun even(x: Double): Int = max(2, (x / 2).roundToInt() * 2)
}
