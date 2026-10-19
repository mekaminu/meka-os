package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.NewsRules
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Full news hub, slice 1, through the facade: a story read on the Fold is read on the Mac; Mark all read and Undo; Save for the train. */
class NewsReadFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London
    private val ops = InMemoryServerOpStore()
    private val clock = HlcClock("server", { now })
    private var n = 0

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun headline(id: String, title: String, url: String?, agoMin: Long, source: String = "The Verge", topic: String = "ai") {
        listOfNotNull(
            "title" to FieldValue.Text(title),
            url?.let { "url" to FieldValue.Text(it) },
            "source" to FieldValue.Text(source),
            "topic" to FieldValue.Text(topic),
            "publishedAtMs" to FieldValue.Int64(now - agoMin * 60_000L),
            "removed" to FieldValue.Bool(false),
        ).forEach { (field, value) ->
            ops.append(Op("srv${n++}", "hh", os.meka.core.domain.EntityTypes.HEADLINE, id, field, value, clock.now(), emptyList(), "server"))
        }
    }

    @Test
    fun readOnTheFoldIsReadOnTheMacAndMarkAllReadHasUndo() = runTest {
        headline("ai-0", "Model ships", "https://www.theverge.com/ai/1", 10)
        headline("ai-1", "Chip news", "https://www.theverge.com/ai/2", 20)
        headline("ai-2", "No link here", null, 30)
        val fold = core("android"); val mac = core("mac")
        fold.syncNow(); mac.syncNow()
        assertEquals("3 new", fold.newsPlace.value.unreadLine)

        assertTrue(fold.newsOpened("ai-0"))
        assertFalse(fold.newsOpened("ai-0"))
        fold.syncNow(); mac.syncNow()
        assertEquals("2 new · 1 read", mac.newsPlace.value.unreadLine)
        assertTrue(mac.newsPlace.value.items.single { it.id == "ai-0" }.read)

        assertEquals(2, mac.newsMarkAllRead())
        assertEquals("All read · new stories get a dot", mac.newsPlace.value.unreadLine)
        assertEquals(0, mac.newsMarkAllRead())
        assertTrue(mac.newsUndoAllRead())
        assertEquals("2 new · 1 read", mac.newsPlace.value.unreadLine)
        assertFalse(mac.newsUndoAllRead())
    }

    @Test
    fun saveForTheTrainQueuesTheLinkOnBothAppsAndMarksItRead() = runTest {
        headline("ai-0", "Model ships", "https://www.theverge.com/ai/1", 10)
        headline("ai-2", "No link here", null, 30)
        val fold = core("android"); val mac = core("mac")
        fold.syncNow()
        val saved = fold.newsForTrain("ai-0")
        assertFalse(saved.refused)
        assertEquals("Saved for the train: Model ships", saved.line)
        val story = fold.newsPlace.value.items.single { it.id == "ai-0" }
        assertTrue(story.queued)
        assertTrue(story.read)
        assertFalse(story.canQueue)
        val none = fold.newsForTrain("ai-2")
        assertTrue(none.refused)
        assertEquals(NewsRules.NO_LINK_TO_SAVE, none.line)

        fold.syncNow(); mac.syncNow()
        assertEquals(listOf("Model ships"), mac.queueView.value.waiting.map { it.title })
        assertTrue(mac.newsPlace.value.items.single { it.id == "ai-0" }.queued)
        // Done in the queue: the story can be saved again.
        mac.queueDone(mac.queueView.value.waiting.single().id, true)
        assertTrue(mac.newsPlace.value.items.single { it.id == "ai-0" }.canQueue)
    }

    /** Full news hub, slice 2: a source switched off on the Fold leaves News and the brief on the Mac too. */
    @Test
    fun aSourceSwitchedOffOnTheFoldIsOffOnTheMacAndInTheBrief() = runTest {
        headline("ai-0", "Model ships", "https://www.theverge.com/ai/1", 10)
        headline("ai-1", "Funding round", "https://techcrunch.com/ai/2", 20, source = "TechCrunch")
        val fold = core("android"); val mac = core("mac")
        fold.syncNow(); mac.syncNow()
        assertEquals(listOf("TechCrunch · 1", "The Verge · 1"), mac.newsPlace.value.sources.map { it.label })
        assertTrue(mac.briefView.value.headlines.any { it.title == "Model ships" })

        assertTrue(fold.setNewsSource("The Verge", on = false))
        assertFalse(fold.setNewsSource("the verge", on = false))
        fold.syncNow(); mac.syncNow()
        assertEquals(listOf("ai-1"), mac.newsPlace.value.items.map { it.id })
        assertEquals(listOf("TechCrunch · 1", "The Verge · off"), mac.newsPlace.value.sources.map { it.label })
        assertEquals(listOf("Funding round"), mac.briefView.value.headlines.map { it.title })

        assertTrue(mac.setNewsSource("The Verge", on = true))
        mac.syncNow(); fold.syncNow()
        assertEquals(2, fold.newsPlace.value.items.size)
    }

    @Test
    fun aWordFollowedOnTheFoldLeadsNewsTheTickerAndTheBriefOnTheMac() = runTest {
        headline("ai-0", "Model ships", "https://www.theverge.com/ai/1", 10)
        // Not a chosen topic (health), but it names a followed word.
        headline("he-0", "Oakfield Rovers sign a new keeper", "https://www.bbc.co.uk/sport/1", 30, source = "BBC Sport", topic = "health")
        val fold = core("android"); val mac = core("mac")
        fold.syncNow(); mac.syncNow()
        assertEquals(listOf("ai-0"), mac.newsPlace.value.items.map { it.id })

        assertEquals("Following Oakfield Rovers and Bitcoin · stories naming them lead News", fold.newsFollow("Oakfield Rovers, bitcoin"))
        assertEquals("Already following Bitcoin", fold.newsFollow("BITCOIN"))
        assertEquals("Type a word or name to follow, at least two letters", fold.newsFollow(" , x"))
        fold.syncNow(); mac.syncNow()
        val place = mac.newsPlace.value
        assertEquals("following", place.lanes.first().topicId)
        assertEquals(listOf("he-0"), place.lanes.first().items.map { it.id })
        assertEquals("BBC Sport · 30 min ago · Oakfield Rovers", place.lanes.first().items.single().meta)
        assertEquals(listOf("Oakfield Rovers · 1", "bitcoin · none yet"), place.follows.map { it.label })
        assertEquals("Oakfield Rovers sign a new keeper", mac.briefView.value.headlines.first().title)
        assertEquals("BBC Sport · Following Oakfield Rovers · 30 min ago", mac.briefView.value.headlines.first().meta)
        assertEquals("Oakfield Rovers sign a new keeper", os.meka.core.domain.TickerRules.ticker(place).items.first().title)

        assertTrue(mac.newsUnfollow("oakfield rovers"))
        assertFalse(mac.newsUnfollow("Oakfield Rovers"))
        mac.syncNow(); fold.syncNow()
        assertEquals(listOf("ai-0"), fold.newsPlace.value.items.map { it.id })
        assertEquals(listOf("bitcoin"), fold.newsPlace.value.follows.map { it.name })
    }
}
