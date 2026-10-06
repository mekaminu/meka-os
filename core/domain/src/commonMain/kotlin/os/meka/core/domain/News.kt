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
}

/** Which topics the brief shows: one `context_mode` entity, id [News.ENTITY_ID], one LWW field. */
object NewsFields {
    /** Comma-separated [NewsTopic.id]s ("top,world"); empty for no news. Unknown ids are ignored when read. */
    const val TOPICS = "newsTopics"
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

    val ALL = listOf(TOP, UK, WORLD, POLITICS, BUSINESS, TECHNOLOGY, SCIENCE, HEALTH, FOOTBALL)
    val DEFAULT: List<String> = listOf(TOP.id, WORLD.id)

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
            )
        }
    }
}

/** One headline in the brief: "BBC News · Technology · 2 h ago". */
data class BriefHeadline(val id: String, val title: String, val url: String?, val meta: String)

/** A topic chip in the brief. */
data class NewsTopicChoice(val id: String, val label: String, val chosen: Boolean)

/** Pure rules, unit-tested without a replica. */
object NewsRules {
    /** Headlines shown in the brief. */
    const val MAX_IN_BRIEF = 5
    /** Older headlines are left out: the brief is about this morning. */
    const val MAX_AGE_MS = 36 * 3_600_000L
    private const val MAX_TITLE = 300

    /** Plain one-line text: control characters and anything tag-like removed, whitespace collapsed, length capped. */
    fun clean(s: String): String =
        s.replace(Regex("<[^>]*>"), " ").map { if (it < ' ') ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim().take(MAX_TITLE)

    /** The link only when it is a plain https URL with a host; anything else is never opened. */
    fun safeUrl(s: String): String? {
        val u = s.trim()
        if (!u.startsWith("https://", ignoreCase = true) || u.length > 2_000) return null
        val host = u.substring(8).takeWhile { it != '/' && it != '?' && it != '#' }
        if (host.isEmpty() || host.any { it.isWhitespace() || it == '@' }) return null
        if (u.any { it.isWhitespace() || it < ' ' }) return null
        return u
    }

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

    /** Key that treats the same story in two topic feeds as one. */
    private fun storyKey(h: Headline) = h.url?.substringBefore('?')?.substringBefore('#')?.lowercase() ?: ("t:" + h.title.lowercase())

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
            .filter { seenStories.add(storyKey(it)) && seenTitles.add(it.title.lowercase()) }
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

    fun topics(): List<String> =
        NewsTopics.decode(replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(NewsFields.TOPICS)?.textOrNull) ?: NewsTopics.DEFAULT

    fun choices(): List<NewsTopicChoice> = topics().let { chosen -> NewsTopics.ALL.map { NewsTopicChoice(it.id, it.label, it.id in chosen) } }

    /** Turns one topic on or off in the brief (synced). */
    fun setTopic(id: String, on: Boolean) {
        require(NewsTopics.byId(id) != null) { "unknown news topic" }
        val current = topics()
        val next = if (on) current + id else current - id
        val encoded = NewsTopics.encode(next)
        if (replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(NewsFields.TOPICS)?.textOrNull == encoded) return
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(NewsFields.TOPICS to encoded.fv()))
    }

    companion object {
        const val ENTITY_ID = "news"
    }
}
