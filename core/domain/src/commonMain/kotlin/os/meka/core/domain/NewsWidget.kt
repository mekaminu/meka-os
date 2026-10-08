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

/**
 * One card of the Mac's desktop News widget (news ticker slice 3b). Mac desktop widgets can't animate, so the widget
 * shows the top [DeskNewsWidgetRules.MAX_CARDS] stories still: today's match first, then Barça and AI in turn, then the
 * other lanes if there are fewer. The widget runs in its own sandboxed process without the core, so a card carries what
 * it needs to say its line itself as time moves on ([source] and [publishedAtMs] → "Sport · 2 h ago", the same words as
 * [NewsRules.age]); the match's line is fixed when written and the card is dropped after [untilMs].
 */
data class DeskNewsCard(
    /** The story's id, or [NewsWidgetRules.MATCH_ID]. */
    val id: String,
    /** "Barça", "AI" … or "MATCHDAY" / "ON NOW". */
    val label: String,
    val title: String,
    /** The publisher ("Sport"); empty for the match. */
    val source: String,
    /** When the story was published; 0 for the match. */
    val publishedAtMs: Long,
    /** The match's "21:00 · in 3 h"; null for a story (its line is [source] and its age). */
    val fixedLine: String?,
    /** The picture the app saves beside the widget's file; null → the tile. */
    val imageKey: String?,
    val tileInitial: String,
    val isBarca: Boolean,
    /** What clicking opens in MEKA: `mekaos://news?story=<id>`, or `mekaos://news` for the match. */
    val openUrl: String,
    /** After this the card isn't shown (the match's final whistle); [Long.MAX_VALUE] for a story. */
    val untilMs: Long,
) {
    val isMatch: Boolean get() = id == NewsWidgetRules.MATCH_ID
}

/** What the desktop widget shows: up to three cards, or [emptyTitle]/[emptyLine]. */
data class DeskNewsView(val cards: List<DeskNewsCard>, val emptyTitle: String, val emptyLine: String) {
    val isEmpty: Boolean get() = cards.isEmpty()
}

object DeskNewsWidgetRules {
    /** Three stories: the medium widget's rows (the small one shows the first). */
    const val MAX_CARDS = 3
    /** The widget looks again this often (the plan's 30 minutes) besides when MEKA writes new stories. */
    const val REFRESH_MS = 30 * 60_000L
    /** The widget's links: MEKA opens News (on [STORY_PARAM] when given). */
    const val OPEN_URL = "mekaos://news"
    const val STORY_PARAM = "story"

    /** The widget from Today's ticker: the match, then Barça and AI in the ticker's turn, then the rest; at most three. */
    fun view(ticker: NewsTicker): DeskNewsView {
        val cards = ArrayList<DeskNewsCard>()
        ticker.matchday?.let { m ->
            val label = if (m.live) "ON NOW" else "MATCHDAY"
            val line = m.line.removePrefix(m.title).removePrefix(" · ").ifEmpty { m.line }
            cards += DeskNewsCard(
                NewsWidgetRules.MATCH_ID, label, m.title, "", 0L, line, null, "⚽", true, OPEN_URL, m.event.endAtMs,
            )
        }
        val first = ticker.items.filter { it.topic == NewsTopics.BARCA.id || it.topic == NewsTopics.AI.id }
        val rest = ticker.items.filter { it !in first }
        (first + rest).forEach { item ->
            if (cards.size >= MAX_CARDS) return@forEach
            cards += DeskNewsCard(
                item.id, NewsTopics.byId(item.topic)?.label ?: "News", item.title, item.source, item.publishedAtMs, null,
                item.imageKey, item.tileInitial, item.topic == NewsTopics.BARCA.id, openUrl(item.id), Long.MAX_VALUE,
            )
        }
        return DeskNewsView(cards, NewsWidgetRules.EMPTY_TITLE, NewsWidgetRules.EMPTY_LINE)
    }

    /** "mekaos://news?story=<id>" (story ids are the server's keys, but anything not plain is left out). */
    fun openUrl(storyId: String): String =
        if (storyId.isNotEmpty() && storyId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) "$OPEN_URL?$STORY_PARAM=$storyId"
        else OPEN_URL

    /** The card's line at [nowMs]: the match's fixed line, or "Sport · 2 h ago" (the widget says the same in Swift). */
    fun line(card: DeskNewsCard, nowMs: Long): String =
        card.fixedLine ?: listOf(card.source, NewsRules.age(card.publishedAtMs, nowMs)).filter { it.isNotEmpty() }.joinToString(" · ")

    /** The cards still to show at [nowMs] (the match goes at its final whistle). */
    fun showing(view: DeskNewsView, nowMs: Long): List<DeskNewsCard> = view.cards.filter { nowMs < it.untilMs }

    /** What a screen reader says for a card: "Barça · Sport · 2 h ago: Pedri returns". */
    fun spoken(card: DeskNewsCard, nowMs: Long): String = "${card.label} · ${line(card, nowMs)}: ${card.title}"
}
