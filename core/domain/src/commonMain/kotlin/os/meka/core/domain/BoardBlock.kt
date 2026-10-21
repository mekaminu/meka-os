package os.meka.core.domain

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
