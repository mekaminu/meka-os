package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The call assistant puts a genuine urgent caller through (build plan "spam calls still ring", item (c) slice 2). */
class PutThroughTest {
    private val mobile = "07700 900123"
    private val assistant = "+44 1632 960000"
    private val sam = "It's Sam from Oakfield School about Alex, please call back"

    private fun offer(
        intro: String? = sam,
        rep: NumberReputation? = null,
        seconds: Int? = 12,
        from: String? = "01632 960555",
        mobile: String? = this.mobile,
        assistant: String? = this.assistant,
        urgent: Boolean = true,
    ) = PutThroughRules.offer(urgent, intro, CallIntroRules.judge(intro, rep), rep, seconds, from, mobile, assistant)

    @Test
    fun aGenuineUrgentCallerIsOffered() {
        assertTrue(offer())
        assertTrue(offer(rep = NumberReputation(NumberVerdict.LEGIT, 0, false, "a listed business")))
        assertTrue(offer(seconds = null))
        assertTrue(offer(from = null)) // a withheld number who said who they are (an emergency callback often is)
    }

    @Test
    fun notUrgentAMachineOrNoMobileIsNeverOffered() {
        assertFalse(offer(urgent = false))
        assertFalse(offer(intro = null))
        assertFalse(offer(intro = "1"))
        assertFalse(offer(intro = "hello"))
        assertFalse(offer(seconds = 1))
        assertFalse(offer(mobile = null))
        assertFalse(offer(mobile = "not a number"))
        assertFalse(offer(assistant = null))
        // His own mobile calling the assistant (a test call) isn't rung back.
        assertFalse(offer(from = "+447700900123"))
    }

    @Test
    fun officialClaimsScamsAndReportedNumbersAreNeverPutThrough() {
        assertFalse(offer(intro = "This is Barclays about your mortgage appointment, please call us back"))
        assertFalse(offer(intro = "Detective Smith from the police, nothing to worry about, please call me"))
        assertFalse(offer(intro = "HMRC here, you owe tax and must pay today"))
        assertFalse(offer(rep = NumberReputation(NumberVerdict.NUISANCE, 4, true)))
        assertFalse(offer(rep = NumberReputation(NumberVerdict.SCAM, 2, false)))
    }

    @Test
    fun numbersAreDialledTheInternationalWay() {
        assertEquals("+447700900123", PutThroughRules.dialable("07700 900123"))
        assertEquals("+447700900123", PutThroughRules.dialable("+44 7700 900123"))
        assertEquals("+447700900123", PutThroughRules.dialable("0044 (7700) 900-123"))
        assertEquals("+12025550123", PutThroughRules.dialable("+1 202 555 0123"))
        assertNull(PutThroughRules.dialable("0770090012"))
        assertNull(PutThroughRules.dialable("7+7700900123"))
        assertNull(PutThroughRules.dialable("call me"))
        assertNull(PutThroughRules.dialable(null))
        assertTrue(PutThroughRules.fromAssistant("01632 960000", assistant))
        assertFalse(PutThroughRules.fromAssistant("01632 960001", assistant))
        assertFalse(PutThroughRules.fromAssistant(null, assistant))
    }

    @Test
    fun theWhisperSaysWhoAndHowToTakeIt() {
        val w = PutThroughRules.whisper(sam)
        assertTrue(w.startsWith("Navilife call assistant. A caller says it's urgent. They said: It's Sam from Oakfield School about Alex, please call back."))
        assertTrue(w.endsWith("Press 1 to take the call, or hang up and they'll know you have their message."))
        assertTrue(PutThroughRules.whisper("x".repeat(400)).contains("…."))
        assertFalse(PutThroughRules.whisper(null).contains("They said"))
        assertTrue(PutThroughRules.takes("1"))
        assertFalse(PutThroughRules.takes("2"))
        assertFalse(PutThroughRules.takes(null))
    }

    @Test
    fun theMessageSaysWhetherHeTookIt() {
        val item = CapturedItem(
            "h1", CaptureApp.PHONE, CaptureKind.VOICE_MESSAGE, "01632 960555", "Please call back", null, 0L, seconds = 12,
            urgent = true, urgentBy = CallNoticeRules.BY_SPEECH, intro = sam, putThrough = PutThroughRules.TAKEN,
        )
        assertTrue(CallNoticeRules.happened(item, LocalCalendar.UTC).endsWith("· left a 0:12 message · put through · you took it"))
        assertTrue(CallNoticeRules.happened(item.copy(putThrough = PutThroughRules.MISSED_VALUE), LocalCalendar.UTC).endsWith("· put through · you didn't take it"))
        assertTrue(CallNoticeRules.happened(item.copy(putThrough = null), LocalCalendar.UTC).endsWith("· left a 0:12 message"))
        val a = PutThroughRules.activity("01632 960555", taken = true, atMs = 0L)
        assertEquals("Put an urgent caller through to you · 01632 960555", (a[ActivityFields.SUMMARY] as os.meka.core.sync.FieldValue.Text).value)
    }

    @Test
    fun theAssistantsOwnNumberAlwaysRings() {
        val lists = PeopleLists()
        val d = CallScreeningRules.decide(
            true, true, "01632 960000", lists, emptyList(), 0L,
            signals = CallSignals(household = true, fromAssistant = true),
        )
        assertEquals(CallVerdict.RING, d.verdict)
        assertEquals(CallReason.ASSISTANT, d.reason)
        // Without it, a household number at work is declined as before.
        assertEquals(CallVerdict.DECLINE, CallScreeningRules.decide(true, true, "01632 960000", lists, emptyList(), 0L, signals = CallSignals(household = true)).verdict)
        assertEquals("Your call assistant putting a caller through", CallLogRules.rangWhy(CallReason.ASSISTANT))
    }
}
