package os.meka.android.calendar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.calendarTone
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.BoardFoldRules
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.CalendarMode
import os.meka.core.domain.CalendarWeekRules
import os.meka.core.domain.WeekBlock
import os.meka.core.domain.WeekChip
import os.meka.core.domain.WeekChipKind
import os.meka.core.domain.WeekDrop
import os.meka.core.domain.WeekGrid
import os.meka.core.domain.WeekGridDay
import os.meka.core.facade.MekaCore

internal const val CALENDAR_WEEK_TAG = "calendar-week"
internal const val WEEK_BLOCK_TAG = "week-block"
internal const val WEEK_CHIP_TAG = "week-chip"
internal const val WEEK_GRID_TAG = "week-grid"

/**
 * Calendar → **Week**, Option A (approved by Meka 2026-11-10 07:38, square corners; `docs/design/calendar-week/`): a
 * calm time grid, Calendar's first view, beside Board and Agenda (core [CalendarWeekRules]).
 *
 * Open Fold: "November 2026 · Week 46", the Week · Board · Agenda switch, 5 days · 7 days (5 by default), Today and ‹ ›;
 * the calendars' key and "Booked this week 5.8 h"; then the grid in one rounded panel: the days' heads (today's date in
 * the gold pill), the all-day strip (tasks as square chips with a teal edge), and the hours 08:00–21:00 at 40 dp, each
 * event a square block with its calendar's colour on a 2 dp left edge, the title regular over its time, a short one on
 * one line, a clash with work as a small amber dot and a thin amber outline; work hours a plain faint band; the gold now
 * line with its breathing dot. Folded: "November" over "Week 46 · booked 5.8 h" with the switch, the week's seven days
 * as a strip, then three days from the chosen one (38 dp an hour); a swipe across the grid moves three days.
 *
 * A tap on an event opens it; a tap on an empty hour adds an event on that day (where an account allows editing); a
 * tap on a task chip opens the task. Hold an event (its own, no guests, not started: EventMoveRules) and drag it: it
 * lifts with a light haptic, snaps a quarter hour at a time and a whole day per column (only onto a day in view), its
 * time line says where it will land ("Move to Thu 12 Nov · 15:30"), and let go it moves there with the five-second
 * Undo ([CalendarWeekRules.drop]).
 * Motion (catalogue "Calendar"): blocks fade up as the days change, the lit switch blends across with a tick, the now
 * dot breathes; reduced motion: no fade-up, a steady dot.
 */
@Composable
fun CalendarWeekView(
    core: MekaCore,
    modifier: Modifier = Modifier,
    onMode: (CalendarMode) -> Unit,
    onEvent: (CalendarEvent) -> Unit,
    onAddOn: ((Long) -> Unit)?,
    onTask: (String) -> Unit = {},
    canMove: (CalendarEvent) -> Boolean = { false },
    onMove: (CalendarEvent, Int, Int) -> Unit = { _, _, _ -> },
) {
    val board by core.weekBoard.collectAsState()
    val v by core.calendarView.collectAsState()
    val today = v.todayEpochDay.takeIf { it > 0 } ?: core.todayEpochDay()
    var anchor by rememberSaveable { mutableLongStateOf(today) }
    var openDays by rememberSaveable { mutableIntStateOf(CalendarWeekRules.DEFAULT_OPEN_DAYS) }
    val haptics = rememberMekaHaptics()
    BoxWithConstraints(modifier.fillMaxSize().background(Meka.colors.background).testTag(CALENDAR_WEEK_TAG)) {
        val wide = BoardFoldRules.wide(maxWidth.value)
        val count = if (wide) openDays else CalendarWeekRules.FOLDED_DAYS
        val start = CalendarWeekRules.start(anchor, count)
        // Rebuilt when the board does (a task, an event, the minute), or the days in view change.
        val grid = remember(board, start, count) { core.calendarWeek(board, start, count) }
        val step: (Int) -> Unit = { d -> haptics.tick(); anchor = CalendarWeekRules.step(start, count, d) }
        Column(
            Modifier.fillMaxSize().padding(horizontal = MekaSpace.gutter).padding(top = MekaSpace.l, bottom = MekaSpace.xs),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            if (wide) {
                OpenHead(grid, openDays, onDays = { n -> openDays = n }, onToday = { haptics.tick(); anchor = today }, onStep = step, onMode = onMode,
                    modifier = Modifier.appear(rememberAppearance(0)))
                Row(Modifier.fillMaxWidth().appear(rememberAppearance(1)), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                        v.legend.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(Meka.colors.calendarTone(c.tone)))
                                Text(c.label, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(start = MekaSpace.xxs), maxLines = 1)
                            }
                        }
                    }
                    Text(CalendarWeekRules.BOOKED, style = MekaType.caption, color = Meka.colors.textSecondary)
                    Text(grid.booked, style = MekaType.metaStrong, color = Meka.colors.textPrimary, modifier = Modifier.padding(start = MekaSpace.xs))
                }
            } else {
                FoldedHead(grid, onMode, Modifier.appear(rememberAppearance(0)))
                WeekStrip(CalendarWeekRules.monday(start), start, today, Modifier.appear(rememberAppearance(1))) { d -> haptics.tick(); anchor = d }
            }
            Grid(
                grid, wide,
                Modifier.weight(1f).fillMaxWidth().appear(rememberAppearance(2)).let { m ->
                    if (wide) m else m.pointerInput(start) {
                        var dx = 0f
                        detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
                            if (dx < -SWIPE_PX) step(1) else if (dx > SWIPE_PX) step(-1)
                        }) { _, d -> dx += d }
                    }
                },
                onEvent = onEvent, onAddOn = onAddOn, today = today,
                actions = WeekActions(onTask, canMove, onMove),
            )
        }
    }
}

private const val SWIPE_PX = 120f

/** What the grid's chips and held blocks do: open a task, and move an event that may be moved. */
private class WeekActions(
    val onTask: (String) -> Unit,
    val canMove: (CalendarEvent) -> Boolean,
    val onMove: (CalendarEvent, Int, Int) -> Unit,
)

/** Week · Board · Agenda, as one quiet pill switch (the lit side blends across with a tick). */
@Composable
fun CalendarModeSwitch(mode: CalendarMode, modifier: Modifier = Modifier, onChange: (CalendarMode) -> Unit) {
    PillSwitch(CalendarMode.entries.map { it.label }, CalendarMode.entries.indexOf(mode), modifier) { i -> onChange(CalendarMode.entries[i]) }
}

@Composable
private fun PillSwitch(labels: List<String>, lit: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    // Drawn 32 dp tall on its track; each side answers a 48 dp touch reaching 8 dp above and below (the quality gate).
    Box(modifier.height(MekaSpace.touch)) {
        Box(Modifier.matchParentSize().padding(vertical = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface))
        Row(Modifier.padding(horizontal = MekaSpace.xxs)) {
            labels.forEachIndexed { i, label ->
                val on = i == lit
                val fill by animateColorAsState(if (on) Meka.colors.surfaceRaised else Meka.colors.surface, MekaMotion.appear(reduced), label = "pill-$label")
                Box(
                    Modifier.height(MekaSpace.touch).clip(RoundedCornerShape(MekaRadius.pill))
                        .clickable(role = Role.Tab) { if (!on) { haptics.tick(); onPick(i) } }
                        .semantics { selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.height(MekaSpace.xl).clip(RoundedCornerShape(MekaRadius.pill)).background(fill).padding(horizontal = MekaSpace.xs),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = if (on) MekaType.captionStrong else MekaType.caption, color = if (on) Meka.colors.textPrimary else Meka.colors.textSecondary, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenHead(
    grid: WeekGrid, openDays: Int, onDays: (Int) -> Unit, onToday: () -> Unit, onStep: (Int) -> Unit, onMode: (CalendarMode) -> Unit,
    modifier: Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Column(Modifier.weight(1f)) {
            Text(grid.title, style = MekaType.greetingCompact, color = Meka.colors.textPrimary, maxLines = 1, modifier = Modifier.semantics { heading() })
            Text(grid.weekLabel, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1)
        }
        CalendarModeSwitch(CalendarMode.WEEK, onChange = onMode)
        PillSwitch(CalendarWeekRules.OPEN_DAYS.map(CalendarWeekRules::daysLabel), CalendarWeekRules.OPEN_DAYS.indexOf(openDays)) { i -> onDays(CalendarWeekRules.OPEN_DAYS[i]) }
        RoundButton("Today", "Back to this week", onToday)
        RoundButton("‹", "Last week") { onStep(-1) }
        RoundButton("›", "Next week") { onStep(1) }
    }
}

@Composable
private fun RoundButton(text: String, spoken: String, onTap: () -> Unit) {
    // Drawn 36 dp round (a pill for "Today"), answering a 48 dp touch.
    Box(
        Modifier.size(width = if (text.length > 1) 72.dp else MekaSpace.touch, height = MekaSpace.touch).clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(role = Role.Button, onClickLabel = spoken, onClick = onTap)
            .clearAndSetSemantics { contentDescription = spoken; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = if (text.length > 1) 72.dp else 36.dp, height = 36.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface),
            contentAlignment = Alignment.Center,
        ) { Text(text, style = MekaType.caption, color = Meka.colors.textPrimary, maxLines = 1) }
    }
}

@Composable
private fun FoldedHead(grid: WeekGrid, onMode: (CalendarMode) -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(grid.month, style = MekaType.greetingCompact, color = Meka.colors.textPrimary, maxLines = 1, modifier = Modifier.semantics { heading() })
            Text(grid.foldedLine, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1)
        }
        CalendarModeSwitch(CalendarMode.WEEK, onChange = onMode)
    }
}

/** Folded: the week's seven days; the three in view are lit, today's number in gold. */
@Composable
private fun WeekStrip(monday: Long, start: Long, today: Long, modifier: Modifier, onTap: (Long) -> Unit) {
    val reduced = Meka.reducedMotion
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        (0 until 7).forEach { i ->
            val d = monday + i
            val inView = d in start until start + CalendarWeekRules.FOLDED_DAYS
            val fill by animateColorAsState(if (inView) Meka.colors.surfaceRaised else Meka.colors.surface, MekaMotion.appear(reduced), label = "strip-$i")
            val ymd = os.meka.core.domain.CivilDate.fromEpochDay(d)
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(MekaRadius.s)).background(fill)
                    .clickable(role = Role.Button) { onTap(d) }
                    .clearAndSetSemantics { contentDescription = os.meka.core.domain.CivilDate.longLabel(d) + if (d == today) ", today" else "" }
                    .padding(vertical = MekaSpace.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(os.meka.core.domain.LocalClock.DAY_SHORT[i].take(1), style = MekaType.micro, color = Meka.colors.textTertiary)
                Text(ymd.day.toString(), style = MekaType.captionStrong, color = if (d == today) Meka.colors.accent else Meka.colors.textPrimary)
            }
        }
    }
}

@Composable
private fun Grid(
    grid: WeekGrid, wide: Boolean, modifier: Modifier, onEvent: (CalendarEvent) -> Unit, onAddOn: ((Long) -> Unit)?, today: Long,
    actions: WeekActions,
) {
    val hourDp = if (wide) CalendarWeekRules.HOUR_DP else CalendarWeekRules.FOLDED_HOUR_DP
    val labelDp = (if (wide) CalendarWeekRules.LABEL_DP else CalendarWeekRules.FOLDED_LABEL_DP).dp
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // It opens at the morning (08:00 is the top), or an hour before now when now is further down.
    LaunchedEffect(grid.startEpochDay) {
        val now = grid.days.firstNotNullOfOrNull { it.nowMinute } ?: return@LaunchedEffect
        val px = with(density) { (CalendarWeekRules.minuteDp(now - 120, hourDp)).dp.toPx() }.toInt()
        scroll.scrollTo(px.coerceAtLeast(0))
    }
    Column(
        modifier.clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .border(1.dp, Meka.colors.hairline, RoundedCornerShape(MekaRadius.m)).testTag(WEEK_GRID_TAG),
    ) {
        // The days' heads.
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(labelDp))
            grid.days.forEach { d ->
                Column(
                    Modifier.weight(1f).padding(vertical = MekaSpace.xs).clearAndSetSemantics { contentDescription = d.spoken; heading() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(d.weekday, style = MekaType.micro, color = Meka.colors.textTertiary)
                    Box(
                        Modifier.padding(top = MekaSpace.xxs).widthIn(min = 30.dp).height(28.dp).clip(RoundedCornerShape(MekaRadius.s))
                            .background(if (d.isToday) Meka.colors.accent else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) { Text(d.number, style = MekaType.metaStrong, color = if (d.isToday) Meka.colors.onAccent else Meka.colors.textPrimary) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Meka.colors.hairline))
        // The all-day strip: all-day events, events outside the hours and the day's open tasks, as square chips.
        Row(Modifier.fillMaxWidth().heightIn(min = 30.dp)) {
            Text("all day", style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1,
                modifier = Modifier.width(labelDp).align(Alignment.CenterVertically).padding(end = MekaSpace.xs).clearAndSetSemantics { })
            grid.days.forEach { d ->
                Column(Modifier.weight(1f).padding(MekaSpace.xxs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    CalendarWeekRules.shownChips(d.chips).forEach { c -> Chip(c) { c.event?.let(onEvent) ?: c.taskId?.let(actions.onTask) } }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Meka.colors.hairline))
        // The hours.
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            Row(Modifier.fillMaxWidth().height(CalendarWeekRules.heightDp(hourDp).dp + MekaSpace.xs)) {
                Box(Modifier.width(labelDp).fillMaxHeight()) {
                    CalendarWeekRules.hours.forEachIndexed { i, h ->
                        Text(h, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1,
                            modifier = Modifier.align(Alignment.TopEnd).offset(y = (i * hourDp).dp).padding(end = MekaSpace.xs))
                    }
                }
                grid.days.forEachIndexed { i, d ->
                    DayColumn(d, hourDp, Modifier.weight(1f).fillMaxHeight(), onEvent, onAddOn, HeldPlace(i, grid.startEpochDay, grid.days.size, today), actions)
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    d: WeekGridDay, hourDp: Float, modifier: Modifier, onEvent: (CalendarEvent) -> Unit, onAddOn: ((Long) -> Unit)?, place: HeldPlace,
    actions: WeekActions,
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val density = LocalDensity.current
    // The block being held in this column, and how far the finger has taken it (px).
    var heldKey by remember { mutableStateOf<String?>(null) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    Box(modifier.zIndex(if (heldKey != null) 1f else 0f).border(width = 0.5.dp, color = Meka.colors.hairline, shape = RectangleShape)) {
        // Each hour's row: a hairline, and a tap adds an event on this day.
        (CalendarWeekRules.FIRST_HOUR until CalendarWeekRules.END_HOUR).forEachIndexed { i, _ ->
            Box(
                Modifier.fillMaxWidth().height(hourDp.dp).offset(y = (i * hourDp).dp)
                    .let { m -> if (onAddOn != null) m.clickable(onClickLabel = "Add an event") { onAddOn(d.epochDay) } else m }
                    .clearAndSetSemantics { },
            ) { Box(Modifier.fillMaxWidth().height(1.dp).background(Meka.colors.hairline.copy(alpha = 0.5f))) }
        }
        // Work hours: a plain faint band behind the events.
        if (d.workStartMinute != null && d.workEndMinute != null) {
            val top = CalendarWeekRules.minuteDp(d.workStartMinute!!, hourDp)
            val bottom = CalendarWeekRules.minuteDp(d.workEndMinute!!, hourDp)
            Box(Modifier.fillMaxWidth().offset(y = top.dp).height((bottom - top).dp).background(Meka.colors.textPrimary.copy(alpha = 0.035f)).clearAndSetSemantics { })
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val colW = maxWidth
            val colDp = colW.value
            d.blocks.forEach { b ->
                val w = (colW - 6.dp) / b.columns
                androidx.compose.runtime.key(b.key) {
                    val held = heldKey == b.key
                    val drop: WeekDrop? = if (held) with(density) {
                        CalendarWeekRules.drop(b, place.index, place.start, place.count, dx.toDp().value, dy.toDp().value, colDp, hourDp, place.today)
                    } else null
                    val (ox, oy) = CalendarWeekRules.dropOffsetDp(drop, colDp, hourDp)
                    val movable = actions.canMove(b.event)
                    val appearance = rememberAppearance(0, play = !reduced)
                    Block(
                        b, hourDp, drop?.hint,
                        Modifier.offset(x = 3.dp + w * b.column + ox.dp, y = b.topDp(hourDp).dp + 1.dp + oy.dp).width(w).height(b.heightDp(hourDp).dp)
                            .appear(appearance).let { if (held) it.zIndex(1f).shadow(6.dp, RectangleShape) else it }
                            .let { m ->
                                if (!movable) m else m.pointerInput(b, place, colDp) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { haptics.light(); dx = 0f; dy = 0f; heldKey = b.key },
                                        onDragEnd = {
                                            val landed = with(density) {
                                                CalendarWeekRules.drop(b, place.index, place.start, place.count, dx.toDp().value, dy.toDp().value, colDp, hourDp, place.today)
                                            }
                                            heldKey = null; dx = 0f; dy = 0f
                                            if (landed != null) actions.onMove(b.event, landed.days, landed.minutes)
                                        },
                                        onDragCancel = { heldKey = null; dx = 0f; dy = 0f },
                                    ) { change, amount ->
                                        change.consume()
                                        val before = heldDrop(b, place, dx, dy, colDp, hourDp, density)
                                        dx += amount.x; dy += amount.y
                                        // A tick each time it snaps to a new quarter hour or day.
                                        if (heldDrop(b, place, dx, dy, colDp, hourDp, density) != before) haptics.tick()
                                    }
                                }
                            },
                        movable = movable,
                    ) { onEvent(b.event) }
                }
            }
        }
        // The gold now line, its dot breathing.
        d.nowMinute?.let { now ->
            val y = CalendarWeekRules.minuteDp(now, hourDp)
            Box(Modifier.fillMaxWidth().offset(y = y.dp).height(2.dp).background(Meka.colors.accent).clearAndSetSemantics { })
            val pulse = if (reduced) 1f else {
                val t = rememberInfiniteTransition(label = "now-dot")
                t.animateFloat(1f, 1.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "now-dot-scale").value
            }
            Box(Modifier.offset(x = (-5).dp, y = y.dp - 4.dp).size(10.dp).scale(pulse).clip(CircleShape).background(Meka.colors.accent))
        }
    }
}

/** Where a column sits among the days in view, for a held block's drop. */
private data class HeldPlace(val index: Int, val start: Long, val count: Int, val today: Long)

private fun heldDrop(b: WeekBlock, place: HeldPlace, dx: Float, dy: Float, colDp: Float, hourDp: Float, density: androidx.compose.ui.unit.Density): Pair<Int, Int>? =
    with(density) { CalendarWeekRules.drop(b, place.index, place.start, place.count, dx.toDp().value, dy.toDp().value, colDp, hourDp, place.today) }
        ?.let { it.days to it.minutes }

/**
 * One event: square corners, its calendar's colour on a 2 dp left edge, the title regular over its time. While held,
 * [hint] ("Move to Thu 12 Nov · 15:30") takes the time's place.
 */
@Composable
private fun Block(b: WeekBlock, hourDp: Float, hint: String?, modifier: Modifier, movable: Boolean = false, onTap: () -> Unit) {
    val tone = Meka.colors.calendarTone(b.tone)
    val amber = Meka.colors.calendar3
    Row(
        modifier.background(Meka.colors.surfaceRaised)
            .let { if (b.clash) it.border(1.dp, amber.copy(alpha = 0.6f), RectangleShape) else it }
            .testTag(WEEK_BLOCK_TAG)
            .clickable(role = Role.Button, onClickLabel = "Open the event", onClick = onTap)
            .clearAndSetSemantics {
                contentDescription = b.spoken + if (movable) ", hold and drag to move" else ""
                role = Role.Button
            },
    ) {
        Box(Modifier.width(CalendarWeekRules.EDGE_DP.dp).fillMaxHeight().background(tone))
        val pad = Modifier.padding(horizontal = MekaSpace.xxs + 2.dp, vertical = if (b.oneLine) 0.dp else MekaSpace.xxs) // rhythm: ok (the mockup's 7 px inset)
        if (b.oneLine) {
            Row(pad.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                BlockTitle(b, Modifier.weight(1f, fill = false))
                if (b.clash) ClashDot(amber)
                Text(hint ?: b.time.substringBefore("–"), style = TIME, color = if (hint != null) Meka.colors.accent else Meka.colors.textTertiary, maxLines = 1)
            }
        } else {
            Column(pad) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    BlockTitle(b, Modifier.weight(1f, fill = false))
                    if (b.clash) ClashDot(amber)
                }
                Text(hint ?: b.time, style = TIME, color = if (hint != null) Meka.colors.accent else Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BlockTitle(b: WeekBlock, modifier: Modifier) {
    Text(b.title, style = TITLE, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
private fun ClashDot(amber: Color) {
    Box(Modifier.size(6.dp).clip(CircleShape).background(amber))
}

/** A strip chip: square corners, a 2 dp edge (teal for a task, the calendar's colour for an event). */
@Composable
private fun Chip(c: WeekChip, onTap: () -> Unit) {
    val edge = if (c.kind == WeekChipKind.TASK) Meka.colors.calendar1 else Meka.colors.calendarTone(c.tone)
    Row(
        Modifier.fillMaxWidth().background(edge.copy(alpha = 0.10f))
            .let { if (c.event != null || c.taskId != null) it.clickable(role = Role.Button, onClickLabel = if (c.event != null) "Open the event" else "Open the task", onClick = onTap) else it }
            .testTag(WEEK_CHIP_TAG)
            .clearAndSetSemantics { contentDescription = c.spoken },
    ) {
        Box(Modifier.width(CalendarWeekRules.EDGE_DP.dp).height(18.dp).background(edge))
        Text(c.text, style = TITLE, color = if (c.kind == WeekChipKind.TASK) Meka.colors.calendar1 else Meka.colors.textPrimary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = MekaSpace.xxs).align(Alignment.CenterVertically))
    }
}

/** The approved grid's 11 sp regular titles and 10 sp times (Option A). */
private val TITLE = MekaType.micro.copy(fontSize = 11.sp) // type: ok (the approved Week grid's 11 sp titles)
private val TIME = MekaType.micro.copy(fontSize = 10.sp) // type: ok (the approved Week grid's 10 sp times)
