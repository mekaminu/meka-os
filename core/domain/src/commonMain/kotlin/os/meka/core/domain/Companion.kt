package os.meka.core.domain

/**
 * The companion, slice 1 (Meka 2026-10-19 20:14: "interactive like a human talking to someone, and thinks outside
 * Navilife"). Ask, Talk and Hello answer general questions too (knowledge, advice, ideas, explanations, banter), not
 * only his day. Navilife's server tells an **open** turn from a **quick look-up** with [isOpen]: an open turn goes to
 * the larger model and is metered as [FEATURE] ("Chat" in Activity), a look-up stays on the small model as before.
 * Non-AI, pure; nothing is stored.
 */
object CompanionRules {
    /** The meter an open turn counts against, inside the same monthly AI budget. */
    const val FEATURE = "ask.companion"

    /** A question this long is a conversation, not a look-up, whatever its words. */
    const val LONG_WORDS = 16

    /** Openings that ask for thinking, not a fact from his day. */
    private val OPEN_STARTS = listOf(
        "why", "explain", "tell me about", "tell me a", "tell me something", "teach me", "how does", "how do", "how would",
        "how can", "how should", "how come", "what do you think", "what's the difference", "what is the difference",
        "what's a ", "what is a ", "what's an ", "what is an ", "what are ", "what does ", "what would", "what if", "who was",
        "who is", "who were", "who invented", "who wrote", "is it true", "can you explain", "could you explain", "help me",
        "give me ideas", "give me some", "any ideas", "ideas for", "should i", "would you", "what should i", "recommend",
        "suggest", "brainstorm", "compare", "imagine", "let's talk", "lets talk", "talk to me", "i'm thinking", "im thinking",
        "i've been thinking", "ive been thinking", "i feel", "i'm feeling", "im feeling", "i'm bored", "im bored",
        "fancy a chat", "do you know", "do you like", "have you heard", "write me", "write a", "describe",
    )

    /** Words anywhere that mark an open turn. */
    private val OPEN_WORDS = listOf(
        "brainstorm", "ideas", "advice", "explain", "difference between", "pros and cons", "your opinion", "your view",
        "what do you think", "recipe", "joke", "poem", "story", "history of", "meaning of", "in general",
    )

    /** Openings that are plainly a look-up of his own day, his lists or an action. */
    private val LOOKUP_STARTS = listOf(
        "what's on", "whats on", "what is on", "what have i got", "what've i got", "what's my", "whats my", "what is my",
        "what's next", "whats next", "when's", "whens", "when is", "when do i", "when did i", "when am i", "where's my",
        "wheres my", "where is my", "is it going to", "will it", "am i ", "do i have", "have i got", "add ", "remind ",
        "move ", "show ", "open ", "set ", "start ", "skip ", "book ", "mark ", "put ", "plan my", "what time",
        "what are my", "whats left", "what's left", "how many", "how long until", "how long till",
    )

    /** Short follow-ups that carry on whatever came before ("why?", "go on", "and then?"). */
    private val FOLLOW_UPS = listOf("go on", "and then", "tell me more", "more", "why", "how so", "really", "such as", "like what", "what else", "and", "but", "so")

    /**
     * Whether [question] is an open turn: it asks for thinking (an explanation, advice, ideas, an opinion), is long, or
     * is a short follow-up to an open turn in [earlier] (his earlier questions in this conversation, oldest first). A
     * plain look-up ("what's on today?", "when's the dentist?") or an action is never open, even mid-conversation.
     */
    fun isOpen(question: String, earlier: List<String> = emptyList()): Boolean {
        val q = norm(question)
        if (q.isEmpty()) return false
        val words = q.split(' ').size
        if (LOOKUP_STARTS.any { q.startsWith(it) } && words < LONG_WORDS) return false
        if (OPEN_STARTS.any { q.startsWith(it) }) return true
        if (OPEN_WORDS.any { it in q }) return true
        if (words >= LONG_WORDS) return true
        val last = earlier.lastOrNull() ?: return false
        val followUp = words <= 6 && FOLLOW_UPS.any { q == it || q.startsWith("$it ") }
        return followUp && isOpen(last, earlier.dropLast(1))
    }

    /**
     * The companion, slice 3 (how it addresses him): what Settings → Voice saves when he'd rather be called "sir"; the
     * wire's `callMe`. Anything else (absent, blank, unknown) means by name, as before.
     */
    const val CALL_SIR = "sir"

    /** [raw] as the companion should take it: [CALL_SIR], or null for by name. */
    fun callMe(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it == CALL_SIR }

    /** Settings → Voice's row: its heading, the two choices and the line under them. */
    const val CALL_TITLE = "Obi calls you"
    const val CALL_BY_NAME = "By name"
    const val CALL_SIR_LABEL = "Sir"

    fun callLine(sir: Boolean): String =
        if (sir) "Obi calls you sir now and then, in Ask, Talk and Hello, on every device."
        else "Obi calls you by your name now and then, in Ask, Talk and Hello, on every device."

    /** Lower case, apostrophes kept, other punctuation and extra spaces dropped. */
    private fun norm(s: String): String =
        s.lowercase().replace('’', '\'').replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
}

/** One web result an answer drew on, as Ask shows it under the words: the [site] ("bbc.co.uk"), its [title] and [url]. */
data class AskSource(val site: String, val title: String, val url: String)

/**
 * The companion, slice 2 (live world facts): news, scores, prices, the weather somewhere else and anything current are
 * looked up on the web (the server's Brave Search key, the one the spam-call lookup uses) and the answer names its
 * source on a card. Non-AI, pure. Only the question's own words are searched, and only when it reads as a question about
 * the world: anything about his own life ("my", "our", his family), anything carrying an address, a number or an email,
 * and the home weather the <today> block already has are never sent. No URL is ever fetched; a source card opens in the
 * browser on his tap.
 */
object LiveFactsRules {
    /** The most searches a day (the Brave key's free credit also pays for the spam-call lookup). */
    const val MAX_PER_DAY = 40
    /** The longest query sent. */
    const val MAX_QUERY = 120
    /** The most results the model reads, and the most source cards shown. */
    const val MAX_RESULTS = 4
    const val MAX_SOURCES = 2
    const val MAX_TITLE = 120
    const val MAX_SNIPPET = 320

    /** Words that ask for something current. */
    private val LIVE = listOf(
        "news", "headline", "headlines", "latest", "score", "scores", "who won", "won the", "result of", "results of",
        "price of", "share price", "stock price", "exchange rate", "bitcoin", "right now", "at the moment", "currently", "this week in", "happening in", "going on in",
        "election", "prime minister", "president of", "release date", "come out", "comes out", "open today", "opening hours",
        "is it open", "still open", "league table", "top of the league", "fixtures for", "transfer", "strike", "strikes",
    )

    /** Words that make it about his own life, which is never searched. */
    private val PERSONAL = setOf(
        "my", "mine", "our", "ours", "me", "wife", "husband", "son", "sons", "boys", "kids", "children", "daughter", "mum",
        "dad", "navilife", "meka", "barca", "barça", "barcelona's",
    )

    /**
     * The query to search for [question], or null when it shouldn't go to the web: not about something current, about
     * his own life, carrying a number of five or more digits, an email address or a postcode, or the home weather.
     */
    fun query(question: String): String? {
        val q = question.lowercase().replace('’', '\'')
        val words = q.replace(Regex("[^a-z0-9'ç ]"), " ").split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        if (personal(q, words)) return null
        val flat = " " + words.joinToString(" ") + " "
        if (LIVE.none { " $it " in flat } && !elsewhereWeather(flat)) return null
        val cleaned = question.replace(Regex("""^\s*(?:(?:hey|ok|okay|hi)\b[\s,]*)?""", RegexOption.IGNORE_CASE), "").replace(Regex("""\s+"""), " ").trim()
        return cleaned.take(MAX_QUERY).trim().takeIf { it.length >= 3 }
    }

    /**
     * Obi's Live mode, slice 4: a search the Live model asked for, as sent to the web, or null when it mustn't go: it
     * is about his own life (the same words [query] refuses), carries a number of five or more digits, an email
     * address or a postcode, or [personalNames] (his family's and his own names, from About you) — the same filter as
     * Ask's, without the "is it about something current" test, since the model already chose to search.
     */
    fun searchable(query: String, personalNames: Collection<String> = emptyList()): String? {
        val q = query.lowercase().replace('’', '\'')
        val words = q.replace(Regex("[^a-z0-9'ç ]"), " ").split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || personal(q, words)) return null
        val names = personalNames.mapNotNull { n -> n.trim().lowercase().takeIf { it.length >= 2 } }.toSet()
        if (words.any { it in names }) return null
        return query.replace(Regex("""\s+"""), " ").trim().take(MAX_QUERY).trim().takeIf { it.length >= 3 }
    }

    private fun personal(q: String, words: List<String>): Boolean =
        words.any { it in PERSONAL } || '@' in q || Regex("""\d{5,}""").containsMatchIn(q.replace(" ", "")) || POSTCODE.containsMatchIn(q)

    /** "weather in Madrid", "forecast for Paris": somewhere named, not home's "forecast for tomorrow". */
    private fun elsewhereWeather(flat: String): Boolean =
        Regex(""" (weather|forecast|temperature|raining|rain|snowing|sunny) (in|for|at) ([a-z']+) """).findAll(flat).any { it.groupValues[3] !in WHEN }

    private val WHEN = setOf(
        "the", "this", "today", "tonight", "tomorrow", "morning", "afternoon", "evening", "training", "school", "work", "home",
        "a", "an", "next", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "weekend", "half",
    )

    private val POSTCODE = Regex("""\b[a-z]{1,2}\d[a-z\d]?\s*\d[a-z]{2}\b""")

    /** A result's site as a card names it: the host without "www." ("bbc.co.uk"); null for anything but https. */
    fun site(url: String): String? {
        if (!url.startsWith("https://")) return null
        val host = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
        if (host.isEmpty() || '@' in host || '.' !in host) return null
        return host.removePrefix("www.")
    }

    /** The sources as cards: https only, titled, no site twice, at most [MAX_SOURCES]. */
    fun sources(raw: List<Pair<String, String>>): List<AskSource> =
        raw.mapNotNull { (title, url) ->
            val site = site(url) ?: return@mapNotNull null
            val t = title.replace(Regex("""\s+"""), " ").trim().take(MAX_TITLE).ifEmpty { site }
            AskSource(site, t, url)
        }.distinctBy { it.site }.take(MAX_SOURCES)

    /** The card's line: "bbc.co.uk · Storm Amy: what we know". */
    fun line(s: AskSource): String = if (s.title == s.site) s.site else "${s.site} · ${s.title}"
}
