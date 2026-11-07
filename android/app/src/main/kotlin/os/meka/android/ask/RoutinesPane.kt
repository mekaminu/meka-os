package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.SwitchPill
import os.meka.android.designsystem.TouchPill
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.RoutineDetail
import os.meka.core.domain.RoutineIdea
import os.meka.core.domain.RoutineIdeas
import os.meka.core.domain.RoutineLogLine
import os.meka.core.domain.RoutineOutcome
import os.meka.core.domain.RoutineRow
import os.meka.core.domain.RoutineRunCard
import os.meka.core.domain.RoutineStepRow
import os.meka.core.domain.RoutineRules
import os.meka.core.domain.RoutineSentence
import os.meka.core.domain.RoutineStarterRow
import os.meka.core.facade.MekaCore

/**
 * Ask → Daily → Routines (Routines, slice 1; the approved mockup `docs/design/routines/routines.dc.html`): how many are
 * on, the rule (a routine never sends, pays, books or trades), YOUR ROUTINES — each a card with its day tile ("SUN"
 * over "19:00"), its name, its line, when it runs next and an On/Off switch (synced) — and START WITH ONE, the
 * starters not added yet, each with Add. Slice 2: a tap on a routine opens its own page ([RoutinePage]: when it runs,
 * the NEXT · STEPS · LAST RUN tiles, its steps each with its label, which a tap moves on, LAST TIME, the switch and
 * Remove); ‹ Routines or Back returns. Slice 3: Run now on its page starts a run; the Running now card ([RunningCard],
 * on the list and the page) shows the step it's on, with Carry on · Skip this step · Stop, and LAST TIME lists what
 * each step came to. Slice 4: + New beside the title opens [NewRoutinePage] (said or typed in one sentence, Save adds it
 * switched on and the list says when it first runs). The words are the core's [RoutineRules], the same as the Mac's sheet.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; cards stagger in 40 ms apart; the list
 * and a routine's page cross-fade; a switch or a label blends to its new colour with a tick haptic, and the list
 * resizes on the expand spring as a card comes or goes (Add, Remove). The Running now card's bar grows on the expand
 * spring as steps go by, with a light haptic on each tap. Reduced motion: cross-fades, no resize spring, the bar jumps.
 */
@Composable
fun RoutinesPane(core: MekaCore, onClose: () -> Unit) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val reduced = Meka.reducedMotion
    val v by core.routinesView.collectAsState()
    var said by remember { mutableStateOf<String?>(null) }
    // Slice 2: the routine whose page is open (null: the list).
    var open by remember { mutableStateOf<String?>(null) }
    val page = open?.let { v.detail(it) }
    // Slice 4: New routine is open (said or typed in one sentence).
    var making by remember { mutableStateOf(false) }
    var makeSaid by remember { mutableStateOf<String?>(null) }
    // Slice 5: the idea New routine was opened from (its sentence filled in), or null.
    var fromIdea by remember { mutableStateOf<RoutineIdea?>(null) }
    BackHandler(enabled = page != null || making) { open = null; making = false }
    fun toggle(id: String, on: Boolean) {
        haptics.tick()
        scope.launch { runCatching { core.setRoutineOn(id, on) } }
    }
    fun remove(id: String, name: String) {
        haptics.tick()
        scope.launch {
            val gone = runCatching { core.removeRoutine(id) }.getOrDefault(false)
            said = if (gone) "Removed $name" else RoutineRules.GONE_LINE
            open = null
        }
    }
    // Slice 3: Run now and the Running now card's three taps.
    fun run(id: String, act: suspend MekaCore.(String) -> Boolean) {
        haptics.light()
        scope.launch { runCatching { core.act(id) } }
    }
    val runActions = RunActions(
        carryOn = { run(it) { i -> carryOnRoutine(i) } },
        skip = { run(it) { i -> skipRoutineStep(i) } },
        stop = { run(it) { i -> stopRoutineRun(i) } },
    )
    Crossfade(if (making) NEW_KEY else page?.id, animationSpec = MekaMotion.appear(reduced), label = "routines-page") { shown ->
        val d = shown?.takeIf { it != NEW_KEY }?.let { v.detail(it) }
        if (shown == NEW_KEY) {
            NewRoutinePage(
                onBack = { making = false; makeSaid = null; fromIdea = null },
                said = makeSaid,
                start = fromIdea?.sentence.orEmpty(),
                onSave = { text, modes ->
                    val idea = fromIdea?.takeIf { text.trim() == it.sentence }?.key
                    scope.launch {
                        val r = runCatching { if (idea != null) core.addRoutineFromIdea(text, modes, idea) else core.addRoutineFromSentence(text, modes) }.getOrNull()
                        if (r != null && !r.refused) { said = r.line; makeSaid = null; making = false; fromIdea = null } else { makeSaid = r?.line ?: RoutineRules.GONE_LINE }
                    }
                },
            )
        } else if (d != null) {
            RoutinePage(
                d, runActions, onRunNow = { run(d.id) { i -> runRoutineNow(i) } },
                onBack = { open = null }, onToggle = { toggle(d.id, !d.on) }, onRemove = { remove(d.id, d.name) },
                onLabel = { st ->
                    val next = st.nextCode ?: return@RoutinePage
                    haptics.tick()
                    scope.launch { runCatching { core.setRoutineStepMode(d.id, st.index, next) } }
                },
            )
        } else {
            RoutinesList(v, said, runActions, onClose, onOpen = { open = it }, onToggle = ::toggle, onRemove = ::remove,
                onNew = { haptics.light(); said = null; makeSaid = null; fromIdea = null; making = true },
                onIdea = { idea -> haptics.light(); said = null; makeSaid = null; fromIdea = idea; making = true },
                onAdd = { key ->
                    haptics.light()
                    scope.launch { said = runCatching { core.addStarterRoutine(key) }.getOrNull()?.line ?: RoutineRules.GONE_LINE }
                })
        }
    }
}

@Composable
private fun RoutinesList(
    v: os.meka.core.domain.RoutinesView, said: String?, actions: RunActions, onClose: () -> Unit, onOpen: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit, onRemove: (String, String) -> Unit, onAdd: (String) -> Unit, onNew: () -> Unit,
    onIdea: (RoutineIdea) -> Unit,
) {
    val reduced = Meka.reducedMotion
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter).testTag(ROUTINES_PANE_TAG)
            .let { if (reduced) it else it.animateContentSize(MekaMotion.expand(false)) },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(MoreItem.ROUTINES.label, style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.weight(1f).sharedTitleInPane(SharedMotion.paneKey(MoreItem.ROUTINES)).appear(rememberAppearance(0)))
            // Slice 4: the mockup's brass + New beside the title opens New routine.
            TouchPill(RoutineSentence.NEW, Meka.colors.accent, Meka.colors.onAccent, modifier = Modifier.testTag("routines-new"),
                spoken = RoutineSentence.NEW_SPOKEN, onClick = onNew)
        }
        Text(LoneWordGlue.kept(v.summary), style = MekaType.body, color = Meka.colors.textSecondary,
            modifier = Modifier.appear(rememberAppearance(0)).testTag("routines-summary"))
        Text(LoneWordGlue.kept(RoutineRules.RULE_LINE), style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.appear(rememberAppearance(0)))
        said?.let {
            Text(LoneWordGlue.kept(it), style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.testTag("routines-said"))
        }
        v.running.forEach { card -> RunningCard(card, actions, Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(1))) }
        if (v.rows.isNotEmpty()) {
            Text(RoutineRules.YOURS.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.m).semantics { heading() }.appear(rememberAppearance(1)))
        }
        v.rows.forEachIndexed { i, r ->
            RoutineCard(
                r, Modifier.appear(rememberAppearance(i + 2)),
                onOpen = { onOpen(r.id) }, onToggle = { onToggle(r.id, !r.on) }, onRemove = { onRemove(r.id, r.name) },
            )
        }
        if (v.starters.isNotEmpty()) {
            val base = v.rows.size + 2
            Text(RoutineRules.START_WITH.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.m).semantics { heading() }.appear(rememberAppearance(base)))
            v.starters.forEachIndexed { i, s ->
                StarterCard(s, Modifier.appear(rememberAppearance(base + i + 1))) { onAdd(s.key) }
            }
        }
        // Slice 5: the mockup's IDEAS FROM YOUR WEEK, dashed tiles two to a row; a tap opens New routine filled in.
        if (v.ideas.isNotEmpty()) {
            val base = v.rows.size + v.starters.size + 3
            Text(RoutineIdeas.TITLE.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.m).semantics { heading() }.appear(rememberAppearance(base)))
            v.ideas.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).appear(rememberAppearance(base + row + 1)),
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    pair.forEach { idea -> IdeaTile(idea, Modifier.weight(1f).fillMaxHeight()) { onIdea(idea) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text(LoneWordGlue.kept(RoutineIdeas.NOTE), style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.appear(rememberAppearance(base + 1)))
        }
    }
}

/** An idea from his week: a dashed tile with its name and why; a tap opens New routine with its sentence. */
@Composable
private fun IdeaTile(idea: RoutineIdea, modifier: Modifier, onClick: () -> Unit) {
    val line = Meka.colors.hairline
    Column(
        modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = idea.spoken }
            .drawBehind {
                val inset = 0.5.dp.toPx()
                drawRoundRect(
                    line, topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
                    cornerRadius = CornerRadius(MekaRadius.m.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .minTouch().padding(MekaSpace.m).testTag("routine-idea"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(LoneWordGlue.kept(idea.name), style = MekaType.body, color = Meka.colors.textPrimary)
        Text(LoneWordGlue.kept(idea.why), style = MekaType.caption, color = Meka.colors.textSecondary)
    }
}

/** The pane's own column, for the hand tests. */
const val ROUTINES_PANE_TAG = "routines-pane"

/** The cross-fade's key for New routine (routine ids never start with "+"). */
private const val NEW_KEY = "+new"

/** The day tile: "SUN" over "19:00", on the raised surface. */
@Composable
private fun DayTile(day: String, time: String, on: Boolean) {
    Column(
        Modifier.widthIn(min = 64.dp).clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(day, style = MekaType.sectionLabel, color = if (on) Meka.colors.accent else Meka.colors.textTertiary, maxLines = 1)
        Text(time, style = MekaType.itemMeta, color = if (on) Meka.colors.textPrimary else Meka.colors.textTertiary, maxLines = 1)
    }
}

/**
 * One of his routines: the tile, the name, its line and next run, Remove, and the On/Off switch. Slice 2: a tap on
 * the card opens its page; the switch is its own 48 dp touch.
 */
@Composable
private fun RoutineCard(r: RoutineRow, modifier: Modifier, onOpen: () -> Unit, onToggle: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onOpen() }
            .semantics { contentDescription = r.spoken }
            .padding(MekaSpace.m).testTag("routine-row"),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DayTile(r.day, r.time, r.on)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(LoneWordGlue.kept(r.name), style = MekaType.body, color = if (r.on) Meka.colors.textPrimary else Meka.colors.textTertiary)
            Text(LoneWordGlue.kept(r.line), style = MekaType.caption, color = Meka.colors.textSecondary)
            r.next?.let { Text(LoneWordGlue.kept(it), style = MekaType.caption, color = Meka.colors.textTertiary) }
            Text(RoutineRules.REMOVE, style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onRemove() }.minTouch()
                    .semantics { contentDescription = "${RoutineRules.REMOVE} ${r.name}" })
        }
        RoutineSwitch(r.on, r.switchSpoken, onToggle)
    }
}

/** The On/Off switch: its own 48 dp touch, the pill blending to the accent when on. */
@Composable
private fun RoutineSwitch(on: Boolean, spoken: String, onToggle: () -> Unit) {
    val dot by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.hairline, MekaMotion.appear(Meka.reducedMotion), label = "routine-switch")
    Box(
        Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Switch) { onToggle() }
            .semantics { contentDescription = spoken; selected = on }.minTouch().testTag("routine-switch"),
        contentAlignment = Alignment.Center,
    ) { SwitchPill(on, dot) }
}

/**
 * A routine's own page (slice 2): ‹ Routines, its name and when it runs, the NEXT · STEPS · LAST RUN tiles, STEPS
 * with each step's number, title, place and label (a tap moves the label on), the note on labels, LAST TIME, the
 * switch and Remove.
 */
@Composable
private fun RoutinePage(
    d: RoutineDetail, actions: RunActions, onRunNow: () -> Unit,
    onBack: () -> Unit, onToggle: () -> Unit, onRemove: () -> Unit, onLabel: (RoutineStepRow) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter).testTag("routine-page"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("‹ ${RoutineRules.BACK}", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onBack() }.minTouch().semantics { contentDescription = "Back to ${RoutineRules.BACK}" })
        Row(Modifier.fillMaxWidth().appear(rememberAppearance(0)), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(LoneWordGlue.kept(d.name), style = MekaType.greeting, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
                Text(LoneWordGlue.kept(d.whenLine), style = MekaType.body, color = Meka.colors.textSecondary)
            }
            RoutineSwitch(d.on, if (d.on) "Pause ${d.name}" else "Switch on ${d.name}", onToggle)
        }
        Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs).appear(rememberAppearance(1)), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            PageTile(RoutineRules.NEXT_TILE, d.next, Modifier.weight(1f))
            PageTile(RoutineRules.STEPS_TILE, d.stepCount, Modifier.weight(1f))
            PageTile(RoutineRules.LAST_TILE, d.lastRun, Modifier.weight(1f))
        }
        d.running?.let { RunningCard(it, actions, Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(1))) }
        d.runNow?.let { label ->
            Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs).appear(rememberAppearance(1))) {
                TouchPill(label, Meka.colors.accent, Meka.colors.onAccent, spoken = "$label: ${d.name}", onClick = onRunNow)
            }
        }
        Text(RoutineRules.STEPS_LABEL.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.m).semantics { heading() }.appear(rememberAppearance(2)))
        d.steps.forEachIndexed { i, st -> StepRow(st, Modifier.appear(rememberAppearance(i + 3))) { onLabel(st) } }
        Text(LoneWordGlue.kept(d.stepsNote), style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.appear(rememberAppearance(d.steps.size + 3)))
        Text(RoutineRules.LAST_TIME.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.m).semantics { heading() }.appear(rememberAppearance(d.steps.size + 4)))
        Text(LoneWordGlue.kept(d.history), style = MekaType.body, color = Meka.colors.textSecondary,
            modifier = Modifier.appear(rememberAppearance(d.steps.size + 4)).testTag("routine-history"))
        d.lastSteps.forEach { LastStepRow(it, Modifier.appear(rememberAppearance(d.steps.size + 4))) }
        Row(Modifier.fillMaxWidth().padding(top = MekaSpace.m), horizontalArrangement = Arrangement.End) {
            TouchPill(RoutineRules.REMOVE, Meka.colors.surfaceRaised, Meka.colors.textSecondary, spoken = "${RoutineRules.REMOVE} ${d.name}", onClick = onRemove)
        }
    }
}

/** A tile on a routine's page: "NEXT" over "Sun 8 Nov". */
@Composable
private fun PageTile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.xs)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
    ) {
        Text(label.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, maxLines = 1)
        Text(os.meka.core.domain.DateGlue.kept(value), style = MekaType.itemMeta, color = Meka.colors.textPrimary)
    }
}

/** One step: its number in a ring, its title and place, and its label as a pill (a tap moves it on). */
@Composable
private fun StepRow(st: RoutineStepRow, modifier: Modifier, onLabel: () -> Unit) {
    val ink by animateColorAsState(
        when (st.mode) {
            os.meka.core.domain.RoutineStepMode.SHOW -> Meka.colors.textSecondary
            os.meka.core.domain.RoutineStepMode.ASK -> Meka.colors.accent
            os.meka.core.domain.RoutineStepMode.DOES -> Meka.colors.textPrimary
        },
        MekaMotion.appear(Meka.reducedMotion), label = "routine-step-label",
    )
    Row(
        modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs).testTag("routine-step"),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Meka.colors.surfaceRaised), contentAlignment = Alignment.Center) {
            Text(st.number, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        Column(Modifier.weight(1f)) {
            Text(LoneWordGlue.kept(st.title), style = MekaType.body, color = Meka.colors.textPrimary)
            Text(st.place, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        if (st.nextBadge != null) {
            TouchPill(st.badge, Meka.colors.surfaceRaised, ink, spoken = st.spoken, onClick = onLabel)
        } else {
            Text(st.badge, style = MekaType.caption, color = ink, modifier = Modifier.semantics { contentDescription = st.spoken })
        }
    }
}

/** A starter not added yet: the tile, its name, line and when, and Add. */
@Composable
private fun StarterCard(s: RoutineStarterRow, modifier: Modifier, onAdd: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).semantics(mergeDescendants = false) { contentDescription = s.spoken }.testTag("routine-starter"),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DayTile(s.day, s.time, on = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(LoneWordGlue.kept(s.name), style = MekaType.body, color = Meka.colors.textPrimary)
            Text(LoneWordGlue.kept(s.line), style = MekaType.caption, color = Meka.colors.textSecondary)
            Text(LoneWordGlue.kept(s.whenLine), style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        TouchPill(RoutineRules.ADD, Meka.colors.surface, Meka.colors.accent, spoken = "${RoutineRules.ADD} ${s.name}", onClick = onAdd)
    }
}

/** The Running now card's three taps, by routine id. */
private class RunActions(val carryOn: (String) -> Unit, val skip: (String) -> Unit, val stop: (String) -> Unit)

/**
 * The Running now card (slice 3, the mockup's): RUNNING NOW · the routine, "Step 2 of 4" with a bar, the step it's on
 * and its place, "Waiting on you" (an Asks you step) or "Have a look" (Just show), then Carry on · Skip this step · Stop.
 */
@Composable
private fun RunningCard(c: RoutineRunCard, actions: RunActions, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    val fill by animateFloatAsState(c.fraction, MekaMotion.expand(reduced), label = "routine-run-bar")
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("routine-running"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = c.spoken }) {
            Text(RoutineRules.RUNNING_NOW.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.accent)
            Text(LoneWordGlue.kept(c.name), style = MekaType.itemMeta, color = Meka.colors.textSecondary)
            Text(LoneWordGlue.kept(c.stepTitle), style = MekaType.itemTitle, color = Meka.colors.textPrimary)
            Text("${c.progress} · ${c.place} · ${c.state}", style = MekaType.caption,
                color = if (c.waitingOnYou) Meka.colors.accent else Meka.colors.textTertiary)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.hairline)) {
            Box(Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            TouchPill(RoutineRules.CARRY_ON, Meka.colors.accent, Meka.colors.onAccent, spoken = "${RoutineRules.CARRY_ON}: ${c.stepTitle}") { actions.carryOn(c.id) }
            TouchPill(RoutineRules.SKIP_STEP, Meka.colors.surface, Meka.colors.textSecondary, spoken = "${RoutineRules.SKIP_STEP}: ${c.stepTitle}") { actions.skip(c.id) }
            Box(Modifier.weight(1f))
            Text(RoutineRules.STOP, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.clickable(role = Role.Button) { actions.stop(c.id) }.minTouch()
                    .semantics { contentDescription = "${RoutineRules.STOP} ${c.name}" })
        }
    }
}

/** One of LAST TIME's steps: its title and what it came to. */
@Composable
private fun LastStepRow(l: RoutineLogLine, modifier: Modifier) {
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(LoneWordGlue.kept(l.title), style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
        Text(l.outcome.label, style = MekaType.caption,
            color = when (l.outcome) {
                RoutineOutcome.SKIPPED, RoutineOutcome.NOT_REACHED -> Meka.colors.textTertiary
                else -> Meka.colors.textPrimary
            })
    }
}
