package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Notifications never seen, slice 2: Send a test, sending the trace, the server's last push, MEKA's phone setting. */
class NotifyTestTest {
    private val cal = LocalCalendar.UTC
    private val day = CivilDate.toEpochDay(2026, 10, 13)
    private val now = day * CivilDate.DAY_MS + (20 * 60 + 41) * HealthRules.MIN

    @Test
    fun bothHalvesArrivingSayArrived() {
        val start = NotifyTestRun(now)
        val going = NotifyTestRules.view(start, now)
        assertEquals("On this phone · sending…", going.localLine)
        assertEquals("From MEKA's server · asking…", going.pushLine)
        assertFalse(going.done)

        val sent = start.copy(localShown = true, push = PushTestResult.SENT)
        val waiting = NotifyTestRules.view(sent, now + 2_000)
        assertEquals("On this phone · arrived ✓", waiting.localLine)
        assertEquals("From MEKA's server · sent · waiting…", waiting.pushLine)
        assertNull(waiting.pushOk)
        assertFalse(waiting.done)

        val arrived = NotifyTestRules.view(sent.copy(pushArrivedAtMs = now + 2_400), now + 3_000)
        assertEquals("From MEKA's server · arrived in 2 s ✓", arrived.pushLine)
        assertEquals(true, arrived.pushOk)
        assertNull(arrived.hint)
        assertTrue(arrived.done)
        assertEquals("From MEKA's server · arrived in under a second ✓", NotifyTestRules.view(sent.copy(pushArrivedAtMs = now + 300), now + 400).pushLine)
    }

    @Test
    fun aPushThatNeverComesSaysSoAfterAMinuteWithWhatToCheck() {
        val sent = NotifyTestRun(now, localShown = true, push = PushTestResult.SENT)
        val late = NotifyTestRules.view(sent, now + NotifyTestRules.PUSH_WAIT_MS)
        assertEquals("From MEKA's server · sent, but didn't arrive within a minute", late.pushLine)
        assertEquals(false, late.pushOk)
        assertTrue(late.hint!!.contains("Unrestricted"))
        assertTrue(late.done)
    }

    @Test
    fun eachServerAnswerHasItsOwnLineAndHint() {
        fun v(r: PushTestResult) = NotifyTestRules.view(NotifyTestRun(now, localShown = true, push = r), now + 1_000)
        assertEquals("From MEKA's server · push isn't set up on the server", v(PushTestResult.OFF).pushLine)
        assertEquals("From MEKA's server · this phone hasn't registered for push", v(PushTestResult.NO_ADDRESS).pushLine)
        assertTrue(v(PushTestResult.NO_ADDRESS).hint!!.contains("open it again"))
        assertEquals("From MEKA's server · couldn't reach the server", v(PushTestResult.UNREACHABLE).pushLine)
        assertEquals("From MEKA's server · Firebase didn't take it", v(PushTestResult.FAILED).pushLine)
        PushTestResult.entries.filter { it != PushTestResult.SENT }.forEach { assertTrue(v(it).done, it.name) }
        // The wire's words; anything unknown is a failure, never fine.
        assertEquals(PushTestResult.SENT, PushTestResult.of("sent"))
        assertEquals(PushTestResult.NO_ADDRESS, PushTestResult.of("no_address"))
        assertEquals(PushTestResult.UNREACHABLE, PushTestResult.of(null))
        assertEquals(PushTestResult.FAILED, PushTestResult.of("whatever"))
    }

    @Test
    fun aLocalTestThatCannotShowPointsAtAndroidsSettings() {
        val v = NotifyTestRules.view(NotifyTestRun(now, localShown = false), now)
        assertEquals("On this phone · didn't show", v.localLine)
        assertTrue(v.hint!!.contains("Heads-ups"))
        assertFalse(v.done) // still asking the server
        assertEquals("Test · local posted on Heads-ups", NotifyTrace.line(NotifyTraceEntry(now, NotifyTraceKind.TEST, NotifyTestRules.localTrace(true)), cal).substringAfter("20:41 · "))
        assertEquals("push arrived in 3 s", NotifyTestRules.arrivedTrace(now, now + 3_200))
    }

    @Test
    fun theTraceIsSentWhenThereIsSomethingNewAtMostEveryHalfHourOrRightAfterATest() {
        val m = HealthRules.MIN
        assertFalse(NotifyTraceReportRules.due(null, 0, null, null, now))
        assertTrue(NotifyTraceReportRules.due(now, 0, null, null, now))
        assertFalse(NotifyTraceReportRules.due(now, now, null, null, now)) // nothing newer than what was sent
        assertFalse(NotifyTraceReportRules.due(now, now - m, now - 10 * m, null, now))
        assertTrue(NotifyTraceReportRules.due(now, now - m, now - 30 * m, null, now))
        assertTrue(NotifyTraceReportRules.due(now, now - m, now - 10 * m, null, now, force = true))
        assertFalse(NotifyTraceReportRules.due(now, now - m, now - 40 * m, now - 5 * m, now)) // just failed
        assertTrue(NotifyTraceReportRules.due(now, now - m, now - 40 * m, now - 15 * m, now))
    }

    @Test
    fun instantUpdatesSaysTheServersLastPush() {
        val fold = DeviceHealth(mac = false)
        fun row(s: ServerHealth) = HealthRules.view(
            HealthFacts(device = fold, connected = true, lastSyncedMs = now, server = s, accounts = emptyList(), ai = null, callAssistantOn = false, nowMs = now),
            cal,
        ).rows.first { it.key == "push" }
        val on = ServerHealth(ServerHealth.PUSH_ON, calls = true, speech = true, atMs = now)
        assertEquals("On · changes from the Mac arrive in seconds", row(on).line)
        val sent = row(on.copy(lastPushAtMs = now - 3 * HealthRules.MIN, lastPushResult = "sent"))
        assertEquals("On · changes from the Mac arrive in seconds · last push 20:38 · sent", sent.line)
        assertEquals(HealthState.OK, sent.state)
        val failed = row(on.copy(lastPushAtMs = now - 3 * HealthRules.MIN, lastPushResult = "failed"))
        assertEquals("On · last push 20:38 · failed · changes arrive within 15 min until it works", failed.line)
        assertEquals(HealthState.WARN, failed.state)
    }

    @Test
    fun mekasOwnPhoneSettingOffIsRedAndBlocksTheLook() {
        val ch = listOf(NotifyChannelState("meka_heads_up", "Heads-ups", true))
        val row = NotifyTrace.healthRow(NotificationHealth(true, ch, emptyList(), phoneOff = true), now, cal)!!
        assertEquals(HealthState.BAD, row.state)
        assertTrue(row.line.startsWith("This phone is set to Off"))
        val look = NotifyTrace.look(now, allowed = true, posted = 0, digest = false, channelNames = emptyList(), nextLookMs = null, cal = cal, phoneOff = true)
        assertEquals(NotifyTraceKind.BLOCKED, look.kind)
        assertEquals("this phone is set to Off in MEKA's Notifications", look.detail)
    }
}
