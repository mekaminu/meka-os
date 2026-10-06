package os.meka.core.domain

/** What a row of Today's timeline is. */
enum class TimelineKind { EVENT, TASK, GAP, NOW }

/**
 * One row of Today's timeline (calendar redesign, slice 1). Events and planned tasks share one list in time order,
 * with a "now" line and the free gaps between things.
 */
data class TimelineRow(
    /** Stable across re-projections: "e-<event>", "t-<task>", "gap-<the row after it>" (stable while now moves), "now". */
    val id: String,
    val kind: TimelineKind,
    /** "09:30–10:00" · "Until 10:00" (started before today) · "14:00" (a planned task) · "11:00" (a gap) · "14:32" (now). */
    val time: String,
    /** The event or task title; "1 h 30 free" for a gap; "Now" for the now line. */
    val title: String,
    /** "Camp Nou · Outlook" for an event; "30 min · 1/3 · ↻ Every weekday" for a task; null when there's nothing to say. */
    val detail: String?,
    val task: Task?,
    val event: CalendarEvent?,
    /** An event happening right now. */
    val running: Boolean,
    val startMs: Long,
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

data class DayTimeline(
    /** "Tuesday 6 October" */
    val dateLabel: String,
    /** All-day events, shown as chips above the timeline. */
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
) {
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
    ): DayTimeline {
        fun hhmm(ms: Long) = LocalClock.formatMinute(calendar.minuteOfDay(ms))

        val dayEvents = events.filter { it.overlaps(today) }
        val allDay = dayEvents.filter { it.allDay }.sortedBy { it.title }

        data class Item(val row: TimelineRow, val start: Long, val end: Long, val isEvent: Boolean)
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
        }.sortedWith(compareBy<Item> { it.start }.thenBy { !it.isEvent }.thenBy { it.row.title })

        val ended = items.filter { it.isEvent && it.end <= nowMs }
        val endedIds = ended.map { it.row.id }.toSet()
        val live = items.filter { it.row.id !in endedIds }

        val rows = buildList {
            val started = live.filter { it.start <= nowMs }
            val ahead = live.filter { it.start > nowMs }
            started.forEach { add(it.row) }
            if (items.isNotEmpty()) {
                add(TimelineRow("now", TimelineKind.NOW, hhmm(nowMs), "Now", null, null, null, false, nowMs))
            }
            // Free time counts from now, past anything still running.
            var cursor = (started.filter { it.isEvent }.map { it.end } + nowMs).max()
            ahead.forEach { item ->
                val free = ((item.start - cursor) / MIN_MS).toInt()
                if (free >= MIN_GAP_MIN) {
                    add(TimelineRow("gap-" + item.row.id, TimelineKind.GAP, hhmm(cursor), freeLabel(free), null, null, null, false, cursor))
                }
                add(item.row)
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

        return DayTimeline(
            dateLabel = CivilDate.longLabel(today.epochDay),
            allDay = allDay,
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
