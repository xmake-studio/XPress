package com.xpress.app.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Builds FFmpeg argument lists for an [EncodePlan]. Pure, so it can be unit tested on the JVM. */
object FfmpegCommands {

    enum class Pass { SINGLE, FIRST, SECOND }

    /**
     * @param input FFmpeg input url (a `saf:` url on Android, a path in tests)
     * @param output output .mp4 path, ignored for [Pass.FIRST]
     * @param passLog prefix for two-pass statistics files
     * @param videoKbps overrides the plan's bitrate when the compressor refines the size
     */
    fun encode(
        plan: EncodePlan,
        input: String,
        output: String,
        pass: Pass,
        passLog: String,
        videoKbps: Int? = plan.videoKbps,
    ): List<String> = buildList {
        addAll(listOf("-hide_banner", "-nostdin", "-y"))
        if (plan.startMs > 0) addAll(listOf("-ss", seconds(plan.startMs)))
        if (plan.startMs > 0 || plan.clipEnd) addAll(listOf("-t", seconds(plan.durationMs)))
        addAll(listOf("-i", input))

        addAll(listOf("-map", "0:v:0"))
        val withAudio = plan.hasAudio && pass != Pass.FIRST
        if (withAudio) addAll(listOf("-map", "0:a:0?"))
        // Strip GPS location, device names and other phone metadata before it lands in a chat.
        addAll(listOf("-map_metadata", "-1", "-map_chapters", "-1", "-sn", "-dn"))

        addAll(listOf("-vf", videoFilter(plan)))
        if (plan.tonemap) {
            addAll(listOf("-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-color_range", "tv"))
        }
        addAll(videoCodecArgs(plan, pass, passLog, videoKbps))

        if (withAudio) {
            addAll(listOf("-c:a", "aac", "-b:a", "${plan.audioKbps}k", "-ac", plan.audioChannels.toString()))
            addAll(listOf("-ar", plan.audioSampleRate.toString()))
        } else {
            add("-an")
        }

        if (pass == Pass.FIRST) {
            addAll(listOf("-f", "null", "-"))
        } else {
            addAll(listOf("-movflags", "+faststart", "-f", "mp4", output))
        }
    }

    fun videoFilter(plan: EncodePlan): String {
        val filters = mutableListOf<String>()
        // Drop frames first so the expensive scaling/tonemapping runs on fewer of them.
        if (plan.reduceFps) filters += "fps=${fpsExpr(plan.fps)}"
        if (plan.tonemap) {
            filters += "zscale=w=${plan.width}:h=${plan.height}:f=spline36:tin=${plan.hdrTransfer}:pin=bt2020:min=bt2020nc"
            filters += "zscale=t=linear:npl=100"
            filters += "format=gbrpf32le"
            filters += "zscale=p=bt709"
            filters += "tonemap=tonemap=hable:desat=0"
            filters += "zscale=t=bt709:m=bt709:r=tv"
        } else if (plan.scale) {
            filters += "scale=${plan.width}:${plan.height}:flags=lanczos"
        }
        filters += "format=yuv420p"
        return filters.joinToString(",")
    }

    private fun videoCodecArgs(plan: EncodePlan, pass: Pass, passLog: String, videoKbps: Int?): List<String> = buildList {
        val gop = (plan.fps * 10).roundToInt().coerceAtLeast(1)
        when (plan.codec) {
            VideoCodec.H264 -> {
                addAll(listOf("-c:v", "libx264", "-preset", x264Preset(plan.speed), "-profile:v", "high"))
                rateControl(plan, videoKbps)
                // Auto-variance AQ with dark-scene bias: fewer blocks in shadows at low bitrates.
                addAll(listOf("-x264-params", "aq-mode=3", "-g", gop.toString()))
                passArgs(pass, passLog)
            }
            VideoCodec.HEVC -> {
                addAll(listOf("-c:v", "libx265", "-preset", x265Preset(plan.speed), "-profile:v", "main"))
                rateControl(plan, videoKbps)
                val params = mutableListOf("log-level=error", "keyint=$gop")
                when (pass) {
                    Pass.FIRST -> params += listOf("pass=1", "stats=$passLog.x265")
                    Pass.SECOND -> params += listOf("pass=2", "stats=$passLog.x265")
                    Pass.SINGLE -> Unit
                }
                addAll(listOf("-x265-params", params.joinToString(":")))
                // hvc1 tag is what Apple players and browsers look for.
                addAll(listOf("-tag:v", "hvc1"))
            }
            VideoCodec.AV1 -> {
                addAll(listOf("-c:v", "libaom-av1", "-usage", "good", "-cpu-used", aomCpuUsed(plan.speed)))
                addAll(listOf("-row-mt", "1", "-tiles", "2x2", "-g", gop.toString()))
                if (plan.crf != null) {
                    addAll(listOf("-crf", plan.crf.toString(), "-b:v", "0"))
                } else {
                    addAll(listOf("-b:v", "${videoKbps}k"))
                }
                passArgs(pass, passLog)
            }
        }
    }

    private fun MutableList<String>.rateControl(plan: EncodePlan, videoKbps: Int?) {
        if (plan.crf != null) addAll(listOf("-crf", plan.crf.toString()))
        else addAll(listOf("-b:v", "${videoKbps}k"))
    }

    private fun MutableList<String>.passArgs(pass: Pass, passLog: String) {
        when (pass) {
            Pass.FIRST -> addAll(listOf("-pass", "1", "-passlogfile", passLog))
            Pass.SECOND -> addAll(listOf("-pass", "2", "-passlogfile", passLog))
            Pass.SINGLE -> Unit
        }
    }

    fun x264Preset(speed: EncodeSpeed) = when (speed) {
        EncodeSpeed.FAST -> "medium"
        EncodeSpeed.BALANCED -> "slow"
        EncodeSpeed.BEST -> "veryslow"
    }

    fun x265Preset(speed: EncodeSpeed) = when (speed) {
        EncodeSpeed.FAST -> "fast"
        EncodeSpeed.BALANCED -> "medium"
        EncodeSpeed.BEST -> "slow"
    }

    fun aomCpuUsed(speed: EncodeSpeed) = when (speed) {
        EncodeSpeed.FAST -> "8"
        EncodeSpeed.BALANCED -> "6"
        EncodeSpeed.BEST -> "4"
    }

    /** NTSC rates as exact fractions, so 29.97 does not drift into 29.970000001. */
    fun fpsExpr(fps: Double): String {
        for (base in intArrayOf(24, 30, 48, 60, 120)) {
            if (abs(fps - base * 1000 / 1001.0) < 0.005) return "${base * 1000}/1001"
        }
        if (abs(fps - fps.roundToInt()) < 0.01) return fps.roundToInt().toString()
        return String.format(Locale.US, "%.3f", fps)
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.3f", ms / 1000.0)
}
