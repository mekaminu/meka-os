package os.meka.android.today

import os.meka.core.domain.CommuteRules
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import os.meka.android.calendar.EventActionHandlers
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.mekaMenuBorder
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.AllDayItem
import os.meka.core.domain.CardEdge
import os.meka.core.domain.ScheduleCardRules
import os.meka.core.domain.ScheduleEdgeInk
import os.meka.core.domain.TimelineKind
import os.meka.android.designsystem.calendarTone
import os.meka.core.domain.CalendarTones
import os.meka.core.domain.TodayInkRules
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.TimelineRow
import os.meka.core.domain.UpNextEvent

/**
 * Today's timeline (calendar redesign, slice 1): events and planned tasks in one list with a now line and free gaps.
 * Events are context, not things to tick, so their titles use the regular `eventTitle` (16 sp; Calmer Today, slice 3),
 * with the time in `caption` in the left column.
 * Motion: rows glide as the day moves on (animateItem); the now line's dot breathes; "3 earlier" unfolds in place.
 * Reduced motion: cross-fades only and a steady dot.
 */
internal val TIME_COLUMN = 92.dp

/**
 * The Today rebuild, point (e): inside Today's Schedule card the rows take the card's narrow time column and show a
 * range's start only (core [ScheduleCardRules]); everywhere else (the Calendar's agenda) they keep [TIME_COLUMN].
 */
internal val LocalScheduleCard = staticCompositionLocalOf { false }

internal val timeColumnWidth: Dp
    @Composable get() = if (LocalScheduleCard.current) ScheduleCardRules.TIME_DP.dp else TIME_COLUMN

@Composable
internal fun TimeColumn(time: String, past: Boolean) {
    val card = LocalScheduleCard.current
    Text(
        if (card) ScheduleCardRules.time(time) else time, style = MekaType.caption,
        color = if (past) Meka.colors.textTertiary else Meka.colors.textSecondary,
        maxLines = if (card) 1 else Int.MAX_VALUE,
        modifier = Modifier.width(timeColumnWidth),
    )
}

/**
 * One row of Today's Schedule card (the mockup's one compact card): the surface fill, the corners rounded only on the
 * card's first and last rows, a hairline under every row but the last, and the card's 14 dp inset. The rows inside keep
 * their own motion (they glide with animateItem, so the card grows and shrinks as rows come and go).
 */
@Composable
internal fun Modifier.scheduleSegment(edge: CardEdge): Modifier {
    val r = MekaRadius.m
    val shape = RoundedCornerShape(
        topStart = if (edge.roundsTop) r else 0.dp, topEnd = if (edge.roundsTop) r else 0.dp,
        bottomStart = if (edge.roundsBottom) r else 0.dp, bottomEnd = if (edge.roundsBottom) r else 0.dp,
    )
    val hair = Meka.colors.hairline
    val surface = Meka.colors.surface
    val inset = SCHEDULE_INSET
    val gap = SCHEDULE_ROW_GAP
    // The re-shoot beside TodayNew (2026-11-11): each row is its own item in Today's list, which spaces its items 8 dp
    // apart, so the screen's black showed between the rows as stripes and the card read as a stack of black-edged
    // boxes. A row that isn't the card's last fills the gap under it with the card's surface (drawn before the clip,
    // so it reaches past the row) and draws the hairline in the gap's middle: one card, rows parted by a hairline.
    return this.fillMaxWidth()
        .drawBehind {
            if (!edge.roundsBottom) {
                val g = gap.toPx()
                drawRect(surface, Offset(0f, size.height - 1f), Size(size.width, g + 2f))
                val x = inset.toPx()
                val y = size.height + g / 2f
                drawLine(hair, Offset(x, y), Offset(size.width - x, y), strokeWidth = 1f)
            }
        }
        .clip(shape).background(surface)
        .semantics { scheduleSegment = true }
        .padding(horizontal = inset)
        .padding(top = if (edge.roundsTop) MekaSpace.xxs else 0.dp, bottom = if (edge.roundsBottom) MekaSpace.xxs else 0.dp)
}

/** The space Today's lists put between items ([Arrangement.spacedBy] in TodayRoute); the card's rows bridge it. */
internal val SCHEDULE_ROW_GAP = MekaSpace.xs

/**
 * Marks each row of Today's Schedule card (the screen tests check the card is one surface between them). A semantics
 * key of its own, not a test tag: the rows inside carry their own tags (a test tag set outside would hide them).
 */
internal val ScheduleSegmentKey = androidx.compose.ui.semantics.SemanticsPropertyKey<Boolean>("ScheduleSegment")
internal var androidx.compose.ui.semantics.SemanticsPropertyReceiver.scheduleSegment by ScheduleSegmentKey

/** The Schedule card's side inset (the mockup's 14 px). */
internal val SCHEDULE_INSET = 14.dp

/** The Schedule card's 2 dp edge colours (core [ScheduleCardRules.edgeInk]). */
@Composable
internal fun ScheduleEdgeInk.color(): Color = when (this) {
    ScheduleEdgeInk.CALENDAR -> Meka.colors.calendarTone(1)
    ScheduleEdgeInk.FIXTURE -> Meka.colors.calendarTone(CalendarTones.FIXTURE)
    ScheduleEdgeInk.TASK -> Meka.colors.calendarTone(2)
    ScheduleEdgeInk.QUIET -> Meka.colors.hairline
    ScheduleEdgeInk.NOW -> Meka.colors.accent
}

/**
 * Today regression 3: the one mark every Schedule card row wears, a 2 dp coloured edge between the time and the title,
 * as tall as the row's words (the row is measured with IntrinsicSize.Min). Outside the card nothing is drawn.
 */
@Composable
internal fun ScheduleEdge(ink: ScheduleEdgeInk?, modifier: Modifier = Modifier) {
    if (!LocalScheduleCard.current || ink == null) return
    val c = ink.color()
    Box(modifier.width(ScheduleCardRules.EDGE_DP.dp).fillMaxHeight().padding(vertical = MekaSpace.xxs).background(c))
    Spacer(Modifier.width(MekaSpace.xs))
}

/**
 * Work and the commute inside the Schedule card (Today regression 3, Meka 2026-11-11: "don't like the oval outline"):
 * one dim line on the card's own surface: time, the quiet edge (gold while on), "Work · Now · until 17:30". No band,
 * no outline. No taps.
 */
@Composable
internal fun ScheduleBackgroundRow(r: TimelineRow, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = MekaSpace.xs).testTag(SCHEDULE_BACKGROUND_TAG)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(r.title, r.time, r.detail).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeColumn(r.time, past = true)
        ScheduleEdge(ScheduleCardRules.edgeInk(r.kind, r.running))
        val detail = r.detail?.let { if (r.kind == TimelineKind.COMMUTE) CommuteRules.shown(it) else it }
        Text(
            ScheduleCardRules.backgroundLine(r.title, detail), style = MekaType.caption,
            color = if (r.running) Meka.colors.textSecondary else Meka.colors.textTertiary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}

/** Every work or commute line in the Schedule card, for the screen tests. */
internal const val SCHEDULE_BACKGROUND_TAG = "schedule-background-row"

/** An event: time on the left, title and where/which calendar under it. A running one is marked "Now". */
@Composable
internal fun TimelineEventRow(r: TimelineRow, past: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = MekaSpace.xs), verticalAlignment = Alignment.Top) {
        TimeColumn(r.time, past)
        ScheduleEdge(ScheduleCardRules.edgeInk(TimelineKind.EVENT, r.running, fixture = r.event?.isFixture == true))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MekaType.eventTitle, color = if (past) Meka.colors.textTertiary else Meka.colors.textPrimary)
            // In Today's Schedule card the time column shows the start only, so the end leads the line ("until 10:00").
            val until = if (LocalScheduleCard.current) ScheduleCardRules.until(r.time) else null
            val line = listOfNotNull(if (r.running) "Now" else null, until, r.detail).joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(line, style = MekaType.caption, color = TodayInkRules.detail(r.running).color())
            }
            // During work (Fold review 2026-10-12, bug 4): "Clashes with work · who's taking Theo?" in amber (gold is for taps).
            r.clash?.let { Text(it, style = MekaType.caption, color = TodayInkRules.clash(past).color()) }
        }
    }
}

/**
 * A booked session (the Gym): time on the left, "Gym · Push" in the body weight like a task row (Fold review
 * 2026-10-08: only Up next's title is bold), "Leave by 17:30" under it, or "Now · until 18:45" a step brighter while it's on (gold is for taps). Today's session card above
 * answers it, so the row has no taps of its own.
 */
@Composable
internal fun SessionTimelineRow(r: TimelineRow, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = MekaSpace.xs)
            .semantics(mergeDescendants = true) { contentDescription = "${r.title}, ${r.time}, ${r.detail.orEmpty()}" },
        verticalAlignment = Alignment.Top,
    ) {
        TimeColumn(r.time, past = false)
        ScheduleEdge(ScheduleCardRules.edgeInk(TimelineKind.SESSION, r.running))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MekaType.eventTitle, color = Meka.colors.textPrimary)
            r.detail?.let { Text(it, style = MekaType.caption, color = TodayInkRules.detail(r.running).color()) }
        }
    }
}

/**
 * Work hours (Fold review 2026-10-08): a quiet band, not an event. Time on the left, then a hairline-bordered band with
 * a thin bar and "Work"; while at work the bar is lit and "Now · until 17:30" sits beside it a step brighter (gold is for taps).
 * No taps. Motion: it glides with the other rows and leaves once work is over (animateItem); reduced motion cross-fades.
 */
@Composable
internal fun WorkTimelineRow(r: TimelineRow, modifier: Modifier = Modifier) {
    if (LocalScheduleCard.current) return ScheduleBackgroundRow(r, modifier)
    Row(
        modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(r.title, r.time, r.detail).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeColumn(r.time, past = true)
        WorkBand(r.title, r.detail, r.running, Modifier.weight(1f))
    }
}

/** The work band itself (Today's timeline; the Calendar tab says work on each day's heading instead). */
@Composable
internal fun WorkBand(title: String, detail: String?, running: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(MekaRadius.s)).border(1.dp, Meka.colors.hairline, RoundedCornerShape(MekaRadius.s))
            .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(2.dp).height(14.dp).clip(RoundedCornerShape(1.dp)).background(TodayInkRules.bar(running).color()))
        Spacer(Modifier.width(MekaSpace.xs))
        Text(title, style = MekaType.caption, color = Meka.colors.textSecondary)
        detail?.let {
            Text(" · $it", style = MekaType.caption, color = TodayInkRules.detail(running).color())
        }
    }
}

/**
 * The commute on an office day (the commute counts everywhere, slice 2): thinner than the work band — no box, just a
 * short bar and "Commute" / "Commute home · Home about 18:50" in the caption style; while on the way the bar is lit and
 * "Now · home about 18:50" sits a step brighter (gold is for taps). Not an event: no taps. Motion as the work row (it glides with the
 * other rows and leaves once over; reduced motion cross-fades).
 */
@Composable
internal fun CommuteTimelineRow(r: TimelineRow, modifier: Modifier = Modifier) {
    if (LocalScheduleCard.current) return ScheduleBackgroundRow(r, modifier)
    Row(
        modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(r.title, r.time, r.detail).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeColumn(r.time, past = true)
        Spacer(Modifier.width(MekaSpace.xs))
        Box(Modifier.width(2.dp).height(10.dp).clip(RoundedCornerShape(1.dp)).background(if (r.running) Meka.colors.accent else Meka.colors.hairline))
        Spacer(Modifier.width(MekaSpace.xs))
        // One text, so "Commute home · Home about 18:50" wraps onto a second line on the cover screen instead of its
        // second half being cut to " · Home about…" (the quality gate's Today shots, 2026-10-22).
        val detailColor = TodayInkRules.detail(r.running).color()
        Text(
            buildAnnotatedString {
                append(r.title)
                // "about" kept with its time, so a wrap never leaves it alone at a line's end (re-shot, 2026-10-22).
                r.detail?.let { withStyle(SpanStyle(color = detailColor)) { append(" · ${CommuteRules.shown(it)}") } }
            },
            style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f),
        )
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
        Text(r.time, style = MekaType.caption, color = Meka.colors.accent, maxLines = 1, modifier = Modifier.width(timeColumnWidth))
        Box(Modifier.size(8.dp).alpha(dotAlpha).clip(CircleShape).background(Meka.colors.accent))
        r.detail?.let { free ->
            // The words come first and the hairline takes what is left (the quality gate's Today shots, 2026-10-22: sharing
            // the width by weight cut "5 anytime tasks" to half the row on the cover screen); a long line wraps to two.
            Crossfade(free, animationSpec = MekaMotion.appear(reduced), label = "now-free") { line ->
                Text(
                    line, style = MekaType.caption, color = TodayInkRules.nowWords.color(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = MekaSpace.xs),
                )
            }
        }
        // The hairline takes what is left, but a sliver isn't drawn (walk-through 2026-10-30, A3): after a long line
        // ("Nothing else planned after 18:50") the few dp left read as a stray dash.
        val hair = Meka.colors.accent.copy(alpha = 0.5f)
        Box(Modifier.weight(1f).height(1.dp).drawBehind { if (size.width >= NOW_HAIRLINE_MIN.toPx()) drawRect(hair) })
    }
}

/** The shortest hairline the now line draws; less is left blank. */
internal val NOW_HAIRLINE_MIN = 24.dp

/**
 * The "All day" group's label, once above its rows (all-day polish, Meka 2026-10-07 22:37): "All day", or
 * "All day · Timestripe" when every entry shares a calendar.
 */
@Composable
internal fun AllDayLabel(label: String, modifier: Modifier = Modifier) {
    Text(label, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = modifier.padding(top = MekaSpace.xxs))
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
    /** In Today's Schedule card: "All day" in the time column of the first row (the label line goes); null leaves it blank. */
    timeLabel: String? = null,
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
            if (timeLabel != null) TimeColumn(timeLabel, past = false) else Spacer(Modifier.width(timeColumnWidth))
            Column(Modifier.weight(1f)) {
                Text(item.event.title, style = MekaType.eventTitle, color = Meka.colors.textPrimary)
                item.line?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
            }
            if (item.todo && handlers != null) {
                // Quiet: no fill, a hairline outline and secondary text, so it doesn't compete with the titles. In
                // Today's Schedule card no outline (Meka 2026-11-11: "don't like the oval outline"): the words alone.
                val outlined = !LocalScheduleCard.current
                Text(
                    "Make it a task", style = MekaType.caption, color = Meka.colors.textSecondary,
                    modifier = Modifier.padding(start = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill))
                        .then(if (outlined) Modifier.border(1.dp, Meka.colors.hairline, RoundedCornerShape(MekaRadius.pill)) else Modifier)
                        .clickable(role = Role.Button) { haptics.light(); handlers.makeTask(item.event) }
                        .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
                )
            }
        }
        if (handlers != null) {
            DropdownMenu(
                expanded = menu, onDismissRequest = { menu = false },
                containerColor = Meka.colors.surfaceRaised, border = mekaMenuBorder(Meka.colors),
            ) {
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
internal fun AllDayMore(label: String, onOpen: () -> Unit, modifier: Modifier = Modifier, touch: Boolean = false) {
    // [touch]: the Calendar's agenda gives the line a 48 dp touch (works by hand); Today keeps its compact line.
    Row(
        modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onOpen() }
            .then(if (touch) Modifier.minTouch() else Modifier.padding(vertical = MekaSpace.xxs)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(timeColumnWidth))
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
        Spacer(Modifier.width(timeColumnWidth))
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
            Text(e.line, style = MekaType.eventTitle, color = Meka.colors.textPrimary)
            Text(e.detail, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
    }
}
