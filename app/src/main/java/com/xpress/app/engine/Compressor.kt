package com.xpress.app.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class CompressRequest(
    val uri: Uri,
    val displayName: String,
    val info: VideoInfo,
    val trim: TrimRange,
    val settings: CompressSettings,
)

enum class Stage { PREPARING, ANALYSIS, ENCODING, REFINING, SAVING }

data class Progress(
    val stage: Stage,
    /** Overall progress 0..1. */
    val fraction: Float,
    val encodeFps: Float = 0f,
    val etaSeconds: Long? = null,
    val attempt: Int = 1,
)

data class CompressResult(
    val outputUri: Uri,
    val outputName: String,
    val sizeBytes: Long,
    val plan: EncodePlan,
    val finalVideoKbps: Int?,
    val attempts: Int,
    val elapsedMs: Long,
)

class CompressException(message: String, val details: String? = null) : Exception(message)

/**
 * Runs the encode. In target-size mode it is a two-pass encode followed by a size check:
 * a miss in either direction re-runs only the second pass with a corrected bitrate, reusing
 * the first-pass statistics, so the file ends up as close to the limit as possible without
 * crossing it.
 */
class Compressor(private val context: Context) {

    private companion object {
        const val MAX_ATTEMPTS = 4
        /** Good enough: closer than this to the limit is not worth another full pass. */
        const val ACCEPT_FILL = 0.955
        const val REFINE_FILL = 0.985
        const val OVERSHOOT_FILL = 0.975
        const val PASS1_WEIGHT = 0.3f
    }

    suspend fun compress(req: CompressRequest, onProgress: (Progress) -> Unit): CompressResult {
        val started = SystemClock.elapsedRealtime()
        val plan = EncodePlanner.plan(req.info, req.trim, req.settings)
        val work = File(context.cacheDir, "work").apply { deleteRecursively(); mkdirs() }
        val passLog = File(work, "pass").absolutePath
        val seconds = plan.durationMs / 1000.0

        fun report(stage: Stage, stageFraction: Float, fps: Float, from: Float, to: Float, attempt: Int = 1) {
            val f = (from + (to - from) * stageFraction.coerceIn(0f, 1f))
            val elapsed = (SystemClock.elapsedRealtime() - started) / 1000.0
            val eta = if (f > 0.03f) ((elapsed / f) * (1 - f)).toLong() else null
            onProgress(Progress(stage, f, fps, eta, attempt))
        }

        try {
            onProgress(Progress(Stage.PREPARING, 0f))
            val output: File
            var kbps = plan.videoKbps
            var attempts = 1

            if (!plan.twoPass) {
                output = File(work, "out.mp4")
                run(FfmpegCommands.encode(plan, input(req.uri), output.absolutePath, FfmpegCommands.Pass.SINGLE, passLog), plan) { p, fps ->
                    report(Stage.ENCODING, p, fps, 0f, 1f)
                }
            } else {
                run(FfmpegCommands.encode(plan, input(req.uri), "", FfmpegCommands.Pass.FIRST, passLog), plan) { p, fps ->
                    report(Stage.ANALYSIS, p, fps, 0f, PASS1_WEIGHT)
                }
                val limit = plan.targetBytes
                var best: Pair<File, Int>? = null
                // Size of the last fitting attempt we raised the bitrate from.
                var raisedFrom: Long? = null
                var attempt = 1
                while (true) {
                    val out = File(work, "out_$attempt.mp4")
                    val stage = if (attempt == 1) Stage.ENCODING else Stage.REFINING
                    val (from, to) = if (attempt == 1) PASS1_WEIGHT to 1f else 0f to 1f
                    run(FfmpegCommands.encode(plan, input(req.uri), out.absolutePath, FfmpegCommands.Pass.SECOND, passLog, kbps), plan) { p, fps ->
                        report(stage, p, fps, from, to, attempt)
                    }
                    val size = out.length()
                    if (limit == null) { best = out to kbps!!; break }

                    val fixed = EncodePlanner.audioBytes(plan.audioKbps, seconds) +
                        EncodePlanner.containerOverheadBytes(seconds, plan.fps, plan.audioSampleRate, plan.hasAudio)
                    if (size <= limit) {
                        if (best == null || size > best.first.length()) best = out to kbps!!
                        // Stop when close enough, or when a higher bitrate no longer grows the file
                        // (the encoder already spends as much as the content can use).
                        val stalled = raisedFrom != null && size <= raisedFrom * 1.01
                        if (size >= limit * ACCEPT_FILL || attempt >= MAX_ATTEMPTS || stalled) break
                        kbps = scaled(kbps!!, size, (limit * REFINE_FILL).toLong(), fixed, up = true)
                        raisedFrom = size
                    } else {
                        // The previous attempt fit: keep it rather than spending more passes.
                        if (best != null || attempt >= MAX_ATTEMPTS) break
                        kbps = scaled(kbps!!, size, (limit * OVERSHOOT_FILL).toLong(), fixed, up = false)
                    }
                    attempt++
                }
                attempts = attempt
                val chosen = best ?: throw CompressException("Не удалось уложиться в заданный размер. Попробуйте обрезать видео.")
                output = chosen.first
                kbps = chosen.second
            }

            onProgress(Progress(Stage.SAVING, 1f))
            val name = outputName(req.displayName)
            val uri = MediaSaver.saveVideo(context, output, name)
            val size = output.length()
            work.deleteRecursively()
            return CompressResult(uri, name, size, plan, kbps, attempts, SystemClock.elapsedRealtime() - started)
        } catch (e: Throwable) {
            work.deleteRecursively()
            throw e
        }
    }

    /** Linear correction of the video part only; audio and container overhead do not scale with bitrate. */
    private fun scaled(kbps: Int, size: Long, goal: Long, fixed: Long, up: Boolean): Int {
        val videoNow = (size - fixed).coerceAtLeast(1)
        val videoGoal = (goal - fixed).coerceAtLeast(1)
        var ratio = videoGoal.toDouble() / videoNow
        ratio = if (up) ratio.coerceIn(1.0, 1.6) else ratio.coerceIn(0.4, 0.995)
        return (kbps * ratio).roundToInt().coerceAtLeast(16)
    }

    /** A fresh SAF url per run: FFmpegKit closes the descriptor when a session ends. */
    private fun input(uri: Uri): String =
        if (uri.scheme == "file") uri.path!! else FFmpegKitConfig.getSafParameterForRead(context, uri)

    private suspend fun run(args: List<String>, plan: EncodePlan, onProgress: (Float, Float) -> Unit) {
        val total = plan.durationMs.toDouble().coerceAtLeast(1.0)
        suspendCancellableCoroutine { cont ->
            val session: FFmpegSession = FFmpegKit.executeWithArgumentsAsync(
                args.toTypedArray(),
                { s ->
                    when {
                        ReturnCode.isSuccess(s.returnCode) -> if (cont.isActive) cont.resume(Unit)
                        ReturnCode.isCancel(s.returnCode) -> cont.cancel(CancellationException("cancelled"))
                        else -> if (cont.isActive) cont.resumeWithException(
                            CompressException("Ошибка кодирования", tail(s.logsAsString))
                        )
                    }
                },
                { /* logs are kept in the session; no logcat spam */ },
                { st -> onProgress((st.time / total).toFloat(), st.videoFps) },
            )
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
    }

    private fun tail(log: String?, lines: Int = 25): String? =
        log?.lineSequence()?.filter { it.isNotBlank() }?.toList()?.takeLast(lines)?.joinToString("\n")

    private fun outputName(source: String): String {
        val base = source.substringBeforeLast('.').ifBlank { "video" }.take(80)
        return "${base}_xpress.mp4"
    }
}
