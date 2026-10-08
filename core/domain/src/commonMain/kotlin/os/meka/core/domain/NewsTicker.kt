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

/** Which screen edge the Mac's floating ticker sits on. */
enum class FloatingEdge(val id: String, val label: String) {
    TOP("top", "Top of the screen"),
    BOTTOM("bottom", "Bottom of the screen"),
}

/** A rectangle in screen points, y counted up from the bottom (the Mac's screen coordinates). */
data class TickerRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val midX: Double get() = x + width / 2
    val midY: Double get() = y + height / 2
}

/** Where the floating ticker sits: an edge, and its centre across the screen (0 = left, 1 = right). */
data class FloatingPlacement(val edge: FloatingEdge, val centre: Double)

/**
 * The Mac's floating ticker (build plan M1, news ticker slice 2b), non-AI and pure: a thin always-on-top strip of the
 * same cards as Today's ticker, off by default (View → Floating Ticker), at the top or bottom edge of the screen. It is
 * dragged by its grip and lets go onto the nearer edge, keeping where it was across the screen. It always drifts
 * ([MODE]: there is no "Today opening" to be calm about), holds while hovered, and is hidden while there is nothing to
 * show. The Mac keeps it out of full-screen apps. The choice and the placement are per Mac, nothing synced.
 */
object FloatingTickerRules {
    /** The floating strip keeps drifting (hover holds it; Reduce Motion pages instead). */
    val MODE: TickerMode = TickerMode.MOVING
    const val HEIGHT = 64.0
    const val MAX_WIDTH = 960.0
    const val MIN_WIDTH = 320.0
    /** Gap between the strip and the screen's edges (inside the menu bar and the Dock). */
    const val MARGIN = 8.0

    /** Off until turned on; at the bottom, centred. */
    val DEFAULT = FloatingPlacement(FloatingEdge.BOTTOM, 0.5)

    /** The stored edge; anything unknown (or nothing) is the bottom. */
    fun edge(id: String?): FloatingEdge = FloatingEdge.entries.firstOrNull { it.id == id } ?: FloatingEdge.BOTTOM

    /** The stored placement, the centre kept within the screen (nothing stored, NaN, is the middle). */
    fun placement(edgeId: String?, centre: Double): FloatingPlacement =
        FloatingPlacement(edge(edgeId), if (centre.isNaN()) 0.5 else centre.coerceIn(0.0, 1.0))

    /** Whether the panel is on screen: turned on and something to show. */
    fun shown(enabled: Boolean, ticker: NewsTicker): Boolean = enabled && !ticker.isEmpty

    /**
     * The panel's frame inside the screen's [visible] area (without the menu bar and the Dock): at most [MAX_WIDTH]
     * wide and [MARGIN] in from the sides (never narrower than [MIN_WIDTH] unless the screen is), centred where it was
     * left but never past a side, [MARGIN] from its edge.
     */
    fun frame(visible: TickerRect, placement: FloatingPlacement): TickerRect {
        val room = visible.width - 2 * MARGIN
        val width = minOf(MAX_WIDTH, maxOf(room, minOf(MIN_WIDTH, visible.width)))
        val x = if (width >= room) {
            visible.x + (visible.width - width) / 2
        } else {
            (visible.x + placement.centre * visible.width - width / 2)
                .coerceIn(visible.x + MARGIN, visible.x + visible.width - MARGIN - width)
        }
        val y = when (placement.edge) {
            FloatingEdge.TOP -> visible.y + visible.height - MARGIN - HEIGHT
            FloatingEdge.BOTTOM -> visible.y + MARGIN
        }
        return TickerRect(x, y, width, HEIGHT)
    }

    /** While dragging: the panel moved by ([dx], [dy]) from where the drag began, kept wholly on the screen. */
    fun dragged(start: TickerRect, dx: Double, dy: Double, visible: TickerRect): TickerRect {
        val maxX = maxOf(visible.x, visible.x + visible.width - start.width)
        val maxY = maxOf(visible.y, visible.y + visible.height - start.height)
        return start.copy(x = (start.x + dx).coerceIn(visible.x, maxX), y = (start.y + dy).coerceIn(visible.y, maxY))
    }

    /** Let go at [panel]: the nearer edge (the upper half is the top), and where its centre is across the screen. */
    fun dropped(visible: TickerRect, panel: TickerRect): FloatingPlacement {
        val edge = if (panel.midY >= visible.midY) FloatingEdge.TOP else FloatingEdge.BOTTOM
        val centre = if (visible.width <= 0.0) 0.5 else ((panel.midX - visible.x) / visible.width).coerceIn(0.0, 1.0)
        return FloatingPlacement(edge, centre)
    }
}
