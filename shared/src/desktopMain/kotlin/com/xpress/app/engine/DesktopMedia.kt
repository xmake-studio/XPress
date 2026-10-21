package com.xpress.app.engine

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.File
import java.nio.file.Files

/** Probing, the trimmer filmstrip and preview stills, all through the bundled FFmpeg. */
object DesktopMedia {

    fun probe(file: File): VideoInfo {
        if (!file.isFile) throw ProbeException("Файл не найден")
        val out = Ffmpeg.capture(
            listOf(Ffmpeg.ffprobe, "-v", "error", "-hide_banner", "-print_format", "json", "-show_format", "-show_streams", file.absolutePath)
        )
        if (out.exitCode != 0) throw ProbeException("Формат файла не поддерживается")
        return ProbeParser.parse(out.stdout.decodeToString(), file.length())
    }

    fun thumbnails(file: File, durationMs: Long, count: Int = 10): List<ImageBitmap> {
        val keyframes = keyframeThumbnails(file, durationMs, count)
        if (keyframes.size >= count / 2) return keyframes
        // Long GOPs (screen recordings, downloads) have too few keyframes: seek to each spot instead.
        // Every seek decodes up to a whole GOP, so they run side by side.
        val times = (0 until count).map { i -> durationMs * (2 * i + 1) / (2 * count) }
        return times.parallelStream()
            .map { t -> frame(file, null, t, exact = true, shortSide = 160) }
            .toList()
            .filterNotNull()
    }

    private fun keyframeThumbnails(file: File, durationMs: Long, count: Int): List<ImageBitmap> {
        val dir = Files.createTempDirectory("xpress-thumbs").toFile()
        return try {
            Ffmpeg.capture(listOf(Ffmpeg.ffmpeg) + FfmpegCommands.thumbnails(file.absolutePath, durationMs, count, File(dir, "t%03d.jpg").absolutePath))
            dir.listFiles().orEmpty().sortedBy { it.name }.mapNotNull { decode(it.readBytes()) }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Still frame at [atMs], or null when there is none (past the last frame, unreadable file). */
    fun frame(file: File, info: VideoInfo?, atMs: Long, exact: Boolean, shortSide: Int = 720): ImageBitmap? {
        val out = Ffmpeg.capture(listOf(Ffmpeg.ffmpeg) + FfmpegCommands.previewFrame(info, file.absolutePath, atMs, exact, shortSide))
        return if (out.exitCode == 0) decode(out.stdout) else null
    }

    private fun decode(bytes: ByteArray): ImageBitmap? =
        if (bytes.isEmpty()) null else runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}
