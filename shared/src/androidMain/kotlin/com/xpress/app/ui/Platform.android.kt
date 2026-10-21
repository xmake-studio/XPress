package com.xpress.app.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

actual object PlatformText {
    actual val saving = "Сохранение в галерею…"
    actual val keepsRunning = "Можно свернуть приложение — сжатие продолжится в фоне."
    actual val savedTo = "Сохранено в галерею"
}

@Composable
actual fun PlatformBackHandler(onBack: () -> Unit) = BackHandler(onBack = onBack)

// Handles sit at the screen edges, right where the system back gesture lives.
actual fun Modifier.excludeSystemGestures(): Modifier = systemGestureExclusion()

@Composable
actual fun platformColorScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}
