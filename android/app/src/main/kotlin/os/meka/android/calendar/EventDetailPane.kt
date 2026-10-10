package os.meka.android.calendar

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.platform.LocalContext
import os.meka.android.MekaApplication
import os.meka.core.domain.ReminderRules
import os.meka.core.domain.LeaveAlarmRules
import androidx.compose.foundation.selection.toggleable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.MekaMotion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.delay
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.CalendarEvent
import os.meka.core.facade.MekaCore

/**
 * Event detail (calendar redesign, slice 3): what, when and how soon, which calendar, where (tap to open Maps), a Join
 * button for Meet/Teams/Zoom links, and the event's notes. Opened from an event in Today or the Calendar tab. Shows
 * only: events are a mirror of your calendars and are changed there.
 *
 * The notes come from whoever made the event, so they are untrusted (ADR-006): plain text only, nothing in them is
 * opened unless you tap Join (an https link to a known call service, or the provider's own link).
 *
 * Calendar editing (slice 2c): where the event's account allows editing, **Edit** turns the pane into the event's
 * Edit form ([AddEventPane] with `editing`), whose Save and Delete close the pane and raise [undo]'s bar for five
 * seconds before anything is sent. The event's latest edit is said under the actions ("Moving “Dentist” in Google",
 * a refusal lit in the accent colour); when the server held a delete back because it cancels the event for its
 * guests, **Delete anyway** is Meka's second tap.
 *
 * Motion: the pane springs up from the bottom ([os.meka.android.designsystem.MekaPane]); its sections stagger in 40 ms
 * apart; Join gives a light haptic; Edit cross-fades to the form, whose rows stagger in. Reduced motion: cross-fades only.
 */
@Composable
fun EventDetailPane(core: MekaCore, event: CalendarEvent, onClose: () -> Unit, undo: EventUndo? = null) {
    // "In 25 min" moves on while the pane is open.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(event.id) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    val marks by core.eventMarks.collectAsState()
    val editAccounts by core.calendarEditAccounts.collectAsState()
    val editLines by core.calendarEditLines.collectAsState()
    // Which accounts allow editing (Today doesn't read them otherwise); offline keeps what was known.
    LaunchedEffect(Unit) { if (event.provider in os.meka.core.domain.CalendarEditRules.WRITABLE) runCatching { core.refreshCalendarAccounts() } }
    val d = remember(event, tick, marks, editAccounts, editLines) { core.eventDetail(event) }
    val haptics = rememberMekaHaptics()
    var editing by remember(event.id) { mutableStateOf(false) }
    val reducedFade = Meka.reducedMotion
    AnimatedContent(
        targetState = editing,
        transitionSpec = { fadeIn(MekaMotion.appear(reducedFade)) togetherWith fadeOut(MekaMotion.appear(reducedFade)) },
        label = "event-edit",
    ) { isEditing ->
        if (isEditing) {
            AddEventPane(
                core, -1L,
                onClose = { editing = false },
                onAdded = { id ->
                    editing = false
                    onClose()
                    undo?.show(core.eventEditLine(id) ?: "Changing it in your calendar") { core.undoEventEdit(id) }
                },
                editing = event,
                onDeleted = { id ->
                    editing = false
                    onClose()
                    undo?.show(core.eventEditLine(id) ?: core.deletingLine(event)) { core.undoEventEdit(id) }
                },
            )
        } else {
            DetailContent(core, event, d, tick, marks, onClose, onEdit = { haptics.tick(); editing = true }, undo)
        }
    }
}

@Composable
private fun DetailContent(
    core: MekaCore, event: CalendarEvent, d: os.meka.core.domain.EventDetailView, tick: Int,
    marks: os.meka.core.domain.EventMarks, onClose: () -> Unit, onEdit: () -> Unit, undo: EventUndo?,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val haptics = rememberMekaHaptics()
    BackHandler(onBack = onClose)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.l),
    ) {
        Text(
            "Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s),
        )

        Column(Modifier.padding(top = MekaSpace.s, bottom = MekaSpace.l).appear(rememberAppearance(0))) {
            Text(d.title, style = MekaType.greeting, color = Meka.colors.textPrimary)
            Text(
                listOfNotNull(d.whenLine, d.duration).joinToString(" · "), style = MekaType.itemMeta,
                color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs),
            )
            d.status?.let {
                Text(
                    it, style = MekaType.itemMeta,
                    color = if (d.statusLit) Meka.colors.accent else Meka.colors.textTertiary,
                    modifier = Modifier.padding(top = MekaSpace.xxs),
                )
            }
        }

        // Calendar actions: MEKA-only, the real event is untouched. An event just added in MEKA has none until Google
        // has it (its line below says "Adding “Dentist” to Google").
        if (!d.provisional) Row(
            Modifier.fillMaxWidth().padding(bottom = MekaSpace.l).appear(rememberAppearance(1)),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (d.canPrep) {
                ActionChip("Prep task") {
                    haptics.light()
                    scope.launch { runCatching { core.addPrepTask(event) } }
                }
            }
            // Weekend football: a club fixture's kit list, planned and reminded at 19:00 the evening before.
            if (d.canKit) {
                ActionChip(os.meka.core.domain.FootballRules.CHIP) {
                    haptics.light()
                    scope.launch {
                        val added = runCatching { core.addKitReminder(event) }.getOrNull() ?: return@launch
                        undo?.show(added.line) { core.delete(added.taskId) }
                    }
                }
            }
            // Weekend football, slice 2: a ground Meka has set a travel time for before ("Leave by 09:15 · as last time").
            d.leaveOfferLabel?.let { label ->
                ActionChip(label) {
                    haptics.light()
                    scope.launch {
                        val used = runCatching { core.useLastLeaveBy(event) }.getOrNull() ?: return@launch
                        undo?.show(used.line) { core.undoLastLeaveBy(event.id) }
                    }
                }
            }
            ActionChip(if (d.hidden) "Show in my day" else "Hide from my day") {
                haptics.light()
                scope.launch { runCatching { if (d.hidden) core.showEvent(event.id) else core.hideEvent(event.id) } }
            }
            // Calendar editing: changes the real event (after five seconds' Undo); not while an edit is on its way.
            if (d.editable && undo != null && d.edit?.waiting != true) ActionChip("Edit") { onEdit() }
        }
        val reducedNote = Meka.reducedMotion
        d.edit?.let { note ->
            Column(Modifier.fillMaxWidth().padding(bottom = MekaSpace.l).appear(rememberAppearance(1))) {
                AnimatedContent(
                    targetState = note.text,
                    transitionSpec = { fadeIn(MekaMotion.appear(reducedNote)) togetherWith fadeOut(MekaMotion.appear(reducedNote)) },
                    label = "event-edit-note",
                ) { text ->
                    Text(text, style = MekaType.caption, color = if (note.needsMeka) Meka.colors.accent else Meka.colors.textSecondary)
                }
                if (note.deleteAnyway && d.editable) {
                    Text(
                        "Delete anyway", style = MekaType.itemMeta, color = Meka.colors.critical,
                        modifier = Modifier.padding(top = MekaSpace.xs).clickable(role = Role.Button) {
                            haptics.light()
                            scope.launch {
                                val r = runCatching { core.deleteEvent(event, guestsOk = true) }.getOrNull()
                                if (r is os.meka.core.domain.EventEditResult.Made) {
                                    onClose()
                                    undo?.show(core.eventEditLine(r.id) ?: core.deletingLine(event)) { core.undoEventEdit(r.id) }
                                }
                            }
                        }.padding(vertical = MekaSpace.xs),
                    )
                }
                // Clash chooser (slice 2c-iii): both versions of what the edit touched; Meka chooses, never MEKA.
                androidx.compose.animation.AnimatedVisibility(
                    visible = note.clash != null,
                    enter = if (reducedNote) fadeIn(MekaMotion.appear(true)) else androidx.compose.animation.expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
                    exit = if (reducedNote) fadeOut(MekaMotion.appear(true)) else androidx.compose.animation.shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
                ) {
                    note.clash?.let { c ->
                        ClashChooser(c, canKeepMine = d.editable,
                            onKeepMine = {
                                haptics.light()
                                scope.launch {
                                    val r = runCatching { core.keepMyVersion(c.editId) }.getOrNull()
                                    if (r is os.meka.core.domain.EventEditResult.Made) {
                                        onClose()
                                        undo?.show(core.eventEditLine(r.id) ?: "Sending your version") { core.undoEventEdit(r.id) }
                                    }
                                }
                            },
                            onKeepTheirs = {
                                haptics.tick()
                                scope.launch { runCatching { core.keepTheirVersion(c.editId) } }
                            },
                        )
                    }
                }
            }
        }
        // Remind me / Leave by: a heads-up through the notification governor (quiet hours apply).
        if (d.remindChoices.isNotEmpty() || d.remindMin != 0) {
            ReminderChips("Remind me", d.remindChoices, d.remindMin, { "$it min" }, 1) { m ->
                haptics.tick()
                scope.launch { runCatching { core.setEventReminder(event.id, m) } }
            }
        }
        if (d.travelChoices.isNotEmpty() || d.travelMin != 0) {
            ReminderChips("Leave by · how long to get there", d.travelChoices, d.travelMin, { ReminderRules.travelLabel(it) }, 1) { m ->
                haptics.tick()
                scope.launch { runCatching { core.setEventLeaveBy(event.id, m) } }
            }
        }
        // Alarms, slice 3: once a travel time is set, Leave by can ring like the wake alarm instead of a heads-up.
        val reducedMotion = Meka.reducedMotion
        androidx.compose.animation.AnimatedVisibility(
            visible = d.travelMin != 0,
            enter = if (reducedMotion) fadeIn(MekaMotion.appear(true)) else androidx.compose.animation.expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reducedMotion) fadeOut(MekaMotion.appear(true)) else androidx.compose.animation.shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            LeaveAlarmSwitch(d.leaveAlarm) { on ->
                haptics.tick()
                scope.launch { runCatching { core.setEventLeaveAlarm(event.id, on) } }
            }
        }
        val context = LocalContext.current
        val exact = remember(tick, marks) { (context.applicationContext as? MekaApplication)?.governor?.exactAllowed() ?: true }
        if ((d.remindMin != 0 || d.travelMin != 0) && !exact) {
            Text(
                "Reminders may be up to 5 min late · Allow on time", style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.padding(bottom = MekaSpace.m).clickable(role = Role.Button) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }
        val actionNote = listOfNotNull(d.prepLine, d.kitLine, d.reminderLine, if (d.hidden) "Hidden from your day" else null).joinToString(" · ")
        val reduced = Meka.reducedMotion
        AnimatedContent(
            targetState = actionNote,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "event-actions-note",
        ) { note ->
            if (note.isNotEmpty()) {
                Text(note, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(bottom = MekaSpace.l))
            }
        }

        d.join?.let { j ->
            Text(
                j.label, style = MekaType.itemTitle, color = Meka.colors.onAccent,
                modifier = Modifier.fillMaxWidth().padding(bottom = MekaSpace.l).appear(rememberAppearance(1))
                    .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                    .clickable(role = Role.Button) {
                        haptics.light()
                        runCatching { uriHandler.openUri(j.url) }
                    }
                    .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
            )
        }

        d.location?.let { place ->
            val query = d.mapsQuery
            DetailBlock("Where", 2) {
                Text(place, style = MekaType.body, color = Meka.colors.textPrimary)
                if (query != null) {
                    Text(
                        "Open in Maps", style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button) {
                            runCatching { uriHandler.openUri("geo:0,0?q=" + Uri.encode(query)) }
                        }.padding(vertical = MekaSpace.xs),
                    )
                }
            }
        }

        d.calendarLine?.let { line ->
            DetailBlock("Calendar", 3) {
                Text(line, style = MekaType.body, color = if (d.isFixture) Meka.colors.accent else Meka.colors.textPrimary)
                // The real event, to edit it there: MEKA itself never changes Meka's calendars (read-only in M1).
                d.openIn?.let { o ->
                    Text(
                        o.label, style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button) {
                            haptics.light()
                            runCatching { uriHandler.openUri(o.url) }
                        }.padding(vertical = MekaSpace.xs),
                    )
                }
            }
        }

        d.notes?.let { notes ->
            DetailBlock("Notes", 4) {
                Text(notes, style = MekaType.body, color = Meka.colors.textSecondary)
            }
        }

        Text(
            if (d.editable) "Edit and Delete change the event in your calendar too. Prep tasks, reminders and hiding stay in MEKA."
            else "Change the event itself in your calendar. Prep tasks, reminders and hiding stay in MEKA.", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(5)),
        )
        Spacer(Modifier.height(MekaSpace.xl))
    }
}

/**
 * "Remind me" with a chip per choice still ahead; the one that's set is filled (tap it again to turn it off). Its
 * colour blends across when chosen. A set choice that's no longer offered still shows, so it can be turned off.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReminderChips(label: String, choices: List<Int>, current: Int, text: (Int) -> String, index: Int, onPick: (Int) -> Unit) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth().padding(bottom = MekaSpace.m).appear(rememberAppearance(index))) {
        Text(label.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.padding(bottom = MekaSpace.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.s), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            (if (current != 0 && current !in choices) listOf(current) + choices else choices).forEach { m ->
                val on = m == current
                val bg by androidx.compose.animation.animateColorAsState(
                    if (on) Meka.colors.accent else Meka.colors.background, MekaMotion.themeBlend(reduced), label = "reminder-chip",
                )
                Text(
                    text(m), style = MekaType.itemMeta, color = if (on) Meka.colors.onAccent else Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
                        .border(1.dp, Meka.colors.accent.copy(alpha = 0.6f), RoundedCornerShape(MekaRadius.pill))
                        .clickable(role = Role.Button) { onPick(if (on) 0 else m) }
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                )
            }
        }
    }
}

/**
 * "Ring as an alarm" for Leave by (Alarms, slice 3): an On/Off pill whose colour blends (like the reminder chips), with
 * the line under it cross-fading between what each choice does. The screen reader hears it as a switch.
 */
@Composable
private fun LeaveAlarmSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val reduced = Meka.reducedMotion
    val bg by androidx.compose.animation.animateColorAsState(
        if (on) Meka.colors.accent else Meka.colors.background, MekaMotion.themeBlend(reduced), label = "leave-alarm",
    )
    Column(Modifier.fillMaxWidth().padding(bottom = MekaSpace.m)) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch) { onChange(it) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(LeaveAlarmRules.SWITCH_LABEL, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(
                if (on) "On" else "Off", style = MekaType.itemMeta, color = if (on) Meka.colors.onAccent else Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
                    .border(1.dp, Meka.colors.accent.copy(alpha = 0.6f), RoundedCornerShape(MekaRadius.pill))
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            )
        }
        AnimatedContent(
            targetState = on,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "leave-alarm-line",
        ) { now ->
            Text(
                if (now) "Rings full screen when it's time to go · Snooze or slide to dismiss" else "A heads-up when it's time to go",
                style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs),
            )
        }
    }
}

/** A quiet outlined pill button for the event's actions. */
/**
 * The clash chooser: "It changed in Google after you edited it", then per thing the edit touched its label over Yours
 * and Google's side by side, and Keep mine · Keep Google's (pills, press-in). Unfolds with the expand spring.
 */
@Composable
private fun ClashChooser(
    c: os.meka.core.domain.EventClashView, canKeepMine: Boolean, onKeepMine: () -> Unit, onKeepTheirs: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = MekaSpace.s)
            .clip(RoundedCornerShape(MekaRadius.m))
            .border(1.dp, Meka.colors.hairline, RoundedCornerShape(MekaRadius.m))
            .padding(MekaSpace.m),
    ) {
        Text(c.explain, style = MekaType.caption, color = Meka.colors.textSecondary)
        Row(Modifier.fillMaxWidth().padding(top = MekaSpace.s), horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
            Text(c.mineLabel.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.accent, modifier = Modifier.weight(1f))
            Text(c.theirsLabel.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
        }
        c.rows.forEach { r ->
            Text(r.label, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.s))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                Text(r.mine, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                Text(r.theirs, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.padding(top = MekaSpace.m), horizontalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
            if (canKeepMine) ActionChip(c.keepMineLabel, onKeepMine)
            ActionChip(c.keepTheirsLabel, onKeepTheirs)
        }
    }
}

@Composable
private fun ActionChip(label: String, onTap: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta, color = Meka.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .border(1.dp, Meka.colors.accent.copy(alpha = 0.6f), RoundedCornerShape(MekaRadius.pill))
            .clickable(role = Role.Button) { onTap() }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

@Composable
private fun DetailBlock(label: String, index: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = MekaSpace.l).appear(rememberAppearance(index))) {
        Text(label.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.padding(bottom = MekaSpace.xxs))
        content()
    }
}

/** A tappable event title area used by the timeline and agenda rows. */
internal fun Modifier.opensEvent(event: CalendarEvent?, open: ((CalendarEvent) -> Unit)?): Modifier =
    if (event == null || open == null) this else this.clickable(role = Role.Button) { open(event) }
