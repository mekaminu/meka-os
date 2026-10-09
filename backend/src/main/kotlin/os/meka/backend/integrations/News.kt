package os.meka.backend.integrations

import org.w3c.dom.Element
import os.meka.core.domain.NewsRules
import os.meka.core.domain.NewsTopics
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * One headline as a feed reports it. [id] is unique within the feed (its link, without tracking parameters).
 * [source] names the publisher when it differs per item (an aggregator like Google News; null = the provider's own
 * name); [summary] is the feed's own description as plain text, when it has a useful one.
 */
data class RemoteHeadline(
    val id: String, val title: String, val url: String, val publishedMs: Long,
    val source: String? = null, val summary: String? = null,
    /** The story's picture as the feed names it (https only; see [NewsImages]); null when it names none. */
    val imageUrl: String? = null,
    /** The story's language when it isn't English ("es"; build plan, Fold review 2026-10-09 07:26 item 1); null = English. */
    val lang: String? = null,
)

/**
 * A public news source (ADR-009 NewsProvider): read-only, no sign-in, nothing about the household is sent. The
 * server mirrors the newest few items of each topic; which topics the brief shows is chosen in the apps.
 */
interface NewsProvider {
    val id: String
    /** Shown with every headline ("BBC News"). */
    val source: String
    /** [NewsTopics] ids this provider can fetch. */
    val topics: List<String>
    /** Newest first. */
    fun headlines(topic: String): List<RemoteHeadline>
    /** Headlines mirrored per topic (fixed slots, so the number of entities never grows). */
    val slots: Int get() = 4
}

/**
 * BBC News public RSS feeds. RSS is a stable, documented format; a change shows up as a sync error on this account
 * (never as wrong data) and the provider can be swapped without touching anything else.
 */
class BbcNewsRss internal constructor(private val fetch: (String) -> String) : NewsProvider {
    constructor() : this(::httpGet)

    override val id = "news"
    override val source = "BBC News"

    private val feeds = linkedMapOf(
        NewsTopics.TOP.id to "https://feeds.bbci.co.uk/news/rss.xml",
        NewsTopics.UK.id to "https://feeds.bbci.co.uk/news/uk/rss.xml",
        NewsTopics.WORLD.id to "https://feeds.bbci.co.uk/news/world/rss.xml",
        NewsTopics.POLITICS.id to "https://feeds.bbci.co.uk/news/politics/rss.xml",
        NewsTopics.BUSINESS.id to "https://feeds.bbci.co.uk/news/business/rss.xml",
        NewsTopics.TECHNOLOGY.id to "https://feeds.bbci.co.uk/news/technology/rss.xml",
        NewsTopics.SCIENCE.id to "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml",
        NewsTopics.HEALTH.id to "https://feeds.bbci.co.uk/news/health/rss.xml",
        NewsTopics.FOOTBALL.id to "https://feeds.bbci.co.uk/sport/football/rss.xml",
    )

    override val topics: List<String> get() = feeds.keys.toList()

    override fun headlines(topic: String): List<RemoteHeadline> =
        Rss.parse(fetch(feeds[topic] ?: error("unknown topic"))).sortedByDescending { it.publishedMs }

    companion object {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
        fun httpGet(url: String): String {
            val resp = client.send(
                HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/rss+xml, application/xml;q=0.9").header("User-Agent", "MEKA-OS/1 (personal feed reader)")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            check(resp.statusCode() == 200) { "feed HTTP ${resp.statusCode()}" }
            return resp.body()
        }
    }
}

/**
 * One public feed for one topic. [aggregator]: items name their own publisher (Google News). [summaries]: false when
 * the feed's descriptions aren't about the story (Hacker News's are link lists and points). [lang]: the feed's
 * language, written on every item that isn't English so the apps can leave it out (English only by default, Fold
 * review 2026-10-09 07:26). [match]: a wider feed (all of La Liga) keeps only the items whose title matches.
 */
data class NewsFeed(
    val topic: String, val source: String, val url: String, val aggregator: Boolean = false, val summaries: Boolean = true,
    val lang: String = "en", val match: Regex? = null,
)

/**
 * AI, tech and Barça news (build plan "News ticker", slice 1): several public RSS/Atom feeds per topic, read like the
 * BBC's (plain GET, no key, nothing about Meka sent, untrusted, https links only). One account for all of them, so the
 * Calendars screen gains one row. A feed that fails or has nothing usable is skipped (verified at every refresh, so a
 * feed that changes or goes away just stops contributing); a topic only fails when none of its feeds could be read.
 * The topic's items are merged newest first, the same story from several feeds kept once (normalised title).
 */
class PublicNewsFeeds internal constructor(
    private val feeds: List<NewsFeed>,
    private val fetch: (String) -> String,
) : NewsProvider {
    constructor() : this(DEFAULT_FEEDS, BbcNewsRss::httpGet)

    override val id = "news_more"
    override val source = "AI, tech and Barça news"
    override val topics: List<String> get() = feeds.map { it.topic }.distinct()
    override val slots = 10

    override fun headlines(topic: String): List<RemoteHeadline> {
        val mine = feeds.filter { it.topic == topic }
        require(mine.isNotEmpty()) { "unknown topic" }
        var failed = 0
        val all = mine.flatMap { f ->
            runCatching {
                Rss.parseAny(fetch(f.url)).map { tidy(it, f) }.filter { h -> f.match?.containsMatchIn(h.title) ?: true }
            }.getOrElse { failed++; emptyList() }
        }
        if (failed == mine.size) error("no feed for $topic could be read")
        val seen = HashSet<String>()
        return all.sortedByDescending { it.publishedMs }.filter { seen.add(NewsRules.storyKey(it.title)) && seen.add(it.id) }
    }

    /** The publisher on every item; Google News's " - Mundo Deportivo" title suffix moved into the source. */
    private fun tidy(h: RemoteHeadline, f: NewsFeed): RemoteHeadline {
        val lang = f.lang.lowercase().takeIf { it != "en" }
        if (!f.aggregator) return h.copy(source = f.source, summary = h.summary.takeIf { f.summaries }, lang = lang)
        val publisher = h.source?.takeIf { it.isNotBlank() }
        val title = publisher?.let { p -> h.title.removeSuffix(" - $p").trim().ifEmpty { h.title } } ?: h.title
        // Google News descriptions are only a list of links: no summary from an aggregator.
        return h.copy(title = title, source = publisher ?: f.source, summary = null, lang = lang)
    }

    companion object {
        private const val GOOGLE = "https://news.google.com/rss/search?"
        /** Barça in a wider feed's titles ("Barcelona", "Barça", "Barca"; whole words). */
        val BARCA_TITLE = Regex("(?i)\\b(barcelona|bar[cç]a)\\b")
        val DEFAULT_FEEDS = listOf(
            // English first (Fold review 2026-10-09 07:26): Google News en-GB, BBC Sport's European football and
            // Football España (Barça stories only), Barca Universal. Verified at every refresh: one that fails is skipped.
            NewsFeed(NewsTopics.BARCA.id, "Google News", GOOGLE + "q=%22FC+Barcelona%22&hl=en-GB&gl=GB&ceid=GB:en", aggregator = true),
            NewsFeed(NewsTopics.BARCA.id, "BBC Sport", "https://feeds.bbci.co.uk/sport/football/european/rss.xml", match = BARCA_TITLE),
            NewsFeed(NewsTopics.BARCA.id, "Barca Universal", "https://barcauniversal.com/feed/"),
            NewsFeed(NewsTopics.BARCA.id, "Football España", "https://www.football-espana.net/feed", match = BARCA_TITLE),
            // Spanish sources: still mirrored, marked "es", shown only with News → Spanish sources on.
            NewsFeed(NewsTopics.BARCA.id, "Mundo Deportivo", "https://www.mundodeportivo.com/rss/futbol/fc-barcelona.xml", lang = "es"),
            NewsFeed(NewsTopics.BARCA.id, "Sport", "https://www.sport.es/es/rss/barca/rss.xml", lang = "es"),
            NewsFeed(NewsTopics.SPAIN.id, "Google News", GOOGLE + "q=La+Liga&hl=en-GB&gl=GB&ceid=GB:en", aggregator = true),
            NewsFeed(NewsTopics.SPAIN.id, "Google News", GOOGLE + "q=%22Spain+national+team%22+football&hl=en-GB&gl=GB&ceid=GB:en", aggregator = true),
            NewsFeed(NewsTopics.SPAIN.id, "Football España", "https://www.football-espana.net/feed"),
            NewsFeed(NewsTopics.SPAIN.id, "Google News", GOOGLE + "q=%22selecci%C3%B3n+espa%C3%B1ola+de+f%C3%BAtbol%22&hl=es&gl=ES&ceid=ES:es", aggregator = true, lang = "es"),
            NewsFeed(NewsTopics.AI.id, "The Verge", "https://www.theverge.com/rss/ai-artificial-intelligence/index.xml"),
            NewsFeed(NewsTopics.AI.id, "TechCrunch", "https://techcrunch.com/category/artificial-intelligence/feed/"),
            NewsFeed(NewsTopics.AI.id, "MIT Technology Review", "https://www.technologyreview.com/topic/artificial-intelligence/feed"),
            NewsFeed(NewsTopics.AI.id, "OpenAI", "https://openai.com/news/rss.xml"),
            // Hacker News front-page stories with 200+ points (hnrss.org applies the threshold).
            NewsFeed(NewsTopics.TECH.id, "Hacker News", "https://hnrss.org/frontpage?points=200", summaries = false),
        )
    }
}

/** RSS 2.0 items. Feeds are untrusted: no DTDs or external entities, only https links, dates must parse. */
internal object Rss {
    private const val MAX_BYTES = 2_000_000

    fun parse(xml: String): List<RemoteHeadline> {
        val doc = document(xml)
        val items = doc.getElementsByTagName("item")
        return (0 until items.length).mapNotNull { i ->
            val e = items.item(i) as Element
            val title = title(e) ?: return@mapNotNull null
            val link = e.text("link")?.trim()?.let(::canonical) ?: return@mapNotNull null
            val published = (e.text("pubDate")?.trim()?.let(::parseDate) ?: e.text("dc:date")?.trim()?.let(::parseIsoDate)) ?: return@mapNotNull null
            val source = e.text("source")?.let(::oneLine)?.takeIf { it.isNotEmpty() }
            val description = e.text("description")
            RemoteHeadline(
                link, title, link, published, source = source, summary = description?.let(::plainSummary),
                imageUrl = image(e, description, e.text("content:encoded")),
            )
        }.distinctBy { it.id }
    }

    private fun document(xml: String): org.w3c.dom.Document {
        require(xml.length <= MAX_BYTES) { "feed too large" }
        val f = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isXIncludeAware = false
            isExpandEntityReferences = false
            isNamespaceAware = false
        }
        val builder = f.newDocumentBuilder().apply {
            // Fail quietly (the sync records the error), instead of printing parser complaints to the log.
            setErrorHandler(object : org.xml.sax.ErrorHandler {
                override fun warning(e: org.xml.sax.SAXParseException) = Unit
                override fun error(e: org.xml.sax.SAXParseException) = throw e
                override fun fatalError(e: org.xml.sax.SAXParseException) = throw e
            })
        }
        return builder.parse(InputSource(StringReader(xml)))
    }

    /**
     * Atom 1.0 entries (The Verge and other Atom feeds): the alternate link, `published` (else `updated`) and the
     * summary (else content) as plain text. Same safety rules as RSS.
     */
    fun parseAtom(xml: String): List<RemoteHeadline> {
        val doc = document(xml)
        val entries = doc.getElementsByTagName("entry")
        return (0 until entries.length).mapNotNull { i ->
            val e = entries.item(i) as Element
            val title = title(e) ?: return@mapNotNull null
            val links = e.getElementsByTagName("link")
            val href = (0 until links.length).map { links.item(it) as Element }
                .sortedBy { if (it.getAttribute("rel").let { r -> r.isEmpty() || r == "alternate" }) 0 else 1 }
                .firstNotNullOfOrNull { it.getAttribute("href").takeIf(String::isNotBlank) }
            val link = href?.trim()?.let(::canonical) ?: return@mapNotNull null
            val published = (e.text("published") ?: e.text("updated"))?.trim()?.let(::parseIsoDate) ?: return@mapNotNull null
            val enclosure = (0 until links.length).map { links.item(it) as Element }
                .firstOrNull { it.getAttribute("rel") == "enclosure" && it.getAttribute("type").startsWith("image/") }?.getAttribute("href")
            RemoteHeadline(
                link, title, link, published, summary = (e.text("summary") ?: e.text("content"))?.let(::plainSummary),
                imageUrl = image(e, e.text("summary"), e.text("content")) ?: NewsImages.safeImageUrl(enclosure),
            )
        }.distinctBy { it.id }
    }

    /**
     * The story's picture: `media:thumbnail` or an image `media:content` (the one nearest 320 px wide, else the
     * largest), an image `enclosure`, else the first `<img src>` in the description or content. https only.
     */
    fun image(e: Element, vararg html: String?): String? {
        data class Candidate(val url: String, val width: Int)
        val media = listOf("media:thumbnail", "media:content").flatMap { tag ->
            val nodes = e.getElementsByTagName(tag)
            (0 until nodes.length).mapNotNull { i ->
                val m = nodes.item(i) as Element
                val type = m.getAttribute("type").lowercase()
                val medium = m.getAttribute("medium").lowercase()
                val url = m.getAttribute("url")
                val isImage = tag == "media:thumbnail" || medium == "image" || type.startsWith("image/") ||
                    (medium.isEmpty() && type.isEmpty() && Regex("(?i)\\.(jpe?g|png|gif)(\\?|$)").containsMatchIn(url))
                if (!isImage) return@mapNotNull null
                NewsImages.safeImageUrl(url)?.let { Candidate(it, m.getAttribute("width").toIntOrNull() ?: 0) }
            }
        }
        if (media.isNotEmpty()) {
            val wide = media.filter { it.width >= NewsImageMaker.MAX_WIDTH }.minByOrNull { it.width }
            return (wide ?: media.maxBy { it.width }).url
        }
        val enclosures = e.getElementsByTagName("enclosure")
        for (i in 0 until enclosures.length) {
            val enc = enclosures.item(i) as Element
            if (enc.getAttribute("type").lowercase().startsWith("image/")) NewsImages.safeImageUrl(enc.getAttribute("url"))?.let { return it }
        }
        for (h in html) {
            val src = h?.let { Regex("(?is)<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']").find(it) }?.groupValues?.get(1)
            NewsImages.safeImageUrl(src)?.let { return it }
        }
        return null
    }

    /** RSS or Atom, whichever the document is. */
    fun parseAny(xml: String): List<RemoteHeadline> = if (isAtom(xml)) parseAtom(xml) else parse(xml)

    private fun isAtom(xml: String): Boolean {
        val head = xml.take(4_000)
        val root = Regex("<([A-Za-z][\\w:.-]*)").findAll(head).map { it.groupValues[1] }.firstOrNull { !it.startsWith("?") && it != "!" }
        return root == "feed"
    }

    private const val MAX_SUMMARY = 400

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /**
     * The few HTML entities feeds leave in text that was HTML ("&amp;" in an Atom `type="html"` title). The result is
     * only ever shown as plain text, so nothing here can turn into markup.
     */
    fun unescape(s: String): String =
        s.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun title(e: Element): String? = e.text("title")?.let { oneLine(unescape(it)) }?.takeIf { it.isNotEmpty() }

    /**
     * The description as plain text: markup removed (feeds put HTML in it), entities the parser left decoded, at most
     * [MAX_SUMMARY] characters cut at a word. Null when nothing useful is left.
     */
    fun plainSummary(raw: String): String? {
        val text = oneLine(
            unescape(raw.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]*>"), " ")),
        )
        if (text.length < 20) return null
        if (text.length <= MAX_SUMMARY) return text
        val cut = text.take(MAX_SUMMARY)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > MAX_SUMMARY / 2 } ?: cut.length).trimEnd(',', ';', ':', ' ') + "…"
    }

    /**
     * https only; tracking parameters (utm_*, BBC's at_*, Google's oc, click ids) and fragments dropped, as they'd
     * make one article look like several. Other query parameters stay: some links need them (a video's `?v=`).
     */
    fun canonical(link: String): String? = runCatching {
        val u = URI(link)
        if (!u.scheme.equals("https", ignoreCase = true) || u.host.isNullOrEmpty() || u.rawUserInfo != null) return null
        val path = u.rawPath?.ifEmpty { "/" } ?: "/"
        val query = u.rawQuery?.split('&')?.filter { p ->
            val name = p.substringBefore('=').lowercase()
            p.isNotEmpty() && !name.startsWith("utm_") && !name.startsWith("at_") && name !in TRACKING
        }?.takeIf { it.isNotEmpty() }?.joinToString("&")
        val port = if (u.port == -1 || u.port == 443) "" else ":${u.port}"
        val out = "https://" + u.host.lowercase() + port + path + (query?.let { "?$it" } ?: "")
        URI(out).toASCIIString()
    }.getOrNull()

    private val TRACKING = setOf("oc", "fbclid", "gclid", "mc_cid", "mc_eid", "ref", "ref_src", "cmpid", "ocid", "ito", "int_source")

    fun parseDate(s: String): Long? = runCatching { ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        // Some feeds write "+0200" offsets the same way but a zone name RFC 1123 doesn't know ("CEST"); try those too.
        ?: runCatching { ZonedDateTime.parse(s, DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", java.util.Locale.ENGLISH)).toInstant().toEpochMilli() }.getOrNull()

    fun parseIsoDate(s: String): Long? = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

    private fun Element.text(tag: String): String? {
        val nodes = getElementsByTagName(tag)
        return if (nodes.length == 0) null else nodes.item(0).textContent
    }
}
