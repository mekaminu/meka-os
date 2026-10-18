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

        // Done lets the words go (the storage stays small); Back fetches them again; removing the link drops them too.
        assertTrue(fold.queueDone(article, true))
        assertEquals(null, fold.queuePage(article))
        assertTrue(fold.queuePages.value.isEmpty())
        assertTrue(fold.queueDone(article, false))
        assertEquals(listOf(article), fold.queueToFetch().map { it.id })
        assertTrue(fold.saveQueuePage(article, html))
        assertTrue(fold.removeFromQueue(article))
        assertEquals(null, fold.queuePage(article))
        assertTrue(fold.queuePages.value.isEmpty())
    }

    @Test
    fun aPaywalledArticleIsMarkedOnlineOnlyAndTheTriesOutlastARestart() = runTest {
        val store = InMemoryReplicaStore()
        fun fold(seed: Int) = MekaCore(
            householdId = "hh", deviceId = "android", store = store,
            transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
            secureRandom = Random(seed), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        val first = fold(1)
        val id = first.addToQueue("https://www.ft.com/content/rail", "Rail fares to rise").id!!
        assertEquals(listOf(id), first.queueToFetch().map { it.id })
        assertFalse(first.saveQueuePage(id, "<html><body><p>Subscribe to read.</p></body></html>"))
        assertEquals(mapOf(id to os.meka.core.domain.ReaderRules.ONLINE_FOR_NOW), first.queueOnline.value)

        // The phone restarts: the failed try is remembered, so it still waits six hours.
        val again = fold(2)
        assertTrue(again.queueToFetch().isEmpty())
        assertEquals(mapOf(id to os.meka.core.domain.ReaderRules.ONLINE_FOR_NOW), again.queueOnline.value)
        repeat(2) {
            now += os.meka.core.domain.ReaderRules.RETRY_MS
            assertEquals(listOf(id), again.queueToFetch().map { it.id })
            assertFalse(again.saveQueuePage(id, null))
        }
        // Three tries: left online for good, never fetched again.
        assertEquals(mapOf(id to os.meka.core.domain.ReaderRules.ONLINE_ONLY), again.queueOnline.value)
        now += 10 * os.meka.core.domain.ReaderRules.RETRY_MS
        assertTrue(again.queueToFetch().isEmpty())
        // Done: it's no longer waiting, so the line goes.
        assertTrue(again.queueDone(id, true))
        assertTrue(again.queueOnline.value.isEmpty())
    }

    @Test
    fun onTheTrainTodaySaysHowLongToTheChangeWhatsSavedAndTheBookOnTheGo() = runTest {
        now = 1_791_522_300_000L // Fri 9 Oct 2026, 06:05 in London: an office day
        val fold = core("android")
        fold.setAboutYouField(os.meka.core.domain.AboutYouFields.ROUTE, os.meka.core.testing.InventedRoute.TYPED)
        fold.setAboutYouField(os.meka.core.domain.AboutYouFields.HOME_STATION, "Oakfield")
        fold.setAboutYouField(os.meka.core.domain.AboutYouFields.STATION_WALK, "10, 15")
        fold.addToQueue("https://www.bbc.co.uk/news/rail-1", "Why the trains run late")
        fold.addReading("reading Dune by Frank Herbert, 400 pages")
        fold.readingProgress(fold.readingView.value.onTheGo.single().id, "100")
        val clock = os.meka.core.sync.HlcClock("server", { now })
        var n = 0
        fun board(list: List<os.meka.core.domain.TrainDeparture>) {
            fun put(field: String, v: os.meka.core.sync.FieldValue) = ops.append(os.meka.core.sync.Op("srv${n++}", "hh",
                os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.LineStatusStore.ENTITY_ID, field, v, clock.now(), emptyList(), "server"))
            put(os.meka.core.domain.LineStatusFields.DEPARTURES, os.meka.core.sync.FieldValue.Text(os.meka.core.domain.TrainBoardCodec.encode(list)))
            put(os.meka.core.domain.LineStatusFields.DEPARTURES_DIR, os.meka.core.sync.FieldValue.Text(os.meka.core.domain.TrainBoard.AM))
            put(os.meka.core.domain.LineStatusFields.DEPARTURES_CHECKED, os.meka.core.sync.FieldValue.Int64(now))
            put(os.meka.core.domain.LineStatusFields.BOARD_STATE, os.meka.core.sync.FieldValue.Text(os.meka.core.domain.TrainBoard.OK))
            put(os.meka.core.domain.LineStatusFields.BOARD_OK_AT, os.meka.core.sync.FieldValue.Int64(now))
        }
        val trains = listOf(
            os.meka.core.domain.TrainDeparture(7 * 60 + 30, 7 * 60 + 30, arrMin = 8 * 60 + 2),
            os.meka.core.domain.TrainDeparture(7 * 60 + 42, 7 * 60 + 42, arrMin = 8 * 60 + 14),
            os.meka.core.domain.TrainDeparture(7 * 60 + 54, 7 * 60 + 54, arrMin = 8 * 60 + 26),
        )
        now += 75 * 60_000L // 07:20: leave by 07:32 for the 07:42
        board(trains)
        fold.syncNow()
        assertEquals("For the train · 1 article · Dune at 25%", fold.today.value.trainQueue?.text)
        now += 30 * 60_000L // 07:50: the 07:42 has left the board
        board(trains.drop(2))
        fold.syncNow()
        now += 10 * 60_000L // 08:00
        fold.tick()
        assertEquals("On the 07:42 · 14 min to Oakfield Junction · 1 article · Dune at 25%", fold.today.value.trainQueue?.text)
        now += 15 * 60_000L // 08:15: off at the change
        fold.tick()
        assertEquals("For the train · 1 article · Dune at 25%", fold.today.value.trainQueue?.text)
    }
}
