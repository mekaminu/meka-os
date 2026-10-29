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
}
