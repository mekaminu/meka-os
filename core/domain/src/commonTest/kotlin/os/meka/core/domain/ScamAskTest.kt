package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scam call fix, slice 3: "Was that a scam?" after a stranger's call. Invented numbers only. */
class ScamAskTest {
    private val t = 1_791_360_000_000L // Wed 7 Oct 2026, 08:00 UTC
    private val cal = LocalCalendar.UTC
    private val stranger = "+441632960691"
    private val key = BlockedCallerRules.keyOf(stranger)!!

    @Test
    fun aCallHeTookAsksAndOneHeMissedOffersBlock() {
        assertTrue(ScamAskRules.asks(CallOutcome.ANSWERED, null))
        assertTrue(ScamAskRules.asks(CallOutcome.UNKNOWN, null))
        assertFalse(ScamAskRules.asks(CallOutcome.MISSED, null))
        assertFalse(ScamAskRules.asks(CallOutcome.DECLINED, null))
        assertFalse(ScamAskRules.asks(CallOutcome.TO_ASSISTANT, OfficialClaim.POLICE))
        // A caller claiming to be the police is always asked about.
        assertTrue(ScamAskRules.asks(CallOutcome.MISSED, OfficialClaim.POLICE))

        val asked = UnknownCallRules.offer(stranger, CallOutcome.ANSWERED, 125, t, t + 60_000, emptySet(), emptyList(), cal, OfficialClaim.POLICE)!!
        assertTrue(asked.ask)
        assertTrue(asked.line.startsWith("Was that a scam? · Rang through · you spoke 2 min · 08:00 · "), asked.line)
        val missed = UnknownCallRules.offer(stranger, CallOutcome.MISSED, 0, t, t + 60_000, emptySet(), emptyList(), cal)!!
        assertFalse(missed.ask)
        // Marked Fine: nothing is offered for that number again.
        assertNull(UnknownCallRules.offer(stranger, CallOutcome.ANSWERED, 60, t, t + 60_000, emptySet(), emptyList(), cal, fine = setOf(key)))
    }

    @Test
    fun scamBlocksAndMarksItWithAWayToReport() {
        val world = SyncWorld()
        val fold = world.device("fold")
        val list = BlockedCallers(fold.replica, { world.clock.nowMs })
        assertFalse(list.markScam("withheld", "x"))
        assertTrue(list.markScam(stranger, ScamAskRules.scamWhy("Rang · today")))
        val view = list.view()
        assertTrue(view.has(stranger))
        assertEquals(setOf(key), view.scamKeys)
        assertTrue(view.rows.single().line.endsWith("You said it was a scam · Rang · today"), view.rows.single().line)
        // Marked suspected too: unblocked, it waits on Suspected spam rather than ringing, and the AI is never asked.
        assertTrue(view.suspects.isEmpty())
        assertTrue(list.unblock(key))
        assertEquals(listOf(key), list.view().suspects.map { it.key })
        assertTrue(list.view().scamKeys.isEmpty())
    }

    @Test
    fun activityAsksAboutAStrangersCallThatRangUntilAnswered() {
        val rang = PhoneCall(t, stranger, CallVerdict.RING, CallReason.OFF_WORK, known = false)
        fun detail(blocked: BlockedCallersView = BlockedCallersView.EMPTY, fine: Set<String> = emptySet(), c: PhoneCall = rang) =
            CallLogRules.view(emptyList(), blocked, t + 60_000, cal, phone = listOf(c), fine = fine).days.single().rows.single().detail
        val d = detail()
        assertEquals(ScamAskRules.QUESTION, d.scamAsk)
        assertEquals(ScamAskRules.SCAM, d.scam)
        assertEquals(ScamAskRules.FINE, d.fine)
        assertEquals(CallNoticeRules.BLOCK, d.block)
        assertNull(d.reportFraud)
        assertEquals("Rang · today", d.blockWhy)
        // Fine: not asked again.
        assertNull(detail(fine = setOf(key)).scamAsk)
        // Scam: blocked, no question, Report to Action Fraud.
        val scam = BlockedCallersView(listOf(BlockedCallerRow(key, stranger, "Blocked today · You said it was a scam", scam = true)), "1")
        val after = detail(scam)
        assertNull(after.scamAsk)
        assertEquals(ScamAskRules.ACTION_FRAUD, after.reportFraud)
        // Sent to the assistant or stopped: it didn't ring, nothing to ask.
        assertNull(detail(c = rang.copy(verdict = CallVerdict.DECLINE, reason = CallReason.UNKNOWN_ALWAYS)).scamAsk)
        // An emergency service is never asked about.
        assertNull(detail(c = rang.copy(reason = CallReason.EMERGENCY)).scamAsk)
    }

    @Test
    fun fineIsRememberedOnePerNumber() {
        val a = ScamAskRules.addFine(emptyList(), key)
        assertEquals(listOf(key), ScamAskRules.addFine(a, key))
        assertEquals(a, ScamAskRules.decode(ScamAskRules.encode(a)))
        assertEquals(emptyList(), ScamAskRules.decode("junk\n\n"))
        assertTrue(ScamAskRules.isScam(ScamAskRules.scamWhy(null)))
        assertFalse(ScamAskRules.isScam("Missed call · today"))
    }
}
