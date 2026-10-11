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
}
