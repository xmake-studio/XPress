package com.xpress.app.job

import com.xpress.app.engine.CompressException
import com.xpress.app.engine.CompressRequest
import com.xpress.app.engine.CompressResult
import com.xpress.app.engine.Compressor
import com.xpress.app.engine.Progress
import com.xpress.app.engine.Stage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

sealed interface JobState {
    data object Idle : JobState
    data class Running(val request: CompressRequest, val progress: Progress) : JobState
    data class Done(val request: CompressRequest, val result: CompressResult) : JobState
    data class Failed(val request: CompressRequest, val message: String, val details: String?) : JobState
}

/** Process-wide state of the single compression job, shared by the UI and whatever runs the job. */
object JobManager {

    private val _state = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = _state.asStateFlow()

    @Volatile
    private var pending: CompressRequest? = null

    /** Marks [request] as started; false when another job is still running. */
    fun begin(request: CompressRequest): Boolean {
        if (_state.value is JobState.Running) return false
        pending = request
        _state.value = JobState.Running(request, Progress(Stage.PREPARING, 0f))
        return true
    }

    /** Back to the editor after a finished, failed or cancelled job. */
    fun reset() {
        if (_state.value !is JobState.Running) _state.value = JobState.Idle
    }

    fun takePending(): CompressRequest? = pending.also { pending = null }

    fun update(state: JobState) {
        _state.value = state
    }

    /**
     * Runs [request] to its final state, publishing progress on the way. A cancelled job
     * ends as [JobState.Idle]; the caller publishes the returned state when it is done with it.
     */
    suspend fun execute(request: CompressRequest, compressor: Compressor, onProgress: (Progress) -> Unit = {}): JobState =
        try {
            val result = compressor.compress(request) { p ->
                update(JobState.Running(request, p))
                onProgress(p)
            }
            JobState.Done(request, result)
        } catch (e: CancellationException) {
            JobState.Idle
        } catch (e: CompressException) {
            JobState.Failed(request, e.message ?: "Ошибка", e.details)
        } catch (e: Exception) {
            JobState.Failed(request, "Непредвиденная ошибка", e.stackTraceToString().take(4000))
        }
}
