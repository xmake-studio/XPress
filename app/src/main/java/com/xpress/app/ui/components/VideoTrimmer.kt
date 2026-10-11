package com.xpress.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.xpress.app.engine.TrimRange
import kotlin.math.abs
import kotlin.math.roundToLong

private enum class DragTarget { START, END, SEEK }

/**
 * Filmstrip with two handles. Dragging a handle moves the cut and previews that frame;
 * touching anywhere else seeks the player.
 */
@Composable
fun VideoTrimmer(
    durationMs: Long,
    range: TrimRange,
    positionMs: Long,
    thumbnails: List<ImageBitmap>,
    onRangeChange: (TrimRange, previewMs: Long) -> Unit,
    onRangeChangeFinished: (TrimRange) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    minLengthMs: Long = 500,
) {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val stripBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val handleWidth = 16.dp
    val currentRange by rememberUpdatedState(range)
    val onChange by rememberUpdatedState(onRangeChange)
    val onFinished by rememberUpdatedState(onRangeChangeFinished)
    val seek by rememberUpdatedState(onSeek)

    // Handles sit at the screen edges, right where the system back gesture lives.
    BoxWithConstraints(modifier.fillMaxWidth().height(64.dp).systemGestureExclusion()) {
        val density = LocalDensity.current
        val handlePx = with(density) { handleWidth.toPx() }
        val totalPx = with(density) { maxWidth.toPx() }
        val trackPx = (totalPx - 2 * handlePx).coerceAtLeast(1f)
        val touchSlopPx = with(density) { 28.dp.toPx() }
        val dur = durationMs.coerceAtLeast(1)

        fun xOf(t: Long) = handlePx + trackPx * (t.toFloat() / dur)
        fun tOf(x: Float) = (((x - handlePx) / trackPx).coerceIn(0f, 1f) * dur).roundToLong()

        // Filmstrip
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = handleWidth, vertical = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(stripBg)
        ) {
            thumbnails.forEach { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(durationMs, totalPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val r = currentRange
                        val startHandleX = xOf(r.startMs) - handlePx / 2
                        val endHandleX = xOf(r.endMs) + handlePx / 2
                        val x0 = down.position.x
                        val dStart = abs(x0 - startHandleX)
                        val dEnd = abs(x0 - endHandleX)
                        val target = when {
                            dStart < touchSlopPx && dStart <= dEnd -> DragTarget.START
                            dEnd < touchSlopPx -> DragTarget.END
                            else -> DragTarget.SEEK
                        }
                        down.consume()
                        // Grab offset keeps the handle from jumping under the finger.
                        val grab = when (target) {
                            DragTarget.START -> x0 - (xOf(r.startMs) - handlePx / 2)
                            DragTarget.END -> x0 - (xOf(r.endMs) + handlePx / 2)
                            DragTarget.SEEK -> 0f
                        }

                        fun apply(x: Float) {
                            val cur = currentRange
                            when (target) {
                                DragTarget.START -> {
                                    val t = tOf(x - grab + handlePx / 2).coerceAtMost(cur.endMs - minLengthMs).coerceAtLeast(0)
                                    onChange(cur.copy(startMs = t), t)
                                }
                                DragTarget.END -> {
                                    val t = tOf(x - grab - handlePx / 2).coerceAtLeast(cur.startMs + minLengthMs).coerceAtMost(dur)
                                    onChange(cur.copy(endMs = t), t)
                                }
                                DragTarget.SEEK -> seek(tOf(x).coerceIn(cur.startMs, cur.endMs))
                            }
                        }

                        if (target == DragTarget.SEEK) apply(x0)
                        drag(down.id) { change ->
                            apply(change.position.x)
                            change.consume()
                        }
                        if (target != DragTarget.SEEK) onFinished(currentRange)
                    }
                }
        ) {
            val h = size.height
            val top = 4.dp.toPx()
            val bottom = h - top
            val sx = xOf(range.startMs)
            val ex = xOf(range.endMs)
            val scrim = Color.Black.copy(alpha = 0.55f)

            // Dim what will be cut away.
            if (sx > handlePx) drawRect(scrim, Offset(handlePx, top), Size(sx - handlePx, bottom - top))
            if (ex < handlePx + trackPx) drawRect(scrim, Offset(ex, top), Size(handlePx + trackPx - ex, bottom - top))

            // Frame: top/bottom bars plus two rounded handles.
            val bar = 3.dp.toPx()
            drawRect(accent, Offset(sx, 0f), Size(ex - sx, bar + 1))
            drawRect(accent, Offset(sx, h - bar - 1), Size(ex - sx, bar + 1))
            val r = CornerRadius(6.dp.toPx())
            drawRoundRect(accent, Offset(sx - handlePx, 0f), Size(handlePx, h), r)
            drawRoundRect(accent, Offset(ex, 0f), Size(handlePx, h), r)
            val gripH = h * 0.32f
            listOf(sx - handlePx / 2, ex + handlePx / 2).forEach { cx ->
                drawLine(onAccent, Offset(cx, h / 2 - gripH / 2), Offset(cx, h / 2 + gripH / 2), 2.dp.toPx(), StrokeCap.Round)
            }

            // Playhead with a small triangle on top.
            if (positionMs in range.startMs..range.endMs) {
                val px = xOf(positionMs)
                drawLine(Color.Black.copy(alpha = 0.35f), Offset(px + 1.5f, top), Offset(px + 1.5f, bottom), 3.dp.toPx())
                drawLine(Color.White, Offset(px, top), Offset(px, bottom), 2.dp.toPx(), StrokeCap.Round)
                val tri = Path().apply {
                    moveTo(px - 5.dp.toPx(), top)
                    lineTo(px + 5.dp.toPx(), top)
                    lineTo(px, top + 6.dp.toPx())
                    close()
                }
                drawPath(tri, Color.White, style = Fill)
            }
        }
    }
}
