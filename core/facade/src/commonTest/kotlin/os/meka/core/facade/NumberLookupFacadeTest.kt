package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.CallReason
import os.meka.core.domain.CallSignals
import os.meka.core.domain.CallVerdict
import os.meka.core.domain.NumberVerdict
import os.meka.core.domain.PeopleLists
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.NumberLookupCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spam-call lookup through the facade (2026-10-17): who is asked about, what an answer means, the automatic block. */
class NumberLookupFacadeTest {
    private val now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private inner class Server(service: SyncService) : SyncTransport, NumberLookupApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val asked = mutableListOf<Pair<String, Long>>()
        var answer = NumberLookupCodec.Response(NumberLookupCodec.Response.OK, "scam", 23, true, "an HMRC scam")
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun lookUpNumber(number: String, waitMs: Long): NumberLookupCodec.Response {
            asked += number to waitMs
            if (down) throw TransportException("network error: offline")
            return answer
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val lists = PeopleLists(family = setOf("Jordan")).withNumber("Jordan", "+441632960111")

    @Test
    fun onlyStrangersAreAskedAboutAndEveryFailureIsNoLookup() = runTest {
        val c = core()
        assertTrue(c.callerNeedsLookup("01632 960691", lists, CallSignals.NONE))
        assertFalse(c.callerNeedsLookup("01632 960691", lists, CallSignals(knownContact = true)))
        assertFalse(c.callerNeedsLookup("01632 960691", lists, CallSignals(calledRecently = true)))
        assertFalse(c.callerNeedsLookup("01632 960691", lists, CallSignals(networkName = "Oakfield Police")))
        assertFalse(c.callerNeedsLookup("+441632960111", lists, CallSignals.NONE))
        assertFalse(c.callerNeedsLookup("999", lists, CallSignals.NONE))
        assertFalse(c.callerNeedsLookup(null, lists, CallSignals.NONE))
        assertTrue(c.blockCaller("01632 960692", "Kept ringing"))
        assertFalse(c.callerNeedsLookup("01632 960692", lists, CallSignals.NONE))

        val r = c.numberReputation("+44 1632 960691", 1_500)
        assertEquals(NumberVerdict.SCAM, r!!.verdict)
        assertEquals(23, r.reports)
        assertEquals(listOf("+44 1632 960691" to 1_500L), server.asked)
        assertNull(c.numberReputation("112", 1_500))
        for (state in listOf(NumberLookupCodec.Response.OFF, NumberLookupCodec.Response.PENDING, NumberLookupCodec.Response.FAILED)) {
            server.answer = NumberLookupCodec.Response(state)
            assertNull(c.numberReputation("01632 960691", 1_500), state)
        }
        server.down = true
        assertNull(c.numberReputation("01632 960691", 1_500))
    }

    @Test
    fun aSureScamIsBlockedBeforeItRingsWithUndoAndAfterTheCallOtherwise() = runTest {
        val c = core()
        val rep = c.numberReputation("01632 960691", 1_500)
        val d = c.screenIncomingCall("01632 960691", lists, emptyList(), CallSignals(reputation = rep))
        assertEquals(CallVerdict.BLOCK, d.verdict)
        assertEquals(CallReason.REPORTED_SCAM, d.reason)
        val id = assertNotNull(d.autoBlockId)
        val entry = c.activityView.value.days.flatMap { it.rows }.first { it.id == id }
        assertTrue(entry.canUndo)
        assertEquals("Blocked 01632 960691 · it won't ring again", entry.summary)
        c.undoActivity(id)
        assertTrue(c.blockedCallers.value.rows.isEmpty())

        // The answer came too late to stop the ring: blocked after the call; never twice, never with protection off.
        val after = c.autoBlockReportedCaller("01632 960691", rep, now)
        assertNotNull(after)
        assertEquals(1, c.blockedCallers.value.rows.size)
        assertNull(c.autoBlockReportedCaller("01632 960691", rep, now))
        assertNull(c.autoBlockReportedCaller("01632 960693", rep?.copy(sure = false), now))
        assertTrue(c.setSpamProtection(false))
        assertNull(c.autoBlockReportedCaller("01632 960694", rep, now))

        // The after-call notice names what people reported.
        val offer = c.unknownCallOffer("01632 960695", os.meka.core.domain.CallOutcome.MISSED, 0, now, emptyList(), reputation = rep)
        assertTrue(offer!!.title.endsWith("· reported 23× as an HMRC scam"), offer.title)
    }
}
