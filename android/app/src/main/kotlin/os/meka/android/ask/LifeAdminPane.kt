package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
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
import os.meka.core.domain.LifeAdminJob
import os.meka.core.domain.LifeAdminRules
import os.meka.core.domain.LocalClock
import os.meka.core.domain.LoneWordGlue
import os.meka.core.facade.MekaCore

/**
 * Ask → More → Life admin hour (slice 1): the day and time picked once (weekday and time chips); MEKA books the hour
 * each week around the calendar and gathers the admin jobs into it: the renewals that need you, then tasks like "Call
 * the GP" or "Fill in the school form". Skip this week frees the hour; Turn off stops it. The planner and Gym leave it
 * clear; Today's header says "Life admin hour at 10:00 · 4 jobs" on the day and a heads-up comes at its start.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary and the line after an action
 * cross-fade; the chip rows and the jobs stagger in 40 ms apart; a chip's colour blends as it is chosen (tick haptic);
 * Skip this week and Turn off unfold once it is on (expand spring), and the skip button's words cross-fade. Reduced
 * motion: cross-fades.
 */
@Composable
fun LifeAdminPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.lifeAdminView.collectAsState()
    // What the last tap did ("Skipped this week's life admin hour · Sat 17 Oct is free again").
    var said by remember { mutableStateOf<String?>(null) }

    fun set(weekday: Int, startMin: Int) {
        haptics.tick()
        said = null
        scope.launch { runCatching { core.setLifeAdmin(weekday, startMin) } }
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("life-admin-pane"),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Text("Life admin hour", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.LIFE_ADMIN)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "life-admin-summary") { s ->
                Text(LoneWordGlue.kept(s), style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "when") {
            Column(Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                SectionLabel("WHEN")
                ChipLine {
                    LifeAdminRules.WEEKDAY_CHOICES.forEachIndexed { i, label ->
                        val weekday = i + 1
                        LifeAdminChip(label, v.on && v.weekday == weekday) { set(weekday, v.startMin) }
                    }
                }
                ChipLine {
                    LifeAdminRules.START_CHOICES.forEach { m ->
                        LifeAdminChip(LocalClock.formatMinute(m), v.startMin == m) { set(v.weekday, m) }
                    }
                }
            }
        }
        item(key = "skip") {
            AnimatedVisibility(v.on && v.thisWeek != null, enter = unfold(), exit = fold()) {
                Crossfade(v.skipped, animationSpec = MekaMotion.appear(reduced), label = "life-admin-skip") { skipped ->
                    Text(
                        if (skipped) LifeAdminRules.KEEP_LABEL else LifeAdminRules.SKIP_LABEL,
                        style = MekaType.caption, color = if (skipped) Meka.colors.accent else Meka.colors.textTertiary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                            haptics.tick()
                            scope.launch { said = runCatching { core.skipLifeAdmin(!skipped) }.getOrNull() ?: said }
                        }.minTouch(),
                    )
                }
            }
        }
        if (v.on) {
            item(key = "label-jobs") {
                SectionLabel("GATHERED INTO IT", Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(3)))
            }
            if (v.jobs.isEmpty()) {
                item(key = "no-jobs") {
                    Text(LoneWordGlue.kept(LifeAdminRules.NO_JOBS), style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().appear(rememberAppearance(4)))
                }
            }
            itemsIndexed(v.jobs, key = { _, j -> j.id }) { i, j ->
                JobRow(j, Modifier.animateItem().appear(rememberAppearance(4 + i)))
            }
            if (v.jobCount > v.jobs.size) {
                item(key = "more") {
                    Text("and ${v.jobCount - v.jobs.size} more", style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem())
                }
            }
        }
        item(key = "said") {
            Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "life-admin-said") { line ->
                line?.let { Text(LoneWordGlue.kept(it), style = MekaType.caption, color = Meka.colors.textSecondary) }
            }
        }
        item(key = "off") {
            AnimatedVisibility(v.on, enter = unfold(), exit = fold()) {
                Text(
                    LifeAdminRules.OFF_LABEL, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                        haptics.tick()
                        said = null
                        scope.launch { runCatching { core.lifeAdminOff() } }
                    }.minTouch(),
                )
            }
        }
        item(key = "note") {
            Text(LoneWordGlue.kept(LifeAdminRules.NOTE), style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

/**
 * The chips wrap to a new line rather than scroll sideways (quality gate, Life admin's shots: on the cover screen Sat,
 * Sun and the evening times sat off the edge); each chip's 48 dp touch already spaces the lines apart.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipLine(chips: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) { chips() }
}

/** A choice chip: the pill drawn as Lists' chips, answering a 48 dp touch (as Protected time's). */
@Composable
private fun LifeAdminChip(label: String, lit: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(Meka.reducedMotion), label = "life-admin-chip")
    Text(
        label, style = MekaType.caption, color = if (lit) Meka.colors.onAccent else Meka.colors.textPrimary, maxLines = 1,
        modifier = Modifier.semantics { selected = lit }.clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(role = Role.Button) { onClick() }.minTouch()
            .clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

@Composable
private fun JobRow(job: LifeAdminJob, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
            .semantics(mergeDescendants = true) { contentDescription = job.spoken },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        // Tasks are things to act on (itemTitle weight would be heavy here: rows use body, like Today's task rows).
        Text(LoneWordGlue.kept(job.title), style = MekaType.body, color = Meka.colors.textPrimary)
        job.lineShown?.let { line -> Text(line, style = MekaType.caption, color = Meka.colors.textSecondary) }
    }
}
