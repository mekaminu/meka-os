package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.ActivityKind
import os.meka.core.domain.CallReason
import os.meka.core.domain.CallSignals
import os.meka.core.domain.CallVerdict
import os.meka.core.domain.PeopleLists
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spam call protection through the facade (call assistant polish 8b): the block list, quiet hours and Activity. */
class CallSpamFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())),
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val lists = PeopleLists(family = setOf("Jeanette")).withNumber("Jeanette", "+447700900111")

    @Test
    fun theSeededScamNumberIsBlockedAndLoggedOnce() = runTest {
        val c = core()
        assertTrue(c.seedBlockList())
        assertFalse(c.seedBlockList())
        assertEquals(listOf("01904 618691"), c.blockedCallers.value.rows.map { it.number })

        val d = c.screenIncomingCall("+441904618691", lists, emptyList(), CallSignals.NONE)
        assertEquals(CallVerdict.BLOCK, d.verdict)
        c.screenIncomingCall("+441904618691", lists, emptyList(), CallSignals.NONE)
        val rows = c.activityView.value.days.flatMap { it.rows }.filter { it.kind == ActivityKind.SCREENED }
        assertEquals(listOf("Blocked a call from 01904 618691"), rows.map { it.summary })
        assertEquals("Why: It is on your block list", rows.single().why)
        assertFalse(rows.single().canUndo)

        // Unblocked, it rings like anyone else (the assistant is off).
        assertTrue(c.unblockCaller(c.blockedCallers.value.rows.single().key))
        assertEquals(CallReason.SWITCHED_OFF, c.screenIncomingCall("01904 618691", lists, emptyList(), CallSignals.NONE).reason)
        assertEquals(0, c.blockedCallers.value.rows.size)
    }

    @Test
    fun withTheAssistantOnSpamAndWithheldCallsInQuietHoursGoToTheAssistant() = runTest {
        val c = core()
        c.setCallAssistant(true)
        assertTrue(c.blockCaller("07700 900555", "Kept ringing"))
        assertFalse(c.blockCaller("abc", null))
        assertEquals(CallReason.LIKELY_SPAM, c.screenIncomingCall("01632 960000", lists, emptyList(), CallSignals(verificationFailed = true)).reason)
        // Jeanette is family: never treated as spam.
        assertTrue(c.screenIncomingCall("07700900111", lists, emptyList(), CallSignals(verificationFailed = true)).rings)
        // A withheld number rings by day (a Saturday, off work)…
        assertTrue(c.screenIncomingCall(null, lists, emptyList(), CallSignals.NONE).rings)
        // …and goes to the assistant at 23:00, in the default quiet hours (22:00–07:00).
        now += 13 * 3_600_000L
        assertEquals(CallReason.WITHHELD_QUIET, c.screenIncomingCall(null, lists, emptyList(), CallSignals.NONE).reason)
        assertEquals(CallVerdict.BLOCK, c.screenIncomingCall("+447700900555", lists, emptyList(), CallSignals.NONE).verdict)
        assertTrue(c.activityView.value.weekLine.contains("3 calls stopped"), c.activityView.value.weekLine)
    }
}
