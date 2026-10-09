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
    /** The Day ring at the top of Today (the opening moment): the day's arcs, the now needle, "3 h 45 free · 4 to do". */
    val dayRing: DayRing = DayRing.EMPTY,
    /**
     * The live tiles under the Day ring (the opening moment, part 2): next event countdown, a running fast, habits done
     * today, renewals due ([DayTileRules]). Filled in by the facade, which holds the fasting, goals and lists views.
     */
    val dayTiles: List<DayTile> = emptyList(),
    /**
     * The 12-hour watch face in Today's header (Fold review 2026-10-09 07:26, item 2): the next 12 hours of events on
     * its rim ([WatchFaceRules]). Filled in by the facade, which sees tomorrow's events and work too.
     */
    val watchFace: WatchFace = WatchFace.EMPTY,
    /**
     * Where "Move to later" would put the Up next task when it's late ([LateTaskRules.laterSlot]); null when it isn't
     * late or today has no room left.
     */
    val upNextLaterMs: Long? = null,
) {
    /** The Up next task is a planned task whose time has gone by ([LateTaskRules.isLate]). */
    fun upNextLate(nowMs: Long): Boolean = upNext?.let { LateTaskRules.isLate(it, nowMs) } ?: false

    /** Timed events that have not ended yet: what's still ahead of you today. */
    fun upcomingEvents(nowMs: Long): List<CalendarEvent> = events.filter { !it.allDay && it.endAtMs > nowMs }

    /** "You're clear." — nothing needs attention and nothing is left today. */
    val isClear: Boolean get() = needsYou.isEmpty() && upNext == null && yourDay.isEmpty()

    /**
     * The line Today shows when no task is left (Today clarity, Meka 2026-10-07: "You're clear." showed beside all-day
     * items). "You're clear." only when nothing at all is left today: no tasks, no timed event still to come and no
     * all-day item; "Nothing else timed today" when all-day items remain; null while a task or a timed event is left
     * (the timeline shows those). Events that have ended don't count: they're done.
     */
    val clearLine: String?
        get() = when {
            !isClear || timeline.hasEventsAhead -> null
            timeline.allDay.isNotEmpty() -> NOTHING_ELSE_TIMED
            else -> CLEAR
        }

    /** [clearLine] is "You're clear." (shown large); "Nothing else timed today" is a quieter line. */
    val isAllClear: Boolean get() = clearLine == CLEAR

    companion object {
        const val MAX_NEEDS_YOU = 5
        const val CLEAR = "You're clear."
        const val NOTHING_ELSE_TIMED = "Nothing else timed today"
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
        /** Today's booked sessions still to come or on now ([SessionsView.todayBlocks]), for the timeline. */
        sessions: List<BookedSession> = emptyList(),
        /** Work hours ([WorkHours]): a quiet block on the timeline and not free time on the ring. Null: none shown. */
        work: WorkHours? = null,
    ): Today {
        val workBlocks = work?.blocks(today.epochDay, calendar).orEmpty()
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

        // A planned task whose time has come leads (the earliest, late or not: Fold review 2026-10-09 13:45, item 3),
        // then the next planned one, then anytime.
        val upNext = scheduledToday.firstOrNull() ?: unscheduled.firstOrNull()
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
            sessions = sessions,
            work = workBlocks,
        )
        val ring = DayRingRules.build(
            planned = (needs.map { it.task } + listOfNotNull(upNext) + yourDay).filter { it.scheduledAtMs != null },
            events = todaysEvents,
            sessions = sessions,
            toDo = needs.size + (if (upNext != null) 1 else 0) + yourDay.size,
            nowMs = nowMs,
            today = today,
            calendar = calendar,
            work = workBlocks,
        )
        val later = upNext?.takeIf { LateTaskRules.isLate(it, nowMs) }?.let { t ->
            LateTaskRules.laterSlot(t, scheduledToday, todaysEvents, sessions, nowMs, today)
        }
        return Today(needs, upNext, yourDay, doneToday, todaysEvents, timeline, ring, upNextLaterMs = later)
    }
}
