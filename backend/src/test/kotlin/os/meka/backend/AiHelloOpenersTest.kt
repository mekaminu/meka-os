package os.meka.backend

import os.meka.core.domain.HelloFreshRules
import os.meka.core.wire.AskCodec
import os.meka.core.wire.HelloOpenersCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fresh hello openers (hello slice 4): one small, tool-less call with nothing about him in it. */
class AiHelloOpenersTest {
    private class FakeModel(var outcome: ModelOutcome) : LanguageModelProvider {
        override val id = "fake"
        val asked = mutableListOf<ModelRequest>()
        override fun complete(request: ModelRequest): ModelOutcome { asked += request; return outcome }
    }

    private fun answered(text: String) = ModelOutcome.Answered(text, "claude-haiku-9", 300, 400, "end_turn")

    @Test
    fun theSmallModelWritesThemWithNothingAboutHim() {
        val lines = (1..8).joinToString(",") { """{"part":"evening","open":"Hey {n}, line $it.","ask":"What's up?"}""" }
        val model = FakeModel(answered("""{"openers":[$lines,{"part":"late","open":"Still up, {n}?","ask":"Yes?"}]}"""))
        val r = HelloOpenersService(model).write(HelloOpenersCodec.Request(6))
        assertEquals(AskCodec.Response.ANSWERED, r.state)
        assertEquals(6, r.openers.count { it.part == "evening" })
        assertEquals(1, r.openers.count { it.part == "late" })
        val sent = model.asked.single()
        assertEquals(HelloFreshRules.FEATURE, sent.feature)
        assertEquals(ModelTier.SMALL, sent.tier)
        assertTrue("{n}" in sent.system)
        val turn = sent.turns.single().text
        assertEquals("Write 6 hellos for each part of the day: morning, afternoon, evening, late.", turn)
        assertFalse("Meka" in sent.system + turn)
    }

    @Test
    fun offOverBudgetAndFailuresSayWhy() {
        val req = HelloOpenersCodec.Request(6)
        assertEquals(AskCodec.Response.OFF, HelloOpenersService(null).write(req).state)
        assertEquals(AskCodec.Response.OVER, HelloOpenersService(FakeModel(ModelOutcome.OverBudget)).write(req).state)
        assertEquals(AskCodec.Response.FAILED, HelloOpenersService(FakeModel(ModelOutcome.Failed("x"))).write(req).state)
        assertTrue(HelloOpenersService(FakeModel(answered("Hello!"))).write(req).openers.isEmpty())
    }
}
