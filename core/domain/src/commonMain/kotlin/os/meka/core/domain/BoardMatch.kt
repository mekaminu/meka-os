package os.meka.core.domain

/** Where Obi's nudge pill sits in the Week board's header ([BoardMatchRules.nudgePlace]). */
enum class BoardNudgePlace { BESIDE_TODAY, UNDER_TITLE }

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
 * A list at the board's foot in the mockup's style (slice 2): its name in serif ("Someday") with the count quiet on the
 * right ("3", none when empty), and what a screen reader hears for the heading.
 */
data class BoardListHead(val name: String, val count: String?, val spoken: String)

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

    /** The chip a card with no time once wore; since the density pass it wears none (kept so tests can check it's gone). */
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

    /**
     * A task card's top line: its time and its duration when it has an estimate. The density pass (2026-10-24 18:10):
     * no "Any time" chip any more ([BoardDenseRules.top]); a card with neither has no top line.
     */
    fun top(card: BoardCard): PlannerCardTop? = BoardDenseRules.top(card)

    /** The time chip's words ("12:30"), "" when it has no time (no chip), for Swift. */
    fun timeChip(card: BoardCard): String = BoardDenseRules.chip(card)

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

    // ---- Slice 2 (2026-10-24): the lists as card columns, the timeline's colours by area ----

    /**
     * The lists under the weekend in the mockup's order: Someday, Side hustle, House, then No day yet as a fourth (the
     * mockup has the first three; the week's parked cards sit beside them rather than first).
     */
    fun lists(week: BoardWeek, board: WeekBoard): List<BoardListColumn> =
        BoardWeekLayoutRules.lists(week, board).sortedBy { if (it.tray == BoardTray.NO_DAY) 1 else 0 }

    /** A list's heading: the name alone in serif, the count beside it in mono ("Someday" · "3"). */
    fun listHead(column: BoardListColumn): BoardListHead {
        val name = column.title.substringBefore(" · ")
        return BoardListHead(name, column.count.takeIf { it > 0 }?.toString(), column.accessibilityLabel)
    }

    /**
     * A card's top line in one of the lists: the mockup's list cards are a title, then the tick and the area, so the
     * top line shows only when the card has a time or a length (never the quiet "Any time" chip there).
     */
    fun listTop(card: BoardCard): PlannerCardTop? = BoardPlannerRules.top(card)

    /** The words of [listTop]'s chip and length, for Swift ("" when there is none). */
    fun listTime(card: BoardCard): String = listTop(card)?.time ?: ""
    fun listDuration(card: BoardCard): String = listTop(card)?.duration ?: ""

    /**
     * The area a task block on the day's timeline is coloured by (the mockup's blocks wear their category's colour): the
     * card's area on [board] (his pick, else the guess); null for an event, the commute or a task with no area (gold).
     */
    fun blockArea(block: DayBlock, board: WeekBoard): BoardArea? {
        if (block.kind != DayBlockKind.TASK) return null
        val id = block.taskId ?: return null
        val card = board.days.firstNotNullOfOrNull { d -> d.cards.firstOrNull { it.id == id } }
            ?: board.weeks.firstNotNullOfOrNull { w -> w.lane.firstOrNull { it.id == id } }
            ?: return null
        return BoardAreaRules.of(card)
    }

    // ---- Slice 3 (2026-10-24): Obi's nudge pill in the header, beside Today ----

    /**
     * Where Obi's nudge pill sits (the mockup's header: "October 2026 W44" on the left, then the pill, Today and ‹ › on
     * the right). With room for it (the unfolded Fold, a wide Mac window) it is [BESIDE_TODAY], in the header's row just
     * before Today; on the cover screen (the mockup at 390 wide) it is [UNDER_TITLE], its own full line between the
     * month and the buttons. Never a separate row under the whole header any more.
     */
    fun nudgePlace(wide: Boolean): BoardNudgePlace = if (wide) BoardNudgePlace.BESIDE_TODAY else BoardNudgePlace.UNDER_TITLE

    /** The widest the pill grows beside Today, in dp/pt, so the month and the buttons keep their room. */
    const val NUDGE_MAX_WIDTH = 420

    /** Lines the pill's words may take: two beside Today (the row is tight on the unfolded Fold), two under the title. */
    const val NUDGE_LINES = 2

    /** The orb's size in the pill (the mockup's 30 px at 1280, scaled to the app's header). */
    const val NUDGE_ORB = 24

    // ---- Slice 4 (2026-10-24): the side-by-side check's gaps ----

    /**
     * The words beside a card's dot in its foot, on every lane, the narrow unfolded weekday columns included (the
     * mockup's "● House"; they showed the dot alone): the area's name ("Family", "Side hustle"), else where the card
     * came from ("Mail"); null when it has neither (no dot either). One line, cut short with "…" if a lane is tight.
     */
    fun footLabel(card: BoardCard): String? {
        BoardAreaRules.of(card)?.let { return BoardAreaRules.label(it) }
        return card.source?.let { BoardPlannerRules.sourceLabel(it) }
    }

    /** Whether the foot's words show on a lane: always now (the narrow lanes cut them short rather than drop them). */
    fun footWords(compact: Boolean): Boolean = true

    /**
     * The budget pill's two parts on a lane: "planned" and the figures ("4h 05 / 3h"). A roomy lane draws them on one
     * row, the label left and the figures right (the mockup); a narrow one ([compact]) stacks them, "planned" over the
     * figures, so the word is never dropped and "nothing yet / 6h 15" still has its whole line.
     */
    fun pillLabel(b: PlannerBudget): String = b.label
    fun pillStacked(compact: Boolean): Boolean = compact

    /**
     * The header's buttons as the mockup draws them (`.btn`): round, outlined in the hairline on the card surface, 40
     * high and at least 40 wide; ‹ › are circles, Today a pill. The tap target stays at least the platform's minimum.
     */
    const val BUTTON = 40

    /** The quiet line under a past day with nothing on it (no tasks, no events), so the column is never a bare date. */
    const val NOTHING_PLANNED = "Nothing planned"

    /** [NOTHING_PLANNED] for a past day with no cards and no events; null otherwise (today and later have "+ add"). */
    fun pastEmpty(day: BoardDay): String? =
        if (day.isPast && day.cards.isEmpty() && day.chips.isEmpty() && day.moreChips == 0) NOTHING_PLANNED else null
}
