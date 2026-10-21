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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xpress.app.engine.toMediaRef
import com.xpress.app.engine.uri
import com.xpress.app.ui.AppHost
import com.xpress.app.ui.MainViewModel
import com.xpress.app.ui.XPressApp
import com.xpress.app.ui.theme.XPressTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel(AndroidPlatform(application)) } }
    }

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.open(it.toMediaRef()) }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.open(it.toMediaRef()) }
    }

    private val host = AppHost(
        onPickVideo = ::pickVideo,
        onPickFile = { pickFile.launch(arrayOf("video/*")) },
        homeHint = "Или в галерее: «Поделиться» → XPress",
        homeHintIcon = Icons.Outlined.IosShare,
        onCompress = ::startCompression,
        resultActions = { result ->
            Button(onClick = { share(result.output.uri) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Outlined.Share, null)
                Spacer(Modifier.width(10.dp))
                Text("Поделиться", style = MaterialTheme.typography.titleMedium)
            }
        },
    )

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
                    XPressApp(vm, host)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        sharedUri(intent ?: return)?.let { vm.open(it.toMediaRef()) }
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
}
