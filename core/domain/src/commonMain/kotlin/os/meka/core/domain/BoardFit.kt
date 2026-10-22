package os.meka.core.domain

/**
 * How a Week board lane fits its words (Week board by hand, slice 8; found by the quality gate 2026-10-22 on the
 * unfolded Fold, `inner-dark-many`: a lane is ~134 dp there, so the 48 dp tick column left a card's title about 50 dp
 * and cut it to "Fix the gate l…", and the header's tag, capacity and free-time lines were clipped). Non-AI and pure,
 * the same on the Fold and the Mac.
 *
 * Below [COMPACT_LANE_DP] a lane is compact:
 * - the tick sits in a [TICK_COLUMN_COMPACT_DP] column (the circle stays 20 dp; its touch target stays 48 dp, the
 *   platform widening a small target's touch area past its edges), so the title gets the width;
 * - the title takes the design's card size (13.5 px there: the caption size) and may run to [titleLines] lines rather
 *   than be cut after a word or two ([titleReadable]);
 * - the tag ("Today · Office") goes on its own line under the day number instead of squeezing beside it;
 * - the capacity line is short ([BoardCapacity.shortLine], "15m of 4h", planned of free) and free time says only when
 *   ("19:00–21:00 free", the length is already on the capacity line), with "+1 more" allowed onto a second line.
 */
object BoardFitRules {
    /** A lane narrower than this (dp) is compact. */
    const val COMPACT_LANE_DP = 180f

    /** The tick's column (dp) on a compact lane; a roomy lane keeps [BoardHandRules.CHECK_TARGET]. */
    const val TICK_COLUMN_COMPACT_DP = 28

    /** A compact lane's insets around its cards (dp): 4 each side outside its edge and 4 inside. */
    const val LANE_INSETS_DP = 16f

    fun compact(laneDp: Float): Boolean = laneDp < COMPACT_LANE_DP

    /** A card this wide (dp) came from a compact lane: the lifted card under the finger keeps the lane's look. */
    fun compactCard(cardDp: Float): Boolean = cardDp + LANE_INSETS_DP < COMPACT_LANE_DP

    /** A title this short (characters) always fits whole on a card; longer ones may end in "…". */
    const val WHOLE_TITLE = 24

    /** A cut title still shows at least this much (characters) on its complete lines before the line with the "…". */
    const val MIN_BEFORE_CUT = 12

    /**
     * The quality gate's test of "cut to a word or two" (the shots read each title's laid-out text): [cut] when the
     * title ends in "…"; [wholeLines] the characters on its lines before the cut one.
     */
    fun titleReadable(title: String, cut: Boolean, wholeLines: Int): Boolean = when {
        !cut -> true
        title.length <= WHOLE_TITLE -> false
        else -> wholeLines >= MIN_BEFORE_CUT
    }

    fun tickColumn(compact: Boolean): Int = if (compact) TICK_COLUMN_COMPACT_DP else BoardHandRules.CHECK_TARGET

    /** How many lines a card's title may take: 3 on a compact lane, 2 otherwise (the design's "up to 2 lines"). */
    fun titleLines(compact: Boolean): Int = if (compact) 3 else 2

    /** The tag beside the number on a roomy lane, under it on a compact one. */
    fun tagBelow(compact: Boolean): Boolean = compact

    /**
     * Slice 9 (the re-shot of slice 8, 2026-10-22: Sat and Sun, with no "Office", started their cards a line higher
     * than Thu and Fri, and today's a few dp lower again for its underline): the tag's line under the number, or null
     * when the lane has none. A compact lane keeps the line even on a day without a tag ("", drawn blank and silent),
     * so every lane's first card starts level; a roomy lane's tag sits beside the number and takes no line.
     */
    fun tagLine(day: BoardDay, compact: Boolean): String? =
        if (!tagBelow(compact)) null else BoardLookRules.tag(day) ?: ""

    /** Today's gold underline keeps its slot on every open lane, drawn only on today ([underlineLit]), for the same reason. */
    fun underlineLit(day: BoardDay): Boolean = day.isToday

    /** How many lines the free-time line may take: 2 on a compact lane ("+1 more" wraps), 1 otherwise. */
    fun freeLines(compact: Boolean): Int = if (compact) 2 else 1

    fun capacityLine(c: BoardCapacity, compact: Boolean): String = if (compact) c.shortLine else c.line

    /** The day's free-time line for this lane, or null when the day has none. */
    fun freeLine(free: List<BoardFreeTime>, compact: Boolean): FreeLine? {
        val full = BoardLookRules.freeLine(free) ?: return null
        if (!compact) return full
        val longest = free.maxWithOrNull(compareBy<BoardFreeTime> { it.minutes }.thenByDescending { it.startMinute }) ?: return null
        val more = free.size - 1
        return FreeLine("${longest.label} free" + if (more > 0) " · +$more more" else "", full.spoken)
    }
}
