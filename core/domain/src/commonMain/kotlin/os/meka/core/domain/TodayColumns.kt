package os.meka.core.domain

/** Which of Today's two columns a part sits in on the open Fold and the Mac (slice 2d). */
enum class TodayColumn { LEFT, RIGHT }

/**
 * The Today rebuild, slice 2c (Meka approved 2026-11-08 22:58): on the open Fold Today is two columns so the whole day
 * fits on one screen. The header (date, greeting, weather, the small clock) and the TASKS · NEXT · MAIL tiles run across
 * the top; the left column is what to do (Now, This evening, Today's tasks), the right one the shape of the day
 * (Schedule, Also today, News and the grey "Navilife sorted …" line). The column beside Today that held Needs you and
 * Coming up gives way to it: Needs you keeps its own tab, the coming days are Also today's week ahead and the Calendar.
 * Non-AI, pure.
 *
 * - [splits]: Today splits wherever it had a column beside it (from [CommandCentreRules.TWO_DP]); the closed Fold
 *   stays one column.
 * - [column]: the column each part of [TodayOrderRules.ORDER] sits in. Each column keeps that order.
 * - [LEFT_SHARE]: the columns share the width evenly, each about a folded phone wide on the open Fold.
 * - [HEADER_MAX_SHARE]: the header across the top takes at most this share of the height (it scrolls beyond), so the
 *   columns always have most of the screen.
 *
 * The Mac (slice 2d): the same two columns, in place of Today | Needs you | Coming up. Its window is free to be any width,
 * so [macSplits] uses the Mac's own breakpoint ([MacCommandCentreRules.layout]) and an open task keeps a column of its
 * own on the right ([macWidths]: a desktop has the room, and the task sits beside the day it belongs to); Today splits
 * again inside what's left only while that is still wide enough ([macTodaySplits]), else it is one list.
 */
object TodayColumnsRules {
    const val LEFT_SHARE = 0.5f
    const val HEADER_MAX_SHARE = 0.4f

    /**
     * Found and fixing week 5, A12 (Today on the open Fold): once a split column has scrolled, its top fades over this
     * much, a whole task row with its meta line ([TodayBesideRules.ROW_WITH_LINE_MAX_DP]) and its gap, so a row passing
     * under the header's habit chips dissolves rather than showing half a title. The 24 dp foot fade was too thin there:
     * "Fix the gate latch" read as sliced and Up next's buttons as cut. At rest the top is crisp.
     */
    const val TOP_FADE_DP = 56

    /** How opaque a split column's content is [yDp] below its top once scrolled (0 at the edge, whole past the fade). */
    fun topFadeAlpha(yDp: Float): Float = (yDp / TOP_FADE_DP).coerceIn(0f, 1f)

    fun splits(layout: CommandLayout): Boolean = layout != CommandLayout.SINGLE

    /**
     * Walk-through week 4, A6: a running fast's live card sits in the header only while Today is one list. Split, the
     * header is capped at [HEADER_MAX_SHARE] of the height and the card was crushed to a strip under Up next (no label,
     * time or End), so it leads the left column instead, whole, above Now.
     */
    fun fastInHeader(split: Boolean): Boolean = !split

    fun splits(contentWidthDp: Float): Boolean = splits(CommandCentreRules.layout(contentWidthDp))

    fun column(slot: TodaySlot): TodayColumn = when (slot) {
        TodaySlot.CLEAR, TodaySlot.FROM_YESTERDAY, TodaySlot.UP_NEXT, TodaySlot.EVENING, TodaySlot.NEEDS_YOU,
        TodaySlot.ANYTIME, TodaySlot.DONE -> TodayColumn.LEFT
        TodaySlot.TIMELINE, TodaySlot.CARDS -> TodayColumn.RIGHT
    }

    /** The parts of [TodayOrderRules.ORDER] in [col], in their order. */
    fun slots(col: TodayColumn): List<TodaySlot> = TodayOrderRules.ORDER.filter { column(it) == col }

    /**
     * Walk-through week 4, A6 (3): split, the health line and the setup card ("12 steps left") sit in the right column
     * straight after the Schedule, not above it. Heading the column they pushed the day down, so the open Fold's schedule
     * stopped at 17:15 and never showed the commute or the evening. They still never push Now down (week 3, A1).
     */
    val HOUSEKEEPING_AFTER: TodaySlot = TodaySlot.TIMELINE

    /** True for the right column's parts above the health line and setup card (see [HOUSEKEEPING_AFTER]). */
    fun aboveHousekeeping(slot: TodaySlot): Boolean =
        column(slot) == TodayColumn.RIGHT &&
            TodayOrderRules.ORDER.indexOf(slot) <= TodayOrderRules.ORDER.indexOf(HOUSEKEEPING_AFTER)

    /** The right column in order, with null where the health line and setup card go. */
    fun rightWithHousekeeping(): List<TodaySlot?> =
        slots(TodayColumn.RIGHT).filter { aboveHousekeeping(it) } + listOf(null) +
            slots(TodayColumn.RIGHT).filterNot { aboveHousekeeping(it) }

    /** The open task's column on the Mac: about a third of the window, never narrower than [MAC_DETAIL_MIN] pt. */
    const val MAC_DETAIL_SHARE = 0.36f
    const val MAC_DETAIL_MIN = 320f
    /** Today never narrower than this beside an open task. */
    const val MAC_TODAY_MIN = 300f

    /** The Mac's Today is two columns from the width that used to put a column beside it. */
    fun macSplits(contentWidthPt: Float): Boolean = MacCommandCentreRules.layout(contentWidthPt) != CommandLayout.SINGLE

    /**
     * Left to right, adding up to [contentWidthPt]: Today alone, or Today then the open task's detail. Below the split
     * width Today is the whole width either way (the narrow window's own behaviour is unchanged).
     */
    fun macWidths(contentWidthPt: Float, taskOpen: Boolean): List<Float> {
        val w = maxOf(0f, contentWidthPt)
        if (!taskOpen || !macSplits(w)) return listOf(w)
        val detail = maxOf(MAC_DETAIL_MIN, kotlin.math.floor(w * MAC_DETAIL_SHARE)).coerceAtMost(maxOf(0f, w - MAC_TODAY_MIN))
        return listOf(w - detail, detail)
    }

    /** Today itself splits into its two columns while its own width is at least the split width. */
    fun macTodaySplits(todayWidthPt: Float): Boolean = macSplits(todayWidthPt)
}
