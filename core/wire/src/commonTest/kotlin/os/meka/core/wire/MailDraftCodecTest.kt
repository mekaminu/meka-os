package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A reply drafted by MEKA's AI on the wire (Inbox autopilot, slice 6b-2b). */
class MailDraftCodecTest {
    private val request = MailDraftCodec.Request(
        "2026-10-13", "Tuesday 13 October 2026 · 17:05", "Personal", false, "Sam Taylor", "Saturday's game",
        "Are you still OK to drive on Saturday? We need to be there by 9.", listOf("Yes, all good. See you there.", "Thanks Alex, sorted."),
    )

    @Test
    fun aRequestAndAResponseRoundTrip() {
        assertEquals(request, MailDraftCodec.decodeRequest(MailDraftCodec.encodeRequest(request)))
        val bare = request.copy(preview = null, examples = emptyList(), business = true)
        assertEquals(bare, MailDraftCodec.decodeRequest(MailDraftCodec.encodeRequest(bare)))
        val r = MailDraftCodec.Response("answered", "Yes, I can drive. I'll pick you up at 8:15.")
        assertEquals(r, MailDraftCodec.decodeResponse(MailDraftCodec.encodeResponse(r)))
        val off = MailDraftCodec.Response("off", reason = "Navilife's AI is off")
        assertEquals(off, MailDraftCodec.decodeResponse(MailDraftCodec.encodeResponse(off)))
    }

    @Test
    fun aChaseRoundTripsAndAnOlderRequestIsAReply() {
        val chase = request.copy(
            preview = null, chase = MailDraftCodec.CHASE_FOLLOW_UP, what = "Reply about the quote", since = "Mon 12 Oct",
            chasedBefore = true, sign = "Alex",
        )
        assertEquals(chase, MailDraftCodec.decodeRequest(MailDraftCodec.encodeRequest(chase)))
        val waiting = request.copy(chase = MailDraftCodec.CHASE_WAITING, what = "Signed contract")
        assertEquals(waiting, MailDraftCodec.decodeRequest(MailDraftCodec.encodeRequest(waiting)))
        // A reply carries none of the chase's keys, so it reads as before.
        val reply = MailDraftCodec.encodeRequest(request)
        listOf("chase", "what", "since", "chasedBefore", "sign").forEach { k -> kotlin.test.assertFalse("\"$k\"" in reply, k) }
    }

    @Test
    fun aRequestOutsideTheLimitsIsRefused() {
        val bad = listOf(
            request.copy(date = "13/10/2026"),
            request.copy(from = " "),
            request.copy(from = "x".repeat(MailDraftCodec.MAX_FROM + 1)),
            request.copy(account = ""),
            request.copy(subject = "x".repeat(MailDraftCodec.MAX_SUBJECT + 1)),
            request.copy(preview = "x".repeat(MailDraftCodec.MAX_PREVIEW + 1)),
            request.copy(examples = List(MailDraftCodec.MAX_EXAMPLES + 1) { "Reply $it" }),
            request.copy(examples = listOf("x".repeat(MailDraftCodec.MAX_EXAMPLE + 1))),
            request.copy(chase = "nag"),
            request.copy(chase = MailDraftCodec.CHASE_WAITING, what = "x".repeat(MailDraftCodec.MAX_SUBJECT + 1)),
            request.copy(chase = MailDraftCodec.CHASE_WAITING, since = "x".repeat(MailDraftCodec.MAX_SINCE + 1)),
            request.copy(chase = MailDraftCodec.CHASE_WAITING, sign = "x".repeat(MailDraftCodec.MAX_SIGN + 1)),
        )
        bad.forEach { b -> assertFailsWith<WireFormatException> { MailDraftCodec.decodeRequest(MailDraftCodec.encodeRequest(b)) } }
    }

    @Test
    fun theModelsAnswerIsReadFromItsJsonOnly() {
        assertEquals("Yes, see you then.", MailDraftCodec.parseModelAnswer("```json\n{\"draft\":\" Yes, see you then. \"}\n```").draft)
        assertNull(MailDraftCodec.parseModelAnswer("Sure! Here's a draft: hi").draft)
        assertNull(MailDraftCodec.parseModelAnswer("{\"draft\": 3}").draft)
        assertEquals("answered", MailDraftCodec.parseModelAnswer("nonsense").state)
    }
}
