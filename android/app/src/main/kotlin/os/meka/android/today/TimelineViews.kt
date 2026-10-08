package os.meka.android.today

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import os.meka.android.calendar.EventActionHandlers
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.AllDayItem
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.TimelineRow
import os.meka.core.domain.UpNextEvent

/**
 * Today's timeline (calendar redesign, slice 1): events and planned tasks in one list with a now line and free gaps.
 * Events are context, not things to tick, so their titles use the regular body weight (build plan, type weight).
 * Motion: rows glide as the day moves on (animateItem); the now line's dot breathes; "3 earlier" unfolds in place.
 * Reduced motion: cross-fades only and a steady dot.
 */
internal val TIME_COLUMN = 92.dp

@Composable
internal fun TimeColumn(time: String, past: Boolean) {
    Text(
        time, style = MekaType.itemMeta,
        color = if (past) Meka.colors.textTertiary else Meka.colors.textSecondary,
        modifier = Modifier.width(TIME_COLUMN),
    )
}

/** An event: time on the left, title and where/which calendar under it. A running one is marked "Now". */
@Composable
internal fun TimelineEventRow(r: TimelineRow, past: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs), verticalAlignment = Alignment.Top) {
        TimeColumn(r.time, past)
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MekaType.body, color = if (past) Meka.colors.textTertiary else Meka.colors.textPrimary)
            val line = listOfNotNull(if (r.running) "Now" else null, r.detail).joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(line, style = MekaType.caption, color = if (r.running) Meka.colors.accent else Meka.colors.textTertiary)
            }
        }
    }
}

/**
 * A booked session (the Gym): time on the left, "Gym · Push" in the body weight like a task row (Fold review
 * 2026-10-08: only Up next's title is bold), "Leave by 17:30" under it, or "Now · until 18:45" in the accent colour while it's on. Today's session card above
 * answers it, so the row has no taps of its own.
 */
@Composable
internal fun SessionTimelineRow(r: TimelineRow, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = MekaSpace.xs)
            .semantics(mergeDescendants = true) { contentDescription = "${r.title}, ${r.time}, ${r.detail.orEmpty()}" },
        verticalAlignment = Alignment.Top,
    ) {
        TimeColumn(r.time, past = false)
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MekaType.body, color = Meka.colors.textPrimary)
            r.detail?.let { Text(it, style = MekaType.caption, color = if (r.running) Meka.colors.accent else Meka.colors.textTertiary) }
        }
    }
}

/**
 * Work hours (Fold review 2026-10-08): a quiet band, not an event. Time on the left, then a hairline-bordered band with
 * a thin bar and "Work"; while at work the bar is lit and "Now · until 17:30" sits beside it in the accent colour.
 * No taps. Motion: it glides with the other rows and leaves once work is over (animateItem); reduced motion cross-fades.
 */
@Composable
internal fun WorkTimelineRow(r: TimelineRow, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(r.title, r.time, r.detail).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeColumn(r.time, past = true)
        WorkBand(r.title, r.detail, r.running, Modifier.weight(1f))
    }
}

/** The work band itself; the Calendar tab uses it for "Work 09:00–17:30" on each work day. */
@Composable
internal fun WorkBand(title: String, detail: String?, running: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(MekaRadius.s)).border(1.dp, Meka.colors.hairline, RoundedCornerShape(MekaRadius.s))
            .padding(horizontal = MekaSpace.s, vertical = MekaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(2.dp).height(14.dp).clip(RoundedCornerShape(1.dp)).background(if (running) Meka.colors.accent else Meka.colors.textTertiary))
        Spacer(Modifier.width(MekaSpace.s))
        Text(title, style = MekaType.caption, color = Meka.colors.textSecondary)
        detail?.let {
            Text(" · $it", style = MekaType.caption, color = if (running) Meka.colors.accent else Meka.colors.textTertiary)
        }
    }
}

/** A free stretch: "1 h 30 free", quiet. */
@Composable
internal fun GapRow(r: TimelineRow, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
        TimeColumn(r.time, past = true)
        Text(r.title, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/**
 * The now line: a breathing accent dot, the time, and a hairline across. When the free stretch starts now, its words sit
 * on the line ("07:57 ● 1 h free until Work", Fold review 2026-10-08) and cross-fade as they change.
 */
@Composable
internal fun NowLine(r: TimelineRow, modifier: Modifier = Modifier) {
    val reduced = Meka.reducedMotion
    val dotAlpha = if (reduced) 1f else {
        val t = rememberInfiniteTransition(label = "now-dot")
        val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "now-dot-a")
        a
    }
    Row(
        modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs)
            .semantics { contentDescription = listOfNotNull("Now, ${r.time}", r.detail).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(r.time, style = MekaType.caption, color = Meka.colors.accent, modifier = Modifier.width(TIME_COLUMN))
        Box(Modifier.size(8.dp).alpha(dotAlpha).clip(CircleShape).background(Meka.colors.accent))
        r.detail?.let { free ->
            Crossfade(free, Modifier.weight(1f, fill = false), animationSpec = MekaMotion.appear(reduced), label = "now-free") { line ->
                Text(
                    line, style = MekaType.caption, color = Meka.colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = MekaSpace.xs),
                )
            }
        }
        Box(Modifier.weight(1f).height(1.dp).background(Meka.colors.accent.copy(alpha = 0.5f)))
    }
}

/** All-day events as chips above the timeline; a chip opens the event's detail when [onEvent] is given. */
@Composable
internal fun AllDayChips(events: List<CalendarEvent>, modifier: Modifier = Modifier, onEvent: ((CalendarEvent) -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = MekaSpace.xs),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        events.forEach { e ->
            Text(
                e.title, style = MekaType.caption, color = Meka.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .then(if (onEvent != null) Modifier.clickable(role = Role.Button) { onEvent(e) } else Modifier)
                    .padding(horizontal = MekaSpace.s, vertical = MekaSpace.xxs),
            )
        }
    }
}

/**
 * The "All day" group's label, once above its rows (all-day polish, Meka 2026-10-07 22:37): "All day", or
 * "All day · Timestripe" when every entry shares a calendar.
 */
@Composable
internal fun AllDayLabel(label: String, modifier: Modifier = Modifier) {
    Text(label, style = MekaType.itemMeta, color = Meka.colors.textTertiary, modifier = modifier.padding(top = MekaSpace.xxs))
}

/**
 * One row of Today's "All day" group: the title in the lighter event style, aligned under the group's label in the
 * timeline's title column, with its calendar under it only when calendars are mixed. Tapping opens the event's detail;
 * long-pressing (tick haptic) opens Make it a task · Hide <calendar> from Today · Details. An entry that reads like a
 * to-do also shows a small, quiet "Make it a task" (light haptic; the undo bar rises).
 */
@Composable
internal fun AllDayRow(
    item: AllDayItem,
    modifier: Modifier = Modifier,
    onEvent: ((CalendarEvent) -> Unit)? = null,
    handlers: EventActionHandlers? = null,
) {
    val haptics = rememberMekaHaptics()
    var menu by remember(item.event.id) { mutableStateOf(false) }
    val hideLabel = "Hide ${item.calendarLabel} from Today"
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                .combinedClickable(
                    role = Role.Button,
                    onClick = { onEvent?.invoke(item.event) },
                    onLongClickLabel = "All-day actions",
                    onLongClick = if (handlers != null) ({ haptics.tick(); menu = true }) else null,
                )
                .semantics {
                    if (handlers != null) customActions = listOf(
                        CustomAccessibilityAction("Make it a task") { handlers.makeTask(item.event); true },
                        CustomAccessibilityAction(hideLabel) { handlers.hideCalendar(item.calendarKey, item.calendarLabel); true },
                    )
                }
                .padding(vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(TIME_COLUMN))
            Column(Modifier.weight(1f)) {
                Text(item.event.title, style = MekaType.body, color = Meka.colors.textPrimary)
                item.line?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
            }
            if (item.todo && handlers != null) {
                // Quiet: no fill, a hairline outline and secondary text, so it doesn't compete with the titles.
                Text(
                    "Make it a task", style = MekaType.caption, color = Meka.colors.textSecondary,
                    modifier = Modifier.padding(start = MekaSpace.s).clip(RoundedCornerShape(MekaRadius.pill))
                        .border(1.dp, Meka.colors.hairline, RoundedCornerShape(MekaRadius.pill))
                        .clickable(role = Role.Button) { haptics.light(); handlers.makeTask(item.event) }
                        .padding(horizontal = MekaSpace.xs, vertical = 2.dp),
                )
            }
        }
        if (handlers != null) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                @Composable
                fun entry(label: String, onClick: () -> Unit) = DropdownMenuItem(
                    text = { Text(label, style = MekaType.itemMeta, color = Meka.colors.textPrimary) },
                    onClick = { menu = false; onClick() },
                )
                entry("Make it a task") { haptics.light(); handlers.makeTask(item.event) }
                entry(hideLabel) { haptics.light(); handlers.hideCalendar(item.calendarKey, item.calendarLabel) }
                onEvent?.let { open -> entry("Details") { open(item.event) } }
            }
        }
    }
}

/** "+2 more" under the all-day rows: unfolds the rest (they spring in with the list's item motion). */
@Composable
internal fun AllDayMore(label: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onOpen() }.padding(vertical = MekaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(TIME_COLUMN))
        Text(label, style = MekaType.caption, color = Meka.colors.accent)
    }
}

/** "3 earlier ›": unfolds the events that have finished. */
@Composable
internal fun EarlierToggle(label: String, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val turn by animateFloatAsState(if (open) 90f else 0f, MekaMotion.appear(Meka.reducedMotion), label = "earlier-turn")
    Row(
        modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onToggle() }.padding(vertical = MekaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(TIME_COLUMN))
        Text(label, style = MekaType.caption, color = Meka.colors.textTertiary)
        Spacer(Modifier.width(MekaSpace.xxs))
        Text("›", style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.rotate(turn))
    }
}

/** Up next's event line: "Call with Tunde in 25 min" with its time and place. */
@Composable
internal fun NextEventCard(e: UpNextEvent, modifier: Modifier = Modifier, onEvent: ((CalendarEvent) -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised)
            .then(if (onEvent != null) Modifier.clickable(role = Role.Button) { onEvent(e.event) } else Modifier)
            .padding(MekaSpace.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Meka.colors.accent))
        Spacer(Modifier.width(MekaSpace.m))
        Column(Modifier.weight(1f)) {
            Text(e.line, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(e.detail, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
    }
}
