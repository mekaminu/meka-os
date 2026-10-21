package os.meka.core.domain

/** One of the week's goals as a small gold pill ("◎ Run 5k" with "2/4"): the pill fills gold as its tasks are done. */
data class GoalPill(
    val goalId: String,
    /** "◎ Run 5k" */
    val label: String,
    /** "2/4" · "✓" once the goal is done · null with no tasks yet (never "0/0"). */
    val count: String?,
    /** How much of the pill is filled gold, 0..1. */
    val fill: Float,
    val finished: Boolean,
    /** Screen readers: "Goal Run 5k, 2 of 4 done". */
    val accessibilityLabel: String,
)

/** Which tray a chip opens. */
enum class BoardTray { NO_DAY, SOMEDAY }

/** "No day yet · 3" or "Someday · 12" as a small chip that opens into a lane (folded, a sheet). */
data class TrayChip(
    val tray: BoardTray,
    /** "No day yet · 3" · "Someday" */
    val label: String,
    val count: Int,
    /** Screen readers: "No day yet, 3 tasks, folded". */
    val accessibilityLabel: String,
) {
    /** Only a tray with cards opens; an empty one is a quiet chip (still a place to drop a card). */
    val canOpen: Boolean get() = count > 0
}

/**
 * The Week board's tidy top and narrow past days (by hand, slice 4; Meka 2026-10-21 19:00, the approved design
 * `docs/design/week-board`), non-AI and pure, the same on the Fold and the Mac:
 * - the week's goals are small gold pills ([goalPill]), not boxes with a hint line;
 * - "No day yet" and Someday are two small chips on one row ([trayChips]); tapping one opens it into a lane below (on
 *   the cover screen, a sheet), one at a time ([toggle]); an empty tray never opens into an empty box ([stillOpen]);
 * - past days fold narrow ([collapsed]) with their big number, weekday and "3 done"; tap one to open it;
 * - unfolded, the week's days share the width, past ones narrow, each at most [MAX_LANE_DP] ([laneWidths]), so the
 *   week fits and later weeks scroll on; [starts] and [indexAt] turn those widths into scroll places.
 */
object BoardTidyRules {
    /** A folded past day's width (dp). */
    const val PAST_DP = 64f

    /** A lane never wider than this (dp), however wide the screen. */
    const val MAX_LANE_DP = 250f

    /** Nor narrower than this (dp): below it the week scrolls rather than squeeze the cards. */
    const val MIN_LANE_DP = 96f

    const val GOAL_MARK = "◎"
    const val DONE_MARK = "✓"

    fun goalPill(g: BoardGoal): GoalPill = GoalPill(
        goalId = g.goalId,
        label = "$GOAL_MARK ${g.shortTitle}",
        count = when {
            g.finished -> DONE_MARK
            g.total == 0 -> null
            else -> "${g.done}/${g.total}"
        },
        fill = g.fill,
        finished = g.finished,
        accessibilityLabel = g.accessibilityLabel,
    )

    /** The two trays' chips for [week] and the board's Someday, "No day yet" first. */
    fun trayChips(week: BoardWeek, board: WeekBoard, open: BoardTray? = null): List<TrayChip> = listOf(
        chip(BoardTray.NO_DAY, WeekBoardRules.NO_DAY, week.lane.size, open == BoardTray.NO_DAY),
        chip(BoardTray.SOMEDAY, WeekBoardRules.SOMEDAY, board.someday.size, open == BoardTray.SOMEDAY),
    )

    private fun chip(tray: BoardTray, name: String, count: Int, open: Boolean): TrayChip {
        val tasks = when (count) {
            0 -> "empty"
            1 -> "1 task"
            else -> "$count tasks"
        }
        val state = when {
            count == 0 -> ". Drop a task here"
            open -> ", open"
            else -> ", folded"
        }
        return TrayChip(tray, if (count == 0) name else "$name · $count", count, "$name, $tasks$state")
    }

    /** A tap on [tapped]'s chip: it opens (closing the other), or closes if it was open; an empty tray stays shut. */
    fun toggle(open: BoardTray?, tapped: TrayChip): BoardTray? = when {
        open == tapped.tray -> null
        !tapped.canOpen -> open
        else -> tapped.tray
    }

    /** The open tray, closed once its last card has gone (never an empty box). */
    fun stillOpen(open: BoardTray?, chips: List<TrayChip>): BoardTray? =
        open?.takeIf { o -> chips.any { it.tray == o && it.canOpen } }

    /** A past day shows narrow unless he opened it. */
    fun collapsed(day: BoardDay, opened: Set<Long>): Boolean = day.isPast && day.epochDay !in opened

    /** Screen readers for a folded past day: "Monday 19 October, 3 done. Tap to open". */
    fun collapsedLabel(day: BoardDay): String =
        day.accessibilityLabel.substringBefore(',') + ", " + BoardLookRules.pastLine(day).lowercase() + ". Tap to open"

    /**
     * Each day's width, week by week ([days] in order, seven to a week) for a [viewport] of that width: folded past
     * days [past]; the rest share what is left equally, at most [max] and at least [min].
     */
    fun laneWidths(
        days: List<BoardDay>, viewport: Float, opened: Set<Long>, past: Float = PAST_DP, min: Float = MIN_LANE_DP, max: Float = MAX_LANE_DP,
    ): List<Float> = days.chunked(7).flatMap { week ->
        val narrow = week.count { collapsed(it, opened) }
        val wide = week.size - narrow
        val share = if (wide == 0) 0f else ((viewport - narrow * past) / wide).coerceIn(min, max)
        week.map { if (collapsed(it, opened)) past else share }
    }

    /** Where each column starts along the row. */
    fun starts(widths: List<Float>): List<Float> {
        var at = 0f
        return widths.map { w -> at.also { at += w } }
    }

    /** The column whose start is nearest [offset] (the one in view at that scroll place). */
    fun indexAt(offset: Float, widths: List<Float>): Int {
        if (widths.isEmpty()) return 0
        val s = starts(widths)
        return s.indices.minByOrNull { kotlin.math.abs(s[it] - offset) } ?: 0
    }

    /** The column under [offset] (the one it falls inside). */
    fun columnAt(offset: Float, widths: List<Float>): Int {
        var at = 0f
        widths.forEachIndexed { i, w -> if (offset < at + w) return i; at += w }
        return (widths.size - 1).coerceAtLeast(0)
    }
}
