package os.meka.core.domain

/** The week's big header (the mockup's "October 2026  W44"): the month and year in serif, the ISO week number in mono. */
data class BoardWeekHead(
    val month: String,
    val week: String,
    val spoken: String,
    /** "Oct 2026", for the cover screen's narrow header. */
    val monthShort: String = month,
)

/** A day's header (the mockup's "27 Oct   Mon"): the date in serif on the left, the weekday on the right. */
data class BoardDayHead(val date: String, val weekday: String, val spoken: String)

/**
 * One of the day's events drawn as a card in its column (the mockup's blue time chip): "09:30" with its length on the
 * right ("0:30"), the title, then the calendar's dot and name in the foot. All day: "All day" and no length.
 */
data class BoardEventCard(
    val id: String,
    val time: String,
    val duration: String?,
    val title: String,
    val tone: Int,
    /** The calendar's name for the foot ("Work"), or "Calendar" when it has none. */
    val calendar: String,
    val event: CalendarEvent,
    val spoken: String,
)

/**
 * The Week board made to match the approved Option B mockup exactly (`docs/design/week-board/option-b.dc.html`; Meka
 * 2026-10-24 14:39 with screenshots, "doesn't look like the mock-up prototype"), slice 1: the columns. Non-AI and pure,
 * the same on the unfolded Fold, the cover screen and the Mac.
 *
 * - **No folded days** ([FOLDS_PAST] false, so [BoardTidyRules.collapsed] is always false): Mon–Fri are five equal full
 *   columns every time, a past one only dimmed with its done cards kept; the weekend two shorter columns underneath.
 * - **On a weekend the board opens on next week** ([opensOnWeek]); ‹ › still go back to this one.
 * - The week's header is "October 2026" with "W44" ([weekHead]); a day's header is "27 Oct" with "Mon" ([dayHead]),
 *   then the budget pill, always "planned · 4h 05 / 3h" ([BoardPlannerRules.budget]); the "free 6h 15" pill and the
 *   "2 of 9 done" line are gone (the done bar stays, thin, without words).
 * - Every task card has its top line: the gold time chip ("12:30", or a quiet "Any time" chip, never a lone dash) and
 *   the duration on the right when it has one ([top]).
 * - The day's events are cards too, with a blue time chip ([eventCards]), above the day's tasks.
 * - "+ add" sits at the foot of every column from today ([ADD]), a quiet dashed button.
 */
object BoardMatchRules {
    /** Past days never fold narrow any more (the mockup has none). */
    const val FOLDS_PAST = false

    /** The header's button back to this week. */
    const val TODAY = "Today"

    /** The quiet button at the foot of a column. */
    const val ADD = "+ add"

    /** The time chip of a card with no time planned (the mockup's "—", in words so it's never a lone dash). */
    const val ANY_TIME = "Any time"

    /** An event's foot when its calendar has no name. */
    const val CALENDAR = "Calendar"

    private val MONTH_LONG = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
    )

    /** The ISO week number of the week holding [epochDay] (weeks start on Monday; week 1 holds the year's first Thursday). */
    fun isoWeek(epochDay: Long): Int {
        val thursday = epochDay - (CivilDate.isoDayOfWeek(epochDay) - 1) + 3
        val year = CivilDate.fromEpochDay(thursday).year
        return ((thursday - CivilDate.toEpochDay(year, 1, 1)) / 7 + 1).toInt()
    }

    /** "October 2026" and "W44" for the week starting on Monday [startEpochDay] (the month its Thursday falls in). */
    fun weekHead(startEpochDay: Long): BoardWeekHead {
        val thursday = startEpochDay - (CivilDate.isoDayOfWeek(startEpochDay) - 1) + 3
        val d = CivilDate.fromEpochDay(thursday)
        val month = "${MONTH_LONG[d.month - 1]} ${d.year}"
        val w = isoWeek(startEpochDay)
        return BoardWeekHead(month, "W$w", "$month, week $w", "${Recurrence.MONTH_SHORT[d.month - 1]} ${d.year}")
    }

    /** "27 Oct" and "Mon". */
    fun dayHead(day: BoardDay): BoardDayHead {
        val d = CivilDate.fromEpochDay(day.epochDay)
        return BoardDayHead("${d.day} ${Recurrence.MONTH_SHORT[d.month - 1]}", day.weekday, day.accessibilityLabel)
    }

    /**
     * The week the board opens on: this week from Monday to Friday, next week on Saturday and Sunday (when the board has
     * one), as an index into [WeekBoard.weeks].
     */
    fun opensOnWeek(board: WeekBoard): Int {
        if (board.weeks.size < 2) return 0
        val weekday = CivilDate.isoDayOfWeek(board.todayEpochDay)
        val inFirst = board.todayEpochDay in board.weeks[0].startEpochDay until board.weeks[0].startEpochDay + 7
        return if (weekday >= 6 && inFirst) 1 else 0
    }

    /** The day [opensOnWeek] shows first: today on a weekday, next Monday at the weekend. */
    fun opensOnDay(board: WeekBoard): Long {
        val w = opensOnWeek(board)
        return if (w == 0) board.todayEpochDay else board.weeks[w].startEpochDay
    }

    /** A task card's top line: its time (or "Any time") and its duration when it has an estimate. */
    fun top(card: BoardCard): PlannerCardTop {
        val t = BoardPlannerRules.top(card)
        return PlannerCardTop(t?.time ?: ANY_TIME, t?.duration)
    }

    /** The time chip's words ("12:30" or "Any time"), for Swift. */
    fun timeChip(card: BoardCard): String = top(card).time ?: ANY_TIME

    /** Whether the chip is a planned time (gold) rather than "Any time" (quiet). */
    fun timed(card: BoardCard): Boolean = card.time != null

    /** The day's events as cards, in the board's order (all-day first, then by start). */
    fun eventCards(day: BoardDay): List<BoardEventCard> = day.chips.map { c ->
        val e = c.event
        val minutes = ((e.endAtMs - e.startAtMs) / 60_000L).toInt()
        val duration = if (e.allDay || c.time == "All day" || minutes <= 0) null else BoardPlannerRules.duration(minutes)
        val calendar = e.calendarName?.trim()?.takeIf { it.isNotEmpty() } ?: CALENDAR
        val spoken = buildString {
            append(c.title).append(", ")
            append(if (c.time == "All day") "all day" else c.time)
            if (duration != null) append(", ").append(BoardPlannerRules.hours(minutes))
            append(", ").append(calendar)
        }
        BoardEventCard(c.id, c.time, duration, c.title, c.tone, calendar, e, spoken)
    }
}
