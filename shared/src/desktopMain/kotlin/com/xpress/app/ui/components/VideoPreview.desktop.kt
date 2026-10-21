package com.xpress.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.xpress.app.engine.DesktopMedia
import com.xpress.app.engine.MediaRef
import com.xpress.app.engine.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import kotlin.concurrent.thread

private data class Seek(val ms: Long, val exact: Boolean)

/** No player on the desktop: every seek turns into one decoded still. */
private class FramePreview(val file: File) : VideoPreview {
    override val canPlay = false
    override val isPlaying = false
    override var failed by mutableStateOf(false)
    override var positionMs = 0L
        private set

    var frame by mutableStateOf<ImageBitmap?>(null)
    val seeks = MutableStateFlow(Seek(0, exact = true))

    override fun play() = Unit
    override fun pause() = Unit

    override fun seekTo(ms: Long, exact: Boolean) {
        positionMs = ms
        seeks.value = Seek(ms, exact)
    }
}

@Composable
actual fun rememberVideoPreview(source: MediaRef, info: VideoInfo?): VideoPreview {
    val preview = remember(source) { FramePreview(File(source.value)) }
    LaunchedEffect(preview) {
        // A StateFlow collector that is still decoding skips straight to the newest seek,
        // so dragging a trim handle never queues up a backlog of frames.
        preview.seeks.collect { s ->
            val image = withContext(Dispatchers.IO) {
                DesktopMedia.frame(preview.file, info, s.ms, s.exact)
                    // Right at the end there may be no frame left to show: take the last keyframe instead.
                    ?: if (s.ms > 0) DesktopMedia.frame(preview.file, info, (s.ms - 1000).coerceAtLeast(0), exact = false) else null
            }
            if (image != null) preview.frame = image else if (preview.frame == null) preview.failed = true
        }
    }
    return preview
}

@Composable
actual fun VideoPreviewSurface(preview: VideoPreview, modifier: Modifier, showControls: Boolean) {
    val p = preview as FramePreview
    Box(modifier, contentAlignment = Alignment.Center) {
        p.frame?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium)
        }
        if (showControls) {
            Surface(
                onClick = { openInSystemPlayer(p.file) },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                contentColor = Color.White,
                modifier = Modifier.size(64.dp),
            ) {
                Icon(Icons.Outlined.PlayArrow, "Открыть в плеере", Modifier.padding(12.dp))
            }
        }
    }
}

private fun openInSystemPlayer(file: File) {
    // Desktop.open can block while the shell looks up the handler.
    thread(isDaemon = true) { runCatching { Desktop.getDesktop().open(file) } }
}
