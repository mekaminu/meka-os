package os.meka.core.domain

/** What a row of Today's timeline is. */
enum class TimelineKind {
    EVENT, TASK, GAP, NOW,

    /** A booked session (the Gym, [SessionRules]) still to come or on now; Today's session card answers it. */
    SESSION,

    /**
     * Work hours (Fold review 2026-10-08): a quiet block "Work 09:00–17:30", not an event. It takes its time out of the
     * free gaps and leaves the timeline once it's over.
     */
    WORK,
}

/**
 * One row of Today's timeline (calendar redesign, slice 1). Events and planned tasks share one list in time order,
 * with a "now" line and the free gaps between things.
 */
data class TimelineRow(
    /**
     * Stable across re-projections: "e-<event>", "t-<task>", "s-<habit>" (a booked session), "w-<start minute>" (work),
     * "gap-<the row after it>" (stable while now moves), "now".
     */
    val id: String,
    val kind: TimelineKind,
    /**
     * "09:30–10:00" · "Until 10:00" (started before today) · "14:00" (a planned task) · "11:00" (a gap) · "14:32" (now) ·
     * "09:00–17:30" (work).
     */
    val time: String,
    /** The event or task title; "1 h 30 free" / "1 h free before work" / "30 min free after work" for a gap; "Now"; "Work". */
    val title: String,
    /**
     * "Camp Nou · Outlook" for an event; "30 min · 1/3 · ↻ Every weekday" for a task; "Leave by 17:30" or
     * "Now · until 18:45" for a session; "Now · until 17:30" while at work; null when there's nothing to say.
     */
    val detail: String?,
    val task: Task?,
    val event: CalendarEvent?,
    /** An event happening right now. */
    val running: Boolean,
    val startMs: Long,
    /** The booked session, for a [TimelineKind.SESSION] row. */
    val session: BookedSession? = null,
)

/** The next event within the hour, shown in Up next: "Call with Tunde in 25 min". */
data class UpNextEvent(
    val event: CalendarEvent,
    /** "Call with Tunde in 25 min" · "Standup in 1 h" */
    val line: String,
    val minutes: Int,
    /** "09:30–10:00 · Camp Nou" */
    val detail: String,
)

/**
 * One row of Today's "All day" group (Today clarity, 2026-10-07): the title with its calendar under it, and whether it
 * reads like a to-do ("Check if to pay for…"), which offers "Make it a task".
 */
data class AllDayItem(
    val event: CalendarEvent,
    /**
     * "Personal" · "Fixtures" · "Personal · until Thu 8 Oct"; "until Thu 8 Oct" alone when the group's label already
     * names the calendar (all-day polish); null when there's nothing to say.
     */
    val line: String?,
    val todo: Boolean,
    /** The calendar it comes from ([CalendarRules.key]), for "Hide <calendar> from Today". */
    val calendarKey: String = CalendarRules.key(event),
    /** "Timestripe" · "Fixtures" · "Outlook" · "Google Calendar": the calendar's name in menus. */
    val calendarLabel: String = CalendarRules.label(event),
)

data class DayTimeline(
    /** "Tuesday 6 October" */
    val dateLabel: String,
    /** All-day events touching today, by title. */
    val allDay: List<CalendarEvent>,
    /** Events that have ended, folded away under [earlierLabel]. */
    val earlier: List<TimelineRow>,
    /** "3 earlier" · "1 earlier"; null when nothing has ended yet. */
    val earlierLabel: String?,
    /** What's still to come (plus planned tasks whose time has passed but aren't done), with the now line and gaps. */
    val rows: List<TimelineRow>,
    /** Open tasks for today with no time: "Anytime today". */
    val anytime: List<Task>,
    val nextEvent: UpNextEvent?,
    /** [allDay] as rows of the "All day" group at the top of the timeline. */
    val allDayItems: List<AllDayItem> = emptyList(),
    /** The group's label, shown once above its rows: "All day", or "All day · Timestripe" when they share a calendar. */
    val allDayLabel: String = AllDayRules.LABEL,
) {
    /** Timed events (or booked sessions) still to come or running today. */
    val hasEventsAhead: Boolean get() = rows.any { it.kind == TimelineKind.EVENT || it.kind == TimelineKind.SESSION }

    /** Nothing timed, nothing all-day, nothing ended: the timeline section can be left out. */
    val hasTimedOrAllDay: Boolean get() = allDay.isNotEmpty() || earlier.isNotEmpty() || rows.isNotEmpty()

    companion object {
        val EMPTY = DayTimeline("", emptyList(), emptyList(), null, emptyList(), emptyList(), null)
    }
}

/**
 * Builds Today's timeline (non-AI). Pure, unit-tested.
 *
 * - Timed events touching today and today's planned tasks merge in time order (an event that began before today is
 *   keyed at the start of the day and says "Until 10:00"); at the same minute, events come first.
 * - Events that have ended fold into "N earlier". Planned tasks never fold: until done they still need doing.
 * - The now line sits after everything that has started and before what's still to come.
 * - From now on, a free stretch of at least [MIN_GAP_MIN] minutes between things shows as "1 h 30 free" (rounded down
 *   to 5 minutes so it doesn't tick every minute). A planned task with no estimate takes [DEFAULT_TASK_MIN] minutes.
 * - The next event starting within [UP_NEXT_WINDOW_MIN] minutes is offered to Up next.
 * - Today's booked sessions still to come or on now (the Gym, [SessionRules]) sit in time order like events, "Gym · Push"
 *   with "Leave by 17:30" (or "Now · until 18:45"), and take their time out of the free gaps. Once a session is over
 *   (or answered) it leaves the timeline: Today's session card asks "Did you go?".
 * - Work hours on a work day ([WorkHours], Fold review 2026-10-08) sit in time order as one quiet "Work" row
 *   ("09:00–17:30"); they take their time out of the free gaps (an event during work makes no gap), the gap before
 *   reads "1 h free before work" and the one after "30 min free after work". Once work is over the row leaves (it
 *   isn't folded into "earlier"). Work isn't offered to Up next and doesn't count as an event ahead.
 */
object TimelineRules {
    const val MIN_GAP_MIN = 30
    const val DEFAULT_TASK_MIN = 30
    const val UP_NEXT_WINDOW_MIN = 60
    private const val MIN_MS = 60_000L

    fun build(
        planned: List<Task>,
        anytime: List<Task>,
        events: List<CalendarEvent>,
        nowMs: Long,
        today: DayWindow,
        calendar: LocalCalendar,
        sessions: List<BookedSession> = emptyList(),
        /** Today's work blocks ([WorkHours.blocks]); none on a day off. */
        work: List<WorkBlock> = emptyList(),
    ): DayTimeline {
        fun hhmm(ms: Long) = LocalClock.formatMinute(calendar.minuteOfDay(ms))

        val dayEvents = events.filter { it.overlaps(today) }
        val allDay = dayEvents.filter { it.allDay }.sortedBy { it.title }

        /**
         * [isEvent]: an event, session or work, which comes first at the same minute and takes time from gaps; events
         * and sessions fold once ended, work just leaves.
         */
        data class Item(val row: TimelineRow, val start: Long, val end: Long, val isEvent: Boolean) {
            val isWork: Boolean get() = row.kind == TimelineKind.WORK
        }
        val items = buildList {
            dayEvents.filter { !it.allDay }.forEach { e ->
                val start = maxOf(e.startAtMs, today.startMs)
                val time = if (e.startAtMs < today.startMs) "Until ${hhmm(e.endAtMs)}" else "${hhmm(e.startAtMs)}–${hhmm(e.endAtMs)}"
                val detail = listOfNotNull(e.location, providerLabel(e.provider)).joinToString(" · ").ifEmpty { null }
                val running = e.startAtMs <= nowMs && e.endAtMs > nowMs
                add(Item(TimelineRow("e-" + e.id, TimelineKind.EVENT, time, e.title, detail, null, e, running, start), start, e.endAtMs, true))
            }
            planned.filter { it.scheduledAtMs != null && it.scheduledAtMs in today }.forEach { t ->
                val start = t.scheduledAtMs!!
                val detail = taskDetail(t, today.epochDay)
                val end = start + (t.estimateMinutes ?: DEFAULT_TASK_MIN) * MIN_MS
                add(Item(TimelineRow("t-" + t.id, TimelineKind.TASK, hhmm(start), t.title, detail, t, null, false, start), start, end, false))
            }
            sessions.filter { it.startMs in today && it.endMs > nowMs }.forEach { s ->
                val running = s.startMs <= nowMs
                val title = listOfNotNull(s.title, s.label).joinToString(" · ")
                val detail = if (running) "Now · until ${hhmm(s.endMs)}"
                else "Leave by ${hhmm(s.startMs - SessionRules.BUFFER_MIN * MIN_MS)}"
                val row = TimelineRow("s-" + s.habitId, TimelineKind.SESSION, "${hhmm(s.startMs)}–${hhmm(s.endMs)}", title, detail,
                    null, null, running, s.startMs, s)
                add(Item(row, s.startMs, s.endMs, true))
            }
            work.filter { it.endMs > today.startMs && it.startMs < today.endMs && it.endMs > it.startMs }.forEach { w ->
                val running = w.startMs <= nowMs && w.endMs > nowMs
                val row = TimelineRow("w-${w.startMinute}", TimelineKind.WORK, w.label, WorkHours.TITLE,
                    if (running) "Now · until ${LocalClock.formatMinute(w.endMinute)}" else null, null, null, running, w.startMs)
                add(Item(row, w.startMs, w.endMs, true))
            }
        }.sortedWith(compareBy<Item> { it.start }.thenBy { !it.isEvent }.thenBy { it.isWork }.thenBy { it.row.title })

        val ended = items.filter { it.isEvent && !it.isWork && it.end <= nowMs }
        val endedIds = ended.map { it.row.id }.toSet()
        val live = items.filter { it.row.id !in endedIds && !(it.isWork && it.end <= nowMs) }

        val rows = buildList {
            val started = live.filter { it.start <= nowMs }
            val ahead = live.filter { it.start > nowMs }
            started.forEach { add(it.row) }
            if (items.any { !it.isWork } || live.isNotEmpty()) {
                add(TimelineRow("now", TimelineKind.NOW, hhmm(nowMs), "Now", null, null, null, false, nowMs))
            }
            // Free time counts from now, past anything still running (work included).
            val runningEnds = started.filter { it.isEvent }.map { it.end }
            var cursor = (runningEnds + nowMs).max()
            // The gap after work says so: it starts where work ended.
            var afterWork = started.any { it.isWork && it.end == cursor }
            ahead.forEach { item ->
                val free = ((item.start - cursor) / MIN_MS).toInt()
                if (free >= MIN_GAP_MIN) {
                    val label = when {
                        item.isWork -> "${freeLabel(free)} before work"
                        afterWork -> "${freeLabel(free)} after work"
                        else -> freeLabel(free)
                    }
                    add(TimelineRow("gap-" + item.row.id, TimelineKind.GAP, hhmm(cursor), label, null, null, null, false, cursor))
                }
                add(item.row)
                if (item.end >= cursor) afterWork = item.isWork
                cursor = maxOf(cursor, item.end)
            }
        }

        val next = dayEvents.filter { !it.allDay && it.startAtMs > nowMs && it.startAtMs - nowMs <= UP_NEXT_WINDOW_MIN * MIN_MS }
            .minWithOrNull(compareBy<CalendarEvent> { it.startAtMs }.thenBy { it.title })
            ?.let { e ->
                val minutes = ((e.startAtMs - nowMs + MIN_MS - 1) / MIN_MS).toInt()
                val detail = listOfNotNull("${hhmm(e.startAtMs)}–${hhmm(e.endAtMs)}", e.location).joinToString(" · ")
                UpNextEvent(e, "${e.title} in ${inLabel(minutes)}", minutes, detail)
            }

        val group = AllDayRules.group(allDay, today.epochDay)
        return DayTimeline(
            dateLabel = CivilDate.longLabel(today.epochDay),
            allDay = allDay,
            allDayItems = group.items,
            allDayLabel = group.label,
            earlier = ended.map { it.row },
            earlierLabel = if (ended.isEmpty()) null else "${ended.size} earlier",
            rows = rows,
            anytime = anytime,
            nextEvent = next,
        )
    }

    /** "45 min free" · "2 h free" · "1 h 30 free", rounded down to 5 minutes. */
    fun freeLabel(minutes: Int): String {
        val m = minutes - minutes % 5
        return when {
            m < 60 -> "$m min free"
            m % 60 == 0 -> "${m / 60} h free"
            else -> "${m / 60} h ${(m % 60).toString().padStart(2, '0')} free"
        }
    }

    /** "25 min" · "1 h" */
    fun inLabel(minutes: Int): String = if (minutes >= 60) "${minutes / 60} h" else "$minutes min"

    private fun taskDetail(t: Task, epochDay: Long): String? = buildList {
        t.estimateMinutes?.let { add("$it min") }
        t.checklist.takeIf { it.isNotEmpty() }?.let { cl -> add("${cl.count { it.checked }}/${cl.size}") }
        t.repeatMeta(epochDay)?.let { add("↻ $it") }
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    private fun providerLabel(p: String): String? = when (p) {
        "microsoft" -> "Outlook"
        "fixtures" -> "Fixtures"
        else -> null // Google is the default calendar and needs no label
    }
}

/**
 * Today's "All day" group (Today clarity, Meka 2026-10-07: the chip row cut titles off and didn't say it scrolled).
 * Non-AI, pure, unit-tested.
 *
 * - One row each: the title, and under it the calendar's name (Google's calendar name, else "Outlook" / "Fixtures")
 *   and, for an entry running past today, "until Thu 8 Oct".
 * - At most [SHOWN] rows, then "+2 more", which unfolds the rest.
 * - The group says "All day" once, above its rows (all-day polish, Meka 2026-10-07 22:37). When every entry comes from
 *   one calendar, the label names it ("All day · Timestripe") and the rows drop the repeated caption; with calendars
 *   mixed each row keeps its own.
 * - An entry that reads like a to-do gets "Make it a task": its first word is one of [TODO_VERBS] ("Check if to pay
 *   for…", "Pay council tax", "Call the garage"), or it starts "To do", "Todo" or "Reminder". Fixtures never do.
 */
object AllDayRules {
    const val SHOWN = 3
    const val LABEL = "All day"

    /** The "All day" group: its label and rows. */
    data class Group(val label: String, val items: List<AllDayItem>)

    /** Rows for [events] (already sorted), with the calendar named once in the label when they all share one. */
    fun group(events: List<CalendarEvent>, todayEpochDay: Long): Group {
        val keys = events.map { CalendarRules.key(it) }.distinct()
        val shared = if (keys.size == 1) calendarLabel(events.first()) else null
        if (shared == null) return Group(LABEL, events.map { item(it, todayEpochDay) })
        return Group("$LABEL · $shared", events.map { item(it, todayEpochDay, showCalendar = false) })
    }

    /** First words that make an all-day entry read like something to do. Lower case. */
    val TODO_VERBS = setOf(
        "apply", "arrange", "ask", "book", "buy", "call", "cancel", "chase", "check", "collect", "confirm", "email",
        "file", "fill", "finish", "fix", "follow", "get", "order", "pay", "phone", "pick", "post", "prepare", "print",
        "register", "remember", "renew", "reply", "return", "ring", "schedule", "send", "sign", "sort", "submit",
        "text", "top", "transfer", "update",
    )

    private val PREFIXES = listOf("to do", "to-do", "todo", "reminder")

    fun item(e: CalendarEvent, todayEpochDay: Long, showCalendar: Boolean = true): AllDayItem {
        // All-day bounds are UTC midnights with an exclusive end: the last day is the one before.
        val lastDay = (e.endAtMs - 1).floorDiv(CivilDate.DAY_MS)
        val until = if (lastDay > todayEpochDay) "until ${CivilDate.shortLabel(lastDay)}" else null
        val line = listOfNotNull(if (showCalendar) calendarLabel(e) else null, until).joinToString(" · ").ifEmpty { null }
        return AllDayItem(e, line, !e.isFixture && looksLikeTodo(e.title))
    }

    fun looksLikeTodo(title: String): Boolean {
        val t = title.trim().trimStart('-', '*', '•', '[', ']', ' ').lowercase()
        if (PREFIXES.any { t.startsWith(it) && (t.length == it.length || !t[it.length].isLetter()) }) return true
        val first = t.takeWhile { it.isLetter() }
        return first in TODO_VERBS
    }

    /** The rows to show: all of them when [open] or when there are no more than [SHOWN]; else the first [SHOWN]. */
    fun shown(items: List<AllDayItem>, open: Boolean): List<AllDayItem> =
        if (open || items.size <= SHOWN) items else items.take(SHOWN)

    /** "+2 more" while folded; null when everything is shown. */
    fun moreLabel(items: List<AllDayItem>, open: Boolean): String? =
        if (open || items.size <= SHOWN) null else "+${items.size - SHOWN} more"

    private fun calendarLabel(e: CalendarEvent): String? = when {
        e.isFixture -> "Fixtures"
        !e.calendarName.isNullOrBlank() -> e.calendarName
        e.provider == "microsoft" -> "Outlook"
        else -> null
    }
}
