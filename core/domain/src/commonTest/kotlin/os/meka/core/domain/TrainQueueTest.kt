package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Train-time queue, slice 1: links and podcasts shared to MEKA, kept once, offered on an office day's commute. */
class TrainQueueTest {
    private val world = SyncWorld()
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun queue(dev: Device) = TrainQueue(dev.replica, { "q${n++}" }, { world.clock.nowMs })
    private val qFold = queue(fold)
    private val qMac = queue(mac)

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun aSharedLinkIsFoundAndTidied() {
        assertEquals("https://www.bbc.co.uk/news/uk-123", QueueRules.link("Read this: https://www.bbc.co.uk/news/uk-123."))
        assertEquals("https://en.wikipedia.org/wiki/Rail_(magazine)", QueueRules.link("(see https://en.wikipedia.org/wiki/Rail_(magazine))"))
        assertEquals("http://example.com/a?b=1", QueueRules.link("http://example.com/a?b=1"))
        assertNull(QueueRules.link("no link here"))
        assertNull(QueueRules.link("ftp://files.example.com/x"))
        assertNull(QueueRules.link("https://localhost/x"))
        assertEquals("bbc.co.uk", QueueRules.host("https://www.bbc.co.uk/news/uk-123"))
        assertEquals("theguardian.com", QueueRules.host("https://m.theguardian.com/x"))
        // The same link however it was shared: tracking, fragment and a trailing "/" don't count.
        assertEquals(
            QueueRules.key("https://www.bbc.co.uk/news/uk-123"),
            QueueRules.key("HTTPS://BBC.co.uk/news/uk-123/?utm_source=share&utm_medium=ios#top"),
        )
        assertFalse(QueueRules.key("https://a.com/x?id=1") == QueueRules.key("https://a.com/x?id=2"))
    }

    @Test
    fun podcastsAreToldFromArticlesAndTheTitleComesFromTheShare() {
        assertEquals(QueueKind.PODCAST, QueueRules.kind("https://podcasts.apple.com/gb/podcast/x/id1?i=2", "x"))
        assertEquals(QueueKind.PODCAST, QueueRules.kind("https://open.spotify.com/episode/abc?si=1", "x"))
        assertEquals(QueueKind.PODCAST, QueueRules.kind("https://www.bbc.co.uk/sounds/play/p0abc", "x"))
        assertEquals(QueueKind.PODCAST, QueueRules.kind("https://example.com/ep", "The Rest Is Football podcast"))
        assertEquals(QueueKind.ARTICLE, QueueRules.kind("https://open.spotify.com/track/abc", "A song"))
        assertEquals(QueueKind.ARTICLE, QueueRules.kind("https://www.bbc.co.uk/news/uk-123", "Rail fares rise"))

        val url = "https://www.bbc.co.uk/news/uk-123"
        assertEquals("Rail fares to rise", QueueRules.title("$url", "Rail fares to rise", url))
        assertEquals("Rail fares to rise", QueueRules.title("Rail fares to rise - $url", null, url))
        assertEquals("bbc.co.uk", QueueRules.title(url, null, url))
        val long = QueueRules.title("word ".repeat(60) + url, null, url)
        assertTrue(long.length <= QueueRules.MAX_TITLE + 1 && long.endsWith("…"))
    }

    @Test
    fun aLinkIsKeptOnceAndDoneOrRemovedOnBothApps() {
        assertEquals(QueueAdded(QueueRules.NO_LINK, true), qFold.add("just words", null))
        val a = qFold.add("Rail fares to rise https://www.bbc.co.uk/news/uk-123", null)
        assertEquals("Saved for the train: Rail fares to rise", a.line)
        assertFalse(a.refused)
        world.clock.advance(1_000)
        qFold.add("https://podcasts.apple.com/gb/podcast/x/id1?i=2", "The Rest Is Football")
        assertEquals(QueueRules.ALREADY, qFold.add("https://bbc.co.uk/news/uk-123?utm_source=x", null).line)

        sync()
        val v = qMac.view()
        assertEquals("1 article · 1 podcast for the train", v.summary)
        assertEquals(listOf("The Rest Is Football", "Rail fares to rise"), v.waiting.map { it.title }) // newest first
        assertEquals("podcasts.apple.com · Podcast", v.waiting[0].meta)
        assertEquals("Article from bbc.co.uk: Rail fares to rise", v.waiting[1].spoken)

        val rail = v.waiting[1].id
        assertTrue(qMac.setDone(rail, true))
        assertFalse(qMac.setDone(rail, true))
        sync()
        assertEquals("1 podcast for the train", qFold.view().summary)
        assertEquals(listOf("Rail fares to rise"), qFold.view().done.map { it.title })
        // Shared again once read: back in the queue, not a second copy.
        assertEquals("Back in your train queue: Rail fares to rise", qFold.add("https://www.bbc.co.uk/news/uk-123", null).line)
        assertEquals(2, qFold.view().waiting.size)

        assertTrue(qFold.remove(rail))
        assertFalse(qFold.remove(rail))
        sync()
        assertEquals(listOf("The Rest Is Football"), qMac.view().waiting.map { it.title })
        // A link done long ago drops out of "done" altogether.
        qMac.setDone(qMac.view().waiting.single().id, true)
        world.clock.advance((QueueRules.DONE_KEPT_DAYS + 1) * 86_400_000L)
        assertTrue(qMac.view().done.isEmpty())
        assertEquals(QueueRules.EMPTY_SUMMARY, qMac.view().summary)
    }

    @Test
    fun todayOffersTheQueueOnAnOfficeDaysCommuteOnly() {
        // Friday 9 October 2026, British Summer Time (UTC+1).
        val cal = LocalCalendar.fixedOffset(3_600_000L)
        val fri = CivilDate.toEpochDay(2026, 10, 9)
        fun at(h: Int, m: Int = 0) = cal.toEpochMs(fri, h * 60 + m)
        val office = PlacesRules.officeWindow(WorkHours(WorkSchedule.DEFAULT), fri, cal)
        val items = listOf(
            QueueItem("a", "https://bbc.co.uk/1", "One", QueueKind.ARTICLE, "bbc.co.uk", 1, 0),
            QueueItem("b", "https://bbc.co.uk/2", "Two", QueueKind.ARTICLE, "bbc.co.uk", 2, 0),
            QueueItem("c", "https://overcast.fm/+x", "Three", QueueKind.PODCAST, "overcast.fm", 3, 0),
            QueueItem("d", "https://bbc.co.uk/4", "Four", QueueKind.ARTICLE, "bbc.co.uk", 4, 5),
        )
        val morning = QueueRules.trainLine(items, office, at(7, 10), cal)!!
        assertEquals("For the train · 2 articles · 1 podcast", morning.text)
        assertEquals("For the train: 2 articles and 1 podcast saved", morning.spoken)
        assertNull(QueueRules.trainLine(items, office, at(5, 59), cal))
        assertNull(QueueRules.trainLine(items, office, at(12), cal))
        assertNull(QueueRules.trainLine(items, null, at(7, 10), cal)) // not an office day
        assertNull(QueueRules.trainLine(items.filter { it.id == "d" }, office, at(7, 10), cal)) // nothing waiting
        assertTrue(QueueRules.trainLine(items, office, office!!.endMs - 10 * 60_000L, cal) != null) // the way home
    }
}
