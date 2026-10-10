package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.QueueRules
import os.meka.core.domain.QueueView
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The train queue through the facade (slice 1): a link shared on the Fold is waiting on the Mac; done and removed sync. */
class TrainQueueFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aLinkSharedOnTheFoldWaitsOnTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(QueueView.EMPTY.summary, fold.queueView.value.summary)
        assertTrue(fold.addToQueue("nothing to open").refused)
        val saved = fold.addToQueue("https://www.theguardian.com/uk-news/rail?utm_source=share", "Why the trains run late")
        assertEquals("Saved for the train: Why the trains run late", saved.line)
        assertFalse(saved.refused)
        now += 1_000
        fold.addToQueue("https://overcast.fm/+abc123")
        assertEquals("1 article · 1 podcast for the train", fold.queueView.value.summary)
        // Saturday: no commute, so Today says nothing about it.
        assertEquals(null, fold.today.value.trainQueue)

        fold.syncNow(); mac.syncNow()
        val rows = mac.queueView.value.waiting
        assertEquals(listOf("overcast.fm", "Why the trains run late"), rows.map { it.title })
        assertEquals(QueueRules.ALREADY, mac.addToQueue("https://theguardian.com/uk-news/rail/").line)

        assertTrue(mac.queueDone(rows[1].id, true))
        assertTrue(mac.removeFromQueue(rows[0].id))
        mac.syncNow(); fold.syncNow()
        val v = fold.queueView.value
        assertTrue(v.waiting.isEmpty())
        assertEquals(listOf("Why the trains run late"), v.done.map { it.title })
        assertTrue(fold.queueDone(v.done.single().id, false))
        assertEquals("1 article for the train", fold.queueView.value.summary)
    }

    @Test
    fun anArticlesWordsAreKeptOnThisDeviceOnlyAndGoWithTheLink() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val article = fold.addToQueue("https://www.bbc.co.uk/news/rail-1", "Why the trains run late").id!!
        now += 1_000
        fold.addToQueue("https://overcast.fm/+abc123")
        // Only the article is fetched; the podcast's own app keeps its downloads.
        assertEquals(listOf(article), fold.queueToFetch().map { it.id })

        assertFalse(fold.saveQueuePage(article, null))
        assertTrue(fold.queueToFetch().isEmpty()) // a failure waits six hours
        now += os.meka.core.domain.ReaderRules.RETRY_MS
        assertEquals(listOf(article), fold.queueToFetch().map { it.id })

        val para = "Engineers will work through the weekend to replace the signal cables between Bedford and St Pancras."
        val html = "<html><head><title>BBC · Why the trains run late</title></head><body><article>" +
            (1..4).joinToString("") { "<p>$para ($it)</p>" } + "</article></body></html>"
        assertTrue(fold.saveQueuePage(article, html))
        assertEquals("1 min read · saved to read offline", fold.queuePages.value[article])
        val page = fold.queuePage(article)!!
        assertEquals("BBC · Why the trains run late", page.title)
        assertEquals(4, page.blocks.size)
        assertTrue(fold.queueToFetch().isEmpty())

        // Never synced: the Mac has the link but not its words.
        fold.syncNow(); mac.syncNow()
        assertEquals(null, mac.queuePage(article))
        assertTrue(mac.queuePages.value.isEmpty())

        // Done keeps the words (Back may bring it back); removing the link drops them.
        assertTrue(fold.queueDone(article, true))
        assertTrue(fold.queuePage(article) != null)
        assertTrue(fold.removeFromQueue(article))
        assertEquals(null, fold.queuePage(article))
        assertTrue(fold.queuePages.value.isEmpty())
    }
}
