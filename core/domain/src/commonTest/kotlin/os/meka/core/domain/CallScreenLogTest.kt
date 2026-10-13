package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Spam calls still ring, slice 2: the phone's last screened calls for the diagnosis, never a number. */
class CallScreenLogTest {
    private val number = "01632 960123"

    @Test
    fun theTagIsStablePerPhoneButNeverTheNumber() {
        val a = CallScreenLogRules.tag(number, "salt-a")
        assertEquals(a, CallScreenLogRules.tag("+44 1632 960123", "salt-a"))
        assertNotEquals(a, CallScreenLogRules.tag(number, "salt-b"))
        assertTrue(a.matches(Regex("#[0-9a-f]{6}")))
        assertTrue("960123" !in a)
        assertEquals("withheld", CallScreenLogRules.tag(null, "salt-a"))
        assertEquals("withheld", CallScreenLogRules.tag(" ", "salt-a"))
    }

    @Test
    fun anEntrySaysTheVerdictWhyAndWhatThePhoneKnew() {
        val d = CallDecision(CallVerdict.BLOCK, CallReason.AUTO_BLOCKED, "tel:+441632960123", autoBlockId = "a1")
        val e = CallScreenLogRules.entry(5, "#abcdef", d, CallSignals(verificationFailed = true), CallScreenLogRules.VERIFICATION_FAILED, true)
        assertEquals("BLOCK", e.verdict)
        assertEquals("#abcdef · Failed the network's caller check · blocked · auto-blocked · net failed · not a contact", e.detail)
        val ring = CallScreenLogRules.entry(
            6, "#abcdef", CallDecision(CallVerdict.RING, CallReason.SWITCHED_OFF, "k"),
            CallSignals(knownContact = true, calledRecently = true, networkName = "X"), null, false,
        )
        assertEquals("#abcdef · Call assistant off · net not checked · contact · called in 90 days · network name sent · protection off", ring.detail)
        val slow = CallScreenLogRules.entry(7, "withheld", null, null, CallScreenLogRules.VERIFICATION_PASSED, true)
        assertEquals(CallScreenLogRules.TIMEOUT, slow.verdict)
        assertEquals("withheld · no answer in time · rang · net passed", slow.detail)
    }

    @Test
    fun theLogKeepsTheNewestTwentyAndRoundTrips() {
        var log = emptyList<ScreenedCallEntry>()
        repeat(25) { log = CallScreenLogRules.add(log, ScreenedCallEntry(1_000L + it, "RING", "#00000$it\tx\ny")) }
        assertEquals(CallScreenLogRules.MAX, log.size)
        assertEquals(1_024L, log.first().atMs)
        val back = CallScreenLogRules.decode(CallScreenLogRules.encode(log))
        assertEquals(log.map { it.atMs }, back.map { it.atMs })
        assertEquals("#0000024 x y", back.first().detail)
        assertEquals(emptyList(), CallScreenLogRules.decode(null))
        assertEquals(emptyList(), CallScreenLogRules.decode("junk\n0\tRING\tx\n5\t\tx"))
    }

    @Test
    fun theSummarySaysWhatStopsScreening() {
        val at = { ms: Long -> "t$ms" }
        assertEquals(
            "Caller ID & spam role NOT held (calls never reach Navilife) · protection on · contacts/call log NOT readable · no calls screened yet",
            CallScreenLogRules.summary(CallScreenReport(false, true, false, emptyList()), at),
        )
        assertEquals(
            "Caller ID & spam role held · protection OFF · contacts and call log readable · 1 screened, last t9",
            CallScreenLogRules.summary(CallScreenReport(true, false, true, listOf(ScreenedCallEntry(9, "RING", "x"))), at),
        )
    }
}
