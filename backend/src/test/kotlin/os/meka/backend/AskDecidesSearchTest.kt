package os.meka.backend

import os.meka.core.domain.CantCheckRules
import os.meka.core.domain.LiveFactsRules
import os.meka.core.domain.WebHit
import os.meka.core.wire.AskCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Obi decides when to search (Meka 2026-11-17: "What if I ask random question?"): a current question with none of the
 * word list's words is searched when Obi asks for it; timeless questions and his own day never are, and the cap holds.
 */
class AskDecidesSearchTest {
    private class Model(vararg val texts: String) : LanguageModelProvider {
        override val id = "fake"
        val asked = mutableListOf<ModelRequest>()
        override fun complete(request: ModelRequest): ModelOutcome {
            asked += request
            return ModelOutcome.Answered(texts[minOf(asked.size - 1, texts.size - 1)], "m", 900, 80, "end_turn")
        }
    }

    private class Search(var fail: Boolean = false) : NumberSearch {
        val queries = mutableListOf<String>()
        override fun configured() = true
        override fun search(query: String): List<WebHit> {
            queries += query
            if (fail) throw SearchFailed("rate limited (429)")
            return listOf(WebHit("https://www.example.co.uk/news", "Latest", "Fresh result for $query."))
        }
    }

    private fun request(q: String, voice: Boolean = false) =
        AskCodec.Request(q, "2026-11-17", "Tuesday 17 November 2026 · 09:40", listOf(AskCodec.Item("e1", "event", "Thursday 10:00 Dentist")), voice = voice)

    private fun service(model: Model, facts: LiveFacts) = AskService(
        model, live = { facts.look(it) }, retryLive = { facts.retry(it) }, decideLive = { facts.decide(it) },
    )

    private val fromMemory = """{"answer":"From what I know, it's Alex Smith, but that may be out of date.","actions":[],"search":"Arsenal manager"}"""
    private val fromWeb = """{"answer":"Arsenal's manager is Sam Jones.","actions":[],"sources":[1]}"""

    @Test
    fun aCurrentQuestionWithNoListWordsIsSearchedWhenObiAsks() {
        for (q in listOf("who's the Arsenal manager?", "how much is a PS5?")) {
            assertNull(LiveFactsRules.query(q), q) // the word list doesn't catch these
            val model = Model(fromMemory, fromWeb)
            val search = Search()
            val r = service(model, LiveFacts(search)).ask(request(q))
            assertEquals("Arsenal's manager is Sam Jones.", r.answer)
            assertEquals(listOf("Arsenal manager"), search.queries)
            assertEquals(2, model.asked.size)
            // The first call was offered the search; the second read the web and isn't offered another.
            assertTrue(AskService.SEARCH in model.asked[0].system)
            assertFalse(AskService.SEARCH in model.asked[1].system)
            assertTrue("<web query=\"Arsenal manager\">" in model.asked[1].turns.last().text)
            // An answer that read the web proposes nothing and shows its source.
            assertTrue(r.actions.isEmpty())
            assertEquals("https://www.example.co.uk/news", r.sources.single().url)
        }
    }

    @Test
    fun timelessQuestionsAndJokesTakeOneCallAndNoSearch() {
        for (q in listOf("why is the sky blue?", "tell me a joke")) {
            val model = Model("""{"answer":"Rayleigh scattering.","actions":[]}""")
            val search = Search()
            val r = service(model, LiveFacts(search)).ask(request(q))
            assertEquals("Rayleigh scattering.", r.answer)
            assertTrue(search.queries.isEmpty())
            assertEquals(1, model.asked.size)
        }
    }

    @Test
    fun hisDayIsNeverOfferedASearchAndTakesNoExtraCall() {
        // Even a model that asks for one is ignored: a look-up of his day never sees the offer.
        val model = Model("""{"answer":"The dentist at 10:00.","actions":[],"search":"thursday events"}""")
        val search = Search()
        val r = service(model, LiveFacts(search)).ask(request("what's on Thursday?"))
        assertEquals("The dentist at 10:00.", r.answer)
        assertTrue(search.queries.isEmpty())
        assertEquals(1, model.asked.size)
        assertFalse(AskService.SEARCH in model.asked.single().system)
        assertFalse(LiveFactsRules.mayDecide("what's on Thursday?"))
        assertFalse(LiveFactsRules.mayDecide("what's my wife doing on Friday?"))
        assertFalse(LiveFactsRules.mayDecide("add milk to the shopping list"))
        assertTrue(LiveFactsRules.mayDecide("who's the Arsenal manager?"))
    }

    @Test
    fun aSearchAboutHisOwnLifeIsNeverRun() {
        val model = Model("""{"answer":"From what I know, no idea.","actions":[],"search":"my wife's birthday"}""")
        val search = Search()
        val r = service(model, LiveFacts(search)).ask(request("what's the best-selling car in the UK?"))
        assertTrue(search.queries.isEmpty())
        assertEquals(1, model.asked.size)
        assertEquals("From what I know, no idea.", r.answer)
    }

    @Test
    fun theDailyCapHoldsAndSaysSo() {
        val search = Search()
        val facts = LiveFacts(search)
        repeat(LiveFactsRules.MAX_PER_DAY) { facts.retry("test query $it") }
        val model = Model("""{"answer":"It's Alex Smith.","actions":[],"search":"Arsenal manager"}""")
        val r = service(model, facts).ask(request("who's the Arsenal manager?"))
        assertEquals(1, model.asked.size)
        assertEquals(LiveFactsRules.MAX_PER_DAY, search.queries.size)
        assertEquals("It's Alex Smith. " + LiveFactsRules.FROM_MEMORY + " " + CantCheckRules.USED_UP, r.answer)
    }

    @Test
    fun aFailedSearchKeepsTheAnswerFromMemoryWithWhy() {
        val model = Model(fromMemory)
        val r = service(model, LiveFacts(Search(fail = true))).ask(request("who's the Arsenal manager?"))
        assertEquals(1, model.asked.size)
        // It already says it may be out of date, so that isn't said twice.
        assertEquals("From what I know, it's Alex Smith, but that may be out of date. " + CantCheckRules.FAILED, r.answer)
    }

    @Test
    fun theWordListFastPathStillSearchesFirstWithNoSecondCall() {
        val model = Model(fromWeb)
        val search = Search()
        service(model, LiveFacts(search)).ask(request("latest news on the strike"))
        assertEquals(1, search.queries.size)
        assertEquals(1, model.asked.size)
    }

    @Test
    fun aSpokenAnswerAsksInTheBlockAfterItsWordsAndIsSearched() {
        val model = Model(
            "From what I know it's Alex Smith, may be out of date.\n<after>{\"search\":\"Arsenal manager\"}</after>",
            "Arsenal's manager is Sam Jones.\n<after>{\"sources\":[1]}</after>",
        )
        val search = Search()
        val r = service(model, LiveFacts(search)).askStream(request("who's the Arsenal manager?", voice = true)) { }
        assertEquals(listOf("Arsenal manager"), search.queries)
        assertTrue(r.answer.orEmpty().startsWith("Arsenal's manager is Sam Jones"), r.answer)
    }

    @Test
    fun theReportCountsTheSearchesObiChose() {
        val search = Search()
        val facts = LiveFacts(search)
        facts.decide("Arsenal manager")
        facts.look("latest news")
        assertEquals("live answers: 2 of ${LiveFactsRules.MAX_PER_DAY} searches today (1 chosen by Obi)", facts.report())
    }

    @Test
    fun theModelsSearchIsReadFromJsonOrTheSpokenBlock() {
        assertEquals("PS5 price UK", AskCodec.modelSearch("""{"answer":"x","search":"PS5  price UK"}"""))
        assertNull(AskCodec.modelSearch("""{"answer":"x"}"""))
        assertNull(AskCodec.modelSearch("""{"answer":"x","search":3}"""))
        assertEquals("a b", AskCodec.modelSearch(AskCodec.normaliseModelAnswer("Words.\n<after>{\"search\":\"a b\"}</after>")))
        assertEquals("Fine. " + LiveFactsRules.FROM_MEMORY, LiveFactsRules.fromMemory("Fine."))
    }
}
