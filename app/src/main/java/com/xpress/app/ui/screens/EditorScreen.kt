package com.xpress.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.FirstPage
import androidx.compose.material.icons.automirrored.outlined.LastPage
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.SeekParameters
import com.xpress.app.engine.CompressSettings
import com.xpress.app.engine.EncodePlan
import com.xpress.app.engine.TrimRange
import com.xpress.app.ui.SourceVideo
import com.xpress.app.ui.codecName
import com.xpress.app.ui.components.SettingsPanel
import com.xpress.app.ui.components.VideoSurface
import com.xpress.app.ui.components.VideoTrimmer
import com.xpress.app.ui.components.rememberPlayer
import com.xpress.app.ui.components.rememberPlayerUiState
import com.xpress.app.ui.formatBytes
import com.xpress.app.ui.formatFps
import com.xpress.app.ui.formatTimePrecise
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    video: SourceVideo,
    trim: TrimRange,
    thumbnails: List<ImageBitmap>,
    settings: CompressSettings,
    plan: EncodePlan?,
    onTrim: (TrimRange) -> Unit,
    onSettings: ((CompressSettings) -> CompressSettings) -> Unit,
    onCompress: () -> Unit,
    onClose: () -> Unit,
    onPickAnother: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val info = video.info
    val player = rememberPlayer(video.uri)
    val playerState = rememberPlayerUiState(player)
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    val currentTrim by rememberUpdatedState(trim)

    // Track the playhead and keep playback inside the selected range.
    LaunchedEffect(player) {
        while (true) {
            val pos = player.currentPosition
            if (!dragging) position = pos
            if (player.isPlaying && pos >= currentTrim.endMs) {
                player.pause()
                player.seekTo(currentTrim.startMs)
            }
            delay(32)
        }
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            val pos = player.currentPosition
            if (pos < trim.startMs || pos >= trim.endMs - 50) player.seekTo(trim.startMs)
            player.play()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(video.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Закрыть") } },
                actions = { IconButton(onClick = onPickAnother) { Icon(Icons.Outlined.VideoLibrary, "Другое видео") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        player.pause()
                        onCompress()
                    },
                    enabled = plan != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(56.dp),
                ) {
                    Icon(Icons.Outlined.Compress, null)
                    Spacer(Modifier.width(10.dp))
                    Text("Сжать", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Player
            val ratio = (info.width.toFloat() / info.height).coerceIn(0.3f, 3f)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .heightIn(max = 420.dp)
                        .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black)
                        .clickable(remember { MutableInteractionSource() }, null) { togglePlay() },
                ) {
                    VideoSurface(player, Modifier.fillMaxSize())
                    val playAlpha by animateFloatAsState(if (playerState.isPlaying) 0f else 1f, label = "play")
                    if (playAlpha > 0f) {
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .alpha(playAlpha)
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.PlayArrow, "Играть", tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                    }
                    Text(
                        formatTimePrecise(position),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            // Source facts
            Text(
                buildString {
                    append("${info.width}×${info.height} · ${formatFps(info.fps)} к/с · ${codecName(info.videoCodec)}")
                    if (info.isHdr) append(" · HDR")
                    if (info.sizeBytes > 0) append(" · ${formatBytes(info.sizeBytes)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            // Trimming
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoTrimmer(
                    durationMs = info.durationMs,
                    range = trim,
                    positionMs = position,
                    thumbnails = thumbnails,
                    onRangeChange = { r, preview ->
                        dragging = true
                        if (player.isPlaying) player.pause()
                        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                        player.seekTo(preview)
                        position = preview
                        onTrim(r)
                    },
                    onRangeChangeFinished = { r ->
                        dragging = false
                        player.setSeekParameters(SeekParameters.EXACT)
                        player.seekTo(if (position <= r.startMs + 1) r.startMs else position.coerceIn(r.startMs, r.endMs))
                    },
                    onSeek = { t ->
                        player.setSeekParameters(SeekParameters.EXACT)
                        player.seekTo(t)
                        position = t
                    },
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTimePrecise(trim.startMs), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Длительность ${formatTimePrecise(trim.durationMs)}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(formatTimePrecise(trim.endMs), style = MaterialTheme.typography.labelLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {
                            val t = position.coerceAtMost(trim.endMs - 500).coerceAtLeast(0)
                            onTrim(trim.copy(startMs = t))
                        },
                        label = { Text("Начало здесь") },
                        leadingIcon = { Icon(Icons.Outlined.FirstPage, null, Modifier.size(18.dp)) },
                    )
                    AssistChip(
                        onClick = {
                            val t = position.coerceAtLeast(trim.startMs + 500).coerceAtMost(info.durationMs)
                            onTrim(trim.copy(endMs = t))
                        },
                        label = { Text("Конец здесь") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.LastPage, null, Modifier.size(18.dp)) },
                    )
                    if (trim.startMs > 0 || trim.endMs < info.durationMs) {
                        AssistChip(
                            onClick = { onTrim(TrimRange(0, info.durationMs)) },
                            label = { Text("Сброс") },
                            leadingIcon = { Icon(Icons.Outlined.RestartAlt, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingsPanel(
                    settings = settings,
                    info = info,
                    plan = plan,
                    onChange = onSettings,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
