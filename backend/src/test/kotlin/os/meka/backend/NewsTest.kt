package os.meka.backend

import os.meka.backend.integrations.BbcNewsRss
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
        assertEquals(feed.topics.toSet(), os.meka.core.domain.NewsTopics.ALL.map { it.id }.toSet())
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
}
