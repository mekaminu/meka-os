package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Alarms, slice 1: the smart wake alarm. */
class AlarmsTest {
    private val world = SyncWorld()
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val day = CivilDate.DAY_MS
    private val min = 60_000L
    private val cal = LocalCalendar.UTC
    private val thu = 20734L // Thursday 8 October 2026
    private val fri = thu + 1
    private val sat = thu + 2
    private val work = WorkHours(WorkSchedule.DEFAULT)

    private fun ms(d: Long, minute: Int) = d * day + minute * min
    private fun at(d: Long, minute: Int) { world.clock.nowMs = ms(d, minute) }
    private fun alarms(dev: Device) = Alarms(dev.replica, { world.clock.nowMs }, cal)
    private fun ev(title: String, d: Long, minute: Int, allDay: Boolean = false) =
        CalendarEvent(title, title, ms(d, minute), ms(d, minute + 30), allDay, null, "google", null, null)

    @Test
    fun theWakeTimeIsTheFirstCommitmentLessTheBuffer() {
        // Friday is a work day: work at 09:00 less an hour.
        val s = AlarmRules.suggest(AlarmRules.firstCommitment(fri, emptyList(), work, cal), 60)!!
        assertEquals(8 * 60, s.minute)
        assertEquals("Work at 09:00 · 1 h to get ready", s.line)
        // An earlier event wins over work, and the time rounds down to five minutes.
        val standup = AlarmRules.firstCommitment(fri, listOf(ev("Standup", fri, 8 * 60 + 7)), work, cal)!!
        assertEquals("Standup at 08:07", standup.line)
        assertEquals(7 * 60 + 5, AlarmRules.suggest(standup, 60)!!.minute)
        assertEquals("Standup at 08:07 · 1 h 15 to get ready", AlarmRules.suggest(standup, 75)!!.line)
        // All-day entries and things before 04:00 don't count; never before 04:00.
        val sat0 = AlarmRules.firstCommitment(sat, listOf(ev("Holiday", sat, 0, allDay = true), ev("Late film", sat, 60)), work, cal)
        assertNull(sat0)
        val early = AlarmRules.firstCommitment(sat, listOf(ev("Flight", sat, 4 * 60 + 30)), work, cal)
        assertEquals(4 * 60, AlarmRules.suggest(early, 90)!!.minute)
        // A Saturday with nothing on: no suggestion.
        assertNull(AlarmRules.suggest(AlarmRules.firstCommitment(sat, emptyList(), work, cal), 60))
    }

    @Test
    fun bankHolidaysAndDaysOffHaveNoWorkStart() {
        val holidays = HolidayCalendar(mapOf(fri to "Bank holiday"))
        assertNull(AlarmRules.firstCommitment(fri, emptyList(), WorkHours(WorkSchedule.DEFAULT, holidays), cal))
    }

    @Test
    fun theEveningViewSuggestsThenSaysItIsSet() {
        at(thu, 21 * 60 + 30)
        val a = alarms(fold)
        val events = listOf(ev("Standup", fri, 8 * 60))
        val v = a.wakeView(events, work)
        assertEquals(fri, v.epochDay)
        assertEquals("Tomorrow · Fri 9 Oct", v.dayLabel)
        assertFalse(v.isSet)
        assertEquals("07:00", v.timeLabel)
        assertEquals("Suggested from Standup at 08:00 · 1 h to get ready", v.line)

        assertTrue(a.setWake(v.minute, v.suggestion?.commitment?.line))
        val set = a.wakeView(events, work)
        assertTrue(set.isSet)
        assertEquals("Alarm set · Standup at 08:00, 1 h to get ready", set.line)
        assertFalse(set.suggestionEarlier)

        // A new 07:30 meeting: the alarm stays, the view offers the earlier time.
        val moved = a.wakeView(events + ev("Early call", fri, 7 * 60 + 30), work)
        assertEquals("07:00", moved.timeLabel)
        assertTrue(moved.suggestionEarlier)
        assertEquals("Use 06:30", moved.useSuggestionLabel)
        assertEquals("Alarm set · Early call at 07:30 now, so 06:30 gives you 1 h", moved.line)

        // The buffer is Meka's to choose; outside 15 min–3 h is refused.
        assertTrue(a.setBuffer(45))
        assertEquals(7 * 60 + 15, a.wakeView(events, work).suggestion!!.minute)
        assertFalse(a.setBuffer(200))
        assertFalse(a.setBuffer(47))
        assertEquals(45, a.bufferMin())

        a.wakeOff()
        assertFalse(a.wakeView(events, work).isSet)
        assertNull(a.next())
    }

    @Test
    fun nothingEarlySaysSoAndAfterMidnightItIsThisMorning() {
        at(fri, 22 * 60)
        val v = alarms(fold).wakeView(emptyList(), work)
        assertEquals(sat, v.epochDay)
        assertEquals(AlarmRules.NOTHING_EARLY, v.line)
        assertEquals("07:00", v.timeLabel)

        at(sat, 30)
        val a = alarms(fold)
        val late = a.wakeView(emptyList(), work)
        assertEquals(sat, late.epochDay)
        assertEquals("This morning · Sat 10 Oct", late.dayLabel)
        assertEquals("Nothing early this morning · set one if you like", late.line)
        assertTrue(a.setWake(8 * 60, null))
        assertEquals("Alarm set · rings in 7 h 30", a.wakeView(emptyList(), work).line)
        // A time already gone is refused.
        assertFalse(a.setWake(0, null))
        assertFalse(a.setWake(24 * 60, null))
    }

    @Test
    fun itRingsSnoozesAndIsDismissedOnBothDevices() {
        at(thu, 22 * 60)
        val a = alarms(fold)
        assertTrue(a.setWake(6 * 60 + 45, "Standup at 08:00"))
        fold.sync(); mac.sync()
        val next = alarms(mac).next()!!
        assertEquals(AlarmRules.wakeId(fri), next.id)
        assertEquals(ms(fri, 6 * 60 + 45), next.ringAtMs)
        assertEquals("06:45", next.timeLabel)
        assertEquals("Standup at 08:00", next.line)

        // Too early to snooze; at the time it rings.
        assertNull(a.snooze(next.id))
        at(fri, 6 * 60 + 45)
        assertTrue(AlarmRules.ringing(a.alarm(next.id)!!, world.clock.nowMs))
        val snoozed = a.snooze(next.id)!!
        assertEquals(ms(fri, 6 * 60 + 54), snoozed.ringAtMs)
        assertEquals("Snoozed until 06:54", snoozed.snoozeLine)
        assertEquals("06:45", snoozed.timeLabel)
        assertFalse(AlarmRules.ringing(a.alarm(next.id)!!, world.clock.nowMs))

        // It rings again at 06:54; Dismiss on the Mac stops it on the Fold.
        at(fri, 6 * 60 + 54)
        fold.sync(); mac.sync()
        assertEquals(ms(fri, 6 * 60 + 54), alarms(mac).next()!!.ringAtMs)
        assertTrue(alarms(mac).dismiss(next.id))
        assertFalse(alarms(mac).dismiss(next.id))
        mac.sync(); fold.sync()
        assertNull(a.next())
        assertFalse(AlarmRules.ringing(a.alarm(next.id)!!, world.clock.nowMs))
    }

    @Test
    fun anUnansweredAlarmStopsAfterTenMinutesAndNeverRingsLate() {
        at(thu, 22 * 60)
        val a = alarms(fold)
        a.setWake(7 * 60, null)
        at(fri, 7 * 60 + 9)
        assertNotNull(a.next())
        assertEquals("Good morning", a.next()!!.line)
        at(fri, 7 * 60 + 10)
        assertNull(a.next())
        assertNull(a.snooze(AlarmRules.wakeId(fri)))
    }

    @Test
    fun settingAgainClearsTheSnoozeAndDismissal() {
        at(thu, 22 * 60)
        val a = alarms(fold)
        a.setWake(6 * 60, null)
        a.dismiss(AlarmRules.wakeId(fri))
        assertFalse(a.wakeView(emptyList(), work).isSet)
        a.setWake(6 * 60 + 30, null)
        val v = a.wakeView(emptyList(), work)
        assertTrue(v.isSet)
        assertEquals("06:30", v.timeLabel)
        assertEquals(ms(fri, 6 * 60 + 30), a.next()!!.ringAtMs)
        assertEquals(6 * 60 + 35, AlarmRules.step(v.minute, 1))
        assertEquals(23 * 60 + 55, AlarmRules.step(0, -1))
    }
}
