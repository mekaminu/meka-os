package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyCheckCodecTest {
    private val idea = MoneyCheckCodec.Idea(
        "habit-coffee", "habit", "Coffee 12× in 30 days", "~£250/yr", "£42.00 in the last 30 days. Halving it saves about £250 a year.",
        listOf(MoneyCheckCodec.Pay("Starbucks", "£3.50"), MoneyCheckCodec.Pay("Costa", "£3.20")),
    )
    private val request = MoneyCheckCodec.Request("2026-10-28", listOf(idea))

    @Test
    fun aRequestRoundTrips() {
        assertEquals(request, MoneyCheckCodec.decodeRequest(MoneyCheckCodec.encodeRequest(request)))
    }

    @Test
    fun aRequestOutsideTheLimitsIsRefused() {
        assertFailsWith<WireFormatException> { MoneyCheckCodec.decodeRequest(MoneyCheckCodec.encodeRequest(request.copy(ideas = emptyList()))) }
        assertFailsWith<WireFormatException> { MoneyCheckCodec.decodeRequest(MoneyCheckCodec.encodeRequest(request.copy(ideas = listOf(idea, idea)))) }
        assertFailsWith<WireFormatException> { MoneyCheckCodec.decodeRequest(MoneyCheckCodec.encodeRequest(request.copy(date = "today"))) }
        val many = idea.copy(behind = List(7) { MoneyCheckCodec.Pay("Costa", "£1") })
        assertFailsWith<WireFormatException> { MoneyCheckCodec.decodeRequest(MoneyCheckCodec.encodeRequest(request.copy(ideas = listOf(many)))) }
    }

    @Test
    fun aResponseRoundTrips() {
        val r = MoneyCheckCodec.Response(AskCodec.Response.ANSWERED, listOf(MoneyCheckCodec.Worded("subs", "Your subscriptions", "Worth a look.")))
        assertEquals(r, MoneyCheckCodec.decodeResponse(MoneyCheckCodec.encodeResponse(r)))
        val off = MoneyCheckCodec.Response(AskCodec.Response.OFF, reason = "Navilife's AI is off")
        assertEquals(off, MoneyCheckCodec.decodeResponse(MoneyCheckCodec.encodeResponse(off)))
    }

    @Test
    fun theModelsAnswerKeepsKnownIdsOnceInItsOrder() {
        val text = """```json
            {"ideas":[{"id":"subs","title":"Subscriptions","why":"Check them."},{"id":"made-up","title":"x","why":"y"},
            {"id":"interest","title":"Card interest","why":"Clear the cards."},{"id":"subs","title":"Again","why":"Again."},
            {"id":"habit-coffee","title":"No why"}]}
            ```"""
        val r = MoneyCheckCodec.parseModelAnswer(text, setOf("subs", "interest", "habit-coffee"))
        assertEquals(AskCodec.Response.ANSWERED, r.state)
        assertEquals(listOf("subs", "interest"), r.ideas.map { it.id })
        assertEquals("Subscriptions", r.ideas.first().title)
        assertTrue(MoneyCheckCodec.parseModelAnswer("I think you should save more.", setOf("subs")).ideas.isEmpty())
    }
}
