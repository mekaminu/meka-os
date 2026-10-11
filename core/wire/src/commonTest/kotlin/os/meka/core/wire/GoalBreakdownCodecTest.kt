package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** "Break it down" on the wire (build plan V1, Goals, properly, slice 4). */
class GoalBreakdownCodecTest {
    private val req = GoalBreakdownCodec.Request(
        "2026-10-11", "Sunday 11 October 2026 · 21:40", "Run a half marathon", "This year", "by Thu 31 Dec",
        "To feel fit again", "Under 2 hours", listOf("Run 5k"), listOf("Buy running shoes"),
    )

    @Test
    fun aRequestRoundTripsAndLimitsAreRefusedNotTrimmed() {
        assertEquals(req, GoalBreakdownCodec.decodeRequest(GoalBreakdownCodec.encodeRequest(req)))
        assertEquals(req.copy(due = "", why = "", done = "", milestones = emptyList(), steps = emptyList()),
            GoalBreakdownCodec.decodeRequest(GoalBreakdownCodec.encodeRequest(req.copy(due = "", why = "", done = "", milestones = emptyList(), steps = emptyList()))))
        fun bad(r: GoalBreakdownCodec.Request) =
            assertFailsWith<WireFormatException> { GoalBreakdownCodec.decodeRequest(GoalBreakdownCodec.encodeRequest(r)) }
        bad(req.copy(title = " "))
        bad(req.copy(title = "x".repeat(201)))
        bad(req.copy(why = "x".repeat(501)))
        bad(req.copy(milestones = List(13) { "m" }))
        bad(req.copy(steps = listOf("")))
        bad(req.copy(steps = listOf("x".repeat(121))))
        bad(req.copy(date = "today"))
        assertFailsWith<WireFormatException> { GoalBreakdownCodec.decodeRequest("not json") }
    }

    @Test
    fun aResponseRoundTrips() {
        val r = GoalBreakdownCodec.Response("answered", listOf("Run 10k", "Run 15k"), listOf("Book a gait analysis"))
        assertEquals(r, GoalBreakdownCodec.decodeResponse(GoalBreakdownCodec.encodeResponse(r)))
        val off = GoalBreakdownCodec.Response("off", reason = "MEKA's AI is off")
        assertEquals(off, GoalBreakdownCodec.decodeResponse(GoalBreakdownCodec.encodeResponse(off)))
    }

    @Test
    fun aModelAnswerIsReadOnlyAsTheJsonOfStrings() {
        val text = """
            Sure!
            ```json
            {"milestones":["Run 10k", 42, "", "Run 15k", "a","b","c","d","e","f"], "steps":["Book a gait analysis", {"x":1}, "Plan three runs", "Join a club", "Stretch"]}
            ```
        """.trimIndent()
        val r = GoalBreakdownCodec.parseModelAnswer(text)
        assertEquals("answered", r.state)
        assertEquals(listOf("Run 10k", "Run 15k", "a", "b", "c", "d", "e"), r.milestones)
        assertEquals(listOf("Book a gait analysis", "Plan three runs", "Join a club"), r.steps)
        val none = GoalBreakdownCodec.parseModelAnswer("I can't help with that.")
        assertTrue(none.milestones.isEmpty() && none.steps.isEmpty())
        assertTrue(GoalBreakdownCodec.parseModelAnswer("{\"milestones\": \"none\"}").milestones.isEmpty())
    }
}
