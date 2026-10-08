package os.meka.android.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The breathing ring beside an empty state (motion pass 2, slice 5; catalogue "Empty states"): MEKA's brass ring with
 * a soft glow inside, slowly breathing in and out (one breath per `emptyBreathPeriod`, a smooth cosine from
 * [MotionMath.breath]) so "You're clear." feels at rest rather than blank. Motion → Off: held still, fully in.
 * Decorative: screen readers skip it (the line beside it says what it means).
 *
 * [check] draws the completion check inside (the same points as [CheckRing]'s), so an empty Needs you reads as
 * "all done" rather than an empty circle that looks like an unticked task (Fold review 2026-10-08, item 7).
 */
@Composable
fun BreathingRing(modifier: Modifier = Modifier, size: Dp = 28.dp, check: Boolean = false) {
    val reduced = Meka.reducedMotion
    val accent = Meka.colors.accent
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { elapsed = it - start }
    }
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val b = MotionMath.breath(elapsed, reduced)
        val radius = this.size.minDimension / 2 * MotionMath.breathScale(b)
        val stroke = 1.5.dp.toPx()
        // The glow: a faint accent disc that swells with the breath.
        drawCircle(accent.copy(alpha = accent.alpha * 0.18f * MotionMath.breathGlow(b)), radius = radius)
        drawCircle(accent.copy(alpha = accent.alpha * (0.55f + 0.45f * MotionMath.breathGlow(b))),
            radius = radius - stroke / 2, style = Stroke(stroke))
        if (check) {
            // The check breathes with the ring: scaled about the centre with it.
            val w = radius * 2
            val left = center.x - radius
            val top = center.y - radius
            val path = Path().apply {
                moveTo(left + w * 0.30f, top + w * 0.52f)
                lineTo(left + w * 0.44f, top + w * 0.66f)
                lineTo(left + w * 0.71f, top + w * 0.38f)
            }
            drawPath(path, accent.copy(alpha = accent.alpha * (0.7f + 0.3f * MotionMath.breathGlow(b))),
                style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
