package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Protected time, slice 4: "not protected tonight", just this once. */
class ProtectedOpenTodayTest {
    private val cal = LocalCalendar.UTC
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private val sat = d(10, 17)
    private val sun = d(10, 18)
    private val mon = d(10, 19)
    private val tue = d(10, 20)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val nineToFive = WorkSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60 + 30)
    private val both = ProtectedSetting(evenings = true, fromMin = 19 * 60, weekends = true)
    private val none = HolidayCalendar.NONE

    @Test
    fun liftingAnEveningOpensOnlyThatEvening() {
        val open = both.copy(openDay = tue)
        assertEquals(ProtectedRules.Lifted.EVENING, ProtectedRules.lifted(both, tue, nineToFive, none))
        assertEquals(ProtectedRules.Lifted.EVENING, ProtectedRules.lifted(open, tue, nineToFive, none))
        assertNull(ProtectedRules.eveningFrom(open, tue, nineToFive, none))
        assertTrue(ProtectedRules.slots(open, tue, nineToFive, none, cal).isEmpty())
        assertNull(ProtectedRules.planNote(open, tue, nineToFive, none))
        // Wednesday is protected as usual.
        assertEquals(19 * 60, ProtectedRules.eveningFrom(open, tue + 1, nineToFive, none))
        // Work mail isn't held tonight, nor in the small hours after; Wednesday evening it is again.
        assertNull(ProtectedRules.protectedUntil(open, at(tue, 21), nineToFive, none, cal))
        assertNull(ProtectedRules.protectedUntil(open, at(tue + 1, 2), nineToFive, none, cal))
        assertEquals(at(tue + 2, 7), ProtectedRules.protectedUntil(open, at(tue + 1, 20), nineToFive, none, cal))
    }

    @Test
    fun liftingAWholeDayAndWhatThePaneAndAskSay() {
        val open = both.copy(openDay = sat)
        assertEquals(ProtectedRules.Lifted.DAY, ProtectedRules.lifted(both, sat, nineToFive, none))
        assertFalse(ProtectedRules.wholeDay(open, sat, nineToFive, none))
        assertTrue(ProtectedRules.wholeDay(open, sun, nineToFive, none))
        assertNull(ProtectedRules.waitLabel(open, sat, nineToFive, none))
        // Sunday's held mail still waits till Monday.
        assertEquals(at(mon, 7), ProtectedRules.protectedUntil(open, at(sun, 10), nineToFive, none, cal))

        val before = ProtectedRules.view(both, sat, nineToFive, none)
        assertEquals("Not protected today", before.openAction)
        assertFalse(before.openToday)
        val after = ProtectedRules.view(open, sat, nineToFive, none)
        assertNull(after.openAction)
        assertTrue(after.openToday)
        assertEquals("Not protected today · you opened it · the planner and work mail as usual", after.todayLine)
        assertEquals("Protected today · none · he opened today himself, just this once",
            ProtectedRules.askLines(open, sat, nineToFive, none)[1])
        // A lift on a past day changes nothing today; nothing to lift on a day without protected time.
        val stale = both.copy(openDay = sat)
        assertEquals("Not protected tonight", ProtectedRules.view(stale, tue, nineToFive, none).openAction)
        assertNull(ProtectedRules.lifted(both.copy(evenings = false), tue, nineToFive, none))
        assertNull(ProtectedRules.view(null, tue, nineToFive, none).openAction)
    }

    @Test
    fun theFieldReadsBackAndZeroMeansNone() {
        val r = ProtectedRules.read(mapOf(ProtectedFields.OPEN_DAY to os.meka.core.sync.FieldValue.Int64(tue)))
        assertEquals(tue, r?.openDay)
        assertNull(ProtectedRules.read(mapOf(ProtectedFields.OPEN_DAY to os.meka.core.sync.FieldValue.Int64(0)))?.openDay)
    }
}
