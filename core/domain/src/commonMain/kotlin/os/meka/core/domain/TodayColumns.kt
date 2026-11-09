package os.meka.core.domain

/** Which of Today's two columns a part sits in on the open Fold (and, from slice 2d, the Mac). */
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
 */
object TodayColumnsRules {
    const val LEFT_SHARE = 0.5f
    const val HEADER_MAX_SHARE = 0.4f

    fun splits(layout: CommandLayout): Boolean = layout != CommandLayout.SINGLE

    fun splits(contentWidthDp: Float): Boolean = splits(CommandCentreRules.layout(contentWidthDp))

    fun column(slot: TodaySlot): TodayColumn = when (slot) {
        TodaySlot.CLEAR, TodaySlot.FROM_YESTERDAY, TodaySlot.UP_NEXT, TodaySlot.EVENING, TodaySlot.NEEDS_YOU,
        TodaySlot.ANYTIME, TodaySlot.DONE -> TodayColumn.LEFT
        TodaySlot.TIMELINE, TodaySlot.CARDS -> TodayColumn.RIGHT
    }

    /** The parts of [TodayOrderRules.ORDER] in [col], in their order. */
    fun slots(col: TodayColumn): List<TodaySlot> = TodayOrderRules.ORDER.filter { column(it) == col }
}
