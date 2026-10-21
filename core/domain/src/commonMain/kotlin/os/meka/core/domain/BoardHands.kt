package os.meka.core.domain

/**
 * The Week board by hand (Meka 2026-10-21 19:00, "can't add items on the day and can't move this around or up and
 * down"), slice 1: the rules both apps share for adding at the top of a day, a finger drag that wins over scrolling,
 * where a held card would land, the edge auto-scroll, and the tick that keeps a card in place for a moment.
 */
object BoardHandRules {
    /** Press-and-hold before a card lifts (a finger drifting less than the touch slop meanwhile). */
    const val HOLD_MS = 250L

    /** The always-visible add row at the top of each day from today (Sunsama's "+ Add task"). */
    const val ADD = "+ Add a task"
    const val ADD_HINT = "What needs doing?"
    const val ADD_LABEL_PREFIX = "Add a task for "

    /** The optional estimate chips under the add field. */
    val ESTIMATES: List<Int> get() = TaskEstimateRules.CHOICES

    /** A just-ticked card stays where it was this long before it settles below the open ones. */
    const val SETTLE_MS = 1_200L

    /** The tick's own touch target (dp / pt), separate from the card's tap for its menu. */
    const val CHECK_TARGET = 48

    /** Auto-scroll: within this distance of an edge (dp) the board scrolls, up to this much a frame (dp). */
    const val EDGE = 56f
    const val MAX_STEP = 14f

    fun addLabel(dayTitle: String): String = ADD_LABEL_PREFIX + dayTitle

    /** The estimate chips, the picked one lit (a second tap on it clears the pick). */
    fun estimateChips(picked: Int?): List<AddEstimateChip> =
        ESTIMATES.map { AddEstimateChip(it, TaskEstimateRules.label(it), it == picked) }

    /** The chip's tap: picks it, or clears the pick when it was already lit. */
    fun toggleEstimate(picked: Int?, tapped: Int): Int? = if (picked == tapped) null else tapped

    /**
     * Where a held card lands among a day's open cards other than itself: the number whose middle is above the finger
     * ([centers] in the same order as the cards, the held one left out).
     */
    fun insertIndex(centers: List<Float>, pointer: Float): Int = centers.count { it < pointer }

    /**
     * Where to draw the gold insertion gap, as an index into [ids] (the day's open cards as shown, the held one
     * included when it is from this day): the gap goes before ids[result], or after the last when it equals ids.size.
     * Null when letting go there would leave the card where it is (no gap, its own place glows instead).
     */
    fun gapBefore(ids: List<String>, held: String, to: Int): Int? {
        val others = ids.filter { it != held }
        val from = ids.indexOf(held)
        val at = to.coerceIn(0, others.size)
        if (from >= 0 && at == from) return null
        return if (at == others.size) ids.size else ids.indexOf(others[at])
    }

    /**
     * How far the board scrolls this frame while a card is held at [pointer] along an axis running [start]..[end]:
     * negative near the start, positive near the end, ramping up to [maxStep] at the very edge, 0 in between.
     */
    fun edgeStep(pointer: Float, start: Float, end: Float, edge: Float, maxStep: Float): Float {
        if (end - start <= edge * 2) return 0f
        val fromStart = pointer - start
        val fromEnd = end - pointer
        return when {
            fromStart < edge -> -maxStep * ((edge - fromStart.coerceAtLeast(0f)) / edge).coerceIn(0f, 1f)
            fromEnd < edge -> maxStep * ((edge - fromEnd.coerceAtLeast(0f)) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    /**
     * The day's cards in the order to show them: the core's order, except cards ticked a moment ago ([held]: id → the
     * place it had before the tick), which keep that place until they settle.
     */
    fun settleOrder(ids: List<String>, held: Map<String, Int>): List<String> {
        val keep = held.filterKeys { it in ids }
        if (keep.isEmpty()) return ids
        val out = ids.filter { it !in keep }.toMutableList()
        keep.entries.sortedBy { it.value }.forEach { (id, at) -> out.add(at.coerceIn(0, out.size), id) }
        return out
    }

    /** The tick's spoken label: what a tap does. */
    fun tickLabel(title: String, done: Boolean): String = if (done) "Done: $title. Tap to un-tick" else "Tick off $title"
}

/** One estimate chip under the add field: "30m", lit when picked. */
data class AddEstimateChip(val minutes: Int, val label: String, val picked: Boolean)
