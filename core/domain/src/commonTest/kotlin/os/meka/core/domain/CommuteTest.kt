package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The commute counts everywhere, slice 1: the setting, office days and the wake alarm. */
class CommuteTest {
    private val day = CivilDate.DAY_MS
    private val min = 60_000L
    private val cal = LocalCalendar.UTC
    private val thu = 20734L // Thursday 8 October 2026 (the short day, 09:00–15:30)
    private val fri = thu + 1
    private val sat = thu + 2
    private val work = WorkHours(WorkSchedule.DEFAULT)

    private fun ms(d: Long, minute: Int) = d * day + minute * min
    private fun ev(title: String, d: Long, minute: Int) =
        CalendarEvent(title, title, ms(d, minute), ms(d, minute + 30), false, null, "google", null, null)
    private fun wake(d: Long, hours: WorkHours, events: List<CalendarEvent> = emptyList(), buffer: Int = 60) =
        AlarmRules.suggest(AlarmRules.firstCommitment(d, events, hours, cal), buffer)

    @Test
    fun theSettingReadsDefaultsAndRefusesOddTimes() {
        assertEquals(CommuteSetting.DEFAULT, CommuteRules.read(null))
        assertEquals(80, CommuteSetting.DEFAULT.toWorkMin)
        assertEquals("About 1 h 20 each way · change it below", CommuteRules.summary(CommuteSetting.DEFAULT))
        val own = CommuteRules.read(mapOf(CommuteFields.TO_WORK to FieldValue.Int64(75), CommuteFields.HOME to FieldValue.Int64(90)))
        assertEquals(CommuteSetting(75, 90, chosen = true), own)
        assertEquals("1 h 15 there · 1 h 30 home", CommuteRules.summary(own))
        assertEquals("1 h each way", CommuteRules.summary(CommuteSetting(60, 60, chosen = true)))
        // Out of range or not in fives reads as the default.
        assertEquals(80, CommuteRules.read(mapOf(CommuteFields.TO_WORK to FieldValue.Int64(7))).toWorkMin)
        assertEquals(80, CommuteRules.read(mapOf(CommuteFields.HOME to FieldValue.Int64(83))).homeMin)
        assertEquals(listOf(45, 60, 70, 75, 80, 90, 100, 120), CommuteRules.choices(75))
        assertEquals(CommuteRules.CHOICES, CommuteRules.choices(80))
    }

    @Test
    fun onlyOfficeDaysHaveACommute() {
        assertEquals(80, CommuteRules.toWorkOn(work, fri))
        assertEquals(80, CommuteRules.homeOn(work, thu))
        assertNull(CommuteRules.toWorkOn(work, sat), "a day off")
        assertNull(CommuteRules.toWorkOn(WorkHours(WorkSchedule.DEFAULT, homeDays = setOf(fri)), fri), "from home")
        assertNull(CommuteRules.toWorkOn(WorkHours(WorkSchedule.DEFAULT, leaveDays = setOf(fri)), fri), "annual leave")
        assertNull(CommuteRules.toWorkOn(WorkHours(WorkSchedule.DEFAULT, HolidayCalendar(mapOf(fri to "Bank holiday"))), fri))
    }

    @Test
    fun theWakeAlarmCountsTheCommuteOnOfficeDays() {
        // Work at 09:00 − 1 h 20 − 1 h = 06:40, and the line says why.
        val office = wake(fri, work)!!
        assertEquals(6 * 60 + 40, office.minute)
        assertEquals("Work at 09:00 · 1 h 20 commute · 1 h to get ready", office.line)
        // Thursday's short day still starts at 09:00.
        assertEquals(6 * 60 + 40, wake(thu, work)!!.minute)
        // From home: no commute.
        val home = wake(fri, WorkHours(WorkSchedule.DEFAULT, homeDays = setOf(fri)))!!
        assertEquals(8 * 60, home.minute)
        assertEquals("Work at 09:00 · 1 h to get ready", home.line)
        // His own times.
        val longer = WorkHours(WorkSchedule.DEFAULT, commute = CommuteSetting(95, 80, chosen = true))
        assertEquals(6 * 60 + 25, wake(fri, longer)!!.minute)
        // An event at 08:30 at home comes after he must leave (07:40): work still decides.
        assertEquals("Work at 09:00 · 1 h 20 commute · 1 h to get ready", wake(fri, work, listOf(ev("School run", fri, 8 * 60 + 30)))!!.line)
        // An event before he'd leave wins, with no commute in its line.
        val early = wake(fri, work, listOf(ev("Gym", fri, 6 * 60 + 30)))!!
        assertEquals(5 * 60 + 30, early.minute)
        assertEquals("Gym at 06:30 · 1 h to get ready", early.line)
        // Never before 04:00.
        assertEquals(4 * 60, wake(fri, WorkHours(WorkSchedule.DEFAULT, commute = CommuteSetting(240, 80, chosen = true)), buffer = 180)!!.minute)
    }

    @Test
    fun theSetAlarmSaysTheCommuteToo() {
        val world = SyncWorld()
        val fold = world.device("android")
        world.clock.nowMs = ms(thu, 21 * 60)
        val a = Alarms(fold.replica, { world.clock.nowMs }, cal)
        assertEquals("Suggested from Work at 09:00 · 1 h 20 commute · 1 h to get ready", a.wakeView(emptyList(), work).line)
        assertTrue(a.setWake(6 * 60 + 40, "Work at 09:00"))
        assertEquals("Alarm set · Work at 09:00 · 1 h 20 commute, 1 h to get ready", a.wakeView(emptyList(), work).line)
        assertTrue(a.setWake(7 * 60, "Work at 09:00"))
        val later = a.wakeView(emptyList(), work)
        assertTrue(later.suggestionEarlier)
        assertEquals("Alarm set · Work at 09:00 now, so 06:40 gives you 1 h and the 1 h 20 commute", later.line)
    }

    @Test
    fun theCommuteSyncsAndRidesOnTheWorkState() {
        val world = SyncWorld()
        val fold = world.device("android")
        val mac = world.device("mac")
        world.clock.nowMs = ms(thu, 21 * 60)
        val foldWork = WorkMode(fold.replica, nowMs = { world.clock.nowMs })
        val macWork = WorkMode(mac.replica, nowMs = { world.clock.nowMs })
        assertFalse(foldWork.commute().chosen)
        assertFalse(foldWork.setCommute(83, 80), "not in fives")
        assertFalse(foldWork.setCommute(80, 300), "over 4 h")
        assertTrue(foldWork.setCommute(90, 70))
        assertFalse(foldWork.setCommute(90, 70), "no change")
        fold.sync(); mac.sync()
        assertEquals(CommuteSetting(90, 70, chosen = true), macWork.commute())
        val clock = LocalClock(4, 21 * 60)
        assertEquals(CommuteSetting(90, 70, chosen = true), macWork.state(clock, thu).commute)
        // Work hours built from the state carry it, so the wake alarm on the Mac counts 1 h 30.
        assertEquals(90, CommuteRules.toWorkOn(macWork.hours(clock, thu), fri))
        // Setting the default explicitly still counts as set.
        assertTrue(macWork.setCommute(80, 80))
        assertTrue(macWork.commute().chosen)
    }
}
