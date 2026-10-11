package com.xpress.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.io.File
import java.util.Locale

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

    /**
     * One pass over keyframes only, keeping one per interval: no per-frame seeking, so it works
     * whatever the GOP layout and costs a single keyframe decode per thumbnail.
     */
    private fun withFfmpeg(context: Context, uri: Uri, durationMs: Long, count: Int): List<Bitmap> {
        val dir = File(context.cacheDir, "thumbs").apply { deleteRecursively(); mkdirs() }
        return try {
            val input = if (uri.scheme == "file") uri.path!! else FFmpegKitConfig.getSafParameterForRead(context, uri)
            val interval = String.format(Locale.US, "%.3f", durationMs / 1000.0 / count)
            FFmpegKit.executeWithArguments(
                arrayOf(
                    "-hide_banner", "-nostdin", "-y", "-skip_frame", "nokey", "-i", input, "-an",
                    "-vf", "select='isnan(prev_selected_t)+gte(t-prev_selected_t,$interval)',scale=-2:160",
                    "-fps_mode", "vfr", "-frames:v", count.toString(), "-q:v", "6",
                    File(dir, "t%03d.jpg").absolutePath,
                )
            )
            dir.listFiles().orEmpty().sortedBy { it.name }.mapNotNull { BitmapFactory.decodeFile(it.absolutePath) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
