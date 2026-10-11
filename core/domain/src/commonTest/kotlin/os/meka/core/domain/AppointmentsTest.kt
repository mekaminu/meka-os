package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Appointment nudges, slice 1: typed once, due from the last visit, asked about once in Needs you, synced. */
class AppointmentsTest {
    private val today = CivilDate.toEpochDay(2026, 10, 11) // Sun 11 Oct 2026
    private fun day(m: Int, d: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, d)

    @Test
    fun aTypedLineIsReadForWhatHowOftenAndWhen() {
        val p = AppointmentRules.parse("Dentist every 6 months, last 12 Apr", today, 600)!!
        assertEquals("Dentist", p.title)
        assertEquals(0, p.everyWeeks); assertEquals(6, p.everyMonths)
        assertEquals(day(4, 12), p.lastDay)
        assertNull(p.problem)
        val h = AppointmentRules.parse("haircut every 6 weeks last 26 Sep", today, 600)!!
        assertEquals("Haircut", h.title); assertEquals(6, h.everyWeeks); assertEquals(day(9, 26), h.lastDay)
        // "last March" is its 1st; a date with no year is the latest one not after today ("12 Dec" was last year).
        assertEquals(day(3, 1), AppointmentRules.parse("optician last March", today, 600)!!.lastDay)
        assertEquals(day(12, 12, 2025), AppointmentRules.pastDay("12 Dec", today))
        assertEquals(today - 21, AppointmentRules.pastDay("3 weeks ago", today))
        assertEquals(day(4, 12), AppointmentRules.pastDay("12/4", today))
        assertNull(AppointmentRules.pastDay("12 Oct 2026", today))
        assertEquals(0 to 24, AppointmentRules.interval("every other year"))
        assertEquals(0 to 12, AppointmentRules.interval("yearly"))
        assertEquals(2 to 0, AppointmentRules.interval("every fortnight"))
        assertNull(AppointmentRules.interval("soon"))
        val b = AppointmentRules.parse("dentist booked Tue 20 Oct 9:30", today, 600)!!
        assertEquals(day(10, 20), b.bookedDay); assertEquals(9 * 60 + 30, b.bookedMinute)
        assertEquals(AppointmentRules.BAD_LAST, AppointmentRules.parse("dentist last whenever", today, 600)!!.problem)
        assertEquals(AppointmentRules.BAD_BOOKED, AppointmentRules.parse("dentist booked 3 Oct 2026", today, 600)!!.problem)
        assertNull(AppointmentRules.parse("  ", today, 600))
        assertEquals("Dentist", AppointmentRules.parse("dentist", today, 600)!!.title)
        assertEquals("dentist", AppointmentRules.key("Dental check-up"))
        assertEquals("Every 6 weeks", AppointmentRules.intervalLabel(6, 0))
        assertEquals("Every 2 years", AppointmentRules.intervalLabel(0, 24))
        assertEquals(day(2, 28, 2027), AppointmentRules.addMonths(day(8, 31), 6))
    }

    @Test
    fun dueComesFromTheLastVisitAndOnlyDueOnesAsk() {
        fun item(id: String, w: Int, m: Int, last: Long?, booked: Long? = null, later: Long? = null) =
            AppointmentItem(id, id.replaceFirstChar { it.uppercase() }, w, m, last, booked, if (booked != null) 570 else null, later, 0)
        val dentist = item("dentist", 0, 6, day(4, 12)) // due 12 Oct: tomorrow
        val haircut = item("haircut", 6, 0, day(8, 1)) // due 12 Sep: overdue
        val optician = item("optician", 0, 24, day(3, 1)) // due Mar 2028
        val gp = item("gp", 0, 12, null)
        val car = item("car", 0, 12, day(10, 1, 2025), booked = day(10, 20))
        val barber = item("barber", 4, 0, day(8, 1), later = today + 3)
        assertEquals(AppointmentState.DUE, AppointmentRules.state(dentist, today))
        assertEquals("Due tomorrow", AppointmentRules.status(dentist, today))
        assertEquals("Overdue · was due Sat 12 Sep", AppointmentRules.status(haircut, today))
        assertEquals("Next due Mar 2028", AppointmentRules.status(optician, today))
        assertEquals(AppointmentState.UNKNOWN, AppointmentRules.state(gp, today))
        assertEquals("Booked · Tue 20 Oct 09:30", AppointmentRules.status(car, today))
        assertEquals("Not yet · asks again Wed 14 Oct", AppointmentRules.status(barber, today))
        assertEquals("Every 6 months · last went Sun 12 Apr", AppointmentRules.line(dentist, today))
        // A booked day that has gone counts as the visit.
        assertEquals(day(10, 20), AppointmentRules.lastVisit(car, day(10, 21)))
        assertEquals("Next due Oct 2027", AppointmentRules.status(car, day(10, 21)))

        val v = AppointmentRules.view(listOf(optician, gp, car, dentist, haircut, barber), today)
        assertEquals(listOf("barber", "haircut", "dentist", "gp", "car", "optician"), v.rows.map { it.id })
        assertEquals(listOf("haircut", "dentist"), v.nudges.map { it.id })
        assertEquals("6 appointments · 2 due", v.summary)
        val n = v.nudges[1]
        assertEquals("Due tomorrow · every 6 months", n.line)
        assertEquals("Last went Sun 12 Apr. Booked the next one?", n.question)
        assertTrue(v.rows.first { it.id == "gp" }.lit)
        assertFalse(v.rows.first { it.id == "barber" }.lit)
        // Suggestions leave out what's already there (the dentist, the haircut, the optician and the GP).
        assertEquals(listOf("hygienist", "car-service"), v.suggestions.map { it.id })
        assertEquals(AppointmentRules.EMPTY_SUMMARY, AppointmentRules.view(emptyList(), today).summary)
    }

    private val world = SyncWorld()
    private var n = 0
    private fun appts(dev: Device) = Appointments(dev.replica, { "a${n++}" }, { world.clock.nowMs })

    @Test
    fun typedOnOneDeviceAnsweredOnTheOtherAndUndone() {
        val fold = world.device("android")
        val mac = world.device("mac")
        val aFold = appts(fold)
        val aMac = appts(mac)
        val now = LocalCalendar.UTC.epochDayOf(world.clock.nowMs)
        assertTrue(aFold.type("Physio").refused) // a new one needs how often
        assertEquals(AppointmentRules.NO_INTERVAL, aFold.type("Physio last 2 Mar").line)
        assertTrue(aFold.type("last 12 Apr").refused)
        // A common one knows its interval; a later line adds the last visit to the same appointment.
        assertFalse(aFold.addPreset("dentist").refused)
        assertEquals(AppointmentState.UNKNOWN, aFold.view().rows.single().state)
        val said = aFold.type("dental check-up 7 months ago")
        assertTrue(said.refused) // no "last": nothing new to read
        val last = AppointmentRules.addMonths(now, -7)
        assertFalse(aFold.type("dentist last went ${CivilDate.fromEpochDay(last).let { "${it.day}/${it.month}/${it.year}" }}").refused)
        assertEquals(1, aFold.items().size)
        assertEquals(1, aFold.view().nudges.size)

        fold.sync(); mac.sync()
        val id = aMac.view().nudges.single().id
        val notYet = assertNotNull(aMac.notYet(id))
        assertTrue(aMac.view().nudges.isEmpty())
        assertTrue(aMac.undo(notYet))
        assertEquals(1, aMac.view().nudges.size)
        assertTrue(assertNotNull(aMac.book(id, "whenever")).refused)
        val booked = assertNotNull(aMac.book(id, "tomorrow 9am"))
        assertFalse(booked.refused)
        assertEquals(AppointmentState.BOOKED, aMac.view().rows.single().state)
        mac.sync(); fold.sync()
        assertTrue(aFold.view().nudges.isEmpty())
        val went = assertNotNull(aFold.went(id))
        assertTrue(went.line.startsWith("Dentist · went today · next due "))
        assertEquals(now, aFold.items().single().lastDay)
        assertNull(aFold.items().single().bookedDay)
        assertTrue(aFold.undo(went))
        assertEquals(AppointmentState.BOOKED, aFold.view().rows.single().state)
        assertTrue(aFold.remove(id))
        fold.sync(); mac.sync()
        assertTrue(aMac.items().isEmpty())
        assertFalse(aMac.remove(id))
    }

    /** Slice 2b: when Call to book plans the call, the booking email, and the heads-up the day one starts asking. */
    @Test
    fun callToBookTheEmailAndTheHeadsUp() {
        val mon = day(10, 12)
        assertEquals(mon to 12 * 60 + 30, AppointmentRules.callWhen(today, 600)) // Sunday → Monday lunchtime
        assertEquals(mon to 12 * 60 + 30, AppointmentRules.callWhen(mon, 600))
        assertEquals(mon to 14 * 60 + 15, AppointmentRules.callWhen(mon, 14 * 60 + 7)) // afternoon: the next quarter hour
        assertEquals(mon + 1 to 12 * 60 + 30, AppointmentRules.callWhen(mon, 16 * 60 + 50))
        assertEquals(day(10, 19) to 12 * 60 + 30, AppointmentRules.callWhen(day(10, 16), 17 * 60)) // Friday evening → Monday

        val dentist = AppointmentItem("d", "Dentist", 0, 6, day(5, 1), null, null, null, 0) // due Sun 1 Nov
        val plan = AppointmentRules.callPlan(dentist, today, 600)
        assertEquals("Call to book: Dentist", plan.title)
        assertEquals("Call to book: Dentist · planned tomorrow at 12:30", plan.line)
        assertEquals(mon + AppointmentRules.CALL_LATER_DAYS, plan.laterDay)

        val email = AppointmentRules.bookingEmail(dentist, today)
        assertTrue(email.startsWith("Subject: Booking a dental check-up\n\nHello,"))
        assertTrue(email.contains("My last visit was on Friday 1 May 2026. Could you let me know your next available times?"))
        assertTrue(email.endsWith("Meka"))
        assertEquals("an eye test", AppointmentRules.bookingWhat("Optician"))
        assertEquals("an appointment (Physio)", AppointmentRules.bookingWhat("Physio"))
        assertTrue(!AppointmentRules.bookingEmail(dentist.copy(lastDay = null), today).contains("last visit"))

        // The heads-up: 09:00 on the day it starts asking (two weeks before), once per asking day, into Needs you.
        val cal = LocalCalendar.UTC
        val from = day(10, 18)
        val n = AppointmentRules.notices(listOf(dentist), cal.toEpochMs(today, 600), cal).single()
        assertEquals(NoticeSource.APPOINTMENTS, n.source)
        assertEquals("Dentist · due Sun 1 Nov", n.title)
        assertEquals("Last went Fri 1 May. Booked the next one?", n.text)
        assertEquals(cal.toEpochMs(from, AppointmentRules.NOTICE_MIN), n.atMs)
        assertEquals(NoticeTarget.NEEDS_YOU, n.target)
        // Booked, or already asking since before today (the card is there): no heads-up.
        assertTrue(AppointmentRules.notices(listOf(dentist.copy(bookedDay = day(10, 30), bookedMinute = 600)), cal.toEpochMs(today, 600), cal).isEmpty())
        assertTrue(AppointmentRules.notices(listOf(dentist), cal.toEpochMs(from + 1, 600), cal).isEmpty())
        // Not yet on an overdue one: it asks again when the two weeks are up.
        val overdue = dentist.copy(lastDay = day(3, 1), laterDay = today + 14)
        val again = AppointmentRules.notices(listOf(overdue), cal.toEpochMs(today, 600), cal).single()
        assertEquals("Dentist · overdue", again.title)
        assertEquals(cal.toEpochMs(today + 14, AppointmentRules.NOTICE_MIN), again.atMs)
        // Through the governor: posted once.
        val first = Governor.evaluate(listOf(n), NotificationSettings.DEFAULT, DeviceAlerts.ALL, GovernorState(), cal.toEpochMs(from, 9 * 60 + 5), cal)
        assertEquals(listOf("Dentist · due Sun 1 Nov"), first.post.map { it.title })
        assertTrue(Governor.evaluate(listOf(n), NotificationSettings.DEFAULT, DeviceAlerts.ALL, first.state, cal.toEpochMs(from, 9 * 60 + 30), cal).post.isEmpty())
    }
}
