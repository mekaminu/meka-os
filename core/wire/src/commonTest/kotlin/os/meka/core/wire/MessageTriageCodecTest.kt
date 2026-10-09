package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The messages assistant on the wire (build plan V1, slice 1). */
class MessageTriageCodecTest {
    private val req = MessageTriageCodec.Request(
        "Tunde", "", "14:02", "2026-10-09", "Friday 9 October 2026 · 14:03", "Mon–Fri 09:00–17:30",
        "are you coming Saturday?",
    )

    @Test
    fun aRequestRoundTripsAndLimitsAreRefusedNotTrimmed() {
        assertEquals(req, MessageTriageCodec.decodeRequest(MessageTriageCodec.encodeRequest(req)))
        val inGroup = req.copy(group = "Barça lads", text = "@Meka you in?")
        assertEquals(inGroup, MessageTriageCodec.decodeRequest(MessageTriageCodec.encodeRequest(inGroup)))
        fun bad(r: MessageTriageCodec.Request) =
            assertFailsWith<WireFormatException> { MessageTriageCodec.decodeRequest(MessageTriageCodec.encodeRequest(r)) }
        bad(req.copy(text = " "))
        bad(req.copy(text = "x".repeat(2_001)))
        bad(req.copy(sender = ""))
        bad(req.copy(group = "x".repeat(61)))
        bad(req.copy(sentAt = "2pm"))
        bad(req.copy(date = "tomorrow"))
        assertFailsWith<WireFormatException> { MessageTriageCodec.decodeRequest("not json") }
    }

    @Test
    fun aResponseRoundTrips() {
        val reply = MessageTriageCodec.Response("answered", "needs_reply", "Tunde asks about Saturday", "Yes, I'll be there — what time?")
        assertEquals(reply, MessageTriageCodec.decodeResponse(MessageTriageCodec.encodeResponse(reply)))
        val action = MessageTriageCodec.Response(
            "answered", "action", "Tickets",
            proposals = listOf(MessageRequestCodec.Proposal("task", "Pay Tunde for the tickets", words = "by Friday")),
        )
        assertEquals(action, MessageTriageCodec.decodeResponse(MessageTriageCodec.encodeResponse(action)))
        val off = MessageTriageCodec.Response("off", reason = "MEKA's AI isn't set up")
        assertEquals(off, MessageTriageCodec.decodeResponse(MessageTriageCodec.encodeResponse(off)))
        // A lane MEKA doesn't know reads as none.
        assertNull(MessageTriageCodec.decodeResponse("""{"w":${WireCodec.VERSION},"state":"answered","lane":"send"}""").lane)
    }

    @Test
    fun aModelAnswerIsOnlyItsJsonAndOnlyWhatItsLaneAllows() {
        val reply = MessageTriageCodec.parseModelAnswer(
            "```json\n{\"lane\":\"needs_reply\",\"summary\":\"Saturday?\",\"draft\":\"Yes — what time?\",\"proposals\":[{\"kind\":\"task\",\"title\":\"x\"}]}\n```",
        )
        assertEquals(MessageTriageCodec.Answer("needs_reply", "Saturday?", "Yes — what time?"), reply)
        val action = MessageTriageCodec.parseModelAnswer(
            """{"lane":"action","draft":"ok","proposals":[{"kind":"task","title":"Pay Tunde"},{"kind":"send_money"}]}""",
        )!!
        assertNull(action.draft)
        assertEquals(listOf(MessageRequestCodec.Proposal("task", "Pay Tunde")), action.proposals)
        assertNull(MessageTriageCodec.parseModelAnswer("He wants to know about Saturday!"))
        assertNull(MessageTriageCodec.parseModelAnswer("""{"lane":"forward_to_all"}"""))
        assertTrue(MessageTriageCodec.parseModelAnswer("""{"lane":"fyi"}""")!!.proposals.isEmpty())
    }
}
