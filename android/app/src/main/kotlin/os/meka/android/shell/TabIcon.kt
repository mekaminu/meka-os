package os.meka.android.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MotionMath

/**
 * A tab's icon (motion pass 2, screen-level motion; catalogue "Switch section"): an outline while the tab is quiet,
 * filled when it's lit. Switching morphs it on the approve spring: the flood grows inside the outline from half the
 * shape ([MotionMath.tabFillGrow]) while the icon swells to `tabIconPopScale` and settles ([MotionMath.tabIconScale]);
 * the inner marks ("!", the dates, the bubble's dots) turn to [knockout] as it fills. Motion → Off: the fill
 * cross-fades, no swell. Decorative: the tab itself carries the label for screen readers.
 */
@Composable
fun TabIcon(glyph: TabGlyph, lit: Boolean, color: Color, knockout: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val reduced = Meka.reducedMotion
    val progress by animateFloatAsState(
        if (lit) 1f else 0f,
        if (reduced) MekaMotion.appear(true) else MekaMotion.approve(false),
        label = "tab-icon",
    )
    Canvas(
        modifier.size(size).clearAndSetSemantics { }.graphicsLayer {
            val s = MotionMath.tabIconScale(progress, reduced); scaleX = s; scaleY = s
        },
    ) {
        drawTabGlyph(glyph, MotionMath.tabFill(progress), MotionMath.tabFillGrow(progress), color, knockout)
    }
}

/** Draws [glyph] with [fill] (0 outline … 1 filled) flooding in at [grow] of its size. */
private fun DrawScope.drawTabGlyph(glyph: TabGlyph, fill: Float, grow: Float, color: Color, knockout: Color) {
    val w = size.minDimension
    val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val flood = color.copy(alpha = color.alpha * fill)
    // The inner marks: the icon's colour on the outline, the knock-out colour on the fill.
    val mark = lerp(color, knockout, fill)
    fun p(x: Float, y: Float) = Offset(w * x, w * y)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, c: Color = color) =
        drawLine(c, p(x1, y1), p(x2, y2), stroke.width, cap = StrokeCap.Round)
    fun flooded(pivot: Offset, shape: Path) {
        if (fill > 0f) scale(grow, pivot) { drawPath(shape, flood) }
        drawPath(shape, color, style = stroke)
    }
    when (glyph) {
        TabGlyph.DAY -> {
            // A sun half risen over the horizon, three short rays.
            val sun = Path().apply {
                arcTo(Rect(center = p(0.5f, 0.7f), radius = w * 0.25f), 180f, 180f, forceMoveTo = true)
                close()
            }
            flooded(p(0.5f, 0.7f), sun)
            line(0.08f, 0.7f, 0.92f, 0.7f)
            line(0.5f, 0.2f, 0.5f, 0.31f)
            line(0.18f, 0.36f, 0.25f, 0.43f)
            line(0.82f, 0.36f, 0.75f, 0.43f)
        }
        TabGlyph.NEEDS -> {
            flooded(p(0.5f, 0.5f), Path().apply { addOval(Rect(center = p(0.5f, 0.5f), radius = w * 0.38f)) })
            line(0.5f, 0.3f, 0.5f, 0.55f, mark)
            drawCircle(mark, radius = w * 0.045f, center = p(0.5f, 0.69f))
        }
        TabGlyph.CALENDAR -> {
            val page = Path().apply {
                addRoundRect(RoundRect(Rect(p(0.14f, 0.22f), Size(w * 0.72f, w * 0.64f)), CornerRadius(w * 0.1f)))
            }
            flooded(p(0.5f, 0.54f), page)
            line(0.34f, 0.12f, 0.34f, 0.28f)
            line(0.66f, 0.12f, 0.66f, 0.28f)
            line(0.16f, 0.4f, 0.84f, 0.4f, mark)
            listOf(0.33f to 0.56f, 0.5f to 0.56f, 0.67f to 0.56f, 0.33f to 0.72f, 0.5f to 0.72f).forEach { (x, y) ->
                drawCircle(mark, radius = w * 0.04f, center = p(x, y))
            }
        }
        TabGlyph.ASK -> {
            // A speech bubble with its tail at the lower left and three dots inside.
            val body = Path().apply {
                addRoundRect(RoundRect(Rect(p(0.1f, 0.16f), Size(w * 0.8f, w * 0.54f)), CornerRadius(w * 0.16f)))
            }
            val tail = Path().apply {
                moveTo(w * 0.26f, w * 0.66f); lineTo(w * 0.22f, w * 0.88f); lineTo(w * 0.48f, w * 0.66f); close()
            }
            flooded(p(0.5f, 0.45f), Path().apply { op(body, tail, PathOperation.Union) })
            listOf(0.33f, 0.5f, 0.67f).forEach { x -> drawCircle(mark, radius = w * 0.045f, center = p(x, 0.43f)) }
        }
    }
}
