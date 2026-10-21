package com.xpress.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File

/** Filmstrip frames for the trimmer. */
object Thumbnails {

    fun load(context: Context, uri: Uri, durationMs: Long, count: Int = 10): List<Bitmap> {
        val times = (0 until count).map { i -> durationMs * (2 * i + 1) / (2 * count) }
        val platform = withRetriever(context, uri, times)
        // The platform decoder may not handle the stream (10-bit HEVC on some chips, MKV, ...).
        return if (platform.size >= count / 2) platform else withFfmpeg(context, uri, durationMs, count)
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

    private fun withFfmpeg(context: Context, uri: Uri, durationMs: Long, count: Int): List<Bitmap> {
        val dir = File(context.cacheDir, "thumbs").apply { deleteRecursively(); mkdirs() }
        return try {
            val input = ffmpegInput(context, uri) ?: return emptyList()
            val args = FfmpegCommands.thumbnails(input, durationMs, count, File(dir, "t%03d.jpg").absolutePath)
            FFmpegKit.executeWithArguments(args.toTypedArray())
            dir.listFiles().orEmpty().sortedBy { it.name }.mapNotNull { BitmapFactory.decodeFile(it.absolutePath) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
