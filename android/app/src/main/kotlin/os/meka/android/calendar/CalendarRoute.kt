package os.meka.android.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.today.AllDayChips
import os.meka.android.today.NowLine
import os.meka.android.today.TimeColumn
import os.meka.core.domain.AgendaKind
import os.meka.core.domain.AgendaSection
import os.meka.core.domain.CalendarView
import os.meka.core.domain.DayPill
import os.meka.core.domain.TimelineKind
import os.meka.core.domain.TimelineRow
import os.meka.core.facade.MekaCore

/**
 * CALENDAR (calendar redesign, slice 2): a week strip (seven day pills with busy dots; swipe for the next week) above
 * the next 30 days grouped by day: "Today", "Tomorrow", "Thu 8 Oct". All-day events are chips, fixtures are marked in
 * the accent colour, planned tasks sit among the events, empty stretches fold into one "Nothing planned" line. Tap a
 * day to jump to it; scrolling the agenda keeps the strip on the week you're looking at. Shows only: nothing here
 * changes anything (event detail is slice 3).
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
    Agenda(v)
}

/** One line of the agenda list, flattened so the strip can jump to a section's header. */
private sealed interface Entry {
    val key: String
    val section: AgendaSection

    data class Header(override val section: AgendaSection, val index: Int) : Entry { override val key = "h-" + section.id }
    data class Chips(override val section: AgendaSection) : Entry { override val key = "c-" + section.id }
    data class Line(override val section: AgendaSection, val row: TimelineRow, val past: Boolean) : Entry {
        override val key = section.id + "/" + row.id
    }
    data class Empty(override val section: AgendaSection, val text: String) : Entry { override val key = "x-" + section.id }
}

private fun flatten(v: CalendarView): List<Entry> = buildList {
    v.sections.forEachIndexed { i, s ->
        add(Entry.Header(s, i))
        if (s.allDay.isNotEmpty()) add(Entry.Chips(s))
        s.ended.forEach { add(Entry.Line(s, it, past = true)) }
        s.rows.forEach { add(Entry.Line(s, it, past = false)) }
        s.emptyLine?.let { add(Entry.Empty(s, it)) }
    }
}

@Composable
private fun Agenda(v: CalendarView) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val entries = remember(v) { flatten(v) }
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
            scope.launch { if (reduced) list.scrollToItem(index) else list.animateScrollToItem(index) }
        }
    }

    Column(Modifier.fillMaxSize().background(Meka.colors.background)) {
        Column(Modifier.padding(start = MekaSpace.gutter, end = MekaSpace.gutter, top = MekaSpace.xl)) {
            Text("Calendar", style = MekaType.greeting, color = Meka.colors.textPrimary, modifier = Modifier.appear(rememberAppearance(0)))
            Text(v.summary, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs).appear(rememberAppearance(1)))
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
        Box(Modifier.fillMaxWidth().padding(top = MekaSpace.s).height(1.dp).background(Meka.colors.hairline))
        AgendaList(entries, list)
    }
}

/** The days a section covers, for keeping a tapped day lit inside a free stretch. */
private fun sectionDays(v: CalendarView, firstDay: Long): LongRange =
    v.sections.firstOrNull { it.firstDay == firstDay }?.let { it.firstDay..it.lastDay } ?: LongRange.EMPTY

@Composable
private fun WeekTitle(v: CalendarView, page: Int, modifier: Modifier) {
    val week = v.weeks.getOrNull(page) ?: return
    AnimatedContent(
        targetState = week,
        contentKey = { it.startEpochDay },
        transitionSpec = { fadeIn(MekaMotion.appear(Meka.reducedMotion)) togetherWith fadeOut(MekaMotion.appear(Meka.reducedMotion)) },
        label = "week-title",
        modifier = modifier,
    ) { w ->
        Row(Modifier.fillMaxWidth().padding(bottom = MekaSpace.xs), verticalAlignment = Alignment.Bottom) {
            Text(w.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
            if (w.title != w.range) {
                Spacer(Modifier.width(MekaSpace.s))
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
        Row(Modifier.height(6.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(d.dots) { Box(Modifier.size(4.dp).clip(CircleShape).background(if (d.isToday) Meka.colors.accent else Meka.colors.textSecondary)) }
        }
    }
}

@Composable
private fun AgendaList(entries: List<Entry>, list: LazyListState) {
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(start = MekaSpace.gutter, end = MekaSpace.gutter, top = MekaSpace.s, bottom = MekaSpace.xl),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { it.key }) { e ->
            // Sections stagger in 40 ms apart on first show; later rows just glide.
            val stagger = (e as? Entry.Header)?.index ?: -1
            val m = Modifier.animateItem().let { if (stagger in 0..7) it.appear(rememberAppearance(3 + stagger)) else it }
            when (e) {
                is Entry.Header -> SectionHeader(e.section, m)
                is Entry.Chips -> AllDayChips(e.section.allDay, m.padding(start = os.meka.android.today.TIME_COLUMN))
                is Entry.Empty -> Text(
                    e.text, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = m.padding(start = os.meka.android.today.TIME_COLUMN, bottom = MekaSpace.xs),
                )
                is Entry.Line -> when (e.row.kind) {
                    TimelineKind.EVENT -> EventRow(e.row, e.past, m)
                    TimelineKind.TASK -> TaskRow(e.row, m)
                    TimelineKind.NOW -> NowLine(e.row, m)
                    TimelineKind.GAP -> Unit // the agenda has no gaps; Today shows free time
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
        modifier.fillMaxWidth().padding(top = if (free) MekaSpace.s else MekaSpace.l, bottom = if (free) MekaSpace.s else MekaSpace.xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            s.title, style = if (free) MekaType.body else MekaType.itemTitle,
            color = if (free) Meka.colors.textTertiary else Meka.colors.textPrimary,
        )
        s.subtitle?.let {
            Spacer(Modifier.width(MekaSpace.s))
            Text(it, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
    }
}

/** An event: context, so the regular body weight. Fixtures are marked in the accent colour. */
@Composable
private fun EventRow(r: TimelineRow, past: Boolean, modifier: Modifier) {
    val fixture = r.event?.isFixture == true
    Row(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs), verticalAlignment = Alignment.Top) {
        TimeColumn(r.time, past)
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

/** A planned task: something to act on, so the item title weight. Shown only; it's ticked in Today. */
@Composable
private fun TaskRow(r: TimelineRow, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs), verticalAlignment = Alignment.Top) {
        TimeColumn(r.time, past = false)
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
            r.detail?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        }
    }
}
