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
        ModelRequest("ask", tier, "You are MEKA.", listOf(ModelTurn(ModelTurn.Role.USER, text)), maxTokens)

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
        assertEquals("You are MEKA.", sent["system"]!!.jsonPrimitive.content)
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
        assertEquals(100, budget.state().spentMicroUsd)
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
        }
    }
}
