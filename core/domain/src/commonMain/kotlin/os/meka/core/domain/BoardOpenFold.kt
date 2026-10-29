package os.meka.core.domain

/**
 * The Week board's alignment pass on the open Fold (Meka 2026-10-29 02:12, three screenshots, W44): (a) each day column
 * scrolled on its own, taking its header off screen; (b) Thursday's cards were clipped at the foot of the weekday row;
 * (c) three budget pill styles (none on past days, small boxes on the weekdays, wide bars at the weekend); (d) an empty
 * gap under the weekdays before the weekend; (e) serif and mono type beside the folded layout's one typeface.
 * Non-AI and pure, the same on the unfolded Fold and the Mac.
 *
 * - A row is as tall as its fullest day ([rowHeight]), never less than [LEAST_WEEKDAYS_DP] / [LEAST_WEEKEND_DP]: no
 *   column scrolls by itself or cuts a card off, and a quiet week leaves no gap; the week scrolls as one.
 * - One pill everywhere on the columns ([pillStacked]): "planned" over the figures, the same height on every day.
 * - A past day wears the same pill ([pastPill]): "done" over "2 of 3" (or "none"), its bar filled by what was done.
 * - One header (alignment list (1)(2), 2026-10-24): no "Calendar" title or Agenda · Board switch above the board; the
 *   month's row holds Obi's pill, Today, ‹ › and a ⋯ in the same round style, and ⋯ ([menu]) holds what were four
 *   different text styles: Agenda view, Timeline / Hide timeline, New event, Plan your week, Review the week.
 * - One grid (alignment list (5)(9), 2026-10-29): Saturday and Sunday sit under Monday and Tuesday at a weekday's width,
 *   and the lists under them (Someday, Side hustle, House, No day yet) on the same columns ([gridWidths]), so every card
 *   below the weekdays has a weekday card's width and left edge; nothing is wider or inset.
 */
object BoardOpenFoldRules {
    /** The least the weekday row is drawn (dp): its header, pill and "+ add" with room to drop a card. */
    const val LEAST_WEEKDAYS_DP = 180f

    /** The least the weekend row is drawn (dp). */
    const val LEAST_WEEKEND_DP = 150f

    /** The past day's pill's label. */
    const val DONE = "done"

    /** A past day with nothing on it: its pill's value. */
    const val NOTHING = "none"

    /**
     * A row's height (dp) from each of its columns' content height [contents] (head, cards and "+ add"): the fullest
     * column, at least [least]; a column not measured yet counts as nothing.
     */
    fun rowHeight(contents: List<Float>, least: Float): Float =
        (contents.filter { it.isFinite() && it > 0f }.maxOrNull() ?: 0f).coerceAtLeast(least)

    /** The budget pill's words stack, "planned" over the figures, on every unfolded column (and any compact lane). */
    fun pillStacked(dense: Boolean, compact: Boolean): Boolean = dense || compact

    /**
     * A past day's pill in the budget pill's style: "done" over "2 of 3" (its tasks done of its tasks), or "none" when it
     * had none; the bar is the share done. Null for today and later (they have the budget pill).
     */
    fun pastPill(day: BoardDay): PlannerBudget? {
        if (!day.isPast) return null
        val total = day.cards.size
        val done = day.cards.count { it.done }
        val value = if (total == 0) NOTHING else "$done of $total"
        val spoken = when {
            total == 0 -> "Nothing planned"
            else -> "$done of $total done"
        }
        return PlannerBudget(DONE, value, over = false, fill = if (total == 0) 0f else done.toFloat() / total, spoken = spoken)
    }

    /**
     * The open Fold's (and the Mac's) ⋯ menu for the week at [weekIndex] (0 is this week): Agenda view, the timeline's
     * toggle when the window has room for it ([timelineOpen] null when it hasn't), New event when a calendar can take
     * one, Plan your week, Review the week (from Friday, this week). Goals, No day yet and Someday stay on the page.
     */
    fun menu(board: WeekBoard, weekIndex: Int, timelineOpen: Boolean?, canAddEvent: Boolean, canPlan: Boolean): List<FoldedMenuItem> =
        buildList {
            add(FoldedMenuItem(FoldedMenuKind.AGENDA, AGENDA, ""))
            if (timelineOpen != null) {
                add(
                    if (timelineOpen) FoldedMenuItem(FoldedMenuKind.TIMELINE, BoardTimelineRules.HIDE, "")
                    else FoldedMenuItem(FoldedMenuKind.TIMELINE, BoardTimelineRules.SHOW, "today by the hour"),
                )
            }
            if (canAddEvent) add(FoldedMenuItem(FoldedMenuKind.NEW_EVENT, NEW_EVENT, ""))
            if (canPlan) add(FoldedMenuItem(FoldedMenuKind.PLAN_WEEK, PlanWeekRules.OPEN, "Obi helps"))
            if (board.offersReview && weekIndex == 0) add(FoldedMenuItem(FoldedMenuKind.REVIEW, BoardReviewRules.REVIEW, ""))
        }

    /** The menu's way back to the list of days. */
    const val AGENDA = "Agenda view"

    /** The menu's new event. */
    const val NEW_EVENT = "New event"

    /** The ⋯ button's spoken name. */
    const val MENU_LABEL = "More: Agenda view, timeline, new event, plan your week"

    /** The columns of the board's grid: Monday to Friday. */
    const val GRID = 5

    /** One column of the grid in a row [rowDp] wide with [gapDp] between columns. */
    fun gridColumn(rowDp: Float, gapDp: Float = 0f): Float =
        ((rowDp - gapDp * (GRID - 1)) / GRID).coerceAtLeast(0f)

    /**
     * The widths of [count] columns under the weekdays (the weekend's days, or the lists) in a row [rowDp] wide with
     * [gapDp] between: each a weekday column, from the row's left edge. More than [GRID] share the row equally rather
     * than run off its edge.
     */
    fun gridWidths(count: Int, rowDp: Float, gapDp: Float = 0f): List<Float> {
        if (count <= 0) return emptyList()
        val w = if (count <= GRID) gridColumn(rowDp, gapDp) else ((rowDp - gapDp * (count - 1)) / count).coerceAtLeast(0f)
        return List(count) { w }
    }
}
