package com.xpress.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The FFmpeg build that ships with the desktop app, run as child processes.
 *
 * Lookup order: `-Dxpress.ffmpeg.dir` (tests, dev runs), the app's resources folder in an
 * installed build, then whatever `ffmpeg` is on PATH.
 */
object Ffmpeg {

    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private val dir: File? by lazy {
        listOfNotNull(System.getProperty("xpress.ffmpeg.dir"), System.getProperty("compose.application.resources.dir"))
            .map(::File)
            .firstOrNull { File(it, exe("ffmpeg")).isFile }
    }

    val ffmpeg: String get() = path("ffmpeg")
    val ffprobe: String get() = path("ffprobe")

    private fun exe(name: String) = if (windows) "$name.exe" else name
    private fun path(name: String) = dir?.let { File(it, exe(name)).absolutePath } ?: name

    class Output(val exitCode: Int, val stdout: ByteArray, val stderr: String)

    /** Runs a short command (probe, still frame) to completion. */
    fun capture(command: List<String>, timeoutSeconds: Long = 60): Output {
        val proc = start(command, null)
        val err = StringBuilder()
        var out = ByteArray(0)
        val errReader = thread(isDaemon = true) { proc.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
        val outReader = thread(isDaemon = true) { out = proc.inputStream.readBytes() }
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IOException("FFmpeg did not finish in ${timeoutSeconds}s")
        }
        errReader.join()
        outReader.join()
        return Output(proc.exitValue(), out, err.toString())
    }

    /**
     * Runs an encode in [workDir], reporting encoded media time (ms) and speed from FFmpeg's
     * machine-readable progress on stdout. Cancelling the coroutine kills the process.
     */
    suspend fun encode(args: List<String>, workDir: File, onStats: (timeMs: Double, fps: Float) -> Unit) {
        val proc = start(listOf(ffmpeg, "-progress", "pipe:1", "-nostats") + args, workDir)
        val log = ArrayDeque<String>()
        val errReader = thread(isDaemon = true) {
            proc.errorStream.bufferedReader().forEachLine { line ->
                synchronized(log) {
                    log.addLast(line)
                    if (log.size > 200) log.removeFirst()
                }
            }
        }
        val outReader = thread(isDaemon = true) {
            var fps = 0f
            proc.inputStream.bufferedReader().forEachLine { line ->
                val key = line.substringBefore('=')
                val value = line.substringAfter('=')
                when (key) {
                    "fps" -> fps = value.toFloatOrNull() ?: fps
                    // Despite the name, out_time_ms is in microseconds too.
                    "out_time_us" -> value.toLongOrNull()?.let { onStats(it / 1000.0, fps) }
                }
            }
        }
        try {
            val code = runInterruptible(Dispatchers.IO) {
                proc.waitFor().also {
                    // Both pipes end with the process; drain them before reading the log.
                    errReader.join()
                    outReader.join()
                }
            }
            if (code != 0) {
                throw CompressException("Ошибка кодирования", logTail(synchronized(log) { log.joinToString("\n") }))
            }
        } finally {
            // Normally a no-op; on cancellation this is what stops the encoder. Waiting for it
            // matters on Windows: its open files cannot be deleted until the process is gone.
            if (proc.isAlive) proc.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        }
    }

    private fun start(command: List<String>, workDir: File?): Process =
        try {
            ProcessBuilder(command).directory(workDir).start().also { it.outputStream.close() }
        } catch (e: IOException) {
            throw CompressException("FFmpeg не найден. Переустановите XPress.", e.message)
        }
}
