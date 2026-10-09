package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Requests from people Meka watches, on the wire (build plan V1, slice 1). */
class MessageRequestCodecTest {
    private val req = MessageRequestCodec.Request(
        "Wife", "14:02", "2026-10-09", "Friday 9 October 2026 · 14:03", "Mon–Fri 09:00–17:30",
        "can you pick up the dry cleaning tomorrow?",
    )

    @Test
    fun aRequestRoundTripsAndLimitsAreRefusedNotTrimmed() {
        assertEquals(req, MessageRequestCodec.decodeRequest(MessageRequestCodec.encodeRequest(req)))
        fun bad(r: MessageRequestCodec.Request) =
            assertFailsWith<WireFormatException> { MessageRequestCodec.decodeRequest(MessageRequestCodec.encodeRequest(r)) }
        bad(req.copy(text = " "))
        bad(req.copy(text = "x".repeat(2_001)))
        bad(req.copy(sender = ""))
        bad(req.copy(sender = "x".repeat(61)))
        bad(req.copy(sentAt = "2pm"))
        bad(req.copy(date = "tomorrow"))
        bad(req.copy(work = "x".repeat(121)))
        assertFailsWith<WireFormatException> { MessageRequestCodec.decodeRequest("not json") }
    }

    @Test
    fun aResponseRoundTrips() {
        val r = MessageRequestCodec.Response(
            "answered",
            listOf(MessageRequestCodec.Proposal("task", "Pick up dry cleaning", "2026-10-10", null, "tomorrow")),
        )
        assertEquals(r, MessageRequestCodec.decodeResponse(MessageRequestCodec.encodeResponse(r)))
        val off = MessageRequestCodec.Response("off", reason = "MEKA's AI isn't set up")
        assertEquals(off, MessageRequestCodec.decodeResponse(MessageRequestCodec.encodeResponse(off)))
    }

    @Test
    fun aModelsAnswerKeepsOnlyKnownKindsAndValidFields() {
        val got = MessageRequestCodec.parseModelAnswer(
            """```json
            {"proposals":[{"kind":"task","title":"Pick up dry cleaning","date":"tomorrow","words":"tomorrow"},
              {"kind":"send_message","title":"Will do"},
              {"kind":"work_from_home","date":"2026-10-15","time":"9am"},
              {"kind":"event","title":"Parents' evening","date":"2026-10-13","time":"18:00"},
              {"kind":"reminder","title":"Call Mum"}]}
            ```""",
        )
        assertEquals(
            listOf(
                MessageRequestCodec.Proposal("task", "Pick up dry cleaning", null, null, "tomorrow"),
                MessageRequestCodec.Proposal("work_from_home", null, "2026-10-15", null, null),
                MessageRequestCodec.Proposal("event", "Parents' evening", "2026-10-13", "18:00", null),
            ),
            got,
        )
        assertTrue(MessageRequestCodec.parseModelAnswer("Sure, I'll add that!").isEmpty())
        assertTrue(MessageRequestCodec.parseModelAnswer("""{"proposals":"none"}""").isEmpty())
    }
}
