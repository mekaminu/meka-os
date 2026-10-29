package os.meka.core.domain

/**
 * The Week board's shots beside their mockups (alignment pass, "Next", 2026-10-29): CI's folded shot next to
 * `docs/design/week-board/folded.dc.html` and the open Fold's next to `option-b.dc.html`, each with a column-guide
 * overlay (tools/design-render/beside.py; the pictures in docs/screens/week-board/). What the guides showed, fixed here:
 *
 * - **The lists' headings** under the weekend ("Someday", "Side hustle", "House", "No day yet") were drawn in the day
 *   dates' large type and, a weekday wide, broke mid-word ("Someda / y"). The mockup's are a size below a day's date:
 *   [LIST_HEAD_SP] semibold, on [LIST_HEAD_LINES] line, cut with "…" only if a name can't fit, never split in a word.
 * - **Obi's pill in the header** wraps to two lines at the open Fold's 829 dp where the mockup's 1280 has room for one;
 *   it stays two lines (the readability gate never lets a word be cut short), so it is not changed here.
 * - **The timeline's hour lines crossed an event's title** ("Commute to work" from 07:40 has the 08 line through its
 *   name): every block now has an opaque face ([BLOCKS_OPAQUE]), not only one the now-line runs under.
 * - **Folded, the day's cards sat 4 dp inside** the strip, the day's line and Obi's card: the pager's inset is now
 *   [foldedPagerInset] (the gutter less the column's two paddings), so every card shares their left and right edges;
 *   and the gap under Obi's card is one step ([FOLDED_SPACE_AFTER_OBI_DP] more than the column's own padding), not 24 dp.
 *
 * Non-AI and pure, the same on the Fold and the Mac.
 */
object BoardBesideRules {
    /** A list's heading under the weekend: a size below a day's date (19), as the mockup. */
    const val LIST_HEAD_SP = 16f

    /** A list's heading stays on one line: "…" if it must, never a word split over two lines. */
    const val LIST_HEAD_LINES = 1

    /** Folded, the extra space under Obi's card before the day's column: none (the column's own 8 dp top padding). */
    const val FOLDED_SPACE_AFTER_OBI_DP = 0f

    /** Every timeline block has an opaque face, so neither an hour line nor the now-line shows through its title. */
    const val BLOCKS_OPAQUE = true

    /**
     * The folded pager's side inset so a day's cards line up with the header's gutter: [gutterDp] less the column's
     * outer [outerDp] and inner [innerDp] paddings, never below 0.
     */
    fun foldedPagerInset(gutterDp: Float, outerDp: Float, innerDp: Float): Float {
        if (!gutterDp.isFinite() || !outerDp.isFinite() || !innerDp.isFinite()) return 0f
        return (gutterDp - outerDp - innerDp).coerceAtLeast(0f)
    }

    /** The folded page's width for a window [maxDp] wide with [insetDp] each side (one day per page). */
    fun foldedColumn(maxDp: Float, insetDp: Float): Float {
        if (!maxDp.isFinite() || maxDp <= 0f) return 0f
        return (maxDp - 2 * insetDp.coerceAtLeast(0f)).coerceAtLeast(0f)
    }
}
