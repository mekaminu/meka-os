package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BankHolidaysTest {
    private val weekdays = WorkSchedule.DEFAULT // Mon–Fri 09:00–17:30
    private fun day(y: Int, m: Int, d: Int) = CivilDate.toEpochDay(y, m, d)
    private val christmas = day(2026, 12, 25) // a Friday
    private val boxingSubstitute = day(2026, 12, 28) // Monday
    private val holidays = HolidayCalendar.of(listOf(BankHoliday(christmas, "Christmas Day"), BankHoliday(boxingSubstitute, "Boxing Day (substitute day)")))
    private fun clockOn(epochDay: Long, h: Int, m: Int = 0) = LocalClock(CivilDate.isoDayOfWeek(epochDay), h * 60 + m)
    private fun state(epochDay: Long, h: Int, m: Int = 0, switch: WorkSwitch? = null, nowMs: Long = 0, s: WorkSchedule = weekdays) =
        WorkModeRules.state(s, switch, clockOn(epochDay, h, m), nowMs, epochDay, holidays)

    @Test
    fun encodesAndDecodesTheListSortedAndClean() {
        assertEquals(5, CivilDate.isoDayOfWeek(christmas))
        val encoded = BankHolidays.encode(listOf(BankHoliday(boxingSubstitute, "Boxing Day; substitute=day"), BankHoliday(christmas, "Christmas Day")))
        assertEquals("2026-12-25=Christmas Day;2026-12-28=Boxing Day substitute day", encoded)
        assertEquals(listOf(christmas, boxingSubstitute), BankHolidays.decode(encoded).map { it.epochDay })
        // Text from the network: anything unreadable is skipped, never a crash.
        assertEquals(listOf(christmas), BankHolidays.decode("2026-12-25=Christmas Day;2026-02-30=Nope;garbage;=x").map { it.epochDay })
        assertEquals("Bank holiday", BankHolidays.decode("2026-12-25").single().title)
        assertTrue(BankHolidays.decode(null).isEmpty())
        assertNull(BankHolidays.parseDate("2026-13-01"))
    }

    @Test
    fun aBankHolidayOnAWorkDayKeepsWorkModeOff() {
        val s = state(christmas, 10)
        assertFalse(s.atWork)
        assertEquals("Christmas Day", s.holiday)
        // Christmas Friday, the weekend, then the substitute Monday: back on Tuesday.
        assertEquals("Off work · Christmas Day · next shift Tue 09:00", s.line)
        assertEquals(LocalClock(2, 9 * 60), s.until)
        assertEquals("Off work · next shift Tue 09:00", state(day(2026, 12, 24), 18).line)
        assertEquals("At work until 17:30", state(day(2026, 12, 24), 10).line)
        assertTrue(state(day(2026, 12, 29), 10).atWork)
        // A weekend day is never called a holiday line, and a holiday on a day off says nothing about it.
        val sat = HolidayCalendar.of(listOf(BankHoliday(day(2026, 12, 26), "Boxing Day")))
        assertNull(WorkModeRules.state(weekdays, null, clockOn(day(2026, 12, 26), 10), 0, day(2026, 12, 26), sat).holiday)
    }

    @Test
    fun aLongRunOfHolidaysNamesTheDate() {
        val many = HolidayCalendar.of((0L..9L).map { BankHoliday(day(2027, 1, 4) + it, "Holiday") })
        val s = WorkModeRules.state(weekdays, null, clockOn(day(2027, 1, 1), 18), 0, day(2027, 1, 1), many)
        assertEquals("Off work · next shift Thu 14 Jan 09:00", s.line)
    }

    @Test
    fun aNightShiftBelongsToTheDayItStarts() {
        val nights = WorkSchedule(setOf(4), 22 * 60, 6 * 60) // Thursday 22:00 → Friday 06:00
        val thu = day(2026, 12, 24)
        assertTrue(WorkModeRules.scheduledOn(nights, holidays, thu, 23 * 60))
        assertTrue(WorkModeRules.scheduledOn(nights, holidays, christmas, 5 * 60)) // started Thursday, not a holiday
        val thuOff = HolidayCalendar.of(listOf(BankHoliday(thu, "Holiday")))
        assertFalse(WorkModeRules.scheduledOn(nights, thuOff, christmas, 5 * 60))
    }

    @Test
    fun theSwitchStillWorksOnAHoliday() {
        val now = 1_000_000L
        val on = WorkSwitch(on = true, setAtMs = now, scheduledWhenSet = false)
        val s = state(christmas, 10, switch = on, nowMs = now)
        assertTrue(s.atWork)
        assertTrue(s.switchedManually)
        assertNull(s.holiday)
    }

    @Test
    fun withoutADateTheWeeklyScheduleDecidesAsBefore() {
        assertTrue(WorkModeRules.state(weekdays, null, clockOn(christmas, 10), 0).atWork)
    }

    @Test
    fun theBriefAndTheShutdownTreatTheHolidayAsADayOff() {
        assertEquals("Christmas Day · no work", BriefRules.workLine(weekdays, holidays, christmas))
        assertEquals("Work 09:00–17:30", BriefRules.workLine(weekdays, holidays, day(2026, 12, 24)))
        assertNull(BriefRules.workLine(weekdays, holidays, day(2026, 12, 26)))
        assertEquals(17 * 60 + 30, ShutdownRules.startMinute(weekdays, day(2026, 12, 24), holidays))
        assertEquals(18 * 60, ShutdownRules.startMinute(weekdays, christmas, holidays))
        assertTrue(WorkModeRules.isWorkDay(weekdays, holidays, day(2026, 12, 24)))
        assertFalse(WorkModeRules.isWorkDay(weekdays, holidays, christmas))
    }

    @Test
    fun theMirroredListReachesEveryDeviceAndWorkModeFollowsIt() {
        val world = SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        // What the server writes; written on a device replica here to stand in for it.
        a.replica.commitLocal(
            EntityTypes.CONTEXT_MODE, BankHolidayStore.ENTITY_ID,
            mapOf(BankHolidayFields.DATES to BankHolidays.encode(listOf(BankHoliday(christmas, "Christmas Day"))).fv(), BankHolidayFields.SOURCE to "GOV.UK".fv()),
        )
        a.syncWithRetry(); m.syncWithRetry()
        val store = BankHolidayStore(m.replica)
        assertEquals("Christmas Day", store.calendar().title(christmas))
        val work = WorkMode(m.replica, { store.calendar() }) { world.clock.nowMs }
        assertFalse(work.state(clockOn(christmas, 10), christmas).atWork)
        assertTrue(work.state(clockOn(christmas, 10)).atWork) // no date: the weekly schedule alone
        // "Work on" on the holiday is a real override (the schedule says off).
        work.setSwitch(true, clockOn(christmas, 10), christmas)
        assertTrue(work.state(clockOn(christmas, 10), christmas).let { it.atWork && it.switchedManually })
    }
}
