package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The AI layer's second slice (build plan V1, ADR-006 §4–5): the cloud provider, its meter and the monthly budget. */
class AiModelsTest {
    private var now = 1_791_450_000_000L // 2026-10-08 UTC
    private val goodKey = "sk-ant-api03-" + "a".repeat(40)

    private class FakeHttp : HttpExchange {
        val calls = mutableListOf<Triple<String, String, String?>>()
        val headers = mutableListOf<Map<String, String>>()
        var models = """{"data":[{"id":"claude-opus-9"},{"id":"claude-sonnet-9"},{"id":"claude-haiku-9"},{"id":"claude-sonnet-8"}]}"""
        var modelsStatus = 200
        var status = 200
        var fail = false
        var answer = """{"model":"claude-haiku-9","content":[{"type":"text","text":"Added "},{"type":"text","text":"it."}],
            "stop_reason":"end_turn","usage":{"input_tokens":1000,"output_tokens":200}}"""

        override fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
            calls += Triple(method, url, body)
            this.headers += headers
            if (fail) throw java.io.IOException("down")
            return if (url.contains("/v1/models")) modelsStatus to models else status to answer
        }
    }

    private fun ask(text: String = "What's on today?", tier: ModelTier = ModelTier.SMALL, maxTokens: Int = 1024) =
        ModelRequest("ask", tier, "You are Navilife.", listOf(ModelTurn(ModelTurn.Role.USER, text)), maxTokens)

    private fun setup(usd: Long = 20, key: () -> String? = { goodKey }, alerts: MutableList<AiBudget.Level> = mutableListOf()): Triple<AnthropicProvider, AiBudget, FakeHttp> {
        val http = FakeHttp()
        val budget = AiBudget(usd * 1_000_000, InMemoryAiUsageStore(), nowMs = { now }, onAlert = { l, _ -> alerts += l })
        return Triple(AnthropicProvider(key, budget, http, ModelCatalog(key, http, nowMs = { now })), budget, http)
    }

    @Test
    fun aCallSendsTextOnlyWithNoToolsAndCountsItsTokens() {
        val (p, budget, http) = setup()
        val out = assertIs<ModelOutcome.Answered>(p.complete(ask()))
        assertEquals("Added it.", out.text)
        assertEquals(1000, out.inputTokens)
        assertEquals(200, out.outputTokens)
        val (method, url, body) = http.calls.last()
        assertEquals("POST", method)
        assertEquals("https://api.anthropic.com/v1/messages", url)
        assertEquals(goodKey, http.headers.last()["x-api-key"])
        val sent = Json.parseToJsonElement(body!!).jsonObject
        assertEquals("claude-haiku-9", sent["model"]!!.jsonPrimitive.content)
        assertEquals("You are Navilife.", sent["system"]!!.jsonPrimitive.content)
        assertEquals("user", sent["messages"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content)
        assertFalse("tools" in sent)
        // Haiku: $1 in, $5 out per million tokens → 1000 + 1000 micro-dollars.
        val s = budget.state()
        assertEquals(2000, s.spentMicroUsd)
        assertEquals("2026-10", s.month)
        assertEquals(1, s.byFeature.single().calls)
        assertEquals("ask", s.byFeature.single().feature)
    }

    @Test
    fun eachTierUsesTheNewestModelOfItsFamilyLookedUpOnceADay() {
        val (p, _, http) = setup()
        p.complete(ask(tier = ModelTier.FRONTIER))
        val model = { Json.parseToJsonElement(http.calls.last().third!!).jsonObject["model"]!!.jsonPrimitive.content }
        assertEquals("claude-sonnet-9", model())
        p.complete(ask())
        assertEquals("claude-haiku-9", model())
        assertEquals(1, http.calls.count { it.second.contains("/v1/models") })
        now += 25 * 3_600_000L
        http.models = """{"data":[{"id":"claude-sonnet-10"}]}"""
        p.complete(ask(tier = ModelTier.FRONTIER))
        assertEquals("claude-sonnet-10", model())
        p.complete(ask())
        assertEquals("claude-haiku-4-5", model()) // no haiku listed: the fixed fallback
    }

    @Test
    fun aListThatFailsFallsBackToTheFixedModels() {
        val (p, _, http) = setup()
        http.modelsStatus = 500
        p.complete(ask(tier = ModelTier.FRONTIER))
        assertEquals("claude-sonnet-4-5", Json.parseToJsonElement(http.calls.last().third!!).jsonObject["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun maxTokensIsClampedAndRequestsAreChecked() {
        val (p, _, http) = setup()
        p.complete(ask(maxTokens = 100_000))
        assertEquals(4096, Json.parseToJsonElement(http.calls.last().third!!).jsonObject["max_tokens"]!!.jsonPrimitive.int)
        assertFailsWith<IllegalArgumentException> { ModelRequest("Meka's email", ModelTier.SMALL, "", listOf(ModelTurn(ModelTurn.Role.USER, "x"))) }
        assertFailsWith<IllegalArgumentException> { ModelRequest("ask", ModelTier.SMALL, "", emptyList()) }
        assertFailsWith<IllegalArgumentException> { ModelRequest("ask", ModelTier.SMALL, "", listOf(ModelTurn(ModelTurn.Role.ASSISTANT, "x"))) }
    }

    @Test
    fun noKeyIsOffWithoutCallingAnything() {
        val (p, _, http) = setup(key = { null })
        assertEquals(ModelOutcome.Off, p.complete(ask()))
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun failuresSayWhyAndAreNotCounted() {
        val (p, budget, http) = setup()
        http.status = 401
        assertEquals(ModelOutcome.Failed("Anthropic refused the key"), p.complete(ask()))
        http.status = 429
        assertEquals(ModelOutcome.Failed("Anthropic is limiting requests (spend cap or rate limit)"), p.complete(ask()))
        http.status = 529
        assertEquals(ModelOutcome.Failed("Anthropic is busy; try again shortly"), p.complete(ask()))
        http.status = 200; http.answer = "not json"
        assertEquals(ModelOutcome.Failed("Anthropic's answer couldn't be read"), p.complete(ask()))
        http.fail = true
        assertEquals(ModelOutcome.Failed("Couldn't reach Anthropic"), p.complete(ask()))
        assertEquals(0, budget.state().spentMicroUsd)
    }

    @Test
    fun theBudgetAlertsAt70PercentAndStopsAt100UntilTheFirst() {
        val alerts = mutableListOf<AiBudget.Level>()
        // A $1 budget; each answer below costs 0.2 dollars (sonnet: 10k in × 3 + 11 334 out × 15 ≈ 200 000 micro-dollars).
        val (p, budget, http) = setup(usd = 1, alerts = alerts)
        http.answer = """{"model":"claude-sonnet-9","content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":10000,"output_tokens":11334}}"""
        repeat(3) { assertIs<ModelOutcome.Answered>(p.complete(ask())) }
        assertEquals(AiBudget.Level.OK, budget.state().level)
        assertIs<ModelOutcome.Answered>(p.complete(ask()))
        assertEquals(AiBudget.Level.ALERT, budget.state().level)
        assertIs<ModelOutcome.Answered>(p.complete(ask()))
        assertEquals(AiBudget.Level.OVER, budget.state().level)
        val callsBefore = http.calls.size
        assertEquals(ModelOutcome.OverBudget, p.complete(ask()))
        assertEquals(callsBefore, http.calls.size) // refused before calling
        assertEquals(listOf(AiBudget.Level.ALERT, AiBudget.Level.OVER), alerts) // once each
        // November starts afresh.
        now += 24L * 24 * 3_600_000
        assertEquals("2026-11", budget.month())
        assertEquals(AiBudget.Level.OK, budget.state().level)
        assertIs<ModelOutcome.Answered>(p.complete(ask()))
    }

    @Test
    fun aZeroBudgetMeansOff() {
        val (p, _, http) = setup(usd = 0)
        assertEquals(ModelOutcome.OverBudget, p.complete(ask()))
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun unknownModelsAreChargedAtTheHighestPriceAndCacheTokensCount() {
        assertEquals(ModelPrices.Price(1, 5), ModelPrices.of("claude-haiku-4-5"))
        assertEquals(ModelPrices.Price(15, 75), ModelPrices.of("some-new-model"))
        val (p, budget, http) = setup()
        http.answer = """{"model":"claude-haiku-9","content":[],"usage":{"input_tokens":10,"cache_read_input_tokens":90,"output_tokens":0}}"""
        assertEquals(100, assertIs<ModelOutcome.Answered>(p.complete(ask())).inputTokens)
        // Slice 3 of the conversation item: cache reads at a tenth of the input price (10 + 9 micro-dollars).
        assertEquals(19, budget.state().spentMicroUsd)
    }

    @Test
    fun cachedInputIsPricedAtTheCachesRatesAndRoundedUp() {
        // Haiku ($1 in): 1000 plain, 1000 written at 1.25×, 1000 read at 0.1×, plus 100 out at $5.
        assertEquals(1000 + 1250 + 100 + 500L, ModelPrices.costMicroUsd("claude-haiku-9", 3000, 100, 1000, 1000))
        assertEquals(ModelPrices.costMicroUsd("claude-haiku-9", 500, 10), ModelPrices.costMicroUsd("claude-haiku-9", 500, 10, 0, 0))
        assertEquals(1L, ModelPrices.costMicroUsd("claude-haiku-9", 3, 0, 0, 3)) // 0.3 rounds up, never down to free
        // More cache tokens than input can't make a call cheaper than nothing.
        assertEquals(13L, ModelPrices.costMicroUsd("claude-haiku-9", 10, 0, 50, 50))
    }

    @Test
    fun theUnchangingInstructionsAndTheEarlierTurnsAreMarkedForThePromptCache() {
        val (p, budget, http) = setup()
        http.answer = """{"model":"claude-haiku-9","content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":100,"cache_creation_input_tokens":0,"cache_read_input_tokens":6000,"output_tokens":10}}"""
        val turns = listOf(
            ModelTurn(ModelTurn.Role.USER, "Question: first"),
            ModelTurn(ModelTurn.Role.ASSISTANT, "{\"answer\":\"one\"}"),
            ModelTurn(ModelTurn.Role.USER, "Question: second"),
        )
        val fixed = "You are Ada. " + "Long rules. ".repeat(20)
        val out = assertIs<ModelOutcome.Answered>(p.complete(ModelRequest("ask", ModelTier.SMALL, fixed + "\nHis name is Alex.", turns, 600, fixed.length, 2)))
        assertEquals(6100, out.inputTokens)
        val sent = Json.parseToJsonElement(http.calls.last().third!!).jsonObject
        val system = sent["system"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, system.size)
        assertEquals(fixed, system[0]["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", system[0]["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("\nHis name is Alex.", system[1]["text"]!!.jsonPrimitive.content)
        assertFalse("cache_control" in system[1])
        val messages = sent["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("Question: first", messages[0]["content"]!!.jsonPrimitive.content)
        val marked = messages[1]["content"]!!.jsonArray.single().jsonObject
        assertEquals("{\"answer\":\"one\"}", marked["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", marked["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Question: second", messages[2]["content"]!!.jsonPrimitive.content) // the new turn, uncached
        // 100 plain + 6000 read at a tenth + 10 out at $5.
        assertEquals(100 + 600 + 50L, budget.state().spentMicroUsd)
        // Nothing marked: the plain shapes, as before.
        p.complete(ask())
        val plain = Json.parseToJsonElement(http.calls.last().third!!).jsonObject
        assertEquals("You are Navilife.", plain["system"]!!.jsonPrimitive.content)
        assertEquals("What's on today?", plain["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content)
        // A mark past the instructions or onto the newest turn is refused.
        assertFailsWith<IllegalArgumentException> { ModelRequest("ask", ModelTier.SMALL, "abc", turns, 600, 4) }
        assertFailsWith<IllegalArgumentException> { ModelRequest("ask", ModelTier.SMALL, "abc", turns, 600, 0, 3) }
    }

    @Test
    fun theStatusCarriesTheBudgetInCentsAndNeverTheKey() {
        val (_, budget, _) = setup()
        budget.record("ask", "claude-sonnet-9", 100_000, 10_000) // 0.30 + 0.15 dollars
        val layer = AiLayer(AiHealth(key = { goodKey }, http = { _, _ -> 200 }, nowMs = { now }), budget)
        val json = layer.statusJson()
        assertEquals("on", json["state"]!!.jsonPrimitive.content)
        val b = json["budget"]!!.jsonObject
        assertEquals(45, b["spentCents"]!!.jsonPrimitive.int)
        assertEquals(2000, b["budgetCents"]!!.jsonPrimitive.int)
        assertEquals("ok", b["level"]!!.jsonPrimitive.content)
        assertEquals("ask", b["features"]!!.jsonArray.single().jsonObject["feature"]!!.jsonPrimitive.content)
        assertFalse(goodKey in json.toString())
        assertFalse("budget" in AiLayer(AiHealth(key = { null }, nowMs = { now })).statusJson())
    }

    @Test
    fun theStatusCountsTodayByFeatureOnLondonsDay() {
        val (_, budget, _) = setup()
        now = 1_791_673_800_000L // 2026-10-10 23:10 UTC = 2026-10-11 00:10 in London
        budget.record("ask.talk", "claude-haiku-9", 1000, 200)
        budget.record("ask.talk", "claude-haiku-9", 1000, 200)
        budget.record("ask", "claude-haiku-9", 1000, 200)
        val s = budget.state()
        assertEquals("2026-10-11", s.day)
        assertEquals(listOf("ask" to 1L, "ask.talk" to 2L), s.today.map { it.feature to it.calls })
        val today = s.toJson()["today"]!!.jsonArray.map { it.jsonObject }
        assertEquals("ask.talk", today[1]["feature"]!!.jsonPrimitive.content)
        assertEquals(2, today[1]["calls"]!!.jsonPrimitive.int)
        assertEquals(2 * ModelPrices.costMicroUsd("claude-haiku-9", 1000, 200), today[1]["microUsd"]!!.jsonPrimitive.content.toLong())
        assertEquals("2026-10-11", s.toJson()["day"]!!.jsonPrimitive.content)
        // The next London day starts again; the month keeps counting.
        now += 24 * 3_600_000L
        assertTrue(budget.state().today.isEmpty())
        assertEquals(3, budget.state().byFeature.sumOf { it.calls })
    }

    @Test
    fun theUsageTableIsMeteredInPostgres() {
        val url = System.getenv("MEKA_TEST_DB_URL") ?: return
        com.zaxxer.hikari.HikariDataSource(com.zaxxer.hikari.HikariConfig().apply {
            jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
            password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 4
        }).use { ds ->
            Migrations.apply(ds)
            ds.connection.use { c -> c.createStatement().execute("DELETE FROM ai_usage WHERE month = '1999-01'") }
            val store = PostgresAiUsageStore(ds)
            store.add("1999-01", "ask", 100, 10, 150)
            store.add("1999-01", "ask", 50, 5, 75)
            store.add("1999-01", "extract.email", 1, 1, 6)
            assertEquals(
                listOf(AiUsage("ask", 2, 150, 15, 225), AiUsage("extract.email", 1, 1, 1, 6)),
                store.month("1999-01"),
            )
            ds.connection.use { c -> c.createStatement().execute("DELETE FROM ai_usage_day WHERE day = '1999-01-02'") }
            store.addDay("1999-01-02", "ask.talk", 100, 10, 150)
            store.addDay("1999-01-02", "ask.talk", 50, 5, 75)
            assertEquals(listOf(AiUsage("ask.talk", 2, 150, 15, 225)), store.day("1999-01-02"))
        }
    }

    @Test
    fun aStreamedCallHandsOverTheWordsAsTheyComeAndMetersOnce() {
        // The companion, slice 9: Anthropic's server-sent events, read line by line.
        val http = FakeHttp()
        val budget = AiBudget(20_000_000, InMemoryAiUsageStore(), nowMs = { now })
        val sent = mutableListOf<String?>()
        var status = 200
        var events = listOf(
            "event: message_start",
            """data: {"type":"message_start","message":{"model":"claude-haiku-9","usage":{"input_tokens":1000,"output_tokens":1}}}""",
            "",
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Added "}}""",
            """data: {"type":"ping"}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"it."}}""",
            """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":200}}""",
            """data: {"type":"message_stop"}""",
        )
        val lines = HttpLines { _, _, headers, body, onLine -> sent += body; assertEquals("text/event-stream", headers["accept"]); events.forEach(onLine); status }
        val p = AnthropicProvider({ goodKey }, budget, http, ModelCatalog({ goodKey }, http, nowMs = { now }), lines)
        val got = mutableListOf<String>()
        val out = assertIs<ModelOutcome.Answered>(p.stream(ask()) { got += it })
        assertEquals(listOf("Added ", "it."), got)
        assertEquals("Added it.", out.text)
        assertEquals(200, out.outputTokens)
        assertEquals("end_turn", out.stopReason)
        assertTrue(Json.parseToJsonElement(sent.single()!!).jsonObject["stream"]!!.jsonPrimitive.content == "true")
        assertFalse("tools" in Json.parseToJsonElement(sent.single()!!).jsonObject)
        assertEquals(2000, budget.state().spentMicroUsd)
        // An error part way, a refused key, the network down: said plainly; what was written is still counted.
        events = listOf(
            """data: {"type":"message_start","message":{"model":"claude-haiku-9","usage":{"input_tokens":1000,"output_tokens":0}}}""",
            """data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
        )
        assertEquals("Anthropic is busy; try again shortly", assertIs<ModelOutcome.Failed>(p.stream(ask()) {}).reason)
        assertEquals(3000, budget.state().spentMicroUsd)
        status = 401
        events = listOf("""{"type":"error","error":{"type":"authentication_error"}}""")
        assertEquals("Anthropic refused the key", assertIs<ModelOutcome.Failed>(p.stream(ask()) {}).reason)
        val down = AnthropicProvider({ goodKey }, budget, http, ModelCatalog({ goodKey }, http, nowMs = { now }), { _, _, _, _, _ -> throw java.io.IOException("down") })
        assertEquals("Couldn't reach Anthropic", assertIs<ModelOutcome.Failed>(down.stream(ask()) {}).reason)
        assertEquals(ModelOutcome.Off, AnthropicProvider({ null }, budget, http, lines = lines).stream(ask()) { error("not called") })
    }
}
