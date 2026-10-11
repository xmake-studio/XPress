package com.xpress.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale

/** Filmstrip frames for the trimmer. */
object Thumbnails {

    fun load(context: Context, uri: Uri, durationMs: Long, count: Int = 10): List<Bitmap> {
        val times = (0 until count).map { i -> durationMs * (2 * i + 1) / (2 * count) }
        val platform = withRetriever(context, uri, times)
        // The platform decoder may not handle the stream (10-bit HEVC on some chips, MKV, ...).
        return if (platform.size >= count / 2) platform else withFfmpeg(context, uri, times)
    }

    private fun withRetriever(context: Context, uri: Uri, times: List<Long>): List<Bitmap> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            times.mapNotNull { t ->
                runCatching {
                    r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 192)
                }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            r.release()
        }
    }

    /** Keyframes only, so each frame costs one GOP seek and a single decode. */
    private fun withFfmpeg(context: Context, uri: Uri, times: List<Long>): List<Bitmap> {
        val dir = File(context.cacheDir, "thumbs").apply { deleteRecursively(); mkdirs() }
        return try {
            times.mapIndexedNotNull { i, t ->
                val out = File(dir, "t$i.jpg")
                val input = if (uri.scheme == "file") uri.path!! else FFmpegKitConfig.getSafParameterForRead(context, uri)
                val session = FFmpegKit.executeWithArguments(
                    arrayOf(
                        "-hide_banner", "-nostdin", "-y", "-skip_frame", "nokey",
                        "-ss", String.format(Locale.US, "%.3f", t / 1000.0), "-i", input,
                        "-frames:v", "1", "-vf", "scale=-2:160", "-q:v", "6", out.absolutePath,
                    )
                )
                if (ReturnCode.isSuccess(session.returnCode)) BitmapFactory.decodeFile(out.absolutePath) else null
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
