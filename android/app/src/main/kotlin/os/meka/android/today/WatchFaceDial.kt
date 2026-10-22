package os.meka.android.today

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.MotionPrefs
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.DayArc
import os.meka.core.domain.DayRing
import os.meka.core.domain.DayRingLive
import os.meka.core.domain.DayRingLiveMode
import os.meka.core.domain.DayRingPlay
import os.meka.core.domain.DayTile
import os.meka.core.domain.GoalWeekRules
import os.meka.core.domain.HabitDot
import os.meka.core.domain.WeekGoals
import os.meka.core.domain.HabitDotRules
import os.meka.core.domain.WatchFace
import os.meka.core.domain.WatchFaceLook
import os.meka.core.domain.WatchFaceRules
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The watch face's test tag: Today's UI tests look for it in the header on the closed and open Fold. */
const val WATCH_FACE_TAG = "watch-face"

/**
 * Today's watch face (Fold review 2026-10-09 07:26, item 2; catalogue "Opening moment" and "Living Today"): a 12-hour
 * face in the header — the brass rim with 12 markers (no numerals; 12, 3, 6 and 9 heavier), the next 12 hours of
 * events as arcs on the rim (work a faint band, the gym, Barça and training wider, the one on now glowing), gold
 * tapered hour and minute hands, and a thin glowing sweeping second hand ([WatchFaceLook]). The opening draws it in like
 * the Day ring did: the rim draws itself round from 12, the markers fade in behind it, the arcs grow clockwise one
 * after another and the hands sweep round from 12 to the time. Then it lives: the rim breathes, the hour's shimmer
 * runs, the second hand sweeps. Tapping it opens the full 24-hour Day ring ([onOpen]). Screen readers hear one line.
 *
 * Calm Today, slice 2: today's habits sit on the face as small dots just inside the markers, centred on 6 o'clock
 * ([dots], [HabitDotRules]): hollow until done (behind for the week: a full-accent ring), filled once ticked. A tap on
 * a dot ticks it ([onTick]: the fill floods in and the dot pops on the complete spring; unticking empties it at once);
 * anywhere else on the face opens the day. Screen readers get one action per habit.
 */
@Composable
fun WatchFaceDial(
    face: WatchFace,
    play: DayRingPlay,
    played: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
    onOpen: (() -> Unit)? = null,
    /**
     * The bedside clock's large face (slice 2): a calmer 8 s breath, and in [quiet] hours no second hand, breath or
     * shimmer — redrawn each minute so the hands keep time ([WatchFaceRules.liveMode]).
     */
    bedside: Boolean = false,
    quiet: Boolean = false,
    dots: List<HabitDot> = emptyList(),
    onTick: ((HabitDot) -> Unit)? = null,
    /** Done for the day: the day is shut down, so the face rests like the bedside's in quiet hours (no second hand or breath). */
    night: Boolean = false,
    /**
     * Goals, properly, slice 7: this week's goals as arcs on the face's outermost ring, just outside the rim
     * ([GoalWeekRules]): one segment each round from 12, a faint track filled by the goal's progress — the full accent
     * while it's moving this week, quieter until it does. They draw in behind the rim on the opening.
     */
    week: WeekGoals = WeekGoals.EMPTY,
) {
    val sizeDp = size.value.toInt()
    val expressive = Meka.expressiveMotion
    val total = remember(play) { MotionMath.dayRingTotalMs(face.arcs.size, play, expressive, 0) }
    var elapsed by remember(play) { mutableLongStateOf(if (play == DayRingPlay.STILL) total else 0L) }
    val onPlayed by rememberUpdatedState(played)
    LaunchedEffect(play) {
        if (play != DayRingPlay.STILL) {
            val start = withFrameMillis { it }
            while (elapsed < total) withFrameMillis { elapsed = it - start }
        }
        onPlayed()
    }
    val colors = Meka.colors
    val mark = MotionMath.dayRingMark(elapsed, play, expressive)
    val landed = play == DayRingPlay.STILL || elapsed >= total
    val needle = MotionMath.dayRingNeedle(elapsed, play, expressive)
    val haptics = rememberMekaHaptics()
    val open by rememberUpdatedState(onOpen)
    val tickDot by rememberUpdatedState(onTick)
    val reduced = Meka.reducedMotion
    // Each dot's fill: floods in on the complete spring when ticked here (already done shows full), empties at once.
    val fills = dots.map { d ->
        key(d.id) {
            animateFloatAsState(if (d.done) 1f else 0f, if (d.done && !reduced) MekaMotion.complete<Float>(false) else snap<Float>(), label = "habit-dot")
        }
    }
    val press = remember { MutableInteractionSource() }
    val tapping = if (dots.isEmpty() || onTick == null) {
        // Presses in like anything tappable (the theme's press indication), with a tick haptic.
        if (onOpen == null) Modifier else Modifier.clickable(role = Role.Button) { haptics.tick(); open?.invoke() }
    } else {
        // With habit dots: a tap near a dot ticks it, anywhere else opens the day; the face presses in either way.
        Modifier.indication(press, LocalIndication.current).pointerInput(dots, sizeDp) {
            detectTapGestures(
                onPress = { at ->
                    val p = PressInteraction.Press(at)
                    press.emit(p)
                    press.emit(if (tryAwaitRelease()) PressInteraction.Release(p) else PressInteraction.Cancel(p))
                },
                onTap = { at ->
                    val x = (at.x - this.size.width / 2f).toDp().value
                    val y = (at.y - this.size.height / 2f).toDp().value
                    val dot = HabitDotRules.hit(dots, x, y, sizeDp)
                    if (dot != null) tickDot?.invoke(dot) else open?.let { haptics.tick(); it() }
                },
            )
        }
    }
    Box(
        modifier.size(size)
            .then(tapping)
            .clearAndSetSemantics {
                testTag = WATCH_FACE_TAG
                contentDescription = face.spokenLine + HabitDotRules.spokenLine(dots) + week.spoken.let { if (it.isEmpty()) it else " $it" }
                if (dots.isNotEmpty() && onTick != null) {
                    if (onOpen != null) {
                        role = Role.Button
                        onClick(label = "Open your whole day") { open?.invoke(); true }
                    }
                    customActions = dots.map { d -> CustomAccessibilityAction(d.tickLabel) { tickDot?.invoke(d); true } }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val rim = WatchFaceRules.rimStrokeDp(sizeDp).dp.toPx()
            val radius = WatchFaceRules.rimRadiusDp(sizeDp).dp.toPx()
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            // Watch face item (2026-10-12 18:04): the arcs keep a faint track of their own inside the bezel, and a fine
            // darker hairline sits just inside the bezel for depth; both draw themselves round from 12.
            drawArc(colors.accent.copy(alpha = colors.accent.alpha * WatchFaceLook.ARC_TRACK_ALPHA), -90f, 360f * mark, false, topLeft,
                arcSize, style = Stroke(WatchFaceLook.ARC_TRACK_DP.dp.toPx()))
            val hair = WatchFaceLook.hairlineRadiusDp(sizeDp).dp.toPx()
            drawArc(colors.accent.copy(alpha = colors.accent.alpha * WatchFaceLook.HAIRLINE_ALPHA), -90f, 360f * mark, false,
                Offset(center.x - hair, center.y - hair), Size(hair * 2, hair * 2), style = Stroke(WatchFaceLook.hairlineDp(sizeDp).dp.toPx()))
            // The thin brass bezel draws itself round from 12 on the opening; once landed the live layer breathes it.
            if (!landed) {
                val bz = WatchFaceLook.bezelRadiusDp(sizeDp).dp.toPx()
                drawArc(colors.accent.copy(alpha = colors.accent.alpha * WatchFaceLook.BEZEL_DRAW_ALPHA), -90f, 360f * mark, false,
                    Offset(center.x - bz, center.y - bz), Size(bz * 2, bz * 2), style = Stroke(WatchFaceLook.bezelDp(sizeDp).dp.toPx()))
            }
            // The 12 markers just inside the rim, fading in behind the drawing rim.
            WatchFaceRules.markers().forEachIndexed { i, m ->
                val show = MotionMath.dayRingHour(mark, i * 2, play)
                if (show <= 0f) return@forEachIndexed
                val a = Math.toRadians(m.degrees - 90.0)
                val outer = radius - rim / 2 - 2.dp.toPx()
                val inner = outer - radius * (if (m.major) WatchFaceRules.MAJOR_MARKER_LENGTH else WatchFaceRules.MINOR_MARKER_LENGTH)
                drawLine(colors.accent.copy(alpha = WatchFaceRules.MARKER_ALPHA * show),
                    Offset(center.x + (cos(a) * inner).toFloat(), center.y + (sin(a) * inner).toFloat()),
                    Offset(center.x + (cos(a) * outer).toFloat(), center.y + (sin(a) * outer).toFloat()),
                    strokeWidth = (if (m.major) WatchFaceRules.MAJOR_MARKER_DP else WatchFaceRules.MINOR_MARKER_DP).dp.toPx(),
                    cap = StrokeCap.Round)
            }
            // Work in the next 12 hours and the arcs on their own track, no heavier than the bezel (Meka 12:13: the thick
            // sweep made the rim read as the old one): work fainter underneath, events and tasks over it.
            val arcStroke = WatchFaceLook.arcStrokeDp(sizeDp).dp.toPx()
            face.work.forEach { band ->
                drawArc(colors.accent.copy(alpha = colors.accent.alpha * WatchFaceLook.WORK_ALPHA * mark), band.startDegrees - 90f,
                    band.sweepDegrees, false, topLeft, arcSize, style = Stroke(arcStroke, cap = StrokeCap.Butt))
            }
            // The next 12 hours' arcs, growing clockwise one after another.
            face.arcs.forEachIndexed { i, arc ->
                val grow = MotionMath.dayRingArc(elapsed, i, play, expressive)
                if (grow <= 0f) return@forEachIndexed
                val alpha = WatchFaceRules.arcAlpha(arc.kind)
                drawArc(colors.accent.copy(alpha = colors.accent.alpha * alpha), arc.startDegrees - 90f, arc.sweepDegrees * grow,
                    false, topLeft, arcSize, style = Stroke(arcStroke, cap = StrokeCap.Butt))
            }
            // This week's goals: arcs on the outermost ring, a faint track each, filled by progress as the rim passes.
            if (week.arcs.isNotEmpty()) {
                val gr = GoalWeekRules.ringRadiusDp(sizeDp).dp.toPx()
                val gTopLeft = Offset(center.x - gr, center.y - gr)
                val gSize = Size(gr * 2, gr * 2)
                val gStroke = Stroke(GoalWeekRules.RING_DP.dp.toPx(), cap = StrokeCap.Round)
                week.arcs.forEach { arc ->
                    val shown = GoalWeekRules.shown(mark, arc)
                    if (shown <= 0f) return@forEach
                    drawArc(colors.accent.copy(alpha = colors.accent.alpha * GoalWeekRules.TRACK_ALPHA), arc.startDegrees - 90f,
                        arc.sweepDegrees * shown, false, gTopLeft, gSize, style = gStroke)
                    val fill = minOf(arc.fillDegrees, arc.sweepDegrees * shown)
                    if (fill > 0f) drawArc(colors.accent.copy(alpha = colors.accent.alpha * GoalWeekRules.fillAlpha(arc)),
                        arc.startDegrees - 90f, fill, false, gTopLeft, gSize, style = gStroke)
                }
            }
            // Today's habits: small dots inside the markers at the foot of the dial, coming up as the rim passes them.
            dots.forEachIndexed { i, d ->
                val shown = HabitDotRules.shown(mark, d.degrees)
                if (shown <= 0f) return@forEachIndexed
                val p = HabitDotRules.place(d, sizeDp)
                val at = Offset(center.x + p.xDp.dp.toPx(), center.y + p.yDp.dp.toPx())
                val r = p.radiusDp.dp.toPx()
                val f = fills[i].value.coerceAtLeast(0f)
                // The pop: the whole dot swells to 1.35 while the fill floods in, then settles.
                val pop = 1f + (HabitDotRules.POP_SCALE - 1f) * sin(PI * f.coerceAtMost(1f)).toFloat()
                scale(pop, at) {
                    val ring = if (d.behind || f > 0f) 1f else HabitDotRules.OPEN_ALPHA
                    drawCircle(colors.accent.copy(alpha = colors.accent.alpha * ring * shown), r, at,
                        style = Stroke(HabitDotRules.RING_STROKE_DP.dp.toPx()))
                    if (f > 0f) drawCircle(colors.accent.copy(alpha = colors.accent.alpha * shown), r * (f * 2f).coerceAtMost(1f), at)
                }
            }
        }
        // The hands, the breathing rim, the shimmer and the second hand on a layer of their own.
        WatchFaceLiveLayer(face, landed = landed, needle = needle, size = size,
            bedside = bedside, quiet = quiet, night = night)
    }
}

/**
 * The watch face's living layer: the hour and minute hands (always — a watch shows the time even with Motion → Off),
 * and once the opening has landed the breathing rim with its soft blur, the hour's shimmer, the glow on what's on now
 * and the thin glowing second hand (the bezel and hand from [WatchFaceLook], the breath and shimmer from [DayRingLive]).
 * Only this layer reads the clock. Frames run while sweeping and MEKA is in front; power saving and Motion → Off
 * redraw once a minute, with no second hand or breath.
 */
@Composable
private fun WatchFaceLiveLayer(face: WatchFace, landed: Boolean, needle: Float, size: Dp, bedside: Boolean, quiet: Boolean, night: Boolean) {
    val context = LocalContext.current
    val reduced = Meka.reducedMotion
    val powerSave = remember { MotionPrefs.powerSave(context) }
    val mode = WatchFaceRules.liveMode(reduced, powerSave, bedside, quiet, night)
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val front = resumed.isAtLeast(Lifecycle.State.RESUMED)
    val faceClock = LocalFaceClock.current
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    var landedAt by remember { mutableLongStateOf(Long.MAX_VALUE) }
    LaunchedEffect(mode, landed, front) {
        if (landed && landedAt == Long.MAX_VALUE) landedAt = System.currentTimeMillis()
        clock.longValue = System.currentTimeMillis()
        if (!front) return@LaunchedEffect
        while (true) {
            if (mode == DayRingLiveMode.SWEEP || !landed) {
                withFrameMillis { clock.longValue = System.currentTimeMillis() }
            } else {
                // The hands still keep time: redraw as each minute turns.
                delay(DayRingLive.nextDrawInMs(DayRingLiveMode.MINUTE, System.currentTimeMillis()) ?: 60_000L)
                clock.longValue = System.currentTimeMillis()
            }
        }
    }
    val colors = Meka.colors
    val dark = Meka.theme.isDark
    val sizeDp = size.value.toInt()
    Canvas(Modifier.size(size)) {
        val now = clock.longValue // read only here: a new frame redraws this layer, nothing recomposes
        val wall = now + faceClock.shiftMs // the time the hands show: the core's clock on Today (LocalFaceClock)
        val radius = WatchFaceRules.rimRadiusDp(sizeDp).dp.toPx()
        val sweeping = landed && mode == DayRingLiveMode.SWEEP
        val fadeIn = if (sweeping) DayRingLive.handFade(now - landedAt) else 1f
        val glow = if (sweeping) WatchFaceRules.breath(now, bedside) else 0.8f
        fun box(r: Float) = Pair(Offset(center.x - r, center.y - r), Size(r * 2, r * 2))
        if (landed) {
            // The thin brass bezel (Meka 12:13: a crisp 1.2–1.5 dp line all the way round at 0.9), with a soft glow
            // reaching 6 dp out that breathes with the face.
            val edge = WatchFaceLook.bezelRadiusDp(sizeDp).dp.toPx()
            val blurStroke = WatchFaceLook.blurStrokeDp(sizeDp).dp.toPx()
            for (i in 0 until WatchFaceLook.BLUR_LAYERS) {
                drawCircle(colors.accent.copy(alpha = WatchFaceLook.blurAlpha(i, glow) * (if (sweeping) fadeIn else 1f)),
                    edge + WatchFaceLook.blurOffsetDp(i, sizeDp).dp.toPx(), center, style = Stroke(blurStroke))
            }
            // The bezel never fades out (the opening drew it in); its breath blends in as the second hand fades in.
            val shown = if (sweeping) fadeIn else 1f
            drawCircle(colors.accent.copy(alpha = colors.accent.alpha * WatchFaceLook.bezelAlpha(glow, shown)), edge, center,
                style = Stroke(WatchFaceLook.bezelDp(sizeDp).dp.toPx()))
            // What's on now glows faintly with the rim's breath.
            face.arcs.filter { it.current }.forEach { arc ->
                val (tl, sz) = box(radius)
                drawArc(colors.accent.copy(alpha = WatchFaceLook.NOW_GLOW_ALPHA * glow * shown), arc.startDegrees - 90f, arc.sweepDegrees,
                    false, tl, sz, style = Stroke((WatchFaceLook.arcStrokeDp(sizeDp) + WatchFaceLook.NOW_GLOW_EXTRA_DP).dp.toPx(),
                        cap = StrokeCap.Round))
            }
            if (sweeping) {
                // The hour's shimmer: a band of light running once round the rim.
                DayRingLive.shimmer(wall, faceClock.offsetMs(wall))?.let { s ->
                    val head = 360f * s
                    val fade = ((1f - s) * 4f).coerceIn(0f, 1f)
                    val (tl, sz) = box(edge)
                    val parts = 10
                    val step = DayRingLive.SHIMMER_BAND_DEGREES / parts
                    for (j in 0 until parts) {
                        val a = (1f - j.toFloat() / parts).let { it * it } * 0.8f * fade
                        drawArc(colors.accent.copy(alpha = a), head - step * (j + 1) - 90f, step, false, tl, sz,
                            style = Stroke(WatchFaceLook.bezelDp(sizeDp).dp.toPx() * 1.4f))
                    }
                }
            }
        }
        // The hour and minute hands: gold, tapered, sweeping round from 12 to the time as the face draws in.
        val hands = WatchFaceRules.handsAt(wall, faceClock.offsetMs(wall))
        val scale = (sizeDp / 150f).coerceIn(0.7f, 2f)
        drawHand(colors.accent, hands.minuteDegrees * needle, radius * WatchFaceRules.MINUTE_HAND_LENGTH,
            WatchFaceRules.MINUTE_HAND_BASE_DP.dp.toPx() * scale, WatchFaceRules.MINUTE_HAND_TIP_DP.dp.toPx() * scale, radius)
        drawHand(colors.accent, hands.hourDegrees * needle, radius * WatchFaceRules.HOUR_HAND_LENGTH,
            WatchFaceRules.HOUR_HAND_BASE_DP.dp.toPx() * scale, WatchFaceRules.HOUR_HAND_TIP_DP.dp.toPx() * scale, radius)
        if (sweeping) {
            // The second hand (Meka 07:01 and 12:13): a thin needle in the bright brass from a counterweight right
            // through the centre to just inside the bezel, sweeping smoothly, with a visible blurred glow under it.
            val a = Math.toRadians(hands.secondDegrees - 90.0)
            fun at(r: Float) = Offset(center.x + (cos(a) * r).toFloat(), center.y + (sin(a) * r).toFloat())
            val from = at(-WatchFaceLook.needleTailDp(sizeDp).dp.toPx())
            val to = at(WatchFaceLook.needleReachDp(sizeDp).dp.toPx())
            val needleColor = if (dark) Color(WatchFaceLook.NEEDLE_DARK_ARGB) else colors.accent
            WatchFaceLook.halo(sizeDp).forEach { layer ->
                drawGlowLine(needleColor.copy(alpha = layer.alpha * fadeIn), from, to, layer.widthDp.dp.toPx(), layer.blurDp.dp.toPx())
            }
            drawLine(needleColor.copy(alpha = fadeIn), from, to,
                strokeWidth = WatchFaceLook.needleDp(sizeDp).dp.toPx(), cap = StrokeCap.Round)
        }
        // The small cap over all three hands.
        if (needle > 0f) {
            val cap = WatchFaceLook.capDp(sizeDp).dp.toPx()
            drawCircle(colors.accent, cap, center)
            drawCircle(colors.background, cap * 0.4f, center)
        }
    }
}

/** A soft line in [tint]: a round-capped stroke blurred by [blur] px (BlurMaskFilter), the second hand's glow. */
private fun DrawScope.drawGlowLine(tint: Color, from: Offset, to: Offset, width: Float, blur: Float) {
    val argb = tint.toArgb()
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            strokeWidth = width
            strokeCap = android.graphics.Paint.Cap.ROUND
            style = android.graphics.Paint.Style.STROKE
            if (blur > 0f) maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.nativeCanvas.drawLine(from.x, from.y, to.x, to.y, paint)
    }
}

/** A tapered watch hand from the hub out to [length], with a short counterweight behind the hub. */
private fun DrawScope.drawHand(color: Color, degrees: Float, length: Float, base: Float, tip: Float, radius: Float) {
    val a = Math.toRadians(degrees - 90.0)
    val dx = cos(a).toFloat()
    val dy = sin(a).toFloat()
    val nx = -dy
    val ny = dx
    val back = radius * WatchFaceRules.TAIL_LENGTH
    val path = Path().apply {
        moveTo(center.x - dx * back + nx * base / 2.6f, center.y - dy * back + ny * base / 2.6f)
        lineTo(center.x + nx * base / 2, center.y + ny * base / 2)
        lineTo(center.x + dx * length + nx * tip / 2, center.y + dy * length + ny * tip / 2)
        lineTo(center.x + dx * (length + tip / 2), center.y + dy * (length + tip / 2))
        lineTo(center.x + dx * length - nx * tip / 2, center.y + dy * length - ny * tip / 2)
        lineTo(center.x - nx * base / 2, center.y - ny * base / 2)
        lineTo(center.x - dx * back - nx * base / 2.6f, center.y - dy * back - ny * base / 2.6f)
        close()
    }
    drawPath(path, color)
}

/**
 * The full 24-hour Day ring as a sheet (Fold review 2026-10-09 07:26, item 2: "tap the face → the full 24-hour day
 * ring opens as a sheet"): the existing ring, enlarged, with its centre line, live tiles and tappable arcs. It draws
 * itself in quickly as the pane springs up (the opening's quick draw).
 */
@Composable
fun DayRingSheet(
    ring: DayRing, tiles: List<DayTile>, onOpenArc: ((DayArc) -> Unit)?, onClose: () -> Unit,
    /** The back line: "Close" over Today, "‹ Clock" over the bedside clock. */
    backLabel: String = "Close",
) {
    val reduced = Meka.reducedMotion
    val play = remember { if (reduced) DayRingPlay.STILL else DayRingPlay.QUICK }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.l),
    ) {
        Text(backLabel, style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Column(Modifier.padding(bottom = MekaSpace.l).appear(rememberAppearance(0))) {
            Text("Your whole day", style = MekaType.greeting, color = Meka.colors.textPrimary)
            Text(if (onOpenArc != null) "Midnight at the top · tap an arc to open it" else "Midnight at the top", style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                modifier = Modifier.padding(top = MekaSpace.xxs))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().appear(rememberAppearance(1)), contentAlignment = Alignment.Center) {
            val dial = minOf(maxWidth - MekaSpace.l * 2, 340.dp)
            DayRingHero(ring, play, played = {}, size = dial, tiles = tiles, onOpenArc = onOpenArc)
        }
    }
}

/**
 * The clock a watch face's hands read: the device's clock moved by [shiftMs], in a zone [offsetMs] from UTC at a given
 * time. Today provides the core's ([os.meka.core.facade.MekaCore.clockShiftMs], so the face and the greeting agree, in
 * the quality gate's shots too); everywhere else it is the device's own clock and zone.
 */
class FaceClock(val shiftMs: Long, val offsetMs: (Long) -> Long)

/** The watch face's clock; the device's unless a screen provides another (Today: the core's). */
val LocalFaceClock = androidx.compose.runtime.staticCompositionLocalOf {
    FaceClock(0L) { TimeZone.getDefault().getOffset(it).toLong() }
}
