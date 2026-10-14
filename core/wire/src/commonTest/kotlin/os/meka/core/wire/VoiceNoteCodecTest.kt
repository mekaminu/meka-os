package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A voice note's tidy-up on the wire (Voice notes into actions, slice 2). */
class VoiceNoteCodecTest {
    private val req = VoiceNoteCodec.Request(
        "2026-10-14", "Wednesday 14 October 2026 · 08:12", "Mon–Fri 09:00–17:30", "Voice note · 08:12",
        "Um so ring the garage about the service tomorrow and uh buy milk and maybe a family film night on Fridays.",
    )

    @Test
    fun aRequestRoundTripsAndLimitsAreRefusedNotTrimmed() {
        assertEquals(req, VoiceNoteCodec.decodeRequest(VoiceNoteCodec.encodeRequest(req)))
        fun bad(r: VoiceNoteCodec.Request) =
            assertFailsWith<WireFormatException> { VoiceNoteCodec.decodeRequest(VoiceNoteCodec.encodeRequest(r)) }
        bad(req.copy(text = " "))
        bad(req.copy(text = "x".repeat(6_001)))
        bad(req.copy(subject = "x".repeat(201)))
        bad(req.copy(date = "today"))
        bad(req.copy(work = "x".repeat(121)))
        assertFailsWith<WireFormatException> { VoiceNoteCodec.decodeRequest("[]") }
    }

    @Test
    fun theModelsAnswerKeepsTheNoteNamesAndKnownKindsOnly() {
        val answer = """
            ```json
            {"note":"Ring the garage about the service tomorrow. Buy milk. Idea: a family film night on Fridays, ask Sam.",
             "people":["Sam","sam","  ","${"x".repeat(41)}","Alex"],
             "proposals":[
              {"kind":"task","title":"Ring the garage about the service","words":"tomorrow"},
              {"kind":"task","title":"Buy milk"},
              {"kind":"idea","title":"Family film night on Fridays"},
              {"kind":"reply","title":"Reply","body":"Yes"},
              {"kind":"booking","title":"Hotel","date":"2026-11-01"},
              {"kind":"reminder","title":"Call Sam","time":"18:00"},
              {"kind":"event","title":"Cinema","date":"2026-10-16"},
              {"kind":"task","title":"Sixth one"}
            ]}
            ```
        """.trimIndent()
        val a = VoiceNoteCodec.parseModelAnswer(answer)
        assertTrue(a.note.startsWith("Ring the garage"))
        assertEquals(listOf("Sam", "Alex"), a.people)
        assertEquals(listOf("task", "task", "idea", "reminder", "event"), a.proposals.map { it.kind })
        // Chatter is nothing.
        assertEquals(VoiceNoteCodec.ModelAnswer("", emptyList(), emptyList()), VoiceNoteCodec.parseModelAnswer("Sure! Here you go."))
        // A note past the limit is dropped; the cards still come.
        val long = VoiceNoteCodec.parseModelAnswer("""{"note":"${"x".repeat(1_501)}","proposals":[{"kind":"task","title":"Buy milk"}]}""")
        assertEquals("", long.note)
        assertEquals(1, long.proposals.size)
    }

    @Test
    fun aResponseRoundTrips() {
        val r = VoiceNoteCodec.Response(
            AskCodec.Response.ANSWERED, "Buy milk.", listOf("Sam"),
            listOf(MessageRequestCodec.Proposal("idea", "Family film night"), MessageRequestCodec.Proposal("task", "Buy milk", words = "today")),
        )
        assertEquals(r, VoiceNoteCodec.decodeResponse(VoiceNoteCodec.encodeResponse(r)))
        val off = VoiceNoteCodec.Response(AskCodec.Response.OFF, reason = "Navilife's AI is off")
        assertEquals(off, VoiceNoteCodec.decodeResponse(VoiceNoteCodec.encodeResponse(off)))
    }
}
