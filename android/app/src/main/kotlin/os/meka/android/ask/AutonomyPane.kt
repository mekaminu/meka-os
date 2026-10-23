package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import os.meka.core.domain.AutonomyRow
import os.meka.core.domain.AutonomyRules
import os.meka.core.domain.LoneWordGlue
import os.meka.core.facade.MekaCore

/**
 * Ask → More → What MEKA may do (policy engine UI, slice 1): the kill switch on top ("Pause everything MEKA does on its
 * own" · Pause / Resume), then each earned-trust kind under Your lists ("Does it for you" with Ask first, "Asks first ·
 * 3 of 5 yeses in a row", "Asks first · you said keep asking" with Let MEKA offer again), then the locked rows no
 * setting changes (messages, email, money, trading, documents, other people's words, everything else). Synced.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary, each row's line and the pause
 * button cross-fade; the sections stagger in 40 ms apart; a row's dot blends to the accent while MEKA does it on its own;
 * Pause / Resume gives a light haptic, Ask first a tick; the answer's line unfolds on the expand spring. Reduced motion:
 * cross-fades.
 */
@Composable
fun AutonomyPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.autonomyView.collectAsState()
    var said by remember { mutableStateOf<String?>(null) }

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
        AnimatedVisibility(said != null, enter = unfold(), exit = fold()) {
            Crossfade(said.orEmpty(), animationSpec = MekaMotion.appear(reduced), label = "autonomy-said") { line ->
                Text(LoneWordGlue.kept(line), style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }

        // The kill switch. Quality gate slice 54: on the cover screen (below 420 dp) Pause sits under its words.
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
                .testTag("autonomy-pause").appear(rememberAppearance(2)),
        ) {
            val under = AutonomyRules.actionUnder((maxWidth - MekaSpace.m * 2).value)
            val words: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    Text(LoneWordGlue.kept(AutonomyRules.PAUSE_TITLE), style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(LoneWordGlue.kept(AutonomyRules.PAUSE_LINE), style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
            val pill: @Composable () -> Unit = {
                Pill(v.pauseButton, lit = v.paused) {
                    haptics.light()
                    scope.launch { runCatching { core.setAutonomyPaused(!v.paused) }.getOrNull()?.let { said = it } }
                }
            }
            if (under) {
                Column(Modifier.fillMaxWidth().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)) {
                    words(Modifier.fillMaxWidth())
                    pill()
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    words(Modifier.weight(1f))
                    pill()
                }
            }
        }

        var step = 3
        v.sections.forEach { section ->
            SectionLabel(section.label.uppercase(), Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(step++)))
            section.rows.forEach { row ->
                AutonomyRowView(row, Modifier.appear(rememberAppearance(step))) {
                    haptics.tick()
                    scope.launch { runCatching { core.resetTrust(row.key) }.getOrNull()?.let { said = it } }
                }
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
            else -> Meka.colors.surfaceRaised
        },
        MekaMotion.appear(reduced), label = "autonomy-dot",
    )
    // Quality gate slice 54: on the cover screen (below 420 dp) Ask first / Turn off / Let Navilife offer again sits
    // under the row's words, lined up with them past the dot, instead of squeezing them beside it.
    BoxWithConstraints(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface).testTag("autonomy-row"),
    ) {
        val under = AutonomyRules.actionUnder((maxWidth - MekaSpace.m * 2).value)
        val action = row.action
        val words: @Composable (Modifier) -> Unit = { m ->
            Row(
                m.semantics(mergeDescendants = true) {
                    contentDescription = row.title + ". " + row.line + if (row.locked) ". Locked" else ""
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Box(Modifier.size(DOT).clip(CircleShape).background(dot))
                Column(Modifier.weight(1f)) {
                    Text(LoneWordGlue.kept(row.title), style = MekaType.body, color = Meka.colors.textPrimary)
                    Crossfade(row.line, animationSpec = MekaMotion.appear(reduced), label = "autonomy-line") { l ->
                        Text(LoneWordGlue.kept(l), style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                }
                if (row.locked) Text("Locked", style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }
        if (under && action != null && !row.locked) {
            Column(Modifier.fillMaxWidth().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)) {
                words(Modifier.fillMaxWidth().minTouch())
                Box(Modifier.padding(start = DOT + MekaSpace.xs)) { Pill(action, lit = false, onClick = onAction) }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                words(Modifier.weight(1f))
                if (!row.locked && action != null) Pill(action, lit = false, onClick = onAction)
            }
        }
    }
}

/** A row's mark (lit while Navilife does it on its own). */
private val DOT = 10.dp

@Composable
private fun Pill(label: String, lit: Boolean, onClick: () -> Unit) {
    val reduced = Meka.reducedMotion
    val fill by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(reduced), label = "autonomy-pill")
    val ink by animateColorAsState(if (lit) Meka.colors.onAccent else Meka.colors.accent, MekaMotion.appear(reduced), label = "autonomy-pill-ink")
    Box(
        Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(label, animationSpec = MekaMotion.appear(reduced), label = "autonomy-pill-label") { t ->
            Text(t, style = MekaType.itemMeta, color = ink)
        }
    }
}

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
                }.padding(vertical = MekaSpace.xs).semantics { contentDescription = "Resume Navilife" },
            )
        }
    }
}
