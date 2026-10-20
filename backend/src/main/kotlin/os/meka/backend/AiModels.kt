package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
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

    /**
     * The companion, slice 9: as [complete], with the answer's text handed to [onText] piece by piece as the model
     * writes it, so a spoken answer can start before it is finished. A provider that can't stream hands it over whole.
     */
    fun stream(request: ModelRequest, onText: (String) -> Unit): ModelOutcome =
        complete(request).also { if (it is ModelOutcome.Answered) onText(it.text) }

    /**
     * The orb speed pass, slice 5: opens (or keeps open) the connection to the model with a free request that asks for
     * no answer and costs no tokens, so the answer that follows a hello doesn't pay for a new connection. True when
     * the provider answered. Never throws; a provider with nothing to warm does nothing.
     */
    fun touch(): Boolean = false
}

/**
 * An HTTP exchange whose answer is read line by line as it arrives (server-sent events), so the provider is testable
 * without the network: [onLine] gets each line of the body; the status comes back once the body has ended.
 */
fun interface HttpLines {
    fun send(method: String, url: String, headers: Map<String, String>, body: String?, onLine: (String) -> Unit): Int

    companion object {
        fun jdk(timeout: Duration = Duration.ofSeconds(60), client: HttpClient = AiHttp.client): HttpLines {
            return HttpLines { method, url, headers, body, onLine ->
                val req = HttpRequest.newBuilder(URI(url)).timeout(timeout)
                    .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
                headers.forEach { (k, v) -> req.header(k, v) }
                val r = client.send(req.build(), HttpResponse.BodyHandlers.ofLines())
                r.body().use { lines -> lines.forEach(onLine) }
                r.statusCode()
            }
        }
    }
}

/**
 * The orb speed pass, slice 5: one HTTP client for every call to Anthropic (the model list, whole answers and streamed
 * ones), so the connection a warm-up opens ([LanguageModelProvider.touch]) is the one the next answer uses.
 */
object AiHttp {
    val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
}

/** A minimal HTTP exchange returning status and body, so the provider is testable without the network. */
fun interface HttpExchange {
    fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String>

    companion object {
        fun jdk(timeout: Duration = Duration.ofSeconds(60), client: HttpClient = AiHttp.client): HttpExchange {
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

    /** The same count for one Europe/London [day] ("2026-10-10"), for Activity's daily line. */
    fun addDay(day: String, feature: String, inputTokens: Int, outputTokens: Int, microUsd: Long) {}
    fun day(day: String): List<AiUsage> = emptyList()
}

class InMemoryAiUsageStore : AiUsageStore {
    private val rows = mutableMapOf<Pair<String, String>, AiUsage>()
    private val days = mutableMapOf<Pair<String, String>, AiUsage>()

    private fun MutableMap<Pair<String, String>, AiUsage>.bump(k: Pair<String, String>, inputTokens: Int, outputTokens: Int, microUsd: Long) {
        val r = this[k] ?: AiUsage(k.second, 0, 0, 0, 0)
        this[k] = r.copy(calls = r.calls + 1, inputTokens = r.inputTokens + inputTokens, outputTokens = r.outputTokens + outputTokens, microUsd = r.microUsd + microUsd)
    }

    @Synchronized
    override fun add(month: String, feature: String, inputTokens: Int, outputTokens: Int, microUsd: Long) =
        rows.bump(month to feature, inputTokens, outputTokens, microUsd)

    @Synchronized
    override fun month(month: String): List<AiUsage> = rows.filterKeys { it.first == month }.values.sortedBy { it.feature }

    @Synchronized
    override fun addDay(day: String, feature: String, inputTokens: Int, outputTokens: Int, microUsd: Long) =
        days.bump(day to feature, inputTokens, outputTokens, microUsd)

    @Synchronized
    override fun day(day: String): List<AiUsage> = days.filterKeys { it.first == day }.values.sortedBy { it.feature }
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
    override fun addDay(day: String, feature: String, inputTokens: Int, outputTokens: Int, microUsd: Long) = ds.connection.use { c ->
        c.prepareStatement(
            """INSERT INTO ai_usage_day(day, feature, calls, input_tokens, output_tokens, micro_usd) VALUES (?,?,1,?,?,?)
               ON CONFLICT (day, feature) DO UPDATE SET calls = ai_usage_day.calls + 1,
                 input_tokens = ai_usage_day.input_tokens + EXCLUDED.input_tokens,
                 output_tokens = ai_usage_day.output_tokens + EXCLUDED.output_tokens,
                 micro_usd = ai_usage_day.micro_usd + EXCLUDED.micro_usd, updated_at = now()""",
        ).use {
            it.setString(1, day); it.setString(2, feature); it.setLong(3, inputTokens.toLong()); it.setLong(4, outputTokens.toLong()); it.setLong(5, microUsd)
            it.executeUpdate()
        }
        Unit
    }

    override fun day(day: String): List<AiUsage> = ds.connection.use { c ->
        c.prepareStatement("SELECT feature, calls, input_tokens, output_tokens, micro_usd FROM ai_usage_day WHERE day = ? ORDER BY feature").use { st ->
            st.setString(1, day)
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

    /**
     * [day] and [today]: the Europe/London day and its calls per feature, for Activity's daily count. Sent as
     * micro-dollars too, since a day's few calls are often under a cent.
     */
    data class State(
        val month: String, val spentMicroUsd: Long, val budgetMicroUsd: Long, val level: Level, val byFeature: List<AiUsage>,
        val day: String = "", val today: List<AiUsage> = emptyList(),
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("month", month)
            put("spentCents", spentMicroUsd / 10_000)
            put("budgetCents", budgetMicroUsd / 10_000)
            put("level", level.wire)
            putJsonArray("features") {
                byFeature.forEach { u -> addJsonObject { put("feature", u.feature); put("calls", u.calls); put("cents", u.microUsd / 10_000) } }
            }
            if (day.isNotEmpty()) put("day", day)
            putJsonArray("today") {
                today.forEach { u -> addJsonObject { put("feature", u.feature); put("calls", u.calls); put("microUsd", u.microUsd) } }
            }
        }
    }

    private val alerted = mutableSetOf<Pair<String, Level>>()

    fun month(): String = Instant.ofEpochMilli(nowMs()).atOffset(ZoneOffset.UTC).let { "%04d-%02d".format(it.year, it.monthValue) }

    /** The household's day (Europe/London), so "today" in Activity turns over at Meka's midnight. */
    fun day(): String = Instant.ofEpochMilli(nowMs()).atZone(UK).toLocalDate().toString()

    fun state(): State {
        val m = month()
        val rows = store.month(m)
        val spent = rows.sumOf { it.microUsd }
        val level = when {
            monthlyMicroUsd <= 0 || spent >= monthlyMicroUsd -> Level.OVER
            spent * 10 >= monthlyMicroUsd * 7 -> Level.ALERT
            else -> Level.OK
        }
        val d = day()
        return State(m, spent, monthlyMicroUsd, level, rows, d, runCatching { store.day(d) }.getOrDefault(emptyList()))
    }

    /** Counts a call against its feature and raises the 70 % / 100 % alert once per month each. */
    fun record(feature: String, model: String, inputTokens: Int, outputTokens: Int) =
        meter(feature, inputTokens, outputTokens, ModelPrices.costMicroUsd(model, inputTokens, outputTokens))

    /**
     * Counts one priced use that isn't a model call (Navilife's voice: a Polly clip, voice completeness 2026-10-19)
     * against [feature], at [microUsd], with the same alerts. No tokens; nothing of what was said is kept.
     */
    fun recordCost(feature: String, microUsd: Long) = meter(feature, 0, 0, microUsd.coerceAtLeast(0))

    /** Whether the month's budget is spent (the voice then leaves speaking to the device until the 1st). Never throws. */
    fun over(): Boolean = runCatching { state().level == Level.OVER }.getOrDefault(false)

    private fun meter(feature: String, inputTokens: Int, outputTokens: Int, cost: Long) {
        val m = month()
        store.add(m, feature, inputTokens, outputTokens, cost)
        // The daily count is only for Activity: a failure there never stops the budget's meter.
        runCatching { store.addDay(day(), feature, inputTokens, outputTokens, cost) }
        val s = state()
        if (s.level != Level.OK) {
            val first = synchronized(alerted) { alerted.add(m to s.level) }
            if (first) onAlert(s.level, m)
        }
    }

    companion object {
        /** MEKA's share of the $40/month Anthropic cap (shared with Kestrel) unless MEKA_AI_BUDGET_USD says otherwise. */
        const val DEFAULT_USD = 20
        private val UK: java.time.ZoneId = java.time.ZoneId.of("Europe/London")

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
    /** The companion, slice 9: the streamed exchange ([stream]). */
    private val lines: HttpLines = HttpLines.jdk(),
) : LanguageModelProvider {
    override val id = "anthropic"

    private fun bodyOf(request: ModelRequest, model: String, stream: Boolean): String = buildJsonObject {
        put("model", model)
        put("max_tokens", request.maxTokens.coerceAtMost(ModelRequest.MAX_OUTPUT_TOKENS))
        if (request.system.isNotBlank()) put("system", request.system)
        putJsonArray("messages") {
            request.turns.forEach { t -> addJsonObject { put("role", t.role.wire); put("content", t.text) } }
        }
        if (stream) put("stream", true)
    }.toString()

    override fun complete(request: ModelRequest): ModelOutcome {
        val k = key() ?: return ModelOutcome.Off
        if (budget.state().level == AiBudget.Level.OVER) return ModelOutcome.OverBudget
        val model = catalog.model(request.tier)
        val (code, text) = runCatching { http.send("POST", MESSAGES_URL, anthropicHeaders(k), bodyOf(request, model, stream = false)) }
            .getOrElse { return ModelOutcome.Failed("Couldn't reach Anthropic") }
        return if (code in 200..299) parse(text, model, request.feature) else failure(code)
    }

    /** The model list's first entry (free, no tokens) over the shared client ([AiHttp]); no key: nothing is sent. */
    override fun touch(): Boolean {
        val k = key() ?: return false
        return runCatching { http.send("GET", WARM_URL, anthropicHeaders(k), null).first in 200..299 }.getOrDefault(false)
    }

    private fun failure(code: Int): ModelOutcome.Failed = when (code) {
        401 -> ModelOutcome.Failed("Anthropic refused the key")
        403 -> ModelOutcome.Failed("The key isn't allowed to use the API")
        429 -> ModelOutcome.Failed("Anthropic is limiting requests (spend cap or rate limit)")
        529 -> ModelOutcome.Failed("Anthropic is busy; try again shortly")
        else -> ModelOutcome.Failed("Anthropic answered $code")
    }

    /**
     * The companion, slice 9: the Messages API with `"stream": true`. Each text delta goes to [onText] as it arrives;
     * the usage is metered once the stream ends (what was written is counted even when it ends in an error). Still
     * text in, text out, no tools.
     */
    override fun stream(request: ModelRequest, onText: (String) -> Unit): ModelOutcome {
        val k = key() ?: return ModelOutcome.Off
        if (budget.state().level == AiBudget.Level.OVER) return ModelOutcome.OverBudget
        val asked = catalog.model(request.tier)
        val text = StringBuilder()
        var model = asked
        var input = 0
        var output = 0
        var stop: String? = null
        var failed: String? = null
        val errorBody = StringBuilder()
        val code = runCatching {
            lines.send("POST", MESSAGES_URL, anthropicHeaders(k) + ("accept" to "text/event-stream"), bodyOf(request, asked, stream = true)) { line ->
                if (!line.startsWith("data:")) {
                    if (errorBody.length < 2_000) errorBody.append(line)
                    return@send
                }
                val o = runCatching { Json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject }.getOrNull() ?: return@send
                when (o["type"]?.jsonPrimitive?.content) {
                    "message_start" -> {
                        val m = o["message"]?.jsonObject
                        m?.get("model")?.jsonPrimitive?.content?.let { model = it }
                        val u = m?.get("usage")?.jsonObject
                        input = (u?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0) +
                            (u?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull ?: 0) +
                            (u?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull ?: 0)
                        output = u?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: output
                    }
                    "content_block_delta" -> {
                        val d = o["delta"]?.jsonObject
                        if (d?.get("type")?.jsonPrimitive?.content == "text_delta") {
                            val piece = d["text"]?.jsonPrimitive?.content.orEmpty()
                            if (piece.isNotEmpty()) {
                                text.append(piece)
                                runCatching { onText(piece) }
                            }
                        }
                    }
                    "message_delta" -> {
                        o["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let { stop = it }
                        o["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.intOrNull?.let { output = it }
                    }
                    "error" -> failed = o["error"]?.jsonObject?.get("type")?.jsonPrimitive?.content ?: "error"
                }
            }
        }.getOrElse {
            if (input > 0 || output > 0) budget.record(request.feature, model, input, output)
            return ModelOutcome.Failed("Couldn't reach Anthropic")
        }
        if (code !in 200..299) return failure(code)
        if (input > 0 || output > 0) budget.record(request.feature, model, input, output)
        if (failed != null) return if (failed == "overloaded_error") failure(529) else ModelOutcome.Failed("Anthropic's answer stopped part way")
        return ModelOutcome.Answered(text.toString(), model, input, output, stop)
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
        /** The warm-up's request: one model's name, nothing else ([touch]). */
        const val WARM_URL = "https://api.anthropic.com/v1/models?limit=1"
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
