package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatsAppCallsTest {
    private val on = WhatsAppCallSettings(on = true)
    private val sam = WhatsAppIncomingCall("Sam", WhatsAppCallKind.VOICE, null)
    private val lists = PeopleLists(family = setOf("Alex"), alwaysNotify = setOf("Jordan"))

    private fun decide(
        call: WhatsAppIncomingCall = sam,
        settings: WhatsAppCallSettings = on,
        atWork: Boolean = true,
        replied: Set<String> = emptySet(),
        assistant: Boolean = true,
    ) = WhatsAppCallRules.decide(call, settings, atWork, lists, replied, assistant)

    @Test
    fun detectsIncomingVoiceAndVideoCalls() {
        val voice = WhatsAppCallRules.incoming(true, "call", "Sam", "Incoming voice call", listOf("Decline", "Answer"))
        assertEquals(WhatsAppIncomingCall("Sam", WhatsAppCallKind.VOICE, null), voice)
        val video = WhatsAppCallRules.incoming(true, "call", "Sam", "Incoming video call", listOf("Decline", "Answer"))
        assertEquals(WhatsAppCallKind.VIDEO, video?.kind)
        // CallStyle's own type and person win.
        val styled = WhatsAppCallRules.incoming(true, "call", "WhatsApp", "Calling…", emptyList(), callType = 1, callPerson = "Sam")
        assertEquals("Sam", styled?.caller)
    }

    @Test
    fun ignoresEverythingThatIsNotARingingWhatsAppCall() {
        assertNull(WhatsAppCallRules.incoming(false, "call", "Sam", "Incoming voice call", listOf("Decline", "Answer")))
        assertNull(WhatsAppCallRules.incoming(true, "call", "Sam", "Ongoing voice call", listOf("Hang up")))
        assertNull(WhatsAppCallRules.incoming(true, "call", "Sam", "Ongoing voice call", emptyList(), callType = 2))
        assertNull(WhatsAppCallRules.incoming(true, "msg", "Sam", "See you at 5", listOf("Reply", "Mark as read")))
        assertNull(WhatsAppCallRules.incoming(true, "missed_call", "Sam", "Missed voice call", listOf("Call back", "Message")))
    }

    @Test
    fun groupCallsAreNamedByTheirGroup() {
        val g = WhatsAppCallRules.incoming(true, "call", "Oakfield parents", "Incoming group video call", listOf("Decline", "Join"))
            ?: WhatsAppCallRules.incoming(true, "call", "Oakfield parents", "Incoming group video call", listOf("Decline", "Answer"))
        assertEquals("Oakfield parents", g?.group)
        assertTrue(g!!.isGroup)
    }

    @Test
    fun findsTheDeclineAction() {
        assertEquals(0, WhatsAppCallRules.declineIndex(listOf("Decline", "Answer")))
        assertEquals(1, WhatsAppCallRules.declineIndex(listOf("Answer", " reject ")))
        assertNull(WhatsAppCallRules.declineIndex(listOf("Answer")))
    }

    @Test
    fun offUntilHeTurnsItOnAndNeverOutsideWorkMode() {
        assertEquals(WhatsAppCallWhy.OFF, decide(settings = WhatsAppCallSettings()).why)
        assertEquals(WhatsAppCallVerdict.RING, decide(settings = WhatsAppCallSettings()).verdict)
        assertFalse(WhatsAppCallSettings().on)
        assertEquals(WhatsAppCallDecision(WhatsAppCallVerdict.RING, WhatsAppCallWhy.NOT_AT_WORK), decide(atWork = false))
    }

    @Test
    fun theLetThroughListStillRings() {
        assertEquals(WhatsAppCallVerdict.RING, decide(WhatsAppIncomingCall("alex", WhatsAppCallKind.VOICE, null)).verdict)
        assertEquals(WhatsAppCallWhy.LET_THROUGH, decide(WhatsAppIncomingCall("Jordan", WhatsAppCallKind.VIDEO, null)).why)
    }

    @Test
    fun declinesAndRepliesOncePerCallerPerDay() {
        assertEquals(WhatsAppCallDecision(WhatsAppCallVerdict.DECLINE_AND_REPLY, WhatsAppCallWhy.REPLY), decide())
        val log = WhatsAppCallRules.recordReplied(emptyMap(), "Sam", today = 100)
        val again = decide(replied = WhatsAppCallRules.repliedToday(log, 100))
        assertEquals(WhatsAppCallDecision(WhatsAppCallVerdict.DECLINE, WhatsAppCallWhy.ALREADY_TODAY), again)
        // The next day the message may go again; yesterday's marks are dropped.
        assertEquals(WhatsAppCallWhy.REPLY, decide(replied = WhatsAppCallRules.repliedToday(log, 101)).why)
        assertEquals(mapOf("tom" to 101L), WhatsAppCallRules.recordReplied(log, "Tom", 101))
        assertEquals(log, WhatsAppCallRules.decode(WhatsAppCallRules.encode(log)))
        assertEquals(emptyMap(), WhatsAppCallRules.decode("junk;@4;x@y"))
    }

    @Test
    fun groupsAreDeclinedButNeverMessaged() {
        val g = WhatsAppIncomingCall("Alex", WhatsAppCallKind.VOICE, "Oakfield parents")
        // Even with family in the group: a group call is never let through by its caller, and never answered.
        assertEquals(WhatsAppCallDecision(WhatsAppCallVerdict.DECLINE, WhatsAppCallWhy.GROUP), decide(g))
        assertEquals("Declined a WhatsApp group call in Oakfield parents", WhatsAppCallRules.activitySummary(g, WhatsAppReplyOutcome.NOT_TRIED, WhatsAppCallWhy.GROUP))
    }

    @Test
    fun theMessageIsHeldBackWhileTheAssistantCannotPickUp() {
        assertEquals(WhatsAppCallWhy.ASSISTANT_OFF, decide(assistant = false).why)
        assertEquals(WhatsAppCallVerdict.DECLINE, decide(assistant = false).verdict)
        // His own words that promise nothing about the assistant still go.
        val own = WhatsAppCallSettings(true, "At work, I'll call you back after 5.")
        assertEquals(WhatsAppCallWhy.REPLY, decide(settings = own, assistant = false).why)
        assertEquals(WhatsAppCallRules.ASSISTANT_OFF_LINE, WhatsAppCallRules.line(on, assistantCanPickUp = false))
        assertEquals(WhatsAppCallRules.OFF_LINE, WhatsAppCallRules.line(WhatsAppCallSettings(), assistantCanPickUp = true))
        assertEquals(WhatsAppCallRules.ON_LINE, WhatsAppCallRules.line(on, assistantCanPickUp = true))
    }

    @Test
    fun theMessageIsHisAndBlankMeansTheSuggestedOne() {
        assertTrue(WhatsAppCallRules.DEFAULT_MESSAGE.contains("ring my mobile"))
        assertEquals(WhatsAppCallRules.DEFAULT_MESSAGE, WhatsAppCallRules.message(WhatsAppCallSettings(true, "   ")))
        assertEquals("Back at 5.", WhatsAppCallRules.message(WhatsAppCallSettings(true, "  Back   at 5. ")))
        assertEquals(WhatsAppCallRules.MAX_MESSAGE, WhatsAppCallRules.cleaned("x".repeat(900))!!.length)
    }

    @Test
    fun aRingingCallIsHandledOnce() {
        assertTrue(WhatsAppCallRules.sameCall(1_000, 30_000))
        assertFalse(WhatsAppCallRules.sameCall(1_000, 1_000 + WhatsAppCallRules.SAME_CALL_MS))
        assertFalse(WhatsAppCallRules.sameCall(null, 30_000))
    }

    @Test
    fun activityAndTheSummarySayWhatHappened() {
        assertEquals(
            "Declined a WhatsApp voice call from Sam · sent your at-work message",
            WhatsAppCallRules.activitySummary(sam, WhatsAppReplyOutcome.SENT, WhatsAppCallWhy.REPLY),
        )
        assertEquals(
            "Declined a WhatsApp voice call from Sam · WhatsApp offered no reply, so your message wasn't sent",
            WhatsAppCallRules.activitySummary(sam, WhatsAppReplyOutcome.NO_REPLY_ACTION, WhatsAppCallWhy.REPLY),
        )
        val note = WhatsAppCallRules.heldNote(WhatsAppReplyOutcome.SENT, WhatsAppCallWhy.REPLY)
        val item = CapturedItem("id", CaptureApp.WHATSAPP, CaptureKind.MISSED_CALL, "Sam", note, null, 0)
        assertEquals("Called on WhatsApp · at-work message sent", item.displayLine)
        assertTrue(WhatsAppCallRules.isWhatsAppCall(item))
        assertFalse(item.isUrgent)
        // A phone missed call reads as before.
        assertEquals("Missed call", item.copy(app = CaptureApp.PHONE, text = null).displayLine)
    }

    @Test
    fun callBackOpensTheChat() {
        assertEquals("https://wa.me/447700900123", WhatsAppCallRules.callBackLink("07700 900123"))
        assertEquals("https://wa.me/447700900123", WhatsAppCallRules.callBackLink("+44 7700 900123"))
        assertNull(WhatsAppCallRules.callBackLink(null))
        assertNull(WhatsAppCallRules.callBackLink("123"))
    }
}
