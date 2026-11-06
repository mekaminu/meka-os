package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import os.meka.core.domain.RoutineRow
import os.meka.core.domain.RoutineStepRow
import os.meka.core.domain.RoutineRules
import os.meka.core.domain.RoutineStarterRow
import os.meka.core.facade.MekaCore

/**
 * Ask → Daily → Routines (Routines, slice 1; the approved mockup `docs/design/routines/routines.dc.html`): how many are
 * on, the rule (a routine never sends, pays, books or trades), YOUR ROUTINES — each a card with its day tile ("SUN"
 * over "19:00"), its name, its line, when it runs next and an On/Off switch (synced) — and START WITH ONE, the
 * starters not added yet, each with Add. Slice 2: a tap on a routine opens its own page ([RoutinePage]: when it runs,
 * the NEXT · STEPS · LAST RUN tiles, its steps each with its label, which a tap moves on, LAST TIME, the switch and
 * Remove); ‹ Routines or Back returns. The words are the core's [RoutineRules], the same as the Mac's sheet.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; cards stagger in 40 ms apart; the list
 * and a routine's page cross-fade; a switch or a label blends to its new colour with a tick haptic, and the list
 * resizes on the expand spring as a card comes or goes (Add, Remove). Reduced motion: cross-fades and no resize spring.
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
    BackHandler(enabled = page != null) { open = null }
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
    Crossfade(page?.id, animationSpec = MekaMotion.appear(reduced), label = "routines-page") { shown ->
        val d = shown?.let { v.detail(it) }
        if (d != null) {
            RoutinePage(
                d, onBack = { open = null }, onToggle = { toggle(d.id, !d.on) }, onRemove = { remove(d.id, d.name) },
                onLabel = { st ->
                    val next = st.nextCode ?: return@RoutinePage
                    haptics.tick()
                    scope.launch { runCatching { core.setRoutineStepMode(d.id, st.index, next) } }
                },
            )
        } else {
            RoutinesList(v, said, onClose, onOpen = { open = it }, onToggle = ::toggle, onRemove = ::remove,
                onAdd = { key ->
                    haptics.light()
                    scope.launch { said = runCatching { core.addStarterRoutine(key) }.getOrNull()?.line ?: RoutineRules.GONE_LINE }
                })
        }
    }
}

@Composable
private fun RoutinesList(
    v: os.meka.core.domain.RoutinesView, said: String?, onClose: () -> Unit, onOpen: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit, onRemove: (String, String) -> Unit, onAdd: (String) -> Unit,
) {
    val reduced = Meka.reducedMotion
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter).testTag(ROUTINES_PANE_TAG)
            .let { if (reduced) it else it.animateContentSize(MekaMotion.expand(false)) },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Text(MoreItem.ROUTINES.label, style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.ROUTINES)).appear(rememberAppearance(0)))
        Text(LoneWordGlue.kept(v.summary), style = MekaType.body, color = Meka.colors.textSecondary,
            modifier = Modifier.appear(rememberAppearance(0)).testTag("routines-summary"))
        Text(LoneWordGlue.kept(RoutineRules.RULE_LINE), style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.appear(rememberAppearance(0)))
        said?.let {
            Text(LoneWordGlue.kept(it), style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.testTag("routines-said"))
        }
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
    }
}

/** The pane's own column, for the hand tests. */
const val ROUTINES_PANE_TAG = "routines-pane"

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
private fun RoutinePage(d: RoutineDetail, onBack: () -> Unit, onToggle: () -> Unit, onRemove: () -> Unit, onLabel: (RoutineStepRow) -> Unit) {
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
        Text(RoutineRules.STEPS_LABEL.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.m).semantics { heading() }.appear(rememberAppearance(2)))
        d.steps.forEachIndexed { i, st -> StepRow(st, Modifier.appear(rememberAppearance(i + 3))) { onLabel(st) } }
        Text(LoneWordGlue.kept(d.stepsNote), style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.appear(rememberAppearance(d.steps.size + 3)))
        Text(RoutineRules.LAST_TIME.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.m).semantics { heading() }.appear(rememberAppearance(d.steps.size + 4)))
        Text(LoneWordGlue.kept(d.history), style = MekaType.body, color = Meka.colors.textSecondary,
            modifier = Modifier.appear(rememberAppearance(d.steps.size + 4)))
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
