package com.xpress.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

actual object PlatformText {
    actual val saving = "Сохранение…"
    actual val keepsRunning = "Можно свернуть окно — прогресс виден на панели задач."
    actual val savedTo = "Сохранено в папку"
}

@Composable
actual fun PlatformBackHandler(onBack: () -> Unit) = Unit

actual fun Modifier.excludeSystemGestures(): Modifier = this

@Composable
actual fun platformColorScheme(dark: Boolean): ColorScheme? = null
