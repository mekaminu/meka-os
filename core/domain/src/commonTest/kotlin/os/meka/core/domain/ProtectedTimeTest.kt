package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Protected time, slice 1: evenings and weekends the planner and Move to later keep tasks out of. */
class ProtectedTimeTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val cal = LocalCalendar.UTC
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private val sat = d(10, 10)
    private val mon = d(10, 12)
    private val fri = d(10, 16)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private fun window(day: Long) = DayWindow(cal.toEpochMs(day, 0), cal.toEpochMs(day + 1, 0))
    private val nineToFive = WorkSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60 + 30)
    private val both = ProtectedSetting(evenings = true, fromMin = 19 * 60, weekends = true)

    private fun task(id: String, est: Int = 30) =
        Task(id, id, null, Lifecycle.ACTIVE, null, null, est, 0, null, null, 0, null, false)

    @Test
    fun theSettingIsOffUntilTurnedOnAndSyncs() {
        val world = SyncWorld()
        val fold = world.device("android")
        val mac = world.device("mac")
        val pFold = ProtectedTime(fold.replica)
        val pMac = ProtectedTime(mac.replica)
        assertNull(pFold.setting())
        val off = ProtectedRules.view(pFold.setting(), sat, nineToFive, HolidayCalendar.NONE)
        assertEquals(ProtectedRules.OFF_LINE, off.summary)
        assertFalse(off.on)
        assertNull(off.todayLine)
        assertTrue(ProtectedRules.slots(null, sat, nineToFive, HolidayCalendar.NONE, cal).isEmpty())

        assertTrue(pFold.set(evenings = true, fromMin = 19 * 60, weekends = true))
        assertFalse(pFold.set(evenings = true, fromMin = 19 * 60, weekends = true)) // nothing changed
        assertFalse(pFold.set(evenings = true, fromMin = 24 * 60, weekends = true)) // not a time
        fold.sync(); mac.sync()
        assertEquals(both, pMac.setting())
        assertEquals("Evenings from 19:00, weekends and bank holidays · nothing is planned into them", ProtectedRules.summary(pMac.setting()))
        assertTrue(pMac.set(evenings = false, fromMin = 19 * 60, weekends = true))
        mac.sync(); fold.sync()
        assertEquals("Weekends and bank holidays · nothing is planned into them", ProtectedRules.summary(pFold.setting()))
        assertEquals("Evenings from 18:30 · nothing is planned into them", ProtectedRules.summary(ProtectedSetting(true, 18 * 60 + 30, false)))
    }

    @Test
    fun weekendsAndBankHolidaysAreWholeDaysUnlessThereIsAShift() {
        val none = HolidayCalendar.NONE
        assertTrue(ProtectedRules.wholeDay(both, sat, nineToFive, none))
        assertTrue(ProtectedRules.wholeDay(both, sat + 1, nineToFive, none))
        assertFalse(ProtectedRules.wholeDay(both, mon, nineToFive, none))
        assertEquals(listOf(DayPlanner.Slot(at(sat, 0), at(sat + 1, 0))), ProtectedRules.slots(both, sat, nineToFive, none, cal))
        assertEquals("Waits for Mon 12 Oct", ProtectedRules.waitLabel(both, sat, nineToFive, none))
        assertEquals("Waits for tomorrow", ProtectedRules.waitLabel(both, sat + 1, nineToFive, none))
        assertEquals("Today is protected · tasks wait for Mon 12 Oct", ProtectedRules.todayLine(both, sat, nineToFive, none))
        assertEquals("Protected time: today is kept free, so no tasks are planned.", ProtectedRules.planNote(both, sat, nineToFive, none))

        // A bank holiday Monday is protected too, so Saturday's tasks wait for Tuesday.
        val bankMonday = HolidayCalendar(mapOf(mon to "Bank holiday"))
        assertTrue(ProtectedRules.wholeDay(both, mon, nineToFive, bankMonday))
        assertEquals("Waits for Tue 13 Oct", ProtectedRules.waitLabel(both, sat, nineToFive, bankMonday))

        // A Saturday shift keeps that day open; its evening is still protected after the shift.
        val saturdays = WorkSchedule(setOf(1, 2, 3, 4, 5, 6), 9 * 60, 17 * 60 + 30)
        assertFalse(ProtectedRules.wholeDay(both, sat, saturdays, none))
        assertEquals(listOf(DayPlanner.Slot(at(sat, 19), at(sat + 1, 0))), ProtectedRules.slots(both, sat, saturdays, none, cal))
        assertNull(ProtectedRules.waitLabel(both, sat, saturdays, none))

        // Weekends off: Saturday has only its evening.
        val evenings = both.copy(weekends = false)
        assertFalse(ProtectedRules.wholeDay(evenings, sat, nineToFive, none))
        assertEquals(19 * 60, ProtectedRules.eveningFrom(evenings, sat, nineToFive, none))
    }

    @Test
    fun theEveningStartsAtItsTimeOrWhenALateShiftEndsAndANightShiftHasNone() {
        val none = HolidayCalendar.NONE
        assertEquals(19 * 60, ProtectedRules.eveningFrom(both, fri, nineToFive, none))
        assertEquals("Protected from 19:00 tonight", ProtectedRules.todayLine(both, fri, nineToFive, none))
        assertEquals("Protected time: nothing is planned after 19:00.", ProtectedRules.planNote(both, fri, nineToFive, none))
        val late = nineToFive.withDayHours(5, DayHours(12 * 60, 20 * 60))
        assertEquals(20 * 60, ProtectedRules.eveningFrom(both, fri, late, none))
        val night = nineToFive.withDayHours(5, DayHours(22 * 60, 6 * 60))
        assertNull(ProtectedRules.eveningFrom(both, fri, night, none))
        assertTrue(ProtectedRules.slots(both, fri, night, none, cal).isEmpty())
        assertNull(ProtectedRules.planNote(both, fri, night, none))
        assertNull(ProtectedRules.eveningFrom(both.copy(evenings = false), fri, nineToFive, none))
    }

    @Test
    fun planMyDayKeepsTasksOutButLeavesHabitsAndCountsItAsFree() {
        val none = HolidayCalendar.NONE
        // Friday 18:00: an hour before the evening; three half-hour tasks, a 30-minute evening habit.
        val plan = DayPlanner.plan(
            tasks = listOf(task("a"), task("b"), task("c")), events = emptyList(), nowMs = at(fri, 18), day = window(fri),
            habits = listOf(PlannerHabit("read", "Read", 30, HabitTiming.EVENING, behind = false)),
            protected = ProtectedRules.slots(both, fri, nineToFive, none, cal),
            protectedNote = ProtectedRules.planNote(both, fri, nineToFive, none),
            waitLabel = ProtectedRules.waitLabel(both, fri, nineToFive, none),
        )
        // The habit takes 18:00; one task fits before 19:00; the rest won't fit today (the evening is protected).
        assertEquals(listOf(at(fri, 18)), plan.habits.map { it.startMs })
        assertEquals(listOf("a" to at(fri, 18, 30)), plan.placements.map { it.task.id to it.startMs })
        assertEquals("Won't fit today: b, c", plan.unplacedLine)
        assertEquals("Protected time: nothing is planned after 19:00.", plan.keptLine)
        // 19:00–21:00 is still free time (the planner's day ends at 21:00).
        assertEquals(120, plan.freeMinutesLeft)

        // Saturday: nothing is planned; the tasks wait for Monday.
        val weekend = DayPlanner.plan(
            tasks = listOf(task("a")), events = emptyList(), nowMs = at(sat, 9), day = window(sat),
            protected = ProtectedRules.slots(both, sat, nineToFive, none, cal),
            protectedNote = ProtectedRules.planNote(both, sat, nineToFive, none),
            waitLabel = ProtectedRules.waitLabel(both, sat, nineToFive, none),
        )
        assertTrue(weekend.placements.isEmpty())
        assertEquals("Waits for Mon 12 Oct: a", weekend.unplacedLine)
        assertEquals(12 * 60, weekend.freeMinutesLeft)
        assertFalse(weekend.isBlank)

        // Off: the evening is the planner's as before.
        val open = DayPlanner.plan(listOf(task("a"), task("b"), task("c")), emptyList(), at(fri, 18), window(fri))
        assertEquals(3, open.placements.size)
        assertNull(open.keptLine)
        assertNull(open.unplacedLine)
    }

    @Test
    fun moveToLaterNeverLandsInProtectedTime() {
        val t = task("late", 30).copy(scheduledAtMs = at(fri, 17))
        val kept = ProtectedRules.slots(both, fri, nineToFive, HolidayCalendar.NONE, cal)
        assertEquals(at(fri, 18, 30), LateTaskRules.laterSlot(t, listOf(t), emptyList(), emptyList(), at(fri, 18, 20), window(fri), kept = kept))
        assertNull(LateTaskRules.laterSlot(t, listOf(t), emptyList(), emptyList(), at(fri, 18, 45), window(fri), kept = kept))
        assertEquals(at(fri, 18, 45), LateTaskRules.laterSlot(t, listOf(t), emptyList(), emptyList(), at(fri, 18, 45), window(fri)))
    }
}
