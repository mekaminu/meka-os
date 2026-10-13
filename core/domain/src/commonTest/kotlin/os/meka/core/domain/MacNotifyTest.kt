package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Notifications never seen, the Mac's half: Health's row, Today's line, the sheet's off line and Send a test. */
class MacNotifyTest {
    private val cal = LocalCalendar.UTC
    // Tue 13 Oct 2026 09:15 UTC.
    private val day = CivilDate.toEpochDay(2026, 10, 13)
    private val now = day * CivilDate.DAY_MS + (9 * 60 + 15) * HealthRules.MIN

    private fun facts(n: NotificationHealth) = HealthFacts(
        DeviceHealth(mac = true, notifications = n), connected = true, lastSyncedMs = now, server = ServerHealth(ServerHealth.PUSH_ON, calls = true, speech = true, atMs = now),
        accounts = emptyList(), ai = null, callAssistantOn = false, nowMs = now,
    )

    @Test
    fun offInSystemSettingsIsRedWithTurnOnAndReachesToday() {
        val n = MacNotifyRules.health(allowed = false, macOff = false, lastPostMs = 0)
        val row = NotifyTrace.healthRow(n, now, cal)!!
        assertEquals(HealthState.BAD, row.state)
        assertEquals(HealthFix.NOTIFICATIONS_ON, row.fix)
        assertEquals("Turn on", row.fixLabel)
        assertEquals("Off for MEKA in System Settings · nothing can reach this Mac", row.line)
        val v = HealthRules.view(facts(n), cal)
        assertTrue(v.critical)
        assertEquals("Notifications · Off for MEKA in System Settings · nothing can reach this Mac", v.todayLine)
    }

    @Test
    fun thisMacOffIsRedWithoutAFixButAllowedAndOnIsATick() {
        val off = NotifyTrace.healthRow(MacNotifyRules.health(true, macOff = true, lastPostMs = 0), now, cal)!!
        assertEquals(HealthState.BAD, off.state)
        assertNull(off.fix)
        assertEquals("This Mac is set to Off in MEKA's Notifications · nothing posts here", off.line)

        // No trace on the Mac is not "hasn't looked yet": a Mac that never posted is still fine.
        val never = NotifyTrace.healthRow(MacNotifyRules.health(true, false, 0), now, cal)!!
        assertEquals(HealthState.OK, never.state)
        assertEquals("On · none sent yet", never.line)
        val sent = NotifyTrace.healthRow(MacNotifyRules.health(true, false, now - 30 * HealthRules.MIN), now, cal)!!
        assertEquals("On · last sent 08:45", sent.line)
        assertNull(HealthRules.view(facts(MacNotifyRules.health(true, false, 0)), cal).todayLine)
    }

    @Test
    fun theMacNeverGetsThePhonesPushRow() {
        val v = HealthRules.view(facts(MacNotifyRules.health(true, false, 0)), cal)
        assertFalse(v.rows.any { it.key == "push" })
        assertTrue(v.rows.any { it.key == "notifications" })
    }

    @Test
    fun theSheetsOffLineSaysWhyNothingArrives() {
        assertEquals("Notifications are off for MEKA in System Settings, so nothing can reach this Mac.", MacNotifyRules.offLine(false, false))
        assertEquals("Notifications are off for MEKA in System Settings, so nothing can reach this Mac.", MacNotifyRules.offLine(false, true))
        assertEquals("This Mac is set to Off below, so MEKA posts nothing here.", MacNotifyRules.offLine(true, true))
        assertNull(MacNotifyRules.offLine(true, false))
    }

    @Test
    fun sendATestOnTheMacIsTheLocalHalfOnly() {
        val sending = MacNotifyRules.testSending()
        assertEquals("On this Mac · sending…", sending.localLine)
        assertFalse(sending.done)
        assertEquals("", sending.pushLine)

        val ok = MacNotifyRules.testView(true)
        assertEquals("On this Mac · arrived ✓", ok.localLine)
        assertTrue(ok.done)
        assertNull(ok.hint)

        val no = MacNotifyRules.testView(false)
        assertEquals("On this Mac · didn't show", no.localLine)
        assertEquals(false, no.localOk)
        assertTrue(no.hint!!.contains("System Settings → Notifications → MEKA"))
    }
}
