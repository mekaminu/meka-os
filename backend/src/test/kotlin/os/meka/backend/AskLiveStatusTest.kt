package os.meka.backend

import os.meka.core.domain.CantCheckRules
import os.meka.core.domain.LineState
import os.meka.core.domain.LiveFactsRules
import os.meka.core.domain.WebHit
import os.meka.core.wire.AskCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Obi answers live status (Meka 2026-11-17: "I thought we fixed Obi so it can get answers outside the app"): a train
 * question answers from the trains lines, a line off his route asks TfL, the world is searched, and "I can't check live"
 * is only said when the search couldn't run.
 */
class AskLiveStatusTest {
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
            return listOf(WebHit("https://www.example.co.uk/m25", "M25 traffic", "Clear in both directions."))
        }
    }

    private class Tfl : os.meka.backend.integrations.LineStatusProvider {
        override val id = "lines"
        override val label = "TfL"
        val asked = mutableListOf<List<String>>()
        override fun statuses(lines: List<String>): List<LineState> { asked += lines; return lines.map { LineState(it, if (it == "jubilee") 9 else 10) } }
    }

    /** His question of 08:11, with the invented route's line and stations (rule 13). */
    private val his = "can u check metro line from Oakfield to Riverside is now ok"
    private val trains = listOf(
        AskCodec.Item("", "trains", "train lines on Meka's route (TfL status, checked 08:09): South Rail good service; Metro line good service"),
        AskCodec.Item("", "trains", "next trains from Oakfield to Oakfield Junction (live departures, checked 08:05): 08:31 on time, platform 2"),
    )
    private fun request(q: String, items: List<AskCodec.Item> = trains) =
        AskCodec.Request(q, "2026-11-17", "Tuesday 17 November 2026 · 08:11", items)

    private fun service(model: Model, search: Search, tfl: Tfl = Tfl(), facts: LiveFacts = LiveFacts(search)) = AskService(
        model, live = { facts.look(it) }, retryLive = { facts.retry(it) }, tfl = { ids -> TflOnDemand(tfl).statuses(ids) },
    )

    @Test
    fun hisQuestionAnswersFromTheTrainsLinesWithoutASearch() {
        val model = Model("""{"answer":"The Metro line has good service as of 08:09; your 08:31 is on time.","actions":[]}""")
        val search = Search()
        val tfl = Tfl()
        val r = service(model, search, tfl).ask(request(his))
        assertEquals("The Metro line has good service as of 08:09; your 08:31 is on time.", r.answer)
        // The route's fresh status answers it: no web search, no TfL call, one model call.
        assertTrue(search.queries.isEmpty())
        assertTrue(tfl.asked.isEmpty())
        val sent = model.asked.single()
        val turn = sent.turns.last().text
        assertTrue("trains: train lines on Meka's route (TfL status, checked 08:09)" in turn, turn)
        assertTrue("Its trains lines are today's trains on his route, live" in sent.system)
        // The prompt no longer sends him to other apps.
        assertFalse("you can't look anything up live" in sent.system)
        assertTrue("never send him to another app or website" in sent.system)
    }

    @Test
    fun aLineOffHisRouteIsAskedOfTflOnDemand() {
        val model = Model("""{"answer":"TfL shows minor delays on the Jubilee line right now.","actions":[]}""")
        val search = Search()
        val tfl = Tfl()
        service(model, search, tfl).ask(request("is the jubilee line ok now?"))
        assertEquals(listOf(listOf("jubilee")), tfl.asked)
        assertTrue(search.queries.isEmpty())
        val turn = model.asked.single().turns.last().text
        assertTrue("<lines source=\"TfL\" read=\"just now\">\nJubilee line: minor delays\n</lines>" in turn, turn)
        // A TfL line on his route is never asked again while the trains lines have it fresh.
        val fresh = listOf(AskCodec.Item("", "trains", "train lines on Meka's route (TfL status, checked 08:09): DLR good service"))
        service(model, search, tfl).ask(request("is the dlr ok?", fresh))
        assertEquals(1, tfl.asked.size)
        assertTrue(search.queries.isEmpty())
        // With an old status, it is asked too.
        val old = listOf(AskCodec.Item("", "trains", "train lines on Meka's route: TfL status last read at 06:40, over an hour ago: DLR good service"))
        service(model, search, tfl).ask(request("is the dlr ok?", old))
        assertEquals(listOf("dlr"), tfl.asked.last())
    }

    @Test
    fun theWorldNowIsSearched() {
        val model = Model("""{"answer":"Example says the M25 is clear.","actions":[],"sources":[1]}""")
        val search = Search()
        val r = service(model, search).ask(request("is the M25 ok now", emptyList()))
        assertEquals(listOf("is the M25 ok now"), search.queries)
        assertEquals("https://www.example.co.uk/m25", r.sources.single().url)
    }

    @Test
    fun theSafetyNetSearchesOnceAndAsksAgain() {
        // The question's words don't read as live, the model says it can't check: searched, asked once more.
        val model = Model(
            """{"answer":"I can't check live status from here, but TfL's status page will show the latest.","actions":[]}""",
            """{"answer":"Example says the A12 is clear this morning.","actions":[],"sources":[1]}""",
        )
        val search = Search()
        val q = "how's the A12 looking for the drive in"
        assertNull(LiveFactsRules.query(q))
        val r = service(model, search).ask(request(q, emptyList()))
        assertEquals("Example says the A12 is clear this morning.", r.answer)
        assertEquals(listOf(q), search.queries)
        assertEquals(2, model.asked.size)
        assertTrue("<web query=" in model.asked.last().turns.last().text)
        // An answer that can check isn't asked again.
        val fine = Model("""{"answer":"Sounds good.","actions":[]}""")
        service(fine, Search()).ask(request(q, emptyList()))
        assertEquals(1, fine.asked.size)
        // His own life is never searched, even by the safety net.
        val own = Model("""{"answer":"I can't check that live right now.","actions":[]}""")
        val ownSearch = Search()
        val o = service(own, ownSearch).ask(request("how's my car insurance claim going", emptyList()))
        assertTrue(ownSearch.queries.isEmpty())
        assertEquals("I can't check that live right now.", o.answer)
        assertEquals(1, own.asked.size)
    }

    @Test
    fun whenTheSearchCantRunObiSaysWhich() {
        val q = "how's the A12 looking for the drive in"
        val failed = Model("""{"answer":"I can't check that live right now.","actions":[]}""")
        val f = service(failed, Search(fail = true)).ask(request(q, emptyList()))
        assertEquals("I can't check that live right now. " + CantCheckRules.FAILED, f.answer)
        assertEquals(1, failed.asked.size)
        // The cap still holds: with the day's searches used, nothing more is searched and Obi says so.
        val search = Search()
        val facts = LiveFacts(search)
        repeat(LiveFactsRules.MAX_PER_DAY) { facts.look("latest news on the rail strike") }
        val used = Model("""{"answer":"I can't check that live right now.","actions":[]}""")
        val u = service(used, search, facts = facts).ask(request(q, emptyList()))
        assertEquals(LiveFactsRules.MAX_PER_DAY, search.queries.size)
        assertEquals("I can't check that live right now. " + CantCheckRules.USED_UP, u.answer)
    }

    @Test
    fun tflOnDemandKeepsItsAnswerAndItsHourlyLimit() {
        var now = 0L
        val tfl = Tfl()
        val t = TflOnDemand(tfl, now = { now })
        assertEquals(listOf(LineState("jubilee", 9)), t.statuses(listOf("jubilee")))
        t.statuses(listOf("jubilee"))
        assertEquals(1, tfl.asked.size)
        now += TflOnDemand.KEEP_MS
        repeat(TflOnDemand.MAX_PER_HOUR + 5) { i -> t.statuses(listOf("line-$i")) }
        assertEquals(TflOnDemand.MAX_PER_HOUR, tfl.asked.size)
        assertNull(t.statuses(emptyList()))
    }
}
