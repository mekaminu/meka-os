package os.meka.android.ask

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import os.meka.core.domain.RoutineRow
import os.meka.core.domain.RoutineRules
import os.meka.core.domain.RoutineStarterRow
import os.meka.core.facade.MekaCore

/**
 * Ask → Daily → Routines (Routines, slice 1; the approved mockup `docs/design/routines/routines.dc.html`): how many are
 * on, the rule (a routine never sends, pays, books or trades), YOUR ROUTINES — each a card with its day tile ("SUN"
 * over "19:00"), its name, its line, when it runs next and an On/Off switch (synced) — and START WITH ONE, the
 * starters not added yet, each with Add. The words are the core's [RoutineRules], the same as the Mac's sheet.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; cards stagger in 40 ms apart; a switch
 * blends to the accent with a tick haptic, and the list resizes on the expand spring as a card comes or goes (Add,
 * Remove). Reduced motion: cross-fades and no resize spring.
 */
@Composable
fun RoutinesPane(core: MekaCore, onClose: () -> Unit) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val reduced = Meka.reducedMotion
    val v by core.routinesView.collectAsState()
    var said by remember { mutableStateOf<String?>(null) }
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
                onToggle = {
                    haptics.tick()
                    scope.launch { runCatching { core.setRoutineOn(r.id, !r.on) } }
                },
                onRemove = {
                    haptics.tick()
                    scope.launch {
                        val gone = runCatching { core.removeRoutine(r.id) }.getOrDefault(false)
                        said = if (gone) "Removed ${r.name}" else RoutineRules.GONE_LINE
                    }
                },
            )
        }
        if (v.starters.isNotEmpty()) {
            val base = v.rows.size + 2
            Text(RoutineRules.START_WITH.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.m).semantics { heading() }.appear(rememberAppearance(base)))
            v.starters.forEachIndexed { i, s ->
                StarterCard(s, Modifier.appear(rememberAppearance(base + i + 1))) {
                    haptics.light()
                    scope.launch { said = runCatching { core.addStarterRoutine(s.key) }.getOrNull()?.line ?: RoutineRules.GONE_LINE }
                }
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

/** One of his routines: the tile, the name, its line and next run, Remove, and the On/Off switch (the whole row). */
@Composable
private fun RoutineCard(r: RoutineRow, modifier: Modifier, onToggle: () -> Unit, onRemove: () -> Unit) {
    val dot by animateColorAsState(if (r.on) Meka.colors.accent else Meka.colors.hairline, MekaMotion.appear(Meka.reducedMotion), label = "routine-switch")
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Switch) { onToggle() }
            .semantics { contentDescription = r.spoken; selected = r.on }
            .padding(MekaSpace.m).testTag("routine-row"),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DayTile(r.day, r.time, r.on)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(LoneWordGlue.kept(r.name), style = MekaType.body, color = if (r.on) Meka.colors.textPrimary else Meka.colors.textTertiary)
            Text(LoneWordGlue.kept(r.line), style = MekaType.caption, color = Meka.colors.textSecondary)
            r.next?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
            Text(RoutineRules.REMOVE, style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onRemove() }.minTouch()
                    .semantics { contentDescription = "${RoutineRules.REMOVE} ${r.name}" })
        }
        SwitchPill(r.on, dot, Modifier.semantics { contentDescription = r.switchSpoken })
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
            Text(s.whenLine, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        TouchPill(RoutineRules.ADD, Meka.colors.surface, Meka.colors.accent, spoken = "${RoutineRules.ADD} ${s.name}", onClick = onAdd)
    }
}
