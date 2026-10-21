package com.xpress.app

import androidx.compose.ui.graphics.ImageBitmap
import com.xpress.app.engine.CompressRequest
import com.xpress.app.engine.CompressSettings
import com.xpress.app.engine.Compressor
import com.xpress.app.engine.DesktopEncoder
import com.xpress.app.engine.DesktopMedia
import com.xpress.app.engine.MediaRef
import com.xpress.app.job.JobManager
import com.xpress.app.ui.AppPlatform
import com.xpress.app.ui.SourceVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.prefs.Preferences

/** Desktop side of the shared UI: plain files in, Videos\XPress out, the encode on a background coroutine. */
class DesktopPlatform(private val scope: CoroutineScope) : AppPlatform {

    /** Lives in HKCU\Software\JavaSoft\Prefs on Windows. */
    private val prefs = Preferences.userRoot().node("com/xpress/app")

    private val compressor = Compressor(DesktopEncoder(outputDir = { File(WindowsShell.videosFolder, "XPress") }))

    private var job: Job? = null

    /** Where the Open dialog starts next time. */
    var lastFolder: File?
        get() = prefs.get("lastFolder", null)?.let(::File)
        set(value) = if (value == null) prefs.remove("lastFolder") else prefs.put("lastFolder", value.absolutePath)

    override fun loadSettings(): CompressSettings {
        val d = CompressSettings()
        return CompressSettings(
            mode = enumOr(prefs.get("mode", null), d.mode),
            targetSizeMb = prefs.getDouble("targetSizeMb", d.targetSizeMb),
            videoBitrateKbps = prefs.getInt("videoBitrateKbps", d.videoBitrateKbps),
            quality = prefs.getInt("quality", d.quality),
            codec = enumOr(prefs.get("codec", null), d.codec),
            resolution = prefs.getInt("resolution", d.resolution),
            fps = prefs.getInt("fps", d.fps),
            audioKbps = prefs.getInt("audioKbps", d.audioKbps),
            speed = enumOr(prefs.get("speed", null), d.speed),
        )
    }

    override fun saveSettings(settings: CompressSettings) {
        prefs.put("mode", settings.mode.name)
        prefs.putDouble("targetSizeMb", settings.targetSizeMb)
        prefs.putInt("videoBitrateKbps", settings.videoBitrateKbps)
        prefs.putInt("quality", settings.quality)
        prefs.put("codec", settings.codec.name)
        prefs.putInt("resolution", settings.resolution)
        prefs.putInt("fps", settings.fps)
        prefs.putInt("audioKbps", settings.audioKbps)
        prefs.put("speed", settings.speed.name)
    }

    override fun probe(source: MediaRef): SourceVideo {
        val file = File(source.value)
        return SourceVideo(source, file.name, DesktopMedia.probe(file))
    }

    override fun thumbnails(source: MediaRef, durationMs: Long): List<ImageBitmap> =
        DesktopMedia.thumbnails(File(source.value), durationMs)

    override fun startJob(request: CompressRequest) {
        if (!JobManager.begin(request)) return
        JobManager.takePending()
        job = scope.launch {
            WindowsShell.keepAwake(true)
            try {
                JobManager.update(JobManager.execute(request, compressor))
            } finally {
                WindowsShell.keepAwake(false)
            }
        }
    }

    override fun cancelJob() {
        job?.cancel()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
}
