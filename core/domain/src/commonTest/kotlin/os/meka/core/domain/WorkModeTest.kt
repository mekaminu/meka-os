package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkModeTest {
    private val weekdays = WorkSchedule.WEEKDAYS // Mon–Fri 09:00–17:30
    private fun at(day: Int, h: Int, m: Int = 0) = LocalClock(day, h * 60 + m)

    @Test
    fun scheduleCoversWeekdayHoursOnly() {
        assertTrue(weekdays.isScheduled(at(1, 9)))
        assertTrue(weekdays.isScheduled(at(5, 17, 29)))
        assertFalse(weekdays.isScheduled(at(5, 17, 30)))
        assertFalse(weekdays.isScheduled(at(1, 8, 59)))
        assertFalse(weekdays.isScheduled(at(6, 12)))
        assertFalse(weekdays.copy(enabled = false).isScheduled(at(1, 10)))
    }

    @Test
    fun nightShiftBelongsToTheDayItStarts() {
        val nights = WorkSchedule(setOf(5), 22 * 60, 6 * 60) // Friday 22:00 → Saturday 06:00
        assertTrue(nights.crossesMidnight)
        assertTrue(nights.isScheduled(at(5, 23)))
        assertTrue(nights.isScheduled(at(6, 5, 59)))
        assertFalse(nights.isScheduled(at(6, 6)))
        assertFalse(nights.isScheduled(at(4, 23)))
        assertFalse(nights.isScheduled(at(1, 3))) // Sunday wasn't a shift start
        assertEquals(at(6, 6), nights.nextChange(at(5, 23)))
    }

    @Test
    fun nextChangeFindsTheNextShiftAcrossTheWeekend() {
        assertEquals(at(1, 17, 30), weekdays.nextChange(at(1, 10)))
        assertEquals(at(2, 9), weekdays.nextChange(at(1, 18)))
        assertEquals(at(1, 9), weekdays.nextChange(at(5, 18)))
        assertEquals(at(1, 9), weekdays.nextChange(at(7, 12)))
        assertNull(weekdays.copy(enabled = false).nextChange(at(1, 10)))
        // A single working day: next week's same day, earlier in the day, still counts.
        assertEquals(at(3, 9), WorkSchedule(setOf(3), 540, 600).nextChange(at(3, 11)))
    }

    @Test
    fun statesReadNaturally() {
        assertEquals("At work until 17:30", WorkModeRules.state(weekdays, null, at(1, 10), 0).line)
        assertEquals("Off work · next shift tomorrow 09:00", WorkModeRules.state(weekdays, null, at(1, 18), 0).line)
        assertEquals("Off work · next shift Mon 09:00", WorkModeRules.state(weekdays, null, at(5, 18), 0).line)
        assertEquals("Off work", WorkModeRules.state(weekdays.copy(enabled = false), null, at(1, 10), 0).line)
    }

    @Test
    fun switchOnBeforeAShiftRunsIntoIt() {
        val now = 1_000_000L
        val on = WorkSwitch(on = true, setAtMs = now, scheduledWhenSet = false)
        val early = WorkModeRules.state(weekdays, on, at(1, 8), now)
        assertTrue(early.atWork)
        assertTrue(early.switchedManually)
        assertEquals("At work until 17:30", early.line) // the shift carries it on; it ends with the shift
        val inShift = WorkModeRules.state(weekdays, on, at(1, 10), now + 2 * 3_600_000L)
        assertTrue(inShift.atWork)
        assertFalse(inShift.switchedManually) // the schedule has taken over
    }

    @Test
    fun switchOffLastsUntilTheShiftWouldHaveEnded() {
        val now = 5_000_000L
        val off = WorkSwitch(on = false, setAtMs = now, scheduledWhenSet = true)
        val s = WorkModeRules.state(weekdays, off, at(2, 11), now)
        assertFalse(s.atWork)
        assertEquals("Off work · next shift tomorrow 09:00", s.line)
        assertTrue(WorkModeRules.state(weekdays, off, at(2, 18), now + 7 * 3_600_000L).let { !it.atWork && !it.switchedManually })
        assertTrue(WorkModeRules.state(weekdays, off, at(3, 9), now + 22 * 3_600_000L).atWork) // next morning: schedule again
    }

    @Test
    fun switchNeverOutlivesSixteenHours() {
        val off = weekdays.copy(enabled = false)
        val on = WorkSwitch(true, 0, false)
        assertTrue(WorkModeRules.state(off, on, at(6, 10), WorkSwitch.MAX_MS - 1).atWork)
        assertFalse(WorkModeRules.state(off, on, at(6, 10), WorkSwitch.MAX_MS).atWork)
    }

    @Test
    fun encodingRoundTripsAndRejectsJunk() {
        val s = WorkSchedule(setOf(2, 4), 480, 1020, false)
        assertEquals(s, WorkSchedule.decode(s.encode()))
        assertEquals(WorkSchedule(emptySet(), 0, 0, true), WorkSchedule.decode(";0;0;1"))
        assertNull(WorkSchedule.decode("1,2;abc;3;1"))
        assertNull(WorkSchedule.decode("9;1;2;1")) // day 9 fails validation
        assertNull(WorkSchedule.decode(null))
        val w = WorkSwitch(false, 123L, true)
        assertEquals(w, WorkSwitch.decode(w.encode()))
        assertNull(WorkSwitch.decode("MAYBE;1;1"))
    }

    @Test
    fun daysDescribeCompactly() {
        assertEquals("Mon–Fri · 09:00–17:30", weekdays.summary)
        assertEquals("Mon, Wed, Fri", WorkSchedule.describeDays(setOf(1, 3, 5)))
        assertEquals("Mon, Tue, Thu–Sat", WorkSchedule.describeDays(setOf(1, 2, 4, 5, 6)))
        assertEquals("Every day", WorkSchedule.describeDays((1..7).toSet()))
        assertEquals("No set hours", weekdays.copy(enabled = false).summary)
    }

    @Test
    fun switchAndScheduleSyncBetweenDevices() {
        val world = SyncWorld()
        val fold = world.device("fold"); val mac = world.device("mac")
        val foldWork = WorkMode(fold.replica) { world.clock.nowMs }
        val macWork = WorkMode(mac.replica) { world.clock.nowMs }
        assertEquals(WorkSchedule.DEFAULT, macWork.schedule())

        macWork.setSchedule(WorkSchedule(setOf(1, 2, 3, 4), 8 * 60, 16 * 60))
        macWork.setSwitch(true, at(5, 10)) // Friday is now a day off; Meka switches work on anyway
        mac.sync(); fold.sync()
        assertEquals("Mon–Thu · 08:00–16:00", foldWork.schedule().summary)
        assertTrue(foldWork.state(at(5, 10)).atWork)

        foldWork.setSwitch(false, at(5, 11)) // matches the schedule: just back to schedule
        assertNull(foldWork.currentSwitch())
        fold.sync(); mac.sync()
        assertFalse(macWork.state(at(5, 11)).atWork)
    }

    @Test
    fun choosingWhatTheScheduleSaysWritesNothing() {
        val d = SyncWorld().device("fold")
        val work = WorkMode(d.replica) { 0L }
        val before = d.store.opCount
        work.setSwitch(true, at(1, 10)) // already at work by schedule
        work.backToSchedule()
        assertEquals(before, d.store.opCount)
    }
}
