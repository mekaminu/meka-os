package os.meka.core.domain

/** One pill of the folded Week board's day strip: "M", "9" and the day's planned length ("1h 15m", "·" for nothing). */
data class DayListPill(
    val epochDay: Long,
    val letter: String,
    val number: String,
    val length: String,
    val isToday: Boolean,
    val isPast: Boolean,
    val spoken: String,
)

/** The folded Week board's header: "November" and "Week 46 · 6h 15m planned". */
data class DayListBoardHead(val month: String, val weekLine: String, val spoken: String)

/**
 * The Week board on the folded phone as Day lists (approved 2026-11-08, `docs/design/day-list/BoardFolded.dc.html`;
 * Meka 2026-11-09 22:27: the old board "looks awful"): one header row (the month, "Week 46 · 6h 15m planned", ‹ ›), the
 * week's seven days as a strip of pills, each with that day's planned length, and under it the chosen day's Day list
 * (its title and total, the done bar, the slim rows, its own add bar: Enter for the next). Lengths count what the Day
 * list counts ([DayListRules.minutesOf]), done tasks included, so the strip, the day's total and the week agree.
 * Non-AI and pure.
 */
object DayListBoardRules {
    /** A pill in the strip, dp tall (the mockup's 64). */
    const val PILL_HEIGHT = 64

    /** A day with nothing planned shows this under its number. */
    const val NOTHING = "·"

    /** Under the day's rows (a held task's drop onto a day above comes in a later slice). */
    const val HINT = "Tap a task to move it to another day · hold its ≡ to reorder"

    /** The minutes planned on [day]: every task's length, done ones included (the day's total on its Day list). */
    fun minutes(day: BoardDay): Int = day.cards.sumOf(DayListRules::minutesOf)

    /** "1h 15m" · "45m" · [NOTHING]. */
    fun length(minutes: Int): String = if (minutes <= 0) NOTHING else TaskEstimateRules.label(minutes)

    fun head(week: BoardWeek): DayListBoardHead {
        val h = BoardFoldedRules.head(week.startEpochDay)
        val total = week.days.sumOf(::minutes)
        val line = if (total > 0) "${h.week} · ${TaskEstimateRules.label(total)} planned" else "${h.week} · nothing planned"
        return DayListBoardHead(h.month, line, "${h.spoken}, " + if (total > 0) "${TaskEstimateRules.label(total)} planned" else "nothing planned")
    }

    fun strip(week: BoardWeek): List<DayListPill> = week.days.map { d ->
        val m = minutes(d)
        val spoken = buildString {
            append(CivilDate.longLabel(d.epochDay))
            if (d.isToday) append(", today")
            append(", ")
            append(if (m > 0) "${TaskEstimateRules.label(m)} planned" else "nothing planned")
        }
        DayListPill(d.epochDay, d.weekday.take(1), d.number, length(m), d.isToday, d.isPast, spoken)
    }

    /** "Mon 9 November · today" over the chosen day's list. */
    fun dayTitle(day: BoardDay): String {
        val c = CivilDate.fromEpochDay(day.epochDay)
        return "${day.weekday} ${c.day} ${CivilDate.MONTH_LONG[c.month - 1]}" + if (day.isToday) " · today" else ""
    }

    /** The add bar's prompt: "Add to Mon · Enter for the next"; a past day's adds land on today, so it says so. */
    fun placeholder(day: BoardDay): String =
        if (day.isPast) "Add to today · Enter for the next" else "Add to ${day.weekday} · Enter for the next"

    /** The day the board opens on: [chosen] while it's still on the board, else today, else the first day. */
    fun shownDay(board: WeekBoard, chosen: Long?): Long? {
        val days = board.days
        if (days.isEmpty()) return null
        return chosen?.takeIf { c -> days.any { it.epochDay == c } }
            ?: days.firstOrNull { it.isToday }?.epochDay ?: days.first().epochDay
    }

    /** The week holding [day], or the first. */
    fun weekOf(board: WeekBoard, day: Long): BoardWeek? =
        board.weeks.firstOrNull { w -> w.days.any { it.epochDay == day } } ?: board.weeks.firstOrNull()

    /**
     * ‹ › : the same weekday [delta] weeks on, or today when that week holds it (so going back to this week lands on
     * today); null when the board has no such week.
     */
    fun stepWeek(board: WeekBoard, day: Long, delta: Int): Long? {
        val i = board.weeks.indexOfFirst { w -> w.days.any { it.epochDay == day } }
        if (i < 0) return null
        val w = board.weeks.getOrNull(i + delta) ?: return null
        w.days.firstOrNull { it.isToday }?.let { return it.epochDay }
        val offset = (day - board.weeks[i].startEpochDay).toInt()
        return w.days.getOrNull(offset)?.epochDay ?: w.days.firstOrNull()?.epochDay
    }

    fun canStep(board: WeekBoard, day: Long, delta: Int): Boolean = stepWeek(board, day, delta) != null
}
