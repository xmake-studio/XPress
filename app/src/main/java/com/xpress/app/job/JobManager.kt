package com.xpress.app.job

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.xpress.app.engine.CompressRequest
import com.xpress.app.engine.CompressResult
import com.xpress.app.engine.Progress
import com.xpress.app.engine.Stage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface JobState {
    data object Idle : JobState
    data class Running(val request: CompressRequest, val progress: Progress) : JobState
    data class Done(val request: CompressRequest, val result: CompressResult) : JobState
    data class Failed(val request: CompressRequest, val message: String, val details: String?) : JobState
}

/** Process-wide state of the single compression job, shared by the UI and [CompressionService]. */
object JobManager {

    private val _state = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = _state.asStateFlow()

    @Volatile
    internal var pending: CompressRequest? = null
        private set

    fun start(context: Context, request: CompressRequest) {
        if (_state.value is JobState.Running) return
        pending = request
        _state.value = JobState.Running(request, Progress(Stage.PREPARING, 0f))
        ContextCompat.startForegroundService(context, Intent(context, CompressionService::class.java))
    }

    fun cancel(context: Context) {
        if (_state.value !is JobState.Running) return
        context.startService(Intent(context, CompressionService::class.java).setAction(CompressionService.ACTION_CANCEL))
    }

    /** Back to the editor after a finished, failed or cancelled job. */
    fun reset() {
        if (_state.value !is JobState.Running) _state.value = JobState.Idle
    }

    internal fun takePending(): CompressRequest? = pending.also { pending = null }

    internal fun update(state: JobState) {
        _state.value = state
    }
}
