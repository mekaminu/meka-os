package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AppointmentRules
import os.meka.core.domain.AppointmentState
import os.meka.core.domain.AppointmentView
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Appointment nudges through the facade (slice 1): typed on the Fold, asked about on the Mac, answered on either. */
class AppointmentsFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aDueDentistAsksOnTheMacAndBookingItQuietensBoth() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(AppointmentView.EMPTY.summary, fold.appointmentsView.value.summary)
        assertEquals(6, fold.appointmentsView.value.suggestions.size)
        assertEquals("Dentist · Due Mon 12 Oct", fold.typeAppointment("Dentist every 6 months, last 12 Apr").line)
        assertFalse(fold.addAppointmentPreset("haircut").refused)
        assertEquals("2 appointments · 1 due", fold.appointmentsView.value.summary)
        assertEquals(4, fold.appointmentsView.value.suggestions.size)

        fold.syncNow(); mac.syncNow()
        val card = mac.appointmentsView.value.nudges.single()
        assertEquals("Dentist", card.title)
        assertEquals("Due Mon 12 Oct · every 6 months", card.line)
        assertTrue(assertNotNull(mac.bookAppointment(card.id, "never")).refused)
        val booked = assertNotNull(mac.bookAppointment(card.id, "Tue 20 Oct 9:30"))
        assertEquals("Dentist · Booked · Tue 20 Oct 09:30", booked.line)
        mac.syncNow(); fold.syncNow()
        assertTrue(fold.appointmentsView.value.nudges.isEmpty())
        assertEquals(AppointmentState.BOOKED, fold.appointmentsView.value.rows.first { it.id == card.id }.state)
        assertTrue(fold.undoAppointment(booked))
        assertEquals(1, fold.appointmentsView.value.nudges.size)
        val went = assertNotNull(fold.appointmentWent(card.id))
        assertEquals("Dentist · went today · next due Apr 2027", went.line)
        assertTrue(fold.appointmentsView.value.nudges.isEmpty())
        assertTrue(fold.removeAppointment(card.id))
        assertEquals(AppointmentRules.PRESETS.size - 1, fold.appointmentsView.value.suggestions.size)
    }

    /** Slice 2b: Call to book puts the call on Today (Undo takes it off), Copy email, and the heads-up the day it asks. */
    @Test
    fun callToBookCopyEmailAndTheHeadsUp() = runTest {
        now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London
        val fold = core("android")
        fold.typeAppointment("Dentist every 6 months, last 12 Apr")
        val card = fold.appointmentsView.value.nudges.single()
        assertEquals("Call to book", card.callLabel)
        assertEquals("Copy email", card.emailLabel)

        val email = assertNotNull(fold.appointmentBookingEmail(card.id))
        assertTrue(email.startsWith("Subject: Booking a dental check-up"))
        assertTrue(email.contains("My last visit was on Sunday 12 April 2026."))
        assertTrue(fold.appointmentsView.value.nudges.isNotEmpty()) // copying changes nothing

        val call = assertNotNull(fold.appointmentCallToBook(card.id))
        assertEquals("Call to book: Dentist · planned today at 12:30", call.line)
        val today = fold.today.value
        assertTrue((listOfNotNull(today.upNext) + today.yourDay).any { it.id == call.taskId && it.title == "Call to book: Dentist" })
        assertTrue(fold.appointmentsView.value.nudges.isEmpty()) // asks again two days after the call
        assertTrue(fold.undoAppointmentCall(call))
        assertEquals(1, fold.appointmentsView.value.nudges.size)
        assertTrue((listOfNotNull(fold.today.value.upNext) + fold.today.value.yourDay).none { it.id == call.taskId })

        // Not yet: two weeks later, at 09:00, one heads-up, then the card again.
        fun asks(r: os.meka.core.domain.GovernorResult) = r.post.filter { it.source == os.meka.core.domain.NoticeSource.APPOINTMENTS }
        assertNotNull(fold.appointmentNotYet(card.id))
        assertTrue(asks(fold.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)).isEmpty())
        now += 14 * 86_400_000L // Mon 26 Oct, 09:00 in London (GMT)
        val gov = fold.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        assertEquals(listOf("Dentist · overdue" to "Last went Sun 12 Apr. Booked the next one?"), asks(gov).map { it.title to it.text })
        assertEquals(os.meka.core.domain.NoticeTarget.NEEDS_YOU, asks(gov).single().target)
        assertTrue(asks(fold.governNotifications(gov.stateEncoded, os.meka.core.domain.DeviceAlerts.ALL)).isEmpty()) // once
    }
}
