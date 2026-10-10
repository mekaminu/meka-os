package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings → Watch (Galaxy Watch, slice 1): the code as typed, each row's line, the summary, the watch's screen, Activity. */
class WatchLinkTest {
    private val hour = 3_600_000L
    private val cal = LocalCalendar.fixedOffset(hour)
    private val sat = CivilDate.toEpochDay(2026, 10, 10)
    private fun at(day: Long, h: Int) = cal.toEpochMs(day, h * 60)
    private val now = at(sat, 10)

    @Test
    fun theCodeReadsHoweverItWasTyped() {
        assertEquals("12345678", WatchLinkRules.normaliseCode("1234 5678"))
        assertEquals("12345678", WatchLinkRules.normaliseCode(" 1234-5678 "))
        assertEquals("01234567", WatchLinkRules.normaliseCode("01234567"))
        assertNull(WatchLinkRules.normaliseCode("1234 567"))
        assertNull(WatchLinkRules.normaliseCode("1234 567a"))
        assertNull(WatchLinkRules.normaliseCode(""))
        assertEquals("1234 5678", WatchLinkRules.showCode("12345678"))
        assertEquals("123", WatchLinkRules.showCode("123"))
    }

    @Test
    fun aNewWatchIdIsTheShapeTheServerTakes() {
        val id = WatchLinkRules.newDeviceId(Random(7))
        assertTrue(id.startsWith("watch-"))
        assertEquals(22, id.length)
        assertTrue(id.drop(6).all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(WatchLinkRules.isWatchId(id))
        assertFalse(WatchLinkRules.isWatchId("android"))
        assertTrue(WatchLinkRules.newDeviceId(Random(1)) != WatchLinkRules.newDeviceId(Random(2)))
    }

    @Test
    fun theScreenSaysWhatIsLinkedNewestFirst() {
        val none = WatchLinkRules.view(emptyList(), now, cal)
        assertEquals("No watch linked yet", none.summary)
        assertEquals(WatchLinkRules.HOW, none.how)
        assertTrue(none.rows.isEmpty())

        val one = WatchLinkRules.view(listOf(LinkedWatch("watch-a", "Galaxy Watch", at(sat, 9))), now, cal, problem = "x")
        assertEquals("Galaxy Watch is linked · it shows Up next and takes Done", one.summary)
        assertEquals(listOf(LinkedWatchRow("watch-a", "Galaxy Watch", "Linked today")), one.rows)
        assertEquals("x", one.problem)

        val two = WatchLinkRules.view(
            listOf(LinkedWatch("watch-old", "Old watch", at(sat - 3, 9)), LinkedWatch("watch-new", "Galaxy Watch", at(sat - 1, 9))),
            now, cal,
        )
        assertEquals("2 watches are linked", two.summary)
        assertEquals(listOf("watch-new", "watch-old"), two.rows.map { it.id })
        assertEquals(listOf("Linked yesterday", "Linked Wed 7 Oct"), two.rows.map { it.line })
    }

    @Test
    fun refusalsReadInWords() {
        assertEquals(WatchLinkRules.WRONG_CODE, WatchLinkRules.refusal("code"))
        assertEquals(WatchLinkRules.WRONG_CODE, WatchLinkRules.refusal(null))
        assertEquals(WatchLinkRules.TOO_MANY, WatchLinkRules.refusal("wait"))
        assertEquals(WatchLinkRules.UNLINKED_BEFORE, WatchLinkRules.refusal("revoked"))
    }

    @Test
    fun theWatchShowsTheCodeAndTheTimeLeft() {
        val s = WatchLinkRules.codeScreen("12345678", now + WatchLinkRules.CODE_MS, now)
        assertEquals("Link to MEKA", s.title)
        assertEquals("1234 5678", s.code)
        assertEquals("On your phone: Ask → More → Watch", s.line)
        assertEquals("10 min left", s.left)
        assertFalse(s.expired)
        assertEquals("9 min left", WatchLinkRules.codeScreen("12345678", now + 9 * 60_000 - 1, now).left)
        assertEquals("Less than a minute left", WatchLinkRules.codeScreen("12345678", now + 30_000, now).left)
        val gone = WatchLinkRules.codeScreen("12345678", now, now)
        assertTrue(gone.expired)
        assertEquals("Tap for a new code", gone.left)
        assertEquals("That code ran out", gone.line)
    }

    @Test
    fun activityEntriesAreWrittenOnceWhicheverDeviceSeesThemFirst() {
        val world = SyncWorld()
        world.clock.nowMs = now
        val fold = world.device("android")
        val mac = world.device("mac")
        val logFold = ActivityLog(fold.replica, { "x" }, { world.clock.nowMs }, cal)
        val logMac = ActivityLog(mac.replica, { "y" }, { world.clock.nowMs }, cal)
        val id = WatchLinkRules.linkedId("watch-a")
        logFold.recordDevice(id, at(sat, 8), WatchLinkRules.linkedSummary("Galaxy Watch"), WatchLinkRules.WHY_YOU)
        fold.sync(); mac.sync()
        logMac.recordDevice(id, at(sat, 9), "again", WatchLinkRules.WHY_YOU)
        val items = logMac.items().filter { it.kind == ActivityKind.DEVICE }
        assertEquals(1, items.size)
        assertEquals("Linked Galaxy Watch", items[0].summary)
        assertEquals(at(sat, 8), items[0].atMs)
        assertFalse(items[0].canUndo)
        assertTrue(WatchLinkRules.linkedId("watch-a") != WatchLinkRules.unlinkedId("watch-a"))
        assertEquals("Unlinked Galaxy Watch", WatchLinkRules.unlinkedSummary("Galaxy Watch"))
    }
}
