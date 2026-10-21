package com.xpress.app

import android.app.Application
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.xpress.app.data.SettingsStore
import com.xpress.app.engine.CompressRequest
import com.xpress.app.engine.CompressSettings
import com.xpress.app.engine.MediaRef
import com.xpress.app.engine.Thumbnails
import com.xpress.app.engine.VideoProbe
import com.xpress.app.engine.uri
import com.xpress.app.job.CompressionService
import com.xpress.app.job.JobManager
import com.xpress.app.job.JobState
import com.xpress.app.ui.AppPlatform
import com.xpress.app.ui.SourceVideo

/** Android side of the shared UI: content Uris in, the gallery out, a foreground service in between. */
class AndroidPlatform(private val app: Application) : AppPlatform {

    private val store = SettingsStore(app)

    override fun loadSettings(): CompressSettings = store.load()

    override fun saveSettings(settings: CompressSettings) = store.save(settings)

    override fun probe(source: MediaRef): SourceVideo {
        val uri = source.uri
        return SourceVideo(source, VideoProbe.displayName(app, uri), VideoProbe.probe(app, uri))
    }

    override fun thumbnails(source: MediaRef, durationMs: Long): List<ImageBitmap> =
        Thumbnails.load(app, source.uri, durationMs).map { it.asImageBitmap() }

    override fun startJob(request: CompressRequest) {
        if (JobManager.begin(request)) {
            ContextCompat.startForegroundService(app, Intent(app, CompressionService::class.java))
        }
    }

    override fun cancelJob() {
        if (JobManager.state.value !is JobState.Running) return
        app.startService(Intent(app, CompressionService::class.java).setAction(CompressionService.ACTION_CANCEL))
    }
}
