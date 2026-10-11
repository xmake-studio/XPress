package com.xpress.app.job

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.xpress.app.MainActivity
import com.xpress.app.R
import com.xpress.app.engine.CompressException
import com.xpress.app.engine.Compressor
import com.xpress.app.ui.formatBytes
import com.xpress.app.ui.formatEta
import com.xpress.app.ui.stageLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps the encode alive while the app is in the background and shows its progress. */
class CompressionService : LifecycleService() {

    companion object {
        const val ACTION_CANCEL = "com.xpress.app.CANCEL"
        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_DONE = "done"
        private const val ID_PROGRESS = 1
        private const val ID_DONE = 2

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Сжатие видео", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Готовые видео", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifiedPercent = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(buildProgress("Подготовка…", 0, null))
        if (job?.isActive == true) return START_NOT_STICKY
        val request = JobManager.takePending() ?: run { stopSelf(); return START_NOT_STICKY }

        acquireWakeLock()
        job = lifecycleScope.launch {
            val state = try {
                val result = withContext(Dispatchers.Default) {
                    Compressor(applicationContext).compress(request) { p ->
                        JobManager.update(JobState.Running(request, p))
                        notifyProgress(stageLabel(p.stage, p.attempt), (p.fraction * 100).toInt(), p.etaSeconds)
                    }
                }
                notifyDone("Готово: ${formatBytes(result.sizeBytes)}", result.outputName)
                JobState.Done(request, result)
            } catch (e: CancellationException) {
                JobState.Idle
            } catch (e: CompressException) {
                notifyDone("Не удалось сжать видео", e.message ?: "")
                JobState.Failed(request, e.message ?: "Ошибка", e.details)
            } catch (e: Exception) {
                notifyDone("Не удалось сжать видео", e.message ?: "")
                JobState.Failed(request, "Непредвиденная ошибка", e.stackTraceToString().take(4000))
            }
            JobManager.update(state)
            releaseWakeLock()
            ServiceCompat.stopForeground(this@CompressionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(this, ID_PROGRESS, notification, type)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildProgress(text: String, percent: Int, eta: Long?): Notification {
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, CompressionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Сжатие видео · $percent%")
            .setContentText(if (eta != null) "$text · осталось ${formatEta(eta)}" else text)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Отмена", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyProgress(text: String, percent: Int, eta: Long?) {
        if (percent == lastNotifiedPercent) return
        lastNotifiedPercent = percent
        if (canNotify()) NotificationManagerCompat.from(this).notify(ID_PROGRESS, buildProgress(text, percent, eta))
    }

    private fun notifyDone(title: String, text: String) {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        NotificationManagerCompat.from(this).notify(ID_DONE, n)
    }

    private fun canNotify(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun acquireWakeLock() {
        // Encoding with the screen off would otherwise stall when the CPU goes to sleep.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XPress:compress")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }
}
