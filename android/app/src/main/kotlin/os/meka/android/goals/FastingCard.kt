package os.meka.android.goals

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.FastNow
import os.meka.core.domain.FastRecord
import os.meka.core.domain.FastingHeatDay
import os.meka.core.domain.FastingHistory
import os.meka.core.domain.FastingDay
import os.meka.core.domain.FastingRules
import os.meka.core.domain.FastingView
import os.meka.core.facade.MekaCore

/**
 * FASTING (build plan M1), at the top of Goals: the ring sweeps continuously while a fast runs and glows softly once
 * the goal is reached; the last seven days fill on appear. Start (now or earlier), End, adjust the goal or the start,
 * or throw a mistaken fast away. No health claims, nothing started or ended for you. Reduced motion: no sweep or pulse,
 * the ring is redrawn each second and the glow is steady.
 *
 * Fasting v2: "Longer fast" starts an extended fast (24 h … 7 days, or until a day at 18:00), which shows
 * "Day 3 of 5 · 62 h"; the history under the week keeps every fast (planned against actual), the streak and a
 * twelve-week heat strip whose weeks fade in left to right. Tracking only: no food, calorie or weight advice.
 * When an extended fast reaches its goal, "You did it · 5 days" pops in and the ring bursts once (a ring of light
 * spreading out with twelve short rays, light haptic), once per fast on this phone ([FastBurst]). Reduced motion: the
 * line fades in, no burst.
 */
@Composable
internal fun FastingCard(core: MekaCore, modifier: Modifier = Modifier) {
    val v by core.fastingView.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val act: (suspend () -> Unit) -> Unit = { body -> scope.launch { runCatching { body() } } }
    var adjusting by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val cur = v.current
    // The timer ticks every second while a fast runs.
    LaunchedEffect(cur?.id) {
        while (cur != null) { now = System.currentTimeMillis(); delay(1_000) }
    }
    // The "you did it" moment: once per extended fast, the first time its goal is seen reached on this phone.
    val context = LocalContext.current
    val reduced = Meka.reducedMotion
    val burst = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    val doneId = cur?.takeIf { it.doneLine != null }?.id
    LaunchedEffect(doneId) {
        if (doneId == null || !FastBurst.claim(context, doneId)) return@LaunchedEffect
        if (reduced) return@LaunchedEffect
        haptics.light()
        launch { pop.snapTo(0.6f); pop.animateTo(1f, MekaMotion.complete(false)) }
        burst.snapTo(0f)
        burst.animateTo(1f, tween(900))
        burst.snapTo(0f)
    }

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface).padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FastRing(cur, now, burst.value)
            Spacer(Modifier.width(MekaSpace.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text(
                    when { cur == null -> "Not fasting"; cur.extended -> cur.title; else -> "Fasting" },
                    style = MekaType.itemTitle, color = Meka.colors.textPrimary,
                )
                if (cur != null) {
                    AnimatedVisibility(cur.doneLine != null, enter = androidx.compose.animation.fadeIn(MekaMotion.appear(reduced))) {
                        Text(
                            cur.doneLine.orEmpty(), style = MekaType.itemTitle, color = Meka.colors.accent,
                            modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) },
                        )
                    }
                    cur.dayLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.accent) }
                    Text(cur.goalLine, style = MekaType.itemMeta, color = if (cur.reachedGoal) Meka.colors.accent else Meka.colors.textSecondary)
                    Text(cur.startedLine, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                } else {
                    Text(v.windowLine, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
                    v.last?.let { Text(it.line, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
                }
                Text(v.plan.line, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }

        if (cur != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l)) {
                Action("End fast") { haptics.light(); adjusting = false; act { core.endFast() } }
                Action(if (adjusting) "Done" else "Adjust") { adjusting = !adjusting }
            }
            AnimatedVisibility(adjusting, enter = unfold(), exit = fold()) {
                Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
                    if (cur.extended) {
                        Chips("Goal", FastingRules.EXTENDED_CHOICES.map { it.label to (it.hours == cur.targetHours) }) { i -> act { core.setFastTarget(FastingRules.EXTENDED_CHOICES[i].hours) } }
                    } else {
                        Chips("Goal", FastingRules.TARGET_CHOICES.map { "$it h" to (it == cur.targetHours) }) { i -> act { core.setFastTarget(FastingRules.TARGET_CHOICES[i]) } }
                    }
                    Chips("Started", FastingRules.MOVE_START_CHOICES.map { FastingRules.moveLabel(it) to false }) { i -> act { core.moveFastStart(FastingRules.MOVE_START_CHOICES[i]) } }
                    Action("Discard this fast", critical = true) { adjusting = false; act { core.discardFast() } }
                }
            }
        } else {
            Chips("Start a fast", FastingRules.STARTED_AGO_CHOICES.map { FastingRules.startedAgoLabel(it) to false }) { i ->
                haptics.light(); act { core.startFast(FastingRules.STARTED_AGO_CHOICES[i]) }
            }
            Chips("Longer fast", FastingRules.EXTENDED_CHOICES.map { it.label to false } + v.untilChoices.map { it.label to false }) { i ->
                haptics.light()
                val n = FastingRules.EXTENDED_CHOICES.size
                if (i < n) act { core.startExtendedFast(FastingRules.EXTENDED_CHOICES[i].hours, 0) }
                else act { core.startFastUntil(v.untilChoices[i - n].untilMs, 0) }
            }
            v.last?.takeIf { it.canResume }?.let { last -> Action("Undo end") { act { core.resumeFast(last.id) } } }
            Chips("Plan", FastingRules.PLAN_CHOICES.map { it.label to (FastingRules.planLabel(v.plan) == it.label) }) { i -> act { core.chooseFastingPlan(i) } }
        }

        WeekBars(v)
        HistorySection(v.history)
    }
}

/** The ring: sweeps with the clock, glows softly at the goal. Shows the timer, or the plan's goal when not fasting. */
@Composable
private fun FastRing(cur: FastNow?, now: Long, burst: Float = 0f) {
    val reduced = Meka.reducedMotion
    val target = cur?.progress(now) ?: 0f
    // A linear one-second glide keeps the sweep continuous between ticks.
    val sweep by animateFloatAsState(target, if (reduced) snap() else tween(1_000, easing = LinearEasing), label = "fast-sweep")
    val glowing = cur?.reachedGoal == true
    val pulse = if (reduced || !glowing) 0.3f else {
        val t = rememberInfiniteTransition(label = "fast-glow")
        val a by t.animateFloat(0.18f, 0.42f, infiniteRepeatable(tween(1_800), RepeatMode.Reverse), label = "fast-glow-a")
        a
    }
    val accent = Meka.colors.accent
    val track = Meka.colors.surfaceRaised
    val label = cur?.let { "Fasting ${FastingRules.clock(now - it.startedAtMs)} of ${it.targetHours} hours" } ?: "Not fasting"
    Box(Modifier.size(112.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(112.dp)) {
            val stroke = 8.dp.toPx()
            if (glowing) {
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = pulse), Color.Transparent), center, size.minDimension / 2))
            }
            val inset = stroke / 2 + 6.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (cur != null) drawArc(accent, -90f, 360f * sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            // The "you did it" burst: a ring of light spreading out past the track and twelve rays, fading as it goes.
            if (burst > 0f) {
                val r0 = arcSize.width / 2
                val fade = (1f - burst).coerceIn(0f, 1f)
                drawCircle(accent.copy(alpha = 0.6f * fade), r0 + burst * 10.dp.toPx(), center, style = Stroke(stroke * (1f - burst * 0.7f)))
                val inner = r0 + stroke + burst * 4.dp.toPx()
                val outer = inner + 6.dp.toPx() * (0.4f + burst)
                for (i in 0 until 12) {
                    val a = Math.toRadians(i * 30.0 - 90.0)
                    val dir = Offset(kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())
                    drawLine(accent.copy(alpha = fade), center + dir * inner, center + dir * outer, 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (cur != null) {
                Text(FastingRules.clock(now - cur.startedAtMs), style = MekaType.itemTitle, color = Meka.colors.textPrimary)
                Text(
                    if (cur.extended) "of ${FastingRules.daysLabel(cur.targetHours)}" else "of ${cur.targetHours} h",
                    style = MekaType.caption, color = Meka.colors.textTertiary,
                )
            } else {
                Text("—", style = MekaType.itemTitle, color = Meka.colors.textTertiary)
            }
        }
    }
}

/** The last seven days: one bar per day (the longest fast that ended then), filling on appear. */
@Composable
private fun WeekBars(v: FastingView) {
    if (v.week.isEmpty()) return
    val top = maxOf(24.0, v.week.maxOf { it.hours })
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = v.weekLine ?: "No fasts in the last 7 days" },
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            v.week.forEach { d -> DayBar(d, (d.hours / top).toFloat(), Modifier.weight(1f)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            v.week.forEach { d ->
                Text(
                    d.label, style = MekaType.caption, color = if (d.isToday) Meka.colors.textPrimary else Meka.colors.textTertiary,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Text(v.weekLine ?: "Your last 7 days of fasts show here.", style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

@Composable
private fun DayBar(d: FastingDay, fraction: Float, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    val fill = remember(d.epochDay) { Animatable(if (reduced) fraction else 0f) }
    LaunchedEffect(fraction) { fill.animateTo(fraction, MekaMotion.replan(reduced)) }
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surfaceRaised))
        if (d.hours > 0) {
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(fill.value.coerceIn(0.04f, 1f)).clip(RoundedCornerShape(MekaRadius.s))
                    .background(if (d.reachedGoal) Meka.colors.accent else Meka.colors.textTertiary),
            )
        }
    }
}

/**
 * Fasting v2's history: the streak, a twelve-week heat strip (hours fasted each day, weeks fading in left to right
 * 40 ms apart) and every fast, planned against actual, unfolding under "History". Reduced motion: shown at once.
 */
@Composable
private fun HistorySection(h: FastingHistory) {
    val reduced = Meka.reducedMotion
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        h.streakLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.accent) }
        Row(
            Modifier.semantics { contentDescription = h.totalsLine ?: "No fasts yet" },
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            h.heat.forEachIndexed { w, week ->
                val alpha = remember { Animatable(if (reduced) 1f else 0f) }
                LaunchedEffect(Unit) {
                    if (!reduced) { delay(MotionMath.staggerDelayMs(w, false).toLong()); alpha.animateTo(1f, MekaMotion.appear(false)) }
                }
                Column(Modifier.graphicsLayer { this.alpha = alpha.value }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    week.forEach { d -> HeatCell(d) }
                }
            }
        }
        Text(h.totalsLine ?: "Every fast you finish is kept here.", style = MekaType.caption, color = Meka.colors.textTertiary)
        if (h.fasts.isNotEmpty()) {
            Action(if (open) "Hide history" else "History") { open = !open }
            AnimatedVisibility(open, enter = unfold(), exit = fold()) {
                Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    h.fasts.forEach { f -> HistoryRow(f) }
                }
            }
        }
    }
}

@Composable
private fun HeatCell(d: FastingHeatDay) {
    val accent = Meka.colors.accent
    val color = when {
        d.isFuture -> Meka.colors.surfaceRaised.copy(alpha = 0.4f)
        d.level == 0 -> Meka.colors.surfaceRaised
        d.level == 1 -> accent.copy(alpha = 0.3f)
        d.level == 2 -> accent.copy(alpha = 0.5f)
        d.level == 3 -> accent.copy(alpha = 0.75f)
        else -> accent
    }
    val ring = if (d.isToday) Modifier.border(1.dp, Meka.colors.textSecondary, RoundedCornerShape(2.dp)) else Modifier
    Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(color).then(ring))
}

@Composable
private fun HistoryRow(f: FastRecord) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(f.title, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Text(f.resultLine, style = MekaType.itemMeta, color = if (f.reachedGoal) Meka.colors.accent else Meka.colors.textSecondary)
        }
        Text(f.whenLine, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/** The "you did it" burst plays once per fast on this phone. */
object FastBurst {
    private const val PREFS = "meka.hints"
    private const val KEY = "fastBurst"

    /** True the first time for [fastId]; marked at once so a recomposition or restart doesn't replay it. */
    @Synchronized
    fun claim(context: Context, fastId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY, null) == fastId) return false
        prefs.edit().putString(KEY, fastId).apply()
        return true
    }
}
