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
    /** School rhythm, slice 2: "Theo: PE today" under the weather in Today's header ([SchoolRules.todayLine]); set by the facade. */
    val school: SchoolLine? = null,
    /** Meal plan, slice 1: "Dinner tonight: Chilli" in Today's header from 15:00 to 21:00 ([MealRules.tonightLine]); set by the facade. */
    val dinner: MealLine? = null,
    /** Date night, slice 1: "Date night tonight from 19:00" in Today's header on the day ([DateNightRules.todayLine]); set by the facade. */
    val dateNight: DateNightLine? = null,
    /** Life admin hour, slice 1: "Life admin hour at 10:00 · 4 jobs" on the hour's day ([LifeAdminRules.todayLine]); set by the facade. */
    val lifeAdmin: LifeAdminLine? = null,
    /** Train-time queue, slice 1: "For the train · 2 articles · 1 podcast" on an office day's commute ([QueueRules.trainLine]); set by the facade. */
    val trainQueue: QueueLine? = null,
    /** Sunday week ahead, slice 1: "Your week ahead · 4 office days · 2 matches" on Sunday from 18:00 ([WeekAheadRules.todayLine]); set by the facade. */
    val weekAhead: WeekAheadToday? = null,
    /** Bin days, slice 1: "Black bin and food caddy out tonight" the evening before from 16:00 ([BinRules.tonightLine]); set by the facade. */
    val bins: BinLine? = null,
    /** Gift planner, slice 1: "Mum's birthday today · turns 60", "Order Mum's gift today · birthday Sat 31 Oct" ([GiftRules.todayLine]); set by the facade. */
    val gifts: GiftLine? = null,
    /** Travel mode, slice 1: "Barcelona in 3 days · Flight BA478 Sat 07:40", "Barcelona · day 2 of 5 · …" ([TripRules.todayLine]); set by the facade. */
    val trip: TripLine? = null,
    /** Inbox autopilot, slice 3g: "2 parcels arriving today · Amazon, DPD" from shipping emails ([ParcelRules.todayLine]); set by the facade. */
    val parcels: ParcelLine? = null,
    /** Inbox fixes, slice 7: "Personal · 412 unread · 380 are newsletters, promos or receipts Navilife can tidy" ([MailUnreadRules.todayLine]); set by the facade. */
    val mailUnread: MailUnreadLine? = null,
    /** Drafted replies, slice 2: "3 emails need a reply · 2 drafted" while any does ([MailAutoDraftRules.todayLine]); set by the facade. */
    val mailReplies: MailRepliesLine? = null,
    /** Weekly reflection, slice 2: "This week's one thing · Run 5k" through the week (lit on Monday, [ReflectionRules.weekLine]); set by the facade. */
    val reflection: ReflectionWeekLine? = null,
    /**
     * Needs you's Coming up (Fold review 2026-10-12 17:56, item 3): the next two task reminders this week, shown under
     * the empty line ([NeedsYouMeanwhileRules.comingUp]); set by the facade, which sees every task.
     */
    val comingUp: List<MeanwhileLine> = emptyList(),
    /** Meeting prep, slice 1: from 15 min before a meeting with other people, who's coming and what's open ([MeetingPrepRules.card]); set by the facade. */
    val meetingPrep: MeetingPrep? = null,
    /** Trading tracker, slice 6: "Trading · up today · 2 positions open", no amounts ([TradingTodayRules.todayLine]); set by the facade. */
    val trading: TradingTodayLine? = null,
    /**
     * Roll-over (Week board, slice 1): open tasks planned for an earlier day and not done, oldest first, shown at the top
     * of Today under "From yesterday" ([RollOverRules]); not in Needs you, Up next or the timeline.
     */
    val fromYesterday: List<CarriedTask> = emptyList(),
    /** Week board item (10): the first day stacking up within two days ("Thursday is stacking up: …") with its fixes. */
    val pressure: DayPressure? = null,
) {
    /** The Up next task is a planned task whose time has gone by ([LateTaskRules.isLate]). */
    fun upNextLate(nowMs: Long): Boolean = upNext?.let { LateTaskRules.isLate(it, nowMs) } ?: false

    /** Timed events that have not ended yet: what's still ahead of you today. */
    fun upcomingEvents(nowMs: Long): List<CalendarEvent> = events.filter { !it.allDay && it.endAtMs > nowMs }

    /** "You're clear." — nothing needs attention and nothing is left today. */
    val isClear: Boolean get() = needsYou.isEmpty() && upNext == null && yourDay.isEmpty() && fromYesterday.isEmpty()

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
        /** Whom Meka set for each club series ([EventMarks.clubChildren]): a session's row names the child. */
        children: Map<String, String> = emptyMap(),
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

        val carried = RollOverRules.carried(tasks, needsIds, today, calendar::epochDayOf)
        val rest = open.filter { it.id !in needsIds }
        val scheduledToday = rest.filter { it.scheduledAtMs != null && it.scheduledAtMs in today }.sortedBy { it.scheduledAtMs }
        // A task parked in a week on the Board with no day yet (Week board slice 4) waits there, not on Today.
        val unscheduled = rest.filter { it.scheduledAtMs == null && (it.dueAtMs == null || it.dueAtMs in today) && !WeekBoardRules.parked(it) }
            // Today follows the Board (Week board slice 5): cards he placed on the Board's Today column first, in his order.
            .sortedWith(BoardTodayRules.anytimeOrder)

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
            anytimeLeft = unscheduled.size,
            children = children,
            commute = work?.let { CommuteRules.bands(it, today.epochDay, calendar) }.orEmpty(),
        )
        val ring = DayRingRules.build(
            planned = (needs.map { it.task } + listOfNotNull(upNext) + yourDay).filter { it.scheduledAtMs != null },
            events = todaysEvents,
            sessions = sessions,
            toDo = needs.size + (if (upNext != null) 1 else 0) + yourDay.size + carried.size,
            nowMs = nowMs,
            today = today,
            calendar = calendar,
            work = workBlocks,
        )
        val later = upNext?.takeIf { LateTaskRules.isLate(it, nowMs) }?.let { t ->
            LateTaskRules.laterSlot(t, scheduledToday, todaysEvents, sessions, nowMs, today)
        }
        return Today(needs, upNext, yourDay, doneToday, todaysEvents, timeline, ring, upNextLaterMs = later, fromYesterday = carried)
    }
}
