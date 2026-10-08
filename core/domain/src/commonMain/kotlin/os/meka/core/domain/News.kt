package os.meka.core.domain

import os.meka.core.sync.EntitySnapshot
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * News headlines for the morning brief (build plan M1), non-AI. The server reads a few public RSS feeds (no sign-in,
 * nothing about Meka sent) through a `NewsProvider` (ADR-009) and mirrors the top few items of every topic in
 * [NewsTopics.ALL] as `headline` entities, one entity per (topic, slot), so the number of entities never grows and a
 * headline that stays in the feed is never rewritten. The apps show only the topics chosen here.
 *
 * Headlines are untrusted content (ADR-006): they are only ever shown as text, and a link is only opened when it is
 * https. Nothing in MEKA acts on them.
 */
object HeadlineFields {
    const val TITLE = "title"
    /** The article (https only; anything else is not offered as a link). */
    const val URL = "url"
    /** Who published it, shown with every headline ("BBC News"). */
    const val SOURCE = "source"
    /** A [NewsTopic.id]. */
    const val TOPIC = "topic"
    const val PUBLISHED_AT = "publishedAtMs"
    /** True when the slot is empty (the feed had fewer items). Can flip back to false. */
    const val REMOVED = "removed"
    /** The feed's own summary as plain text (news ticker, slice 1; additive). Absent when the feed has none. */
    const val SUMMARY = "summary"
    /**
     * The story's picture as the server keeps it (news, images slice; additive): a [NewsRules.isImageKey] key the apps
     * fetch from their own server (`/v1/news/image`), never the publisher's address. Absent when the feed has none.
     */
    const val IMAGE = "image"
}

/** Which topics the brief shows: one `context_mode` entity, id [News.ENTITY_ID], one LWW field. */
object NewsFields {
    /** Comma-separated [NewsTopic.id]s ("top,world"); empty for no news. Unknown ids are ignored when read. */
    const val TOPICS = "newsTopics"
    /**
     * The topics that existed when [TOPICS] was last written (additive, news ticker slice 1). A topic added since is
     * neither chosen nor turned off yet, so it follows its default; absent = the first nine (BBC) topics.
     */
    const val KNOWN = "newsTopicsKnown"
}

data class NewsTopic(val id: String, val label: String)

/** The topics the server mirrors. Ids are part of the wire format: never rename, only add. */
object NewsTopics {
    val TOP = NewsTopic("top", "Top stories")
    val UK = NewsTopic("uk", "UK")
    val WORLD = NewsTopic("world", "World")
    val POLITICS = NewsTopic("politics", "Politics")
    val BUSINESS = NewsTopic("business", "Business")
    val TECHNOLOGY = NewsTopic("technology", "Technology")
    val SCIENCE = NewsTopic("science", "Science")
    val HEALTH = NewsTopic("health", "Health")
    val FOOTBALL = NewsTopic("football", "Football")
    // News ticker, slice 1 (Meka, 2026-10-07): several public feeds each, see the server's PublicNewsFeeds.
    val BARCA = NewsTopic("barca", "Barça")
    val SPAIN = NewsTopic("spain", "Spain football")
    val AI = NewsTopic("ai", "AI")
    val TECH = NewsTopic("tech", "Tech news")

    /** In the order the chips are offered: Barça and AI first (what Meka checks most), then the BBC's topics. */
    val ALL = listOf(BARCA, AI, TOP, WORLD, UK, POLITICS, BUSINESS, TECHNOLOGY, TECH, SCIENCE, HEALTH, FOOTBALL, SPAIN)
    val DEFAULT: List<String> = listOf(BARCA.id, AI.id, TOP.id, WORLD.id)
    /** The topics there were before the news ticker (what an older stored choice knew about). */
    val ORIGINAL: List<String> = listOf(TOP.id, UK.id, WORLD.id, POLITICS.id, BUSINESS.id, TECHNOLOGY.id, SCIENCE.id, HEALTH.id, FOOTBALL.id)

    fun byId(id: String): NewsTopic? = ALL.firstOrNull { it.id == id }

    fun encode(ids: List<String>): String = ALL.map { it.id }.filter { it in ids }.joinToString(",")
    fun decode(s: String?): List<String>? = s?.split(',')?.map { it.trim() }?.filter { id -> ALL.any { it.id == id } }
}

/** A mirrored headline as the apps read it. */
data class Headline(
    val id: String,
    val title: String,
    val url: String?,
    val source: String,
    val topic: String,
    val publishedAtMs: Long,
    val summary: String? = null,
    /** The server's key for the story's picture ([HeadlineFields.IMAGE]); null when there is none. */
    val imageKey: String? = null,
) {
    companion object {
        /** Null for empty slots and anything incomplete. */
        fun from(s: EntitySnapshot): Headline? {
            if (s[HeadlineFields.REMOVED].boolOrNull == true) return null
            val title = s[HeadlineFields.TITLE].textOrNull?.let(NewsRules::clean)?.takeIf { it.isNotEmpty() } ?: return null
            val at = s[HeadlineFields.PUBLISHED_AT].longOrNull ?: return null
            return Headline(
                id = s.ref.entityId,
                title = title,
                url = s[HeadlineFields.URL].textOrNull?.let(NewsRules::safeUrl),
                source = s[HeadlineFields.SOURCE].textOrNull?.let(NewsRules::clean)?.takeIf { it.isNotEmpty() } ?: "News",
                topic = s[HeadlineFields.TOPIC].textOrNull ?: "",
                publishedAtMs = at,
                summary = s[HeadlineFields.SUMMARY].textOrNull?.let(NewsRules::cleanSummary)?.takeIf { it.isNotEmpty() },
                imageKey = s[HeadlineFields.IMAGE].textOrNull?.takeIf(NewsRules::isImageKey),
            )
        }
    }
}

/** One headline in the brief: "BBC News · Technology · 2 h ago". */
data class BriefHeadline(val id: String, val title: String, val url: String?, val meta: String)

/** A topic chip in the brief. */
data class NewsTopicChoice(val id: String, val label: String, val chosen: Boolean)

/** One headline in the News place: "Mundo Deportivo · 2 h ago", and the feed's summary for the detail sheet. */
data class NewsItem(
    val id: String,
    val title: String,
    val url: String?,
    val source: String,
    val topic: String,
    val meta: String,
    val summary: String?,
    val publishedAtMs: Long,
    /** The picture to fetch with `MekaCore.newsImage` ([HeadlineFields.IMAGE]); null → the source's tile. */
    val imageKey: String? = null,
) {
    /** The tile shown while the picture loads or when there is none: the source's initial ("M" for Mundo Deportivo). */
    val tileInitial: String get() = NewsRules.tileInitial(source)
    /** The source's short name on its tile when there is no picture ("MD", "BBC", "TC"); see [NewsRules.tileMark]. */
    val tileMark: String get() = NewsRules.tileMark(source)
}

/** One topic's lane: "Barça" with "From Mundo Deportivo, Sport and Google News". */
data class NewsLane(val topicId: String, val label: String, val sources: String, val items: List<NewsItem>) {
    /** The Barça lane has its own colour (the `barca` token) on both apps. */
    val isBarca: Boolean get() = topicId == NewsTopics.BARCA.id
}

/**
 * On matchday the News place (and later the ticker) leads with the fixture: "Barça v Real Madrid · 21:00 · in 3 h",
 * "… · on now" once it has kicked off, gone at the final whistle. [event] opens the fixture's detail.
 */
data class NewsMatchday(val eventId: String, val title: String, val line: String, val live: Boolean, val event: CalendarEvent)

/** The detail sheet: the story, its place in the run ("3 of 18") and the stories either side (Next/Previous). */
data class NewsDetail(val item: NewsItem, val position: String, val previousId: String?, val nextId: String?)

/**
 * The News place (Ask → More → News): the chosen topics as lanes, Barça first, then AI, then the rest in chip order;
 * each story once (in the first lane it fits); [emptyLine] when there's nothing to show and why.
 */
data class NewsPlace(
    val lanes: List<NewsLane>,
    val topics: List<NewsTopicChoice>,
    val emptyLine: String?,
    /** Today's Barça fixture while it is still to come or on (only with the Barça topic chosen); null otherwise. */
    val matchday: NewsMatchday? = null,
) {
    /** Every story in reading order (lane by lane), for Next/Previous. */
    val items: List<NewsItem> get() = lanes.flatMap { it.items }

    fun detail(id: String): NewsDetail? {
        val all = items
        val i = all.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        return NewsDetail(all[i], "${i + 1} of ${all.size}", all.getOrNull(i - 1)?.id, all.getOrNull(i + 1)?.id)
    }

    companion object {
        val EMPTY = NewsPlace(emptyList(), emptyList(), null)
    }
}

/** Pure rules, unit-tested without a replica. */
object NewsRules {
    /** Headlines shown in the brief. */
    const val MAX_IN_BRIEF = 5
    /** Older headlines are left out: the brief is about this morning. */
    const val MAX_AGE_MS = 36 * 3_600_000L
    private const val MAX_TITLE = 300
    private const val MAX_SUMMARY = 500
    /** The News place keeps two days, so a quiet weekend still shows Saturday's Barça news on Sunday evening. */
    const val MAX_AGE_IN_PLACE_MS = 48 * 3_600_000L
    /** Headlines per lane in the News place. */
    const val MAX_IN_LANE = 10

    /** Plain one-line text: control characters and anything tag-like removed, whitespace collapsed, length capped. */
    fun clean(s: String): String =
        s.replace(Regex("<[^>]*>"), " ").map { if (it < ' ') ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim().take(MAX_TITLE)

    /** A summary as plain text (the same cleaning, a longer cap). */
    fun cleanSummary(s: String): String =
        s.replace(Regex("<[^>]*>"), " ").map { if (it < ' ') ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim().take(MAX_SUMMARY)

    /** The link only when it is a plain https URL with a host; anything else is never opened. */
    fun safeUrl(s: String): String? {
        val u = s.trim()
        if (!u.startsWith("https://", ignoreCase = true) || u.length > 2_000) return null
        val host = u.substring(8).takeWhile { it != '/' && it != '?' && it != '#' }
        if (host.isEmpty() || host.any { it.isWhitespace() || it == '@' }) return null
        if (u.any { it.isWhitespace() || it < ' ' }) return null
        return u
    }

    /**
     * A picture key from the server: 32 lowercase hex characters (the first half of the SHA-256 of the picture's
     * address). Anything else is ignored, so a key can never be a path or an address.
     */
    fun isImageKey(s: String): Boolean = s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** The source's first letter or digit, upper-cased ("Mundo Deportivo" → "M", "9to5Mac" → "9"); "N" when none. */
    fun tileInitial(source: String): String =
        source.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "N"

    /**
     * The source's short name for its picture-less tile (Fold review 2026-10-08: a lone grey "T" looked unfinished):
     * an all-capitals first word of 2–4 is kept ("BBC News" → "BBC", "ESPN" → "ESPN"); several words give their
     * initials, a leading "The"/"El"/"La"/"Le" left out ("Mundo Deportivo" → "MD", "Sky Sports" → "SS"); one
     * camel-cased word gives its capitals ("TechCrunch" → "TC", "9to5Mac" → "9M"); otherwise the first letter
     * ("Sport" → "S", "The Athletic" → "A"). At most 3 characters, upper-cased; "N" when there is nothing to use.
     */
    fun tileMark(source: String): String {
        val words = mutableListOf<String>()
        val cur = StringBuilder()
        for (ch in source) {
            if (ch.isLetterOrDigit()) cur.append(ch) else if (cur.isNotEmpty()) { words += cur.toString(); cur.clear() }
        }
        if (cur.isNotEmpty()) words += cur.toString()
        if (words.isEmpty()) return "N"
        val first = words.first()
        if (first.length in 2..4 && first.any { it.isLetter() } && first.all { !it.isLetter() || it.isUpperCase() }) return first
        val kept = if (words.size > 1 && first.lowercase() in LEADING_ARTICLES) words.drop(1) else words
        if (kept.size > 1) return kept.take(3).joinToString("") { it.first().uppercaseChar().toString() }
        val word = kept.first()
        val caps = word.filterIndexed { i, c -> c.isUpperCase() || (i == 0 && c.isDigit()) }
        if (caps.length >= 2) return caps.take(3)
        return word.first().uppercaseChar().toString()
    }

    private val LEADING_ARTICLES = setOf("the", "el", "la", "le", "les", "los", "il")

    /** "just now", "25 min ago", "3 h ago", "yesterday". */
    fun age(publishedAtMs: Long, nowMs: Long): String {
        val min = ((nowMs - publishedAtMs) / 60_000L).coerceAtLeast(0)
        return when {
            min < 5 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            else -> "yesterday"
        }
    }

    /**
     * The chosen topics from the stored choice ([NewsFields.TOPICS]) and the topics it knew about
     * ([NewsFields.KNOWN]): nothing stored = the defaults; a topic added since the choice was made follows its default.
     */
    fun chosen(stored: String?, known: String?): List<String> {
        val picked = NewsTopics.decode(stored) ?: return NewsTopics.DEFAULT.let { d -> NewsTopics.ALL.map { it.id }.filter { it in d } }
        val knew = known?.split(',')?.map { it.trim() }?.toSet() ?: NewsTopics.ORIGINAL.toSet()
        val added = NewsTopics.DEFAULT.filter { it !in knew }
        return NewsTopics.ALL.map { it.id }.filter { it in picked || it in added }
    }

    /** Lanes lead with these, then the other chosen topics in chip order. */
    private val LEAD = listOf(NewsTopics.BARCA.id, NewsTopics.AI.id)

    /** "From Mundo Deportivo, Sport and Google News" (most items first). */
    private fun sourcesLine(items: List<NewsItem>): String {
        val names = items.groupingBy { it.source }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
        return when (names.size) {
            0 -> ""
            1 -> "From ${names[0]}"
            else -> "From " + names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }

    /**
     * The News place: the chosen topics only, the last [MAX_AGE_IN_PLACE_MS], newest first in each lane, each story
     * once across lanes (the same article or the same title from another source), at most [MAX_IN_LANE] per lane.
     */
    fun place(all: List<Headline>, topics: List<NewsTopicChoice>, nowMs: Long, matchday: NewsMatchday? = null): NewsPlace {
        val chosen = topics.filter { it.chosen }.map { it.id }
        val order = LEAD.filter { it in chosen } + chosen.filter { it !in LEAD }
        val seenLinks = HashSet<String>()
        val seenTitles = HashSet<String>()
        val fresh = all.filter { it.publishedAtMs <= nowMs + 10 * 60_000L && nowMs - it.publishedAtMs <= MAX_AGE_IN_PLACE_MS }
        val lanes = order.mapNotNull { topic ->
            val items = fresh.filter { it.topic == topic }
                .sortedWith(compareByDescending<Headline> { it.publishedAtMs }.thenBy { it.id })
                .filter { seenLinks.add(linkKey(it)) && seenTitles.add(storyKey(it.title)) }
                .take(MAX_IN_LANE)
                .map { h -> NewsItem(h.id, h.title, h.url, h.source, h.topic, "${h.source} · ${age(h.publishedAtMs, nowMs)}", h.summary, h.publishedAtMs, h.imageKey) }
            if (items.isEmpty()) null
            else NewsLane(topic, NewsTopics.byId(topic)?.label ?: topic, sourcesLine(items), items)
        }
        val empty = when {
            lanes.isNotEmpty() -> null
            chosen.isEmpty() -> "No topics chosen · pick some below"
            else -> "No headlines in the last two days · they refresh every hour"
        }
        return NewsPlace(lanes, topics, empty, matchday?.takeIf { NewsTopics.BARCA.id in chosen })
    }

    /** What the fixtures feed adds to a title whose kick-off isn't set yet. */
    private const val TBC_SUFFIX = "(kick-off TBC)"

    /**
     * Today's Barça fixture for the top of the News place: the first fixture (not all-day) that starts today and
     * hasn't finished. Pass the events shown on my day, so a hidden fixture stays hidden. "Barça v Real Madrid ·
     * 21:00 · in 3 h" / "· in 25 min" / "· kicking off", "· on now" once it has started, "· today · kick-off TBC".
     */
    fun matchday(events: List<CalendarEvent>, nowMs: Long, cal: LocalCalendar): NewsMatchday? {
        val today = cal.epochDayOf(nowMs)
        val e = events.filter { it.isFixture && !it.allDay && it.endAtMs > nowMs && cal.epochDayOf(it.startAtMs) == today }
            .minWithOrNull(compareBy<CalendarEvent> { it.startAtMs }.thenBy { it.id }) ?: return null
        val tbc = e.title.trimEnd().endsWith(TBC_SUFFIX)
        val title = e.title.trimEnd().removeSuffix(TBC_SUFFIX).trim().ifEmpty { "Barça" }
        val live = !tbc && nowMs >= e.startAtMs
        val line = when {
            tbc -> "$title · today · kick-off TBC"
            live -> "$title · ${LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs))} · on now"
            else -> {
                val min = ((e.startAtMs - nowMs) / 60_000L).toInt()
                "$title · ${LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs))} · " + if (min <= 0) "kicking off" else "in ${TimelineRules.inLabel(min)}"
            }
        }
        return NewsMatchday(e.id, title, line, live, e)
    }

    /** Key that treats the same article in two topic feeds as one. */
    private fun linkKey(h: Headline) = h.url?.substringBefore('?')?.substringBefore('#')?.lowercase() ?: ("t:" + h.title.lowercase())

    private val accents = mapOf(
        'á' to 'a', 'à' to 'a', 'â' to 'a', 'ä' to 'a', 'ã' to 'a', 'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
        'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i', 'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'ö' to 'o', 'õ' to 'o',
        'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ñ' to 'n', 'ç' to 'c',
    )

    /**
     * The same story from several sources, as one key: the title lower-cased, accents and punctuation dropped,
     * spacing collapsed ("Barça beat Real Madrid 3–1!" and "Barca beat Real Madrid 3-1" match). Titles that differ in
     * wording stay apart (merging those is the AI layer's job).
     */
    fun storyKey(title: String): String = buildString {
        var space = false
        for (raw in title.lowercase()) {
            val c = accents[raw] ?: raw
            if (c.isLetterOrDigit()) { if (space && isNotEmpty()) append(' '); append(c); space = false } else space = true
        }
    }

    /**
     * The brief's headlines: the chosen topics only, from the last [MAX_AGE_MS], newest first, each story once (the
     * same article in two topic feeds, or the same title), at most [MAX_IN_BRIEF].
     */
    fun forBrief(all: List<Headline>, chosen: List<String>, nowMs: Long): List<BriefHeadline> {
        val seenStories = HashSet<String>()
        val seenTitles = HashSet<String>()
        return all.asSequence()
            .filter { it.topic in chosen && it.publishedAtMs <= nowMs + 10 * 60_000L && nowMs - it.publishedAtMs <= MAX_AGE_MS }
            .sortedWith(compareByDescending<Headline> { it.publishedAtMs }.thenBy { chosen.indexOf(it.topic) }.thenBy { it.id })
            .filter { seenStories.add(linkKey(it)) && seenTitles.add(storyKey(it.title)) }
            .take(MAX_IN_BRIEF)
            .map { h ->
                val topic = NewsTopics.byId(h.topic)?.takeIf { chosen.size > 1 }?.label
                BriefHeadline(h.id, h.title, h.url, listOfNotNull(h.source, topic, age(h.publishedAtMs, nowMs)).joinToString(" · "))
            }
            .toList()
    }
}

/** Mirrored headlines (read-only: the server writes them) and the synced choice of topics. */
class News(private val replica: Replica) {
    fun all(): List<Headline> = replica.entities(EntityTypes.HEADLINE).mapNotNull { Headline.from(it) }

    fun topics(): List<String> {
        val e = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)
        return NewsRules.chosen(e?.get(NewsFields.TOPICS)?.textOrNull, e?.get(NewsFields.KNOWN)?.textOrNull)
    }

    fun choices(): List<NewsTopicChoice> = topics().let { chosen -> NewsTopics.ALL.map { NewsTopicChoice(it.id, it.label, it.id in chosen) } }

    /** Turns one topic on or off in the brief (synced). */
    fun setTopic(id: String, on: Boolean) {
        require(NewsTopics.byId(id) != null) { "unknown news topic" }
        val current = topics()
        val next = if (on) current + id else current - id
        val encoded = NewsTopics.encode(next)
        val known = NewsTopics.ALL.joinToString(",") { it.id }
        val e = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)
        if (e?.get(NewsFields.TOPICS)?.textOrNull == encoded && e?.get(NewsFields.KNOWN)?.textOrNull == known) return
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(NewsFields.TOPICS to encoded.fv(), NewsFields.KNOWN to known.fv()))
    }

    /** The News place over the chosen topics. */
    fun place(nowMs: Long, dayEvents: List<CalendarEvent> = emptyList(), cal: LocalCalendar = LocalCalendar.UTC): NewsPlace =
        NewsRules.place(all(), choices(), nowMs, NewsRules.matchday(dayEvents, nowMs, cal))

    companion object {
        const val ENTITY_ID = "news"
    }
}
