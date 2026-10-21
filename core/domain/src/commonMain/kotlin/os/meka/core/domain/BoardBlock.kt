package os.meka.core.domain

/**
 * One free stretch in a Board day's timeline (Week board slice 9, item (3)): [startMinute]–[endMinute] past local
 * midnight, on the quarter hour, from the same free time the day's capacity counts. Unfolded on the Fold and on the
 * Mac a card dropped on it is blocked there ([BoardBlockRules.dropSlot]).
 */
data class BoardFreeTime(val startMs: Long, val endMs: Long, val startMinute: Int, val endMinute: Int) {
    val minutes: Int get() = endMinute - startMinute

    /** "10:00–12:00" */
    val label: String get() = "${LocalClock.formatMinute(startMinute)}–${LocalClock.formatMinute(endMinute)}"

    /** "2h free" */
    val lengthLabel: String get() = "${TaskEstimateRules.label(minutes)} free"

    /** Screen readers: "Free from 10:00 to 12:00, drop a task here to block it". */
    val spoken: String get() =
        "Free from ${LocalClock.formatMinute(startMinute)} to ${LocalClock.formatMinute(endMinute)}, drop a task here to block it"

    /** How tall its row draws in the timeline, 0–1 of the tallest (a stretch of [BoardBlockRules.TALL_MIN] or more). */
    val weight: Float get() = (minutes.toFloat() / BoardBlockRules.TALL_MIN).coerceIn(0f, 1f)
}

/** What a card dropped on a free time did: [line] for the bar, [done] when it was blocked (Undo then applies). */
data class FreeTimeDrop(val line: String, val done: Boolean)

/** One start Block time offers on a Board card: [minute] past local midnight, "14:00–14:30". */
data class BlockSlot(val startMs: Long, val minute: Int, val label: String, val spoken: String)

/**
 * Block time on a Board card (Week board and roll-over, slice 6, item (3) time-blocking; Meka approved 2026-10-19
 * 20:36): where the card's task could go in its day as busy time in his calendar, and what his tap will do.
 */
data class BlockOffer(
    val taskId: String,
    val title: String,
    val epochDay: Long,
    /** The block's length: the task's estimate, else Navilife's guess, else [BoardBlockRules.DEFAULT_MIN]. */
    val lengthMin: Int,
    /** Up to [BoardBlockRules.MAX_SLOTS] free starts that day, earliest first; empty when the day is full. */
    val slots: List<BlockSlot>,
    /** The calendar it goes to ("Google"), or null when no account allows editing. */
    val provider: String?,
    /** The task already has a block in his calendar (from Plan my day or an earlier Block time): it moves. */
    val moves: Boolean,
) {
    val canBlock: Boolean get() = provider != null && slots.isNotEmpty()

    /** The menu's heading: "Block time · 30m". */
    val heading: String get() = "${BoardBlockRules.MENU_TITLE} · ${TaskEstimateRules.label(lengthMin)}"

    /** The quiet line under the starts: what a tap does, or why it can't. */
    val line: String get() = when {
        provider == null -> BoardBlockRules.NO_ACCOUNT
        slots.isEmpty() -> BoardBlockRules.NO_ROOM
        moves -> "Moves its block in ${CalendarEditRules.providerName(provider)}"
        else -> "Adds it to ${CalendarEditRules.providerName(provider)} as busy time when you tap a start"
    }
}

/**
 * Time-blocking from the Board (slice 6), non-AI and pure. A card's menu (Fold: tap; Mac: right-click) offers
 * **Block time**: up to [MAX_SLOTS] free starts in the card's day, from the same free time the day's capacity counts
 * ([TaskEstimateRules.freeGaps]: 09:00–21:00, from now on today, less timed events, work and the commute), on the
 * quarter hour, the first start in each free stretch first and then later ones an hour apart, for the task's estimate
 * (else Navilife's guess, else 30 minutes).
 *
 * His tap on a start is the approval (Level 3, ADR-006): the task is planned at that time and its block is added to
 * the calendar Add event remembers (else the first that allows editing) as an ordinary calendar add made for the task
 * (five seconds' Undo, sent by the server, listed in Activity), exactly like Plan my day's "Also add the blocks". A
 * task that already has a block moves it instead. Nothing is written until he taps; a done, repeating, past or
 * unplaced card offers nothing.
 */
object BoardBlockRules {
    const val MENU_TITLE = "Block time"
    const val DEFAULT_MIN = 30
    const val MAX_SLOTS = 4
    const val STEP_MIN = 60
    const val NO_ACCOUNT = "Allow editing on a calendar in Calendars to block time"
    const val NO_ROOM = "No free time left that day"

    /** The block's length for [card]. */
    fun lengthOf(card: BoardCard): Int = card.task.estimateMinutes?.takeIf { it > 0 } ?: card.suggestedMinutes ?: DEFAULT_MIN

    /**
     * The offer for [taskId] on [board], or null when it can't be blocked (not in a day's column, done, repeating, or
     * a day before today). [events] are the board's; [provider] is where the block would go, null for nowhere.
     */
    fun offer(
        board: WeekBoard, taskId: String, nowMs: Long, events: List<CalendarEvent>, calendar: LocalCalendar, work: WorkHours?,
        provider: String?, hasBlock: Boolean, prefs: DayPlanner.Prefs = DayPlanner.Prefs(),
    ): BlockOffer? {
        val day = board.dayOf(taskId) ?: return null
        val card = day.cards.first { it.id == taskId }
        if (card.done || day.isPast || card.task.isRepeating) return null
        val today = calendar.epochDayOf(nowMs)
        val length = lengthOf(card)
        val slots = slots(day.epochDay, today, nowMs, events, calendar, work, length, prefs)
        return BlockOffer(taskId, TaskTitleRules.display(card.task.title), day.epochDay, length, slots, provider, hasBlock)
    }

    /** Free starts on [d] with [lengthMin] of room (see the object's note). */
    fun slots(
        d: Long, today: Long, nowMs: Long, events: List<CalendarEvent>, calendar: LocalCalendar, work: WorkHours?, lengthMin: Int,
        prefs: DayPlanner.Prefs = DayPlanner.Prefs(),
    ): List<BlockSlot> {
        val gaps = TaskEstimateRules.freeGaps(d, today, nowMs, events, calendar, work, prefs)
        val dayEnd = calendar.toEpochMs(d + 1, 0)
        val firsts = mutableListOf<Int>()
        val later = mutableListOf<Int>()
        for (g in gaps) {
            var m = calendar.minuteOfDay(g.startMs)
            if (calendar.toEpochMs(d, m) < g.startMs) m += 1
            m = (m + 14) / 15 * 15
            val endMin = if (g.endMs >= dayEnd) 24 * 60 else calendar.minuteOfDay(g.endMs)
            var first = true
            while (m + lengthMin <= endMin) {
                if (first) firsts += m else later += m
                first = false
                m += STEP_MIN
            }
        }
        val picked = (firsts.take(MAX_SLOTS) + later.sorted()).take(MAX_SLOTS).sorted()
        return picked.map { m ->
            val label = "${LocalClock.formatMinute(m)}–${LocalClock.formatMinute(m + lengthMin)}"
            BlockSlot(calendar.toEpochMs(d, m), m, label, "${LocalClock.formatMinute(m)} to ${LocalClock.formatMinute(m + lengthMin)}")
        }
    }

    /** A day's timeline shows at most this many free stretches (the earliest), each at least [MIN_FREE] long. */
    const val MAX_FREE = 6
    const val MIN_FREE = 15
    /** A free stretch this long or longer draws at full height in the timeline. */
    const val TALL_MIN = 180
    const val FREE_TITLE = "Free"
    const val DROP_HINT = "Drop a task on a free time to block it"

    /**
     * Day [d]'s free stretches for its timeline (slice 9): [TaskEstimateRules.freeGaps] with each start rounded up and
     * each end down to the quarter hour, those of at least [MIN_FREE], the earliest [MAX_FREE]; none before today.
     */
    fun freeTimes(
        d: Long, today: Long, nowMs: Long, events: List<CalendarEvent>, calendar: LocalCalendar, work: WorkHours?,
        prefs: DayPlanner.Prefs = DayPlanner.Prefs(),
    ): List<BoardFreeTime> {
        if (d < today) return emptyList()
        val dayEnd = calendar.toEpochMs(d + 1, 0)
        return TaskEstimateRules.freeGaps(d, today, nowMs, events, calendar, work, prefs).mapNotNull { g ->
            var s = calendar.minuteOfDay(g.startMs)
            if (calendar.toEpochMs(d, s) < g.startMs) s += 1
            s = (s + 14) / 15 * 15
            val rawEnd = if (g.endMs >= dayEnd) 24 * 60 else calendar.minuteOfDay(g.endMs)
            val e = rawEnd - rawEnd % 15
            if (e - s < MIN_FREE) null else BoardFreeTime(calendar.toEpochMs(d, s), calendar.toEpochMs(d, e), s, e)
        }.take(MAX_FREE)
    }

    /**
     * Where a card of [lengthMin] dropped on [free] goes: at the point he let go ([fraction] down the stretch, 0 = its
     * start), on the quarter hour, kept inside the stretch; null when the stretch is shorter than the card.
     */
    fun dropSlot(free: BoardFreeTime, lengthMin: Int, fraction: Float, d: Long, calendar: LocalCalendar): BlockSlot? {
        if (lengthMin <= 0 || lengthMin > free.minutes) return null
        val room = free.minutes - lengthMin
        val off = (fraction.coerceIn(0f, 1f) * room / 15f).toInt() * 15
        val m = free.startMinute + off.coerceIn(0, room)
        val label = "${LocalClock.formatMinute(m)}–${LocalClock.formatMinute(m + lengthMin)}"
        return BlockSlot(calendar.toEpochMs(d, m), m, label, "${LocalClock.formatMinute(m)} to ${LocalClock.formatMinute(m + lengthMin)}")
    }

    /** The bar when the stretch is too short: "“Book MOT” needs 1h · only 45m free there". */
    fun tooShort(title: String, lengthMin: Int, free: BoardFreeTime): String =
        "“${TaskTitleRules.display(title)}” needs ${TaskEstimateRules.label(lengthMin)} · only ${TaskEstimateRules.label(free.minutes)} free there"

    /** The block a tap adds: the task's title (so it keeps in step with the task), its length, Navilife's note. */
    fun draft(task: Task, startMs: Long, lengthMin: Int): EventDraft =
        EventDraft(PlanCalendarRules.blockTitle(task), startMs, startMs + lengthMin * 60_000L, allDay = false, notes = PlanCalendarRules.NOTE)

    /**
     * The undo bar: "Blocked “Book MOT” · Thu 22 Oct 14:00–14:30 in Google" (Today, Tomorrow), or "Moved “Book MOT”'s
     * block to …" when it had one.
     */
    fun line(title: String, slot: BlockSlot, day: Long, today: Long, provider: String, moved: Boolean): String {
        val t = TaskTitleRules.display(title)
        val d = when (day) {
            today -> "Today"
            today + 1 -> "Tomorrow"
            else -> CivilDate.shortLabel(day)
        }
        val where = CalendarEditRules.providerName(provider)
        return if (moved) "Moved “$t”'s block to $d ${slot.label} in $where" else "Blocked “$t” · $d ${slot.label} in $where"
    }
}
