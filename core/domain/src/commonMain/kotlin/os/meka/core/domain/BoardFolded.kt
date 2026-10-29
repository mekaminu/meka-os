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

    /** ⋯ → a list with nothing in it: "Nothing in Someday yet · a task's menu puts one there". */
    fun emptyList(label: String): String = "Nothing in $label yet · a task's menu puts one there"

    private fun count(n: Int): String = if (n == 0) "empty" else n.toString()
}
