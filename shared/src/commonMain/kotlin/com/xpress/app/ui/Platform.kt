package com.xpress.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Wording that genuinely differs between a phone and a PC. */
expect object PlatformText {
    /** Progress label while the result is being stored. */
    val saving: String

    /** Reassurance under the progress ring that leaving the screen is fine. */
    val keepsRunning: String

    /** Prefix for [com.xpress.app.engine.CompressResult.location]. */
    val savedTo: String
}

/** System back (gesture or button); the desktop has none. */
@Composable
expect fun PlatformBackHandler(onBack: () -> Unit)

/** Keeps the system edge-swipe gestures away from controls that sit at the screen edges. */
expect fun Modifier.excludeSystemGestures(): Modifier

/** Wallpaper-based colours where the platform has them, null for the app's own palette. */
@Composable
expect fun platformColorScheme(dark: Boolean): ColorScheme?
