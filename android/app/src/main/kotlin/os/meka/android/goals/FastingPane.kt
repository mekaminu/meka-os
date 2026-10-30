package os.meka.android.goals

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.today.SectionLabel
import os.meka.core.domain.EvidenceTone
import os.meka.core.domain.FastingBesideRules
import os.meka.core.domain.FastingHistoryRow
import os.meka.core.domain.FastingRules
import os.meka.core.domain.FastingScreen
import os.meka.core.domain.FastingScreenRules
import os.meka.core.domain.FastingStageRow
import os.meka.core.domain.FastingWeekRing
import os.meka.core.facade.MekaCore
import kotlin.math.cos
import kotlin.math.sin

/**
 * The clock the Fasting screen reads: the core's (the device's on a real phone; a test's own in the screenshots),
 * ticking every second while a fast runs and every half minute otherwise.
 */
@Composable
internal fun rememberFastClock(core: MekaCore, running: Boolean): Long {
    val shift = remember(core) { core.clockShiftMs() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() + shift) }
    LaunchedEffect(running) {
        while (true) {
            now = System.currentTimeMillis() + shift
            delay(if (running) 1_000 else 30_000)
        }
    }
    return now
}

/**
 * Goals and habits' small Fasting tile (Fasting rebuild slice 2): a small ring, "Fasting · 13:30" or "Not fasting",
 * and when it ends or the next one starts. One tap opens the Fasting screen ([FastingPane]), so the habits show above
 * the fold. The ring sweeps with the clock (one-second linear glide; reduced motion: redrawn each tick).
 */
@Composable
internal fun FastingTile(core: MekaCore, modifier: Modifier = Modifier, open: () -> Unit) {
    val v by core.fastingView.collectAsState()
    val now = rememberFastClock(core, v.current != null)
    val s = remember(v, now) { core.fastingScreen(now) }
    val reduced = Meka.reducedMotion
    val sweep by animateFloatAsState(s.fraction, if (reduced) snap() else tween(1_000, easing = LinearEasing), label = "tile-sweep")
    val accent = Meka.colors.accent
    val track = Meka.colors.surfaceRaised
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .clickable(role = Role.Button) { open() }
            .padding(MekaSpace.m)
            .testTag("fasting-tile")
            .semantics { contentDescription = "${s.tileTitle}. ${s.subLine}. Opens Fasting" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(MekaSpace.xxl)) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            if (s.isFasting) drawArc(accent, -90f, 360f * sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Spacer(Modifier.width(MekaSpace.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(s.tileTitle, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
            Text(s.subLine, style = MekaType.itemMeta, color = if (s.isFasting) Meka.colors.accent else Meka.colors.textSecondary)
            Text(v.plan.line, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        Spacer(Modifier.width(MekaSpace.xs))
        Text("›", style = MekaType.itemTitle, color = Meka.colors.accent)
    }
}

/**
 * FASTING, its own screen (Fasting rebuild slice 2, the approved design docs/design/fasting/fasting.dc.html): the plan
 * chip, a big gold ring with a glowing tip and 4/8/12h markers, the time in the middle and "Ends in … · 12:00"; the
 * Started and Goal tiles (each taps open its choices); one main button, Start fast or End fast; "More options" for a
 * fast started earlier, longer fasts (the GP note above them) and a day and time; what's happening now; the seven
 * stages, each tapping open its words and evidence; this week as seven small rings; History as a list. Every word
 * comes from core [FastingScreenRules]. Tracking only: no food, calorie or weight advice.
 *
 * Motion: the pane springs up from Goals' tile; the ring sweeps with the clock and its tip breathes (2.4 s); the parts
 * appear in the stagger; tiles, More options and stages unfold in place (expand spring, tick haptic); Start and End
 * give a light haptic; an extended fast reaching its goal bursts the ring once ([FastBurst]). Reduced motion: no
 * sweep, breath or burst; unfolds cross-fade.
 */
@Composable
internal fun FastingPane(core: MekaCore, onClose: () -> Unit, backLabel: String = "‹ Goals and habits") {
    val v by core.fastingView.collectAsState()
    val now = rememberFastClock(core, v.current != null)
    val s = remember(v, now) { core.fastingScreen(now) }
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val act: (suspend () -> Unit) -> Unit = { body -> scope.launch { runCatching { body() } } }
    var planOpen by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var stageOpen by rememberSaveable { mutableStateOf<String?>(null) }
    val cur = v.current
    // A fast that ends or starts folds whatever was open for the other state.
    LaunchedEffect(cur?.id) { editing = null; picking = false; moreOpen = false }

    // The "you did it" burst: once per extended fast, the first time its goal is seen reached on this phone.
    val context = LocalContext.current
    val reduced = Meka.reducedMotion
    val burst = remember { Animatable(0f) }
    val doneId = cur?.takeIf { it.doneLine != null }?.id
    LaunchedEffect(doneId) {
        if (doneId == null || !FastBurst.claim(context, doneId) || reduced) return@LaunchedEffect
        haptics.light()
        burst.snapTo(0f)
        burst.animateTo(1f, tween(900))
        burst.snapTo(0f)
    }

    // Unfolded, the screen keeps a phone-like column (the design is drawn at phone width) centred on the page.
    Box(Modifier.fillMaxSize().background(Meka.colors.background), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        Modifier.widthIn(max = 600.dp).fillMaxHeight().testTag("fasting-pane"),
        contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.l),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        item(key = "top") { Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    backLabel, style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.weight(1f).clickable(role = Role.Button) { haptics.tick(); onClose() }.minTouch(),
                )
                PlanChip(s.planChip, planOpen) { haptics.tick(); planOpen = !planOpen }
            }
            AnimatedVisibility(planOpen, enter = unfold(), exit = fold()) {
                Chips("Plan", FastingRules.PLAN_CHOICES.map { it.label to (it.label == s.planChip) }) { i ->
                    haptics.tick(); planOpen = false; act { core.chooseFastingPlan(i) }
                }
            }
        } }
        item(key = "title") {
            Text(
                "Fasting", style = FastingTitle, color = Meka.colors.textPrimary,
                modifier = Modifier.semantics { heading() }.appear(rememberAppearance(0)),
            )
        }
        item(key = "ring") { BigRing(s, burst.value, Modifier.appear(rememberAppearance(1))) }
        cur?.doneLine?.let { done ->
            item(key = "done") {
                Text(done, style = MekaType.itemTitle, color = Meka.colors.accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        if (s.isFasting) item(key = "tiles") {
            Column(Modifier.appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                // The two tiles keep one height when one of them wraps (the re-shot: "Wed 19:45 · 16h" on the cover screen).
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    EditTile("Started", s.startedTile.orEmpty(), editing == "start", Modifier.weight(1f).fillMaxHeight()) {
                        haptics.tick(); editing = if (editing == "start") null else "start"
                    }
                    EditTile("Goal", s.goalTile.orEmpty(), editing == "goal", Modifier.weight(1f).fillMaxHeight()) {
                        haptics.tick(); editing = if (editing == "goal") null else "goal"
                    }
                }
                AnimatedVisibility(editing == "start", enter = unfold(), exit = fold()) {
                    Chips("Move the start", FastingRules.MOVE_START_CHOICES.map { FastingRules.moveLabel(it) to false }) { i ->
                        haptics.tick(); act { core.moveFastStart(FastingRules.MOVE_START_CHOICES[i]) }
                    }
                }
                AnimatedVisibility(editing == "goal", enter = unfold(), exit = fold()) {
                    val c = cur
                    if (c != null && c.extended) {
                        Chips("Goal", FastingRules.EXTENDED_CHOICES.map { it.label to (it.hours == c.targetHours) }) { i ->
                            haptics.tick(); act { core.setFastTarget(FastingRules.EXTENDED_CHOICES[i].hours) }
                        }
                    } else {
                        Chips("Goal", FastingRules.TARGET_CHOICES.map { "$it h" to (it == c?.targetHours) }) { i ->
                            haptics.tick(); act { core.setFastTarget(FastingRules.TARGET_CHOICES[i]) }
                        }
                    }
                }
            }
        }
        item(key = "main") {
            Column(Modifier.appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                MainButton(s.mainLabel, filled = !s.isFasting) {
                    haptics.light()
                    if (s.isFasting) act { core.endFast() } else act { core.startFast(0) }
                }
                s.undoEndId?.let { id ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Action(FastingScreenRules.UNDO_END) { haptics.tick(); act { core.resumeFast(id) } }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (s.isFasting) "Started by mistake?" else FastingScreenRules.MORE_OPTIONS_PROMPT,
                        style = MekaType.caption, color = Meka.colors.textTertiary,
                    )
                    Spacer(Modifier.width(MekaSpace.xs))
                    Text(
                        if (moreOpen) "Close" else FastingScreenRules.MORE_OPTIONS, style = MekaType.captionStrong, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); moreOpen = !moreOpen }.minTouch(),
                    )
                }
                AnimatedVisibility(moreOpen, enter = unfold(), exit = fold()) {
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        if (s.isFasting) {
                            Action("Discard this fast", critical = true) { haptics.light(); moreOpen = false; act { core.discardFast() } }
                        } else {
                            Chips("Started earlier", FastingRules.STARTED_AGO_CHOICES.filter { it > 0 }.map { FastingRules.startedAgoLabel(it) to false }) { i ->
                                haptics.light(); act { core.startFast(FastingRules.STARTED_AGO_CHOICES.filter { it > 0 }[i]) }
                            }
                            Text(FastingScreenRules.GP_NOTE, style = MekaType.caption, color = Meka.colors.textSecondary)
                            Chips("Longer fast", FastingRules.EXTENDED_CHOICES.map { it.label to false } + v.untilChoices.map { it.label to false }) { i ->
                                haptics.light()
                                val n = FastingRules.EXTENDED_CHOICES.size
                                if (i < n) act { core.startExtendedFast(FastingRules.EXTENDED_CHOICES[i].hours, 0) }
                                else act { core.startFastUntil(v.untilChoices[i - n].untilMs, 0) }
                            }
                            Action(if (picking) "Close" else "Pick a day and time") { haptics.tick(); picking = !picking }
                            AnimatedVisibility(picking, enter = unfold(), exit = fold()) {
                                UntilPicker(v) { untilMs -> haptics.light(); picking = false; act { core.startFastUntil(untilMs, 0) } }
                            }
                        }
                    }
                }
            }
        }
        s.now?.let { n ->
            item(key = "now") {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l))
                        .background(Meka.colors.accent.copy(alpha = 0.08f))
                        .border(1.dp, Meka.colors.accent.copy(alpha = 0.3f), RoundedCornerShape(MekaRadius.l))
                        .padding(MekaSpace.m)
                        .appear(rememberAppearance(3)),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    Text(FastingScreenRules.NOW_TITLE.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.accent)
                    Text(n.stage.name, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
                    Text(n.stage.body, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge(n.stage.evidence, n.tone)
                        n.next?.let {
                            Spacer(Modifier.width(MekaSpace.xs))
                            Text(it, style = MekaType.caption, color = Meka.colors.textTertiary)
                        }
                    }
                }
            }
        }
        // The stages are one block as in the mockup: the label, the rows 8 dp apart, the note (beside the mockup, 2026-10-30).
        item(key = "stages") {
            Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(FastingBesideRules.ROW_GAP_DP.dp)) {
                SectionLabel(FastingScreenRules.STAGES_TITLE)
                s.stages.forEach { row ->
                    StageRow(row, s.isFasting, stageOpen == row.stage.id) {
                        haptics.tick(); stageOpen = if (stageOpen == row.stage.id) null else row.stage.id
                    }
                }
                Text(FastingScreenRules.STAGES_NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }
        item(key = "week") { WeekSection(s, Modifier.padding(top = MekaSpace.xs)) }
        item(key = "history") {
            Column(Modifier.padding(top = MekaSpace.xs).animateContentSize(MekaMotion.replan(Meka.reducedMotion)), verticalArrangement = Arrangement.spacedBy(FastingBesideRules.ROW_GAP_DP.dp)) {
                SectionLabel("History")
                if (s.history.isEmpty()) {
                    Text(FastingScreenRules.HISTORY_EMPTY, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
                }
                s.history.forEach { h -> key(h.id) { HistoryLine(h) } }
            }
        }
    }
    }
}

@Composable
private fun PlanChip(label: String, lit: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(Meka.reducedMotion), label = "plan-chip")
    Box(Modifier.minTouch(), contentAlignment = Alignment.Center) {
        Text(
            label, style = MekaType.captionStrong, color = if (lit) Meka.colors.onAccent else Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
                .clickable(role = Role.Button) { onClick() }
                .semantics { contentDescription = "Plan $label" }
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        )
    }
}

/**
 * The big ring: a gold sweep over a dark track with markers at 4, 8 and 12 hours (whole days on a longer fast), a
 * glowing tip at the sweep's end that breathes while a fast runs, and the time in the middle. Up to 300 dp, smaller
 * on a narrow screen.
 */
@Composable
private fun BigRing(s: FastingScreen, burst: Float, modifier: Modifier = Modifier) {
    val reduced = Meka.reducedMotion
    val sweep by animateFloatAsState(s.fraction, if (reduced) snap() else tween(1_000, easing = LinearEasing), label = "fast-ring")
    val breath = if (reduced || !s.showTip) 0.8f else {
        val t = rememberInfiniteTransition(label = "fast-tip")
        val a by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1_200), RepeatMode.Reverse), label = "fast-tip-a")
        a
    }
    val accent = Meka.colors.accent
    val light = lerp(accent, Color.White, 0.4f)
    val track = Meka.colors.surfaceRaised
    val bg = Meka.colors.background
    val passedDot = Meka.colors.onAccent
    val markAhead = Meka.colors.textTertiary.copy(alpha = 0.6f)
    val spoken = "${s.topLine}. ${s.bigTime}. ${s.subLine}"
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = if (maxWidth < 300.dp) maxWidth else 300.dp
        Box(Modifier.size(side).semantics { contentDescription = spoken }.testTag("fasting-ring"), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(side)) {
                val stroke = 16.dp.toPx()
                val r = size.minDimension / 2 - stroke / 2 - 8.dp.toPx()
                val tl = Offset(center.x - r, center.y - r)
                val arc = Size(r * 2, r * 2)
                drawArc(track, 0f, 360f, false, tl, arc, style = Stroke(stroke))
                if (s.isFasting && sweep > 0f) {
                    drawArc(
                        Brush.linearGradient(listOf(light, accent), start = tl, end = Offset(tl.x + arc.width, tl.y + arc.height)),
                        -90f, 360f * sweep, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                fun at(fraction: Float): Offset {
                    val a = fraction * 2 * Math.PI - Math.PI / 2
                    return Offset(center.x + cos(a).toFloat() * r, center.y + sin(a).toFloat() * r)
                }
                s.marks.forEach { m ->
                    val p = at(m.fraction)
                    // Passed: a dark dot in the gold; ahead: a grey dot ringed in the background, as in the design.
                    drawCircle(if (m.passed) passedDot else bg, 6.dp.toPx(), p)
                    drawCircle(if (m.passed) passedDot else markAhead, 4.dp.toPx(), p)
                }
                if (s.showTip) {
                    val p = at(sweep)
                    drawCircle(Brush.radialGradient(listOf(light.copy(alpha = 0.55f * breath), Color.Transparent), p, 22.dp.toPx()), 22.dp.toPx(), p)
                    drawCircle(lerp(light, Color.White, 0.5f), 11.dp.toPx(), p)
                }
                // The "you did it" burst: a ring of light spreading out past the track, fading as it goes.
                if (burst > 0f) {
                    val fade = (1f - burst).coerceIn(0f, 1f)
                    drawCircle(accent.copy(alpha = 0.6f * fade), r + burst * 24.dp.toPx(), center, style = Stroke(stroke * (1f - burst * 0.7f)))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text(s.topLine.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textSecondary)
                Text(s.bigTime, style = FastingTime, color = Meka.colors.textPrimary)
                if (!s.isFasting) Text(FastingRules.IDLE_RING_CAPTION, style = MekaType.caption, color = Meka.colors.textTertiary)
                Text(s.subLine, style = MekaType.caption, color = Meka.colors.accent, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun EditTile(label: String, value: String, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (open) Meka.colors.surfaceRaised else Meka.colors.surface, MekaMotion.appear(Meka.reducedMotion), label = "edit-tile")
    Column(
        modifier.clip(RoundedCornerShape(MekaRadius.m)).background(bg)
            .clickable(role = Role.Button) { onClick() }
            .semantics { contentDescription = "$label $value. Change" }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
            .testTag("fasting-edit-tile"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        // The pencil sits beside the label, so the value has the tile's whole width.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
            Text("✎", style = MekaType.caption, color = Meka.colors.accent)
        }
        Text(value, style = MekaType.metaStrong, color = Meka.colors.textPrimary)
    }
}

@Composable
private fun MainButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(MekaRadius.pill)
    val accent = Meka.colors.accent
    Box(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (filled) accent else Meka.colors.surface)
            .border(1.dp, accent, shape)
            .clickable(role = Role.Button) { onClick() }
            .padding(vertical = MekaSpace.m)
            .testTag("fasting-main"),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MekaType.metaStrong, color = if (filled) Meka.colors.onAccent else accent)
    }
}

@Composable
private fun Badge(evidence: String, tone: EvidenceTone) {
    val color = when (tone) {
        EvidenceTone.STRONG -> Meka.colors.accent
        EvidenceTone.EARLY -> Meka.colors.calendar3
        EvidenceTone.MODERATE -> Meka.colors.textSecondary
    }
    Text(
        evidence, style = MekaType.micro, color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(color.copy(alpha = 0.14f))
            .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
    )
}

@Composable
private fun StageRow(row: FastingStageRow, fasting: Boolean, open: Boolean, onClick: () -> Unit) {
    val accent = Meka.colors.accent
    val dot = when {
        row.here -> lerp(accent, Color.White, 0.3f)
        row.reached -> accent
        else -> Meka.colors.hairline
    }
    val dim = fasting && !row.reached
    val pad by animateDpAsState(if (open) MekaSpace.s else 0.dp, MekaMotion.expand(Meka.reducedMotion), label = "stage-pad")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
            .background(if (row.here) accent.copy(alpha = 0.1f) else Meka.colors.surface)
            .clickable(role = Role.Button) { onClick() }
            .graphicsLayer { alpha = if (dim) 0.7f else 1f }
            // The row is the 48 dp touch target and no taller (was 64 dp: 8 dp more above and below); open, it keeps
            // 12 dp above and below its words.
            .padding(horizontal = MekaSpace.m, vertical = pad)
            .minTouch()
            .testTag(STAGE_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            // The dot sits on the name's line (the re-shot: it hung below it).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(MekaSpace.xs).clip(CircleShape).background(dot))
                Spacer(Modifier.width(MekaSpace.m))
                Text(row.stage.name, style = MekaType.metaStrong, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(MekaSpace.xs))
                Text(row.stage.whenLabel, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1)
            }
            AnimatedVisibility(open, enter = unfold(), exit = fold()) {
                Column(Modifier.padding(start = MekaSpace.l), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text(row.stage.body, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
                    Badge(row.stage.evidence, row.tone)
                }
            }
        }
    }
}

@Composable
private fun WeekSection(s: FastingScreen, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("THIS WEEK", style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f).semantics { heading() })
            s.weekLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
        }
        if (s.weekShown) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                s.week.forEach { d -> WeekRing(d, Modifier.weight(1f)) }
            }
        } else {
            Text(FastingScreenRules.WEEK_EMPTY, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
    }
}

@Composable
private fun WeekRing(d: FastingWeekRing, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    val fill = remember(d.day) { Animatable(if (reduced) d.fill else 0f) }
    LaunchedEffect(d.fill) { fill.animateTo(d.fill, MekaMotion.replan(reduced)) }
    val accent = Meka.colors.accent
    val color = when {
        d.onGoal -> accent
        d.isToday -> lerp(accent, Color.White, 0.3f)
        else -> accent.copy(alpha = 0.5f)
    }
    val track = Meka.colors.surfaceRaised
    Column(modifier.semantics { contentDescription = d.spoken }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Canvas(Modifier.size(40.dp)) {
            val stroke = 5.dp.toPx()
            val tl = Offset(stroke / 2, stroke / 2)
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, tl, arc, style = Stroke(stroke))
            if (fill.value > 0f) drawArc(color, -90f, 360f * fill.value, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(
            d.day, style = if (d.isToday) MekaType.captionStrong else MekaType.caption, maxLines = 1,
            color = if (d.isToday) accent else Meka.colors.textTertiary,
        )
    }
}

@Composable
private fun HistoryLine(h: FastingHistoryRow, modifier: Modifier = Modifier) {
    // One line as in the mockup: span · length · goal, the row the 48 dp touch target (beside the mockup, 2026-10-30).
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .semantics(mergeDescendants = true) { contentDescription = FastingBesideRules.spoken(h) }
            .padding(horizontal = MekaSpace.m).minTouch().testTag(HISTORY_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (h.onGoal) Meka.colors.accent else Meka.colors.accent.copy(alpha = 0.4f)))
        Spacer(Modifier.width(MekaSpace.s))
        Text(h.span, style = MekaType.itemMeta, color = Meka.colors.textPrimary, maxLines = 1, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(MekaSpace.xs))
        Text(h.length, style = MekaType.metaStrong, color = Meka.colors.textPrimary, maxLines = 1)
        Spacer(Modifier.width(MekaSpace.s))
        Text(
            FastingBesideRules.goalOnRow(h.goal), style = MekaType.caption, maxLines = 1, textAlign = TextAlign.End,
            color = if (h.onGoal) Meka.colors.accent else Meka.colors.textTertiary,
        )
    }
}

internal const val STAGE_ROW_TAG = "fasting-stage-row"
internal const val HISTORY_ROW_TAG = "fasting-history-row"

/** The mockup's title and ring time (FastingBesideRules). */
private val FastingTitle = MekaType.greeting.copy(fontSize = FastingBesideRules.TITLE_SP.sp, lineHeight = (FastingBesideRules.TITLE_SP * 1.15f).sp) // type: ok (beside the mockup: its sizes, FastingBesideRules)
private val FastingTime = MekaType.greeting.copy(fontSize = FastingBesideRules.TIME_SP.sp, fontWeight = FontWeight.Bold, lineHeight = (FastingBesideRules.TIME_SP * 1.1f).sp, letterSpacing = (-0.01f).em) // type: ok (beside the mockup: its sizes, FastingBesideRules)
