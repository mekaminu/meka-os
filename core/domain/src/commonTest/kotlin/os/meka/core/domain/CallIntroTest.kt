package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The call assistant talks to them: what a caller says when asked who's calling (build plan "spam calls still ring" (c)). */
class CallIntroTest {
    private fun judge(s: String?, rep: NumberReputation? = null) = CallIntroRules.judge(s, rep)

    @Test
    fun aClaimWithAScamsAskIsAScam() {
        val hmrc = judge("Hello, this is HMRC, you have an outstanding tax bill and must pay today")
        assertEquals(IntroVerdict.SCAM, hmrc.verdict)
        assertEquals("Said they're from HMRC and asked for payment", CallIntroRules.summary(hmrc))
        val bank = judge("I'm calling from your bank's fraud team, we need to verify a suspicious transaction")
        assertEquals(IntroVerdict.SCAM, bank.verdict)
        assertEquals("Said they're from a bank's fraud team and asked for your details", CallIntroRules.summary(bank))
        assertEquals(IntroVerdict.SCAM, judge("This is Detective Smith from the Metropolitan Police, there is a warrant").verdict)
        assertEquals(IntroVerdict.SCAM, judge("Amazon Prime calling about a refund on your account").verdict)
    }

    @Test
    fun aStrongAskAloneIsAScam() {
        val gift = judge("you need to buy gift cards to settle this today")
        assertEquals(IntroVerdict.SCAM, gift.verdict)
        assertEquals("Asked for gift cards or crypto", CallIntroRules.summary(gift))
        assertEquals(IntroVerdict.SCAM, judge("move your money to a safe account now").verdict)
    }

    @Test
    fun ordinaryCallersAndClaimsAloneGoOnToAMessage() {
        assertEquals(IntroVerdict.OK, judge("It's Sam from Oakfield dental surgery about your appointment on Thursday").verdict)
        assertEquals(IntroVerdict.OK, judge("Hi it's the garage, your car is ready to collect").verdict)
        // A refund from someone who doesn't claim to be anyone is fine; "I'm fine" isn't a fine.
        assertEquals(IntroVerdict.OK, judge("Hi, I'm fine thanks, it's Alex about the refund for the tickets").verdict)
        val bank = judge("This is Barclays about your mortgage appointment, please call us back")
        assertEquals(IntroVerdict.CLAIMS_OFFICIAL, bank.verdict)
        assertEquals("a bank", bank.claim)
    }

    @Test
    fun silenceOrAKeyGoesOnUnlessTheNumberIsASureScam() {
        assertEquals(IntroVerdict.NONE, judge(null).verdict)
        assertEquals(IntroVerdict.NONE, judge("1").verdict)
        assertEquals(IntroVerdict.NONE, judge("hello").verdict)
        val sure = NumberReputation(NumberVerdict.SCAM, reports = 23, sure = true, why = "an HMRC scam")
        val ended = judge(null, sure)
        assertEquals(IntroVerdict.SCAM, ended.verdict)
        assertEquals("Reported online as a scam", CallIntroRules.summary(ended))
        // A number reported (not sure) is a scam only when it also claims or asks something.
        val reported = NumberReputation(NumberVerdict.SCAM, reports = 2, sure = false)
        assertEquals(IntroVerdict.SCAM, judge("this is the tax office calling", reported).verdict)
        assertEquals(IntroVerdict.OK, judge("hi it's Jordan about Saturday", reported).verdict)
        assertEquals(IntroVerdict.OK, judge("hi it's Jordan about Saturday", NumberReputation(NumberVerdict.NUISANCE, 4, true)).verdict)
    }

    @Test
    fun theWordsAreCleanedAndOnlyARealAnswerCounts() {
        assertNull(CallIntroRules.clean("   "))
        assertEquals("It's Sam about Thursday", CallIntroRules.clean("  It's  Sam\nabout <Thursday>  "))
        assertTrue(CallIntroRules.clean("word ".repeat(200))!!.length <= CallIntroRules.MAX_INTRO)
        assertTrue(CallIntroRules.real("It's Sam about Thursday"))
        assertFalse(CallIntroRules.real("1 2 3"))
        assertFalse(CallIntroRules.real("yes"))
        assertFalse(CallIntroRules.real(null))
    }

    @Test
    fun anEndedScamCallReadsAsOneAndIsNeverUrgent() {
        val item = CapturedItem(
            "h1", CaptureApp.PHONE, CaptureKind.VOICE_MESSAGE, "01632 960001", "This is HMRC, it's urgent, you must pay now", null, 0L,
            summary = "Said they're from HMRC and asked for payment", intro = "This is HMRC, it's urgent, you must pay now",
            ended = CallIntroRules.ENDED_SCAM,
        )
        assertTrue(item.endedAsScam)
        assertFalse(item.isUrgent)
        assertFalse(item.automated)
        assertEquals("Likely scam · your call assistant ended the call · Said they're from HMRC and asked for payment", item.displayLine)
        assertEquals("Said they're from HMRC and asked for payment", CallNoticeRules.about(item))
        val cal = LocalCalendar.UTC
        assertTrue(CallNoticeRules.happened(item, cal).endsWith("· ended as a likely scam"), CallNoticeRules.happened(item, cal))
    }

    @Test
    fun aCallerWhoSaidWhoTheyWereButLeftNoMessageShowsWhatTheySaid() {
        val item = CapturedItem(
            "h2", CaptureApp.PHONE, CaptureKind.VOICE_MESSAGE, "01632 960002", "It's Sam from the surgery about Thursday", null, 0L,
            intro = "It's Sam from the surgery about Thursday", ended = CallIntroRules.ENDED_NO_MESSAGE,
        )
        assertFalse(item.automated)
        assertEquals("Said: “It's Sam from the surgery about Thursday”", CallNoticeRules.about(item))
        assertTrue(CallNoticeRules.happened(item, LocalCalendar.UTC).endsWith("· said who they were · no message"))
        // Before the message's words arrive, what they said when asked shows instead of "Writing the message down…".
        val waiting = CapturedItem(
            "h3", CaptureApp.PHONE, CaptureKind.VOICE_MESSAGE, "01632 960003", null, null, 0L, transcribing = true, seconds = 20,
            intro = "Dentist about Thursday",
        )
        assertEquals("Said: “Dentist about Thursday”", CallNoticeRules.about(waiting))
    }
}
