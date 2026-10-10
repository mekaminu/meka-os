package os.meka.backend

import os.meka.core.domain.CallCreditPause
import os.meka.core.domain.CallCreditReading
import os.meka.core.domain.CallCreditState
import os.meka.core.domain.CallCreditStore
import os.meka.core.domain.MekaSchema
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The call assistant's low-balance guard on the server: the credit read, written as server ops, read by the Fold. */
class CallCreditWatchTest {
    private var nowMs = 1_791_540_000_000L
    private val hour = 60 * 60_000L
    private val ops = InMemoryServerOpStore()
    private val woken = mutableListOf<String>()
    private var configured = true
    private var next: CallCreditReading? = CallCreditReading(true, "19.08", "GBP")
    private val source = object : CreditSource {
        override fun creditConfigured() = configured
        override fun readCredit() = next
    }
    private val watch = CallCreditWatch(ops, CallCreditWatch.scanning(ops), { "hh" }, source, { nowMs }, onWritten = { woken += it })

    /** Meka's Fold after pulling everything the server holds. */
    private fun fold(): CallCreditStore {
        var n = 0
        val replica = Replica("hh", "fold", HlcClock("fold", { nowMs }), InMemoryReplicaStore(), MekaSchema) { "fold${n++}" }
        replica.applyRemoteBatch(ops.after("hh", 0, 1000).map { it.op })
        return CallCreditStore(replica)
    }

    @Test
    fun theBalanceReachesTheFoldAndOnlyChangesAreWritten() {
        assertTrue(watch.check())
        assertEquals(listOf("hh"), woken)
        val ok = assertNotNull(fold().current())
        assertEquals(CallCreditState.OK, ok.state)
        assertEquals(1908L, ok.pence)
        assertEquals("GBP", ok.currency)
        val written = ops.after("hh", 0, 1000).size
        // Read again with nothing changed: nothing written, nobody woken.
        nowMs += 6 * hour
        assertFalse(watch.check())
        assertEquals(written, ops.after("hh", 0, 1000).size)
        assertEquals(1, woken.size)
        // Down to £4.20: the amount, the state and its time change, each over the field's head.
        next = CallCreditReading(true, "4.20", "GBP")
        assertTrue(watch.check())
        val low = assertNotNull(fold().current())
        assertEquals(CallCreditState.LOW, low.state)
        assertEquals(420L, low.pence)
        assertEquals(nowMs, low.sinceMs)
        val newOps = ops.after("hh", 0, 1000).drop(written).map { it.op }
        assertEquals(setOf("state", "pence", "since"), newOps.map { it.field }.toSet())
        assertTrue(newOps.all { it.baseOpIds.size == 1 && it.opId.startsWith("srvcredit") })
        // Empty: paused.
        next = CallCreditReading(true, "0.12", "GBP")
        assertTrue(watch.check())
        assertTrue(fold().current()!!.paused)
        assertEquals(CallCreditPause.EMPTY, fold().current()!!.reason)
    }

    @Test
    fun failedReadsKeepTheLastStateForADayThenPause() {
        assertTrue(watch.check())
        next = null
        nowMs += hour
        assertFalse(watch.check())
        assertTrue(watch.failing)
        nowMs += 24 * hour - 1 // a day after the first failed read, less a millisecond
        assertFalse(watch.check())
        assertEquals(CallCreditState.OK, fold().current()!!.state)
        nowMs += 1
        assertTrue(watch.check())
        val paused = fold().current()!!
        assertTrue(paused.paused)
        assertEquals(CallCreditPause.UNREADABLE, paused.reason)
        assertNull(paused.pence)
        // Readable again: back to fine at once.
        next = CallCreditReading(true, "19.08", "GBP")
        assertTrue(watch.check())
        assertFalse(watch.failing)
        assertEquals(CallCreditState.OK, fold().current()!!.state)
        // A suspended account pauses straight away.
        next = CallCreditReading(false, null, null)
        assertTrue(watch.check())
        assertEquals(CallCreditPause.SUSPENDED, fold().current()!!.reason)
    }

    @Test
    fun nothingIsReadOrWrittenUntilTheAccountIsSetUp() {
        configured = false
        assertFalse(watch.check())
        assertTrue(ops.after("hh", 0, 1000).isEmpty())
        configured = true
        val noHousehold = CallCreditWatch(ops, CallCreditWatch.scanning(ops), { null }, source, { nowMs })
        assertFalse(noHousehold.check())
    }

    @Test
    fun twilioReadsTheBalanceAndTheAccountStatus() {
        val sid = "AC" + "a".repeat(32)
        val asked = mutableListOf<Pair<String, Map<String, String>>>()
        var account = """{"sid":"$sid","status":"active"}"""
        var balance: String? = """{"currency":"GBP","balance":"4.20","account_sid":"$sid"}"""
        val twilio = TwilioVoice(authToken = { "tok" }, accountSid = { sid }, get = { url, h ->
            asked += url to h
            when {
                url.endsWith("/Balance.json") -> balance?.let { HttpFetched(200, it.toByteArray()) } ?: HttpFetched(401, ByteArray(0))
                url.endsWith("/$sid.json") -> HttpFetched(200, account.toByteArray())
                else -> HttpFetched(404, ByteArray(0))
            }
        })
        assertTrue(twilio.creditConfigured())
        assertEquals(CallCreditReading(true, "4.20", "GBP"), twilio.readCredit())
        assertEquals(
            setOf("https://api.twilio.com/2010-04-01/Accounts/$sid.json", "https://api.twilio.com/2010-04-01/Accounts/$sid/Balance.json"),
            asked.map { it.first }.toSet(),
        )
        assertTrue(asked.all { it.second["Authorization"]!!.startsWith("Basic ") })
        // Suspended: the balance may not be readable, the status says enough.
        account = """{"sid":"$sid","status":"suspended"}"""
        balance = null
        assertEquals(CallCreditReading(false, null, null), twilio.readCredit())
        // Neither readable: a failed read.
        account = "not json"
        assertNull(twilio.readCredit())
        // Not set up: never asked.
        assertFalse(TwilioVoice(authToken = { "tok" }, accountSid = { null }).creditConfigured())
        assertFalse(TwilioVoice(authToken = { null }, accountSid = { sid }).creditConfigured())
        assertNull(TwilioVoice(authToken = { null }, accountSid = { sid }).readCredit())
    }
}
