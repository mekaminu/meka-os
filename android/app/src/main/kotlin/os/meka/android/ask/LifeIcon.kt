package os.meka.android.ask

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import os.meka.core.domain.LifeTile

/**
 * A Life tile's icon (Fold review 2026-10-12, item 7), drawn by the app like the tab icons (no icon library): thin
 * round-capped outlines in [color]. The Mac uses the matching SF Symbols (macos/MekaOS/Shell/ShellNav.swift). Decorative:
 * the tile carries its label for screen readers.
 */
@Composable
fun LifeIcon(tile: LifeTile, color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) { drawLifeGlyph(tile, color) }
}

private fun DrawScope.drawLifeGlyph(tile: LifeTile, color: Color) {
    val w = size.minDimension
    val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(w * x, w * y)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), stroke.width, cap = StrokeCap.Round)
    fun box(x: Float, y: Float, bw: Float, bh: Float, r: Float = 0.08f) =
        drawRoundRect(color, p(x, y), Size(w * bw, w * bh), CornerRadius(w * r), style = stroke)
    fun ring(x: Float, y: Float, r: Float) = drawCircle(color, w * r, p(x, y), style = stroke)
    fun dot(x: Float, y: Float) = drawCircle(color, w * 0.045f, p(x, y))
    fun path(build: Path.() -> Unit) = drawPath(Path().apply(build), color, style = stroke)
    when (tile) {
        LifeTile.LISTS -> {
            // Three lines with a dot before each.
            listOf(0.25f, 0.5f, 0.75f).forEach { y -> dot(0.18f, y); line(0.34f, y, 0.86f, y) }
        }
        LifeTile.GOALS -> {
            // A target: two rings and a centre.
            ring(0.5f, 0.5f, 0.38f); ring(0.5f, 0.5f, 0.2f); dot(0.5f, 0.5f)
        }
        LifeTile.PEOPLE -> {
            // Two heads and shoulders.
            ring(0.36f, 0.34f, 0.13f)
            path { arcTo(Rect(p(0.12f, 0.56f), Size(w * 0.48f, w * 0.48f)), 180f, 180f, true) }
            ring(0.68f, 0.38f, 0.1f)
            path { arcTo(Rect(p(0.52f, 0.6f), Size(w * 0.36f, w * 0.36f)), 200f, 140f, true) }
        }
        LifeTile.DINNERS -> {
            // A plate with a fork beside it.
            ring(0.58f, 0.52f, 0.3f); ring(0.58f, 0.52f, 0.16f)
            line(0.14f, 0.18f, 0.14f, 0.86f); line(0.07f, 0.18f, 0.07f, 0.36f); line(0.21f, 0.18f, 0.21f, 0.36f)
        }
        LifeTile.BINS -> {
            // A wheelie bin: lid, body, a wheel.
            line(0.18f, 0.24f, 0.82f, 0.24f)
            path { moveTo(w * 0.24f, w * 0.24f); lineTo(w * 0.3f, w * 0.84f); lineTo(w * 0.7f, w * 0.84f); lineTo(w * 0.76f, w * 0.24f) }
            line(0.42f, 0.14f, 0.58f, 0.14f)
            ring(0.72f, 0.86f, 0.06f)
        }
        LifeTile.DATE_NIGHT -> {
            // A crescent moon and a star.
            path {
                arcTo(Rect(p(0.12f, 0.16f), Size(w * 0.64f, w * 0.64f)), 60f, 250f, true)
                quadraticTo(w * 0.42f, w * 0.52f, w * 0.6f, w * 0.75f)
            }
            line(0.78f, 0.14f, 0.78f, 0.34f); line(0.68f, 0.24f, 0.88f, 0.24f)
        }
        LifeTile.SCHOOL -> {
            // A mortarboard.
            path { moveTo(w * 0.08f, w * 0.4f); lineTo(w * 0.5f, w * 0.2f); lineTo(w * 0.92f, w * 0.4f); lineTo(w * 0.5f, w * 0.6f); close() }
            path { moveTo(w * 0.26f, w * 0.5f); lineTo(w * 0.26f, w * 0.72f); quadraticTo(w * 0.5f, w * 0.86f, w * 0.74f, w * 0.72f); lineTo(w * 0.74f, w * 0.5f) }
            line(0.92f, 0.4f, 0.92f, 0.66f)
        }
        LifeTile.APPOINTMENTS -> {
            // A clock face.
            ring(0.5f, 0.5f, 0.38f); line(0.5f, 0.5f, 0.5f, 0.28f); line(0.5f, 0.5f, 0.66f, 0.6f)
        }
        LifeTile.LEAVE -> {
            // A sun over the sea.
            ring(0.5f, 0.4f, 0.16f)
            line(0.5f, 0.1f, 0.5f, 0.16f); line(0.22f, 0.4f, 0.28f, 0.4f); line(0.72f, 0.4f, 0.78f, 0.4f)
            line(0.12f, 0.7f, 0.88f, 0.7f); line(0.24f, 0.84f, 0.76f, 0.84f)
        }
        LifeTile.GIFTS -> {
            // A wrapped box with its bow.
            box(0.14f, 0.4f, 0.72f, 0.48f, 0.06f); line(0.1f, 0.4f, 0.9f, 0.4f); line(0.5f, 0.4f, 0.5f, 0.88f)
            path { moveTo(w * 0.5f, w * 0.4f); quadraticTo(w * 0.26f, w * 0.12f, w * 0.34f, w * 0.38f) }
            path { moveTo(w * 0.5f, w * 0.4f); quadraticTo(w * 0.74f, w * 0.12f, w * 0.66f, w * 0.38f) }
        }
        LifeTile.TRIPS -> {
            // A suitcase.
            box(0.12f, 0.32f, 0.76f, 0.52f, 0.08f); box(0.36f, 0.16f, 0.28f, 0.16f, 0.04f)
            line(0.32f, 0.32f, 0.32f, 0.84f); line(0.68f, 0.32f, 0.68f, 0.84f)
        }
        LifeTile.ERRANDS -> {
            // A map pin.
            path {
                moveTo(w * 0.5f, w * 0.9f)
                cubicTo(w * 0.18f, w * 0.56f, w * 0.18f, w * 0.12f, w * 0.5f, w * 0.12f)
                cubicTo(w * 0.82f, w * 0.12f, w * 0.82f, w * 0.56f, w * 0.5f, w * 0.9f)
            }
            ring(0.5f, 0.4f, 0.1f)
        }
        LifeTile.CAR -> {
            // A car from the side: the body with its cabin, and two wheels.
            path {
                moveTo(w * 0.1f, w * 0.66f); lineTo(w * 0.1f, w * 0.52f); lineTo(w * 0.26f, w * 0.48f)
                lineTo(w * 0.36f, w * 0.32f); lineTo(w * 0.66f, w * 0.32f); lineTo(w * 0.78f, w * 0.48f)
                lineTo(w * 0.9f, w * 0.52f); lineTo(w * 0.9f, w * 0.66f)
            }
            line(0.36f, 0.66f, 0.64f, 0.66f); line(0.26f, 0.48f, 0.78f, 0.48f)
            ring(0.27f, 0.68f, 0.08f); ring(0.73f, 0.68f, 0.08f)
        }
        LifeTile.EMERGENCY -> {
            // A cross in a rounded square.
            box(0.12f, 0.12f, 0.76f, 0.76f, 0.16f); line(0.5f, 0.3f, 0.5f, 0.7f); line(0.3f, 0.5f, 0.7f, 0.5f)
        }
        LifeTile.FACTS -> {
            // An open book.
            path { moveTo(w * 0.5f, w * 0.24f); quadraticTo(w * 0.3f, w * 0.14f, w * 0.1f, w * 0.22f); lineTo(w * 0.1f, w * 0.8f); quadraticTo(w * 0.3f, w * 0.72f, w * 0.5f, w * 0.82f) }
            path { moveTo(w * 0.5f, w * 0.24f); quadraticTo(w * 0.7f, w * 0.14f, w * 0.9f, w * 0.22f); lineTo(w * 0.9f, w * 0.8f); quadraticTo(w * 0.7f, w * 0.72f, w * 0.5f, w * 0.82f) }
            line(0.5f, 0.24f, 0.5f, 0.82f)
        }
    }
}
