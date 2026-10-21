package com.xpress.app.ui.components

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.xpress.app.engine.MediaRef
import com.xpress.app.engine.VideoInfo
import com.xpress.app.engine.uri
import com.xpress.shared.R

/** ExoPlayer behind the shared [VideoPreview], with its flags turned into Compose state. */
private class ExoPreview(val player: ExoPlayer) : VideoPreview, Player.Listener {
    override val canPlay = true
    override var isPlaying by mutableStateOf(false)
    override var failed by mutableStateOf(false)
    override val positionMs: Long get() = player.currentPosition

    override fun play() = player.play()
    override fun pause() = player.pause()

    override fun seekTo(ms: Long, exact: Boolean) {
        player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo(ms)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        this.isPlaying = isPlaying
    }

    override fun onPlayerError(error: PlaybackException) {
        failed = true
    }
}

/** Player bound to the composition: released when it leaves, paused when the app goes to background. */
@Composable
actual fun rememberVideoPreview(source: MediaRef, info: VideoInfo?): VideoPreview {
    val context = LocalContext.current
    val preview = remember(source) {
        // Decoder fallback: if the hardware decoder rejects a stream (say, 10-bit HEVC), try the next one.
        val player = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true)).build().apply {
            setMediaItem(MediaItem.fromUri(source.uri))
            prepare()
        }
        ExoPreview(player).also(player::addListener)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(preview, lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) preview.player.pause() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            preview.player.release()
        }
    }
    return preview
}

@OptIn(UnstableApi::class)
@Composable
actual fun VideoPreviewSurface(preview: VideoPreview, modifier: Modifier, showControls: Boolean) {
    val player = (preview as ExoPreview).player
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).apply {
                useController = showControls
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                this.player = player
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}
