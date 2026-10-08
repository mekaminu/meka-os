package os.meka.backend

import os.meka.backend.integrations.BbcNewsRss
import os.meka.backend.integrations.NewsFeed
import os.meka.backend.integrations.PublicNewsFeeds
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.TokenCipher
import os.meka.core.domain.News
import os.meka.core.domain.MekaSchema
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NewsTest {
    private var now = 1_791_270_000_000L // 2026-10-06T07:00Z

    /** Shape of a BBC News RSS feed (fields we read only). */
    private fun rss(vararg items: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <rss xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:media="http://search.yahoo.com/mrss/" version="2.0">
        <channel><title><![CDATA[BBC News]]></title><link>https://www.bbc.co.uk/news</link>
        ${items.joinToString("\n")}
        </channel></rss>"""

    private fun item(slug: String, title: String, pubDate: String) = """
        <item><title><![CDATA[$title]]></title><description><![CDATA[Summary]]></description>
        <link>https://www.bbc.com/news/articles/$slug?at_medium=RSS&amp;at_campaign=rss</link>
        <guid isPermaLink="false">https://www.bbc.com/news/articles/$slug#0</guid>
        <pubDate>$pubDate</pubDate><media:thumbnail width="240" height="135" url="https://ichef.bbci.co.uk/x.jpg"/></item>"""

    private val noCipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain
        override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher
    }

    @Test
    fun parsesItemsNewestFirstWithCanonicalLinks() {
        val feed = BbcNewsRss { url ->
            assertTrue(url.startsWith("https://feeds.bbci.co.uk/"))
            rss(
                item("a1", "Older story", "Tue, 06 Oct 2026 05:10:00 GMT"),
                item("a2", "  Summit   opens in Geneva ", "Tue, 06 Oct 2026 06:40:12 GMT"),
                item("a3", "No date", "not a date"),
                """<item><title>Plain http</title><link>http://www.bbc.com/news/x</link><pubDate>Tue, 06 Oct 2026 06:00:00 GMT</pubDate></item>""",
            )
        }
        val items = feed.headlines("top")
        assertEquals(listOf("Summit opens in Geneva", "Older story"), items.map { it.title })
        assertEquals("https://www.bbc.com/news/articles/a2", items[0].url)
        assertEquals(1_791_268_812_000L, items[0].publishedMs)
        assertEquals(os.meka.core.domain.NewsTopics.ORIGINAL.toSet(), feed.topics.toSet())
        assertTrue(items.all { it.summary == null }) // "Summary" is too short to be worth showing
    }

    @Test
    fun refusesDoctypesSoNoEntityTricksReachTheParser() {
        val evil = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><item><title>&x;</title></item></channel></rss>"""
        assertFailsWith<Exception> { BbcNewsRss { evil }.headlines("top") }
    }

    @Test
    fun headlinesReachDevicesInStableSlotsAndRefreshHourly() {
        var fetches = 0
        var top = rss(
            item("a1", "Storm warning", "Tue, 06 Oct 2026 05:00:00 GMT"),
            item("a2", "Summit opens", "Tue, 06 Oct 2026 06:00:00 GMT"),
        )
        val feed = BbcNewsRss { url ->
            fetches++
            when {
                url.endsWith("/news/rss.xml") -> top
                url.contains("/world/") -> error("feed down") // one topic failing doesn't stop the rest
                else -> rss()
            }
        }
        val store = InMemoryIntegrationStore(knownHouseholds = listOf("home"))
        val ops = InMemoryServerOpStore()
        val integrations = Integrations(store, ops, emptyMap(), { null }, noCipher, "https://meka.example", { now }, news = mapOf(feed.id to feed))
        integrations.syncAll()
        assertEquals(listOf("BBC News"), store.accounts("home").map { it.email })

        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + System.nanoTime() }
        var cursor = 0L
        fun pull() { val page = ops.after("home", cursor, 10_000); r.applyRemoteBatch(page.map { it.op }); page.lastOrNull()?.let { cursor = it.seq } }
        pull()
        val first = News(r).all()
        assertEquals(setOf("Storm warning", "Summit opens"), first.map { it.title }.toSet())
        assertTrue(first.all { it.source == "BBC News" && it.topic == "top" && it.url!!.startsWith("https://www.bbc.com/news/articles/") })
        val stormSlot = first.single { it.title == "Storm warning" }.id

        // Within the hour nothing is fetched again.
        val fetched = fetches
        now += 30 * 60_000L
        integrations.syncAll()
        assertEquals(fetched, fetches)

        // An hour on: a new story arrives and the summit drops out. The storm keeps its slot and isn't rewritten.
        top = rss(
            item("a1", "Storm warning", "Tue, 06 Oct 2026 05:00:00 GMT"),
            item("a3", "Rates held", "Tue, 06 Oct 2026 07:20:00 GMT"),
        )
        now += 31 * 60_000L
        val before = ops.after("home", 0, 10_000).size
        integrations.syncAll()
        pull()
        val second = News(r).all()
        assertEquals(setOf("Storm warning", "Rates held"), second.map { it.title }.toSet())
        assertEquals(stormSlot, second.single { it.title == "Storm warning" }.id)
        // Only the replaced slot changed: its title, link and time (source, topic and "removed" are unchanged).
        assertEquals(before + 3, ops.after("home", 0, 10_000).size)

        // The feed shrinks: the leftover slot is marked removed, not deleted.
        top = rss(item("a3", "Rates held", "Tue, 06 Oct 2026 07:20:00 GMT"))
        now += 61 * 60_000L
        integrations.syncAll()
        pull()
        assertEquals(listOf("Rates held"), News(r).all().map { it.title })
    }

    // ---- AI, tech and Barça feeds (news ticker, slice 1) ----

    /** Shape of an Atom feed like The Verge's. */
    private fun atom(vararg entries: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en-US"><title type="html">The Verge - AI</title>
        <link rel="self" href="https://www.theverge.com/rss/ai-artificial-intelligence/index.xml"/>
        ${entries.joinToString("\n")}
        </feed>"""

    private fun entry(slug: String, title: String, published: String) = """
        <entry><published>$published</published><updated>2026-10-06T06:59:00-04:00</updated>
        <title type="html">$title</title>
        <content type="html">&lt;figure&gt;&lt;img src="https://cdn.example/x.jpg"/&gt;&lt;/figure&gt;&lt;p&gt;The lab says its new model writes &amp;amp; reasons better than the last one, and it ships today.&lt;/p&gt;</content>
        <link rel="alternate" type="text/html" href="https://www.theverge.com/ai-artificial-intelligence/$slug"/>
        <id>https://www.theverge.com/ai-artificial-intelligence/$slug</id><author><name>Someone</name></author></entry>"""

    /** Shape of a Google News search feed: the publisher in <source> and appended to the title. */
    private fun gnewsItem(slug: String, title: String, publisher: String, pubDate: String) = """
        <item><title>$title - $publisher</title><link>https://news.google.com/rss/articles/$slug?oc=5</link>
        <guid isPermaLink="false">$slug</guid><pubDate>$pubDate</pubDate>
        <description>&lt;a href="https://news.google.com/rss/articles/$slug?oc=5"&gt;$title&lt;/a&gt;&amp;nbsp;&amp;nbsp;&lt;font color="#6f6f6f"&gt;$publisher&lt;/font&gt;</description>
        <source url="https://www.example.com">$publisher</source></item>"""

    /** Shape of Mundo Deportivo / Sport: RSS 2.0, Spanish titles, a description with markup. */
    private fun paperItem(host: String, slug: String, title: String, pubDate: String, query: String = "") = """
        <item><title><![CDATA[$title]]></title><link>https://www.$host/futbol/fc-barcelona/$slug.html$query</link>
        <description><![CDATA[<p>El Barça prepara el partido del sábado con la vuelta de varios internacionales.</p>]]></description>
        <pubDate>$pubDate</pubDate><enclosure url="https://www.$host/img.jpg" type="image/jpeg" length="0"/></item>"""

    private val feeds = listOf(
        NewsFeed("barca", "Mundo Deportivo", "https://md.example/barca.xml"),
        NewsFeed("barca", "Sport", "https://sport.example/barca.xml"),
        NewsFeed("barca", "Google News", "https://news.google.com/rss/search?q=barca", aggregator = true),
        NewsFeed("ai", "The Verge", "https://verge.example/ai.xml"),
        NewsFeed("ai", "OpenAI", "https://openai.example/rss.xml"),
        NewsFeed("tech", "Hacker News", "https://hnrss.example/frontpage?points=200", summaries = false),
    )

    @Test
    fun barcaFeedsMergeNewestFirstEachStoryOnceWithTheirPublisher() {
        val p = PublicNewsFeeds(feeds) { url ->
            when {
                url.startsWith("https://md.") -> rss(
                    paperItem("mundodeportivo.com", "flick-xi", "Flick ya tiene el once para el Clásico", "Tue, 06 Oct 2026 06:30:00 +0200"),
                    paperItem("mundodeportivo.com", "pedri", "Pedri vuelve a entrenar", "Tue, 06 Oct 2026 05:00:00 GMT", "?utm_source=rss&amp;id=7"),
                )
                url.startsWith("https://sport.") -> rss(
                    paperItem("sport.es", "flick-once", "Flick ya tiene el once para el Clásico", "Tue, 06 Oct 2026 04:45:00 GMT"), // same story
                )
                else -> rss(gnewsItem("CBMiABC", "Barcelona beat Sevilla 3-1", "Marca", "Tue, 06 Oct 2026 06:50:00 GMT"))
            }
        }
        val items = p.headlines("barca")
        assertEquals(listOf("Barcelona beat Sevilla 3-1", "Pedri vuelve a entrenar", "Flick ya tiene el once para el Clásico"), items.map { it.title })
        assertEquals(listOf("Marca", "Mundo Deportivo", "Sport"), items.map { it.source })
        assertEquals("https://news.google.com/rss/articles/CBMiABC", items[0].url) // Google's oc= dropped
        assertEquals(null, items[0].summary) // an aggregator's description is only links
        assertEquals("https://www.mundodeportivo.com/futbol/fc-barcelona/pedri.html?id=7", items[1].url) // utm_ dropped, id kept
        assertEquals("El Barça prepara el partido del sábado con la vuelta de varios internacionales.", items[1].summary)
        assertEquals(10, p.slots)
        assertEquals(listOf("barca", "ai", "tech"), p.topics)
    }

    @Test
    fun atomFeedsAndAFailingFeedOnlyFailTheTopicWhenAllFail() {
        var openAiDown = true
        var vergeDown = false
        val p = PublicNewsFeeds(feeds) { url ->
            when {
                url.startsWith("https://verge.") -> if (vergeDown) error("down") else atom(
                    entry("1", "A new open model tops the charts", "2026-10-06T07:30:00-04:00"),
                    entry("2", "Chip export rules &amp;amp; what they mean", "2026-10-06T05:00:00-04:00"),
                )
                url.startsWith("https://openai.") -> if (openAiDown) error("down") else rss()
                url.startsWith("https://hnrss.") -> rss(
                    """<item><title>Show HN: A tiny database</title><link>https://example.dev/db</link><pubDate>Tue, 06 Oct 2026 06:00:00 GMT</pubDate>
                    <description><![CDATA[<p>Article URL: https://example.dev/db</p><p>Points: 412</p><p># Comments: 120</p>]]></description></item>""",
                )
                else -> rss()
            }
        }
        val ai = p.headlines("ai")
        assertEquals(listOf("A new open model tops the charts", "Chip export rules & what they mean"), ai.map { it.title })
        assertEquals(1_791_286_200_000L, ai[0].publishedMs) // 11:30Z
        assertEquals("https://www.theverge.com/ai-artificial-intelligence/1", ai[0].url)
        assertEquals("The lab says its new model writes & reasons better than the last one, and it ships today.", ai[0].summary)
        assertTrue(ai.all { it.source == "The Verge" })
        assertEquals(null, p.headlines("tech").single().summary) // Hacker News's description is points and links
        vergeDown = true
        assertFailsWith<Exception> { p.headlines("ai") } // nothing for AI could be read: the account shows the error
        openAiDown = false
        assertTrue(p.headlines("ai").isEmpty()) // an empty feed is not a failure
    }

    @Test
    fun moreHeadlinesReachDevicesInTenSlotsWithPublisherAndSummary() {
        val many = (1..12).map { i -> paperItem("sport.es", "s$i", "Noticia $i del Barça", "Tue, 06 Oct 2026 0${i % 7}:${10 + i}:00 GMT") }
        val p = PublicNewsFeeds(listOf(NewsFeed("barca", "Sport", "https://sport.example/barca.xml"))) { rss(*many.toTypedArray()) }
        val store = InMemoryIntegrationStore(knownHouseholds = listOf("home"))
        val ops = InMemoryServerOpStore()
        val bbc = BbcNewsRss { rss() }
        val integrations = Integrations(store, ops, emptyMap(), { null }, noCipher, "https://meka.example", { now }, news = listOf(bbc, p).associateBy { it.id })
        integrations.syncAll()
        assertEquals(setOf("BBC News", "AI, tech and Barça news"), store.accounts("home").map { it.email }.toSet())
        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + System.nanoTime() }
        r.applyRemoteBatch(ops.after("home", 0, 10_000).map { it.op })
        val read = News(r).all()
        assertEquals(10, read.size)
        assertTrue(read.all { it.source == "Sport" && it.topic == "barca" && it.summary!!.startsWith("El Barça prepara") })
        assertEquals(10, read.map { it.id }.toSet().size)
    }
}
