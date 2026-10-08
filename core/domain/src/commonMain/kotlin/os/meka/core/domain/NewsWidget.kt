package os.meka.core.domain

/**
 * The Fold's News home-screen widget (build plan M1, news ticker slice 3a), non-AI and pure: picture-and-headline
 * cards the launcher flips through on its own every [NewsWidgetRules.FLIP_INTERVAL_MS], in two sizes (a 4×1 strip and
 * a 4×2 card). The same stories as Today's ticker, in the same order: today's match first, then one story from each
 * lane in turn (Barça, AI, then the rest), at most [NewsWidgetRules.MAX_CARDS] so the launcher holds few pictures.
 *
 * Headlines are untrusted content (ADR-006): only ever shown as text; tapping opens MEKA's own News detail on the
 * story, never the publisher's page. The match opens News, which leads with it.
 */
data class NewsWidgetCard(
    /** The story's id ([NewsItem.id]); for the match, [NewsWidgetRules.MATCH_ID]. */
    val id: String,
    /** "Barça", "AI" … for a story; "MATCHDAY" / "ON NOW" for the match. */
    val label: String,
    val title: String,
    /** "Sport · 2 h ago"; the match's "21:00 · in 3 h". */
    val line: String,
    /** The picture to fetch with `MekaCore.newsImage`; null → the tile. */
    val imageKey: String?,
    /** The tile's letter while the picture loads or when there is none. */
    val tileInitial: String,
    /** Barça stories and the match wear Barça's colour (the `barca` token). */
    val isBarca: Boolean,
    /** What tapping opens: the News place on this story, or null for News itself (the match leads it). */
    val openStoryId: String?,
    /** What a screen reader says. */
    val spoken: String,
) {
    val isMatch: Boolean get() = id == NewsWidgetRules.MATCH_ID
}

/** What the widget shows: the cards, or [emptyTitle]/[emptyLine] when there are none. */
data class NewsWidgetView(
    val cards: List<NewsWidgetCard>,
    val emptyTitle: String,
    val emptyLine: String,
    /** When the lines ("2 h ago", "in 3 h") should be looked at again; [Long.MAX_VALUE] with nothing shown. */
    val nextChangeMs: Long,
) {
    val isEmpty: Boolean get() = cards.isEmpty()
}

object NewsWidgetRules {
    /** The launcher flips to the next card every five seconds (the plan's). */
    const val FLIP_INTERVAL_MS = 5_000
    /** At most this many cards (each holds a small picture in the launcher). */
    const val MAX_CARDS = 8
    /** "2 h ago" and "in 3 h" move on: the widget is redrawn at most this often while it shows something. */
    const val REFRESH_MS = 30 * 60_000L
    /** The match's card id (story ids are the server's keys, never this). */
    const val MATCH_ID = "match"

    const val EMPTY_TITLE = "No news yet"
    const val EMPTY_LINE = "Choose topics in MEKA · Ask › More › News"

    /** The widget from Today's ticker (the same stories, the same order), at [nowMs]. */
    fun view(ticker: NewsTicker, nowMs: Long): NewsWidgetView {
        val cards = ArrayList<NewsWidgetCard>()
        ticker.matchday?.let { m ->
            val label = if (m.live) "ON NOW" else "MATCHDAY"
            val line = m.line.removePrefix(m.title).removePrefix(" · ").ifEmpty { m.line }
            cards += NewsWidgetCard(MATCH_ID, label, m.title, line, null, "⚽", true, null, "$label: ${m.title}, $line")
        }
        ticker.items.forEach { item ->
            if (cards.size >= MAX_CARDS) return@forEach
            val lane = NewsTopics.byId(item.topic)?.label ?: "News"
            cards += NewsWidgetCard(
                item.id, lane, item.title, item.meta, item.imageKey, item.tileInitial,
                item.topic == NewsTopics.BARCA.id, item.id, TickerRules.spoken(item),
            )
        }
        val shown = cards.take(MAX_CARDS)
        return NewsWidgetView(shown, EMPTY_TITLE, EMPTY_LINE, if (shown.isEmpty()) Long.MAX_VALUE else nowMs + REFRESH_MS)
    }

    /** The widget is redrawn only when this changes (not when the next look-again time moves on). */
    fun signature(v: NewsWidgetView): List<Any?> = listOf(v.cards, v.emptyTitle, v.emptyLine)

    /** What a screen reader says for the whole widget: "News, 8 stories. Barça: Pedri returns …". */
    fun spoken(v: NewsWidgetView): String =
        if (v.isEmpty) "News. ${v.emptyTitle}. ${v.emptyLine}"
        else "News, ${v.cards.size} ${if (v.cards.size == 1) "story" else "stories"}. ${v.cards.first().spoken}"
}
