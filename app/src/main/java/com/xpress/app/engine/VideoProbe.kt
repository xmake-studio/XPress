package com.xpress.app.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.StreamInformation
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class ProbeException(message: String) : Exception(message)

object VideoProbe {

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "video"
    }

    private fun querySize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull() ?: 0L

    fun probe(context: Context, uri: Uri): VideoInfo {
        val url = if (uri.scheme == "file") uri.path!! else FFmpegKitConfig.getSafParameterForRead(context, uri)
            ?: throw ProbeException("Не удалось открыть файл")
        val media = FFprobeKit.getMediaInformation(url).mediaInformation
            ?: return probeWithRetriever(context, uri)

        val streams = media.streams.orEmpty()
        val video = streams.firstOrNull { it.type == "video" && !it.isAttachedPicture() }
            ?: throw ProbeException("В файле нет видеодорожки")
        val audio = streams.firstOrNull { it.type == "audio" }
        val props = video.allProperties ?: JSONObject()

        val rotation = rotationOf(props)
        var w = video.width?.toInt() ?: 0
        var h = video.height?.toInt() ?: 0
        if (w <= 0 || h <= 0) throw ProbeException("Не удалось определить размер кадра")
        sampleAspect(video.sampleAspectRatio)?.let { sar -> if (abs(sar - 1.0) > 0.01) w = (w * sar).roundToInt() }
        if (abs(rotation) % 180 == 90) w = h.also { h = w }

        val durationSec = media.duration?.toDoubleOrNull()
            ?: props.optString("duration").toDoubleOrNull()
            ?: throw ProbeException("Не удалось определить длительность")

        val fps = rational(video.averageFrameRate)?.takeIf { it in 1.0..480.0 }
            ?: rational(video.realFrameRate)?.takeIf { it in 1.0..480.0 }
            ?: 30.0

        return VideoInfo(
            durationMs = (durationSec * 1000).roundToLong(),
            width = w,
            height = h,
            fps = fps,
            videoCodec = video.codec ?: "?",
            pixelFormat = props.optString("pix_fmt").ifEmpty { null },
            colorTransfer = props.optString("color_transfer").ifEmpty { null },
            colorPrimaries = props.optString("color_primaries").ifEmpty { null },
            colorSpace = props.optString("color_space").ifEmpty { null },
            colorRange = props.optString("color_range").ifEmpty { null },
            videoBitrate = video.bitrate?.toLongOrNull(),
            hasAudio = audio != null,
            audioCodec = audio?.codec,
            audioChannels = audio?.allProperties?.optInt("channels", 2) ?: 0,
            audioSampleRate = audio?.sampleRate?.toIntOrNull() ?: 48000,
            sizeBytes = media.size?.toLongOrNull() ?: querySize(context, uri),
            dolbyVisionProfile = dolbyVisionProfile(props),
        )
    }

    /** Fallback for files FFprobe could not parse: the platform extractor still knows the basics. */
    private fun probeWithRetriever(context: Context, uri: Uri): VideoInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            fun key(k: Int) = r.extractMetadata(k)
            var w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) throw ProbeException("Формат файла не поддерживается")
            val rotation = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (abs(rotation) % 180 == 90) w = h.also { h = w }
            val durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frames = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toDoubleOrNull()
            val fps = if (frames != null && durationMs > 0) frames * 1000 / durationMs else 30.0
            return VideoInfo(
                durationMs = durationMs,
                width = w,
                height = h,
                fps = fps,
                videoCodec = key(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "?",
                hasAudio = key(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                sizeBytes = querySize(context, uri),
            )
        } catch (e: ProbeException) {
            throw e
        } catch (e: Exception) {
            throw ProbeException("Формат файла не поддерживается")
        } finally {
            r.release()
        }
    }

    private fun StreamInformation.isAttachedPicture(): Boolean =
        allProperties?.optJSONObject("disposition")?.optInt("attached_pic") == 1

    private fun rotationOf(props: JSONObject): Int {
        props.optJSONArray("side_data_list")?.let { list ->
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                if (item.has("rotation")) return item.optInt("rotation")
            }
        }
        return props.optJSONObject("tags")?.optString("rotate")?.toIntOrNull() ?: 0
    }

    private fun dolbyVisionProfile(props: JSONObject): Int? {
        val list = props.optJSONArray("side_data_list") ?: return null
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (item.has("dv_profile")) return item.optInt("dv_profile")
        }
        return null
    }

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
