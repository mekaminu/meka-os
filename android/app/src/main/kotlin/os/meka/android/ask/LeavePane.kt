package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import os.meka.android.lists.Field
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.LeavePageRules
import os.meka.core.domain.LeaveRules
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val LEAVE_LIST_TAG = "leave-list"
const val LEAVE_ROW_SHEET_TAG = "leave-row-sheet"
const val LEAVE_ALL_SHEET_TAG = "leave-all-sheet"

/**
 * Ask → Annual leave in the page shape (every page under Ask in one shape, slice 9; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ, one gold + Add (A stretch of leave, a field in the
 * sheet: "Spain 18 Dec – 4 Jan"), three numbers (days left, the next leave, school holidays to book), then Booked,
 * School holidays, Taken this leave year and Allowance. A stretch's row opens its sheet with Remove; a holiday's its
 * note and the gold Book n days; Allowance's the days a year and the leave year's start, each with − / +.
 *
 * Motion: the title travels in from Ask's tile (shared title); the header, summary, tiles and cards appear in turn;
 * cards move with the list's item motion; the ⓘ's words unfold and the sheets rise on the expand spring
 * (LifePageParts); the summary, the year line, the answer line and the steppers' values cross-fade (a refusal lit in
 * the accent); a holiday's nudge is lit in the accent; Add and Book give a light haptic, − / + and Remove a tick.
 * Reduced motion: cross-fades.
 */
@Composable
fun LeavePane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.leaveView.collectAsState()
    val page = remember(v) { core.leavePage(v) }
    val today = remember(v) { core.todayEpochDay() }
    // The line after Add or Book (kept while the pane is open), and whether nothing was written.
    var said by remember { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var addChosen by remember { mutableStateOf<String?>(null) }
    var allCard by remember { mutableStateOf<String?>(null) }
    var rowKey by remember { mutableStateOf<String?>(null) }
    val saidColor by animateColorAsState(
        if (refused) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "leave-said",
    )
    fun settings(days: Int, month: Int) {
        haptics.tick()
        scope.launch { runCatching { core.setLeaveAllowance(days, month) } }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(LEAVE_LIST_TAG),
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
                    titleModifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.LEAVE)).appear(rememberAppearance(0)),
                    onInfo = { haptics.tick(); infoOpen = !infoOpen },
                    onAdd = { haptics.tick(); addChosen = LeavePageRules.ADD_CHOICES.singleOrNull()?.key; said = null; addOpen = true },
                )
            }
            item(key = "summary") {
                Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "leave-summary") { s ->
                        Text(s, style = MekaType.caption, color = if (v.daysLeft < 0) Meka.colors.accent else Meka.colors.textSecondary)
                    }
                    Crossfade(v.yearLine, animationSpec = MekaMotion.appear(reduced), label = "leave-year") { s ->
                        if (s.isNotEmpty()) Text(s, style = MekaType.caption, color = Meka.colors.textTertiary)
                    }
                    // What the last add or Book said, while no sheet is open.
                    val line = if (addOpen || rowKey != null) null else said
                    Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "leave-line") { l ->
                        l?.let { Text(it, style = MekaType.caption, color = saidColor) }
                    }
                }
            }
            item(key = "tiles") { LifeNumberTiles(page.tiles, Modifier.appear(rememberAppearance(2))) }
            page.cards.forEachIndexed { i, c ->
                item(key = "card-${c.key}") {
                    LifePageCard(
                        c, Modifier.animateItem().appear(rememberAppearance(3 + i)),
                        onMore = { haptics.tick(); allCard = c.key },
                        onRow = { r -> haptics.tick(); said = null; rowKey = r.key },
                    )
                }
            }
            item(key = "note") {
                Text(LeaveRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.l))
            }
        }
        // + Add: a stretch of leave, typed once.
        LifeSheet(addOpen, page.addTitle, LIFE_ADD_SHEET_TAG, onClose = { addOpen = false }) {
            AddChoiceRows(page.addChoices, addChosen) { c -> haptics.tick(); said = null; addChosen = c.key }
            addChosen?.let { choice ->
                Field(LeavePageRules.hint(choice), description = LeaveRules.ADD_DESCRIPTION) { typed ->
                    haptics.light()
                    scope.launch {
                        val r = runCatching { core.addLeave(typed) }.getOrNull()
                        said = r?.line ?: LeaveRules.NOT_READ
                        refused = r?.refused ?: true
                        if (refused) haptics.tick() else addOpen = false
                    }
                }
                if (refused) said?.let { Text(it, style = MekaType.caption, color = saidColor) }
            }
        }
        // See all: every row of one card, a tap opens its sheet.
        val all = allCard
        LifeSheet(all != null, all ?: LeavePageRules.TITLE, LEAVE_ALL_SHEET_TAG, onClose = { allCard = null }) {
            if (all != null) LeavePageRules.allRows(v, all, today).forEach { r ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { haptics.tick(); allCard = null; rowKey = r.key }
                        .semantics(mergeDescendants = true) {}
                        .minTouch().padding(vertical = MekaSpace.xs),
                ) {
                    Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(listOf(r.line, r.whenText).filter { it.isNotEmpty() }.joinToString(" · "), style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
        }
        // A row's sheet: a stretch with Remove, a school holiday with Book, or the allowance.
        val openKey = rowKey
        val stretch = openKey?.let { LeavePageRules.leave(v, it) }
        val holiday = openKey?.let { LeavePageRules.holiday(v, it) }
        val isAllowance = openKey == LeavePageRules.KEY_ALLOWANCE
        val sheetTitle = stretch?.title ?: holiday?.title ?: if (isAllowance) LeavePageRules.ALLOWANCE else LeavePageRules.TITLE
        LifeSheet(openKey != null, sheetTitle, LEAVE_ROW_SHEET_TAG, onClose = { rowKey = null }) {
            when {
                stretch != null -> {
                    Text(stretch.lineShown, style = MekaType.body, color = Meka.colors.textPrimary)
                    stretch.note?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
                    Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs)) {
                        Text(
                            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) {
                                    haptics.tick()
                                    rowKey = null
                                    scope.launch { runCatching { core.removeLeave(stretch.id) } }
                                }
                                .semantics { contentDescription = "Remove ${stretch.title}" }
                                .minTouch()
                                .padding(MekaSpace.xs),
                        )
                    }
                }
                holiday != null -> {
                    Text(holiday.lineShown, style = MekaType.body, color = Meka.colors.textPrimary)
                    Crossfade(holiday.noteShown, animationSpec = MekaMotion.appear(reduced), label = "holiday-${holiday.id}") { n ->
                        Text(n, style = MekaType.caption, color = if (holiday.nudge) Meka.colors.accent else Meka.colors.textTertiary)
                    }
                    holiday.bookLabel?.let { label ->
                        Text(
                            label, style = MekaType.itemMeta, color = Meka.colors.onAccent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                                .clickable(role = Role.Button) {
                                    haptics.light()
                                    scope.launch {
                                        val r = runCatching { core.bookLeaveForHoliday(holiday.id) }.getOrNull() ?: return@launch
                                        said = r.line
                                        refused = r.refused
                                    }
                                }
                                .semantics { contentDescription = "$label for ${holiday.title}" }
                                .minTouch()
                                .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                        )
                    }
                    Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "holiday-said") { l ->
                        l?.let { Text(it, style = MekaType.caption, color = saidColor) }
                    }
                }
                isAllowance -> {
                    Stepper(
                        LeavePageRules.DAYS_A_YEAR, LeaveRules.daysWord(v.allowance),
                        less = { settings((v.allowance - 1).coerceAtLeast(LeaveRules.MIN_ALLOWANCE), v.yearStartMonth) },
                        more = { settings((v.allowance + 1).coerceAtMost(LeaveRules.MAX_ALLOWANCE), v.yearStartMonth) },
                    )
                    Stepper(
                        LeavePageRules.YEAR_STARTS, LeavePageRules.yearStartLabel(v.yearStartMonth),
                        less = { settings(v.allowance, LeavePageRules.monthBefore(v.yearStartMonth)) },
                        more = { settings(v.allowance, LeavePageRules.monthAfter(v.yearStartMonth)) },
                    )
                    Text(v.allowanceLine, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                openKey != null -> Text(LeavePageRules.GONE, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, less: () -> Unit, more: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
        Text("−", style = MekaType.itemTitle, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button, onClick = less)
                .semantics { contentDescription = "$label: fewer" }.minTouch().padding(horizontal = MekaSpace.m))
        Crossfade(value, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "leave-$label") { t ->
            Text(t, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        }
        Text("+", style = MekaType.itemTitle, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button, onClick = more)
                .semantics { contentDescription = "$label: more" }.minTouch().padding(horizontal = MekaSpace.m))
    }
}
