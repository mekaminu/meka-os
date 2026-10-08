package os.meka.core.domain

/**
 * The news ticker (build plan M1, news ticker slice 2), non-AI and pure: a strip of picture-and-headline cards that
 * drifts right to left under Today's header (Fold) or at the foot of Today (Mac). The apps draw it; these rules decide
 * what it holds, whether it moves and how far.
 *
 * Today stays calm: by default ([TickerMode.CALM]) the strip drifts [CALM_LOOPS] times each time Today opens and then
 * rests as a still row (▸ sets it moving again). "Always moving" keeps it drifting; "Off" hides it. The choice is per
 * device (Appearance → News ticker), like the theme. Reduced motion never drifts: a still card with ‹ › paging.
 * It moves only while it is on screen and not being touched or hovered.
 *
 * Headlines are untrusted content (ADR-006): only ever shown as text; tapping opens MEKA's own News detail.
 */
enum class TickerMode(val id: String, val label: String, val line: String) {
    CALM("calm", "Calm", "Drifts twice when Today opens, then rests"),
    MOVING("moving", "Always moving", "Keeps drifting while Today is on screen"),
    OFF("off", "Off", "Not on Today · News stays in Ask → More"),
}

/** What the strip shows: today's match first (only with Barça chosen), then stories from each lane in turn. */
data class NewsTicker(val matchday: NewsMatchday?, val items: List<NewsItem>) {
    val isEmpty: Boolean get() = matchday == null && items.isEmpty()

    companion object {
        val EMPTY = NewsTicker(null, emptyList())
    }
}

/** How far the strip has drifted: [offsetDp] into one loop (0 until the loop's width) and the loops completed. */
data class TickerDrift(val offsetDp: Float, val loops: Int) {
    companion object {
        val START = TickerDrift(0f, 0)
    }
}

object TickerRules {
    /** Constant slow speed (the plan's ~40 dp/s). */
    const val SPEED_DP_PER_S = 40f
    /** Calm mode: loops each time Today opens before the strip rests. */
    const val CALM_LOOPS = 2
    /** At most this many stories on the strip. */
    const val MAX_ITEMS = 12
    /** A frame gap longer than this (a pause, a dropped frame) moves the strip no further than this. */
    const val MAX_STEP_MS = 100L

    /** The stored choice; anything unknown (or nothing) is [TickerMode.CALM]. */
    fun mode(id: String?): TickerMode = TickerMode.entries.firstOrNull { it.id == id } ?: TickerMode.CALM

    /**
     * The strip from the News place: the matchday line, then one story from each lane in turn (Barça, AI, then the
     * other chosen topics, the place's own order), newest first within a lane, at most [MAX_ITEMS]. Each story once
     * (the place already shows a story once across lanes).
     */
    fun ticker(place: NewsPlace): NewsTicker {
        val lanes = place.lanes.map { it.items }
        val out = ArrayList<NewsItem>()
        val seen = HashSet<String>()
        var round = 0
        while (out.size < MAX_ITEMS && lanes.any { round < it.size }) {
            for (lane in lanes) {
                val item = lane.getOrNull(round) ?: continue
                if (out.size < MAX_ITEMS && seen.add(item.id)) out += item
            }
            round++
        }
        return NewsTicker(place.matchday, out)
    }

    /** Whether the strip is shown on Today at all. */
    fun shown(mode: TickerMode, ticker: NewsTicker): Boolean = mode != TickerMode.OFF && !ticker.isEmpty

    /**
     * Whether the strip drifts right now: never with reduced motion, off screen, while touched/hovered or with nothing
     * to show; always in [TickerMode.MOVING]; in [TickerMode.CALM] until [CALM_LOOPS] loops are done.
     */
    fun moving(mode: TickerMode, reducedMotion: Boolean, onScreen: Boolean, held: Boolean, loopsDone: Int, hasItems: Boolean): Boolean =
        hasItems && !reducedMotion && onScreen && !held && when (mode) {
            TickerMode.OFF -> false
            TickerMode.MOVING -> true
            TickerMode.CALM -> loopsDone < CALM_LOOPS
        }

    /** Whether the still strip offers ▸ to set it moving again (calm mode, rested; never with reduced motion). */
    fun offersPlay(mode: TickerMode, reducedMotion: Boolean, loopsDone: Int, hasItems: Boolean): Boolean =
        hasItems && !reducedMotion && mode == TickerMode.CALM && loopsDone >= CALM_LOOPS

    /**
     * One frame of drift: [dtMs] at [SPEED_DP_PER_S] (a gap over [MAX_STEP_MS] counts as [MAX_STEP_MS]), wrapping at
     * [loopWidthDp] (the width of one set of cards) and counting the loop. Nothing moves before the width is known.
     */
    fun step(drift: TickerDrift, dtMs: Long, loopWidthDp: Float): TickerDrift {
        if (loopWidthDp <= 0f || dtMs <= 0L) return drift
        val next = drift.offsetDp + SPEED_DP_PER_S * dtMs.coerceAtMost(MAX_STEP_MS) / 1000f
        return if (next >= loopWidthDp) TickerDrift(next - loopWidthDp, drift.loops + 1) else drift.copy(offsetDp = next)
    }

    /**
     * After the finger scrubs the strip to [offsetDp] (it may go past either end of one loop), the same place within
     * one loop. Scrubbing never counts as a loop.
     */
    fun scrubbed(drift: TickerDrift, offsetDp: Float, loopWidthDp: Float): TickerDrift {
        if (loopWidthDp <= 0f) return drift
        val m = offsetDp % loopWidthDp
        return drift.copy(offsetDp = if (m < 0f) m + loopWidthDp else m)
    }

    /** Reduced motion's ‹ › paging over [count] cards, wrapping both ways. */
    fun page(index: Int, delta: Int, count: Int): Int =
        if (count <= 0) 0 else ((index + delta) % count + count) % count

    /** "2 of 12" for the still card's pager. */
    fun pageLabel(index: Int, count: Int): String = if (count <= 0) "" else "${index.coerceIn(0, count - 1) + 1} of $count"

    /** What a screen reader says for a card: "Barça · Sport · 2 h ago: Pedri returns". */
    fun spoken(item: NewsItem): String {
        val lane = NewsTopics.byId(item.topic)?.label
        return listOfNotNull(lane, item.meta).joinToString(" · ") + ": " + item.title
    }
}
