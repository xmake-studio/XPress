package com.xpress.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xpress.app.job.JobState
import com.xpress.app.ui.MainViewModel
import com.xpress.app.ui.SourceState
import com.xpress.app.ui.SourceVideo
import com.xpress.app.ui.screens.EditorScreen
import com.xpress.app.ui.screens.ErrorScreen
import com.xpress.app.ui.screens.HomeScreen
import com.xpress.app.ui.screens.ProgressScreen
import com.xpress.app.ui.screens.ResultScreen
import com.xpress.app.ui.theme.XPressTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::open)
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::open)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Compression runs either way; the permission only controls the progress notification.
        vm.compress()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            XPressTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Root()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        sharedUri(intent ?: return)?.let(vm::open)
    }

    private fun sharedUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.firstOrNull()
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
        else -> null
    }

    private fun pickVideo() = pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))

    private fun startCompression() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.compress()
        }
    }

    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Отправить видео"))
    }

    /** What is on screen; the job outranks the editor so progress survives activity recreation. */
    private sealed interface Screen {
        data class Home(val source: SourceState) : Screen
        data class Editor(val video: SourceVideo) : Screen
        data class Progress(val job: JobState.Running) : Screen
        data class Result(val job: JobState.Done) : Screen
        data class Failure(val job: JobState.Failed) : Screen
    }

    @Composable
    private fun Root() {
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
            contentKey = { if (it is Screen.Editor) it.video.uri else it::class },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
        ) { s ->
            when (s) {
                is Screen.Progress -> ProgressScreen(s.job, onCancel = vm::cancel)
                is Screen.Result -> ResultScreen(
                    state = s.job,
                    onShare = { share(s.job.result.outputUri) },
                    onTweak = vm::backToEditor,
                    onNew = vm::close,
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
                    onCompress = ::startCompression,
                    onClose = vm::close,
                    onPickAnother = ::pickVideo,
                )
                is Screen.Home -> HomeScreen(
                    loading = s.source is SourceState.Loading,
                    error = (s.source as? SourceState.Error)?.message,
                    onPickGallery = ::pickVideo,
                    onPickFile = { pickFile.launch(arrayOf("video/*")) },
                )
            }
        }
    }
}
