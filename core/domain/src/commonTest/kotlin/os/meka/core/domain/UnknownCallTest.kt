package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Unknown caller · Block?" after a call from a number nobody knows (build plan "Call assistant live — polish" 8b b). */
class UnknownCallTest {
    /** Wed 7 Oct 2026, 08:00 UTC. */
    private val t = 1_791_360_000_000L
    private val cal = LocalCalendar.UTC
    private val lists = PeopleLists(family = setOf("Jordan"), alwaysNotify = setOf("Ada"))
        .withNumber("Jordan", "+447700900111")
        .withNumber("Ada", "07700 900222")
    private val stranger = "+441632960691"

    private fun decide(number: String?, on: Boolean = true, atWork: Boolean = false, signals: CallSignals = CallSignals.NONE, blocked: Set<String> = emptySet()) =
        CallScreeningRules.decide(on, atWork, number, lists, emptyList(), t, blocked, signals)

    private fun watch(number: String?, on: Boolean = true, atWork: Boolean = false, signals: CallSignals = CallSignals.NONE, own: Boolean = false, blocked: Set<String> = emptySet()) =
        UnknownCallRules.watch(decide(number, on, atWork, signals, blocked), number, signals, own)

    @Test
    fun onlyACallFromANumberNobodyKnowsIsLookedAtAfterwards() {
        // A stranger who rang (assistant on or off, off work) or was sent to the assistant as likely spam.
        assertTrue(watch(stranger))
        assertTrue(watch(stranger, on = false))
        // Spam calls still ring, slice 1: one that failed the network's check is blocked now, so nothing to offer.
        assertFalse(watch(stranger, signals = CallSignals(verificationFailed = true)))
        // Family, always-notify, a contact, someone Meka called, this phone: never.
        assertFalse(watch("07700 900111"))
        assertFalse(watch("+447700900222"))
        assertFalse(watch(stranger, signals = CallSignals(knownContact = true)))
        assertFalse(watch(stranger, signals = CallSignals(calledRecently = true)))
        assertFalse(watch(stranger, own = true))
        // Withheld: nothing to block. Already blocked: rejected silently, nothing to ask.
        assertFalse(watch(null))
        assertFalse(watch("Unknown"))
        assertFalse(watch(stranger, blocked = setOf(BlockedCallerRules.keyOf(stranger)!!)))
        // Declined at work: it is in the after-work summary (with Block there), and work mode posts nothing that can wait.
        assertFalse(watch(stranger, atWork = true))
        // A repeat stranger at work rings (second call within 3 min): looked at.
        val repeat = CallScreeningRules.decide(true, true, stranger, lists, listOf(ScreenedCall(CallScreeningRules.callerKey(stranger), t - 60_000)), t)
        assertTrue(UnknownCallRules.watch(repeat, stranger, CallSignals.NONE))
    }

    @Test
    fun howTheCallEndedComesFromThePhonesCallLog() {
        assertEquals(CallOutcome.MISSED, UnknownCallRules.outcomeOf(UnknownCallRules.LOG_MISSED, 0))
        assertEquals(CallOutcome.DECLINED, UnknownCallRules.outcomeOf(UnknownCallRules.LOG_REJECTED, 0))
        assertEquals(CallOutcome.DECLINED, UnknownCallRules.outcomeOf(UnknownCallRules.LOG_VOICEMAIL, 0))
        assertEquals(CallOutcome.ANSWERED, UnknownCallRules.outcomeOf(UnknownCallRules.LOG_INCOMING, 125))
        assertEquals(CallOutcome.MISSED, UnknownCallRules.outcomeOf(UnknownCallRules.LOG_INCOMING, 0))
        assertEquals(CallOutcome.UNKNOWN, UnknownCallRules.outcomeOf(null, 0))
        assertEquals(CallOutcome.TO_ASSISTANT, UnknownCallRules.outcomeOf(UnknownCallRules.LOG_REJECTED, 0, sentToAssistant = true))
    }

    @Test
    fun theNotificationSaysWhoAndHowAndOffersBlockAndReport() {
        val at = t + 6 * 3_600_000L + 5 * 60_000L // 14:05
        val o = assertNotNull(UnknownCallRules.offer(stranger, CallOutcome.MISSED, 0, at, at + 60_000, emptySet(), emptyList(), cal))
        assertEquals("01632 960691 · not in your contacts", o.title)
        assertEquals("Rang · you missed it · 14:05 · Not blocked · it will ring again", o.line)
        assertEquals("Missed call · Wed 7 Oct", o.why)
        assertEquals("Call 01632960691", o.reportText)
        assertEquals(BlockedCallerRules.keyOf("01632 960691"), o.key)

        fun line(outcome: CallOutcome, s: Long = 0) = UnknownCallRules.offer(stranger, outcome, s, at, at + 60_000, emptySet(), emptyList(), cal)!!.line
        assertEquals("You declined it · 14:05 · Not blocked · it will ring again", line(CallOutcome.DECLINED))
        // Scam call fix, slice 3: a call he took (or whose ending is unknown) asks instead.
        assertEquals("Was that a scam? · Rang through · you spoke 2 min · 14:05", line(CallOutcome.ANSWERED, 125))
        assertEquals("Sent to your call assistant · 14:05 · the network couldn't verify the number · Not blocked · it will ring again", line(CallOutcome.TO_ASSISTANT))
        assertEquals("Was that a scam? · Rang · 14:05", line(CallOutcome.UNKNOWN))
        // Checked after midnight: the day is said.
        val late = UnknownCallRules.offer(stranger, CallOutcome.MISSED, 0, at, at + 12 * 3_600_000L, emptySet(), emptyList(), cal)!!
        assertEquals("Rang · you missed it · yesterday 14:05 · Not blocked · it will ring again", late.line)
        assertEquals("45 s", UnknownCallRules.length(45))
        assertEquals("1 h 05", UnknownCallRules.length(3_900))
    }

    @Test
    fun oneOfferPerNumberADayAndNoneOnceItIsBlocked() {
        val key = BlockedCallerRules.keyOf(stranger)!!
        assertNull(UnknownCallRules.offer(stranger, CallOutcome.MISSED, 0, t, t, setOf(key), emptyList(), cal))
        assertNull(UnknownCallRules.offer(null, CallOutcome.MISSED, 0, t, t, emptySet(), emptyList(), cal))
        var offered = UnknownCallRules.remember(emptyList(), key, t)
        // Rang four more times that afternoon (written another way, too): one notification.
        assertNull(UnknownCallRules.offer("01632 960691", CallOutcome.MISSED, 0, t, t + 3_600_000, emptySet(), offered, cal))
        // The next day: asked again.
        assertNotNull(UnknownCallRules.offer(stranger, CallOutcome.MISSED, 0, t, t + UnknownCallRules.ONCE_PER_MS, emptySet(), offered, cal))
        // Another stranger meanwhile is asked about.
        assertNotNull(UnknownCallRules.offer("07700 900999", CallOutcome.MISSED, 0, t, t + 60_000, emptySet(), offered, cal))
        // The record keeps one entry a number and forgets after a day; it round-trips the Fold's preferences string.
        offered = UnknownCallRules.remember(offered, key, t + 1_000)
        assertEquals(1, offered.size)
        assertEquals(offered, CallScreeningRules.decode(CallScreeningRules.encode(offered)))
        assertEquals(1, UnknownCallRules.remember(offered, "tel:7700900999", t + UnknownCallRules.ONCE_PER_MS + 2_000).size)
    }

    @Test
    fun aFlaggedNumbersAfterCallNoticeSaysSoAndOffersNotSpam() {
        val key = BlockedCallerRules.keyOf(stranger)!!
        val at = t
        val o = assertNotNull(UnknownCallRules.offer(stranger, CallOutcome.TO_ASSISTANT, 0, at, at + 60_000, emptySet(), emptyList(), cal, suspected = setOf(key)))
        assertTrue(o.notSpam)
        assertTrue(o.line.endsWith(CallNoticeRules.SUSPECTED_STATUS), o.line)
        val plain = assertNotNull(UnknownCallRules.offer(stranger, CallOutcome.MISSED, 0, at, at + 60_000, emptySet(), emptyList(), cal))
        assertFalse(plain.notSpam)
    }
}
