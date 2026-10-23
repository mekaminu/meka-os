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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import os.meka.core.domain.Constellation
import os.meka.core.domain.ConstellationFrame
import os.meka.core.domain.HelloOrb
import kotlin.math.PI
import os.meka.core.domain.OrbState

/**
 * The Hello orb ("Hello, Navilife"; motion catalogue "Hello, Navilife"), drawn as the **gold particle constellation**
 * (Meka 2026-10-19 19:19): ~300 points on a slowly turning sphere, each joined to its nearest neighbours by faint lines,
 * depth-shaded, a soft gold core and drifting dust, drawn with additive blending in Dark ([Constellation] works every
 * frame out, the same numbers as the Mac). Idle it turns and breathes; listening the points shiver with his voice;
 * thinking it tightens and spins, the lines brighten and three tilted orbit rings carry bright comets; speaking the
 * points push out with the voice, the dust streams, a gold ring lights round the equator and a soft shockwave goes out
 * on each beat; needs-you a warm halo and two amber comets. Battery-light: one Canvas, frames only while it's on screen
 * and moving. Reduced motion (and [still]: the bedside clock in quiet hours): the constellation held still with a calm
 * glow, and a level bar under it while listening. Drawing only; the caller gives it its click and screen-reader label.
 */
@Composable
fun HelloOrbView(
    state: OrbState, level: Float, modifier: Modifier = Modifier, size: Dp = HelloOrb.SIZE.dp, still: Boolean = false,
    /** The entrance (slice 7, [os.meka.core.domain.HelloEntrance]): how far the points are flung out, to [reach]× the radius, and its one shockwave. */
    spread: Float = 0f, reach: Float = 1f, entranceShock: Pair<Float, Float>? = null,
    /** Obi's face, slice 3c: how much the points hint at his face (0…1, [os.meka.core.domain.FaceLook.pointShade]); the docked orb while he speaks. */
    faceHint: Float = 0f,
    /** Obi's face, slice 3d: how far the points have gathered into his face (0…1, [os.meka.core.domain.FaceGather]); the rings and comets let go with it. */
    gather: Float = 0f,
) {
    val reduced = Meka.reducedMotion || still
    val accent = Meka.colors.accent
    val amber = Meka.colors.calendar3
    val dark = Meka.theme.isDark
    // Dark: a brighter gold for the near points and the comets (the watch face's bright brass); Light: the accent.
    val bright = if (dark) Color(0xFFECD79E) else accent
    val blend = if (dark) BlendMode.Plus else BlendMode.SrcOver
    val n = Constellation.points(size.value.toInt(), mac = false)
    val geo = remember(n) { Constellation.geometry(n) }
    val frame = remember(n) { ConstellationFrame(n, Constellation.dust(size.value.toInt())) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var angle by remember { mutableFloatStateOf(0.6f) }
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        var last = withFrameMillis { it }
        val start = last - elapsed
        while (true) withFrameMillis {
            angle = Constellation.advance(angle, it - last, currentState, reduced = false)
            last = it
            elapsed = it - start
        }
    }
    val shownLevel by animateFloatAsState(if (state == OrbState.LISTENING || state == OrbState.SPEAKING) level else 0f, label = "hello-level")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(size).clearAndSetSemantics { }) {
            Constellation.frame(geo, frame, state, shownLevel, angle, elapsed, reduced, spread, reach)
            os.meka.core.domain.FaceGather.apply(geo, frame, gather)
            val ext = 1f - gather.coerceIn(0f, 1f) // the rings, comets and halo let go as the points gather into the face
            val c = center
            val half = this.size.minDimension / 2
            val r = half / Constellation.EXTENT
            val hair = (if (n <= Constellation.POINTS_SMALL) 0.5f else 0.6f).dp.toPx()
            fun gold(x: Float) = accent.copy(alpha = (accent.alpha * x).coerceIn(0f, 1f))
            fun shine(x: Float) = bright.copy(alpha = x.coerceIn(0f, 1f))
            fun ellipse(phi: Float, squash: Float, radius: Float): Path = Path().apply {
                for (k in 0..48) {
                    val (x, y) = Constellation.ringPoint(phi, squash, (2 * PI * k / 48).toFloat(), radius)
                    if (k == 0) moveTo(c.x + x * r, c.y + y * r) else lineTo(c.x + x * r, c.y + y * r)
                }
            }
            fun comet(phi: Float, squash: Float, radius: Float, theta: Float, color: Color, head: Float) {
                for (t in 0 until 9) { // the head, then a fading tail behind it
                    val (x, y) = Constellation.ringPoint(phi, squash, theta - t * 0.07f, radius)
                    val fade = 1f - t / 9f
                    drawCircle(color.copy(alpha = (0.95f * fade * fade * ext).coerceIn(0f, 1f)), radius = head * (0.45f + 0.55f * fade),
                        center = Offset(c.x + x * r, c.y + y * r), blendMode = blend)
                }
            }
            // Needs you: a warm amber halo breathing outside the sphere.
            if (state == OrbState.NEEDS_YOU) {
                val p = HelloOrb.pulse(elapsed, reduced)
                drawCircle(
                    Brush.radialGradient(listOf(amber.copy(alpha = 0.20f * (0.4f + 0.6f * p) * ext), Color.Transparent), c, half),
                    radius = half,
                )
            }
            // The core: a soft gold glow, brighter at the centre.
            drawCircle(
                Brush.radialGradient(listOf(shine(0.55f * frame.core), gold(0.18f * frame.core), Color.Transparent), c, r * 0.9f),
                radius = r * 0.9f, blendMode = blend,
            )
            // Speaking: the soft shockwave on the beat.
            Constellation.shock(state, elapsed, reduced)?.let { (reach, a) ->
                drawCircle(gold(a * ext), radius = r * reach, style = Stroke(hair * 3 * (1f - (reach - 1f))), blendMode = blend)
            }
            // The entrance: one gold shockwave as the points re-form.
            entranceShock?.let { (reach2, a) ->
                drawCircle(shine(a), radius = r * reach2, style = Stroke(hair * 4 * (1.6f - (reach2 - 1f) / 1.1f).coerceAtLeast(0.4f)), blendMode = blend)
            }
            // The lines, in four bands of brightness (one path each, so a few hundred lines stay cheap).
            val bands = Array(4) { Path() }
            for (k in 0 until geo.linkCount) {
                val a = geo.links[2 * k]; val b = geo.links[2 * k + 1]
                val la = Constellation.linkAlpha(frame.depth[a], frame.depth[b], frame.linkBoost)
                if (la < 0.02f) continue
                val band = (la / 0.15f).toInt().coerceIn(0, 3)
                bands[band].moveTo(c.x + frame.px[a] * r, c.y + frame.py[a] * r)
                bands[band].lineTo(c.x + frame.px[b] * r, c.y + frame.py[b] * r)
            }
            bands.forEachIndexed { i, p -> drawPath(p, gold(0.075f + 0.15f * i), style = Stroke(hair), blendMode = blend) }
            // The points, far to near; the nearest catch the bright gold.
            for (i in 0 until n) {
                val d = frame.depth[i]
                val shade = if (faceHint > 0f) os.meka.core.domain.FaceLook.pointShade(frame.px[i], frame.py[i], faceHint) else 1f
                drawCircle(
                    (if (d > 0.7f) bright else accent).copy(alpha = (frame.alpha[i] * shade).coerceIn(0f, 1f)),
                    radius = Constellation.dotRadius(d, n) * r,
                    center = Offset(c.x + frame.px[i] * r, c.y + frame.py[i] * r), blendMode = blend,
                )
            }
            // The dust drifting out.
            for (k in 0 until frame.dustN) {
                drawCircle(gold(frame.da[k]), radius = hair * 1.2f, center = Offset(c.x + frame.dx[k] * r, c.y + frame.dy[k] * r), blendMode = blend)
            }
            // Thinking: three tilted orbit rings, each with a bright comet.
            if (state == OrbState.THINKING) {
                Constellation.RINGS.forEachIndexed { k, (phi, squash) ->
                    drawPath(ellipse(phi, squash, Constellation.RING_R), gold(0.22f * ext), style = Stroke(hair), blendMode = blend)
                    if (!reduced) comet(phi, squash, Constellation.RING_R, Constellation.comet(k, elapsed), bright, hair * 3.2f)
                }
            }
            // Speaking: a voice-reactive gold ring round the equator.
            if (state == OrbState.SPEAKING) {
                val (w, a) = Constellation.equator(frame.amp)
                drawPath(ellipse(0f, Constellation.EQUATOR_SQUASH, Constellation.EQUATOR_R), gold(a * ext), style = Stroke(hair * w * 1.6f), blendMode = blend)
            }
            // Needs you: two amber comets on faint rings.
            if (state == OrbState.NEEDS_YOU) {
                Constellation.AMBER_RINGS.forEachIndexed { k, (phi, squash) ->
                    drawPath(ellipse(phi, squash, Constellation.AMBER_R), amber.copy(alpha = 0.12f * ext), style = Stroke(hair), blendMode = blend)
                    val theta = if (reduced) k * PI.toFloat() else Constellation.comet(k, elapsed, Constellation.AMBER_COMET_MS)
                    comet(phi, squash, Constellation.AMBER_R, theta, amber, hair * 2.6f)
                }
            }
        }
        if (reduced && state == OrbState.LISTENING) {
            // Reduced motion: the voice level as a still bar instead of the shiver.
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
fun BedsideHelloOrb(
    needsYou: Int, onHello: () -> Unit, modifier: Modifier = Modifier, dim: Boolean = false, onPlaced: (Offset) -> Unit = {},
    /** Speaking up, slice 3: Ada is saying a moment on her own; the orb shows Speaking (lit even in quiet dimming). */
    speaking: Boolean = false,
) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) 0.97f else 1f, MekaMotion.complete(reduced), label = "helloPress")
    val alpha by animateFloatAsState(if (dim && !speaking) 0.45f else 1f, MekaMotion.themeBlend(reduced), label = "helloDim")
    val line = if (speaking) os.meka.core.domain.HelloDock.label(TalkPhase.SPEAKING, needsYou) else HelloOrb.restingLine(needsYou, mac = false)
    val state = HelloOrb.state(if (speaking) TalkPhase.SPEAKING else TalkPhase.ENDED, needsYou)
    HelloOrbView(
        state, os.meka.core.domain.HelloDock.level(state), size = HelloOrb.BEDSIDE_SIZE.dp, still = dim && !speaking,
        modifier = modifier.onGloballyPositioned { onPlaced(it.boundsInWindow().center) }
            .scale(scale).graphicsLayer { this.alpha = alpha }.clip(RoundedCornerShape(MekaRadius.pill))
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

/**
 * "Hello, Navilife" slice 5 (placement B): the small docked orb in the middle of the bar (and the open Fold's rail) on
 * every screen: the 80-point constellation at [os.meka.core.domain.HelloDock.SIZE], raised a little, amber with two
 * comets while Needs you has cards, shivering while a conversation runs. It presses in to 0.94 on the complete spring
 * with a light haptic; [onTap] says hello, or goes back to the conversation ([os.meka.core.domain.HelloDock.tap]).
 * Reduced motion: a still glow, no press scale.
 */
@Composable
fun DockedHelloOrb(phase: TalkPhase, needsYou: Int, onTap: () -> Unit, modifier: Modifier = Modifier, onPlaced: (Offset) -> Unit = {}) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) 0.94f else 1f, MekaMotion.complete(reduced), label = "dockPress")
    val state = os.meka.core.domain.HelloDock.state(phase, needsYou)
    // Obi's face, slice 3c: it stays an orb, but while he speaks with the face shown its points hint at his face.
    val faceReady by ObiFaceStore.ready.collectAsState()
    val faceOn by ObiFaceStore.on.collectAsState()
    val faceShown = os.meka.core.domain.FaceFileRules.shows(faceReady, faceOn)
    val faceHint by animateFloatAsState(
        os.meka.core.domain.FaceLook.hint(state, faceShown),
        if (reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(os.meka.core.domain.FaceLook.HINT_MS.toInt()),
        label = "dockFace",
    )
    HelloOrbView(
        state, os.meka.core.domain.HelloDock.level(state), size = os.meka.core.domain.HelloDock.SIZE.dp, faceHint = faceHint,
        modifier = modifier.onGloballyPositioned { onPlaced(it.boundsInWindow().center) }.scale(scale).clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(interactionSource = source, indication = null, role = Role.Button) { haptics.light(); onTap() }
            .semantics { contentDescription = os.meka.core.domain.HelloDock.label(phase, needsYou) },
    )
}
