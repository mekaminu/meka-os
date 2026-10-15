package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Train-time queue (V1, Meka approved 2026-10-10): "read later" links and podcasts shared to MEKA for the commute, and
 * on an office day's commute Today offers them ("For the train · 2 articles · 1 podcast"). One synced entity type
 * (ADR-008 addendum 2026-10-10): `queue_item`, one saved link, a random id.
 *
 * Slice 1 keeps the link, its title and its site. Slice 2 ([ReaderRules]) keeps an article's words on the Fold for
 * reading offline; the train's minutes ("15 min to the change", which waits on the trains item) come later.
 * Plain, non-AI rules; nothing is fetched and nothing leaves Meka's own synced data.
 */
object QueueFields {
    const val URL = "url"
    const val TITLE = "title"
    /** [QueueKind.key]: `article` · `podcast`. */
    const val KIND = "kind"
    /** The site the link is on, "bbc.co.uk". */
    const val SITE = "site"
    const val ADDED_AT = "addedAtMs"
    /** When Meka marked it read or listened to; 0 while it waits. */
    const val DONE_AT = "doneAtMs"
    const val DELETED = ActionableFields.DELETED
}

enum class QueueKind(val key: String, val label: String) {
    ARTICLE("article", "Article"),
    PODCAST("podcast", "Podcast"),
    ;

    companion object {
        fun of(key: String?): QueueKind = entries.firstOrNull { it.key == key } ?: ARTICLE
    }
}

/** One saved link as stored. */
data class QueueItem(
    val id: String,
    val url: String,
    val title: String,
    val kind: QueueKind,
    val site: String,
    val addedAtMs: Long,
    val doneAtMs: Long,
) {
    val done: Boolean get() = doneAtMs > 0
}

/** One saved link as the queue shows it: "Why trains run late" · "bbc.co.uk · Article". */
data class QueueRow(
    val id: String,
    val url: String,
    val title: String,
    /** "bbc.co.uk · Article" (or "· Podcast"). */
    val meta: String,
    val kind: String,
    val done: Boolean,
    val spoken: String,
)

data class QueueView(
    /** Waiting to be read or listened to, newest first. */
    val waiting: List<QueueRow>,
    /** Marked done in the last [QueueRules.DONE_KEPT_DAYS] days, newest first, at most [QueueRules.DONE_SHOWN]. */
    val done: List<QueueRow>,
    /** "2 articles · 1 podcast for the train", or how to start. */
    val summary: String,
    val articles: Int,
    val podcasts: Int,
) {
    companion object {
        val EMPTY = QueueView(emptyList(), emptyList(), QueueRules.EMPTY_SUMMARY, 0, 0)
    }
}

/** Today's line on an office day's commute: "For the train · 2 articles · 1 podcast". */
data class QueueLine(val text: String, val spoken: String)

/** What saving did: the line to show and whether it was turned away (lit in the accent). */
data class QueueAdded(val line: String, val refused: Boolean, val id: String? = null)

object QueueRules {
    const val MAX_TITLE = 120
    const val MAX_URL = 2_000
    const val DONE_KEPT_DAYS = 30
    const val DONE_SHOWN = 5
    private const val DAY_MS = 86_400_000L

    const val EMPTY_SUMMARY = "Nothing saved yet. Share a link to Navilife and choose For the train."
    const val HINT = "Paste a link to save for the train…"
    const val NO_LINK = "That has no link in it. Paste or share a web link (https://…)."
    const val ALREADY = "Already in your train queue"
    const val ACTION = "For the train"
    const val NOTE = "Saved on both apps. On Wi-Fi the Fold keeps each article's words to read on the train without a signal; podcasts open in their own app, which keeps its downloads."

    /** Hosts whose links are podcasts (an episode page, or a show). */
    private val PODCAST_HOSTS = listOf(
        "podcasts.apple.com", "overcast.fm", "pca.st", "pocketcasts.com", "play.pocketcasts.com", "castbox.fm",
        "podcasts.google.com", "podbean.com", "podcastaddict.com", "player.fm", "castro.fm", "podcasts.audible.co.uk",
    )
    /** Query parameters that only track where a link came from; dropped when comparing two links. */
    private val TRACKING = listOf("utm_", "fbclid", "gclid", "si", "igshid", "mc_cid", "mc_eid", "ref_src")

    private val LINK = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    /** The first web link in [text], without trailing punctuation a sentence put after it; null when there is none. */
    fun link(text: String?): String? {
        val m = LINK.find(text ?: return null) ?: return null
        var url = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '»', '…')
        // A ")" that closes a "(" inside the link (Wikipedia) stays.
        if (m.value.length > url.length && m.value[url.length] == ')' && url.count { it == '(' } > url.count { it == ')' }) url += ")"
        if (url.length > MAX_URL) return null
        return url.takeIf { host(it).contains('.') }
    }

    /** The site a link is on, lower case, without "www." or "m.": "bbc.co.uk". */
    fun host(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.substringAfterLast('@').substringBefore(':').lowercase()
            .removePrefix("www.").removePrefix("m.")
    }

    /** The same link however it was shared: scheme and host in lower case, no fragment, no tracking, no trailing "/". */
    fun key(url: String): String {
        val noFragment = url.substringBefore('#')
        val path = noFragment.substringAfter("://", noFragment).dropWhile { it != '/' && it != '?' }
        val query = path.substringAfter('?', "")
        val kept = query.split('&').filter { p ->
            val name = p.substringBefore('=').lowercase()
            p.isNotEmpty() && TRACKING.none { t -> if (t.endsWith("_")) name.startsWith(t) else name == t }
        }
        val bare = path.substringBefore('?').trimEnd('/')
        return host(url) + bare + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
    }

    /** A podcast when the link is on a podcast app's site, a Spotify or BBC Sounds episode, or the title says "podcast". */
    fun kind(url: String, title: String): QueueKind {
        val host = host(url)
        val path = url.substringAfter(host, "").lowercase()
        val podcast = PODCAST_HOSTS.any { host == it || host.endsWith(".$it") } ||
            (host == "open.spotify.com" && (path.startsWith("/episode") || path.startsWith("/show"))) ||
            (host == "bbc.co.uk" && path.startsWith("/sounds")) ||
            Regex("""\bpodcast\b""", RegexOption.IGNORE_CASE).containsMatchIn(title)
        return if (podcast) QueueKind.PODCAST else QueueKind.ARTICLE
    }

    /**
     * The title to keep: the share's subject when it has one, else the shared words around the link (first line,
     * separators trimmed), else the site. Cut at a word past [MAX_TITLE].
     */
    fun title(text: String?, subject: String?, url: String): String {
        fun tidy(s: String?): String? = s?.replace(url, " ")?.let { LINK.replace(it, " ") }
            ?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            ?.replace(Regex("\\s+"), " ")?.trim(' ', '-', '–', '—', '|', ':', '·', '•')?.trim()
            ?.takeIf { it.length >= 2 }
        var t = tidy(subject) ?: tidy(text) ?: host(url)
        if (t.length > MAX_TITLE) t = t.take(MAX_TITLE).substringBeforeLast(' ', t.take(MAX_TITLE)).trimEnd(',', ';', ':', ' ') + "…"
        return t
    }

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    fun items(entities: Map<String, Map<String, FieldValue>>): List<QueueItem> = entities.mapNotNull { (id, s) ->
        if (s.f(QueueFields.DELETED).boolOrNull == true) return@mapNotNull null
        val url = s.f(QueueFields.URL).textOrNull?.trim().orEmpty()
        if (url.isEmpty()) return@mapNotNull null
        QueueItem(
            id = id,
            url = url,
            title = s.f(QueueFields.TITLE).textOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: host(url),
            kind = QueueKind.of(s.f(QueueFields.KIND).textOrNull),
            site = s.f(QueueFields.SITE).textOrNull?.takeIf { it.isNotBlank() } ?: host(url),
            addedAtMs = s.f(QueueFields.ADDED_AT).longOrNull ?: 0L,
            doneAtMs = s.f(QueueFields.DONE_AT).longOrNull ?: 0L,
        )
    }.sortedWith(compareByDescending<QueueItem> { it.addedAtMs }.thenBy { it.id })

    fun row(i: QueueItem) = QueueRow(
        i.id, i.url, i.title, "${i.site} · ${i.kind.label}", i.kind.key, i.done,
        "${i.kind.label} from ${i.site}: ${i.title}" + if (i.done) ", done" else "",
    )

    /** "2 articles · 1 podcast"; null when nothing waits. */
    fun countLine(articles: Int, podcasts: Int): String? = listOfNotNull(
        articles.takeIf { it > 0 }?.let { ShutdownRules.count(it, "article") },
        podcasts.takeIf { it > 0 }?.let { ShutdownRules.count(it, "podcast") },
    ).joinToString(" · ").ifEmpty { null }

    fun view(items: List<QueueItem>, nowMs: Long): QueueView {
        val waiting = items.filter { !it.done }
        val done = items.filter { it.done && nowMs - it.doneAtMs < DONE_KEPT_DAYS * DAY_MS }
            .sortedByDescending { it.doneAtMs }.take(DONE_SHOWN)
        val articles = waiting.count { it.kind == QueueKind.ARTICLE }
        val podcasts = waiting.count { it.kind == QueueKind.PODCAST }
        val summary = countLine(articles, podcasts)?.let { "$it for the train" }
            ?: if (done.isNotEmpty()) "All caught up. Share a link to Navilife to save the next one." else EMPTY_SUMMARY
        return QueueView(waiting.map(::row), done.map(::row), summary, articles, podcasts)
    }

    /**
     * Today's line while Meka is on an office day's commute ([RouteRules.commute]: from 06:00 until work starts, and
     * around the end of work) and something waits: "For the train · 2 articles · 1 podcast". Null otherwise.
     */
    fun trainLine(items: List<QueueItem>, office: OfficeWindow?, nowMs: Long, cal: LocalCalendar): QueueLine? {
        RouteRules.commute(office, nowMs, cal) ?: return null
        val waiting = items.filter { !it.done }
        val count = countLine(waiting.count { it.kind == QueueKind.ARTICLE }, waiting.count { it.kind == QueueKind.PODCAST }) ?: return null
        return QueueLine("$ACTION · $count", "$ACTION: ${count.replace(" · ", " and ")} saved")
    }

    /**
     * What saving shared or pasted words would write. No link: refused, nothing written. The same link already
     * waiting: said, nothing written. The same link marked done: back in the queue. Otherwise a new item.
     */
    fun addPlan(
        text: String?,
        subject: String?,
        items: List<QueueItem>,
        nowMs: Long,
        newId: () -> String,
    ): Pair<QueueAdded, Pair<String, Map<String, FieldValue>>?> {
        val url = link(text) ?: link(subject) ?: return QueueAdded(NO_LINK, true) to null
        val k = key(url)
        val same = items.firstOrNull { key(it.url) == k }
        if (same != null && !same.done) return QueueAdded(ALREADY, false, same.id) to null
        if (same != null) {
            return QueueAdded("Back in your train queue: ${same.title}", false, same.id) to
                (same.id to mapOf(QueueFields.DONE_AT to 0L.fv(), QueueFields.ADDED_AT to nowMs.fv()))
        }
        val title = title(text, subject, url)
        val id = newId()
        return QueueAdded("Saved for the train: $title", false, id) to (id to mapOf(
            QueueFields.URL to url.fv(),
            QueueFields.TITLE to title.fv(),
            QueueFields.KIND to kind(url, title).key.fv(),
            QueueFields.SITE to host(url).fv(),
            QueueFields.ADDED_AT to nowMs.fv(),
            QueueFields.DONE_AT to 0L.fv(),
        ))
    }
}

/** The train queue on a replica. */
class TrainQueue(private val replica: Replica, private val newId: () -> String, private val nowMs: () -> Long) {
    fun items(): List<QueueItem> = QueueRules.items(replica.entities(EntityTypes.QUEUE_ITEM).associate { it.ref.entityId to it.fields })

    fun view(): QueueView = QueueRules.view(items(), nowMs())

    /** Saves the link in shared or pasted words (the share's [subject] gives the title when it has one). */
    fun add(text: String?, subject: String?): QueueAdded {
        val (added, write) = QueueRules.addPlan(text, subject, items(), nowMs(), newId)
        write?.let { (id, fields) -> replica.commitLocal(EntityTypes.QUEUE_ITEM, id, fields) }
        return added
    }

    /** Marks it read or listened to ([done] true), or puts it back in the queue. */
    fun setDone(id: String, done: Boolean): Boolean {
        val item = items().firstOrNull { it.id == id } ?: return false
        if (item.done == done) return false
        replica.commitLocal(EntityTypes.QUEUE_ITEM, id, mapOf(QueueFields.DONE_AT to (if (done) nowMs() else 0L).fv()))
        return true
    }

    /** Takes it off the queue for good (both apps). */
    fun remove(id: String): Boolean {
        replica.entity(EntityTypes.QUEUE_ITEM, id)?.takeIf { it[QueueFields.DELETED].boolOrNull != true } ?: return false
        replica.commitLocal(EntityTypes.QUEUE_ITEM, id, mapOf(QueueFields.DELETED to true.fv()))
        return true
    }
}

/**
 * Ask and Talk know the train queue (train-time queue, slice 3): "what's in my train queue?" is answered from it, and
 * "I've read the BBC one" becomes a card that marks that link done on Meka's tap. Non-AI, pure. A saved link's title
 * comes from the page or app it was shared from (words other people wrote), so queue lines mark a question untrusted,
 * like a calendar invitation's title (ADR-006 §2).
 */
object QueueAskRules {
    /** How many waiting links go with a question (newest first), under the queue's line. */
    const val ASK_ITEMS = 8
    /** How many links marked done lately go with them. */
    const val ASK_DONE = 3

    const val GONE = "That link isn't waiting in your train queue any more"

    private fun tidy(s: String): String = s.replace(Regex("""\s+"""), " ").trim()

    /**
     * The queue as MEKA describes it to the model (Ask's `queue` lines): first the count ("Train queue · 2 articles ·
     * 1 podcast waiting · the newest 8 below", or "Train queue · nothing waiting"), then each waiting link ("Waiting ·
     * Article · bbc.co.uk · Why trains run late · saved Fri 9 Oct") and the few marked done lately ("Done · … · done
     * Thu 8 Oct"). Nothing at all while nothing has been saved (or only long-done links remain).
     */
    fun askLines(items: List<QueueItem>, nowMs: Long, cal: LocalCalendar): List<String> {
        val view = QueueRules.view(items, nowMs)
        val waiting = items.filter { !it.done }
        val doneIds = view.done.map { it.id }.toSet()
        val done = items.filter { it.id in doneIds }.sortedByDescending { it.doneAtMs }.take(ASK_DONE)
        if (waiting.isEmpty() && done.isEmpty()) return emptyList()
        val head = QueueRules.countLine(view.articles, view.podcasts)?.let { c ->
            "Train queue · $c waiting" + if (waiting.size > ASK_ITEMS) " · the newest $ASK_ITEMS below" else ""
        } ?: "Train queue · nothing waiting"
        fun day(ms: Long) = CivilDate.shortLabel(cal.epochDayOf(ms))
        return listOf(head) +
            waiting.take(ASK_ITEMS).map { "Waiting · ${it.kind.label} · ${it.site} · ${tidy(it.title)} · saved ${day(it.addedAtMs)}" } +
            done.map { "Done · ${it.kind.label} · ${it.site} · ${tidy(it.title)} · done ${day(it.doneAtMs)}" }
    }

    /**
     * The one waiting link a model's `queue_done` names: the same title, else the one link whose title holds the words
     * (or is held by them), else the one link from that site ("the BBC one" → bbc.co.uk). Null when none or several fit.
     */
    fun find(items: List<QueueItem>, title: String?): QueueItem? {
        val t = title?.let(::tidy)?.lowercase()?.trim('“', '”', '"', '\'', ' ')?.takeIf { it.length >= 2 } ?: return null
        val waiting = items.filter { !it.done }
        waiting.filter { tidy(it.title).lowercase() == t }.singleOrNull()?.let { return it }
        waiting.filter { val own = tidy(it.title).lowercase(); own.contains(t) || (own.length >= 4 && t.contains(own)) }
            .singleOrNull()?.let { return it }
        val words = t.split(Regex("""[^a-z0-9.]+""")).filter { it.length >= 3 }
        val squashed = t.filter { it.isLetterOrDigit() }
        return waiting.filter { i ->
            val site = i.site.lowercase()
            val name = site.substringBefore('.')
            // "bbc.co.uk", "the BBC one", "the Guardian article" (theguardian.com).
            t.contains(site) || (name.length >= 3 && name in words) || (name.length >= 6 && squashed.contains(name))
        }.singleOrNull()
    }

    /** The card's words: "Done · “Why trains run late” · off the train queue". */
    fun cardLine(title: String): String = "Done · “$title” · off the train queue"

    /** The undo bar's line: "Marked “Why trains run late” done · off the train queue". */
    fun doneLine(title: String): String = "Marked “$title” done · off the train queue"
}
