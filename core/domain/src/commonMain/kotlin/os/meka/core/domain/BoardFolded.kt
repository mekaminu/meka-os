package os.meka.core.domain

/** The folded Board's one header row (the approved `folded.dc.html`): "October" and "Week 43", then ‹ › ⋯. */
data class FoldedHead(val month: String, val week: String, val spoken: String)

/**
 * The chosen day's line under the strip: "Sun 25 October · today" on the left, the plan ("3h 30 of 9h 30", "Nothing
 * yet") on the right, and the thin plan bar's [fill] (amber when [over]).
 */
data class FoldedDayLine(
    val title: String,
    /** "Sun", for "+ Add to Sun". */
    val short: String,
    /** "" when the day has no plan to show. */
    val plan: String,
    val fill: Float,
    val over: Boolean,
    val spoken: String,
)

/**
 * A card on the folded Board as the mockup draws it (slice 2, Meka 2026-10-29 02:12 (f)–(i)): the tick (a task) or the
 * calendar's colour bar (an event) left of the title, one quiet line under it (the area's or calendar's dot and name,
 * then the length or carried note), and the time on the right, all in the app's sans ("18:00", "1h 30", never "1:30").
 */
data class FoldedCard(
    val title: String,
    /** "Work", "Personal · 1h", "Mail · Carried 2 days"; "" for nothing to say. */
    val meta: String,
    /** "18:00", "All day", a task's length "45m" when it has no time; "" for none. */
    val right: String,
    val spoken: String,
)

/**
 * One chip in the folded Move to sheet: "Tomorrow", "Thu 22" or "Someday". [epochDay] is the day it moves to (null for
 * Someday); [lit] marks the day the task is on now (tapping it just closes the sheet).
 */
data class FoldedMoveChip(val label: String, val epochDay: Long?, val lit: Boolean, val spoken: String) {
    val someday: Boolean get() = epochDay == null
}

/** The folded Move to sheet for a task (the approved mockup's tap): its title, the chips, then Done · Edit · Close. */
data class FoldedMoveSheet(val taskId: String, val title: String, val chips: List<FoldedMoveChip>)

/** What the folded Board's ⋯ menu holds: everything that used to crowd the cover screen's header. */
enum class FoldedMenuKind { AGENDA, TIMELINE, NEW_EVENT, PLAN_WEEK, REVIEW, GOALS, NO_DAY, SOMEDAY }

/** One row of the ⋯ menu: what it does on the left, a quiet detail on the right ("Obi helps", "3"). */
data class FoldedMenuItem(val kind: FoldedMenuKind, val label: String, val detail: String)

/**
 * The Week board on the folded phone, as the approved mockup (Meka 2026-10-27 07:44, "Yes to folded week board
 * mockup"; `docs/design/week-board/folded.dc.html`), non-AI and pure: one header row (the month in the app's sans, the
 * week as "Week 43", no serif and no monospace), the week's seven days as a strip, the chosen day's line and plan bar,
 * Obi's one line, then that day's cards from the top third of the screen. Agenda, Timeline, Plan your week, Review the
 * week, Goals and the two lists move into ⋯ ([menu]). The open Fold and the Mac keep the columns.
 */
object BoardFoldedRules {
    /** The header's round buttons (‹ › ⋯), dp. */
    const val BUTTON = 40

    /** A day's pill in the strip, dp tall. */
    const val PILL_HEIGHT = 60

    /** The busy dots under a pill's number: one short bar per thing on the day (an open task or an event), at most this many. */
    const val MAX_DOTS = 3

    /** The plan bar under the day's line, dp. */
    const val BAR = 4

    /** Obi's orb in his one line, dp. */
    const val ORB = 22

    /** "+ Add to Sun", dp tall. */
    const val ADD_HEIGHT = 40

    const val NOTHING_YET = "Nothing yet"
    const val TODAY = "today"

    fun head(startEpochDay: Long): FoldedHead {
        val thursday = startEpochDay - (CivilDate.isoDayOfWeek(startEpochDay) - 1) + 3
        val month = CivilDate.MONTH_LONG[CivilDate.fromEpochDay(thursday).month - 1]
        val week = BoardMatchRules.isoWeek(startEpochDay)
        return FoldedHead(month, "Week $week", "$month, week $week")
    }

    /**
     * The short bars under a pill: one for each open task and each event, up to [MAX_DOTS] (the mockup counts
     * everything on the day, so a day of events alone still looks busy; Meka 2026-10-29 02:12 (g)).
     */
    fun dots(day: BoardDay): Int = (day.openCards.size + day.chips.size + day.moreChips).coerceAtMost(MAX_DOTS)

    /** The card's padding inside, dp, and the tick's circle beside the title (the mockup's 12 and 20). */
    const val CARD_PAD = 12
    const val TICK = 20

    /** An event's colour bar in the tick's place, dp wide. */
    const val EVENT_BAR = 4

    /** A task's card: its area's (or source's) name and any carried note under the title, its time or length right. */
    fun card(card: BoardCard): FoldedCard {
        val meta = listOfNotNull(BoardMatchRules.footLabel(card), card.line?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" · ")
        val right = card.time ?: card.task.estimateMinutes?.takeIf { it > 0 }?.let(BoardPlannerRules::hours) ?: ""
        val spoken = listOf(card.title, right, meta).filter { it.isNotEmpty() }.joinToString(", ") + if (card.done) ", done" else ""
        return FoldedCard(card.title, meta, right, spoken)
    }

    /** An event's card: the calendar's name (never an address) and its length under the title, its start right. */
    fun event(e: BoardEventCard): FoldedCard {
        val minutes = ((e.event.endAtMs - e.event.startAtMs) / 60_000L).toInt()
        val length = if (e.duration != null && minutes > 0) BoardPlannerRules.hours(minutes) else null
        val meta = listOfNotNull(e.calendar, length).joinToString(" · ")
        return FoldedCard(e.title, meta, e.time, e.spoken)
    }

    fun dayLine(day: BoardDay): FoldedDayLine {
        val d = CivilDate.fromEpochDay(day.epochDay)
        val title = buildString {
            append("${day.weekday} ${d.day} ${CivilDate.MONTH_LONG[d.month - 1]}")
            if (day.isToday) append(" · $TODAY")
        }
        val c = day.capacity
        val plan = when {
            day.isPast -> day.countLine
            c == null -> ""
            else -> plan(c)
        }
        val over = c != null && !day.isPast && c.plannedMin > 0 && c.plannedMin > c.freeMin
        val fill = when {
            c == null || day.isPast || c.plannedMin <= 0 -> 0f
            c.freeMin <= 0 -> 1f
            else -> c.fill
        }
        val spoken = listOf(title.replace(" · ", ", "), plan).filter { it.isNotEmpty() }.joinToString(", ")
        return FoldedDayLine(title, day.weekday, plan, fill, over, spoken)
    }

    /** "Nothing yet", "2h planned" (no free time to set it against), "1h 30 · 30m over", "3h 30 of 9h 30". */
    fun plan(c: BoardCapacity): String {
        if (c.plannedMin <= 0) return NOTHING_YET
        val planned = BoardPlannerRules.hours(c.plannedMin)
        return when {
            c.freeMin <= 0 -> "$planned planned"
            c.plannedMin > c.freeMin -> "$planned · ${BoardPlannerRules.hours(c.plannedMin - c.freeMin)} over"
            else -> "$planned of ${BoardPlannerRules.hours(c.freeMin)}"
        }
    }

    /** "+ Add to Sun" at the foot of the day (the add field opens there, as before). */
    fun addLabel(day: BoardDay): String = "+ Add to ${day.weekday}"

    /**
     * The ⋯ menu for [week] ([weekIndex] 0 is this week): Agenda view, Timeline (or back to the cards), New event (when
     * a calendar can take one), Plan your week, Review the week (from Friday, this week), Goals, No day yet, Someday.
     */
    fun menu(
        board: WeekBoard, week: BoardWeek, weekIndex: Int, timelineOpen: Boolean, canAddEvent: Boolean, canPlan: Boolean,
    ): List<FoldedMenuItem> = buildList {
        add(FoldedMenuItem(FoldedMenuKind.AGENDA, "Agenda view", ""))
        add(
            if (timelineOpen) FoldedMenuItem(FoldedMenuKind.TIMELINE, "Cards", "the day's tasks")
            else FoldedMenuItem(FoldedMenuKind.TIMELINE, "Timeline", "the day by the hour"),
        )
        if (canAddEvent) add(FoldedMenuItem(FoldedMenuKind.NEW_EVENT, "New event", ""))
        if (canPlan) add(FoldedMenuItem(FoldedMenuKind.PLAN_WEEK, PlanWeekRules.OPEN, "Obi helps"))
        if (board.offersReview && weekIndex == 0) add(FoldedMenuItem(FoldedMenuKind.REVIEW, BoardReviewRules.REVIEW, ""))
        if (week.goals.isNotEmpty() || week.canAddGoal) {
            val n = week.goals.size
            add(FoldedMenuItem(FoldedMenuKind.GOALS, "Goals", if (n == 0) "up to ${BoardGoalRules.MAX}" else "$n this week"))
        }
        add(FoldedMenuItem(FoldedMenuKind.NO_DAY, WeekBoardRules.NO_DAY, count(week.lane.size)))
        add(FoldedMenuItem(FoldedMenuKind.SOMEDAY, WeekBoardRules.SOMEDAY, count(board.someday.size)))
    }

    /** A task swiped right this far (dp) goes to Tomorrow when let go; less springs back. */
    const val SWIPE_DP = 96

    /** The hint under the day's cards shows until he has moved this many tasks on the folded Board. */
    const val HINT_MOVES = 3
    const val HINT = "Tap a task to move it · or hold it and drag onto a day above · swipe right = tomorrow"
    const val MOVE_TO = "Move to"
    const val TOMORROW = "Tomorrow"
    const val DONE = "Done"
    const val EDIT = "Edit"
    const val CLOSE = "Close"

    /** The hint line shows while fewer than [HINT_MOVES] tasks were moved, on a day that has an open task to move. */
    fun showsHint(moves: Int, day: BoardDay): Boolean = moves < HINT_MOVES && day.canDrop && day.openCards.isNotEmpty()

    /**
     * Tap a task on the folded Board: Move to "Tomorrow" (unless it is already tomorrow's), then each day of its week
     * from today on ("Mon 26" … "Sun 1", its own day lit), then Someday (not for a repeating task, which belongs to its
     * days). Null when [taskId] isn't an open task on a day.
     */
    fun moveSheet(board: WeekBoard, taskId: String): FoldedMoveSheet? {
        val day = board.dayOf(taskId) ?: return null
        val card = day.cards.first { it.id == taskId }
        if (!card.canMove) return null
        val tomorrow = board.todayEpochDay + 1
        val week = board.weeks.firstOrNull { day.epochDay in it.startEpochDay until it.startEpochDay + 7 } ?: return null
        val chips = buildList {
            if (day.epochDay != tomorrow && board.day(tomorrow)?.canDrop == true) {
                add(FoldedMoveChip(TOMORROW, tomorrow, false, "Move to tomorrow"))
            }
            week.days.filter { it.canDrop }.forEach { d ->
                val lit = d.epochDay == day.epochDay
                val spoken = CivilDate.shortLabel(d.epochDay).let { if (lit) "$it, where it is now" else "Move to $it" }
                add(FoldedMoveChip("${d.weekday} ${d.number}", d.epochDay, lit, spoken))
            }
            if (!card.task.isRepeating) add(FoldedMoveChip(WeekBoardRules.SOMEDAY, null, false, "Move to Someday"))
        }
        return FoldedMoveSheet(taskId, card.title, chips)
    }

    /** Swipe a task right: the day it goes to (tomorrow), or null when it's already tomorrow's or can't move. */
    fun swipeTo(board: WeekBoard, taskId: String): Long? {
        val day = board.dayOf(taskId) ?: return null
        if (day.cards.firstOrNull { it.id == taskId }?.canMove != true) return null
        val tomorrow = board.todayEpochDay + 1
        return tomorrow.takeIf { day.epochDay != it && board.day(it)?.canDrop == true }
    }

    /** How far the swiped card has gone towards Tomorrow, 0 to 1 (its "Tomorrow ›" label fills in as it goes). */
    fun swipeProgress(dx: Float, thresholdPx: Float): Float = if (thresholdPx <= 0f) 0f else (dx / thresholdPx).coerceIn(0f, 1f)

    /** ⋯ → a list with nothing in it: "Nothing in Someday yet · a task's menu puts one there". */
    fun emptyList(label: String): String = "Nothing in $label yet · a task's menu puts one there"

    private fun count(n: Int): String = if (n == 0) "empty" else n.toString()
}
