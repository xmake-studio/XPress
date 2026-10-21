package com.xpress.app.engine

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun Uri.toMediaRef() = MediaRef(toString())

val MediaRef.uri: Uri get() = Uri.parse(value)

/** FFmpeg url for [uri]: plain path for files, a fresh SAF url otherwise (FFmpegKit closes it after each session). */
internal fun ffmpegInput(context: Context, uri: Uri): String? =
    if (uri.scheme == "file") uri.path!! else FFmpegKitConfig.getSafParameterForRead(context, uri)

/** FFmpegKit inside the app process; finished videos go to the gallery. */
class AndroidEncoder(private val context: Context) : EncoderBackend {

    override val workDir: File get() = File(context.cacheDir, "work")

    override fun inputUrl(source: MediaRef): String =
        ffmpegInput(context, source.uri) ?: throw CompressException("Нет доступа к исходному видео")

    override suspend fun ffmpeg(args: List<String>, onStats: (timeMs: Double, fps: Float) -> Unit) {
        suspendCancellableCoroutine { cont ->
            val session: FFmpegSession = FFmpegKit.executeWithArgumentsAsync(
                args.toTypedArray(),
                { s ->
                    when {
                        ReturnCode.isSuccess(s.returnCode) -> if (cont.isActive) cont.resume(Unit)
                        ReturnCode.isCancel(s.returnCode) -> cont.cancel(CancellationException("cancelled"))
                        else -> if (cont.isActive) cont.resumeWithException(
                            CompressException("Ошибка кодирования", logTail(s.logsAsString))
                        )
                    }
                },
                { /* logs are kept in the session; no logcat spam */ },
                { st -> onStats(st.time, st.videoFps) },
            )
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
    }

    override fun save(file: File, displayName: String): SavedVideo {
        val uri = MediaSaver.saveVideo(context, file, displayName)
        return SavedVideo(uri.toMediaRef(), "Movies/${MediaSaver.ALBUM}")
    }
}
