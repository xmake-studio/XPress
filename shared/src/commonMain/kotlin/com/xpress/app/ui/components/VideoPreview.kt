package com.xpress.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import com.xpress.app.engine.MediaRef
import com.xpress.app.engine.VideoInfo

/** The player as the screens see it. Android plays the video; the desktop shows still frames. */
@Stable
interface VideoPreview {
    /** False when the preview only shows frames and cannot play. */
    val canPlay: Boolean
    val isPlaying: Boolean
    val failed: Boolean

    /** Current playhead. Not observable: the editor polls it. */
    val positionMs: Long

    fun play()
    fun pause()

    /** [exact] false allows a quick seek to a nearby keyframe while scrubbing. */
    fun seekTo(ms: Long, exact: Boolean)
}

/** Preview bound to the composition: released when it leaves. [info] helps with sizing and HDR when known. */
@Composable
expect fun rememberVideoPreview(source: MediaRef, info: VideoInfo?): VideoPreview

/** [showControls] adds the platform's own playback controls (the result screen). */
@Composable
expect fun VideoPreviewSurface(preview: VideoPreview, modifier: Modifier = Modifier, showControls: Boolean = false)
