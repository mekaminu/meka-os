package os.meka.android.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.DayPickerDialog
import os.meka.core.domain.Task
import os.meka.core.domain.TaskWhenRules

/**
 * When (Fold review 2026-10-08, item 8): the row says "Today · 14:30" and unfolds in place (expand spring; reduced
 * motion cross-fades) into Today · Tomorrow · (a picked day) · Pick a date, then the time: "Add a time", or ‹ 14:30 ›
 * stepping a quarter hour with a tick haptic (the digits cross-fade) and "No time". A time plans the task on that
 * day's timeline; a later day takes it out of Today until then. Chips blend their colour with a tick haptic.
 */
@Composable
internal fun WhenSection(task: Task, actions: TodayActions) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    var open by rememberSaveable(task.id) { mutableStateOf(false) }
    var picking by remember(task.id) { mutableStateOf(false) }
    val v = actions.whenOf(task)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
            .clickable(role = Role.Button) { open = !open }.minTouch().padding(vertical = MekaSpace.xs)
            .semantics { contentDescription = "When, ${v.label}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("When", style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Spacer(Modifier.weight(1f))
        FadeText(v.label) { Text(it, style = MekaType.itemMeta, color = Meka.colors.accent) }
    }
    AnimatedVisibility(
        visible = open,
        enter = if (reduced) fadeIn(MekaMotion.expand(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.expand(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                v.chips.forEach { c ->
                    WhenChip(c.label, c.selected) {
                        if (!c.selected) { haptics.tick(); actions.setWhen(task.id, c.day, v.minute) }
                    }
                }
                WhenChip("Pick a date", false) { picking = true }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                val minute = v.minute
                if (minute == null) {
                    WhenChip("Add a time", false) { haptics.tick(); actions.setWhen(task.id, v.day, v.suggestedMinute) }
                } else {
                    StepText("‹", "15 minutes earlier") { haptics.tick(); actions.setWhen(task.id, v.day, TaskWhenRules.step(minute, -1)) }
                    FadeText(TaskWhenRules.timeLabel(minute)) {
                        Text(it, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.padding(horizontal = MekaSpace.xs))
                    }
                    StepText("›", "15 minutes later") { haptics.tick(); actions.setWhen(task.id, v.day, TaskWhenRules.step(minute, 1)) }
                    Spacer(Modifier.padding(start = MekaSpace.xs))
                    WhenChip("No time", false) { haptics.tick(); actions.setWhen(task.id, v.day, null) }
                }
            }
        }
    }
    if (picking) {
        DayPickerDialog(
            initialDay = v.day,
            onPick = { day ->
                val today = v.chips.first().day
                if (day >= today) { haptics.tick(); actions.setWhen(task.id, day, v.minute) }
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * Remind me (Fold review 2026-10-08, item 8): the row says "Off" or when ("Today · 14:15") and unfolds in place like
 * When into the chips still ahead (At 14:30 · 15 min before · 1 h before, or In 1 h · 13:00 · 18:00) and Off. The
 * reminder is a heads-up through the notification governor (quiet hours apply; an exact alarm when allowed) and moves
 * with the task's When. Chips blend their colour with a tick haptic; the label cross-fades.
 */
@Composable
internal fun ReminderSection(task: Task, actions: TodayActions) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    var open by rememberSaveable(task.id) { mutableStateOf(false) }
    val v = actions.reminderOf(task)
    if (v.choices.isEmpty() && !v.isSet) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
            .clickable(role = Role.Button) { open = !open }.minTouch().padding(vertical = MekaSpace.xs)
            .semantics { contentDescription = "Remind me, ${v.label}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Remind me", style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Spacer(Modifier.weight(1f))
        FadeText(v.label) { Text(it, style = MekaType.itemMeta, color = if (v.isSet) Meka.colors.accent else Meka.colors.textSecondary) }
    }
    AnimatedVisibility(
        visible = open,
        enter = if (reduced) fadeIn(MekaMotion.expand(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.expand(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = MekaSpace.xs).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            v.choices.forEach { c ->
                WhenChip(c.label, c.selected) {
                    haptics.tick()
                    actions.setReminder(task.id, if (c.selected) null else c.atMs)
                }
            }
            if (v.isSet) WhenChip("Off", false) { haptics.tick(); actions.setReminder(task.id, null) }
        }
    }
}

@Composable
private fun FadeText(target: String, content: @Composable (String) -> Unit) {
    val reduced = Meka.reducedMotion
    AnimatedContent(target, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) }, label = "when") {
        content(it)
    }
}

@Composable
private fun WhenChip(label: String, lit: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(Meka.reducedMotion), label = "when-chip")
    Text(
        label, style = MekaType.caption, color = if (lit) Meka.colors.onAccent else Meka.colors.textPrimary, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .semantics { selected = lit }
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

@Composable
private fun StepText(symbol: String, description: String, onClick: () -> Unit) {
    Text(
        symbol, style = MekaType.itemTitle, color = Meka.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onClick() }
            .semantics { contentDescription = description }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs),
    )
}

/**
 * Notes: a multi-line field under the rows. Saved a moment after typing stops and when the detail closes; blank
 * clears them. A change from the other device shows once you aren't typing.
 */
@Composable
internal fun NotesSection(task: Task, actions: TodayActions) {
    SectionLabel("Notes", Modifier.padding(top = MekaSpace.l))
    var text by remember(task.id) { mutableStateOf(task.notes.orEmpty()) }
    var dirty by remember(task.id) { mutableStateOf(false) }
    // Synced notes arrive when nothing is waiting to be saved here.
    LaunchedEffect(task.notes) { if (!dirty) text = task.notes.orEmpty() }
    val save: () -> Unit = {
        if (dirty) {
            actions.setNotes(task.id, text)
            dirty = false
        }
    }
    LaunchedEffect(text, dirty) {
        if (dirty) { delay(NOTES_SAVE_MS); save() }
    }
    DisposableEffect(task.id) { onDispose { save() } }
    Box(
        Modifier.fillMaxWidth().padding(vertical = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surfaceRaised).padding(MekaSpace.m),
    ) {
        if (text.isEmpty()) Text("Add notes…", style = MekaType.body, color = Meka.colors.textTertiary)
        BasicTextField(
            value = text,
            onValueChange = { text = it; dirty = true },
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics { contentDescription = "Notes" },
        )
    }
}

private const val NOTES_SAVE_MS = 800L

/**
 * The detail's actions as pills, like Up next's: Done (filled) · Skip (repeating) · Tomorrow · Someday (one-off);
 * Delete is a quiet red line under them, with an undo bar rather than a dialog.
 */
@Composable
internal fun DetailActions(task: Task, actions: TodayActions) {
    val haptics = rememberMekaHaptics()
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            DetailPill("Done", filled = true) { haptics.light(); actions.complete(task.id) }
            if (task.isRepeating) DetailPill("Skip", filled = false) { haptics.tick(); actions.skip(task.id) }
            DetailPill("Tomorrow", filled = false) { haptics.tick(); actions.snooze(task.id) }
            if (!task.isRepeating) DetailPill("Someday", filled = false) { haptics.tick(); actions.someday(task.id) }
        }
        Text(
            "Delete", style = MekaType.itemMeta, color = Meka.colors.critical,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { haptics.tick(); actions.delete(task.id) }
                .padding(vertical = MekaSpace.xs),
        )
    }
}

@Composable
private fun DetailPill(label: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        label, style = MekaType.caption, maxLines = 1,
        color = if (filled) Meka.colors.onAccent else Meka.colors.textPrimary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (filled) Meka.colors.accent else Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onClick() }
            .minTouch().padding(horizontal = MekaSpace.l),
    )
}
