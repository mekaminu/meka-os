package os.meka.core.domain

/**
 * Today's collapsing header (watch face item, Meka 2026-10-12 18:04, part 2). As Today scrolls, the watch face shrinks
 * smoothly with the scroll (not a jump) and glides into a small [COMPACT_DP] clock beside the greeting, while the date,
 * weather and train lines fold into one compact line ([compactLine]); scrolling back to the top grows it back. The face
 * stays pinned at the header's top-right corner the whole way, so it never jumps between two drawings: it is one face,
 * scaled ([faceScale]) about that corner. A bar behind it (the greeting and the compact line) fades in over the last
 * part of the glide ([barAlpha]), so the rows scrolling under it don't show through.
 *
 * On the closed Fold's cover screen ([collapses]) and in the Mac's Today header; the open Fold (two panes) keeps the
 * face at its size, as before. Reduced motion: the header switches between the two states at half way, no glide
 * ([shown]). In the compact clock the habit dots aren't tap targets ([dotsTappable]); a tap on it opens the whole day,
 * as the full face does, and a tap on the bar's text scrolls Today back to the top.
 */
object HeaderCollapseRules {
    /** The compact clock's size beside the greeting. */
    const val COMPACT_DP = 40

    /** The bar behind the compact clock starts fading in here and is fully there at 1. */
    const val BAR_FROM = 0.6f

    /** Past this the face counts as compact: its habit dots stop taking taps. */
    const val COMPACT_FROM = 0.5f

    /** Whether Today's header collapses as it scrolls: on the cover screen and the Mac, not on the open Fold. */
    fun collapses(cover: Boolean, mac: Boolean = false): Boolean = cover || mac

    /**
     * How far the header has collapsed, 0 (at the top) → 1 (compact), from the list's scroll: [firstIndex] is the
     * first visible item (the header is item 0), [offsetPx] how far it has scrolled past that item's top, [rangePx]
     * the distance over which the glide runs (the header's height less the bar's).
     */
    fun progress(firstIndex: Int, offsetPx: Float, rangePx: Float): Float = when {
        firstIndex > 0 -> 1f
        rangePx <= 0f -> if (offsetPx > 0f) 1f else 0f
        else -> (offsetPx / rangePx).coerceIn(0f, 1f)
    }

    /** The glide's range: the header's height less the compact bar's, never under one pixel. */
    fun rangePx(headerPx: Float, barPx: Float): Float = maxOf(headerPx - barPx, 1f)

    /** What the header shows: the scroll's [progress], or with [reduced] motion one state or the other (half way). */
    fun shown(progress: Float, reduced: Boolean): Float {
        val p = progress.coerceIn(0f, 1f)
        return if (reduced) (if (p >= 0.5f) 1f else 0f) else p
    }

    /** The face's size at [shown] (dp): [fullDp] at the top, [COMPACT_DP] collapsed. */
    fun faceDp(shown: Float, fullDp: Int): Float {
        val p = shown.coerceIn(0f, 1f)
        return fullDp + (COMPACT_DP - fullDp) * p
    }

    /** The face's scale about its top-right corner at [shown]: 1 at the top, [COMPACT_DP] / [fullDp] collapsed. */
    fun faceScale(shown: Float, fullDp: Int): Float = if (fullDp <= 0) 1f else faceDp(shown, fullDp) / fullDp

    /**
     * The face's top edge at [shown] (dp from the list's top): where the header puts it at rest ([restDp]), rising to
     * the compact bar's top padding ([compactDp]) so the bar stays slim.
     */
    fun faceTopDp(shown: Float, restDp: Float, compactDp: Float): Float =
        restDp + (compactDp - restDp) * shown.coerceIn(0f, 1f)

    /** The compact bar's alpha (its background, greeting and line): nothing until [BAR_FROM], all there at 1. */
    fun barAlpha(shown: Float): Float = ((shown - BAR_FROM) / (1f - BAR_FROM)).coerceIn(0f, 1f)

    /** Whether the bar is drawn (and takes taps) at all. */
    fun barShown(shown: Float): Boolean = barAlpha(shown) > 0f

    /** Whether the face's habit dots take taps: only while it's still the full face. */
    fun dotsTappable(shown: Float): Boolean = shown < COMPACT_FROM

    /**
     * The one compact line under the greeting: the date and the weather's first part ("Tue 13 Oct · 14°"), or the
     * date alone; the weather's later parts and the other lines stay in the list.
     */
    fun compactLine(dateLabel: String, weatherLine: String?): String {
        val weather = weatherLine?.substringBefore(" · ")?.substringBefore("\n")?.trim().orEmpty()
        return listOf(dateLabel.trim(), weather).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
