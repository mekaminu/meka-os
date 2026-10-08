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
import os.meka.core.domain.DayArcKind
import os.meka.core.domain.DayTile
import os.meka.core.domain.DayRing
import os.meka.core.domain.DayRingPlay
import os.meka.core.domain.DayRingRules
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
) {
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
    val mark = MotionMath.dayRingMark(elapsed, play)
    val needle = MotionMath.dayRingNeedle(elapsed, play)
    val count = MotionMath.dayRingCount(elapsed, play, expressive)
    val free = MotionMath.countUpValue(0, ring.freeMinutes, count)
    val toDo = MotionMath.countUpValue(0, ring.toDo, count)

    Column(modifier.fillMaxWidth().padding(vertical = MekaSpace.s), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = ring.spokenLine },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(size)) {
                val stroke = 10.dp.toPx()
                val inset = stroke / 2 + 2.dp.toPx()
                val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                val radius = arcSize.width / 2
                // The mark: the dial's track, drawing itself round from the top.
                drawArc(colors.textTertiary.copy(alpha = 0.28f), -90f, 360f * mark, false, topLeft, arcSize, style = Stroke(1.5.dp.toPx()))
                // Quarter ticks (00 · 06 · 12 · 18) once the mark has passed them.
                for (q in 0 until 4) {
                    if (mark < q / 4f) continue
                    val a = Math.toRadians(q * 90.0 - 90.0)
                    val outer = radius + 4.dp.toPx()
                    val inner = radius - 4.dp.toPx()
                    drawLine(colors.textTertiary.copy(alpha = 0.5f),
                        Offset(center.x + (cos(a) * inner).toFloat(), center.y + (sin(a) * inner).toFloat()),
                        Offset(center.x + (cos(a) * outer).toFloat(), center.y + (sin(a) * outer).toFloat()),
                        strokeWidth = 1.dp.toPx())
                }
                // The day's arcs, growing clockwise from their starts.
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
                        false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt),
                    )
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
            Column(
                Modifier.size(size * 0.62f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    DayRingRules.freeLine(free, ring.nowMinute),
                    style = MekaType.body, color = colors.textPrimary, textAlign = TextAlign.Center,
                )
                Text(DayRingRules.toDoLine(toDo), style = MekaType.caption, color = colors.textSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = MekaSpace.xxs))
            }
        }
        if (tiles.isNotEmpty()) {
            DayTilesRow(tiles, count, Modifier.padding(top = MekaSpace.m)) { i -> MotionMath.dayTile(elapsed, i, play, expressive) }
        }
    }
}

/**
 * The live tiles under the Day ring (the opening moment, part 2): up to four small tiles side by side — next event
 * countdown, a running fast, habits done today, renewals due ([os.meka.core.domain.DayTileRules]). Each fades and rises
 * in after the mark closes ([MotionMath.dayTile]) while its number counts up with the centre ([count]); afterwards
 * they follow Today's minute refresh. Each is one screen-reader line.
 */
@Composable
private fun DayTilesRow(tiles: List<DayTile>, count: Float, modifier: Modifier = Modifier, appear: (Int) -> Float) {
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
                    .padding(horizontal = MekaSpace.s, vertical = MekaSpace.xs)
                    .clearAndSetSemantics { contentDescription = tile.spokenLine },
            ) {
                Text(
                    tile.text(MotionMath.countUpValue(0, tile.value, count)),
                    style = MekaType.body.copy(fontFeatureSettings = "tnum"), color = colors.textPrimary, maxLines = 1,
                )
                Text(
                    tile.label, style = MekaType.caption, color = colors.textSecondary, maxLines = 2,
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
}
