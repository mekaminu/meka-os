package os.meka.android.ask

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MotionMath
import os.meka.core.domain.HelloOrb
import os.meka.core.domain.OrbState
import os.meka.core.domain.TalkOrb

/**
 * The Hello orb ("Hello, Navilife"; motion catalogue "Hello, Navilife"): a brass orb with a soft glowing core, larger
 * than the field's mic. Idle it breathes; while something needs him a faint warm halo breathes round it
 * ([HelloOrb.pulse]); listening it swells with his voice; thinking two arcs turn round the rim ([HelloOrb.swirl]);
 * speaking rings ripple out ([TalkOrb.ripple]). Battery-light: one Canvas, frames only while it moves. Reduced motion:
 * a still glow (and the halo held), with a level bar under it while listening. Drawing only; the caller gives it its
 * click and screen-reader label.
 */
@Composable
fun HelloOrbView(state: OrbState, level: Float, modifier: Modifier = Modifier, size: Dp = HelloOrb.SIZE.dp) {
    val reduced = Meka.reducedMotion
    val accent = Meka.colors.accent
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(reduced, state) {
        if (reduced) return@LaunchedEffect
        val start = withFrameMillis { it } - elapsed
        while (true) withFrameMillis { elapsed = it - start }
    }
    val shownLevel by animateFloatAsState(if (state == OrbState.LISTENING) level else 0f, label = "hello-level")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(size).clearAndSetSemantics { }) {
            val breath = MotionMath.breath(elapsed, reduced)
            val scale = if (reduced) 1f else HelloOrb.scale(state, shownLevel, breath)
            val base = this.size.minDimension / 2 / (1f + TalkOrb.SWELL) // room to swell and ripple inside the box
            val r = base * scale
            val ring = 2.dp.toPx()
            fun a(x: Float) = accent.copy(alpha = accent.alpha * x.coerceIn(0f, 1f))
            // Needs you: a faint warm halo breathing outside the rim.
            if (state == OrbState.NEEDS_YOU) {
                val p = HelloOrb.pulse(elapsed, reduced)
                drawCircle(
                    Brush.radialGradient(listOf(a(0.22f * (0.4f + 0.6f * p)), Color.Transparent), center, base * (1f + TalkOrb.SWELL)),
                    radius = base * (1f + TalkOrb.SWELL),
                )
            }
            // Speaking: rings ripple out from the rim.
            if (state == OrbState.SPEAKING && !reduced) {
                repeat(TalkOrb.RIPPLES) { i ->
                    val p = TalkOrb.ripple(i, elapsed)
                    drawCircle(a(0.5f * (1f - p)), radius = base * (1f + TalkOrb.SWELL * p), style = Stroke(ring * (1f - 0.5f * p)))
                }
            }
            // The core: a glow brighter at the centre, lit while he talks or it speaks.
            val lit = state == OrbState.LISTENING || state == OrbState.SPEAKING
            val glow = when (state) {
                OrbState.LISTENING -> 0.45f + 0.55f * shownLevel
                OrbState.SPEAKING -> 0.85f
                else -> MotionMath.breathGlow(breath)
            }
            drawCircle(
                Brush.radialGradient(listOf(a(if (lit) 0.75f else 0.35f * glow + 0.15f), a(0.06f)), center, r),
                radius = r,
            )
            drawCircle(a(0.6f + 0.4f * glow), radius = r - ring / 2, style = Stroke(ring))
            // A fine inner hairline for depth (the watch face's bezel, smaller).
            drawCircle(a(0.25f), radius = r * 0.72f, style = Stroke(ring / 2))
            // Thinking: two arcs turning round the rim.
            if (state == OrbState.THINKING) {
                val turn = HelloOrb.swirl(elapsed, reduced) * 360f
                val arcR = r + ring * 2.5f
                repeat(2) { k ->
                    drawArc(
                        a(0.9f), startAngle = turn + 180f * k - 90f, sweepAngle = HelloOrb.ARC_DEG, useCenter = false,
                        topLeft = Offset(center.x - arcR, center.y - arcR), size = Size(arcR * 2, arcR * 2),
                        style = Stroke(ring * 1.5f, cap = StrokeCap.Round),
                    )
                }
            }
        }
        if (reduced && state == OrbState.LISTENING) {
            // Reduced motion: the voice level as a still bar instead of the swell.
            Box(Modifier.padding(top = MekaSpace.xs).width(size / 2).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Meka.colors.surfaceRaised)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0f, 1f)).background(accent))
            }
        }
    }
}
