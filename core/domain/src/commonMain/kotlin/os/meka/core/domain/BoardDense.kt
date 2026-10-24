package os.meka.core.domain

/**
 * The Week board's density pass (Claude's side-by-side check of docs/screens/week-board, 2026-10-24 18:10: "Option B is
 * closer but still not the mockup on the open Fold"). The unfolded Fold is about 830 dp wide and the mockup 1280 px, yet
 * the app drew everything bigger than the mockup, so only the five day columns fitted, each card was huge, and the
 * timeline and the lists sat off screen. Non-AI and pure, the same on the unfolded Fold and the Mac; the cover screen
 * keeps its sizes (it was already close) except that it loses the "Any time" chip too.
 *
 * - **The mockup's proportions** ([DATE_SP] … [CARD_GAP]): a day's date 19 sp serif, the weekday 11, a card's title
 *   13.5, its chips and figures 10.5 mono, the foot's area name 11, 10 dp inside a card, 8 between cards, an 18 dp tick
 *   and a 6 dp dot. With the timeline open the five weekday columns are about 110 dp each at 830 dp.
 * - **The timeline is there by default** ([timelineByDefault]): unfolded and on the Mac it opens whenever the weekday
 *   columns keep [MIN_COLUMN_DP] beside it (the mockup always has it; "Hide timeline" stays his choice).
 * - **No "Any time" chip** ([chip]): a task with no time shows no chip, its length alone on the right; the gold chip only
 *   for a set time, blue for events. A card with neither has no top line at all.
 * - **The weekend underneath is smaller** ([weekendWidths]): Saturday and Sunday are at most [WEEKEND_SCALE] times a
 *   weekday column wide (they were half the row each, full-width cards in body type), left-aligned.
 */
object BoardDenseRules {
    /** A day's date in serif ("27 Oct"), sp. */
    const val DATE_SP = 19f

    /** The weekday beside it ("Mon"), sp. */
    const val WEEKDAY_SP = 11f

    /** A card's title, sp (the mockup's 15 px at 1280, read on a phone held closer). */
    const val TITLE_SP = 13.5f

    /** Time chips, lengths and the budget's figures, mono, sp. */
    const val CHIP_SP = 10.5f

    /** The area's name beside its dot in a card's foot, sp. */
    const val FOOT_SP = 11f

    /** Inside a card, dp. */
    const val CARD_PAD = 10

    /** Between cards in a column, dp. */
    const val CARD_GAP = 8

    /** The tick's drawn circle in a card's foot, dp (its touch target stays the platform's minimum). */
    const val TICK = 18

    /** The area's colour dot, dp. */
    const val DOT = 6

    /** The least a weekday column keeps beside the open timeline, dp (about 110 at the Fold's 830 with the 200 panel). */
    const val MIN_COLUMN_DP = 100f

    /** The weekend's columns are at most this many weekday columns wide. */
    const val WEEKEND_SCALE = 1.5f

    /**
     * Whether the timeline starts open on a board [boardDp] wide ([spareDp] for the gutters and the gap): whenever the
     * five weekday columns keep [MIN_COLUMN_DP] beside the panel ([BoardTimelineRules.panelDp]).
     */
    fun timelineByDefault(boardDp: Float, spareDp: Float): Boolean =
        (boardDp - spareDp - BoardTimelineRules.panelDp(boardDp)) / BoardWeekLayoutRules.WEEKDAYS >= MIN_COLUMN_DP

    /**
     * A day card's top line: the gold chip only for a set time, the length on the right when it has one, and nothing at
     * all when it has neither (never a quiet "Any time" chip).
     */
    fun top(card: BoardCard): PlannerCardTop? = BoardPlannerRules.top(card)

    /** The time chip's words ("12:30"), "" for none (for Swift). */
    fun chip(card: BoardCard): String = top(card)?.time ?: ""

    /** The length on the right ("0:45"), "" for none (for Swift). */
    fun duration(card: BoardCard): String = top(card)?.duration ?: ""

    /**
     * Saturday's and Sunday's widths under the weekdays: the row's equal share, but never wider than [WEEKEND_SCALE]
     * weekday columns ([weekdayDp]), so their cards look like the week's rather than full-width slabs.
     */
    fun weekendWidths(weekend: List<BoardDay>, rowDp: Float, weekdayDp: Float, opened: Set<Long>): List<Float> {
        val cap = weekdayDp * WEEKEND_SCALE
        return BoardWeekLayoutRules.rowWidths(weekend, rowDp, opened).map { w -> if (weekdayDp > 0f) minOf(w, cap) else w }
    }
}
