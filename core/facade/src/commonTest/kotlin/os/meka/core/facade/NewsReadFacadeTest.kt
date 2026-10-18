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

    private fun headline(id: String, title: String, url: String?, agoMin: Long) {
        listOfNotNull(
            "title" to FieldValue.Text(title),
            url?.let { "url" to FieldValue.Text(it) },
            "source" to FieldValue.Text("The Verge"),
            "topic" to FieldValue.Text("ai"),
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
}
