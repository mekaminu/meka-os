package os.meka.android.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Swipe actions (motion pass 2, feedback motion; catalogue "Swipe actions"): what sits behind a swiped row. The
 * action's colour shows as soon as the row moves and deepens to full at the arm point ([MotionMath.swipeTint]); its
 * icon and label fade in, the icon growing with the swipe and popping (spring) to `swipeIconPopScale` once armed
 * ([MotionMath.swipeIconScale]). Motion → Off: the colour still deepens, the icon is full size with no pop.
 */

/** The small line icons swipe actions use (drawn, so the app needs no icon library). */
enum class SwipeGlyph { ADD, HIDE, CHECK, LATER, OPEN }

/** One [glyph] in [color], stroked like MEKA's check (round caps), [size] square. Decorative. */
@Composable
fun SwipeGlyphIcon(glyph: SwipeGlyph, color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val w = this.size.minDimension
        val stroke = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(w * x, w * y)
        fun DrawScope.line(vararg pts: Offset) {
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(path, color, style = stroke)
        }
        when (glyph) {
            SwipeGlyph.ADD -> { line(p(0.5f, 0.18f), p(0.5f, 0.82f)); line(p(0.18f, 0.5f), p(0.82f, 0.5f)) }
            SwipeGlyph.CHECK -> line(p(0.2f, 0.52f), p(0.41f, 0.72f), p(0.8f, 0.3f))
            SwipeGlyph.OPEN -> { line(p(0.5f, 0.82f), p(0.5f, 0.2f)); line(p(0.26f, 0.44f), p(0.5f, 0.2f), p(0.74f, 0.44f)) }
            SwipeGlyph.LATER -> {
                drawCircle(color, radius = w * 0.36f, center = p(0.5f, 0.5f), style = stroke)
                line(p(0.5f, 0.3f), p(0.5f, 0.5f), p(0.64f, 0.6f))
            }
            SwipeGlyph.HIDE -> {
                val eye = Path().apply {
                    moveTo(w * 0.1f, w * 0.5f)
                    quadraticTo(w * 0.5f, w * 0.12f, w * 0.9f, w * 0.5f)
                    quadraticTo(w * 0.5f, w * 0.88f, w * 0.1f, w * 0.5f)
                }
                drawPath(eye, color, style = stroke)
                drawCircle(color, radius = w * 0.1f, center = p(0.5f, 0.5f), style = stroke)
                line(p(0.18f, 0.82f), p(0.82f, 0.18f))
            }
        }
    }
}

/** The icon with its pop: grows with [progress], springs to the pop once [armed]; Off: full size. */
@Composable
fun SwipeActionIcon(glyph: SwipeGlyph, color: Color, progress: Float, armed: Boolean, modifier: Modifier = Modifier) {
    val reduced = Meka.reducedMotion
    // Growing follows the finger (no lag); the pop and its release spring.
    val pop by animateFloatAsState(
        if (armed && !reduced) MotionMath.swipeIconScale(progress, true, false) else 1f,
        MekaMotion.approve(reduced), label = "swipe-icon-pop",
    )
    val grow = if (armed) 1f else MotionMath.swipeIconScale(progress, false, reduced)
    SwipeGlyphIcon(glyph, color, modifier.graphicsLayer { scaleX = grow * pop; scaleY = grow * pop })
}

/**
 * The layer behind a row swiped [side] (1 right, -1 left) [progress] of the way to arming: the action's [fill]
 * colour with its [glyph] and [label] in [content] colour, at the edge the row is leaving.
 */
@Composable
fun BoxScope.SwipeBackdrop(
    side: Int,
    progress: Float,
    armed: Boolean,
    glyph: SwipeGlyph,
    label: String,
    fill: Color,
    content: Color,
) {
    if (side == 0) return
    Box(
        Modifier.matchParentSize().clip(RoundedCornerShape(MekaRadius.m))
            .background(fill.copy(alpha = fill.alpha * MotionMath.swipeTint(progress)))
            .padding(horizontal = MekaSpace.l),
        contentAlignment = if (side > 0) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.graphicsLayer { alpha = MotionMath.swipeIconAlpha(progress) },
        ) {
            if (side < 0) Text(label, style = MekaType.itemMeta, color = content)
            SwipeActionIcon(glyph, content, progress, armed)
            if (side > 0) Text(label, style = MekaType.itemMeta, color = content)
        }
    }
}
