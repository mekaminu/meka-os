package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.border
import os.meka.android.designsystem.lightCardEdge
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.SectionLabel
import os.meka.android.notify.NotifyChoiceRowView
import os.meka.android.notify.NotifyChoiceSheetView
import os.meka.core.domain.AutonomyChoiceRules
import os.meka.core.domain.AutonomyRow
import os.meka.core.domain.AutonomyRules
import os.meka.core.domain.LoneWordGlue
import os.meka.core.facade.MekaCore

/**
 * Ask → More → What MEKA may do (policy engine UI, slice 1): the kill switch on top ("Pause everything Navilife does
 * on its own" · "Off" ›), then each earned-trust kind under Your lists ("Does it for you", "Asks first · 3 of 5 yeses in
 * a row", "Asks first · you said keep asking"), Your mail's switches, then the locked rows no setting changes (messages,
 * email, money, trading, documents, other people's words, everything else). Synced. Walk-through week 5, A17: no pills
 * on the page; the kill switch and each row that can change open a sheet of their two choices ([AutonomyChoiceRules]).
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary and each row's line
 * cross-fade; the sections stagger in 40 ms apart; a row's dot blends to the accent while MEKA does it on its own; a
 * choice sheet rises over a fading scrim, its lit dot blending, with a tick haptic (pausing or resuming a light one);
 * the answer's line unfolds on the expand spring. Reduced motion: cross-fades.
 */
@Composable
fun AutonomyPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.autonomyView.collectAsState()
    var said by remember { mutableStateOf<String?>(null) }
    // Walk-through week 5, A17: the sheet open, the kill switch's or a row's (by its key).
    var pauseOpen by remember { mutableStateOf(false) }
    var openKey by remember { mutableStateOf<String?>(null) }
    if (pauseOpen) NotifyChoiceSheetView(
        AutonomyChoiceRules.pauseSheet(v.paused),
        onChoose = { name ->
            AutonomyChoiceRules.pauseNamed(v.paused, name)?.let { on ->
                haptics.light()
                scope.launch { runCatching { core.setAutonomyPaused(on) }.getOrNull()?.let { said = it } }
            }
        },
        onClose = { pauseOpen = false },
    )
    val openRow = openKey?.let { k -> v.sections.flatMap { it.rows }.firstOrNull { it.key == k } }
    val openSheet = openRow?.let { AutonomyChoiceRules.sheet(it, v.paused) }
    if (openRow != null && openSheet != null) NotifyChoiceSheetView(
        openSheet,
        onChoose = { name ->
            if (AutonomyChoiceRules.changes(name)) {
                val key = openRow.key
                scope.launch { runCatching { core.resetTrust(key) }.getOrNull()?.let { said = it } }
            }
        },
        onClose = { openKey = null },
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter).testTag("autonomy-pane"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Text("What Navilife may do", style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.AUTONOMY)).appear(rememberAppearance(0)))
        Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "autonomy-summary") { s ->
            Text(LoneWordGlue.kept(s), style = MekaType.body, color = if (v.paused) Meka.colors.accent else Meka.colors.textSecondary,
                modifier = Modifier.appear(rememberAppearance(1)))
        }
        // A15: the last tap's line shows only when it says something the header doesn't.
        val under = AutonomyRules.answerUnder(said, v.summary)
        AnimatedVisibility(under != null, enter = unfold(), exit = fold()) {
            Crossfade(under.orEmpty(), animationSpec = MekaMotion.appear(reduced), label = "autonomy-said") { line ->
                Text(LoneWordGlue.kept(line), style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }

        // Walk-through week 5, A17: the kill switch is a card row ("Pause everything Navilife does on its own" · "Off" ›)
        // opening Off · On, each saying what it does; a choice pauses or resumes and closes the sheet.
        NotifyChoiceRowView(AutonomyChoiceRules.pauseRow(v.paused),
            Modifier.padding(top = MekaSpace.xs).testTag("autonomy-pause").appear(rememberAppearance(2))) { pauseOpen = true }

        var step = 3
        v.sections.forEach { section ->
            SectionLabel(section.label.uppercase(), Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(step++)))
            section.rows.forEach { row ->
                AutonomyRowView(row, Modifier.appear(rememberAppearance(step))) { openKey = row.key }
            }
            step++
        }
        Text(LoneWordGlue.kept(AutonomyRules.NOTE), style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(step)))
    }
}

@Composable
private fun AutonomyRowView(row: AutonomyRow, modifier: Modifier, onAction: () -> Unit) {
    val reduced = Meka.reducedMotion
    val dot by animateColorAsState(
        when {
            row.onItsOwn -> Meka.colors.accent
            row.locked -> Meka.colors.textTertiary
            // Walk-through 2026-11-15, A21: off was surfaceRaised on the card (1.1:1, near-invisible in both themes);
            // now the card's own fill inside a tertiary ring (drawn below), so off reads as an empty dot.
            else -> Meka.colors.surface
        },
        MekaMotion.appear(reduced), label = "autonomy-dot",
    )
    // Walk-through week 5, A17: a row that can change is one card opening its sheet of two choices (the pill it had
    // is gone), its dot, title and line kept, › at its end; a locked row says Locked and opens nothing.
    val opens = AutonomyChoiceRules.opens(row)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).lightCardEdge(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface)
            .clickable(enabled = opens, role = Role.Button, onClick = onAction)
            .semantics(mergeDescendants = true) { contentDescription = AutonomyChoiceRules.spoken(row) }
            .testTag("autonomy-row")
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Box(
            Modifier.size(DOT).testTag("autonomy-dot").clip(CircleShape).background(dot)
                .then(if (!row.onItsOwn && !row.locked) Modifier.border(1.5.dp, Meka.colors.textTertiary, CircleShape) else Modifier),
        )
        Column(Modifier.weight(1f)) {
            Text(LoneWordGlue.kept(row.title), style = MekaType.body, color = Meka.colors.textPrimary)
            Crossfade(row.line, animationSpec = MekaMotion.appear(reduced), label = "autonomy-line") { l ->
                Text(LoneWordGlue.kept(l), style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
        if (row.locked) Text("Locked", style = MekaType.caption, color = Meka.colors.textTertiary)
        else if (opens) Text("›", style = MekaType.body, color = Meka.colors.textTertiary)
    }
}

/** A row's mark (lit while Navilife does it on its own). */
private val DOT = 10.dp

/**
 * "What Navilife may do" completeness: while Navilife is paused, Today says so under the header — "Paused since 21:40 ·
 * Navilife asks before everything" in the accent — with **Resume** beside it (light haptic), so a pause from the watch,
 * Talk or the other device is never forgotten. It unfolds on the expand spring and folds away on Resume (reduced
 * motion: a fade); the line cross-fades as it changes.
 */
@Composable
fun AutonomyTodayLine(core: MekaCore, modifier: Modifier = Modifier) {
    val v by core.autonomyView.collectAsState()
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val reduced = Meka.reducedMotion
    val line = v.todayLine
    // The last line shown, so the row folds away still showing it (not state: nothing reads it to recompose).
    val last = remember { arrayOfNulls<String>(1) }
    if (line != null) last[0] = line
    AnimatedVisibility(
        line != null, modifier,
        enter = if (reduced) androidx.compose.animation.fadeIn(MekaMotion.appear(true)) else androidx.compose.animation.expandVertically(MekaMotion.expand(false)) + androidx.compose.animation.fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) androidx.compose.animation.fadeOut(MekaMotion.appear(true)) else androidx.compose.animation.shrinkVertically(MekaMotion.expand(false)) + androidx.compose.animation.fadeOut(MekaMotion.appear(false)),
        label = "autonomy-today",
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.l), verticalAlignment = Alignment.CenterVertically) {
            Crossfade(line ?: last[0].orEmpty(), Modifier.weight(1f), MekaMotion.appear(reduced), label = "autonomy-today-text") { t ->
                Text(t, style = MekaType.caption, color = Meka.colors.accent, modifier = Modifier.padding(vertical = MekaSpace.xs))
            }
            Text(
                AutonomyRules.RESUME, style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) {
                    haptics.light()
                    scope.launch { runCatching { core.setAutonomyPaused(false) } }
                }.padding(vertical = MekaSpace.xs).semantics { contentDescription = "Resume Navilife" }.minTouch(),
            )
        }
    }
}
