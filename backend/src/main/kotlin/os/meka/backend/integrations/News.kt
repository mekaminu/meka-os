package os.meka.backend.integrations

import org.w3c.dom.Element
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

/** One headline as a feed reports it. [id] is unique within the feed (its link, without tracking parameters). */
data class RemoteHeadline(val id: String, val title: String, val url: String, val publishedMs: Long)

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

/** RSS 2.0 items. Feeds are untrusted: no DTDs or external entities, only https links, dates must parse. */
internal object Rss {
    private const val MAX_BYTES = 2_000_000

    fun parse(xml: String): List<RemoteHeadline> {
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
        val doc = builder.parse(InputSource(StringReader(xml)))
        val items = doc.getElementsByTagName("item")
        return (0 until items.length).mapNotNull { i ->
            val e = items.item(i) as Element
            val title = e.text("title")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val link = e.text("link")?.trim()?.let(::canonical) ?: return@mapNotNull null
            val published = e.text("pubDate")?.trim()?.let(::parseDate) ?: return@mapNotNull null
            RemoteHeadline(link, title, link, published)
        }.distinctBy { it.id }
    }

    /** https only; tracking parameters and fragments dropped (they'd make one article look like several). */
    fun canonical(link: String): String? = runCatching {
        val u = URI(link)
        if (!u.scheme.equals("https", ignoreCase = true) || u.host.isNullOrEmpty() || u.rawUserInfo != null) return null
        URI("https", null, u.host.lowercase(), u.port, u.path?.ifEmpty { "/" } ?: "/", null, null).toASCIIString()
    }.getOrNull()

    fun parseDate(s: String): Long? = runCatching { ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()

    private fun Element.text(tag: String): String? {
        val nodes = getElementsByTagName(tag)
        return if (nodes.length == 0) null else nodes.item(0).textContent
    }
}
