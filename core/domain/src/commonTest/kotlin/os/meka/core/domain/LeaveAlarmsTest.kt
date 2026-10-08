package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Alarms, slice 3: a leave-by that rings as an alarm. */
class LeaveAlarmsTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    private val world = SyncWorld()
    // Fixed +1 h (London in October before the clocks go back).
    private val cal = LocalCalendar.fixedOffset(hour)
    private val thu = CivilDate.toEpochDay(2026, 10, 8)
    private fun at(h: Int, m: Int = 0) = cal.toEpochMs(thu, h * 60 + m)

    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun actions(d: Device) = EventActions(d.replica, d.tasks, { world.clock.nowMs }, cal)
    private val eaFold = actions(fold)
    private val eaMac = actions(mac)

    private var events = listOf(dentist())
    private fun alarms(d: Device, ea: EventActions) =
        Alarms(d.replica, { world.clock.nowMs }, cal) { LeaveAlarmRules.alarms(events, ea.marks(), world.clock.nowMs, cal) }
    private val aFold = alarms(fold, eaFold)
    private val aMac = alarms(mac, eaMac)

    init { world.clock.nowMs = at(10) }

    private fun dentist(start: Long = at(14), location: String? = "High St Surgery") =
        CalendarEvent("ev2", "Dentist", start, start + hour, false, location, "google", "meka@gmail.com", "Personal")

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun itRingsAtTheStartLessTheTravelTimeInsteadOfTheHeadsUp() {
        eaFold.setLeaveBy("ev2", 30)
        // Off: the heads-up as before and no alarm.
        assertEquals(1, ReminderRules.notices(events, eaFold.marks(), world.clock.nowMs, cal).size)
        assertNull(aFold.next())

        eaFold.setLeaveAlarm("ev2", true)
        val ring = assertNotNull(aFold.next())
        assertEquals(LeaveAlarmRules.id("ev2", at(14), 30), ring.id)
        assertEquals(at(13, 30), ring.ringAtMs)
        assertEquals("Time to leave", ring.title)
        assertEquals("13:30", ring.timeLabel)
        assertEquals("Dentist at 14:00 · High St Surgery", ring.line)
        assertEquals(AlarmKind.LEAVE, ring.kind)
        assertFalse(ring.opensBrief)
        // The heads-up isn't posted as well; the line says it rings.
        assertTrue(ReminderRules.notices(events, eaFold.marks(), world.clock.nowMs, cal).isEmpty())
        assertEquals("Leave by 13:30 · 30 min away · alarm", ReminderRules.line(dentist(), eaFold.marks(), cal))
        assertTrue(EventDetails.build(dentist(), world.clock.nowMs, cal, eaFold.marks()).leaveAlarm)
        // It isn't one of Today's quick alarm rows.
        assertTrue(aFold.quickItems().isEmpty())
    }

    @Test
    fun itNeedsATravelTimeAndAPlaceAndLeavesHiddenAndAllDayEventsAlone() {
        eaFold.setLeaveAlarm("ev2", true)
        assertNull(aFold.next()) // no travel time yet
        eaFold.setLeaveBy("ev2", 20)
        assertNotNull(aFold.next())
        events = listOf(dentist(location = "https://meet.google.com/abc-defg-hij"))
        assertNull(aFold.next())
        events = listOf(dentist(location = null))
        assertNull(aFold.next())
        events = listOf(dentist())
        eaFold.hide("ev2")
        assertNull(aFold.next())
        eaFold.show("ev2")
        assertNotNull(aFold.next())
        events = listOf(dentist().copy(allDay = true))
        assertNull(aFold.next())
    }

    @Test
    fun movingTheEventOrTheTravelTimeMovesTheAlarm() {
        eaFold.setLeaveBy("ev2", 30)
        eaFold.setLeaveAlarm("ev2", true)
        val first = aFold.next()!!
        events = listOf(dentist(start = at(16)))
        val moved = aFold.next()!!
        assertTrue(first.id != moved.id)
        assertEquals(at(15, 30), moved.ringAtMs)
        eaFold.setLeaveBy("ev2", 45)
        assertEquals(at(15, 15), aFold.next()!!.ringAtMs)
        // Leave by off: nothing rings, though the switch is kept for next time.
        eaFold.setLeaveBy("ev2", 0)
        assertNull(aFold.next())
        assertTrue(eaFold.marks().leaveRingsOf("ev2"))
    }

    @Test
    fun snoozeAndDismissOnOneDeviceEndItOnBoth() {
        eaFold.setLeaveBy("ev2", 30)
        eaFold.setLeaveAlarm("ev2", true)
        sync()
        assertEquals(at(13, 30), aMac.next()!!.ringAtMs) // set on the Fold, rings on the Mac too

        world.clock.nowMs = at(13, 31)
        val id = aFold.next()!!.id
        assertTrue(AlarmRules.ringing(aFold.alarm(id)!!, world.clock.nowMs))
        val snoozed = assertNotNull(aFold.snooze(id))
        assertEquals("Snoozed until 13:40", snoozed.snoozeLine)
        sync()
        assertEquals(at(13, 40), aMac.next()!!.ringAtMs)

        world.clock.nowMs = at(13, 40)
        assertTrue(aMac.dismiss(id))
        sync()
        assertNull(aFold.next())
        assertNull(aMac.next())
        assertFalse(aFold.dismiss(id))
    }

    @Test
    fun aStoredOneIsForgottenOnceItNoLongerMatches() {
        eaFold.setLeaveBy("ev2", 30)
        eaFold.setLeaveAlarm("ev2", true)
        world.clock.nowMs = at(13, 31)
        val id = aFold.next()!!.id
        aFold.snooze(id)
        // Ring as an alarm turned off while snoozed: the snoozed alarm doesn't come back.
        eaFold.setLeaveAlarm("ev2", false)
        assertNull(aFold.next())
        assertNull(aFold.alarm(id))
        assertEquals(1, ReminderRules.notices(events, eaFold.marks(), at(13, 0), cal).size)
    }

    @Test
    fun itIsNeverRungLateAndStopsOnceTheEventStarts() {
        eaFold.setLeaveBy("ev2", 30)
        eaFold.setLeaveAlarm("ev2", true)
        world.clock.nowMs = at(13, 39)
        assertNotNull(aFold.next()) // still within its ten minutes
        world.clock.nowMs = at(13, 41)
        assertNull(aFold.next()) // the phone was off: never rung late
        world.clock.nowMs = at(14, 1)
        assertNull(aFold.next())
    }

    @Test
    fun theSwitchSyncsAndTheLastTapWins() {
        eaFold.setLeaveBy("ev2", 30)
        eaFold.setLeaveAlarm("ev2", true)
        sync()
        assertTrue(eaMac.marks().leaveRingsOf("ev2"))
        world.clock.advance(1_000)
        eaMac.setLeaveAlarm("ev2", false)
        sync()
        assertFalse(eaFold.marks().leaveRingsOf("ev2"))
        assertNull(aFold.next())
    }

    @Test
    fun aLongTitleIsShortenedOnTheRingingScreen() {
        val long = dentist().copy(title = "Quarterly planning offsite with the whole leadership team and partners")
        val note = LeaveAlarmRules.note(long, cal)
        val title = note.substringBefore(" at 14:00")
        assertTrue(title.endsWith("…") && title.length <= 60 && title.startsWith("Quarterly planning offsite"), note)
        assertTrue(note.endsWith(" at 14:00 · High St Surgery"), note)
    }
}
