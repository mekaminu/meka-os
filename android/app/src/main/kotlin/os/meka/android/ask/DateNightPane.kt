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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
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
import os.meka.core.domain.DateNightPageRules
import os.meka.core.domain.DateNightRules
import os.meka.core.domain.DateNightView
import os.meka.core.domain.LocalClock
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val DATE_NIGHT_LIST_TAG = "date-night-list"
const val DATE_NIGHT_ROW_SHEET_TAG = "date-night-row-sheet"
const val DATE_NIGHT_EVENING_SHEET_TAG = "date-night-evening-sheet"

/**
 * Ask → Date night in the page shape (every page under Ask in one shape, slice 11; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ, one gold + Add (The evening: the weekday, the start time
 * and which of the next two weeks it starts, as chips in its sheet), three numbers (how soon the next one is, whether
 * it's booked, the time), then Coming up (the next four nights) and The evening (its row opens the same chooser with
 * Turn off). A night's row opens its sheet: the gold Booked (or Not booked yet) and Skip this one / Keep it. The
 * planner and Gym bookings keep each night's evening clear; Needs you asks a week before. Navilife books nothing.
 *
 * Motion: the title travels in from Ask's tile (shared title); the header, summary, tiles and cards appear in turn;
 * cards move with the list's item motion; the ⓘ's words unfold and the sheets rise on the expand spring
 * (LifePageParts); the summary and the line after an action cross-fade; a chip's colour blends as it is chosen (tick
 * haptic); the Starts row and Turn off unfold once it is on (expand spring); Booked gives a light haptic, the rest a
 * tick. Reduced motion: cross-fades.
 */
@Composable
fun DateNightPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.dateNightView.collectAsState()
    val page = remember(v) { core.dateNightPage(v) }
    // What the last tap did ("Skipped Fri 23 Oct · the evening is free to plan").
    var said by remember { mutableStateOf<String?>(null) }
    var infoOpen by remember { mutableStateOf(false) }
    var eveningOpen by remember { mutableStateOf(false) }
    var rowKey by remember { mutableStateOf<String?>(null) }

    fun set(weekday: Int, startMin: Int, firstDay: Long) {
        haptics.tick()
        scope.launch { runCatching { core.setDateNight(weekday, startMin, firstDay) }.getOrNull()?.let { said = null } }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(DATE_NIGHT_LIST_TAG),
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
                    titleModifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.DATE_NIGHT)).appear(rememberAppearance(0)),
                    onInfo = { haptics.tick(); infoOpen = !infoOpen },
                    onAdd = { haptics.tick(); said = null; eveningOpen = true },
                )
            }
            item(key = "summary") {
                Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Crossfade(v.summaryShown, animationSpec = MekaMotion.appear(reduced), label = "date-night-summary") { s ->
                        Text(s, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                    val line = if (eveningOpen || rowKey != null) null else said
                    Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "date-night-said") { l ->
                        l?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
                    }
                }
            }
            item(key = "tiles") { LifeNumberTiles(page.tiles, Modifier.appear(rememberAppearance(2))) }
            page.cards.forEachIndexed { i, c ->
                item(key = "card-${c.key}") {
                    LifePageCard(
                        c, Modifier.animateItem().appear(rememberAppearance(3 + i)),
                        onMore = {},
                        onRow = { r ->
                            haptics.tick()
                            said = null
                            if (r.key == DateNightPageRules.EVENING_KEY) eveningOpen = true else rowKey = r.key
                        },
                    )
                }
            }
            item(key = "note") {
                Text(DateNightRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.l))
            }
        }
        // + Add → The evening (and the evening's row): the weekday, the start time, the first night and Turn off.
        LifeSheet(eveningOpen, DateNightPageRules.EVENING, DATE_NIGHT_EVENING_SHEET_TAG, onClose = { eveningOpen = false }) {
            EveningChooser(v, core.todayEpochDay(), ::set) {
                haptics.tick()
                said = null
                eveningOpen = false
                scope.launch { runCatching { core.dateNightOff() } }
            }
        }
        // A night's sheet: its line, Booked (or Not booked yet) and Skip this one / Keep it.
        val openKey = rowKey
        val night = openKey?.let { DateNightPageRules.night(v, it) }
        LifeSheet(openKey != null, night?.label ?: DateNightPageRules.TITLE, DATE_NIGHT_ROW_SHEET_TAG, onClose = { rowKey = null }) {
            if (night == null) {
                if (openKey != null) Text(DateNightPageRules.GONE, style = MekaType.caption, color = Meka.colors.textSecondary)
            } else {
                Crossfade(night.line, animationSpec = MekaMotion.appear(reduced), label = "night-line") { l ->
                    Text(l, style = MekaType.body, color = if (night.skipped) Meka.colors.textTertiary else Meka.colors.textPrimary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                    if (DateNightPageRules.canBook(night)) {
                        val label = DateNightPageRules.bookLabel(night)
                        val gold = !night.booked
                        Text(
                            label, style = MekaType.itemMeta, color = if (gold) Meka.colors.onAccent else Meka.colors.textSecondary,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                                .clickable(role = Role.Button) {
                                    if (gold) haptics.light() else haptics.tick()
                                    scope.launch { said = runCatching { core.bookDateNight(night.day, !night.booked) }.getOrNull() ?: said }
                                }
                                .semantics { contentDescription = "$label, ${night.label}" }
                                .minTouch()
                                .clip(RoundedCornerShape(MekaRadius.pill))
                                .background(if (gold) Meka.colors.accent else Meka.colors.surfaceRaised)
                                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                        )
                    }
                    val skip = DateNightPageRules.skipLabel(night)
                    Text(
                        skip, style = MekaType.caption, color = if (night.skipped) Meka.colors.accent else Meka.colors.textTertiary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                            .clickable(role = Role.Button) {
                                haptics.tick()
                                scope.launch { said = runCatching { core.skipDateNight(night.day, !night.skipped) }.getOrNull() ?: said }
                            }
                            .semantics { contentDescription = (if (night.skipped) "Keep ${night.label}" else "Skip ${night.label}") }
                            .minTouch().padding(horizontal = MekaSpace.xs),
                    )
                }
                Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "night-said") { l ->
                    l?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
                }
            }
        }
    }
}

/** The evening's chooser in its sheet: a weekday chip, a start time, which of the next two weeks it starts, Turn off. */
@Composable
private fun EveningChooser(v: DateNightView, today: Long, set: (Int, Int, Long) -> Unit, onOff: () -> Unit) {
    // A night that stays one when only the time changes (so the fortnight isn't moved).
    fun anchor(weekday: Int): Long =
        v.nights.firstOrNull()?.day?.takeIf { weekday == v.weekday && v.on } ?: DateNightRules.firstNight(weekday, today)
    Text(DateNightPageRules.WEEKDAY, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
    ChipLine {
        DateNightRules.WEEKDAY_CHOICES.forEachIndexed { i, label ->
            val weekday = i + 1
            Chip(label, v.on && v.weekday == weekday) { set(weekday, v.startMin, anchor(weekday)) }
        }
    }
    Text(DateNightPageRules.START, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
    ChipLine {
        DateNightRules.START_CHOICES.forEach { m ->
            Chip(LocalClock.formatMinute(m), DateNightRules.startLit(v.on, v.startMin, m)) { set(v.weekday, m, anchor(v.weekday)) }
        }
    }
    AnimatedVisibility(v.on && v.starts.isNotEmpty(), enter = unfold(), exit = fold()) {
        Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Text(DateNightPageRules.FIRST, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
            ChipLine { v.starts.forEach { s -> Chip(s.label, s.chosen) { set(v.weekday, v.startMin, s.day) } } }
        }
    }
    Text(DateNightRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
    AnimatedVisibility(v.on, enter = unfold(), exit = fold()) {
        Text(
            DateNightRules.OFF_LABEL, style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onOff).minTouch(),
        )
    }
}

/** A line of chips that wraps onto a second line rather than running off the right edge (walk-through A10). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipLine(chips: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) { chips() }
}
