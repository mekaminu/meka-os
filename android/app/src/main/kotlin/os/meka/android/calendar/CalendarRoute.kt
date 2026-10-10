package os.meka.android.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.footFade
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.calendarTone
import os.meka.android.today.AllDayLabel
import os.meka.android.today.AllDayMore
import os.meka.android.today.NowLine
import os.meka.android.today.TimeColumn
import os.meka.core.domain.AgendaKind
import os.meka.core.domain.AgendaSection
import os.meka.core.domain.AllDayItem
import os.meka.core.domain.AllDayRules
import os.meka.core.domain.CalendarTone
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.CalendarView
import os.meka.core.domain.DayPill
import os.meka.core.domain.TimelineKind
import os.meka.core.domain.TimelineRow
import os.meka.core.facade.MekaCore

/**
 * CALENDAR (calendar redesign, slice 2): a week strip (seven day pills with busy dots; swipe for the next week) above
 * the next 30 days grouped by day: "Today", "Tomorrow", "Thu 8 Oct". All-day events are an "All day" group like
 * Today's (one row each, at most 3 then "+2 more"; Fold review 2026-10-08, item 9), each event carries its calendar's
 * colour dot (a key under the summary names them), fixtures are marked in Barça's colour, planned tasks sit among the
 * events, empty stretches fold into one "Nothing planned" line. Tap a day to spring the agenda to it; scrolling the
 * agenda keeps the strip on the week you're looking at. Tapping an event opens its detail (slice 3); "+ Add event"
 * (calendar editing, slice 2b) adds a real event where an account allows editing, and the lines under the summary say
 * how it's going ("Added “Dentist” to Google").
 *
 * Motion (catalogue "Calendar"): the strip slides between weeks; the lit pill's colour blends across with a tick
 * haptic; sections stagger in 40 ms apart; rows glide as the day moves on; the now line's dot breathes. Reduced
 * motion: jumps and week changes happen at once, cross-fades only, a steady dot.
 */
@Composable
fun CalendarRoute(core: MekaCore) {
    val v by core.calendarView.collectAsState()
    if (v.sections.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Meka.colors.background))
        return
    }
    // Tapping an event or an all-day chip opens its detail over the agenda (slice 3). The last one is kept so it
    // stays visible while the pane leaves.
    var openEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    var shown by remember { mutableStateOf<CalendarEvent?>(null) }
    if (openEvent != null) shown = openEvent
    // Calendar actions: swipe right for a prep task, left to hide from my day; hidden events wait at the day's foot.
    val scope = rememberCoroutineScope()
    val undo = rememberEventUndo()
    val handlers = remember(core, scope, undo) { eventActionHandlers(core, scope, undo) }
    val showAgain: (CalendarEvent) -> Unit = { e -> scope.launch { runCatching { core.showEvent(e.id) } } }
    // Calendar editing (slice 2b): "+ Add event" while an account allows editing; the pane opens on the lit day.
    val editAccounts by core.calendarEditAccounts.collectAsState()
    val editLines by core.calendarEditLines.collectAsState()
    LaunchedEffect(Unit) { runCatching { core.refreshCalendarAccounts() } }
    var addingOn by remember { mutableStateOf<Long?>(null) }
    var addShown by remember { mutableLongStateOf(-1L) }
    addingOn?.let { addShown = it }
    val addHeader = AddHeader(editAccounts.isNotEmpty(), editLines) { day -> addingOn = day }
    Box(Modifier.fillMaxSize()) {
        Agenda(v, handlers, showAgain, addHeader) { openEvent = it }
        MekaPane(visible = openEvent != null) {
            shown?.let { e -> EventDetailPane(core, e, onClose = { openEvent = null }, undo = undo) }
        }
        MekaPane(visible = addingOn != null) {
            AddEventPane(
                core, addShown,
                onClose = { addingOn = null },
                onAdded = { id ->
                    addingOn = null
                    undo.show(core.eventEditLine(id) ?: "Adding it to your calendar") { core.undoEventEdit(id) }
                },
            )
        }
        EventUndoBar(undo, Modifier.align(Alignment.BottomCenter))
    }
}

/** The header's editing bits: the Add event button (when allowed) and the lines about edits on their way. */
private class AddHeader(val canAdd: Boolean, val lines: List<os.meka.core.domain.EditLine>, val onAdd: (Long) -> Unit)

/** One line of the agenda list, flattened so the strip can jump to a section's header. */
private sealed interface Entry {
    val key: String
    val section: AgendaSection

    data class Header(override val section: AgendaSection, val index: Int) : Entry { override val key = "h-" + section.id }
    data class AllDayHead(override val section: AgendaSection) : Entry { override val key = "al-" + section.id }
    data class AllDay(override val section: AgendaSection, val item: AllDayItem) : Entry { override val key = section.id + "/a-" + item.event.id }
    data class AllDayMoreLine(override val section: AgendaSection, val text: String) : Entry { override val key = "am-" + section.id }
    data class Line(override val section: AgendaSection, val row: TimelineRow, val past: Boolean) : Entry {
        override val key = section.id + "/" + row.id
    }
    data class Empty(override val section: AgendaSection, val text: String) : Entry { override val key = "x-" + section.id }
    data class Work(override val section: AgendaSection, val text: String) : Entry { override val key = "w-" + section.id }
    data class HiddenLabel(override val section: AgendaSection, val text: String) : Entry { override val key = "hl-" + section.id }
    data class Hidden(override val section: AgendaSection, val event: CalendarEvent) : Entry { override val key = section.id + "/hidden-" + event.id }
}

private fun flatten(v: CalendarView, allDayOpen: Set<String>): List<Entry> = buildList {
    v.sections.forEachIndexed { i, s ->
        add(Entry.Header(s, i))
        if (s.allDayItems.isNotEmpty()) {
            val open = s.id in allDayOpen
            add(Entry.AllDayHead(s))
            AllDayRules.shown(s.allDayItems, open).forEach { add(Entry.AllDay(s, it)) }
            AllDayRules.moreLabel(s.allDayItems, open)?.let { add(Entry.AllDayMoreLine(s, it)) }
        }
        s.workTitle?.let { add(Entry.Work(s, it)) }
        s.ended.forEach { add(Entry.Line(s, it, past = true)) }
        s.rows.forEach { add(Entry.Line(s, it, past = false)) }
        s.emptyLine?.let { add(Entry.Empty(s, it)) }
        s.hiddenLabel?.let { label ->
            add(Entry.HiddenLabel(s, label))
            s.hidden.forEach { add(Entry.Hidden(s, it)) }
        }
    }
}

@Composable
private fun Agenda(
    v: CalendarView, handlers: EventActionHandlers, showAgain: (CalendarEvent) -> Unit, add: AddHeader, onEvent: (CalendarEvent) -> Unit,
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    // Days whose "+2 more" was tapped show all their all-day rows (they spring in with the list's item motion).
    var allDayOpen by remember { mutableStateOf(emptySet<String>()) }
    val entries = remember(v, allDayOpen) { flatten(v, allDayOpen) }
    val headerIndex = remember(entries) { entries.withIndex().filter { it.value is Entry.Header }.associate { it.value.section.id to it.index } }
    val list = rememberLazyListState()
    val pager = rememberPagerState(pageCount = { v.weeks.size })
    // The day the strip lights: the one tapped, else the first section on screen.
    var tapped by rememberSaveable { mutableLongStateOf(Long.MIN_VALUE) }
    val onScreen by remember(entries) { derivedStateOf { entries.getOrNull(list.firstVisibleItemIndex)?.section?.firstDay ?: v.todayEpochDay } }
    // A tapped day inside a free stretch stays lit while that stretch is on screen.
    val lit = if (tapped in sectionDays(v, onScreen)) tapped else onScreen

    // Scrolling the agenda keeps the strip on the week being looked at (unless a finger is on the strip).
    LaunchedEffect(v.weekIndexOf(onScreen)) {
        val week = v.weekIndexOf(onScreen)
        if (!pager.isScrollInProgress && pager.currentPage != week) {
            if (reduced) pager.scrollToPage(week) else pager.animateScrollToPage(week)
        }
    }

    val jump: (DayPill) -> Unit = { d ->
        val index = d.sectionId?.let { headerIndex[it] }
        if (index != null) {
            haptics.tick()
            tapped = d.epochDay
            scope.launch { if (reduced) list.scrollToItem(index) else list.springScrollTo(index) }
        }
    }

    Column(Modifier.fillMaxSize().background(Meka.colors.background)) {
        Column(Modifier.padding(start = MekaSpace.gutter, end = MekaSpace.gutter, top = MekaSpace.xl)) {
            Row(Modifier.fillMaxWidth().appear(rememberAppearance(0)), verticalAlignment = Alignment.CenterVertically) {
                Text("Calendar", style = MekaType.greeting, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                if (add.canAdd) AddEventButton(onClick = { add.onAdd(lit) })
            }
            Text(v.summary, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs).appear(rememberAppearance(1)))
            EditLines(add.lines, Modifier.padding(top = MekaSpace.xxs).appear(rememberAppearance(1)))
            if (v.legend.isNotEmpty()) CalendarKey(v.legend, Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(1)))
            Spacer(Modifier.height(MekaSpace.m))
            WeekTitle(v, pager.currentPage, Modifier.appear(rememberAppearance(2)))
        }
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = MekaSpace.gutter - MekaSpace.xxs),
            modifier = Modifier.fillMaxWidth().appear(rememberAppearance(2)),
        ) { page ->
            Row(Modifier.fillMaxWidth()) {
                v.weeks[page].days.forEach { d -> Pill(d, d.epochDay == lit, Modifier.weight(1f)) { jump(d) } }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = MekaSpace.xs).height(1.dp).background(Meka.colors.hairline))
        AgendaList(v, entries, list, handlers, showAgain, onEvent) { id -> allDayOpen = allDayOpen + id }
    }
}

/**
 * Springs the agenda to the item at [index] (the expand spring): from close by it glides the whole way; from far away
 * it first jumps to a few rows short of the day so the last stretch still springs into place.
 */
private suspend fun LazyListState.springScrollTo(index: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        val near = if (index > firstVisibleItemIndex) index - 3 else index + 3
        scrollToItem(near.coerceIn(0, (layoutInfo.totalItemsCount - 1).coerceAtLeast(0)))
    }
    val target = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (target == null) animateScrollToItem(index) else animateScrollBy(target.offset.toFloat(), MekaMotion.expand(false))
}

/** The key under the summary: each calendar's dot and name ("● Kids  ● Personal  ● Fixtures"), wrapping. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarKey(legend: List<CalendarTone>, modifier: Modifier) {
    FlowRow(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Calendars: " + legend.joinToString(", ") { it.label } },
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        legend.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CalendarDot(c.tone)
                Spacer(Modifier.width(MekaSpace.xxs))
                Text(c.label, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }
    }
}

/** A calendar's small colour dot. */
@Composable
private fun CalendarDot(tone: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).clip(CircleShape).background(Meka.colors.calendarTone(tone)))
}

/** The days a section covers, for keeping a tapped day lit inside a free stretch. */
private fun sectionDays(v: CalendarView, firstDay: Long): LongRange =
    v.sections.firstOrNull { it.firstDay == firstDay }?.let { it.firstDay..it.lastDay } ?: LongRange.EMPTY

@Composable
private fun WeekTitle(v: CalendarView, page: Int, modifier: Modifier) {
    val week = v.weeks.getOrNull(page) ?: return
    val reduced = Meka.reducedMotion
    AnimatedContent(
        targetState = week,
        contentKey = { it.startEpochDay },
        transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
        label = "week-title",
        modifier = modifier,
    ) { w ->
        Row(Modifier.fillMaxWidth().padding(bottom = MekaSpace.xs), verticalAlignment = Alignment.Bottom) {
            Text(w.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
            if (w.title != w.range) {
                Spacer(Modifier.width(MekaSpace.xs))
                Text(w.range, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }
    }
}

/** A day pill: the letter, the date (accent for today), up to three busy dots. Lit with a soft fill. */
@Composable
private fun Pill(d: DayPill, lit: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val fill by animateColorAsState(if (lit) Meka.colors.surfaceRaised else Meka.colors.background, MekaMotion.appear(Meka.reducedMotion), label = "pill-fill")
    val numberColor = when {
        d.isToday -> Meka.colors.accent
        d.inRange -> Meka.colors.textPrimary
        else -> Meka.colors.textTertiary
    }
    Column(
        modifier
            .padding(horizontal = MekaSpace.xxs)
            .clip(RoundedCornerShape(MekaRadius.pill))
            .background(fill)
            .then(if (d.sectionId != null) Modifier.clickable(role = Role.Tab) { onTap() } else Modifier)
            .alpha(if (d.inRange) 1f else 0.5f)
            .padding(vertical = MekaSpace.xs)
            .clearAndSetSemantics { contentDescription = d.accessibilityLabel; selected = lit },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(d.letter, style = MekaType.caption, color = Meka.colors.textTertiary)
        Text(d.number, style = MekaType.itemTitle, color = numberColor, textAlign = TextAlign.Center)
        Row(Modifier.height(6.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) { // rhythm: ok (the dot pips under a day number)
            repeat(d.dots) { Box(Modifier.size(4.dp).clip(CircleShape).background(if (d.isToday) Meka.colors.accent else Meka.colors.textSecondary)) }
        }
    }
}

@Composable
private fun AgendaList(
    v: CalendarView, entries: List<Entry>, list: LazyListState, handlers: EventActionHandlers, showAgain: (CalendarEvent) -> Unit,
    onEvent: (CalendarEvent) -> Unit, openAllDay: (String) -> Unit,
) {
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(start = MekaSpace.gutter, end = MekaSpace.gutter, top = MekaSpace.xs, bottom = MekaSpace.xl),
        // The foot fades into the tabs rather than cutting a row in half; the xl bottom padding clears the fade.
        modifier = Modifier.fillMaxSize().footFade(),
    ) {
        items(entries, key = { it.key }) { e ->
            // Sections stagger in 40 ms apart on first show; later rows just glide.
            val stagger = (e as? Entry.Header)?.index ?: -1
            val appearance = rememberAppearance(3 + stagger.coerceIn(0, 7), play = stagger in 0..7)
            val m = Modifier.animateItem().appear(appearance)
            when (e) {
                is Entry.Header -> SectionHeader(e.section, m)
                // The "All day" group as Today shows it: the label once, one row each, "+2 more" unfolds the rest.
                is Entry.AllDayHead -> AllDayLabel(e.section.allDayLabel, m.padding(start = os.meka.android.today.TIME_COLUMN))
                is Entry.AllDay -> AgendaAllDayRow(e.item, v.toneOf(e.item.event), m) { onEvent(e.item.event) }
                is Entry.AllDayMoreLine -> AllDayMore(e.text, { openAllDay(e.section.id) }, m)
                is Entry.Empty -> Text(
                    e.text, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = m.padding(start = os.meka.android.today.TIME_COLUMN, bottom = MekaSpace.xs),
                )
                // Work hours (Fold review 2026-10-08): "Work 09:00–17:30" as the quiet band Today uses, full width like
                // Today's, and "Work · Now · until 17:30" with the bar lit while at work (Meka's 10:48 screenshots).
                is Entry.Work -> os.meka.android.today.WorkBand(
                    e.text, e.section.workDetail, running = e.section.workRunning,
                    modifier = m.padding(start = os.meka.android.today.TIME_COLUMN, bottom = MekaSpace.xs).fillMaxWidth(),
                )
                is Entry.HiddenLabel -> Text(
                    e.text, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = m.padding(start = os.meka.android.today.TIME_COLUMN, top = MekaSpace.xs),
                )
                is Entry.Hidden -> HiddenRow(e.event, m, { onEvent(e.event) }) { showAgain(e.event) }
                is Entry.Line -> when (e.row.kind) {
                    TimelineKind.EVENT -> {
                        val tone = e.row.event?.let { v.toneOf(it) }
                        if (e.past) EventRow(e.row, true, tone, m.opensEvent(e.row.event, onEvent))
                        else SwipeableEvent(e.row.event, handlers, m, onOpen = onEvent) { sm -> EventRow(e.row, false, tone, sm) }
                    }
                    TimelineKind.TASK -> TaskRow(e.row, m)
                    TimelineKind.NOW -> NowLine(e.row, m)
                    TimelineKind.GAP -> Unit // the agenda has no gaps; Today shows free time
                    TimelineKind.SESSION -> Unit // booked sessions live on Today (and Goals), not in the agenda
                    TimelineKind.WORK -> Unit // the agenda says "Work 09:00–17:30" once per day instead
                }
            }
        }
    }
}

/** "Today" with "Tuesday 6 October" beside it; a free stretch is one quiet line. */
@Composable
private fun SectionHeader(s: AgendaSection, modifier: Modifier) {
    val free = s.kind == AgendaKind.FREE
    Row(
        modifier.fillMaxWidth().padding(top = if (free) MekaSpace.xs else MekaSpace.l, bottom = MekaSpace.xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            s.title, style = if (free) MekaType.body else MekaType.itemTitle,
            color = if (free) Meka.colors.textTertiary else Meka.colors.textPrimary,
        )
        s.subtitle?.let {
            Spacer(Modifier.width(MekaSpace.xs))
            Text(it, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
    }
}

/**
 * An event: context, so the regular body weight, after its calendar's colour dot (dimmed once it has ended). Fixtures
 * are marked in the accent colour.
 */
@Composable
private fun EventRow(r: TimelineRow, past: Boolean, tone: Int?, modifier: Modifier) {
    val fixture = r.event?.isFixture == true
    Row(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs), verticalAlignment = Alignment.Top) {
        TimeColumn(r.time, past)
        DotColumn(tone, past)
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MekaType.body, color = if (past) Meka.colors.textTertiary else Meka.colors.textPrimary)
            val line = listOfNotNull(if (r.running) "Now" else null, r.detail).joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(
                    line, style = MekaType.caption,
                    color = if (!past && (r.running || fixture)) Meka.colors.accent else Meka.colors.textTertiary,
                )
            }
        }
    }
}

/** The dot beside a title, centred on its first line; nothing (but the same width) without a calendar. */
@Composable
private fun DotColumn(tone: Int?, past: Boolean = false) {
    Box(Modifier.width(MekaSpace.m).height(24.dp), contentAlignment = Alignment.CenterStart) {
        if (tone != null) CalendarDot(tone, Modifier.alpha(if (past) 0.5f else 1f))
    }
}

/**
 * One all-day entry in the agenda, as Today's "All day" row (the title in the event weight in the title column, its
 * calendar under it only when calendars are mixed), with its calendar's dot. Tapping opens the detail; the agenda only
 * shows, so the to-do pill and the long-press menu stay on Today.
 */
@Composable
private fun AgendaAllDayRow(item: AllDayItem, tone: Int, modifier: Modifier, onOpen: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onOpen() }
            .padding(vertical = MekaSpace.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Spacer(Modifier.width(os.meka.android.today.TIME_COLUMN))
        DotColumn(tone)
        Column(Modifier.weight(1f)) {
            Text(item.event.title, style = MekaType.body, color = Meka.colors.textPrimary)
            item.line?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        }
    }
}

/** An event hidden from my day: dimmed, with Show to bring it back. */
@Composable
private fun HiddenRow(event: CalendarEvent, modifier: Modifier, onOpen: () -> Unit, onShow: () -> Unit) {
    val haptics = rememberMekaHaptics()
    Row(modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(os.meka.android.today.TIME_COLUMN))
        Text(
            event.title, style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.weight(1f).clickable(role = Role.Button) { onOpen() },
        )
        Text(
            "Show", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { haptics.light(); onShow() }
                .padding(start = MekaSpace.m, top = MekaSpace.xxs, bottom = MekaSpace.xxs),
        )
    }
}

/** A planned task in the body weight like Today's task rows (Fold review 2026-10-08). Shown only; it's ticked in Today. */
@Composable
private fun TaskRow(r: TimelineRow, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs), verticalAlignment = Alignment.Top) {
        TimeColumn(r.time, past = false)
        DotColumn(null)
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MekaType.body, color = Meka.colors.textPrimary)
            r.detail?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        }
    }
}
