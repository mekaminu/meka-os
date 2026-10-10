package os.meka.android.today

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import os.meka.android.designsystem.MekaRadius
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionMath
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.DayArc
import os.meka.core.domain.DayArcKind
import os.meka.core.domain.DayTile
import os.meka.core.domain.DayRing
import os.meka.core.domain.DayRingPlay
import os.meka.core.domain.DayRingRules
import os.meka.core.domain.DayRingHeader
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import os.meka.android.designsystem.MotionPrefs
import os.meka.core.domain.DayRingLive
import os.meka.core.domain.DayRingLook
import os.meka.core.domain.DayRingLiveMode
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

/**
 * The opening moment's Day ring (motion pass 2, slice 7; catalogue "Opening moment"): a 24-hour dial at the top of
 * Today with midnight at the top. The brass mark (the track) draws itself round, the day's events, planned tasks and
 * booked sessions draw in as brass arcs (free time stays dark, finished things dimmer), the now needle sweeps from
 * midnight to now, and the centre counts up to "3 h 45 free" · "4 to do". [play] is decided once per launch
 * ([DayRingRules.play]: in full the first time today, quickly after, at once with Motion → Off); [played] runs when
 * the opening has landed, so scrolling the ring away and back doesn't replay it. Screen readers hear one line.
 */
@Composable
fun DayRingHero(
    ring: DayRing,
    play: DayRingPlay,
    played: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 196.dp,
    /** The live tiles under the dial (the opening moment, part 2): next event, fast, habits, renewals. */
    tiles: List<DayTile> = emptyList(),
    /** Test hook (the motion workflow): called on every drawn frame of the living ring with the hand's angle and the glow. */
    onLiveFrame: ((handDegrees: Float, glow: Float) -> Unit)? = null,
    /** Tapping an arc on the ring opens it (Living Today, slice 3); null: the ring doesn't answer taps. */
    onOpenArc: ((DayArc) -> Unit)? = null,
    /**
     * The bedside clock's ring (Living Today, slice 5): a slower breath ([DayRingLive.bedsideGlow]), and in [quiet]
     * hours no sweeping hand ([DayRingLive.bedsideMode]).
     */
    bedside: Boolean = false,
    quiet: Boolean = false,
    /**
     * False when the ring sits beside the greeting in Today's header (Fold review 2026-10-09): it takes only its own
     * width instead of centring itself across the screen.
     */
    fillWidth: Boolean = true,
) {
    val sizeDp = size.value.toInt()
    // The compact header dial (closed Fold) thins its stroke and drops the centre text (DayRingHeader).
    val strokeDp = DayRingHeader.strokeDp(sizeDp).dp
    val showsCentre = DayRingHeader.showsCentre(sizeDp)
    val expressive = Meka.expressiveMotion
    val total = remember(play) { MotionMath.dayRingTotalMs(ring.arcs.size, play, expressive, tiles.size) }
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
    val needle = MotionMath.dayRingNeedle(elapsed, play, expressive)
    val count = MotionMath.dayRingCount(elapsed, play, expressive)
    val free = MotionMath.countUpValue(0, ring.freeMinutes, count)
    val toDo = MotionMath.countUpValue(0, ring.toDo, count)

    val haptics = rememberMekaHaptics()
    val currentRing by rememberUpdatedState(ring)
    val open by rememberUpdatedState(onOpenArc)
    val dial = size
    val width = if (fillWidth) Modifier.fillMaxWidth() else Modifier
    Column(modifier.then(width).padding(vertical = if (fillWidth) MekaSpace.m else 0.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            width.clearAndSetSemantics { testTag = DAY_RING_TAG; contentDescription = ring.spokenLine }
                .then(
                    if (onOpenArc == null) Modifier else Modifier.pointerInput(dial) {
                        // Tap an arc to open it: the angle from the dial's centre picks the arc (free time opens nothing).
                        detectTapGestures { p ->
                            // The track's radius, as the dial draws it (inset by half the stroke plus 2 dp).
                            val radius = DayRingHeader.trackRadiusDp(dial.value.toInt()).dp.toPx()
                            val deg = DayRingRules.tapDegrees(p.x - this.size.width / 2f, p.y - this.size.height / 2f, radius)
                                ?: return@detectTapGestures
                            DayRingRules.arcAt(currentRing, deg)?.let { arc ->
                                haptics.tick()
                                open?.invoke(arc)
                            }
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(size)) {
                val stroke = strokeDp.toPx()
                val inset = stroke / 2 + 2.dp.toPx()
                val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                val radius = arcSize.width / 2
                // The mark: the dial's track, drawing itself round from the top — brass, 3 dp at 55 % (DayRingLook).
                drawArc(colors.accent.copy(alpha = colors.accent.alpha * DayRingLook.TRACK_ALPHA), -90f, 360f * mark, false, topLeft,
                    arcSize, style = Stroke(DayRingLook.TRACK_STROKE_DP.dp.toPx()))
                // The hour marks: a tick at 00 · 06 · 12 · 18, a fine dot just inside the track at every other hour. On
                // the first open they fade in one by one behind the drawing mark; the quick draw brings them up with it.
                for (h in 0 until 24) {
                    val show = MotionMath.dayRingHour(mark, h, play)
                    if (show <= 0f) continue
                    val a = Math.toRadians(h * 15.0 - 90.0)
                    if (h % 6 == 0) {
                        val outer = radius + 4.dp.toPx()
                        val inner = radius - 4.dp.toPx()
                        drawLine(colors.accent.copy(alpha = DayRingLook.HOUR_MARK_ALPHA * show),
                            Offset(center.x + (cos(a) * inner).toFloat(), center.y + (sin(a) * inner).toFloat()),
                            Offset(center.x + (cos(a) * outer).toFloat(), center.y + (sin(a) * outer).toFloat()),
                            strokeWidth = 1.dp.toPx())
                    } else {
                        val r = radius - stroke / 2 - 3.dp.toPx()
                        drawCircle(colors.accent.copy(alpha = DayRingLook.HOUR_MARK_ALPHA * show), radius = 0.9.dp.toPx(),
                            center = Offset(center.x + (cos(a) * r).toFloat(), center.y + (sin(a) * r).toFloat()))
                    }
                }
                // Work hours: a faint band along the track (not booked, so not an arc), coming up with the mark.
                ring.work.forEach { band ->
                    drawArc(colors.textTertiary.copy(alpha = (if (band.current) 0.26f else 0.16f) * mark), band.startDegrees - 90f,
                        band.sweepDegrees, false, topLeft, arcSize, style = Stroke(4.dp.toPx(), cap = StrokeCap.Butt))
                }
                // Rain still to come today (Weather slice 2): a faint blue tint along the track, a shade brighter while
                // it's raining now, coming up with the mark.
                ring.rain.forEach { band ->
                    drawArc(colors.rain.copy(alpha = (if (band.current) 0.42f else 0.26f) * mark), band.startDegrees - 90f,
                        band.sweepDegrees, false, topLeft, arcSize, style = Stroke(3.dp.toPx(), cap = StrokeCap.Butt))
                }
                // A running fast: an inner arc from when it began round to its goal, filling as it counts up.
                ring.fast?.let { f ->
                    val r = radius - stroke * 1.9f
                    val tl = Offset(center.x - r, center.y - r)
                    val sz = Size(r * 2, r * 2)
                    drawArc(colors.accent.copy(alpha = 0.16f), f.startDegrees - 90f, f.sweepDegrees * mark, false, tl, sz,
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                    if (f.filledDegrees > 0f) {
                        drawArc(colors.accent.copy(alpha = if (f.reachedGoal) 1f else 0.85f), f.startDegrees - 90f, f.filledDegrees * count,
                            false, tl, sz, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
                // The day's arcs, growing clockwise from their starts; the gym's booked sessions wider, to stand out.
                ring.arcs.forEachIndexed { i, arc ->
                    val grow = MotionMath.dayRingArc(elapsed, i, play, expressive)
                    if (grow <= 0f) return@forEachIndexed
                    val alpha = when {
                        arc.past -> 0.38f
                        arc.kind == DayArcKind.TASK -> 0.7f
                        else -> 1f
                    }
                    drawArc(
                        colors.accent.copy(alpha = colors.accent.alpha * alpha), arc.startDegrees - 90f, arc.sweepDegrees * grow,
                        false, topLeft, arcSize, style = Stroke(if (arc.highlighted && !arc.past) stroke * 1.4f else stroke, cap = StrokeCap.Butt),
                    )
                }
                // After Shut down: tomorrow's first thing as a hollow brass mark on the track, coming up with the mark.
                ring.tomorrow?.degrees?.let { deg ->
                    val a = Math.toRadians(deg - 90.0)
                    val at = Offset(center.x + (cos(a) * radius).toFloat(), center.y + (sin(a) * radius).toFloat())
                    drawCircle(colors.background, radius = stroke * 0.55f * mark, center = at)
                    drawCircle(colors.accent.copy(alpha = mark), radius = stroke * 0.55f * mark, center = at, style = Stroke(2.dp.toPx()))
                }
                // The now needle: from inside the arcs out past them, with a brass dot at its tip.
                if (needle > 0f) {
                    val a = Math.toRadians(ring.nowDegrees * needle - 90.0)
                    val from = radius - stroke * 1.6f
                    val to = radius + stroke * 0.7f
                    val tip = Offset(center.x + (cos(a) * to).toFloat(), center.y + (sin(a) * to).toFloat())
                    drawLine(colors.textPrimary.copy(alpha = 0.8f),
                        Offset(center.x + (cos(a) * from).toFloat(), center.y + (sin(a) * from).toFloat()), tip,
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(colors.accent, radius = 3.5.dp.toPx(), center = tip)
                }
            }
            // Living Today: once the opening has landed the ring stays alive — the gold second hand, the breath, the
            // hour's shimmer and the now dot's pop — drawn on a layer of its own.
            DayRingLiveLayer(ring, landed = play == DayRingPlay.STILL, size = size, onFrame = onLiveFrame, bedside = bedside, quiet = quiet)
            if (showsCentre) Column(
                Modifier.size(size * 0.62f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // "3 h 45 free" · "4 to do"; once the day is shut down, tomorrow's first thing ("Tomorrow 09:30" · "Standup").
                Text(
                    ring.centreLine(free),
                    style = MekaType.body, color = colors.textPrimary, textAlign = TextAlign.Center,
                )
                Text(ring.centreCaption(toDo), style = MekaType.caption, color = colors.textSecondary, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = MekaSpace.xxs))
            }
        }
        if (tiles.isNotEmpty()) {
            DayTilesRow(tiles, count, Modifier.padding(top = MekaSpace.m)) { i -> MotionMath.dayTile(elapsed, i, play, expressive) }
        }
    }
}

/**
 * The living Day ring (Living Today, slice 1; catalogue "Living Today"): drawn over the dial once the opening has
 * landed ([landed]), with the same geometry. Everything it draws comes from [DayRingLive]:
 * - a fine brass second hand with a comet tail sweeping round once a minute (smoothly, never ticking), fading in;
 * - the brass edge breathing 60 % → 100 % over 5 s, and once at the top of each hour a band of light running round;
 * - the now needle's dot popping on its spring as each minute turns.
 * Only this layer's draw reads the clock, so a frame redraws it without recomposing Today. Frames run only while the
 * ring is on screen (the effect leaves with the list item) and MEKA is in front (resumed). Power saving: no hand or
 * breath, redrawn once a minute. Motion → Off: a still edge, no hand.
 */
@Composable
private fun DayRingLiveLayer(
    ring: DayRing, landed: Boolean, size: Dp, onFrame: ((Float, Float) -> Unit)?, bedside: Boolean = false, quiet: Boolean = false,
) {
    val context = LocalContext.current
    val reduced = Meka.reducedMotion
    val powerSave = remember { MotionPrefs.powerSave(context) }
    val mode = if (bedside) DayRingLive.bedsideMode(reduced, powerSave, quiet) else DayRingLive.mode(reduced, powerSave)
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val front = resumed.isAtLeast(Lifecycle.State.RESUMED)
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    var landedAt by remember { mutableLongStateOf(Long.MAX_VALUE) }
    LaunchedEffect(mode, landed, front) {
        if (!landed) {
            landedAt = Long.MAX_VALUE
            return@LaunchedEffect
        }
        if (landedAt == Long.MAX_VALUE) landedAt = System.currentTimeMillis()
        clock.longValue = System.currentTimeMillis()
        if (!front) return@LaunchedEffect
        while (true) {
            when (mode) {
                DayRingLiveMode.SWEEP -> withFrameMillis { clock.longValue = System.currentTimeMillis() }
                DayRingLiveMode.MINUTE -> {
                    delay(DayRingLive.nextDrawInMs(mode, System.currentTimeMillis()) ?: return@LaunchedEffect)
                    clock.longValue = System.currentTimeMillis()
                }
                DayRingLiveMode.STILL -> return@LaunchedEffect
            }
        }
    }
    val colors = Meka.colors
    Canvas(Modifier.size(size)) {
        val now = clock.longValue // read only here: a new frame redraws this layer, nothing recomposes
        if (!landed) return@Canvas // the opening draws the dial in first
        val stroke = DayRingHeader.strokeDp(size.value.toInt()).dp.toPx()
        val inset = stroke / 2 + 2.dp.toPx()
        val radius = (this.size.width - inset * 2) / 2
        val sweeping = mode == DayRingLiveMode.SWEEP
        // Fades in as the opening lands (at once when the ring isn't sweeping: nothing would advance the fade).
        val fadeIn = if (sweeping) DayRingLive.handFade(now - landedAt) else 1f
        val glow = when {
            !sweeping -> 0.8f
            bedside -> DayRingLive.bedsideGlow(now)
            else -> DayRingLive.glow(now)
        }
        val edge = radius + stroke / 2 + 1.dp.toPx()
        // The brass edge, breathing 60 % → 100 %, with a soft blur reaching 8 dp out (layered rings, DayRingLook).
        val blurStroke = DayRingLook.blurStrokeDp().dp.toPx()
        for (i in 0 until DayRingLook.EDGE_BLUR_LAYERS) {
            drawCircle(colors.accent.copy(alpha = DayRingLook.blurAlpha(i, glow)), edge + DayRingLook.blurOffsetDp(i).dp.toPx(), center,
                style = Stroke(blurStroke), alpha = fadeIn)
        }
        drawCircle(colors.accent.copy(alpha = DayRingLook.edgeAlpha(glow)), edge, center,
            style = Stroke(DayRingLook.EDGE_STROKE_DP.dp.toPx()), alpha = fadeIn)
        fun box(r: Float) = Pair(Offset(center.x - r, center.y - r), Size(r * 2, r * 2))
        // The arc on now glows with the ring's breath (Living Today, slice 3): a soft wider halo over it.
        ring.arcs.filter { it.current }.forEach { arc ->
            val (tl, sz) = box(radius)
            drawArc(colors.accent.copy(alpha = 0.30f * glow * fadeIn), arc.startDegrees - 90f, arc.sweepDegrees, false, tl, sz,
                style = Stroke(stroke + 8.dp.toPx(), cap = StrokeCap.Round))
        }
        if (!sweeping) return@Canvas
        // The hour's shimmer: a band of light running once round the edge.
        DayRingLive.shimmer(now, TimeZone.getDefault().getOffset(now).toLong())?.let { s ->
            val head = 360f * s
            val fade = ((1f - s) * 4f).coerceIn(0f, 1f)
            val (tl, sz) = box(edge)
            val parts = 10
            for (j in 0 until parts) {
                val step = DayRingLive.SHIMMER_BAND_DEGREES / parts
                val a = (1f - j.toFloat() / parts).let { it * it } * 0.8f * fade
                drawArc(colors.accent.copy(alpha = a), head - step * (j + 1) - 90f, step, false, tl, sz, style = Stroke(2.5.dp.toPx()))
            }
        }
        // The second hand: a comet tail along the track, a fine brass hand across it and a bead where they meet.
        val hand = DayRingLive.handDegrees(now)
        val (tl, sz) = box(radius)
        val step = DayRingLive.TAIL_DEGREES / DayRingLive.TAIL_SEGMENTS
        for (i in 0 until DayRingLive.TAIL_SEGMENTS) {
            drawArc(colors.accent.copy(alpha = DayRingLive.tailAlpha(i) * fadeIn), hand - step * (i + 1) - 90f, step + 0.4f,
                false, tl, sz, style = Stroke(DayRingLook.TAIL_STROKE_DP.dp.toPx()))
        }
        val a = Math.toRadians(hand - 90.0)
        fun at(r: Float) = Offset(center.x + (cos(a) * r).toFloat(), center.y + (sin(a) * r).toFloat())
        drawLine(colors.accent.copy(alpha = fadeIn), at(radius - stroke * 1.2f), at(radius + stroke * 0.9f),
            strokeWidth = DayRingLook.HAND_STROKE_DP.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(colors.accent.copy(alpha = DayRingLook.HAND_TIP_HALO_ALPHA * fadeIn), DayRingLook.HAND_TIP_HALO_DP.dp.toPx(), at(radius))
        drawCircle(colors.accent.copy(alpha = fadeIn), DayRingLook.HAND_TIP_DP.dp.toPx(), at(radius))
        // The now dot pops as the minute turns.
        val pop = DayRingLive.nowPop(now)
        if (pop > 1f) {
            val n = Math.toRadians(ring.nowDegrees - 90.0)
            val tip = radius + stroke * 0.7f
            drawCircle(colors.accent, 3.5.dp.toPx() * pop, Offset(center.x + (cos(n) * tip).toFloat(), center.y + (sin(n) * tip).toFloat()))
        }
        onFrame?.invoke(hand, glow)
    }
}

/**
 * The live tiles under the Day ring (the opening moment, part 2): up to four small tiles side by side — next event
 * countdown, a running fast, habits done today, renewals due ([os.meka.core.domain.DayTileRules]). Each fades and rises
 * in after the mark closes ([MotionMath.dayTile]) while its number counts up with the centre ([count]); afterwards
 * they follow Today's minute refresh. Each is one screen-reader line.
 */
/** The ring's test tag: Today's UI tests look for it on the closed and open Fold. */
const val DAY_RING_TAG = "day-ring"

/**
 * The live tiles as a slim row under Today's header (Fold review 2026-10-09, item 1): the ring sits beside the
 * greeting now, so its tiles leave the dial. They keep the opening's timing — the same [play] starts the same clock,
 * so each still rises in after the mark closes while its number counts up with the ring's centre.
 */
@Composable
fun DayTilesStrip(tiles: List<DayTile>, play: DayRingPlay, arcs: Int, modifier: Modifier = Modifier) {
    if (tiles.isEmpty()) return
    val expressive = Meka.expressiveMotion
    val total = remember(play) { MotionMath.dayRingTotalMs(arcs, play, expressive, tiles.size) }
    var elapsed by remember(play) { mutableLongStateOf(if (play == DayRingPlay.STILL) total else 0L) }
    LaunchedEffect(play) {
        if (play != DayRingPlay.STILL) {
            val start = withFrameMillis { it }
            while (elapsed < total) withFrameMillis { elapsed = it - start }
        }
    }
    DayTilesRow(tiles, MotionMath.dayRingCount(elapsed, play, expressive), modifier, slim = true) { i ->
        MotionMath.dayTile(elapsed, i, play, expressive)
    }
}

@Composable
private fun DayTilesRow(tiles: List<DayTile>, count: Float, modifier: Modifier = Modifier, slim: Boolean = false, appear: (Int) -> Float) {
    val colors = Meka.colors
    val rise = with(LocalDensity.current) { MotionMath.riseDistanceDp(Meka.expressiveMotion).dp.toPx() / 2 }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        tiles.forEachIndexed { i, tile ->
            val a = appear(i)
            Column(
                Modifier.weight(1f)
                    .graphicsLayer { alpha = a; translationY = (1f - a) * rise }
                    .clip(RoundedCornerShape(MekaRadius.s))
                    .background(colors.surface)
                    .padding(horizontal = MekaSpace.xs, vertical = if (slim) MekaSpace.xxs else MekaSpace.xs)
                    .clearAndSetSemantics { contentDescription = tile.spokenLine },
            ) {
                Text(
                    tile.text(MotionMath.countUpValue(0, tile.value, count)),
                    style = MekaType.body.copy(fontFeatureSettings = "tnum"), color = colors.textPrimary, maxLines = 1,
                )
                Text(
                    tile.label, style = MekaType.caption, color = colors.textSecondary, maxLines = if (slim) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The greeting on Today. On the first open of the day ([DayRingPlay.FULL]) its letters fade in one after another
 * (each rising a little, [MotionMath.greetingLetter]); later opens and Motion → Off show it at once (the section's own
 * fade-up still plays). Screen readers hear the plain words.
 */
@Composable
fun GreetingText(text: String, play: DayRingPlay, modifier: Modifier = Modifier) {
    val colors = Meka.colors
    val total = remember(play, text) { MotionMath.greetingTotalMs(text.length, play) }
    var elapsed by remember(play, text) { mutableLongStateOf(if (total == 0L) total else 0L) }
    LaunchedEffect(play, text) {
        if (total > 0L) {
            val start = withFrameMillis { it }
            while (elapsed < total) withFrameMillis { elapsed = it - start }
        }
    }
    if (total == 0L || elapsed >= total) {
        Text(text, style = MekaType.greeting, color = colors.textPrimary, modifier = modifier)
        return
    }
    val styled = buildAnnotatedString {
        text.forEachIndexed { i, ch ->
            val f = MotionMath.greetingLetter(elapsed, i, play)
            withStyle(SpanStyle(color = colors.textPrimary.copy(alpha = colors.textPrimary.alpha * f))) { append(ch) }
        }
    }
    // The letters fade; the whole line rises a touch with the first of them (a span can't move on its own).
    val lead = MotionMath.greetingLetter(elapsed, 0, play)
    val rise = with(LocalDensity.current) { MotionMath.GREETING_LETTER_RISE_DP.dp.toPx() }
    Text(
        styled, style = MekaType.greeting,
        modifier = modifier.graphicsLayer { translationY = (1f - lead) * rise }.semantics { contentDescription = text },
    )
}

/** Which day the Day ring last played in full on this phone, so later opens that day play quickly. */
object DayRingOpen {
    private const val PREFS = "meka.hints"
    private const val KEY = "dayRingFullDay"

    /** How the ring plays on this open; a full play is marked at once so a restart the same day plays quickly. */
    @Synchronized
    fun claim(context: Context, todayEpochDay: Long, reduced: Boolean): DayRingPlay {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = if (prefs.contains(KEY)) prefs.getLong(KEY, 0L) else null
        val play = DayRingRules.play(last, todayEpochDay, reduced)
        if (play == DayRingPlay.FULL) prefs.edit().putLong(KEY, todayEpochDay).apply()
        return play
    }

    /**
     * What coming back to Today after [awayMs] away plays ([DayRingRules.onReturn]): null for a short trip (Today stays
     * as it was), else the quick draw-in, or the full opening on a new day (marked at once, like [claim]).
     */
    @Synchronized
    fun onReturn(context: Context, todayEpochDay: Long, awayMs: Long, reduced: Boolean): DayRingPlay? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = if (prefs.contains(KEY)) prefs.getLong(KEY, 0L) else null
        val play = DayRingRules.onReturn(last, todayEpochDay, awayMs, reduced)
        if (play == DayRingPlay.FULL) prefs.edit().putLong(KEY, todayEpochDay).apply()
        return play
    }
}
