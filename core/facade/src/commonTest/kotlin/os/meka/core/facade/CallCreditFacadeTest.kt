package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.CallCredit
import os.meka.core.domain.CallCreditPause
import os.meka.core.domain.CallCreditRules
import os.meka.core.domain.CallCreditState
import os.meka.core.domain.CallReason
import os.meka.core.domain.CallSignals
import os.meka.core.domain.CallVerdict
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.PeopleLists
import os.meka.core.sync.Hlc
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The call assistant's low-balance guard through the facade: what the server writes reaches screening and Needs you. */
class CallCreditFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London
    private val ops = InMemoryServerOpStore()
    private var seq = 0

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    /** What the server's credit watch writes. */
    private fun serverWrites(credit: CallCredit) {
        for ((f, v) in CallCreditRules.fields(credit)) {
            seq++
            ops.append(Op("s$seq", "hh", EntityTypes.CONTEXT_MODE, CallCreditRules.ENTITY_ID, f, v, Hlc(now, seq, "server"), emptyList(), "server"))
        }
    }

    @Test
    fun aPausedAssistantLetsCallsRingAndNeedsYouSaysTopUp() = runTest {
        val c = core()
        c.setCallAssistant(true)
        val lists = PeopleLists()
        serverWrites(CallCredit(CallCreditState.LOW, 420, "GBP", now))
        c.syncNow()
        assertFalse(c.workMode.value.callAssistantPaused)
        assertEquals(CallCreditRules.CARD_ID, c.needsYouStack.value.cards.last().id)
        assertEquals("£4.20 left · top up so callers can leave a message", c.needsYouStack.value.cards.last().why)

        // 23:00, quiet hours: a withheld call goes to the assistant while it has credit…
        now += 13 * 3_600_000L
        assertEquals(CallVerdict.DECLINE, c.screenIncomingCall(null, lists, emptyList(), CallSignals.NONE).verdict)
        // …and rings once it's paused.
        serverWrites(CallCredit(CallCreditState.PAUSED, 12, "GBP", now, CallCreditPause.EMPTY))
        c.syncNow()
        assertTrue(c.workMode.value.callAssistantPaused)
        val d = c.screenIncomingCall(null, lists, emptyList(), CallSignals.NONE)
        assertTrue(d.rings)
        assertEquals(CallReason.PAUSED, d.reason)
        val top = c.needsYouStack.value.cards.first()
        assertEquals("Call assistant paused · Twilio credit", top.title)
        assertEquals(CallCreditRules.TOP_UP_URL, top.link)

        // Switched off: no card, nothing paused.
        c.setCallAssistant(false)
        assertFalse(c.workMode.value.callAssistantPaused)
        assertTrue(c.needsYouStack.value.cards.none { it.id == CallCreditRules.CARD_ID })
    }
}
