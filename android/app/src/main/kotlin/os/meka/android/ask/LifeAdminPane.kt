package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import os.meka.android.lists.Chip
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.LifeAdminPageRules
import os.meka.core.domain.LifeAdminRules
import os.meka.core.domain.LifeAdminView
import os.meka.core.domain.LifePageRules
import os.meka.core.domain.LocalClock
import os.meka.core.domain.LoneWordGlue
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val LIFE_ADMIN_LIST_TAG = "life-admin-pane"
const val LIFE_ADMIN_HOUR_SHEET_TAG = "life-admin-hour-sheet"
const val LIFE_ADMIN_WEEK_SHEET_TAG = "life-admin-week-sheet"
const val LIFE_ADMIN_JOB_SHEET_TAG = "life-admin-job-sheet"

/**
 * Ask → Life admin hour in the page shape (week 5's walk-through A16; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`), like Date night: the title with its ⓘ, one gold + Add (The hour: the weekday and
 * the start time as chips in its sheet, with Turn off), three numbers (how soon the next hour is, the jobs waiting, how
 * many are overdue), then Gathered into it (five jobs, See all for the rest; a task's row opens Done) and The hour (this
 * week's hour opens Skip this week / Keep it; the setting's row opens the chooser). Off, it is one raised card with Pick
 * the day. Navilife books the hour around the calendar; the planner and Gym leave it clear.
 *
 * Motion: the title travels in from Ask's tile (shared title); the header, tiles and cards appear in turn; cards move
 * with the list's item motion; the ⓘ's words unfold and the sheets rise on the expand spring (LifePageParts); the line
 * after an action cross-fades; a chip's colour blends as it is chosen (tick haptic); Turn off unfolds once it is on
 * (expand spring); Done gives a light haptic, the rest a tick. Reduced motion: cross-fades.
 */
@Composable
fun LifeAdminPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.lifeAdminView.collectAsState()
    var all by remember { mutableStateOf(false) }
    val page = remember(v, all) { core.lifeAdminPage(v, all) }
    // What the last tap did ("Skipped this week's life admin hour · Sat 24 Oct is free again").
    var said by remember { mutableStateOf<String?>(null) }
    var infoOpen by remember { mutableStateOf(false) }
    var hourOpen by remember { mutableStateOf(false) }
    var weekOpen by remember { mutableStateOf(false) }
    var jobKey by remember { mutableStateOf<String?>(null) }

    fun set(weekday: Int, startMin: Int) {
        haptics.tick()
        said = null
        scope.launch { runCatching { core.setLifeAdmin(weekday, startMin) } }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(LIFE_ADMIN_LIST_TAG),
            contentPadding = PaddingValues(MekaSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "close") {
                Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
            }
            item(key = "title") {
                LifePageHeader(
                    page.title, page.info, infoOpen,
                    titleModifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.LIFE_ADMIN)).appear(rememberAppearance(0)),
                    onInfo = { haptics.tick(); infoOpen = !infoOpen },
                    onAdd = { haptics.tick(); said = null; hourOpen = true },
                )
            }
            item(key = "summary") {
                Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    if (LifeAdminPageRules.blank(v)) {
                        LifeEmptyCard(LifeAdminPageRules.EMPTY_LINE, LifeAdminPageRules.ADD_FIRST) {
                            haptics.tick(); said = null; hourOpen = true
                        }
                    }
                    val line = if (hourOpen || weekOpen || jobKey != null) null else said
                    Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "life-admin-said") { l ->
                        l?.let { Text(LoneWordGlue.kept(it), style = MekaType.caption, color = Meka.colors.textSecondary) }
                    }
                }
            }
            if (page.tiles.isNotEmpty()) {
                item(key = "tiles") { LifeNumberTiles(page.tiles, Modifier.appear(rememberAppearance(2))) }
            }
            page.cards.forEachIndexed { i, c ->
                item(key = "card-${c.key}") {
                    LifePageCard(
                        c, Modifier.animateItem().appear(rememberAppearance(3 + i)),
                        onMore = { haptics.tick(); all = !all },
                        onRow = { r ->
                            haptics.tick()
                            said = null
                            when (r.key) {
                                LifeAdminPageRules.EVERY_KEY -> hourOpen = true
                                LifeAdminPageRules.WEEK_KEY, LifeAdminPageRules.NEXT_KEY -> weekOpen = true
                                else -> jobKey = r.key
                            }
                        },
                    )
                }
            }
        }
        // + Add → The hour (and the setting's row): the weekday, the start time and Turn off.
        LifeSheet(hourOpen, LifeAdminPageRules.HOUR, LIFE_ADMIN_HOUR_SHEET_TAG, onClose = { hourOpen = false }) {
            HourChooser(v, ::set) {
                haptics.tick()
                said = null
                hourOpen = false
                scope.launch { runCatching { core.lifeAdminOff() } }
            }
        }
        // This week's hour: when it is, and Skip this week / Keep it.
        LifeSheet(weekOpen, v.thisWeek?.whenLine ?: v.next?.whenLine ?: LifeAdminPageRules.HOUR, LIFE_ADMIN_WEEK_SHEET_TAG, onClose = { weekOpen = false }) {
            Crossfade(v.skipped, animationSpec = MekaMotion.appear(reduced), label = "life-admin-week-line") { skipped ->
                Text(
                    if (skipped) LifeAdminPageRules.SKIPPED_LINE else LifeAdminPageRules.BOOKED_LINE,
                    style = MekaType.body, color = if (skipped) Meka.colors.textTertiary else Meka.colors.textPrimary,
                )
            }
            if (v.thisWeek != null) {
                val label = LifeAdminPageRules.skipLabel(v)
                Text(
                    label, style = MekaType.itemMeta, color = Meka.colors.textPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                        .clickable(role = Role.Button) {
                            haptics.tick()
                            val skip = !v.skipped
                            scope.launch { said = runCatching { core.skipLifeAdmin(skip) }.getOrNull() ?: said }
                        }
                        .minTouch().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                )
            }
            Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "life-admin-week-said") { l ->
                l?.let { Text(LoneWordGlue.kept(it), style = MekaType.caption, color = Meka.colors.textSecondary) }
            }
        }
        // A gathered job: its line, and Done for a task (a renewal is marked renewed in Lists).
        val openKey = jobKey
        val job = openKey?.let { LifeAdminPageRules.job(v, it) }
        LifeSheet(openKey != null, job?.title ?: LifeAdminPageRules.JOBS, LIFE_ADMIN_JOB_SHEET_TAG, onClose = { jobKey = null }) {
            if (job == null) {
                if (openKey != null) Text(LifeAdminPageRules.GONE, style = MekaType.caption, color = Meka.colors.textSecondary)
            } else {
                job.lineShown?.let { Text(it, style = MekaType.body, color = Meka.colors.textPrimary) }
                val taskId = LifeAdminPageRules.taskId(job)
                if (taskId == null) {
                    Text(LifePageRules.shown(LifeAdminPageRules.RENEWAL_IN_LISTS), style = MekaType.caption, color = Meka.colors.textSecondary)
                } else {
                    Text(
                        LifeAdminPageRules.DONE, style = MekaType.itemMeta, color = Meka.colors.onAccent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                            .clickable(role = Role.Button) {
                                haptics.light()
                                jobKey = null
                                scope.launch { runCatching { core.complete(taskId) } }
                            }
                            .semantics { contentDescription = "${LifeAdminPageRules.DONE}, ${job.title}" }
                            .minTouch().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                    )
                }
            }
        }
    }
}

/** The hour's chooser in its sheet: a weekday chip, a start time, Turn off. */
@Composable
private fun HourChooser(v: LifeAdminView, set: (Int, Int) -> Unit, onOff: () -> Unit) {
    Text(LifeAdminPageRules.WEEKDAY, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
    ChipLine {
        LifeAdminRules.WEEKDAY_CHOICES.forEachIndexed { i, label ->
            val weekday = i + 1
            Chip(label, v.on && v.weekday == weekday) { set(weekday, v.startMin) }
        }
    }
    Text(LifeAdminPageRules.START, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
    ChipLine {
        LifeAdminRules.START_CHOICES.forEach { m ->
            Chip(LocalClock.formatMinute(m), LifeAdminRules.startLit(v.on, v.startMin, m)) { set(v.weekday, m) }
        }
    }
    AnimatedVisibility(v.on, enter = unfold(), exit = fold()) {
        Text(
            LifeAdminRules.OFF_LABEL, style = MekaType.caption, color = Meka.colors.textSecondary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onOff).minTouch(),
        )
    }
}

/** A line of chips that wraps onto a second line rather than running off the right edge. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipLine(chips: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) { chips() }
}
