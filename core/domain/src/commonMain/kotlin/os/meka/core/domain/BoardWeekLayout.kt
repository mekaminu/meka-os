package os.meka.core.domain

/** How tall each tier of the unfolded week is drawn (dp): Mon–Fri, the weekend under it, then the lists. */
data class BoardTierHeights(val weekdays: Float, val weekend: Float, val lists: Float)

/**
 * One of the lists under the weekend: "No day yet" (the week's parked tasks) or Someday. [title] is "No day yet · 3"
 * (just the name when empty), [empty] the quiet line an empty list shows, and [accessibilityLabel] what a screen reader
 * hears for the list as a drop target ("Someday, 12 tasks").
 */
data class BoardListColumn(
    val tray: BoardTray,
    val title: String,
    val count: Int,
    val empty: String,
    val accessibilityLabel: String,
    /** Its cards, in order: the week's lane, or Someday's share (slice 3: Side hustle and House have their own). */
    val cards: List<BoardCard> = emptyList(),
    /** Slice 3: the area of a Side hustle or House list (a card dropped there is filed under it); null otherwise. */
    val area: BoardArea? = null,
) {
    /** Unique among the lists of a week: "NO_DAY", "SOMEDAY", "SOMEDAY:HOUSE". */
    val key: String get() = tray.name + (area?.let { ":${it.name}" } ?: "")
}

/**
 * The Week board redesign, Option B (Meka approved the mockup and its refinements 2026-10-24 08:30), slice 2: the
 * layout. Non-AI and pure, the same on the unfolded Fold and the Mac; the cover screen keeps its one day at a time.
 *
 * - One week at a time: Monday to Friday as five columns across ([weekdays]), the weekend as two shorter columns
 *   underneath ([weekend], refinement 1), then the lists ([lists]): No day yet and Someday, always open, each a place to
 *   drop a card; slice 3 adds Side hustle and House (Someday's cards of those areas).
 * - A row's columns share its width; a folded past day keeps its narrow strip ([rowWidths]).
 * - The weekday row takes most of the height, the weekend row about [WEEKEND_RATIO] of it, the lists as much again;
 *   on a short screen the tiers keep their least heights and the week scrolls down ([heights]).
 */
object BoardWeekLayoutRules {
    /** Monday to Friday. */
    const val WEEKDAYS = 5

    /** The weekday row's share of the screen's height, and the least it is drawn (dp). */
    const val WEEKDAYS_SHARE = 0.62f
    const val MIN_WEEKDAYS_DP = 320f

    /** The weekend row is shorter: this much of the weekday row, at least [MIN_WEEKEND_DP]. */
    const val WEEKEND_RATIO = 0.6f
    const val MIN_WEEKEND_DP = 200f

    /** The lists' row (dp), at least. */
    const val MIN_LISTS_DP = 200f

    const val NO_DAY_EMPTY = "Nothing waiting for a day"
    const val SIDE_HUSTLE_EMPTY = "No side hustle tasks waiting"
    const val HOUSE_EMPTY = "Nothing for the house waiting"

    /** Monday to Friday of [week] (its days start on Monday). */
    fun weekdays(week: BoardWeek): List<BoardDay> = week.days.take(WEEKDAYS)

    /** Saturday and Sunday of [week]. */
    fun weekend(week: BoardWeek): List<BoardDay> = week.days.drop(WEEKDAYS)

    /**
     * Each column's width in a row of [days] across [viewport] dp: folded past days [past], the rest share what is
     * left equally (never below zero).
     */
    fun rowWidths(days: List<BoardDay>, viewport: Float, opened: Set<Long>, past: Float = BoardTidyRules.PAST_DP): List<Float> {
        val narrow = days.count { BoardTidyRules.collapsed(it, opened) }
        val wide = days.size - narrow
        val share = if (wide == 0) 0f else ((viewport - narrow * past) / wide).coerceAtLeast(0f)
        return days.map { if (BoardTidyRules.collapsed(it, opened)) past else share }
    }

    /** The three tiers' heights for a board area [viewportHeight] dp tall. */
    fun heights(viewportHeight: Float): BoardTierHeights {
        val weekdays = (viewportHeight * WEEKDAYS_SHARE).coerceAtLeast(MIN_WEEKDAYS_DP)
        val weekend = (weekdays * WEEKEND_RATIO).coerceAtLeast(MIN_WEEKEND_DP)
        return BoardTierHeights(weekdays, weekend, weekend.coerceAtLeast(MIN_LISTS_DP))
    }

    /**
     * The lists under the weekend: No day yet, Someday, then Side hustle and House (slice 3: Someday's cards whose area
     * is one of those two sit in its list instead, [BoardAreaRules.listOf]).
     */
    fun lists(week: BoardWeek, board: WeekBoard): List<BoardListColumn> {
        val split = board.someday.groupBy(BoardAreaRules::listOf)
        return listOf(
            list(BoardTray.NO_DAY, WeekBoardRules.NO_DAY, week.lane, NO_DAY_EMPTY, null),
            list(BoardTray.SOMEDAY, WeekBoardRules.SOMEDAY, split[null].orEmpty(), WeekBoardRules.SOMEDAY_EMPTY, null),
        ) + BoardAreaRules.LISTS.map { a ->
            val name = BoardAreaRules.label(a)
            list(BoardTray.SOMEDAY, name, split[a].orEmpty(), if (a == BoardArea.HOUSE) HOUSE_EMPTY else SIDE_HUSTLE_EMPTY, a)
        }
    }

    private fun list(tray: BoardTray, name: String, cards: List<BoardCard>, empty: String, area: BoardArea?): BoardListColumn {
        val count = cards.size
        val tasks = when (count) {
            0 -> "empty. Drop a task here"
            1 -> "1 task"
            else -> "$count tasks"
        }
        return BoardListColumn(tray, if (count == 0) name else "$name · $count", count, empty, "$name, $tasks", cards, area)
    }
}
