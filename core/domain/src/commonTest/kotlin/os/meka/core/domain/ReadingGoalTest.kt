package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading and learning, slice 4: a reading goal for the year, its pace, and setting it with Undo across devices. */
class ReadingGoalTest {
    private val cal = LocalCalendar.fixedOffset(0L)
    private val day = 86_400_000L
    private val sun18Oct = CivilDate.toEpochDay(2026, 10, 18) * day + 10 * 3_600_000L

    private fun book(i: Int, endedAt: Long, kind: ReadingKind = ReadingKind.BOOK, state: ReadingState = ReadingState.FINISHED) = ReadingItem(
        id = "b$i", title = "Book $i", by = "", kind = kind, url = "", unit = ReadingUnit.PAGE, at = 0, total = 0,
        state = state, addedAtMs = 0, startedAtMs = 0, movedAtMs = endedAt, endedAtMs = endedAt, removed = false,
    )

    private fun finished(n: Int) = (1..n).map { book(it, CivilDate.toEpochDay(2026, 3, 1) * day) }

    @Test
    fun aGoalIsReadFromWhatHeTypes() {
        assertEquals(12, ReadingGoalRules.parse("12"))
        assertEquals(24, ReadingGoalRules.parse("24 books"))
        assertEquals(20, ReadingGoalRules.parse("read 20 books this year"))
        assertEquals(30, ReadingGoalRules.parse("Goal: 30 books a year"))
        assertEquals(0, ReadingGoalRules.parse("none"))
        assertNull(ReadingGoalRules.parse("lots"))
        assertNull(ReadingGoalRules.parse("500"))
        assertNull(ReadingGoalRules.parse(""))
    }

    @Test
    fun thePaceIsAgainstHowFarThroughTheYearTodayIs() {
        assertNull(ReadingGoalRules.view(0, finished(3), sun18Oct, cal))
        // 18 Oct is day 291 of 365: 12 a year expects about 9.6 by now.
        val behind = ReadingGoalRules.view(12, finished(4), sun18Oct, cal)!!
        assertEquals("Read 12 books in 2026", behind.title)
        assertEquals("4 of 12", behind.count)
        assertEquals("5 behind pace · 8 to go · about 3 a month", behind.line)
        assertFalse(behind.lit)
        assertEquals("On pace · 3 to go · about 1 a month", ReadingGoalRules.view(12, finished(9), sun18Oct, cal)!!.line)
        val ahead = ReadingGoalRules.view(12, finished(11), sun18Oct, cal)!!
        assertEquals("1 ahead of pace · 1 to go · about 1 a month", ahead.line)
        assertTrue(ahead.lit)
        assertEquals("Done · well read", ReadingGoalRules.view(12, finished(12), sun18Oct, cal)!!.line)
        assertEquals("Done · 1 over · well read", ReadingGoalRules.view(12, finished(13), sun18Oct, cal)!!.line)
        assertEquals(1f, ReadingGoalRules.view(12, finished(13), sun18Oct, cal)!!.fraction)

        // Only books finished this year count: not last year's, a podcast, or one still on the go.
        val mixed = finished(2) + listOf(
            book(10, CivilDate.toEpochDay(2025, 12, 30) * day),
            book(11, sun18Oct, ReadingKind.PODCAST),
            book(12, 0, state = ReadingState.READING),
        )
        assertEquals("2 of 12", ReadingGoalRules.view(12, mixed, sun18Oct, cal)!!.count)
        // December: what's left is "by 31 Dec".
        val dec = CivilDate.toEpochDay(2026, 12, 2) * day
        assertEquals("1 behind pace · 2 to go · by 31 Dec", ReadingGoalRules.view(12, finished(10), dec, cal)!!.line)
    }

    @Test
    fun settingItSyncsAndUndoPutsTheOneBeforeBack() {
        val world = SyncWorld()
        val fold = world.device("android")
        val mac = world.device("mac")
        var n = 0
        val rFold = Reading(fold.replica, { "x${n++}" }, { world.clock.nowMs })
        val rMac = Reading(mac.replica, { "y${n++}" }, { world.clock.nowMs })
        assertTrue(rFold.setGoal("lots", cal).refused)
        val set = rFold.setGoal("12 books", cal)
        assertTrue(set.line.startsWith("Goal: 12 books in "))
        fold.sync(); mac.sync()
        assertEquals(12, rMac.goalTarget())
        assertEquals(12, rMac.view(cal).goalTarget)
        val again = rMac.setGoal("20", cal)
        assertEquals(20, rMac.goalTarget())
        assertTrue(rMac.undo(again.undo!!))
        assertEquals(12, rMac.goalTarget())
        assertEquals("Reading goal cleared", rMac.setGoal("none", cal).line)
        assertNull(rMac.goalView(cal))
        mac.sync(); fold.sync()
        assertEquals(0, rFold.goalTarget())
        assertNull(rFold.view(cal).goal)
    }
}
