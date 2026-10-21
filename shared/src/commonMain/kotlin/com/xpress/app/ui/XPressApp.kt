package com.xpress.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xpress.app.engine.CompressResult
import com.xpress.app.job.JobState
import com.xpress.app.ui.screens.EditorScreen
import com.xpress.app.ui.screens.ErrorScreen
import com.xpress.app.ui.screens.HomeScreen
import com.xpress.app.ui.screens.ProgressScreen
import com.xpress.app.ui.screens.ResultScreen

/** The platform's part of the shared screens: how videos come in and how results go out. */
class AppHost(
    val onPickVideo: () -> Unit,
    /** A second way in on the home screen (Android's file picker); null hides the button. */
    val onPickFile: (() -> Unit)?,
    val homeHint: String,
    val homeHintIcon: ImageVector,
    /** Starts compression; Android asks for the notification permission first. */
    val onCompress: () -> Unit,
    /** Buttons that hand the finished file over: share on Android, copy and reveal on the desktop. */
    val resultActions: @Composable ColumnScope.(CompressResult) -> Unit,
)

/** What is on screen; the job outranks the editor so progress survives activity recreation. */
private sealed interface Screen {
    data class Home(val source: SourceState) : Screen
    data class Editor(val video: SourceVideo) : Screen
    data class Progress(val job: JobState.Running) : Screen
    data class Result(val job: JobState.Done) : Screen
    data class Failure(val job: JobState.Failed) : Screen
}

@Composable
fun XPressApp(vm: MainViewModel, host: AppHost) {
    val job by vm.job.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val trim by vm.trim.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val thumbnails by vm.thumbnails.collectAsStateWithLifecycle()

    val screen: Screen = when (val j = job) {
        is JobState.Running -> Screen.Progress(j)
        is JobState.Done -> Screen.Result(j)
        is JobState.Failed -> Screen.Failure(j)
        JobState.Idle -> when (val s = source) {
            is SourceState.Ready -> Screen.Editor(s.video)
            else -> Screen.Home(s)
        }
    }
    AnimatedContent(
        targetState = screen,
        // Progress ticks and source loading must not restart the cross-fade.
        contentKey = { if (it is Screen.Editor) it.video.source else it::class },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { s ->
        when (s) {
            is Screen.Progress -> ProgressScreen(s.job, onCancel = vm::cancel)
            is Screen.Result -> ResultScreen(
                state = s.job,
                onTweak = vm::backToEditor,
                onNew = vm::close,
                actions = { host.resultActions(this, s.job.result) },
            )
            is Screen.Failure -> ErrorScreen(s.job, onBack = vm::backToEditor)
            is Screen.Editor -> EditorScreen(
                video = s.video,
                trim = trim,
                thumbnails = thumbnails,
                settings = settings,
                plan = plan,
                onTrim = vm::setTrim,
                onSettings = vm::updateSettings,
                onCompress = host.onCompress,
                onClose = vm::close,
                onPickAnother = host.onPickVideo,
            )
            is Screen.Home -> HomeScreen(
                loading = s.source is SourceState.Loading,
                error = (s.source as? SourceState.Error)?.message,
                onPickVideo = host.onPickVideo,
                onPickFile = host.onPickFile,
                hint = host.homeHint,
                hintIcon = host.homeHintIcon,
            )
        }
    }
}
