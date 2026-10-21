package com.xpress.app.engine

import java.io.File
import java.nio.file.Files

/** Encodes with the bundled FFmpeg; finished videos land in [outputDir] without overwriting anything there. */
class DesktopEncoder(
    private val outputDir: () -> File,
    override val workDir: File = File(System.getProperty("java.io.tmpdir"), "XPress/work-${ProcessHandle.current().pid()}"),
) : EncoderBackend {

    override fun inputUrl(source: MediaRef): String = source.value

    // x265 reads its stats path from a ':'-separated option string, and a drive letter breaks it.
    // FFmpeg runs inside workDir, so a relative prefix is enough.
    override fun passLog(workDir: File): String = "pass"

    // Right after a cancelled encode Windows may still hold its files for a moment.
    override fun clearWorkDir() {
        repeat(30) {
            if (workDir.deleteRecursively()) return
            Thread.sleep(100)
        }
    }

    override suspend fun ffmpeg(args: List<String>, onStats: (timeMs: Double, fps: Float) -> Unit) =
        Ffmpeg.encode(args, workDir, onStats)

    override fun save(file: File, displayName: String): SavedVideo {
        val dir = outputDir().apply { mkdirs() }
        val target = freeName(dir, displayName)
        Files.move(file.toPath(), target.toPath())
        return SavedVideo(MediaRef(target.absolutePath), dir.absolutePath)
    }

    /** `name.mp4`, then `name (2).mp4` and so on. */
    private fun freeName(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        return generateSequence(1) { it + 1 }
            .map { n -> File(dir, if (n == 1) name else "$base ($n).$ext") }
            .first { !it.exists() }
    }
}
