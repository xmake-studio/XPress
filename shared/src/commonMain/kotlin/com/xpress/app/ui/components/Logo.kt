package com.xpress.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The play triangle squeezed between two chevrons; same shape as the launcher icon. */
val XPressLogo: ImageVector by lazy {
    val white = SolidColor(Color.White)
    ImageVector.Builder("XPressLogo", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes("M10,8.2 L15.6,12 L10,15.8 Z"),
            fill = white, stroke = white, strokeLineWidth = 1.2f, strokeLineJoin = StrokeJoin.Round,
        )
        .addPath(
            addPathNodes("M3.2,6.5 L7.4,12 L3.2,17.5"),
            stroke = white, strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        .addPath(
            addPathNodes("M20.8,6.5 L16.6,12 L20.8,17.5"),
            stroke = white, strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        .build()
}
