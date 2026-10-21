package com.xpress.app.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.FFprobeKit
import kotlin.math.abs

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
        // FFmpegKit swallows permission errors; surface them before they look like a bad format.
        context.contentResolver.openFileDescriptor(uri, "r")?.close()
            ?: throw ProbeException("Не удалось открыть файл")
        val url = ffmpegInput(context, uri) ?: throw ProbeException("Не удалось открыть файл")
        val media = FFprobeKit.getMediaInformation(url).mediaInformation
            ?: return probeWithRetriever(context, uri)
        return ProbeParser.parse(media.allProperties.toString(), querySize(context, uri))
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
}
