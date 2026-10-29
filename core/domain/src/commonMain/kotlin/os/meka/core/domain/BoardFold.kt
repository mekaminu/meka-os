package os.meka.core.domain

/** One pill of the folded Board's 7-day strip ("Mon 19"): today gold, a dot when it has tasks, amber when stacking up. */
data class DayStripPill(
    val epochDay: Long,
    /** "Mon" */
    val weekday: String,
    /** "19" */
    val number: String,
    val isToday: Boolean,
    val isPast: Boolean,
    /** A dot under the number: the day has open tasks. */
    val hasTasks: Boolean,
    /** Amber: the day is stacking up ([BoardDay.pressure]). */
    val stacking: Boolean,
    /** Screen readers: "Thursday 22 October, 3 tasks, stacking up". */
    val accessibilityLabel: String,
    /** The folded mockup's short bars under the number: one an open task, up to three ([BoardFoldedRules.dots]). */
    val dots: Int = 0,
) {
    /** A held card can be dropped on it (today and later). */
    val canDrop: Boolean get() = !isPast
}

/**
 * The Week board folded, on the cover screen (by hand, slice 3; Meka 2026-10-21 19:06: one lane stopped half-scrolled
 * between days "doesn't make sense and looks weird"), non-AI and pure:
 * - one day at a time, full width, paged with a snap ([snapPage]): a swipe moves one day, a slow drag settles on the
 *   nearest, never between two days;
 * - a strip of the week's seven days across the top ([strip]): tap a pill to jump to that day, and drop a held card on
 *   a pill to move it there (the pill swells while it hovers);
 * - while a card is held near the lane's left or right edge, the lane turns a page after [FLIP_MS] ([edgeFlip]);
 * - unfolding keeps the same day in view ([focusAfterFold]); the layout is chosen by the window's width class ([wide]),
 *   live as he folds or unfolds, the new one fading in ([RELAYOUT_FROM], [relayoutMs]), and unfolded the day's row is
 *   brought into view ([revealScroll]).
 */
object BoardFoldRules {
    /** From this width (dp) the Board is the week of columns (open Fold, Mac); below it the folded one-day layout. */
    const val WIDE_DP = 600f

    /** The width class: the columns at [WIDE_DP] and wider, else the folded layout. */
    fun wide(widthDp: Float): Boolean = widthDp >= WIDE_DP

    /** As he folds or unfolds, the new layout fades in from this opacity (the old one is gone with the old width). */
    const val RELAYOUT_FROM = 0f

    /** The fade's length (ms): the appear token's 220, or the short 120 cross-fade with Reduced motion. */
    fun relayoutMs(reduced: Boolean): Int = if (reduced) 120 else 220

    /**
     * Unfolded on a day whose row starts [rowTopDp] down the week's scroll (the weekend's row sits under the weekdays')
     * and is [rowHeightDp] tall, in a view [viewDp] tall scrolled [scrolledDp]: where to scroll (dp) so the row shows
     * its head and a third of the view below it, [marginDp] under the view's top; null when it already shows.
     */
    fun revealScroll(rowTopDp: Float, rowHeightDp: Float, viewDp: Float, scrolledDp: Float, marginDp: Float = 16f): Float? {
        if (!(rowTopDp.isFinite() && rowHeightDp.isFinite() && viewDp.isFinite() && scrolledDp.isFinite()) || viewDp <= 0f) return null
        val top = rowTopDp - scrolledDp
        val need = minOf(rowHeightDp.coerceAtLeast(0f), viewDp / 3f)
        if (top >= 0f && top + need <= viewDp) return null
        return (rowTopDp - marginDp).coerceAtLeast(0f)
    }

    /** A swipe faster than this (dp/s) turns a page whichever way it was going; slower settles on the nearest day. */
    const val FLING_DP_PER_S = 400f

    /** How long a held card must stay at the lane's edge before the next or previous day comes in (ms). */
    const val FLIP_MS = 550L

    /** A hovered pill swells to this. */
    const val PILL_SWELL = 1.15f

    /** The seven pills for [week]. */
    fun strip(week: BoardWeek): List<DayStripPill> = week.days.map(::pill)

    fun pill(day: BoardDay): DayStripPill {
        val open = day.openCards.size
        val label = buildString {
            append(day.accessibilityLabel.substringBefore(',').ifEmpty { "${day.weekday} ${day.number}" })
            if (day.isToday) append(", today")
            append(
                when (open) {
                    0 -> ", nothing to do"
                    1 -> ", 1 task"
                    else -> ", $open tasks"
                },
            )
            if (day.pressure != null) append(", stacking up")
            if (!day.isPast) append(". Drop a task here to move it to this day")
        }
        return DayStripPill(
            epochDay = day.epochDay, weekday = day.weekday, number = day.number, isToday = day.isToday, isPast = day.isPast,
            hasTasks = open > 0, stacking = day.pressure != null, accessibilityLabel = label,
            dots = BoardFoldedRules.dots(day),
        )
    }

    /** The day in view for a scroll [offset] (px) with pages [page] px wide: the nearest. */
    fun pageOf(offset: Float, page: Float, pages: Int): Int {
        if (pages <= 0 || page <= 0f) return 0
        return kotlin.math.round(offset / page).toInt().coerceIn(0, pages - 1)
    }

    /**
     * Where a fling settles: with a quick swipe ([velocity] px/s beyond [threshold] px/s, positive towards later
     * days) the next page that way from where the finger left it; otherwise the nearest. Always a whole day.
     */
    fun snapPage(offset: Float, page: Float, velocity: Float, threshold: Float, pages: Int): Int {
        if (pages <= 0 || page <= 0f) return 0
        val at = offset / page
        val to = when {
            velocity > threshold -> kotlin.math.ceil(at - EPSILON)
            velocity < -threshold -> kotlin.math.floor(at + EPSILON)
            else -> kotlin.math.round(at)
        }
        return to.toInt().coerceIn(0, pages - 1)
    }

    /** While a card is held: -1 at the lane's left edge (the day before), +1 at its right edge, 0 elsewhere. */
    fun edgeFlip(x: Float, width: Float, edge: Float): Int = when {
        width <= 0f -> 0
        x < edge -> -1
        x > width - edge -> 1
        else -> 0
    }

    /**
     * The day to keep in view across a fold or unfold: the day folded in view when folding out; unfolding shows that
     * day's week. With nothing in view yet, today. [days] are the board's days in order; returns an index into them.
     */
    fun focusAfterFold(days: List<BoardDay>, focus: Long?): Int {
        val byFocus = focus?.let { f -> days.indexOfFirst { it.epochDay == f } } ?: -1
        if (byFocus >= 0) return byFocus
        return days.indexOfFirst { it.isToday }.coerceAtLeast(0)
    }

    /** Unfolded, the first column of the week holding day [index]. */
    fun weekStartIndex(index: Int): Int = index - index % 7

    /**
     * The day folded in view when unfolded at week [weekIndex]: the day already chosen ([current]) while that week holds
     * it (so unfolding on Saturday and folding again comes back to Saturday), else today if that week holds it, else
     * its Monday.
     */
    fun focusInWeek(days: List<BoardDay>, weekIndex: Int, current: Long? = null): Long? {
        val week = days.drop(weekIndex * 7).take(7)
        week.firstOrNull { it.epochDay == current }?.let { return it.epochDay }
        return (week.firstOrNull { it.isToday } ?: week.firstOrNull())?.epochDay
    }

    private const val EPSILON = 0.02f
}
