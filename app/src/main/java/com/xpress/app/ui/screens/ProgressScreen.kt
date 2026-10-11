package com.xpress.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xpress.app.engine.EncodePlanner
import com.xpress.app.engine.Stage
import com.xpress.app.job.JobState
import com.xpress.app.ui.formatEta
import com.xpress.app.ui.planSummary
import com.xpress.app.ui.stageLabel
import kotlin.math.roundToInt

@Composable
fun ProgressScreen(state: JobState.Running, onCancel: () -> Unit) {
    val p = state.progress
    val plan = remember(state.request) {
        EncodePlanner.plan(state.request.info, state.request.trim, state.request.settings)
    }
    val fraction by animateFloatAsState(p.fraction, label = "progress")
    val indeterminate = p.stage == Stage.PREPARING || p.stage == Stage.SAVING

    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (indeterminate) {
                    CircularProgressIndicator(Modifier.size(220.dp), strokeWidth = 12.dp, strokeCap = StrokeCap.Round)
                } else {
                    CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.size(220.dp),
                        strokeWidth = 12.dp,
                        strokeCap = StrokeCap.Round,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
                Text(
                    "${(fraction * 100).roundToInt()}%",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(stageLabel(p.stage, p.attempt), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            val details = buildList {
                if (p.encodeFps > 0) add("${p.encodeFps.roundToInt()} кадр/с")
                p.etaSeconds?.let { add("осталось ~${formatEta(it)}") }
            }
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                planSummary(plan),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Можно свернуть приложение — сжатие продолжится в фоне.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, null)
                Spacer(Modifier.width(8.dp))
                Text("Отменить")
            }
        }
    }
}
