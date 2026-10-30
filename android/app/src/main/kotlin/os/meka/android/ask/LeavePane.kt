package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import os.meka.android.today.SectionLabel
import os.meka.core.domain.LeaveHoliday
import os.meka.core.domain.LeaveRow
import os.meka.core.domain.LeaveRules
import os.meka.core.domain.Recurrence
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val LEAVE_LIST_TAG = "leave-list"

/**
 * Ask → More → Annual leave (annual leave planner, slice 1): the allowance and the days booked against it this leave
 * year, a field for each stretch ("27–30 Oct", "Spain 18 Dec – 4 Jan"), the school holidays coming up with how much of
 * each is booked ("Book 5 days"), and the stretches to come and taken, each with Remove. Booked leave is a day off on
 * both apps (no work band, no commute, "Annual leave · no work" in the brief).
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary, the year line, the line under
 * the field and the steppers' values cross-fade (a refusal lit in the accent); sections and rows stagger in 40 ms apart
 * and arrive, move and leave with the list's item motion; a holiday's nudge is lit in the accent; Add and Book give a
 * light haptic, − / + and Remove a tick. Reduced motion: cross-fades.
 */
@Composable
fun LeavePane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.leaveView.collectAsState()
    // The line under the field after Add or Book (kept while the pane is open), and whether nothing was written.
    var said by remember { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf(false) }
    val saidColor by animateColorAsState(
        if (refused) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "leave-said",
    )
    fun settings(days: Int, month: Int) {
        haptics.tick()
        scope.launch { runCatching { core.setLeaveAllowance(days, month) } }
    }

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
            Text("Annual leave", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.LEAVE)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "leave-summary") { s ->
                    Text(s, style = MekaType.body, color = if (v.daysLeft < 0) Meka.colors.accent else Meka.colors.textSecondary)
                }
                Crossfade(v.yearLine, animationSpec = MekaMotion.appear(reduced), label = "leave-year") { s ->
                    if (s.isNotEmpty()) Text(s, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
            }
        }
        item(key = "field") {
            Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(LeaveRules.ADD_HINT, description = LeaveRules.ADD_DESCRIPTION) { text ->
                    haptics.light()
                    scope.launch {
                        val r = runCatching { core.addLeave(text) }.getOrNull()
                        said = r?.line ?: LeaveRules.NOT_READ
                        refused = r?.refused ?: true
                        if (refused) haptics.tick()
                    }
                }
                Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "leave-added") { line ->
                    line?.let { Text(it, style = MekaType.caption, color = saidColor) }
                }
            }
        }
        if (v.holidays.isNotEmpty()) {
            item(key = "label-holidays") {
                SectionLabel("SCHOOL HOLIDAYS", Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(3)))
            }
            itemsIndexed(v.holidays, key = { _, h -> "holiday-${h.id}" }) { i, h ->
                HolidayRowView(h, Modifier.animateItem().appear(rememberAppearance(4 + i))) {
                    haptics.light()
                    scope.launch {
                        val r = runCatching { core.bookLeaveForHoliday(h.id) }.getOrNull() ?: return@launch
                        said = r.line
                        refused = r.refused
                    }
                }
            }
        }
        val remove: (LeaveRow) -> Unit = { row -> haptics.tick(); scope.launch { runCatching { core.removeLeave(row.id) } } }
        val after = 4 + v.holidays.size
        group("Booked", "booked", v.booked, after, remove)
        group("Taken this leave year", "taken", v.taken, after + v.booked.size, remove)
        item(key = "allowance") {
            Column(
                Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(after + v.booked.size + v.taken.size)),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                SectionLabel("ALLOWANCE")
                Stepper(
                    "Days a year", LeaveRules.daysWord(v.allowance),
                    less = { settings((v.allowance - 1).coerceAtLeast(LeaveRules.MIN_ALLOWANCE), v.yearStartMonth) },
                    more = { settings((v.allowance + 1).coerceAtMost(LeaveRules.MAX_ALLOWANCE), v.yearStartMonth) },
                )
                Stepper(
                    "Leave year starts", "1 ${Recurrence.MONTH_SHORT[v.yearStartMonth - 1]}",
                    less = { settings(v.allowance, (v.yearStartMonth + 10) % 12 + 1) },
                    more = { settings(v.allowance, v.yearStartMonth % 12 + 1) },
                )
            }
        }
        item(key = "note") {
            Text(LeaveRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

private fun LazyListScope.group(label: String, key: String, rows: List<LeaveRow>, firstStep: Int, remove: (LeaveRow) -> Unit) {
    if (rows.isEmpty()) return
    item(key = "label-$key") {
        SectionLabel(label.uppercase(), Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(firstStep)))
    }
    itemsIndexed(rows, key = { _, r -> "leave-${r.id}" }) { i, r ->
        LeaveRowView(r, Modifier.animateItem().appear(rememberAppearance(firstStep + 1 + i))) { remove(r) }
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

@Composable
private fun HolidayRowView(h: LeaveHoliday, modifier: Modifier, onBook: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = h.spoken },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(h.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(h.lineShown, style = MekaType.caption, color = Meka.colors.textSecondary)
            Crossfade(h.noteShown, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "holiday-${h.id}") { n ->
                Text(n, style = MekaType.caption, color = if (h.nudge) Meka.colors.accent else Meka.colors.textTertiary)
            }
        }
        h.bookLabel?.let { label ->
            Text(
                label, style = MekaType.captionStrong, color = Meka.colors.onAccent,
                // Works by hand (quality gate (3)): the pill drawn as before inside a 48 dp touch, as the chips.
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                    .clickable(role = Role.Button, onClick = onBook)
                    .semantics { contentDescription = "$label for ${h.title}" }
                    .minTouch()
                    .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            )
        }
    }
}

@Composable
private fun LeaveRowView(row: LeaveRow, modifier: Modifier, onRemove: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = row.spoken },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(row.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(row.lineShown, style = MekaType.caption, color = Meka.colors.textSecondary)
            row.note?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
        }
        Text(
            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove ${row.title}" }
                // Works by hand (quality gate (3)): a 48 dp touch, the word drawn as before.
                .minTouch().padding(horizontal = MekaSpace.xs),
        )
    }
}
