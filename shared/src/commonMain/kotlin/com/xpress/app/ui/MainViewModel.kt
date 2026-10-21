package com.xpress.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xpress.app.engine.CompressRequest
import com.xpress.app.engine.CompressSettings
import com.xpress.app.engine.EncodePlan
import com.xpress.app.engine.EncodePlanner
import com.xpress.app.engine.MediaRef
import com.xpress.app.engine.ProbeException
import com.xpress.app.engine.TrimRange
import com.xpress.app.engine.VideoInfo
import com.xpress.app.job.JobManager
import com.xpress.app.job.JobState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SourceVideo(val source: MediaRef, val name: String, val info: VideoInfo)

sealed interface SourceState {
    data object None : SourceState
    data object Loading : SourceState
    data class Ready(val video: SourceVideo) : SourceState
    data class Error(val message: String) : SourceState
}

/** What the shared UI needs from the platform it runs on. Calls may block; the view model moves them off the main thread. */
interface AppPlatform {
    fun loadSettings(): CompressSettings
    fun saveSettings(settings: CompressSettings)

    /** Name and stream facts of [source]; throws [ProbeException] for files it cannot use. */
    fun probe(source: MediaRef): SourceVideo
    fun thumbnails(source: MediaRef, durationMs: Long): List<ImageBitmap>

    /** Starts [request] in the background; progress arrives through [JobManager.state]. */
    fun startJob(request: CompressRequest)
    fun cancelJob()
}

class MainViewModel(private val platform: AppPlatform) : ViewModel() {

    private val _source = MutableStateFlow<SourceState>(SourceState.None)
    val source: StateFlow<SourceState> = _source.asStateFlow()

    private val _trim = MutableStateFlow(TrimRange(0, 0))
    val trim: StateFlow<TrimRange> = _trim.asStateFlow()

    private val _settings = MutableStateFlow(platform.loadSettings())
    val settings: StateFlow<CompressSettings> = _settings.asStateFlow()

    private val _thumbnails = MutableStateFlow<List<ImageBitmap>>(emptyList())
    val thumbnails: StateFlow<List<ImageBitmap>> = _thumbnails.asStateFlow()

    val job: StateFlow<JobState> = JobManager.state

    /** Live preview of what the encoder will do with the current settings. */
    val plan: StateFlow<EncodePlan?> = combine(_source, _trim, _settings) { src, trim, s ->
        (src as? SourceState.Ready)?.let { EncodePlanner.plan(it.video.info, trim, s) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var loadJob: Job? = null

    fun open(source: MediaRef) {
        JobManager.reset()
        loadJob?.cancel()
        _source.value = SourceState.Loading
        _thumbnails.value = emptyList()
        loadJob = viewModelScope.launch {
            try {
                val video = withContext(Dispatchers.IO) { platform.probe(source) }
                _trim.value = TrimRange(0, video.info.durationMs)
                _source.value = SourceState.Ready(video)
                _thumbnails.value = withContext(Dispatchers.IO) { platform.thumbnails(source, video.info.durationMs) }
            } catch (e: ProbeException) {
                _source.value = SourceState.Error(e.message ?: "Не удалось открыть видео")
            } catch (e: SecurityException) {
                _source.value = SourceState.Error("Нет доступа к файлу. Откройте его ещё раз.")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _source.value = SourceState.Error("Не удалось открыть видео: ${e.message}")
            }
        }
    }

    fun close() {
        loadJob?.cancel()
        JobManager.reset()
        _source.value = SourceState.None
        _thumbnails.value = emptyList()
    }

    fun setTrim(range: TrimRange) {
        _trim.value = range
    }

    fun updateSettings(transform: (CompressSettings) -> CompressSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        platform.saveSettings(next)
    }

    fun compress() {
        val video = (_source.value as? SourceState.Ready)?.video ?: return
        platform.startJob(CompressRequest(video.source, video.name, video.info, _trim.value, _settings.value))
    }

    fun cancel() = platform.cancelJob()

    fun backToEditor() = JobManager.reset()
}
