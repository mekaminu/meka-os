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
    /**
     * The story's language when it isn't English ("es"), as the server's feed list names it (Fold review 2026-10-09
     * 07:26 item 1; additive). Absent = English. Non-English stories show only with [NewsFields.SPANISH] on.
     */
    const val LANG = "lang"
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
    /** News → Spanish sources (Fold review 2026-10-09 07:26 item 1; additive, LWW): absent = off, English only. */
    const val SPANISH = "newsSpanish"
    /**
     * The stories Meka has read (full news hub, slice 1; additive, LWW): comma-separated [NewsRules.readKey]s, oldest
     * first, at most [NewsRules.MAX_READ]. A story is known by its link (or title), never by its headline slot, since a
     * slot is reused once its story drops out of the feed. Two devices marking at once: the later list wins (a story
     * read on one may show as new again on the other: harmless).
     */
    const val READ = "newsRead"
    /**
     * The sources Meka switched off (full news hub, slice 2; additive, LWW): comma-separated publisher names as the
     * stories carry them ("BBC Sport", "Daily Star"), commas taken out, at most [NewsRules.MAX_SOURCES_OFF]; matched by
     * [NewsRules.sourceKey]. A story from one of them is left out of News, the ticker and the brief. Absent = all on.
     */
    const val SOURCES_OFF = "newsSourcesOff"
    /**
     * The words and names Meka follows (full news hub, slice 3; additive, LWW): comma-separated phrases as he typed
     * them ("Arsenal", "Lamine Yamal"), at most [NewsRules.MAX_FOLLOWS], matched by [NewsRules.followKey] as whole
     * words in a story's title or summary, whatever its topic. Matching stories lead News in a Following lane and
     * reach the ticker and the brief. Absent = none.
     */
    const val FOLLOWS = "newsFollows"
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
    /** [HeadlineFields.LANG] lower-cased; null = English. */
    val lang: String? = null,
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
                lang = s[HeadlineFields.LANG].textOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "en" },
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
    /** Full news hub, slice 1: opened (here or on the other app), or put away with Mark all read. */
    val read: Boolean = false,
    /** Full news hub, slice 1: its link is waiting in the train queue. */
    val queued: Boolean = false,
    /** Full news hub, slice 3: the followed word it matched (it sits in the Following lane); null otherwise. */
    val followed: String? = null,
) {
    /** The key the read list holds for this story ([NewsRules.readKey]). */
    val readKey: String get() = NewsRules.readKey(url, title)
    /** "Save for the train" is offered only for a story with a link that isn't waiting already. */
    val canQueue: Boolean get() = url != null && !queued

    /** The tile shown while the picture loads or when there is none: the source's initial ("M" for Mundo Deportivo). */
    val tileInitial: String get() = NewsRules.tileInitial(source)
    /** The source's short name on its tile when there is no picture ("MD", "BBC", "TC"); see [NewsRules.tileMark]. */
    val tileMark: String get() = NewsRules.tileMark(source)
}

/** One topic's lane: "Barça" with "From Mundo Deportivo, Sport and Google News". */
data class NewsLane(val topicId: String, val label: String, val sources: String, val items: List<NewsItem>) {
    /** The Barça lane has its own colour (the `barca` token) on both apps. */
    val isBarca: Boolean get() = topicId == NewsTopics.BARCA.id
    /** Full news hub, slice 3: the lane of stories matching the words he follows, first of all. */
    val isFollowing: Boolean get() = topicId == NewsRules.FOLLOWING_LANE
    /** Full news hub, slice 1: stories not read yet in this lane. */
    val unread: Int get() = items.count { !it.read }
    /** "3 new" beside the lane's name, or null when everything in it is read. */
    val unreadLabel: String? get() = unread.takeIf { it > 0 }?.let { "$it new" }
}

/**
 * On matchday the News place (and later the ticker) leads with the fixture: "Barça v Real Madrid · 21:00 · in 3 h",
 * "… · on now" once it has kicked off, gone at the final whistle. [event] opens the fixture's detail.
 */
data class NewsMatchday(val eventId: String, val title: String, val line: String, val live: Boolean, val event: CalendarEvent)

/**
 * Full news hub, slice 2: one source's switch under Topics — "BBC News · 8" while on, "Daily Star · off" once off. [stories]
 * counts its stories in the chosen topics over the last two days (0 for an off source with nothing now).
 */
data class NewsSourceChoice(
    val key: String, val name: String, val stories: Int, val on: Boolean,
    /** Full news hub, slice 5: the server couldn't read this source at its last tries ([NewsFeedDown]). */
    val down: Boolean = false,
) {
    val label: String get() = when {
        !on -> "$name · off"
        down -> "$name · $stories · not coming through"
        else -> "$name · $stories"
    }
}

/**
 * Full news hub, slice 5: one feed the server couldn't read (an error, or nothing usable in it) at its last [fails]
 * tries in a row, since [sinceMs]: the source's name as its stories carry it, and the topic it feeds. Display state
 * from the server's account list, never synced.
 */
data class NewsFeedDown(val source: String, val topic: String, val sinceMs: Long, val fails: Int)

/**
 * Full news hub, slice 3: one word or name he follows — "Arsenal · 3" (stories in the last two days, any topic, from
 * the sources he has on), "Bitcoin · none yet". A tap takes it off (with Undo).
 */
data class NewsFollowChoice(val key: String, val name: String, val stories: Int) {
    val label: String get() = if (stories > 0) "$name · $stories" else "$name · none yet"
}

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
    /** News → Spanish sources: off by default (English only). */
    val spanishSources: Boolean = false,
    /** Full news hub, slice 2: every source in the chosen topics (most stories first), then the ones switched off. */
    val sources: List<NewsSourceChoice> = emptyList(),
    /**
     * Full news hub, slice 2: a chosen topic with nothing to show, and why — "AI · its sources are off" or "Health ·
     * nothing in the last two days" — shown after the lanes.
     */
    val quiet: List<String> = emptyList(),
    /** Full news hub, slice 3: the words and names he follows, in the order he added them. */
    val follows: List<NewsFollowChoice> = emptyList(),
) {
    /** "12 sources · 2 off · …" under the source switches; null with none. */
    val sourcesLine: String? get() = NewsRules.sourceSwitchesLine(sources)
    /** The line under Follow: what following does, or how many he follows and what a tap does. */
    val followsLine: String get() = NewsRules.followsLine(follows)
    /** Whether another word can be followed ([NewsRules.MAX_FOLLOWS]). */
    val canFollowMore: Boolean get() = follows.size < NewsRules.MAX_FOLLOWS

    /** The switch's line under Topics, and the caption naming every source for what's switched on. */
    val spanishLine: String get() = NewsRules.spanishLine(spanishSources)
    val sourcesCaption: String get() = NewsRules.sourcesCaption(spanishSources)

    /** Every story in reading order (lane by lane), for Next/Previous. */
    val items: List<NewsItem> get() = lanes.flatMap { it.items }

    /** Full news hub, slice 1: stories not read yet across the lanes. */
    val unread: Int get() = items.count { !it.read }
    /** The line over the lanes: "12 new · 6 read", "All read · new stories get a dot", or null with nothing to show. */
    val unreadLine: String? get() = NewsRules.unreadLine(unread, items.size)
    /** Mark all read is offered while something is new. */
    val canMarkAllRead: Boolean get() = unread > 0

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
    /** Read stories remembered (well over two days of every topic's lanes); the oldest go first. */
    const val MAX_READ = 400
    /** Sources that can be switched off at once (well over what the feeds name). */
    const val MAX_SOURCES_OFF = 40
    private const val MAX_SOURCE_NAME = 60
    /** Words and names followed at once. */
    const val MAX_FOLLOWS = 12
    /** A followed phrase's longest (a name or a few words, not a sentence). */
    const val MAX_FOLLOW_CHARS = 40
    /** The Following lane's id ([NewsLane.topicId]); never a topic id. */
    const val FOLLOWING_LANE = "following"

    /** A followed phrase's key: as [storyKey] makes it ("Lamine  Yamal!" and "lamine yamal" are one). */
    fun followKey(phrase: String): String = storyKey(phrase)

    /** The stored follows as display phrases (blank, too-short and repeated ones dropped), in the order added. */
    fun follows(stored: String?): List<String> {
        val seen = HashSet<String>()
        return stored?.split(',')?.map { clean(it).take(MAX_FOLLOW_CHARS) }
            ?.filter { followKey(it).length >= 2 && seen.add(followKey(it)) } ?: emptyList()
    }

    /**
     * What he typed under Follow, as phrases: split on commas, semicolons and " and " only between commas is kept as
     * one name ("Marks and Spencer" stays whole), each cleaned and capped; under 2 letters dropped.
     */
    fun parseFollows(typed: String): List<String> {
        val seen = HashSet<String>()
        return typed.split(',', ';', '\n').map { clean(it).trim('"', '\'', '#', '@', ' ').take(MAX_FOLLOW_CHARS).trim() }
            .filter { followKey(it).length >= 2 && seen.add(followKey(it)) }
    }

    /**
     * [stored] with [phrases] followed (added at the end, each once; past [MAX_FOLLOWS] the newest are not added) or,
     * with [on] false, unfollowed; null when nothing changes. The second value is what was actually added or taken off.
     */
    fun withFollows(stored: String?, phrases: List<String>, on: Boolean): Pair<String, List<String>>? {
        val have = follows(stored)
        val keys = have.map(::followKey).toHashSet()
        return if (on) {
            val room = (MAX_FOLLOWS - have.size).coerceAtLeast(0)
            val added = phrases.map { clean(it.replace(',', ' ')).take(MAX_FOLLOW_CHARS) }
                .filter { followKey(it).length >= 2 && keys.add(followKey(it)) }.take(room)
            if (added.isEmpty()) null else (have + added).joinToString(",") to added
        } else {
            val drop = phrases.map(::followKey).toHashSet()
            val gone = have.filter { followKey(it) in drop }
            if (gone.isEmpty()) null else have.filter { followKey(it) !in drop }.joinToString(",") to gone
        }
    }

    /**
     * The first followed phrase ([follows], display names) a story names as whole words in its title or summary,
     * accents, case and punctuation ignored ("Barça" matches "barca"; "AI" doesn't match "said"); null for none.
     */
    fun followMatch(h: Headline, follows: List<String>): String? {
        if (follows.isEmpty()) return null
        val text = " " + storyKey(h.title + " " + (h.summary ?: "")) + " "
        return follows.firstOrNull { f -> followKey(f).let { k -> k.isNotEmpty() && text.contains(" $k ") } }
    }

    /** "Follow a word or name…" with none; "Following 2 · stories naming them lead News, the ticker and the brief · tap one to stop". */
    fun followsLine(follows: List<NewsFollowChoice>): String = when {
        follows.isEmpty() -> "Follow a team, a person, a company or a word: stories naming it lead News, the ticker and the brief, whatever their topic"
        follows.size >= MAX_FOLLOWS -> "Following $MAX_FOLLOWS, the most · tap one to stop following it"
        else -> "Following ${follows.size} · stories naming them lead News, the ticker and the brief · tap one to stop"
    }

    /** A source's key: its name as [storyKey] makes it ("BBC  Sport" and "bbc sport" are one source). */
    fun sourceKey(name: String): String = storyKey(name)

    /** The stored off list as display names (blank and repeated ones dropped). */
    fun sourcesOff(stored: String?): List<String> {
        val seen = HashSet<String>()
        return stored?.split(',')?.map { clean(it).take(MAX_SOURCE_NAME) }?.filter { it.isNotEmpty() && seen.add(sourceKey(it)) } ?: emptyList()
    }

    /** The off list's keys, for filtering. */
    fun sourcesOffKeys(stored: String?): Set<String> = sourcesOff(stored).map(::sourceKey).toHashSet()

    /**
     * [stored] with [name] switched [on] (taken out of the list) or off (added at the end, the oldest dropped past
     * [MAX_SOURCES_OFF]); null when nothing changes or the name is empty.
     */
    fun withSource(stored: String?, name: String, on: Boolean): String? {
        val n = clean(name.replace(',', ' ')).take(MAX_SOURCE_NAME).takeIf { sourceKey(it).isNotEmpty() } ?: return null
        val have = sourcesOff(stored)
        val key = sourceKey(n)
        val there = have.any { sourceKey(it) == key }
        return when {
            on && there -> have.filter { sourceKey(it) != key }.joinToString(",")
            !on && !there -> (have + n).takeLast(MAX_SOURCES_OFF).joinToString(",")
            else -> null
        }
    }

    /**
     * The source switches: each source with stories in the chosen topics over the last two days (most first, then by
     * name; a story counted once however many topics carry it), then every source switched off that has none now.
     */
    fun sources(all: List<Headline>, chosen: List<String>, nowMs: Long, off: List<String>): List<NewsSourceChoice> {
        val offKeys = off.map(::sourceKey).toHashSet()
        val counts = LinkedHashMap<String, Pair<String, HashSet<String>>>()
        for (h in all) {
            if (h.topic !in chosen || h.publishedAtMs > nowMs + 10 * 60_000L || nowMs - h.publishedAtMs > MAX_AGE_IN_PLACE_MS) continue
            val k = sourceKey(h.source)
            if (k.isEmpty()) continue
            counts.getOrPut(k) { h.source to HashSet() }.second.add(linkKey(h))
        }
        val seen = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Pair<String, HashSet<String>>>> { it.value.second.size }.thenBy { it.value.first.lowercase() })
            .map { (k, v) -> NewsSourceChoice(k, v.first, v.second.size, k !in offKeys) }
        val quietOff = off.filter { sourceKey(it) !in counts }.map { NewsSourceChoice(sourceKey(it), it, 0, false) }
        return seen + quietOff
    }

    /** "12 sources · 2 off · tap one to leave its stories out of News and the brief" / "… · all on · …"; null with none. */
    fun sourceSwitchesLine(sources: List<NewsSourceChoice>): String? {
        if (sources.isEmpty()) return null
        val off = sources.count { !it.on }
        val n = sources.size
        val down = sources.count { it.on && it.down }
        return "$n source" + (if (n == 1) "" else "s") + " · " + (if (off == 0) "all on" else "$off off") +
            (if (down > 0) " · $down not coming through" else "") +
            " · tap one to leave its stories out of News and the brief"
    }

    /** Leaves out the stories from switched-off sources ([offKeys]: [sourceKey]s). */
    fun fromSourcesOn(all: List<Headline>, offKeys: Set<String>): List<Headline> =
        if (offKeys.isEmpty()) all else all.filter { sourceKey(it.source) !in offKeys }

    /**
     * Full news hub, slice 1: a story's key in the read list — 12 hex characters of a 64-bit FNV-1a hash of its link
     * (query and fragment dropped, lower-cased) or, without one, its title's [storyKey]. Short, so 400 fit in a field,
     * and never the link itself.
     */
    fun readKey(url: String?, title: String): String {
        val basis = url?.substringBefore('?')?.substringBefore('#')?.lowercase() ?: ("t:" + storyKey(title))
        var h = -0x340d631b7bdddcdbL // FNV-1a offset basis
        for (b in basis.encodeToByteArray()) { h = h xor (b.toLong() and 0xff); h *= 0x100000001b3L }
        return (h ushr 16).toString(16).padStart(12, '0').takeLast(12)
    }

    /** The stored read list as keys (anything malformed dropped). */
    fun readKeys(stored: String?): List<String> =
        stored?.split(',')?.map { it.trim() }?.filter { k -> k.length == 12 && k.all { it in '0'..'9' || it in 'a'..'f' } } ?: emptyList()

    /** [stored] with [keys] added at the end (each once), the oldest dropped past [MAX_READ]; null when nothing is new. */
    fun withRead(stored: String?, keys: List<String>): String? {
        val have = readKeys(stored)
        val set = have.toHashSet()
        val added = keys.filter { set.add(it) }
        if (added.isEmpty()) return null
        return (have + added).takeLast(MAX_READ).joinToString(",")
    }

    /** [stored] with [keys] taken out (Undo after Mark all read); null when none of them was there. */
    fun withoutRead(stored: String?, keys: Collection<String>): String? {
        val have = readKeys(stored)
        val drop = keys.toHashSet()
        val kept = have.filter { it !in drop }
        return if (kept.size == have.size) null else kept.joinToString(",")
    }

    /** "12 new · 6 read" / "12 new" / "All read · new stories get a dot"; null with no stories. */
    fun unreadLine(unread: Int, total: Int): String? = when {
        total == 0 -> null
        unread == 0 -> "All read · new stories get a dot"
        unread == total -> "$unread new"
        else -> "$unread new · ${total - unread} read"
    }

    /**
     * Quality gate slice 43: what a story's detail says when its feed sent no summary. With a link it points at Read
     * full story under it; without one (some feeds give none) it never sends him to a button that isn't there.
     */
    fun noSummary(source: String, hasLink: Boolean): String =
        if (hasLink) "No summary from $source. Read the full story for the details."
        else "$source sent the headline only, with no summary or link."

    /** What Save for the train says for a story without a link (some feeds give none). */
    const val NO_LINK_TO_SAVE = "This story has no link to save for the train"

    /** Spanish-language sources, named under the switch. */
    const val SPANISH_SOURCES = "Mundo Deportivo, Sport and Spanish Google News"

    /** "Off · English only" / "On · Mundo Deportivo, Sport and Spanish Google News". */
    fun spanishLine(on: Boolean): String = if (on) "On · $SPANISH_SOURCES" else "Off · English only"

    /** What the Topics caption says on both apps: every source, the Spanish ones only while they're on. */
    fun sourcesCaption(spanish: Boolean): String =
        "Shown here and in the morning brief. Barça: Google News, BBC Sport, Barca Universal and Football España" +
            (if (spanish) ", with Mundo Deportivo and Sport" else "") +
            " · AI: The Verge, TechCrunch, MIT Technology Review and OpenAI · Tech news: Hacker News (200+ points) · " +
            "Spain football: Google News and Football España · the rest: BBC News. Refreshed every hour by your server; " +
            "nothing about you is sent."

    /** English only unless Spanish sources are on: a story marked with another language is left out. */
    fun inLanguage(all: List<Headline>, spanish: Boolean): List<Headline> =
        if (spanish) all.filter { it.lang == null || it.lang == "es" } else all.filter { it.lang == null }

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
    fun place(
        all: List<Headline>,
        topics: List<NewsTopicChoice>,
        nowMs: Long,
        matchday: NewsMatchday? = null,
        spanish: Boolean = false,
        read: Set<String> = emptySet(),
        queuedLinks: Set<String> = emptySet(),
        sourcesOff: List<String> = emptyList(),
        follows: List<String> = emptyList(),
        down: List<NewsFeedDown> = emptyList(),
    ): NewsPlace {
        val chosen = topics.filter { it.chosen }.map { it.id }
        val offKeys = sourcesOff.map(::sourceKey).toHashSet()
        val downShown = downShown(down, chosen, offKeys)
        val sources = withDown(sources(all, chosen, nowMs, sourcesOff), downShown)
        val order = LEAD.filter { it in chosen } + chosen.filter { it !in LEAD }
        val seenLinks = HashSet<String>()
        val seenTitles = HashSet<String>()
        val freshAll = all.filter { it.publishedAtMs <= nowMs + 10 * 60_000L && nowMs - it.publishedAtMs <= MAX_AGE_IN_PLACE_MS }
        val fresh = fromSourcesOn(freshAll, offKeys)
        fun item(h: Headline, followed: String?) = NewsItem(
            h.id, h.title, h.url, h.source, h.topic,
            "${h.source} · ${age(h.publishedAtMs, nowMs)}" + (followed?.let { " · $it" } ?: ""), h.summary, h.publishedAtMs, h.imageKey,
            read = readKey(h.url, h.title) in read,
            queued = h.url != null && QueueRules.key(h.url) in queuedLinks,
            followed = followed,
        )
        // Full news hub, slice 3: stories naming a followed word lead, from any topic the server mirrors (each once).
        val followCounts = LinkedHashMap<String, HashSet<String>>()
        follows.forEach { followCounts[followKey(it)] = HashSet() }
        val following = fresh.mapNotNull { h -> followMatch(h, follows)?.let { h to it } }
            .sortedWith(compareByDescending<Pair<Headline, String>> { it.first.publishedAtMs }.thenBy { it.first.id })
            .filter { (h, f) -> followCounts[followKey(f)]?.add(linkKey(h)); seenLinks.add(linkKey(h)) && seenTitles.add(storyKey(h.title)) }
            .take(MAX_IN_LANE)
            .map { (h, f) -> item(h, f) }
        val followLane = if (following.isEmpty()) null else NewsLane(FOLLOWING_LANE, "Following", sourcesLine(following), following)
        val lanes = listOfNotNull(followLane) + order.mapNotNull { topic ->
            val items = fresh.filter { it.topic == topic }
                .sortedWith(compareByDescending<Headline> { it.publishedAtMs }.thenBy { it.id })
                .filter { seenLinks.add(linkKey(it)) && seenTitles.add(storyKey(it.title)) }
                .take(MAX_IN_LANE)
                .map { h -> item(h, null) }
            if (items.isEmpty()) null
            else NewsLane(topic, NewsTopics.byId(topic)?.label ?: topic, sourcesLine(items), items)
        }
        val followChoices = follows.map { NewsFollowChoice(followKey(it), it, followCounts[followKey(it)]?.size ?: 0) }
        val shown = lanes.map { it.topicId }.toSet()
        val hiddenBySources = order.filter { t -> t !in shown && freshAll.any { it.topic == t } && fresh.none { it.topic == t } }.toSet()
        val empty = when {
            lanes.isNotEmpty() -> null
            chosen.isEmpty() && follows.isEmpty() -> "No topics chosen · pick some below"
            chosen.isEmpty() -> "Nothing naming what you follow in the last two days · pick some topics below"
            hiddenBySources.isNotEmpty() -> "Nothing from the sources you have on · turn some back on under Topics"
            else -> "No headlines in the last two days · they refresh every hour"
        }
        // With nothing at all to show, the empty line says it; otherwise each quiet topic gets its own line.
        val quiet = (if (lanes.isEmpty()) emptyList() else order.filter { it !in shown }.map { t ->
            val label = NewsTopics.byId(t)?.label ?: t
            if (t in hiddenBySources) "$label · its sources are off" else "$label · nothing in the last two days"
        }) + downLines(downShown, nowMs)
        return NewsPlace(lanes, topics, empty, matchday?.takeIf { NewsTopics.BARCA.id in chosen }, spanish, sources, quiet, followChoices)
    }

    /** A feed counts as not coming through after this many failed tries in a row (the server reads them hourly). */
    const val DOWN_AFTER_FAILS = 2

    /**
     * Full news hub, slice 5: the feeds worth a word — failed [DOWN_AFTER_FAILS]+ tries in a row, feeding a chosen
     * topic, from a source he has on — one per source (its topics in chip order, the earliest failure), longest down first.
     */
    fun downShown(down: List<NewsFeedDown>, chosen: List<String>, offKeys: Set<String>): List<NewsFeedDown> =
        down.filter { it.fails >= DOWN_AFTER_FAILS && it.topic in chosen && sourceKey(it.source).isNotEmpty() && sourceKey(it.source) !in offKeys }
            .groupBy { sourceKey(it.source) }
            .map { (_, feeds) ->
                val topicsHere = feeds.map { it.topic }.distinct().sortedBy { chosen.indexOf(it) }
                NewsFeedDown(feeds.first().source.take(MAX_SOURCE_NAME), topicsHere.joinToString(","), feeds.minOf { it.sinceMs }, feeds.maxOf { it.fails })
            }
            .sortedWith(compareBy<NewsFeedDown> { it.sinceMs }.thenBy { it.source.lowercase() })

    /**
     * The source switches with the ones not coming through marked. A down feed whose name carries no stories (an
     * aggregator like Google News, whose stories carry their publishers' names) gets no switch: its line says it.
     */
    fun withDown(sources: List<NewsSourceChoice>, down: List<NewsFeedDown>): List<NewsSourceChoice> {
        if (down.isEmpty()) return sources
        val keys = down.map { sourceKey(it.source) }.toHashSet()
        return sources.map { if (it.on && it.key in keys) it.copy(down = true) else it }
    }

    /** "Barca Universal · Barça · not coming through for 5 h · Navilife keeps trying", one line per source. */
    fun downLines(down: List<NewsFeedDown>, nowMs: Long): List<String> = down.map { d ->
        val topicsLabel = d.topic.split(',').mapNotNull { NewsTopics.byId(it)?.label }.let(::joinAnd)
        listOf(d.source, topicsLabel.takeIf { it.isNotEmpty() }, "not coming through " + downFor(nowMs - d.sinceMs), "Navilife keeps trying")
            .filterNotNull().joinToString(" · ")
    }

    /** "for under an hour" · "for 5 h" · "for 2 days". */
    fun downFor(ms: Long): String {
        val h = ms.coerceAtLeast(0) / 3_600_000L
        return when {
            h < 1 -> "for under an hour"
            h < 48 -> "for $h h"
            else -> "for ${h / 24} days"
        }
    }

    private fun joinAnd(xs: List<String>): String = when (xs.size) {
        0 -> ""
        1 -> xs[0]
        else -> xs.dropLast(1).joinToString(", ") + " and " + xs.last()
    }

    /** What the fixtures feed adds to a title whose kick-off isn't set yet. */
    private const val TBC_SUFFIX = "(kick-off TBC)"

    /**
     * Today's Barça fixture for the top of the News place: the first fixture (not all-day) that starts today and
     * hasn't finished. Pass the events shown on my day, so a hidden fixture stays hidden. "Barça v Real Madrid ·
     * 21:00 · in 3 h" / "· in 25 min" / "· kicking off", "· on now" once it has started (the score once the server
     * has it: "Barça 2–1 Real Madrid · 67'", then "· full time"), "· today · kick-off TBC".
     */
    fun matchday(events: List<CalendarEvent>, nowMs: Long, cal: LocalCalendar): NewsMatchday? {
        val today = cal.epochDayOf(nowMs)
        val e = events.filter { it.isFixture && !it.allDay && it.endAtMs > nowMs && cal.epochDayOf(it.startAtMs) == today }
            .minWithOrNull(compareBy<CalendarEvent> { it.startAtMs }.thenBy { it.id }) ?: return null
        val tbc = e.title.trimEnd().endsWith(TBC_SUFFIX)
        val title = e.title.trimEnd().removeSuffix(TBC_SUFFIX).trim().ifEmpty { "Barça" }
        val live = !tbc && nowMs >= e.startAtMs
        // Barça match day, slice 2: once it has started, the score the server wrote ("Barça 2–1 Getafe · 67'").
        val score = if (live) BarcaMatchRules.score(e) else null
        val line = when {
            tbc -> "$title · today · kick-off TBC"
            score != null && score.live -> score.line + (score.detail?.let { " · $it" } ?: "")
            score != null -> "${score.line} · full time"
            live -> "$title · ${LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs))} · on now"
            else -> {
                val min = ((e.startAtMs - nowMs) / 60_000L).toInt()
                "$title · ${LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs))} · " + if (min <= 0) "kicking off" else "in ${TimelineRules.inLabel(min)}"
            }
        }
        return NewsMatchday(e.id, title, line, live && score?.live != false, e)
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
     * same article in two topic feeds, or the same title), at most [MAX_IN_BRIEF]. Full news hub, slice 3: stories naming
     * a word he [follows] come first, whatever their topic ("BBC Sport · Following Arsenal · 2 h ago"), at most three of
     * them, so the chosen topics still have their say. Full news hub, slice 4: stories he has [read] (their
     * [readKey]s, opened on either app or marked all read) come after every new one, so the brief says what's new and
     * only falls back on read ones when there aren't enough new.
     */
    fun forBrief(
        all: List<Headline>,
        chosen: List<String>,
        nowMs: Long,
        follows: List<String> = emptyList(),
        read: Set<String> = emptySet(),
    ): List<BriefHeadline> {
        val seenStories = HashSet<String>()
        val seenTitles = HashSet<String>()
        val fresh = all.filter { it.publishedAtMs <= nowMs + 10 * 60_000L && nowMs - it.publishedAtMs <= MAX_AGE_MS }
        val newest = compareByDescending<Headline> { it.publishedAtMs }.thenBy { chosen.indexOf(it.topic) }.thenBy { it.id }
        val followed = fresh.mapNotNull { h -> followMatch(h, follows)?.let { h to it } }
            .sortedWith(compareBy(newest) { it.first })
            .filter { (h, _) -> seenStories.add(linkKey(h)) && seenTitles.add(storyKey(h.title)) }
            .let { newFirst(it, read) { p -> p.first } }
            .take(MAX_FOLLOWED_IN_BRIEF)
            .map { (h, f) -> BriefHeadline(h.id, h.title, h.url, listOf(h.source, "Following $f", age(h.publishedAtMs, nowMs)).joinToString(" · ")) }
        val rest = fresh
            .filter { it.topic in chosen }
            .sortedWith(newest)
            .filter { seenStories.add(linkKey(it)) && seenTitles.add(storyKey(it.title)) }
            .let { newFirst(it, read) { h -> h } }
            .take(MAX_IN_BRIEF - followed.size)
            .map { h ->
                val topic = NewsTopics.byId(h.topic)?.takeIf { chosen.size > 1 }?.label
                BriefHeadline(h.id, h.title, h.url, listOfNotNull(h.source, topic, age(h.publishedAtMs, nowMs)).joinToString(" · "))
            }
            .toList()
        return followed + rest
    }

    /** [items] with the stories not in [read] first, each group keeping its order (a stable partition). */
    private fun <T> newFirst(items: List<T>, read: Set<String>, headline: (T) -> Headline): List<T> {
        if (read.isEmpty()) return items
        val (old, new) = items.partition { headline(it).let { h -> readKey(h.url, h.title) in read } }
        return new + old
    }

    /** Followed stories in the brief at most (the rest of [MAX_IN_BRIEF] are the chosen topics'). */
    const val MAX_FOLLOWED_IN_BRIEF = 3
}

/** Mirrored headlines (read-only: the server writes them) and the synced choice of topics. */
class News(private val replica: Replica) {
    /**
     * The headlines Meka reads: English only unless [spanish] is on ([NewsRules.inLanguage]), without the sources he
     * switched off ([NewsFields.SOURCES_OFF]) — what the brief and Ask read.
     */
    fun all(): List<Headline> = NewsRules.fromSourcesOn(inLanguage(), NewsRules.sourcesOffKeys(sourcesOffStored()))

    /** In his languages, every source included (the News place lists the off ones as switches). */
    private fun inLanguage(): List<Headline> = NewsRules.inLanguage(everything(), spanish())

    private fun sourcesOffStored(): String? = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(NewsFields.SOURCES_OFF)?.textOrNull

    /** The sources switched off, as their names. */
    fun sourcesOff(): List<String> = NewsRules.sourcesOff(sourcesOffStored())

    /** Switches one source on or off everywhere news shows (synced); false when it already was. */
    fun setSource(name: String, on: Boolean): Boolean {
        val next = NewsRules.withSource(sourcesOffStored(), name, on) ?: return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(NewsFields.SOURCES_OFF to next.fv()))
        return true
    }

    private fun followsStored(): String? = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(NewsFields.FOLLOWS)?.textOrNull

    /** The words and names followed ([NewsFields.FOLLOWS]), in the order added. */
    fun follows(): List<String> = NewsRules.follows(followsStored())

    /**
     * Follows (or with [on] false stops following) these phrases everywhere news shows (synced); what changed, empty
     * when nothing did (already followed, too short, or [NewsRules.MAX_FOLLOWS] reached).
     */
    fun setFollows(phrases: List<String>, on: Boolean): List<String> {
        val (next, changed) = NewsRules.withFollows(followsStored(), phrases, on) ?: return emptyList()
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(NewsFields.FOLLOWS to next.fv()))
        return changed
    }

    /** Every mirrored headline, whatever its language. */
    fun everything(): List<Headline> = replica.entities(EntityTypes.HEADLINE).mapNotNull { Headline.from(it) }

    /** News → Spanish sources (synced, last switch wins); off unless turned on. */
    fun spanish(): Boolean = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(NewsFields.SPANISH)?.boolOrNull == true

    fun setSpanish(on: Boolean) {
        if (spanish() == on) return
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(NewsFields.SPANISH to on.fv()))
    }

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

    /**
     * The News place over the chosen topics, each story marked read or new and whether its link waits in the train
     * queue ([queued]: the queue's waiting items).
     */
    fun place(
        nowMs: Long,
        dayEvents: List<CalendarEvent> = emptyList(),
        cal: LocalCalendar = LocalCalendar.UTC,
        queued: List<QueueItem> = emptyList(),
        /** Full news hub, slice 5: the feeds the server last said it couldn't read. */
        down: List<NewsFeedDown> = emptyList(),
    ): NewsPlace = NewsRules.place(
        inLanguage(), choices(), nowMs, NewsRules.matchday(dayEvents, nowMs, cal), spanish(),
        read = readKeys().toHashSet(),
        queuedLinks = queued.filter { !it.done }.map { QueueRules.key(it.url) }.toHashSet(),
        sourcesOff = sourcesOff(),
        follows = follows(),
        down = down,
    )

    /** The read list ([NewsFields.READ]). */
    fun readKeys(): List<String> = NewsRules.readKeys(replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(NewsFields.READ)?.textOrNull)

    /** Marks these stories read (synced); false when they all were already. */
    fun markRead(keys: List<String>): Boolean {
        val next = NewsRules.withRead(replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(NewsFields.READ)?.textOrNull, keys) ?: return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(NewsFields.READ to next.fv()))
        return true
    }

    /** Marks these stories new again (Undo after Mark all read); false when none was read. */
    fun markUnread(keys: Collection<String>): Boolean {
        val next = NewsRules.withoutRead(replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(NewsFields.READ)?.textOrNull, keys) ?: return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(NewsFields.READ to next.fv()))
        return true
    }

    companion object {
        const val ENTITY_ID = "news"
    }
}
