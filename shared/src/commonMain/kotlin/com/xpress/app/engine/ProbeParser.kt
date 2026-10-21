package com.xpress.app.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class ProbeException(message: String) : Exception(message)

/** Turns `ffprobe -print_format json -show_format -show_streams` output into [VideoInfo]. */
object ProbeParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** @param fallbackSize file size to use when the container does not report one */
    fun parse(ffprobeJson: String, fallbackSize: Long = 0): VideoInfo {
        val root = runCatching { json.parseToJsonElement(ffprobeJson).jsonObject }
            .getOrElse { throw ProbeException("Формат файла не поддерживается") }
        val format = root.obj("format")
        val streams = root.arr("streams")
        val video = streams.firstOrNull { it.str("codec_type") == "video" && !it.isAttachedPicture() }
            ?: throw ProbeException("В файле нет видеодорожки")
        val audio = streams.firstOrNull { it.str("codec_type") == "audio" }

        val rotation = rotationOf(video)
        var w = video.int("width") ?: 0
        var h = video.int("height") ?: 0
        if (w <= 0 || h <= 0) throw ProbeException("Не удалось определить размер кадра")
        sampleAspect(video.str("sample_aspect_ratio"))?.let { sar -> if (abs(sar - 1.0) > 0.01) w = (w * sar).roundToInt() }
        if (abs(rotation) % 180 == 90) w = h.also { h = w }

        val durationSec = format?.str("duration")?.toDoubleOrNull()
            ?: video.str("duration")?.toDoubleOrNull()
            ?: throw ProbeException("Не удалось определить длительность")

        val fps = rational(video.str("avg_frame_rate"))?.takeIf { it in 1.0..480.0 }
            ?: rational(video.str("r_frame_rate"))?.takeIf { it in 1.0..480.0 }
            ?: 30.0

        return VideoInfo(
            durationMs = (durationSec * 1000).roundToLong(),
            width = w,
            height = h,
            fps = fps,
            videoCodec = video.str("codec_name") ?: "?",
            pixelFormat = video.str("pix_fmt"),
            colorTransfer = video.str("color_transfer"),
            colorPrimaries = video.str("color_primaries"),
            colorSpace = video.str("color_space"),
            colorRange = video.str("color_range"),
            videoBitrate = video.str("bit_rate")?.toLongOrNull(),
            hasAudio = audio != null,
            audioCodec = audio?.str("codec_name"),
            audioChannels = if (audio != null) audio.int("channels") ?: 2 else 0,
            audioSampleRate = audio?.str("sample_rate")?.toIntOrNull() ?: 48000,
            sizeBytes = format?.str("size")?.toLongOrNull() ?: fallbackSize,
            dolbyVisionProfile = sideData(video).firstNotNullOfOrNull { it.int("dv_profile") },
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }
    private fun JsonObject.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.isAttachedPicture(): Boolean = obj("disposition")?.int("attached_pic") == 1

    private fun sideData(stream: JsonObject): List<JsonObject> = stream.arr("side_data_list")

    private fun rotationOf(stream: JsonObject): Int =
        sideData(stream).firstNotNullOfOrNull { it.int("rotation") }
            ?: stream.obj("tags")?.int("rotate")
            ?: 0

    private fun sampleAspect(s: String?): Double? {
        val parts = s?.split(':') ?: return null
        val n = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val d = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return if (n > 0 && d > 0) n / d else null
    }

    private fun rational(s: String?): Double? {
        val parts = s?.split('/') ?: return null
        val n = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val d = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        return if (d > 0) n / d else null
    }
}
