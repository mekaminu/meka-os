package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.BlockedCallersView
import os.meka.core.domain.CallAskRules
import os.meka.core.domain.CallLogRules
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem
import os.meka.core.domain.HeldCall
import os.meka.core.domain.LocalCalendar
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Call assistant completeness: Ask "any calls today?" is answered on the device from Activity → Calls, no AI. */
class CallAskFacadeTest {
    private val now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aCallQuestionIsAnsweredFromTheCallLogWithOpenCalls() = runTest {
        val fold = core()
        val empty = fold.askMeka("Any calls today?") as AskOutcome.Answered
        assertEquals("No calls today in your call log.", empty.answer.text)
        assertEquals(CallAskRules.link, empty.answer.help)
        // The Fold's own source: its contacts-named log.
        val missed = CapturedItem("m1", CaptureApp.PHONE, CaptureKind.MISSED_CALL, "Alex", null, null, now - 30 * 60_000)
        fold.callLogSource = { CallLogRules.view(listOf(HeldCall(missed, cleared = false)), BlockedCallersView.EMPTY, now, LocalCalendar.UTC) }
        val one = fold.askMeka("did Alex ring?") as AskOutcome.Answered
        assertTrue(one.answer.text.startsWith("1 call from Alex in the last 7 days."), one.answer.text)
        assertTrue(one.answer.cards.isEmpty())
    }
}
