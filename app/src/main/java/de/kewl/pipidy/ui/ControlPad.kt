package de.kewl.pipidy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.kewl.pipidy.R
import java.util.Locale

/** Share of the pad width that the steering circle takes as its radius. */
private const val RING = 0.38f

/**
 * Coordinate field with a steering circle. The dot follows the finger inside the circle.
 * Reports x and y from -1 to 1, right and up are positive.
 */
@Composable
fun ControlPad(
    onMove: (x: Float, y: Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    springBack: Boolean = true,
) {
    var point by remember { mutableStateOf(Offset.Zero) }
    var held by remember { mutableStateOf(false) }
    val move by rememberUpdatedState(onMove)
    val release by rememberUpdatedState(onRelease)

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .alpha(if (enabled) 1f else 0.35f)
            .glass(),
    ) {
        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(enabled, springBack) {
                    if (!enabled) return@pointerInput
                    fun follow(position: Offset) {
                        val radius = size.width * RING
                        var vector = Offset(position.x - size.width / 2f, position.y - size.height / 2f) / radius
                        val length = vector.getDistance()
                        if (length > 1f) vector /= length
                        point = Offset(vector.x, -vector.y)
                        move(point.x, point.y)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // Touches outside the circle are left to the page, so it can still be scrolled.
                        val fromCentre = (down.position - Offset(size.width / 2f, size.height / 2f)).getDistance()
                        if (fromCentre > size.width * RING * 1.08f) return@awaitEachGesture
                        down.consume()
                        held = true
                        follow(down.position)
                        drag(down.id) { change ->
                            follow(change.position)
                            change.consume()
                        }
                        held = false
                        if (springBack) point = Offset.Zero
                        release()
                    }
                }
        ) {
            val centre = center
            val radius = size.width * RING
            val faint = Color.White.copy(alpha = 0.06f)
            val cell = size.width / 12f
            for (i in 1 until 12) {
                drawLine(faint, Offset(cell * i, 0f), Offset(cell * i, size.height))
                drawLine(faint, Offset(0f, cell * i), Offset(size.width, cell * i))
            }

            val axis = Color.White.copy(alpha = 0.34f)
            drawLine(axis, Offset(0f, centre.y), Offset(size.width, centre.y))
            drawLine(axis, Offset(centre.x, 0f), Offset(centre.x, size.height))
            val tick = 4.dp.toPx()
            for (i in -4..4) {
                if (i == 0) continue
                val step = radius * i / 4f
                drawLine(axis, Offset(centre.x + step, centre.y - tick), Offset(centre.x + step, centre.y + tick))
                drawLine(axis, Offset(centre.x - tick, centre.y + step), Offset(centre.x + tick, centre.y + step))
            }

            drawCircle(Color.White.copy(alpha = 0.10f), radius / 3f, centre, style = Stroke(1.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.10f), radius * 2f / 3f, centre, style = Stroke(1.dp.toPx()))
            drawCircle(
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.18f))),
                radius,
                centre,
                style = Stroke(1.5.dp.toPx()),
            )

            val dot = Offset(centre.x + point.x * radius, centre.y - point.y * radius)
            val dashed = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            val guide = Ink.Amber.copy(alpha = 0.55f)
            drawLine(guide, dot, Offset(dot.x, centre.y), pathEffect = dashed)
            drawLine(guide, dot, Offset(centre.x, dot.y), pathEffect = dashed)

            val glow = 54.dp.toPx()
            drawCircle(
                Brush.radialGradient(listOf(Ink.Amber.copy(alpha = if (held) 0.55f else 0.30f), Color.Transparent), dot, glow),
                glow,
                dot,
            )
            val knob = 17.dp.toPx()
            drawCircle(
                Brush.radialGradient(listOf(Color.White, Ink.Amber), dot - Offset(knob * 0.3f, knob * 0.3f), knob * 1.3f),
                knob,
                dot,
            )
            drawCircle(Color.White.copy(alpha = 0.8f), knob, dot, style = Stroke(1.dp.toPx()))
        }

        Label(stringResource(R.string.pad_up), Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        Label(stringResource(R.string.pad_down), Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
        Label(stringResource(R.string.pad_left), Modifier.align(Alignment.CenterStart).padding(start = 12.dp).sideways(upwards = true))
        Label(stringResource(R.string.pad_right), Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).sideways(upwards = false))
        Txt(
            String.format(Locale.getDefault(), "X %+.2f   Y %+.2f", point.x, point.y),
            Type.Mono.copy(fontSize = Type.Label.fontSize),
            Modifier.align(Alignment.BottomStart).padding(12.dp),
            color = Ink.Dim,
        )
    }
}
