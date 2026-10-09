package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Work hours per weekday (Places item 1; Meka 2026-10-09 12:54: Thursday is 09:00–15:30, other weekdays 09:00–17:30). */
class WorkDayHoursTest {
    private val hour = 3_600_000L
    private val cal = LocalCalendar.fixedOffset(hour) // London in early October (BST)
    private val thu8 = CivilDate.toEpochDay(2026, 10, 8) // a Thursday
    private val fri9 = thu8 + 1
    private fun clock(day: Int, h: Int, m: Int = 0) = LocalClock(day, h * 60 + m)
    private val meka = WorkSchedule.DEFAULT

    @Test
    fun thursdayIsTheShortDayByDefault() {
        assertEquals(DayHours(540, 930), meka.hoursOn(4))
        assertEquals(DayHours(540, 1050), meka.hoursOn(3))
        assertTrue(meka.isScheduled(clock(4, 15, 29)))
        assertFalse(meka.isScheduled(clock(4, 15, 30)))
        assertTrue(meka.isScheduled(clock(3, 17, 29)))
        assertFalse(meka.isScheduled(clock(6, 10)))
        assertEquals("Mon–Fri · 09:00–17:30 · Thu 09:00–15:30", meka.summary)
        assertEquals("Mon–Fri 09:00–17:30, Thu 09:00–15:30", meka.plainLine)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri"), meka.weekRows.map { it.dayShort })
        assertEquals(listOf(false, false, false, true, false), meka.weekRows.map { it.own })
        assertEquals("09:00–15:30 · own hours", meka.weekRows[3].line)
        assertEquals("09:00–17:30 · usual", meka.weekRows[0].line)
        assertEquals("Thursday", meka.weekRows[3].name)
    }

    @Test
    fun theStateLineAndTheNextChangeFollowTheDay() {
        assertEquals(clock(4, 15, 30), meka.nextChange(clock(4, 10)))
        assertEquals(clock(3, 17, 30), meka.nextChange(clock(3, 10)))
        val thu = WorkModeRules.state(meka, null, clock(4, 14), 0L, thu8)
        assertTrue(thu.atWork)
        assertEquals("At work until 15:30", thu.line)
        val after = WorkModeRules.state(meka, null, clock(4, 16), 0L, thu8)
        assertFalse(after.atWork)
        assertEquals("Off work · next shift tomorrow 09:00", after.line)
        val wed = WorkModeRules.state(meka, null, clock(3, 16), 0L, thu8 - 1)
        assertEquals("At work until 17:30", wed.line)
    }

    @Test
    fun todayTheCalendarTheBriefAndTheShutdownUseTheDaysOwnHours() {
        val hours = WorkHours(meka)
        assertEquals("Work 09:00–15:30", hours.line(thu8))
        assertEquals("Work 09:00–17:30", hours.line(fri9))
        val block = hours.blocks(thu8, cal).single()
        assertEquals(cal.toEpochMs(thu8, 930), block.endMs)
        assertEquals("09:00–15:30", block.label)
        assertEquals("Work 09:00–15:30", BriefRules.workLine(meka, HolidayCalendar.NONE, thu8))
        assertEquals(930, ShutdownRules.startMinute(meka, thu8))
        assertEquals(1050, ShutdownRules.startMinute(meka, fri9))
        val busy = SessionRules.busyOn(thu8, emptyList(), meka, HolidayCalendar.NONE, cal)
        assertEquals(cal.toEpochMs(thu8, 930) + SessionRules.BUFFER_MIN * 60_000L, busy.single().endMs)
    }

    @Test
    fun aDaysOwnHoursOnlyCountOnAWorkDayAndTheUsualHoursClearThem() {
        val noThu = meka.copy(days = setOf(1, 2, 3, 5))
        assertFalse(noThu.worksOn(4))
        assertEquals(meka.usual, noThu.hoursOn(4))
        assertEquals("Mon–Wed, Fri · 09:00–17:30", noThu.summary)
        assertEquals(DayHours(540, 930), WorkSchedule.decode(noThu.encode())!!.copy(days = meka.days).hoursOn(4)) // kept for when Thursday is back
        val back = meka.withDayHours(4, DayHours(540, 1050))
        assertTrue(back.dayHours.isEmpty())
        assertEquals(WorkSchedule.WEEKDAYS, back)
        assertEquals(meka, WorkSchedule.WEEKDAYS.withDayHours(4, DayHours(540, 930)))
    }

    @Test
    fun aNightShiftOnOneDayRunsIntoTheNext() {
        val s = WorkSchedule.WEEKDAYS.withDayHours(5, DayHours(22 * 60, 6 * 60)) // Friday nights
        assertTrue(s.isScheduled(clock(6, 5)))
        assertFalse(s.isScheduled(clock(5, 10)))
        assertTrue(s.isScheduled(clock(4, 10)))
        val blocks = WorkHours(s).blocks(fri9 + 1, cal)
        assertEquals(cal.toEpochMs(fri9 + 1, 360), blocks.single().endMs)
        assertTrue(WorkModeRules.scheduledOn(s, HolidayCalendar(mapOf(fri9 + 1 to "Made-up Day")), fri9 + 1, 5 * 60))
        assertFalse(WorkModeRules.scheduledOn(s, HolidayCalendar(mapOf(fri9 to "Made-up Day")), fri9 + 1, 5 * 60))
    }

    @Test
    fun encodingRoundTripsAndTheOldDefaultReadsAsUnset() {
        assertEquals("1,2,3,4,5;540;1050;1;4=540-930", meka.encode())
        assertEquals(meka, WorkSchedule.decode(meka.encode()))
        // Chosen weekdays-alike is kept as chosen: it isn't the bare old default.
        assertEquals("1,2,3,4,5;540;1050;1;", WorkSchedule.WEEKDAYS.encode())
        assertEquals(WorkSchedule.WEEKDAYS, WorkSchedule.decode(WorkSchedule.WEEKDAYS.encode()))
        val other = WorkSchedule(setOf(1, 2), 480, 960)
        assertEquals("1,2;480;960;1", other.encode())
        assertNull(WorkSchedule.decode("1,2;480;960;1;4=oops"))
        assertNull(WorkSchedule.decode("1,2;480;960;1;9=480-960"))

        val world = SyncWorld()
        val fold = world.device("fold"); val mac = world.device("mac")
        val foldWork = WorkMode(fold.replica) { world.clock.nowMs }
        val macWork = WorkMode(mac.replica) { world.clock.nowMs }
        fold.replica.commitLocal(EntityTypes.CONTEXT_MODE, WorkMode.ENTITY_ID, mapOf(WorkFields.SCHEDULE to os.meka.core.sync.FieldValue.Text(WorkSchedule.LEGACY_DEFAULT)))
        assertEquals(WorkSchedule.DEFAULT, foldWork.schedule())
        foldWork.setSchedule(WorkSchedule.WEEKDAYS)
        fold.sync(); mac.sync()
        assertEquals(WorkSchedule.WEEKDAYS, macWork.schedule())
        macWork.setSchedule(macWork.schedule().withDayHours(4, DayHours(540, 930)))
        mac.sync(); fold.sync()
        assertEquals("Mon–Fri · 09:00–17:30 · Thu 09:00–15:30", foldWork.schedule().summary)
    }
}
