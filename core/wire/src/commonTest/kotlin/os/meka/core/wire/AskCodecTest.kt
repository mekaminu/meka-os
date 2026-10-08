package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Ask MEKA on the wire (build plan V1, AI layer slice 3). */
class AskCodecTest {
    private val req = AskCodec.Request(
        "What's left today?", "2026-10-08", "Thursday 8 October 2026 · 17:05",
        listOf(AskCodec.Item("t1", "task", "Book dentist · anytime today"), AskCodec.Item("", "event", "19:00–20:00 · Training")),
    )

    @Test
    fun aRequestRoundTripsAndLimitsAreRefusedNotTrimmed() {
        assertEquals(req, AskCodec.decodeRequest(AskCodec.encodeRequest(req)))
        fun bad(r: AskCodec.Request) = assertFailsWith<WireFormatException> { AskCodec.decodeRequest(AskCodec.encodeRequest(r)) }
        bad(req.copy(question = "  "))
        bad(req.copy(question = "q".repeat(501)))
        bad(req.copy(date = "tomorrow"))
        bad(req.copy(items = listOf(AskCodec.Item("task-uuid-123", "task", "x"))))
        bad(req.copy(items = listOf(AskCodec.Item("t1", "email", "x"))))
        bad(req.copy(items = listOf(AskCodec.Item("t1", "task", "x".repeat(161)))))
        bad(req.copy(items = List(61) { AskCodec.Item("", "event", "x") }))
        assertFailsWith<WireFormatException> { AskCodec.decodeRequest("""{"w":2,"question":"q","date":"2026-10-08","now":"","items":[]}""") }
        assertFailsWith<WireFormatException> { AskCodec.decodeRequest("not json") }
    }

    @Test
    fun aResponseRoundTrips() {
        val r = AskCodec.Response(
            AskCodec.Response.ANSWERED, "Two things left.",
            listOf(AskCodec.Action("move_task", ref = "t1", date = "2026-10-09", time = "09:00"), AskCodec.Action("set_timer", minutes = 20)),
        )
        assertEquals(r, AskCodec.decodeResponse(AskCodec.encodeResponse(r)))
        val off = AskCodec.Response(AskCodec.Response.OFF, reason = "No AI key set")
        assertEquals(off, AskCodec.decodeResponse(AskCodec.encodeResponse(off)))
    }

    @Test
    fun aModelAnswerIsReadAsWordsAndActionsAndAnythingElseIsJustWords() {
        val (words, actions) = AskCodec.parseModelAnswer(
            """```json
            {"answer": "Moved it.", "actions": [{"kind": "move_task", "ref": "t1", "date": "2026-10-09", "time": "09:00", "hours": "12"},
              {"kind": 5}, "junk", {"kind": "set_timer", "minutes": 20}]}
            ```""",
        )
        assertEquals("Moved it.", words)
        // Wrong types are dropped field by field ("12" as a string isn't hours); an action with no kind is dropped.
        assertEquals(listOf(AskCodec.Action("move_task", "t1", null, "2026-10-09", "09:00", null, null), AskCodec.Action("set_timer", minutes = 20)), actions)

        val (plain, none) = AskCodec.parseModelAnswer("  You have two things left: dentist and the CR.  ")
        assertEquals("You have two things left: dentist and the CR.", plain)
        assertTrue(none.isEmpty())
        // JSON without an answer is not trusted for actions either.
        assertTrue(AskCodec.parseModelAnswer("""{"actions": [{"kind": "set_timer", "minutes": 5}]}""").second.isEmpty())
        // At most five actions are read.
        val many = (1..9).joinToString(",") { """{"kind":"set_timer","minutes":$it}""" }
        assertEquals(5, AskCodec.parseModelAnswer("""{"answer":"ok","actions":[$many]}""").second.size)
        assertEquals(AskCodec.MAX_ANSWER, AskCodec.parseModelAnswer("z".repeat(3000)).first.length)
    }
}
