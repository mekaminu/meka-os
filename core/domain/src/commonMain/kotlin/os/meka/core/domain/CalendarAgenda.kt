package os.meka.core.domain

/** One day pill in the Calendar tab's week strip. */
data class DayPill(
    val epochDay: Long,
    /** "T" */
    val letter: String,
    /** "6" */
    val number: String,
    val isToday: Boolean,
    /** Inside the agenda's window (today and the next 29 days); days outside it are shown dimmed and can't be tapped. */
    val inRange: Boolean,
    /** 0–3 dots for how busy the day is (events, all-day events and planned tasks). */
    val dots: Int,
    /** The agenda section a tap jumps to (the day, or the free stretch it sits in); null outside the window. */
    val sectionId: String?,
    /** Screen readers: "Thursday 8 October, 2 events, 1 task" · "Saturday 10 October, nothing planned". */
    val accessibilityLabel: String,
)

/** Seven pills, Monday to Sunday. */
data class WeekStrip(
    val startEpochDay: Long,
    /** "This week" · "Next week" · "Last week" · "19–25 Oct" */
    val title: String,
    /** "5–11 Oct" · "28 Sep – 4 Oct" */
    val range: String,
    val days: List<DayPill>,
)

enum class AgendaKind {
    /** A day with things on it (today and tomorrow are always days, even when empty). */
    DAY,

    /** One or more days in a row with nothing on them, folded into one quiet line. */
    FREE,
}

/** One section of the agenda: a day, or a free stretch of days. */
data class AgendaSection(
    /** "d-<epochDay>" for a day, "f-<first epochDay>" for a free stretch. */
    val id: String,
    val kind: AgendaKind,
    val firstDay: Long,
    val lastDay: Long,
    /** "Today" · "Tomorrow" · "Thu 8 Oct" · "Thu 8 – Sat 10 Oct" */
    val title: String,
    /** "Tuesday 6 October" under Today and Tomorrow; "2 events · 1 fixture" under other days; "Nothing planned" when free. */
    val subtitle: String?,
    val allDay: List<CalendarEvent>,
    /** Today only: events that have ended, shown dimmed at the top. */
    val ended: List<TimelineRow>,
    /** Events and planned tasks in time order; today's carry a now line after what has started. */
    val rows: List<TimelineRow>,
    /** "Nothing planned" for an empty today or tomorrow; null otherwise. */
    val emptyLine: String?,
    /** Events hidden from my day (calendar actions), listed quietly at the bottom of the day with "Show". */
    val hidden: List<CalendarEvent> = emptyList(),
) {
    /** "1 hidden from your day" · "2 hidden from your day"; null when none. */
    val hiddenLabel: String? get() = if (hidden.isEmpty()) null else "${hidden.size} hidden from your day"
}

data class CalendarView(
    /** "Tuesday 6 October" */
    val todayLabel: String,
    val todayEpochDay: Long,
    /** From the week holding today to the week holding the last day of the window. */
    val weeks: List<WeekStrip>,
    val sections: List<AgendaSection>,
    /** "4 events in the next 30 days · 2 fixtures" */
    val summary: String,
) {
    /** The week strip index holding [epochDay], or 0. */
    fun weekIndexOf(epochDay: Long): Int = weeks.indexOfFirst { epochDay in it.startEpochDay until it.startEpochDay + 7 }.coerceAtLeast(0)

    /** The section index holding [epochDay] (a day or a free stretch), or -1 outside the window. */
    fun sectionIndexOf(epochDay: Long): Int = sections.indexOfFirst { epochDay in it.firstDay..it.lastDay }

    companion object {
        val EMPTY = CalendarView("", 0, emptyList(), emptyList(), "")
    }
}

/**
 * The Calendar tab (calendar redesign, slice 2), non-AI and pure: a week strip and a 30-day agenda grouped by day.
 *
 * - The agenda runs from today for [DAYS] days (the server mirrors 30 days ahead). Today and Tomorrow always show;
 *   later days with nothing on them fold into one "Nothing planned" line per stretch ("Thu 8 – Sat 10 Oct").
 * - A day holds its all-day events (as chips) and its timed events and open planned tasks in time order (events first
 *   at the same minute). An event that runs over midnight shows on each day it touches: "From 22:00", "All day",
 *   "Until 01:00".
 * - Today's events that have ended are listed first (dimmed), then what has started, a now line, and what's ahead.
 * - Fixtures (the fixtures feed) are marked so they stand out.
 * - Week strips run Monday–Sunday from the week holding today; each day pill has up to 3 busy dots and jumps to its
 *   section. Days before today or past the window are dimmed.
 */
object CalendarAgenda {
    const val DAYS = 30
    const val MAX_DOTS = 3
    private const val MIN_MS = 60_000L

    fun build(
        tasks: List<Task>,
        events: List<CalendarEvent>,
        nowMs: Long,
        calendar: LocalCalendar,
        days: Int = DAYS,
        hidden: Set<String> = emptySet(),
    ): CalendarView {
        val today = calendar.epochDayOf(nowMs)
        val lastDay = today + days - 1
        fun hhmm(ms: Long) = LocalClock.formatMinute(calendar.minuteOfDay(ms))

        val planned = tasks.filter {
            (it.lifecycle == Lifecycle.ACTIVE || it.lifecycle == Lifecycle.INBOX) && it.scheduledAtMs != null
        }

        val shown = events.filter { it.id !in hidden }
        val hiddenEvents = events.filter { it.id in hidden }

        data class Day(val epochDay: Long, val allDay: List<CalendarEvent>, val ended: List<TimelineRow>, val rows: List<TimelineRow>, val events: Int, val fixtures: Int, val tasks: Int, val hidden: List<CalendarEvent>) {
            val count get() = allDay.size + ended.size + rows.count { it.kind != TimelineKind.NOW }
        }

        val dayData = (today..lastDay).map { d ->
            val w = window(d, calendar)
            val dayEvents = shown.filter { it.overlaps(w) }
            val allDay = dayEvents.filter { it.allDay }.sortedWith(compareBy({ it.startAtMs }, { it.title }))

            data class Item(val row: TimelineRow, val start: Long, val end: Long, val isEvent: Boolean)
            val items = buildList {
                dayEvents.filter { !it.allDay }.forEach { e ->
                    val startsBefore = e.startAtMs < w.startMs
                    val endsAfter = e.endAtMs > w.endMs
                    val time = when {
                        startsBefore && endsAfter -> "All day"
                        startsBefore -> "Until ${hhmm(e.endAtMs)}"
                        endsAfter -> "From ${hhmm(e.startAtMs)}"
                        e.endAtMs <= e.startAtMs -> hhmm(e.startAtMs)
                        else -> "${hhmm(e.startAtMs)}–${hhmm(e.endAtMs)}"
                    }
                    val start = maxOf(e.startAtMs, w.startMs)
                    val running = e.startAtMs <= nowMs && e.endAtMs > nowMs
                    add(Item(TimelineRow("e-" + e.id, TimelineKind.EVENT, time, e.title, eventDetail(e), null, e, running, start), start, e.endAtMs, true))
                }
                planned.filter { it.scheduledAtMs!! in w }.forEach { t ->
                    val start = t.scheduledAtMs!!
                    val detail = taskDetail(t, d)
                    add(Item(TimelineRow("t-" + t.id, TimelineKind.TASK, hhmm(start), t.title, detail, t, null, false, start), start, start, false))
                }
            }.sortedWith(compareBy<Item> { it.start }.thenBy { !it.isEvent }.thenBy { it.row.title })

            val isToday = d == today
            val endedItems = if (isToday) items.filter { it.isEvent && it.end <= nowMs } else emptyList()
            val endedIds = endedItems.map { it.row.id }.toSet()
            val live = items.filter { it.row.id !in endedIds }
            val rows = if (!isToday) live.map { it.row } else buildList {
                val started = live.filter { it.start <= nowMs }
                started.forEach { add(it.row) }
                if (items.isNotEmpty()) add(TimelineRow("now", TimelineKind.NOW, hhmm(nowMs), "Now", null, null, null, false, nowMs))
                live.filter { it.start > nowMs }.forEach { add(it.row) }
            }
            val timedEvents = items.filter { it.isEvent }.map { it.row.event!! }
            Day(
                epochDay = d,
                allDay = allDay,
                ended = endedItems.map { it.row },
                rows = rows,
                events = allDay.size + timedEvents.size,
                fixtures = (allDay + timedEvents).count { it.isFixture },
                tasks = items.count { !it.isEvent },
                hidden = hiddenEvents.filter { it.overlaps(w) }.sortedWith(compareBy({ it.startAtMs }, { it.title })),
            )
        }

        val sections = buildList {
            var i = 0
            while (i < dayData.size) {
                val day = dayData[i]
                val d = day.epochDay
                if (day.count > 0 || day.hidden.isNotEmpty() || d <= today + 1) {
                    val named = d == today || d == today + 1
                    add(
                        AgendaSection(
                            id = "d-$d",
                            kind = AgendaKind.DAY,
                            firstDay = d,
                            lastDay = d,
                            title = when (d) {
                                today -> "Today"
                                today + 1 -> "Tomorrow"
                                else -> CivilDate.shortLabel(d)
                            },
                            subtitle = if (named) CivilDate.longLabel(d) else countsLine(day.events, day.fixtures, day.tasks) ?: "Nothing planned",
                            allDay = day.allDay,
                            ended = day.ended,
                            rows = day.rows,
                            emptyLine = if (day.count == 0 && named) "Nothing planned" else null,
                            hidden = day.hidden,
                        ),
                    )
                    i++
                } else {
                    var j = i
                    while (j + 1 < dayData.size && dayData[j + 1].count == 0 && dayData[j + 1].hidden.isEmpty()) j++
                    val last = dayData[j].epochDay
                    add(AgendaSection("f-$d", AgendaKind.FREE, d, last, spanLabel(d, last), "Nothing planned", emptyList(), emptyList(), emptyList(), null))
                    i = j + 1
                }
            }
        }

        val byDay = dayData.associateBy { it.epochDay }
        val firstWeek = today - (CivilDate.isoDayOfWeek(today) - 1)
        val weeks = generateSequence(firstWeek) { it + 7 }.takeWhile { it <= lastDay }.map { ws ->
            WeekStrip(
                startEpochDay = ws,
                title = when (ws) {
                    firstWeek -> "This week"
                    firstWeek + 7 -> "Next week"
                    else -> rangeLabel(ws, ws + 6)
                },
                range = rangeLabel(ws, ws + 6),
                days = (ws until ws + 7).map { d ->
                    val data = byDay[d]
                    val section = sections.firstOrNull { d in it.firstDay..it.lastDay }
                    val ymd = CivilDate.fromEpochDay(d)
                    val counts = data?.let { countsLine(it.events, it.fixtures, it.tasks) }
                    DayPill(
                        epochDay = d,
                        letter = CivilDate.DAY_LONG[CivilDate.isoDayOfWeek(d) - 1].take(1),
                        number = ymd.day.toString(),
                        isToday = d == today,
                        inRange = data != null,
                        dots = data?.count?.coerceAtMost(MAX_DOTS) ?: 0,
                        sectionId = section?.id,
                        accessibilityLabel = buildString {
                            append(CivilDate.longLabel(d))
                            if (d == today) append(", today")
                            when {
                                data == null -> {}
                                counts == null -> append(", nothing planned")
                                else -> append(", ").append(counts)
                            }
                        },
                    )
                },
            )
        }.toList()

        val totalEvents = shown.filter { e -> dayData.any { e.overlaps(window(it.epochDay, calendar)) } }
        val summary = listOfNotNull(
            when (totalEvents.size) {
                0 -> "Nothing in your calendars for the next $days days"
                1 -> "1 event in the next $days days"
                else -> "${totalEvents.size} events in the next $days days"
            },
            totalEvents.count { it.isFixture }.takeIf { it > 0 }?.let { plural(it, "fixture") },
        ).joinToString(" · ")

        return CalendarView(CivilDate.longLabel(today), today, weeks, sections, summary)
    }

    /** The local day [epochDay] as a window (DST-safe: its own midnight to the next). */
    fun window(epochDay: Long, calendar: LocalCalendar): DayWindow {
        val start = calendar.toEpochMs(epochDay, 0)
        val end = calendar.toEpochMs(epochDay + 1, 0)
        return DayWindow(start, end, epochDay * CivilDate.DAY_MS - start)
    }

    /** "2 events · 1 fixture · 1 task"; null when there's nothing. */
    fun countsLine(events: Int, fixtures: Int, tasks: Int): String? = buildList {
        if (events > 0) add(plural(events, "event"))
        if (fixtures > 0) add(plural(fixtures, "fixture"))
        if (tasks > 0) add(plural(tasks, "task"))
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** "5–11 Oct" · "28 Sep – 4 Oct" · "29 Dec – 4 Jan" */
    fun rangeLabel(first: Long, last: Long): String {
        val a = CivilDate.fromEpochDay(first)
        val b = CivilDate.fromEpochDay(last)
        val mb = Recurrence.MONTH_SHORT[b.month - 1]
        return if (a.month == b.month && a.year == b.year) "${a.day}–${b.day} $mb"
        else "${a.day} ${Recurrence.MONTH_SHORT[a.month - 1]} – ${b.day} $mb"
    }

    /** A free stretch: "Thu 8 Oct" · "Thu 8 – Sat 10 Oct" · "Wed 30 Sep – Fri 2 Oct". */
    fun spanLabel(first: Long, last: Long): String {
        if (first == last) return CivilDate.shortLabel(first)
        val a = CivilDate.fromEpochDay(first)
        val b = CivilDate.fromEpochDay(last)
        val da = LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(first) - 1]
        return if (a.month == b.month && a.year == b.year) "$da ${a.day} – ${CivilDate.shortLabel(last)}"
        else "${CivilDate.shortLabel(first)} – ${CivilDate.shortLabel(last)}"
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    private fun eventDetail(e: CalendarEvent): String? = listOfNotNull(
        e.location,
        when (e.provider) {
            "microsoft" -> "Outlook"
            "fixtures" -> "Fixture"
            else -> null // Google is the default calendar and needs no label
        },
    ).joinToString(" · ").ifEmpty { null }

    private fun taskDetail(t: Task, epochDay: Long): String? = buildList {
        t.estimateMinutes?.let { add("$it min") }
        t.checklist.takeIf { it.isNotEmpty() }?.let { cl -> add("${cl.count { it.checked }}/${cl.size}") }
        t.repeatMeta(epochDay)?.let { add("↻ $it") }
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
