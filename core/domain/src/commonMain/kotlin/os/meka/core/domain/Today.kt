package os.meka.core.domain

/** Why an item needs the user. Kept small on purpose: Needs You shows at most [Today.MAX_NEEDS_YOU]. */
enum class NeedsYouReason { CONFLICT, OVERDUE, DUE_TODAY_UNSCHEDULED }

data class NeedsYouItem(val task: Task, val reason: NeedsYouReason)

data class Today(
    val needsYou: List<NeedsYouItem>,
    val upNext: Task?,
    val yourDay: List<Task>,
    val doneToday: List<Task>,
    /** Today's calendar events from all connected accounts: all-day first, then by start time. */
    val events: List<CalendarEvent> = emptyList(),
    /**
     * One timeline for the screen: the date, all-day events, events and planned tasks (not Needs you or Up next) in
     * time order (Up next's planned task included, Needs you not) with a now line and free gaps, finished events folded, and "Anytime today" for tasks with no time.
     */
    val timeline: DayTimeline = DayTimeline.EMPTY,
) {
    /** Timed events that have not ended yet: what's still ahead of you today. */
    fun upcomingEvents(nowMs: Long): List<CalendarEvent> = events.filter { !it.allDay && it.endAtMs > nowMs }

    /** "You're clear." — nothing needs attention and nothing is left today. */
    val isClear: Boolean get() = needsYou.isEmpty() && upNext == null && yourDay.isEmpty()

    companion object {
        const val MAX_NEEDS_YOU = 5
    }
}

/** Local-day boundaries in epoch ms for the user's current timezone. Supplied by the platform. */
data class DayWindow(
    val startMs: Long,
    val endMs: Long,
    /** The timezone's UTC offset at [startMs]; lets all-day events (stored as UTC dates) land on the right day. */
    val utcOffsetMs: Long = 0,
) {
    operator fun contains(t: Long) = t in startMs until endMs

    /** The local day as an epoch day (repeating tasks belong to local days). */
    val epochDay: Long get() = (startMs + utcOffsetMs).floorDiv(CivilDate.DAY_MS)
}

/**
 * Deterministic Today projection (M0). No AI, no invented work: only what the graph contains.
 * Ranking inside Needs You: conflicts, then overdue (oldest first), then due-today-unscheduled.
 */
object TodayProjection {
    fun project(
        tasks: List<Task>,
        nowMs: Long,
        today: DayWindow,
        events: List<CalendarEvent> = emptyList(),
        calendar: LocalCalendar = LocalCalendar.fixedOffset(today.utcOffsetMs),
    ): Today {
        val open = tasks.filter { (it.lifecycle == Lifecycle.ACTIVE || it.lifecycle == Lifecycle.INBOX) && !it.waitsForItsDay(today.epochDay) }

        val needs = buildList {
            open.filter { it.hasConflict }.forEach { add(NeedsYouItem(it, NeedsYouReason.CONFLICT)) }
            open.filter { !it.hasConflict && it.dueAtMs != null && it.dueAtMs < nowMs }
                .sortedBy { it.dueAtMs }
                .forEach { add(NeedsYouItem(it, NeedsYouReason.OVERDUE)) }
            open.filter {
                !it.hasConflict && it.dueAtMs != null && it.dueAtMs >= nowMs && it.dueAtMs in today && it.scheduledAtMs == null
            }.sortedBy { it.dueAtMs }.forEach { add(NeedsYouItem(it, NeedsYouReason.DUE_TODAY_UNSCHEDULED)) }
        }.take(Today.MAX_NEEDS_YOU)
        val needsIds = needs.map { it.task.id }.toSet()

        val rest = open.filter { it.id !in needsIds }
        val scheduledToday = rest.filter { it.scheduledAtMs != null && it.scheduledAtMs in today }.sortedBy { it.scheduledAtMs }
        val unscheduled = rest.filter { it.scheduledAtMs == null && (it.dueAtMs == null || it.dueAtMs in today) }
            .sortedWith(compareByDescending<Task> { it.priority }.thenBy { it.createdAtMs })

        val upNext = scheduledToday.firstOrNull { it.scheduledAtMs!! >= nowMs } ?: unscheduled.firstOrNull()
        val yourDay = (scheduledToday + unscheduled).filter { it.id != upNext?.id }
        val doneToday = tasks.filter { it.lifecycle == Lifecycle.DONE && it.completedAtMs != null && it.completedAtMs in today }
            .sortedByDescending { it.completedAtMs }

        val todaysEvents = events.filter { it.overlaps(today) }
            .sortedWith(compareByDescending<CalendarEvent> { it.allDay }.thenBy { it.startAtMs }.thenBy { it.title })

        val timeline = TimelineRules.build(
            // The planned Up next task stays in the timeline too, so the free time around it is right.
            planned = (listOfNotNull(upNext) + yourDay).filter { it.scheduledAtMs != null },
            anytime = yourDay.filter { it.scheduledAtMs == null },
            events = todaysEvents,
            nowMs = nowMs,
            today = today,
            calendar = calendar,
        )
        return Today(needs, upNext, yourDay, doneToday, todaysEvents, timeline)
    }
}
