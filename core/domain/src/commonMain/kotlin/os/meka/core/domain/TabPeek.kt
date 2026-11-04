package os.meka.core.domain

/**
 * A row of pills that scrolls sideways when it doesn't fit (Money's tabs). Walk-through 2026-11-02, A14: on the cover
 * screen the fourth pill ("Trading") ended right at the screen's edge, so the row read as cut against the edge rather
 * than as one that scrolls. When the row overflows, each pill's side padding is nudged (never below [MIN_PAD], never
 * above [MAX_PAD]) so the screen's edge falls in the middle part of a pill: a clear peek that says "more this way".
 * A row that fits keeps its base padding. Non-AI, pure, in dp.
 */
object TabPeekRules {
    const val MIN_PAD = 12f
    const val MAX_PAD = 22f

    /** The share of the cut pill that should show: between these, the edge reads as a peek. */
    const val PEEK_MIN = 0.3f
    const val PEEK_MAX = 0.7f

    /**
     * The side padding for each pill. [labels] are the labels' widths, [start] the row's leading padding, [gap] the space
     * between pills, [viewport] the width the row is seen through, [base] the padding it would use.
     */
    fun padding(labels: List<Float>, start: Float, gap: Float, viewport: Float, base: Float): Float {
        if (labels.isEmpty() || viewport <= 0f) return base
        if (end(labels, start, gap, base) <= viewport - start) return base
        // Try paddings nearest the base first, in half-dp steps either way.
        val tries = buildList {
            add(base)
            var d = 0.5f
            while (base - d >= MIN_PAD || base + d <= MAX_PAD) {
                if (base - d >= MIN_PAD) add(base - d)
                if (base + d <= MAX_PAD) add(base + d)
                d += 0.5f
            }
        }
        return tries.firstOrNull { p -> peeks(labels, start, gap, viewport, p) } ?: base
    }

    /** True when, at padding [pad], the edge cuts a pill with between [PEEK_MIN] and [PEEK_MAX] of it showing. */
    fun peeks(labels: List<Float>, start: Float, gap: Float, viewport: Float, pad: Float): Boolean {
        var x = start
        for (w in labels) {
            val pill = w + 2 * pad
            if (viewport < x) return false // the edge falls in a gap
            if (viewport <= x + pill) {
                val shown = (viewport - x) / pill
                return shown in PEEK_MIN..PEEK_MAX
            }
            x += pill + gap
        }
        return false
    }

    private fun end(labels: List<Float>, start: Float, gap: Float, pad: Float): Float =
        start + labels.sumOf { (it + 2 * pad).toDouble() }.toFloat() + gap * (labels.size - 1)
}
