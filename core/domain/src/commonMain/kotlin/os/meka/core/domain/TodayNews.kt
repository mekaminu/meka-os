package os.meka.core.domain

/**
 * The Today rebuild, slice 2b-2b-2b-3 (Meka 2026-11-09 08:09 (d)), non-AI and pure: Today's news is the approved
 * mockup's row of picture cards that you swipe yourself, not the drifting ticker. A small-caps NEWS label with a quiet
 * "swipe ›" when there is more than one card, then today's match first (only with Barça chosen) and the stories, each a
 * picture, a two-line headline and "BBC News · 49 min ago". It always starts at the first card, never mid-card, and
 * drifts across continuously on its own ([drifts]; still with the app's own Motion → Off). Off (Appearance → News) still hides
 * it. The Fold and the Mac (slice 2d-2e) both read these rules; the drifting ticker stays for the bedside clock and the
 * Mac's floating strip.
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
     * The row drifts across **continuously** again (Meka 2026-11-10 07:38: "my news are not scrolling as before"; first
     * asked 2026-11-09 19:20: "why are my news not scrolling across? That's what I enjoyed"): a slow constant glide at
     * [SPEED_DP_PER_S] (the old ticker's speed), no hops, looping back to the first card without a seam, for as long as
     * Today is on screen, in Calm and in Always moving alike (Calm no longer rests after two rounds). His own swipe, a
     * press or (on the Mac) a hover pauses it; it picks up again [RESUME_AFTER_MS] after he lets go. Only the app's own
     * Motion → Off (reduced motion) or News → Off keeps it still.
     */
    const val SPEED_DP_PER_S = TickerRules.SPEED_DP_PER_S
    const val RESUME_AFTER_MS = 4_000L

    /** How many copies of the cards the row lays out while it drifts, so the loop wraps without a gap. */
    const val LOOP_COPIES = 3

    /** Whether the row drifts on its own: two or more cards, News not Off, and the app's Motion not Off. */
    fun drifts(mode: TickerMode, count: Int, reduced: Boolean): Boolean = !reduced && count >= 2 && mode != TickerMode.OFF

    /** The row's offset after [dtMs] of drift from [offsetDp], wrapped within one set of cards ([loopWidthDp]). */
    fun drift(offsetDp: Float, dtMs: Long, loopWidthDp: Float): Float =
        TickerRules.stepAt(TickerDrift(offsetDp, 0), dtMs, loopWidthDp, SPEED_DP_PER_S).offsetDp

    /**
     * Where the drifting row (laid out [LOOP_COPIES] times) jumps back to once [firstIndex] has passed the first set of
     * [count] cards: the same card in the first set, so the jump can't be seen. Null: no jump needed.
     */
    fun wrapIndex(firstIndex: Int, count: Int): Int? = if (count <= 0 || firstIndex < count) null else firstIndex % count

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
