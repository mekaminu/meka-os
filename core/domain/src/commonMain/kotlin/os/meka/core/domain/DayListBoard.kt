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

/** One column of the open Fold's spread: the day's heading ("Mon 9 · today"), its add bar's prompt, and whether it's today. */
data class DayListColumn(val epochDay: Long, val title: String, val placeholder: String, val isToday: Boolean)

/**
 * The Week board on the open Fold as Day lists side by side (approved 2026-11-08, `docs/design/day-list/OpenSpread.dc.html`;
 * Meka 2026-11-09 22:27: the open Fold's old board "looks awful"): the month and week line with ‹ › ⋯, a 1 · 2 · 3 days
 * switch (3 by default), the week's seven pills with the days in view lit, and that many Day lists across, each with its
 * own heading, total, done bar, rows and add bar. The days in view start at the chosen day, held inside the week (the
 * chosen Sunday with 3 days shows Fri · Sat · Sun). Non-AI and pure.
 */
object DayListSpreadRules {
    val COUNTS = listOf(1, 2, 3)
    const val DEFAULT_COUNT = 3

    /** "1 day" · "2 days" · "3 days". */
    fun countLabel(count: Int): String = if (count == 1) "1 day" else "$count days"

    fun countOrDefault(count: Int?): Int = count?.takeIf { it in COUNTS } ?: DEFAULT_COUNT

    /**
     * The Mac's spread (slice 5; Meka 2026-11-08: "the Mac offers 3 · 5 · 7 days, default 5"): a wide window has room
     * for the working week or the whole week side by side. Each Mac remembers its own count.
     */
    val MAC_COUNTS = listOf(3, 5, 7)
    const val MAC_DEFAULT_COUNT = 5

    fun macCountOrDefault(count: Int?): Int = count?.takeIf { it in MAC_COUNTS } ?: MAC_DEFAULT_COUNT

    /** The [count] days of [week] in view, starting at [chosen] and held inside the week. */
    fun inView(week: BoardWeek, chosen: Long, count: Int): List<BoardDay> = slice(week, chosen, countOrDefault(count))

    /** The Mac's days in view: 3, 5 or 7 from [chosen], held inside the week (7 is the whole week, Monday first). */
    fun macInView(week: BoardWeek, chosen: Long, count: Int): List<BoardDay> = slice(week, chosen, macCountOrDefault(count))

    /** The Mac's columns: three or more across are narrow, so each says "Mon 9 · today" and "Add · Enter for next". */
    fun macColumns(week: BoardWeek, chosen: Long, count: Int): List<DayListColumn> =
        macInView(week, chosen, count).map { column(it, 3) }

    private fun slice(week: BoardWeek, chosen: Long, count: Int): List<BoardDay> {
        val days = week.days
        if (days.isEmpty()) return emptyList()
        val k = count.coerceIn(1, days.size)
        val at = days.indexOfFirst { it.epochDay == chosen }.coerceAtLeast(0)
        val start = at.coerceAtMost(days.size - k)
        return days.subList(start, start + k)
    }

    /**
     * A column's heading and prompt. Three across are narrow, so "Mon 9" and "Add · Enter for next"; one or two across
     * have room for "Monday 9" and "Add to Monday · Enter for the next". A past day's adds land on today, so it says so.
     */
    fun column(day: BoardDay, count: Int): DayListColumn {
        val n = CivilDate.fromEpochDay(day.epochDay).day
        val long = CivilDate.DAY_LONG[CivilDate.isoDayOfWeek(day.epochDay) - 1]
        val narrow = countOrDefault(count) == 3
        val title = (if (narrow) "${day.weekday} $n" else "$long $n") + if (day.isToday) " · today" else ""
        val placeholder = when {
            day.isPast -> if (narrow) "Add to today · Enter" else "Add to today · Enter for the next"
            narrow -> "Add · Enter for next"
            else -> "Add to $long · Enter for the next"
        }
        return DayListColumn(day.epochDay, title, placeholder, day.isToday)
    }

    fun columns(week: BoardWeek, chosen: Long, count: Int): List<DayListColumn> =
        inView(week, chosen, count).map { column(it, count) }

    /** The line under each column's rows: a task's ≡ drags it up or down, or across to another day's list or pill. */
    const val HINT = "Drag a task's ≡ to another day"

    /**
     * Drag across days (slice 4b): a held task can land on [to] when it's another day on the board, today or later
     * (a past day's list only holds what was left there; the sheet's Move to offers the same days).
     */
    fun canDropOn(board: WeekBoard, from: Long, to: Long): Boolean =
        to != from && to >= board.todayEpochDay && board.day(to) != null

    /**
     * The day a held task dropped at ([x], [y]) lands on: the first of [zones] (the day pills, then the columns, in one
     * coordinate space) under the finger that [canDropOn] allows, else null (it goes back where it was).
     */
    fun dropDay(zones: List<DayDropZone>, x: Float, y: Float, board: WeekBoard, from: Long): Long? =
        zones.firstOrNull { it.contains(x, y) }?.epochDay?.takeIf { canDropOn(board, from, it) }

    /** Whether ([x], [y]) has left the column of [from] (the held task shows as a card under the finger from then). */
    fun leftColumn(zones: List<DayDropZone>, x: Float, y: Float, from: Long): Boolean =
        zones.filter { it.epochDay == from && it.isColumn }.none { it.contains(x, y) }
}

/** Where a held task can land on the open Fold's spread: a day's pill or column, by its bounds on the screen. */
data class DayDropZone(
    val epochDay: Long, val left: Float, val top: Float, val right: Float, val bottom: Float, val isColumn: Boolean,
) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}
