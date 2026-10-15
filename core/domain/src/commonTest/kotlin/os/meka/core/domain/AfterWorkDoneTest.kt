package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Done per card on the after-work summary (Meka, 2026-10-15 20:28). */
class AfterWorkDoneTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val a = world.device("android")
    private val m = world.device("mac")
    private fun held(d: Device) = HeldMessages(d.replica) { world.clock.nowMs }
    private val fold = held(a)
    private val mac = held(m)
    private val t0 = 1_791_360_000_000L

    init { world.clock.nowMs = t0 + 18 * hour }

    private fun msg(who: String, text: String?, at: Long) =
        CapturedItem(Capture.itemId(CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, null, at, text), CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, text, null, at)

    private fun call(who: String, at: Long) =
        CapturedItem(Capture.itemId(CaptureApp.PHONE, CaptureKind.MISSED_CALL, who, null, at, null), CaptureApp.PHONE, CaptureKind.MISSED_CALL, who, null, null, at)

    private fun syncBoth() { a.sync(); m.sync(); a.sync() }

    private fun holdThree() {
        fold.hold(listOf(
            msg("Alex", "pub later?", t0 + 10 * hour),
            msg("Alex", "or tomorrow", t0 + 10 * hour + 60_000),
            msg("Sam", "running late", t0 + 11 * hour),
            call("Jo", t0 + 12 * hour),
        ))
        syncBoth()
    }

    @Test
    fun clearingOneCardKeepsTheRestOnBothDevices() {
        holdThree()
        val alex = mac.summary().people.first { it.personName == "Alex" }
        assertEquals(2, mac.clear(alex.ids))
        syncBoth()
        for (s in listOf(fold.summary(), mac.summary())) {
            assertEquals(listOf("Jo", "Sam"), s.people.map { it.personName })
            assertEquals("2 people · 1 message · 1 missed call", s.headline)
        }
        // Its words are blanked like the old Done; the others keep theirs.
        assertEquals("running late", fold.summary().people.first { it.personName == "Sam" }.latestText)
    }

    @Test
    fun clearWithNoIdsClearsNothingAndAllStillClearsEverything() {
        holdThree()
        assertEquals(0, fold.clear(emptyList()))
        assertEquals(0, fold.clear(listOf("not-held")))
        assertEquals(3, fold.summary().people.size)
        assertEquals(4, fold.clear())
        assertTrue(fold.summary().isEmpty)
    }

    @Test
    fun hidingWhileUndoIsOfferedUpdatesTheCountsAndUndoBringsItBack() {
        holdThree()
        val s = fold.summary()
        val sam = s.people.first { it.personName == "Sam" }
        val hidden = s.without(sam.ids.toSet())
        assertEquals(listOf("Jo", "Alex"), hidden.people.map { it.personName })
        assertEquals("2 people · 2 messages · 1 missed call", hidden.headline)
        // Undo: nothing was written, so the full summary is still what's held.
        assertEquals(s, s.without(emptySet()))
        assertEquals(3, fold.summary().people.size)
    }

    @Test
    fun partOfACardRecountsIt() {
        holdThree()
        val alex = fold.summary().people.first { it.personName == "Alex" }
        val kept = alex.keeping(alex.items.takeLast(1))!!
        assertEquals("1 message", kept.line)
        assertEquals("or tomorrow", kept.latestText)
        assertNull(alex.keeping(emptyList()))
    }

    @Test
    fun undoLinesAndCallNotices() {
        holdThree()
        val s = fold.summary()
        val alex = s.people.first { it.personName == "Alex" }
        val jo = s.people.first { it.personName == "Jo" }
        assertEquals("Cleared Alex · 2 messages", AfterWorkDone.clearedOne(alex))
        assertEquals("Cleared 3 people · 3 messages · 1 missed call", AfterWorkDone.clearedAll(s))
        assertEquals("Done with Alex", AfterWorkDone.doneLabel(alex))
        assertTrue(AfterWorkDone.clearsCallNotice(jo))
        assertFalse(AfterWorkDone.clearsCallNotice(alex))
    }

    @Test
    fun theNeedsYouCardAndTheSheetReadOneSummary() {
        holdThree()
        // Both screens show `afterWork` with the same lists applied, so their counts can't drift apart.
        val lists = PeopleLists(family = setOf("Sam"))
        val synced = fold.summary()
        assertEquals(synced.withLists(lists).headline, synced.withLists(lists).headline)
        assertEquals("3 people · 3 messages · 1 missed call", synced.withLists(lists).headline)
        assertEquals("Sam", synced.withLists(lists).people.first().personName)
    }
}
