package com.xpress.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xpress.app.engine.CompressSettings
import com.xpress.app.engine.EncodePlan
import com.xpress.app.engine.EncodeSpeed
import com.xpress.app.engine.SizeMode
import com.xpress.app.engine.VideoCodec
import com.xpress.app.engine.VideoInfo
import com.xpress.app.ui.formatBytes
import com.xpress.app.ui.planSummary
import java.util.Locale

private val SIZE_PRESETS = listOf(10.0 to "Discord · 10 МБ", 25.0 to "25 МБ", 50.0 to "50 МБ", 100.0 to "100 МБ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsPanel(
    settings: CompressSettings,
    info: VideoInfo,
    plan: EncodePlan?,
    onChange: ((CompressSettings) -> CompressSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var advanced by rememberSaveable { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Segmented(
            options = listOf(SizeMode.TARGET_SIZE to "Размер", SizeMode.BITRATE to "Битрейт", SizeMode.QUALITY to "Качество"),
            selected = settings.mode,
            onSelect = { m -> onChange { it.copy(mode = m) } },
        )

        when (settings.mode) {
            SizeMode.TARGET_SIZE -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SIZE_PRESETS.forEach { (mb, label) ->
                        FilterChip(
                            selected = settings.targetSizeMb == mb,
                            onClick = { onChange { it.copy(targetSizeMb = mb) } },
                            label = { Text(label) },
                        )
                    }
                }
                NumberField(
                    value = settings.targetSizeMb,
                    label = "Размер файла, МБ",
                    decimals = true,
                    onValue = { v -> if (v in 0.1..4000.0) onChange { it.copy(targetSizeMb = v) } },
                )
            }
            SizeMode.BITRATE -> NumberField(
                value = settings.videoBitrateKbps.toDouble(),
                label = "Битрейт видео, кбит/с",
                decimals = false,
                onValue = { v -> if (v in 50.0..200_000.0) onChange { it.copy(videoBitrateKbps = v.toInt()) } },
            )
            SizeMode.QUALITY -> Column {
                Text(
                    "CRF ${settings.quality} · меньше — лучше и тяжелее",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = settings.quality.toFloat(),
                    onValueChange = { v -> onChange { it.copy(quality = v.toInt()) } },
                    valueRange = 14f..40f,
                    steps = 40 - 14 - 1,
                )
                Text(
                    "Итоговый размер заранее неизвестен",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Kept outside "advanced" on purpose: muting is a one-tap decision with any preset.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(enabled = info.hasAudio) { onChange { it.copy(removeAudio = !it.removeAudio) } },
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.VolumeOff, null)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Удалить звук", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (info.hasAudio) "Весь объём уйдёт в картинку" else "В исходнике нет звука",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.removeAudio || !info.hasAudio,
                    enabled = info.hasAudio,
                    onCheckedChange = { c -> onChange { it.copy(removeAudio = c) } },
                )
            }
        }

        plan?.let { PlanCard(it, settings) }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { advanced = !advanced }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("Дополнительно", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            val angle by animateFloatAsState(if (advanced) 180f else 0f, label = "chevron")
            Icon(Icons.Outlined.ExpandMore, null, Modifier.rotate(angle))
        }

        AnimatedVisibility(advanced) {
            AdvancedSettings(settings, info, onChange)
        }
    }
}

@Composable
private fun PlanCard(plan: EncodePlan, settings: CompressSettings) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                val head = when (settings.mode) {
                    SizeMode.TARGET_SIZE -> "Итог: не больше ${formatBytes(plan.targetBytes ?: 0)}"
                    SizeMode.BITRATE -> {
                        val bytes = ((plan.videoKbps ?: 0) + plan.audioKbps) * 1000L / 8 * plan.durationMs / 1000
                        "Итог: около ${formatBytes(bytes)}"
                    }
                    SizeMode.QUALITY -> "Итог: постоянное качество"
                }
                Text(head, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(planSummary(plan), style = MaterialTheme.typography.bodyMedium)
            plan.warning?.let {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.WarningAmber, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AdvancedSettings(
    settings: CompressSettings,
    info: VideoInfo,
    onChange: ((CompressSettings) -> CompressSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Label("Кодек")
        Segmented(
            options = listOf(VideoCodec.H264 to "H.264", VideoCodec.HEVC to "H.265", VideoCodec.AV1 to "AV1"),
            selected = settings.codec,
            onSelect = { c -> onChange { it.copy(codec = c) } },
        )
        Text(
            when (settings.codec) {
                VideoCodec.H264 -> "Играет везде, в том числе встроенным плеером Discord. Лучший выбор по умолчанию."
                VideoCodec.HEVC -> "На ~30% эффективнее, но Discord на ПК и в браузере часто не показывает превью."
                VideoCodec.AV1 -> "Самое сильное сжатие, но кодирует очень медленно. Старые iPhone не воспроизведут."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val sides = listOf(2160, 1440, 1080, 720, 540, 480, 360).filter { it < info.shortSide }
        Dropdown(
            label = "Разрешение",
            selected = settings.resolution,
            options = listOf(CompressSettings.AUTO to "Авто — по битрейту", CompressSettings.ORIGINAL to "Исходное (${info.shortSide}p)") +
                sides.map { it to "${it}p" },
            onSelect = { v -> onChange { it.copy(resolution = v) } },
        )
        Dropdown(
            label = "Частота кадров",
            selected = settings.fps,
            options = listOf(CompressSettings.AUTO to "Авто", CompressSettings.ORIGINAL to "Исходная") +
                listOf(60, 30, 24, 15).filter { it < info.fps - 0.5 }.map { it to "$it к/с" },
            onSelect = { v -> onChange { it.copy(fps = v) } },
        )
        Dropdown(
            label = "Звук (AAC)",
            selected = settings.audioKbps,
            enabled = info.hasAudio && !settings.removeAudio,
            options = listOf(CompressSettings.AUTO to "Авто (128 или меньше)") +
                listOf(192, 160, 128, 96, 64).map { it to "$it кбит/с" },
            onSelect = { v -> onChange { it.copy(audioKbps = v) } },
        )

        Label("Скорость кодирования")
        Segmented(
            options = listOf(EncodeSpeed.FAST to "Быстро", EncodeSpeed.BALANCED to "Баланс", EncodeSpeed.BEST to "Максимум"),
            selected = settings.speed,
            onSelect = { sp -> onChange { it.copy(speed = sp) } },
        )
        Text(
            "«Максимум» выжимает ещё немного качества, но кодирует в 2–3 раза дольше.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun NumberField(value: Double, label: String, decimals: Boolean, onValue: (Double) -> Unit) {
    fun fmt(v: Double) = if (decimals) {
        if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    } else v.toLong().toString()

    var text by remember { mutableStateOf(fmt(value)) }
    var lastValue by remember { mutableStateOf(value) }
    // Follow external changes (preset chips), but never fight the user while they type.
    if (value != lastValue) {
        lastValue = value
        if (text.replace(',', '.').toDoubleOrNull() != value) text = fmt(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            val clean = t.filter { it.isDigit() || (decimals && (it == '.' || it == ',')) }.take(8)
            text = clean
            clean.replace(',', '.').toDoubleOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun <T> Dropdown(
    label: String,
    selected: T,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: options.first().second
    Box {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled) { open = true },
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        current,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
                Icon(Icons.Outlined.ExpandMore, null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, (value, text) ->
                if (i == 2 && options.size > 2) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(text, fontWeight = if (value == selected) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = {
                        open = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}
