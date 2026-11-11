package os.meka.android.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Walk-through 2026-11-08 A13: Trip lists' and Study's fold marks were a bare caption-size "⌄" (about 8 dp of ink,
 * sitting low on the line) and their "⋯" a bare glyph with nothing to say it could be pressed. These are the drawn
 * marks that replace them.
 */
object FoldMarkSpec {
    /** The chevron's drawn square: big enough to read at a glance beside a count. */
    const val CHEVRON_DP = 20
    /** The ⋯ button's drawn disc: the settings pills' 32 dp, inside the 48 dp target. */
    const val MORE_DISC_DP = 32
}

/**
 * A fold chevron, drawn (1.75 dp stroke, round caps like every MEKA line icon) in a [FoldMarkSpec.CHEVRON_DP] square,
 * pointing down when folded and turning to point up as it opens on the expand spring (a quick turn under reduced
 * motion). Decorative: its row says "tap to open" / "tap to fold". Tagged `fold-chevron`.
 */
@Composable
fun FoldChevron(open: Boolean, color: Color, modifier: Modifier = Modifier) {
    val turn by animateFloatAsState(if (open) 180f else 0f, MekaMotion.expand(Meka.reducedMotion), label = "fold-chevron")
    Canvas(modifier.testTag("fold-chevron").size(FoldMarkSpec.CHEVRON_DP.dp).rotate(turn).clearAndSetSemantics { }) {
        val w = size.minDimension
        val path = Path().apply {
            moveTo(w * 0.28f, w * 0.40f)
            lineTo(w * 0.5f, w * 0.62f)
            lineTo(w * 0.72f, w * 0.40f)
        }
        drawPath(path, color, style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * The "⋯" button: a whole 48 × 48 target with a [FoldMarkSpec.MORE_DISC_DP] disc drawn on the raised surface and
 * three dots on it in [ink], so it reads as something to press. [spoken] is what TalkBack reads. The disc is tagged
 * `more-disc` so the shots can measure it; the target carries [tag].
 */
@Composable
fun MoreButton(spoken: String, ink: Color, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }.size(MekaSpace.touch).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        val disc = Meka.colors.surfaceRaised
        Canvas(Modifier.testTag("more-disc").size(FoldMarkSpec.MORE_DISC_DP.dp).clip(CircleShape).background(disc)) {
            val r = 1.6.dp.toPx()
            val gap = 5.dp.toPx() // rhythm: ok (the dots' spacing inside the mark)
            val c = center
            for (i in -1..1) drawCircle(ink, radius = r, center = Offset(c.x + i * gap, c.y))
        }
    }
}
