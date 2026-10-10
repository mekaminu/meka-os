package os.meka.core.domain

/*
 * Read-outs in Talk (build plan V1, "MEKA as the phone's default assistant + voice": "read my brief", "the headlines",
 * "Barça news"; non-AI, pure).
 *
 * Asked aloud, MEKA reads the morning brief, the headlines or one news topic's latest stories in its voice, as a
 * reading (short pieces asked for ahead, so the voice never changes mid-read), then listens again. Recognised on the
 * device: nothing goes to the AI for this and it doesn't count towards the question cap. Headlines are other people's
 * words (ADR-006): they are only ever read out as plain text, never acted on, and never sent with a later question
 * (the conversation remembers only that MEKA read them).
 */

/** What to read: the morning [Kind.BRIEF], the brief's [Kind.HEADLINES], or one news [topicId]'s stories ([Kind.TOPIC]). */
data class ReadOut(val kind: Kind, val topicId: String? = null) {
    enum class Kind { BRIEF, HEADLINES, TOPIC }
}

object ReadOutRules {
    /** Stories read for one topic (the brief's headlines are already at most [NewsRules.MAX_IN_BRIEF]). */
    const val MAX_STORIES = 5

    const val NO_HEADLINES = "There are no headlines right now. Anything else?"

    /** Words around the request that change nothing ("can you read me the news please"). */
    private val DROP = setOf(
        "please", "meka", "hey", "can", "could", "would", "will", "you", "me", "us", "now", "then", "so", "just", "um",
        "umm", "uh", "er", "erm", "ok", "okay", "for", "out", "aloud", "loud", "again", "quickly",
    )
    /** Asking words before the thing ("read", "what's", "is there any", "tell me", "give me", "go through"). */
    private val ASKING = listOf(
        listOf("read", "through"), listOf("go", "through"), listOf("is", "there", "any"), listOf("are", "there", "any"),
        listOf("what", "is"), listOf("what's"), listOf("whats"), listOf("what", "are"), listOf("what're"),
        listOf("tell"), listOf("give"), listOf("read"), listOf("play"), listOf("any"),
    )
    /** Words between the asking and the thing ("the", "my", "today's", "the latest"). */
    private val DETERMINERS = setOf("the", "my", "today's", "todays", "this", "morning's", "mornings", "latest", "new", "any", "some", "in")
    /** Words after the thing that change nothing ("the news today", "the headlines this morning"). */
    private val TRAILING = setOf("today", "this", "morning", "right", "now", "at", "the", "moment")

    private val BRIEF = listOf(listOf("morning", "brief"), listOf("morning", "briefing"), listOf("brief"), listOf("briefing"))
    private val NEWS = listOf(listOf("news", "headlines"), listOf("headlines"), listOf("news"), listOf("top", "stories"))
    /** Words that name each topic, as Meka would say them ("barça" reads as "barca"). */
    private val TOPIC_WORDS: List<Pair<List<String>, String>> = listOf(
        listOf("spanish", "football") to NewsTopics.SPAIN.id,
        listOf("spain", "football") to NewsTopics.SPAIN.id,
        listOf("la", "liga") to NewsTopics.SPAIN.id,
        listOf("barca") to NewsTopics.BARCA.id,
        listOf("barcelona") to NewsTopics.BARCA.id,
        listOf("barsa") to NewsTopics.BARCA.id,
        listOf("ai") to NewsTopics.AI.id,
        listOf("a", "i") to NewsTopics.AI.id,
        listOf("tech") to NewsTopics.TECH.id,
        listOf("technology") to NewsTopics.TECHNOLOGY.id,
        listOf("football") to NewsTopics.FOOTBALL.id,
        listOf("world") to NewsTopics.WORLD.id,
        listOf("uk") to NewsTopics.UK.id,
        listOf("politics") to NewsTopics.POLITICS.id,
        listOf("political") to NewsTopics.POLITICS.id,
        listOf("business") to NewsTopics.BUSINESS.id,
        listOf("science") to NewsTopics.SCIENCE.id,
        listOf("health") to NewsTopics.HEALTH.id,
    ).sortedByDescending { it.first.size }

    /** Lower-case words, accents folded ("Barça" → "barca"), punctuation gone (apostrophes kept). */
    fun words(utterance: String): List<String> =
        TalkRules.words(fold(utterance))

    private fun fold(s: String): String = buildString {
        s.lowercase().forEach { c ->
            append(
                when (c) {
                    'ç' -> 'c'; 'á', 'à', 'â', 'ä' -> 'a'; 'é', 'è', 'ê', 'ë' -> 'e'; 'í', 'ì', 'î', 'ï' -> 'i'
                    'ó', 'ò', 'ô', 'ö' -> 'o'; 'ú', 'ù', 'û', 'ü' -> 'u'; 'ñ' -> 'n'; else -> c
                },
            )
        }
    }

    /**
     * What Meka asked to hear, or null when it isn't a read-out: "read my brief", "brief me", "what's in my morning
     * brief", "read the headlines", "what's the news", "any news?", "Barça news", "what's the latest Barça news",
     * "read me the AI headlines", "news about Barcelona". Anything with more in it ("what's the news on the
     * strike and should I drive?") goes to MEKA as a question.
     */
    fun request(utterance: String): ReadOut? {
        val ws = words(utterance).filter { it !in DROP }
        if (ws.isEmpty()) return null
        if (ws == listOf("brief") || ws == listOf("brief", "me")) return ReadOut(ReadOut.Kind.BRIEF)
        val asked = ASKING.firstOrNull { ws.size >= it.size && ws.subList(0, it.size) == it }?.size ?: 0
        var rest = ws.drop(asked).dropWhile { it in DETERMINERS }
        rest = rest.dropLastWhile { it in TRAILING }
        if (rest.isEmpty()) return null
        if (rest in BRIEF) return ReadOut(ReadOut.Kind.BRIEF)
        if (rest in NEWS) return ReadOut(ReadOut.Kind.HEADLINES)
        // "<topic> news", "<topic> headlines", "news about/on <topic>", "the latest on <topic>"
        topicBefore(rest)?.let { return ReadOut(ReadOut.Kind.TOPIC, it) }
        val after = NEWS.firstOrNull { rest.size > it.size && rest.subList(0, it.size) == it }?.size
        if (after != null) {
            val tail = rest.drop(after)
            if (tail.firstOrNull() in setOf("about", "on", "from", "for")) topicOnly(tail.drop(1))?.let { return ReadOut(ReadOut.Kind.TOPIC, it) }
        }
        if (rest.firstOrNull() == "on" && "latest" in ws.take(ws.size - rest.size)) {
            topicOnly(rest.drop(1))?.let { return ReadOut(ReadOut.Kind.TOPIC, it) }
        }
        return null
    }

    /** "barca news" → "barca"; the topic's words then a news word, nothing else. */
    private fun topicBefore(rest: List<String>): String? {
        for ((topic, id) in TOPIC_WORDS) {
            if (rest.size > topic.size && rest.subList(0, topic.size) == topic && rest.drop(topic.size) in NEWS) return id
        }
        return null
    }

    /** "barcelona" → "barca"; only the topic's words. */
    private fun topicOnly(rest: List<String>): String? {
        val r = rest.dropWhile { it == "the" }
        return TOPIC_WORDS.firstOrNull { it.first == r }?.second
    }

    /** What the conversation remembers MEKA did (never the headlines themselves: they are untrusted, ADR-006). */
    fun remembered(read: ReadOut): String = when (read.kind) {
        ReadOut.Kind.BRIEF -> "Read Meka the morning brief aloud."
        ReadOut.Kind.HEADLINES -> "Read Meka the headlines aloud."
        ReadOut.Kind.TOPIC -> "Read Meka the latest ${label(read.topicId)} news aloud."
    }

    /** The line under the orb while MEKA reads (not the whole text): "Reading your morning brief…". */
    fun showing(read: ReadOut): String = when (read.kind) {
        ReadOut.Kind.BRIEF -> "Reading your morning brief…"
        ReadOut.Kind.HEADLINES -> "Reading the headlines…"
        ReadOut.Kind.TOPIC -> "Reading the latest ${label(read.topicId)} news…"
    }

    /** A topic as said in a sentence: "Barça", "AI", "UK", "Spanish football", else lower case ("tech", "world"). */
    fun spokenLabel(topicId: String?): String = when (topicId) {
        NewsTopics.BARCA.id -> "Barça"
        NewsTopics.AI.id -> "AI"
        NewsTopics.UK.id -> "UK"
        NewsTopics.SPAIN.id -> "Spanish football"
        NewsTopics.TECH.id -> "tech"
        null -> "the"
        else -> NewsTopics.byId(topicId)?.label?.lowercase() ?: "the"
    }

    private fun label(topicId: String?): String = spokenLabel(topicId)

    /**
     * The words MEKA reads for [read]: the brief as its Listen button reads it ([BriefSpeech.script]); the brief's
     * headlines ("In the news. From BBC Sport: …"); or one topic's newest [MAX_STORIES] stories from the News place
     * ("The latest Barça news. From Mundo Deportivo: …"). Each ends "Anything else?" so the conversation carries on.
     * A topic that isn't chosen, or has nothing in the last two days, is said plainly.
     */
    fun text(read: ReadOut, brief: MorningBriefView, place: NewsPlace, name: String = "Meka"): String = when (read.kind) {
        ReadOut.Kind.BRIEF -> BriefSpeech.script(brief, name) + " " + TalkRules.ANYTHING_ELSE
        ReadOut.Kind.HEADLINES -> {
            val lines = BriefSpeech.news(brief.headlines)
            if (lines.isEmpty()) NO_HEADLINES else (lines + TalkRules.ANYTHING_ELSE).joinToString(" ")
        }
        ReadOut.Kind.TOPIC -> topicText(read.topicId, place)
    }

    private fun topicText(topicId: String?, place: NewsPlace): String {
        val label = label(topicId)
        val chosen = place.topics.firstOrNull { it.id == topicId }?.chosen ?: false
        val lane = place.lanes.firstOrNull { it.topicId == topicId }
        if (lane == null || lane.items.isEmpty()) {
            return if (!chosen) "${label.replaceFirstChar { it.uppercase() }} isn't one of your news topics. You can turn it on in News. Anything else?"
            else "There's no $label news in the last two days. Anything else?"
        }
        val stories = lane.items.take(MAX_STORIES).map { story(it.source, it.title) }
        return (listOf("The latest $label news.") + stories + TalkRules.ANYTHING_ELSE).joinToString(" ")
    }

    /** "From Mundo Deportivo: Barça win again." (the title made speakable and shortened like the brief's). */
    private fun story(source: String, title: String): String {
        val t = BriefSpeech.shorten(BriefSpeech.clean(title)).trimEnd(',', ';', ' ')
        val said = if (source.isBlank()) t else "From ${source.trim()}: $t"
        return if (said.isNotEmpty() && said.last() in ".!?…") said else "$said."
    }
}
