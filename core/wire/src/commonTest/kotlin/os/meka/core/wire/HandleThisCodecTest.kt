package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** "Handle this" on the wire (build plan V1, slice 1). */
class HandleThisCodecTest {
    private val req = HandleThisCodec.Request(
        "2026-10-10", "Saturday 10 October 2026 · 21:40", "Mon–Fri 09:00–17:30", "Parents' evening",
        "Parents' evening is on Tuesday 20 October from 6pm. Please book a slot by Friday.",
    )

    @Test
    fun aRequestRoundTripsAndLimitsAreRefusedNotTrimmed() {
        assertEquals(req, HandleThisCodec.decodeRequest(HandleThisCodec.encodeRequest(req)))
        // A subject alone is enough (a shared link's page title, say); nothing at all is refused.
        val subjectOnly = req.copy(text = "")
        assertEquals(subjectOnly, HandleThisCodec.decodeRequest(HandleThisCodec.encodeRequest(subjectOnly)))
        fun bad(r: HandleThisCodec.Request) =
            assertFailsWith<WireFormatException> { HandleThisCodec.decodeRequest(HandleThisCodec.encodeRequest(r)) }
        bad(req.copy(text = " ", subject = ""))
        bad(req.copy(text = "x".repeat(6_001)))
        bad(req.copy(subject = "x".repeat(201)))
        bad(req.copy(date = "today"))
        bad(req.copy(work = "x".repeat(121)))
        bad(req.copy(now = "x".repeat(81)))
        assertFailsWith<WireFormatException> { HandleThisCodec.decodeRequest("[]") }
    }

    @Test
    fun theModelMayOnlyProposeTasksEventsAndReminders() {
        val answer = """
            ```json
            {"proposals":[
              {"kind":"event","title":"Parents' evening","date":"2026-10-20","time":"18:00","words":"Tuesday 20 October from 6pm"},
              {"kind":"work_from_home","date":"2026-10-20"},
              {"kind":"send_email","title":"Reply yes"},
              {"kind":"task","title":"Book a parents' evening slot","words":"by Friday"}
            ]}
            ```
        """.trimIndent()
        assertEquals(listOf("event", "task"), HandleThisCodec.parseModelAnswer(answer).map { it.kind })
        assertEquals(emptyList(), HandleThisCodec.parseModelAnswer("Sure! I'll book it for you."))
    }

    @Test
    fun aRenewalCarriesOnlyKnownTypeRepeatAndCostAndOnlyHandleThisReadsIt() {
        val answer = """{"proposals":[
            {"kind":"renewal","title":"Car insurance","date":"2026-11-12","words":"12 November","type":"insurance","repeat":"year","cost":"£1,240.50"},
            {"kind":"renewal","title":"Gym","date":"2026-11-01","type":"crypto","repeat":"fortnight","cost":"lots"}
        ]}"""
        val got = HandleThisCodec.parseModelAnswer(answer)
        assertEquals(MessageRequestCodec.Proposal("renewal", "Car insurance", "2026-11-12", null, "12 November", "insurance", "year", "1240.50"), got[0])
        assertEquals(MessageRequestCodec.Proposal("renewal", "Gym", "2026-11-01"), got[1])
        // A watched person's message never becomes a renewal.
        assertEquals(emptyList(), MessageRequestCodec.parseModelAnswer(answer))
        // The device reads it back from the server; a task's extra fields are dropped.
        val back = MessageRequestCodec.decodeResponse(MessageRequestCodec.encodeResponse(MessageRequestCodec.Response("answered", got)))
        assertEquals(got, back.proposals)
        val task = MessageRequestCodec.decodeResponse("""{"w":${WireCodec.VERSION},"state":"answered","proposals":[{"kind":"task","title":"x","type":"bill","cost":"9"}]}""")
        assertEquals(MessageRequestCodec.Proposal("task", "x"), task.proposals.single())
    }

    @Test
    fun aReplyCarriesItsWordsAndOnlyHandleThisReadsIt() {
        // Slice 3: a drafted reply's words come in "body" (whole, up to MAX_BODY; none or too many and it's dropped).
        val words = "Hi Mrs Patel,\n\nYes, Theo will be on the trip. Thank you!"
        val answer = """{"proposals":[
            {"kind":"reply","title":"Reply to Mrs Patel","body":${kotlinx.serialization.json.JsonPrimitive(words)}},
            {"kind":"reply","title":"No words"},
            {"kind":"reply","title":"Too long","body":"${"x".repeat(MessageRequestCodec.MAX_BODY + 1)}"},
            {"kind":"task","title":"Pack lunch","body":"ignored"}
        ]}"""
        val got = HandleThisCodec.parseModelAnswer(answer)
        assertEquals(
            listOf(MessageRequestCodec.Proposal("reply", "Reply to Mrs Patel", body = words), MessageRequestCodec.Proposal("task", "Pack lunch")),
            got,
        )
        // A watched person's message never becomes a reply card.
        assertEquals(listOf("task"), MessageRequestCodec.parseModelAnswer(answer).map { it.kind })
        val back = MessageRequestCodec.decodeResponse(MessageRequestCodec.encodeResponse(MessageRequestCodec.Response("answered", got)))
        assertEquals(got, back.proposals)
    }

    @Test
    fun aBookingCarriesItsTypeReferenceAndLastDayAndOnlyHandleThisReadsIt() {
        // Travel mode, slice 2b.
        val answer = """{"proposals":[
            {"kind":"booking","title":"Hotel Arts","date":"2026-11-14","until":"2026-11-18","type":"stay","ref":"XKQPLM"},
            {"kind":"booking","title":"Flight","date":"2026-11-14","until":"18 Nov","type":"insurance","ref":"<script>"},
            {"kind":"task","title":"Pack","ref":"ABC123","until":"2026-11-18","type":"flight"}
        ]}"""
        val got = HandleThisCodec.parseModelAnswer(answer)
        assertEquals(
            listOf(
                MessageRequestCodec.Proposal("booking", "Hotel Arts", "2026-11-14", type = "stay", ref = "XKQPLM", until = "2026-11-18"),
                MessageRequestCodec.Proposal("booking", "Flight", "2026-11-14"),
                MessageRequestCodec.Proposal("task", "Pack"),
            ),
            got,
        )
        assertEquals(listOf("task"), MessageRequestCodec.parseModelAnswer(answer).map { it.kind })
        val back = MessageRequestCodec.decodeResponse(MessageRequestCodec.encodeResponse(MessageRequestCodec.Response("answered", got)))
        assertEquals(got, back.proposals)
    }
}
