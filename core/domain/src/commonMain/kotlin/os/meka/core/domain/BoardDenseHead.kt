package os.meka.core.domain

/**
 * The Week board's density pass, slice 2 (the rest of Claude's side-by-side check of docs/screens/week-board,
 * app-fold-unfolded.png beside design-1280.png, 2026-10-24): the header, "+ add" and the narrow budget pills at the
 * mockup's size on the unfolded Fold and the Mac. Non-AI and pure; the cover screen keeps its sizes.
 *
 * - **The header at the mockup's size** ([MONTH_SP] … [NUDGE_ORB]): "October 2026" 28 serif (it was the 34 greeting),
 *   "W44" 12 mono, Today / Hide timeline / Obi's name 13, Obi's line 12, the round buttons and the pills 36 high.
 * - **The goals fold into the header** ([goalsInHeader]): unfolded and on the Mac the week's goals and "+ Goal" sit in
 *   the header's row after "W44" (the mockup has no goals row), each goal at most [GOAL_MAX_DP] wide; "+ Goal" drops its
 *   hint there ([goalHint]: the hint stays on the cover screen, where the goals keep their own row).
 * - **"+ add" is a slim dashed row** ([ADD_HEIGHT], [ADD_SP]): 28 high with 11 type, as the mockup's (it was a 40 dp
 *   slab), the touch target still the platform's minimum around it.
 * - **The narrow pills on one or two lines** ([pillValue]): a narrow lane stacks "planned" over its figures, and the
 *   figures keep one line: "nothing yet / 4h" becomes "0m / 4h" there (the spoken line still says "nothing planned"),
 *   so no pill wraps to three lines. The weekend's pills and "+ add" take the same smaller sizes.
 */
object BoardDenseHeadRules {
    /** "October 2026", sp, serif. */
    const val MONTH_SP = 28f

    /** "W44" beside it, sp, mono. */
    const val WEEK_SP = 12f

    /** Today, Hide timeline, Review, Plan your week and Obi's name in the header, sp. */
    const val LABEL_SP = 13f

    /** Obi's line in the header's pill, and the goals' titles, sp. */
    const val NUDGE_SP = 12f

    /** The round ‹ › and Today, Obi's pill and the goals' pills: this high, dp (the mockup's 36 at its scale). */
    const val BUTTON = 36

    /** Obi's orb in the header's pill, dp. */
    const val NUDGE_ORB = 20

    /** A goal's pill in the header is at most this wide, dp (its title cut short with "…"). */
    const val GOAL_MAX_DP = 160

    /** The "+ add" dashed row's drawn height, dp. */
    const val ADD_HEIGHT = 28

    /** "+ add", sp. */
    const val ADD_SP = 11f

    /** Nothing planned yet, on a narrow lane's one line of figures: "0m / 4h" (a bare dash read as a fault; A6, 2026-10-30). */
    const val NOTHING_SHORT = "0m"

    /** Whether the week's goals sit in the header's row (unfolded and the Mac) rather than a row of their own. */
    fun goalsInHeader(wide: Boolean): Boolean = wide

    /** Whether "+ Goal" carries its hint ("Pick up to three goals for the week"): only in a row of its own. */
    fun goalHint(empty: Boolean, inHeader: Boolean): Boolean = empty && !inHeader

    /**
     * The budget pill's figures: on a narrow lane "nothing yet / 4h" is "0m / 4h" so the figures keep one line under
     * "planned"; elsewhere (and once anything is planned) the figures as they are.
     */
    fun pillValue(b: PlannerBudget, narrow: Boolean): String =
        if (narrow && b.value.startsWith(BoardPlannerRules.NOTHING_YET)) NOTHING_SHORT + b.value.removePrefix(BoardPlannerRules.NOTHING_YET)
        else b.value

    /** How many lines the pill's words take: one beside each other, two stacked on a narrow lane. */
    fun pillLines(narrow: Boolean): Int = if (BoardMatchRules.pillStacked(narrow)) 2 else 1
}
