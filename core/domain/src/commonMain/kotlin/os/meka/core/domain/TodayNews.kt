package os.meka.core.domain

/**
 * The Today rebuild, slice 2b-2b-2b-3 (Meka 2026-11-09 08:09 (d)), non-AI and pure: Today's news is the approved
 * mockup's row of picture cards that you swipe yourself, not the drifting ticker. A small-caps NEWS label with a quiet
 * "swipe ›" when there is more than one card, then today's match first (only with Barça chosen) and the stories, each a
 * picture, a two-line headline and "BBC News · 49 min ago". It always starts at the first card, never mid-card, and
 * never moves on its own, so reduced motion needs nothing different. Off (Appearance → News) still hides it.
 * The drifting ticker stays for the bedside clock and the Mac's floating strip.
 *
 * Headlines are untrusted content (ADR-006): only ever shown as text; a tap opens MEKA's own News detail.
 */
data class TodayNewsCard(
    /** Stable key for the row: the story's id, or "match:" + the event's id. */
    val key: String,
    val title: String,
    /** The quieter line under the headline: "BBC News · 49 min ago", or the match's kick-off line. */
    val meta: String,
    val spoken: String,
    /** The story a tap opens; null for the match card, which opens its own event. */
    val storyId: String?,
    val match: NewsMatchday?,
    /** Barça's colour on the card (the match, or a story from the Barça lane). */
    val barca: Boolean,
)

object TodayNewsRules {
    const val LABEL = "NEWS"
    const val SWIPE_HINT = "swipe ›"

    /** The mockup's card: 250 wide, so the next one peeks in from the edge on the folded phone. */
    const val CARD_WIDTH_DP = 250

    /** A 56 square picture beside the headline. */
    const val THUMB_DP = 56

    /** At most this many cards: past that it is the News page's job ("Open News" is one tap away in Ask). */
    const val MAX_CARDS = 8

    /** Where the row starts: always the first card, whole. */
    const val START_INDEX = 0

    fun shown(mode: TickerMode, ticker: NewsTicker): Boolean = TickerRules.shown(mode, ticker)

    /**
     * The row drifts across on its own again (Meka 2026-11-09 19:20: "why are my news not scrolling across? That's what
     * I enjoyed"): every [DRIFT_STEP_MS] it glides to the next card, back to the first after the last. Calm goes round
     * [CALM_LOOPS] times then rests; Always moving keeps going; reduced motion never drifts. A swipe of his own pauses it
     * for [RESUME_AFTER_MS].
     */
    const val DRIFT_STEP_MS = 3_500L
    const val RESUME_AFTER_MS = 6_000L
    const val CALM_LOOPS = 2

    /** How many glides the row makes on its own for [count] cards: none with one card or reduced motion. */
    fun driftSteps(mode: TickerMode, count: Int, reduced: Boolean): Int = when {
        reduced || count < 2 || mode == TickerMode.OFF -> 0
        mode == TickerMode.MOVING -> Int.MAX_VALUE
        else -> CALM_LOOPS * count
    }

    /** The card the row glides to after [index]: the next, or the first after the last. */
    fun nextIndex(index: Int, count: Int): Int = if (count <= 0) 0 else (index + 1) % count

    /** Whether the row may glide at [nowMs] after his own last swipe at [touchedAtMs] (null: never touched). */
    fun mayDrift(touchedAtMs: Long?, nowMs: Long): Boolean = touchedAtMs == null || nowMs - touchedAtMs >= RESUME_AFTER_MS

    /** The cards in order: the match first, then the stories, at most [MAX_CARDS]. */
    fun cards(ticker: NewsTicker): List<TodayNewsCard> {
        val match = ticker.matchday?.let { m ->
            // "Barça v Real Madrid" over "21:00 · in 3 h" (the line without the title it starts with), or the live score.
            val meta = m.line.removePrefix(m.title).removePrefix(" · ").ifBlank { m.line }
            TodayNewsCard("match:" + m.eventId, m.title, meta, m.line, null, m, barca = true)
        }
        val stories = ticker.items.map { s ->
            TodayNewsCard(s.id, s.title, s.meta, TickerRules.spoken(s), s.id, null, barca = s.topic == "barca")
        }
        return (listOfNotNull(match) + stories).distinctBy { it.key }.take(MAX_CARDS)
    }

    /** "swipe ›" only when there is a second card to swipe to. */
    fun hint(count: Int): String = if (count > 1) SWIPE_HINT else ""

    /** The label as a screen reader hears it: "News, 4 stories". */
    fun spokenLabel(count: Int): String = when (count) {
        0 -> "News"
        1 -> "News, 1 story"
        else -> "News, $count stories"
    }
}
