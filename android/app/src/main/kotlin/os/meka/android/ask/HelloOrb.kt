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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.TalkPhase
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
 * click and screen-reader label. [still]: drawn as with reduced motion (the bedside clock in quiet hours, no frames).
 */
@Composable
fun HelloOrbView(state: OrbState, level: Float, modifier: Modifier = Modifier, size: Dp = HelloOrb.SIZE.dp, still: Boolean = false) {
    val reduced = Meka.reducedMotion || still
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

/**
 * "Hello, Navilife" slice 2: the Hello orb on the bedside clock (the Fold on its stand), where the mic was: the resting
 * orb at [HelloOrb.BEDSIDE_SIZE], breathing (a warm halo while Needs you has cards), pressing in to 0.97 on the complete
 * spring with a light haptic; [onHello] springs the Talk pane up and greets him. [dim]: quiet hours at the bedside, it
 * quietens with the clock and holds still (no frames drawn all night). Reduced motion: no press scale, a still glow.
 */
@Composable
fun BedsideHelloOrb(needsYou: Int, onHello: () -> Unit, modifier: Modifier = Modifier, dim: Boolean = false) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) 0.97f else 1f, MekaMotion.complete(reduced), label = "helloPress")
    val alpha by animateFloatAsState(if (dim) 0.45f else 1f, MekaMotion.themeBlend(reduced), label = "helloDim")
    val line = HelloOrb.restingLine(needsYou, mac = false)
    HelloOrbView(
        HelloOrb.state(TalkPhase.ENDED, needsYou), 0f, size = HelloOrb.BEDSIDE_SIZE.dp, still = dim,
        modifier = modifier.scale(scale).graphicsLayer { this.alpha = alpha }.clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(interactionSource = source, indication = null, role = Role.Button) { haptics.light(); onHello() }
            .semantics { contentDescription = "Navilife · $line" },
    )
}

/**
 * "Hello, Navilife" slice 3: the visible sign that Navilife is listening for its phrase on the stand: a small brass
 * dot and "Listening for “Hello Navilife”" ([os.meka.core.domain.HelloWakeRules.LISTENING_LINE]) in the caption style.
 * It fades in and out with [listening] (the appear spec); reduced motion: the same cross-fade, nothing moves.
 */
@Composable
fun HelloWakeLine(listening: Boolean, modifier: Modifier = Modifier) {
    val reduced = Meka.reducedMotion
    val alpha by animateFloatAsState(if (listening) 1f else 0f, MekaMotion.appear(reduced), label = "helloWake")
    if (alpha <= 0f) return
    androidx.compose.foundation.layout.Row(
        modifier.graphicsLayer { this.alpha = alpha }.semantics { contentDescription = os.meka.core.domain.HelloWakeRules.LISTENING_LINE },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
        androidx.compose.foundation.layout.Spacer(Modifier.width(MekaSpace.xs))
        androidx.compose.material3.Text(
            os.meka.core.domain.HelloWakeRules.LISTENING_LINE,
            style = os.meka.android.designsystem.MekaType.caption, color = Meka.colors.textTertiary,
        )
    }
}
