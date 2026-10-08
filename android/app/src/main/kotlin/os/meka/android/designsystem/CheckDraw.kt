package os.meka.android.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The completion check (motion pass 2, slice 4; catalogue "Complete a task"): a quiet outline at rest; as [fraction]
 * runs 0 → 1 the accent ring sweeps once round from the top, the inside floods with the accent as it closes, and the
 * check strokes in over the second half (short leg first). The phases come from [MotionMath] so the Mac draws the same.
 * Motion → Off: callers pass 1 straight away, so it shows done at once.
 */
@Composable
fun CheckRing(fraction: Float, rest: Color, accent: Color, onAccent: Color, modifier: Modifier = Modifier) {
    val path = remember { Path() }
    val measure = remember { PathMeasure() }
    val part = remember { Path() }
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        val inset = stroke / 2
        val box = Size(size.width - stroke, size.height - stroke)
        drawCircle(rest, radius = size.minDimension / 2 - inset, style = Stroke(stroke))
        val ring = MotionMath.checkRingDegrees(fraction)
        if (ring > 0f) {
            drawArc(accent, startAngle = -90f, sweepAngle = ring, useCenter = false, topLeft = Offset(inset, inset), size = box,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        val fill = MotionMath.checkFill(fraction)
        if (fill > 0f) drawCircle(accent.copy(alpha = accent.alpha * fill), radius = size.minDimension / 2)
        val drawn = MotionMath.checkStroke(fraction)
        if (drawn > 0f) {
            val w = size.width
            val h = size.height
            path.reset()
            path.moveTo(w * 0.30f, h * 0.52f)
            path.lineTo(w * 0.44f, h * 0.66f)
            path.lineTo(w * 0.71f, h * 0.38f)
            measure.setPath(path, false)
            part.reset()
            measure.getSegment(0f, measure.length * drawn, part, true)
            drawPath(part, onAccent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
