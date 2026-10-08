package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

/*
 * The AI layer's second slice (build plan V1, ADR-006 §4 and §5): the cloud `LanguageModelProvider` on the server, with a
 * per-feature token meter and a monthly budget (soft alert at 70 %, off at 100 %). Nothing calls it yet; Ask MEKA is the
 * first caller (next slice). The provider has no tools: it turns text into text and nothing else, so whatever a model
 * says can only ever become a proposal that the policy engine decides on (ADR-006 §3).
 */

/** Which kind of model a feature needs. Small for extraction and short answers; frontier for planning and drafting. */
enum class ModelTier(val family: String, val fallbackModel: String) {
    SMALL("haiku", "claude-haiku-4-5"),
    FRONTIER("sonnet", "claude-sonnet-4-5"),
}

data class ModelTurn(val role: Role, val text: String) {
    enum class Role(val wire: String) { USER("user"), ASSISTANT("assistant") }
}

/**
 * One model call. [feature] names the meter it counts against (e.g. "ask", "extract.email"), never anything of Meka's;
 * [maxTokens] is clamped to [MAX_OUTPUT_TOKENS].
 */
data class ModelRequest(
    val feature: String,
    val tier: ModelTier,
    val system: String,
    val turns: List<ModelTurn>,
    val maxTokens: Int = 1024,
) {
    init {
        require(FEATURE.matches(feature)) { "feature" }
        require(turns.isNotEmpty() && turns.first().role == ModelTurn.Role.USER) { "turns" }
        require(maxTokens > 0) { "maxTokens" }
    }

    companion object {
        const val MAX_OUTPUT_TOKENS = 4096
        private val FEATURE = Regex("[a-z][a-z0-9.]{0,39}")
    }
}

/** What a call came back with. Every outcome other than [Answered] means "carry on without AI" (ADR-006 addendum). */
sealed interface ModelOutcome {
    data class Answered(val text: String, val model: String, val inputTokens: Int, val outputTokens: Int, val stopReason: String?) : ModelOutcome
    /** No key set: AI is off, a normal state. */
    data object Off : ModelOutcome
    /** This month's budget is spent; AI is off until the 1st (degrade-to-local, ADR-006 §5). */
    data object OverBudget : ModelOutcome
    data class Failed(val reason: String) : ModelOutcome
}

/** ADR-006 §4: models sit behind one interface, so on-device ones can join the router later. */
interface LanguageModelProvider {
    val id: String
    fun complete(request: ModelRequest): ModelOutcome
}

/** A minimal HTTP exchange returning status and body, so the provider is testable without the network. */
fun interface HttpExchange {
    fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String>

    companion object {
        fun jdk(timeout: Duration = Duration.ofSeconds(60)): HttpExchange {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            return HttpExchange { method, url, headers, body ->
                val req = HttpRequest.newBuilder(URI(url)).timeout(timeout)
                    .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
                headers.forEach { (k, v) -> req.header(k, v) }
                val r = client.send(req.build(), HttpResponse.BodyHandlers.ofString())
                r.statusCode() to (r.body() ?: "")
            }
        }
    }
}

/**
 * List prices in micro-dollars per token (= dollars per million tokens), by model family. A model we don't recognise is
 * charged at the highest price, so the meter never under-counts.
 */
object ModelPrices {
    data class Price(val inputMicroUsd: Long, val outputMicroUsd: Long)

    private val byFamily = listOf("haiku" to Price(1, 5), "sonnet" to Price(3, 15), "opus" to Price(5, 25))
    private val highest = Price(15, 75)

    fun of(model: String): Price = byFamily.firstOrNull { (f, _) -> f in model }?.second ?: highest

    fun costMicroUsd(model: String, inputTokens: Int, outputTokens: Int): Long =
        of(model).let { it.inputMicroUsd * inputTokens + it.outputMicroUsd * outputTokens }
}

/** One month's use of the AI layer, per feature. Counts only: no prompt, answer or anything of Meka's is kept. */
data class AiUsage(val feature: String, val calls: Long, val inputTokens: Long, val outputTokens: Long, val microUsd: Long)

interface AiUsageStore {
    fun add(month: String, feature: String, inputTokens: Int, outputTokens: Int, microUsd: Long)
    fun month(month: String): List<AiUsage>
}

class InMemoryAiUsageStore : AiUsageStore {
    private val rows = mutableMapOf<Pair<String, String>, AiUsage>()

    @Synchronized
    override fun add(month: String, feature: String, inputTokens: Int, outputTokens: Int, microUsd: Long) {
        val k = month to feature
        val r = rows[k] ?: AiUsage(feature, 0, 0, 0, 0)
        rows[k] = r.copy(calls = r.calls + 1, inputTokens = r.inputTokens + inputTokens, outputTokens = r.outputTokens + outputTokens, microUsd = r.microUsd + microUsd)
    }

    @Synchronized
    override fun month(month: String): List<AiUsage> = rows.filterKeys { it.first == month }.values.sortedBy { it.feature }
}

class PostgresAiUsageStore(private val ds: DataSource) : AiUsageStore {
    override fun add(month: String, feature: String, inputTokens: Int, outputTokens: Int, microUsd: Long) = ds.connection.use { c ->
        c.prepareStatement(
            """INSERT INTO ai_usage(month, feature, calls, input_tokens, output_tokens, micro_usd) VALUES (?,?,1,?,?,?)
               ON CONFLICT (month, feature) DO UPDATE SET calls = ai_usage.calls + 1,
                 input_tokens = ai_usage.input_tokens + EXCLUDED.input_tokens,
                 output_tokens = ai_usage.output_tokens + EXCLUDED.output_tokens,
                 micro_usd = ai_usage.micro_usd + EXCLUDED.micro_usd, updated_at = now()""",
        ).use {
            it.setString(1, month); it.setString(2, feature); it.setLong(3, inputTokens.toLong()); it.setLong(4, outputTokens.toLong()); it.setLong(5, microUsd)
            it.executeUpdate()
        }
        Unit
    }

    override fun month(month: String): List<AiUsage> = ds.connection.use { c ->
        c.prepareStatement("SELECT feature, calls, input_tokens, output_tokens, micro_usd FROM ai_usage WHERE month = ? ORDER BY feature").use { st ->
            st.setString(1, month)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(AiUsage(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5))) } }
        }
    }
}

/**
 * The monthly budget (ADR-006 §5), in UTC calendar months. Below 70 % is fine; from 70 % the status says so (the soft
 * alert); at 100 % calls are refused until the 1st. The Anthropic Console's own spend cap stays the hard stop behind it.
 */
class AiBudget(
    val monthlyMicroUsd: Long,
    private val store: AiUsageStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onAlert: (Level, String) -> Unit = { _, _ -> },
) {
    enum class Level(val wire: String) { OK("ok"), ALERT("alert"), OVER("over") }

    data class State(val month: String, val spentMicroUsd: Long, val budgetMicroUsd: Long, val level: Level, val byFeature: List<AiUsage>) {
        fun toJson(): JsonObject = buildJsonObject {
            put("month", month)
            put("spentCents", spentMicroUsd / 10_000)
            put("budgetCents", budgetMicroUsd / 10_000)
            put("level", level.wire)
            putJsonArray("features") {
                byFeature.forEach { u -> addJsonObject { put("feature", u.feature); put("calls", u.calls); put("cents", u.microUsd / 10_000) } }
            }
        }
    }

    private val alerted = mutableSetOf<Pair<String, Level>>()

    fun month(): String = Instant.ofEpochMilli(nowMs()).atOffset(ZoneOffset.UTC).let { "%04d-%02d".format(it.year, it.monthValue) }

    fun state(): State {
        val m = month()
        val rows = store.month(m)
        val spent = rows.sumOf { it.microUsd }
        val level = when {
            monthlyMicroUsd <= 0 || spent >= monthlyMicroUsd -> Level.OVER
            spent * 10 >= monthlyMicroUsd * 7 -> Level.ALERT
            else -> Level.OK
        }
        return State(m, spent, monthlyMicroUsd, level, rows)
    }

    /** Counts a call against its feature and raises the 70 % / 100 % alert once per month each. */
    fun record(feature: String, model: String, inputTokens: Int, outputTokens: Int) {
        val m = month()
        store.add(m, feature, inputTokens, outputTokens, ModelPrices.costMicroUsd(model, inputTokens, outputTokens))
        val s = state()
        if (s.level != Level.OK) {
            val first = synchronized(alerted) { alerted.add(m to s.level) }
            if (first) onAlert(s.level, m)
        }
    }

    companion object {
        /** MEKA's share of the $40/month Anthropic cap (shared with Kestrel) unless MEKA_AI_BUDGET_USD says otherwise. */
        const val DEFAULT_USD = 20

        fun usdFromEnv(): Long = (System.getenv("MEKA_AI_BUDGET_USD")?.trim()?.toLongOrNull()?.takeIf { it in 0..1000 } ?: DEFAULT_USD.toLong()) * 1_000_000
    }
}

/**
 * Which model each tier uses: the newest of its family in Anthropic's model list (newest first), looked up once a day,
 * else the tier's fixed fallback. Listing models sends nothing of Meka's.
 */
class ModelCatalog(
    private val key: () -> String?,
    private val http: HttpExchange,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val forMs: Long = 24 * 3_600_000L,
) {
    private var cached: Pair<Long, List<String>>? = null

    @Synchronized
    fun model(tier: ModelTier): String {
        val ids = cached?.takeIf { nowMs() - it.first < forMs }?.second ?: list().also { cached = nowMs() to it }
        return ids.firstOrNull { tier.family in it } ?: tier.fallbackModel
    }

    private fun list(): List<String> = runCatching {
        val k = key() ?: return emptyList()
        val (code, body) = http.send("GET", MODELS_URL, anthropicHeaders(k), null)
        if (code !in 200..299) return emptyList()
        Json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
    }.getOrDefault(emptyList())

    companion object {
        const val MODELS_URL = "https://api.anthropic.com/v1/models?limit=100"
    }
}

internal fun anthropicHeaders(key: String) =
    mapOf("x-api-key" to key, "anthropic-version" to AiHealth.API_VERSION, "accept" to "application/json", "content-type" to "application/json")

/**
 * The cloud provider: Anthropic's Messages API with the key read at use time. Text in, text out; no tools are ever
 * offered to the model. Refuses before calling once the month's budget is spent, and meters every answer.
 */
class AnthropicProvider(
    private val key: () -> String?,
    private val budget: AiBudget,
    private val http: HttpExchange = HttpExchange.jdk(),
    private val catalog: ModelCatalog = ModelCatalog(key, http),
) : LanguageModelProvider {
    override val id = "anthropic"

    override fun complete(request: ModelRequest): ModelOutcome {
        val k = key() ?: return ModelOutcome.Off
        if (budget.state().level == AiBudget.Level.OVER) return ModelOutcome.OverBudget
        val model = catalog.model(request.tier)
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxTokens.coerceAtMost(ModelRequest.MAX_OUTPUT_TOKENS))
            if (request.system.isNotBlank()) put("system", request.system)
            putJsonArray("messages") {
                request.turns.forEach { t -> addJsonObject { put("role", t.role.wire); put("content", t.text) } }
            }
        }.toString()
        val (code, text) = runCatching { http.send("POST", MESSAGES_URL, anthropicHeaders(k), body) }
            .getOrElse { return ModelOutcome.Failed("Couldn't reach Anthropic") }
        return when (code) {
            in 200..299 -> parse(text, model, request.feature)
            401 -> ModelOutcome.Failed("Anthropic refused the key")
            403 -> ModelOutcome.Failed("The key isn't allowed to use the API")
            429 -> ModelOutcome.Failed("Anthropic is limiting requests (spend cap or rate limit)")
            529 -> ModelOutcome.Failed("Anthropic is busy; try again shortly")
            else -> ModelOutcome.Failed("Anthropic answered $code")
        }
    }

    private fun parse(text: String, asked: String, feature: String): ModelOutcome = runCatching {
        val o = Json.parseToJsonElement(text).jsonObject
        val answer = o["content"]?.jsonArray.orEmpty()
            .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            .joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }
        val usage = o["usage"]?.jsonObject
        val input = (usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0) +
            (usage?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull ?: 0) +
            (usage?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull ?: 0)
        val output = usage?.get("output_tokens")?.jsonPrimitive?.int ?: 0
        val model = o["model"]?.jsonPrimitive?.content ?: asked
        budget.record(feature, model, input, output)
        ModelOutcome.Answered(answer, model, input, output, o["stop_reason"]?.jsonPrimitive?.content)
    }.getOrElse { ModelOutcome.Failed("Anthropic's answer couldn't be read") }

    companion object {
        const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
    }
}

/** The AI layer the routes see: the key's health, and the budget when the meter is set up. */
class AiLayer(val health: AiHealth, val budget: AiBudget? = null, val provider: LanguageModelProvider? = null) {
    fun statusJson(): JsonObject {
        val s = health.status().toJson()
        val b = budget?.state() ?: return s
        return JsonObject(s + ("budget" to b.toJson()))
    }
}
