package com.xpress.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xpress.app.engine.MediaSaver
import com.xpress.app.job.JobState
import com.xpress.app.ui.components.VideoSurface
import com.xpress.app.ui.components.rememberPlayer
import com.xpress.app.ui.formatBytes
import com.xpress.app.ui.formatEta
import com.xpress.app.ui.planSummary
import kotlin.math.roundToInt

@Composable
fun ResultScreen(
    state: JobState.Done,
    onShare: () -> Unit,
    onTweak: () -> Unit,
    onNew: () -> Unit,
) {
    BackHandler(onBack = onTweak)
    val result = state.result
    val plan = result.plan
    val player = rememberPlayer(result.outputUri)
    val source = state.request.info.sizeBytes

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("Готово", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        val ratio = (plan.width.toFloat() / plan.height).coerceIn(0.3f, 3f)
        Box(
            Modifier
                .heightIn(max = 400.dp)
                .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black),
        ) {
            VideoSurface(player, Modifier.fillMaxSize(), showControls = true)
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (source > 0) {
                        Text(formatBytes(source), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("  →  ", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(formatBytes(result.sizeBytes), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                if (source > 0 && result.sizeBytes < source) {
                    val saved = (100 - result.sizeBytes * 100.0 / source).roundToInt()
                    Text("−$saved%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    planSummary(plan, result.finalVideoKbps),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                plan.targetBytes?.let { limit ->
                    val fill = String.format(java.util.Locale.forLanguageTag("ru"), "%.1f", result.sizeBytes * 100.0 / limit)
                    Text(
                        "Заполнено $fill% лимита · проходов: ${result.attempts + 1} · ${formatEta(result.elapsedMs / 1000)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    "Сохранено в галерею: Movies/${MediaSaver.ALBUM}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(onClick = onShare, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Outlined.Share, null)
            Spacer(Modifier.width(10.dp))
            Text("Поделиться", style = MaterialTheme.typography.titleMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = onTweak, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Outlined.Tune, null)
                Spacer(Modifier.width(8.dp))
                Text("Изменить")
            }
            OutlinedButton(onClick = onNew, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Outlined.AddPhotoAlternate, null)
                Spacer(Modifier.width(8.dp))
                Text("Новое видео")
            }
        }
    }
}

@Composable
fun ErrorScreen(state: JobState.Failed, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(64.dp))
        Text("Не получилось", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(state.message, textAlign = TextAlign.Center)
        if (state.details != null) {
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(if (showDetails) "Скрыть подробности" else "Подробности")
            }
            if (showDetails) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(
                        state.details,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        Button(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
            Spacer(Modifier.width(8.dp))
            Text("Назад")
        }
    }
}
