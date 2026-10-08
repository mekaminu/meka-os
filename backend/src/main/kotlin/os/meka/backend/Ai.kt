package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * The AI layer's first slice (build plan V1, ADR-006 §4 "keys live server-side only"): the service reads MEKA's cloud
 * model key and checks it works. No model is asked anything yet and none of Meka's data leaves the server: the check
 * lists one model name (Anthropic's `GET /v1/models`, which costs nothing).
 *
 * The key lives in Secrets Manager `meka-os-dev/ai/anthropic` as `{"api_key": "sk-ant-…"}`; `{}` or a blank key means
 * AI off. The key is never logged, returned or stored anywhere else.
 */
object AnthropicKey {
    /** Null for the placeholder, a blank key, or something that isn't an Anthropic API key. */
    fun parse(json: String?): String? = runCatching {
        val o = Json.parseToJsonElement(json ?: return null).jsonObject
        o["api_key"]?.jsonPrimitive?.content?.trim()?.takeIf { it.startsWith("sk-ant-") && it.length in 20..400 && it.none(Char::isWhitespace) }
    }.getOrNull()
}

/** A minimal HTTP GET, so the check is testable without the network. Returns the status; throws on network errors. */
fun interface HttpGet {
    fun get(url: String, headers: Map<String, String>): Int

    companion object {
        fun jdk(): HttpGet {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            return HttpGet { url, headers ->
                val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).GET()
                headers.forEach { (k, v) -> req.header(k, v) }
                client.send(req.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
            }
        }
    }
}

/** What the apps may know about the AI layer: whether it's on, never the key. */
data class AiStatus(val state: State, val checkedAtMs: Long?, val reason: String?) {
    enum class State(val wire: String) { OFF("off"), ON("on"), FAILING("failing") }

    fun toJson(): JsonObject = buildJsonObject {
        put("state", state.wire)
        checkedAtMs?.let { put("checkedAtMs", it) }
        reason?.let { put("reason", it) }
    }
}

/**
 * The health check. The key is read at use time (so pasting or rotating it in the console needs no redeploy); a good
 * answer is trusted for [okForMs] (six hours), a failure re-checked after [retryMs] (ten minutes), and a different key
 * is checked at once. At most one check runs at a time.
 */
class AiHealth(
    private val key: () -> String?,
    private val http: HttpGet = HttpGet.jdk(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val okForMs: Long = 6 * 3_600_000L,
    private val retryMs: Long = 10 * 60_000L,
) {
    private var last: Pair<String, AiStatus>? = null // key fingerprint, status

    @Synchronized
    fun status(): AiStatus {
        val k = key() ?: return AiStatus(AiStatus.State.OFF, null, "No AI key set").also { last = null }
        val fp = fingerprint(k)
        last?.let { (f, s) ->
            val age = nowMs() - (s.checkedAtMs ?: 0)
            if (f == fp && age < (if (s.state == AiStatus.State.ON) okForMs else retryMs)) return s
        }
        return check(k).also { last = fp to it }
    }

    private fun check(k: String): AiStatus {
        val at = nowMs()
        val code = runCatching {
            http.get(MODELS_URL, mapOf("x-api-key" to k, "anthropic-version" to API_VERSION, "accept" to "application/json"))
        }.getOrElse { return AiStatus(AiStatus.State.FAILING, at, "Couldn't reach Anthropic") }
        return when (code) {
            in 200..299 -> AiStatus(AiStatus.State.ON, at, null)
            401 -> AiStatus(AiStatus.State.FAILING, at, "Anthropic refused the key")
            403 -> AiStatus(AiStatus.State.FAILING, at, "The key isn't allowed to use the API")
            429 -> AiStatus(AiStatus.State.FAILING, at, "Anthropic is limiting requests (spend cap or rate limit)")
            else -> AiStatus(AiStatus.State.FAILING, at, "Anthropic answered $code")
        }
    }

    companion object {
        const val MODELS_URL = "https://api.anthropic.com/v1/models?limit=1"
        const val API_VERSION = "2023-06-01"

        private fun fingerprint(k: String): String =
            MessageDigest.getInstance("SHA-256").digest(k.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Reads the key from Secrets Manager by ARN, cached for a minute; null when unset or `{}`. */
        fun fromSecret(secretId: String): AiHealth {
            val sm = SecretsManagerClient.builder().httpClient(UrlConnectionHttpClient.create()).build()
            var cached: Pair<Long, String?>? = null
            val lock = Any()
            return AiHealth(key = {
                synchronized(lock) {
                    val hit = cached?.takeIf { System.currentTimeMillis() - it.first < 60_000 }
                    if (hit != null) hit.second else {
                        val v = runCatching { AnthropicKey.parse(sm.getSecretValue { it.secretId(secretId) }.secretString()) }.getOrNull()
                        cached = System.currentTimeMillis() to v
                        v
                    }
                }
            })
        }
    }
}

/** Null unless the deployment names the AI secret (local dev and tests run without it). */
fun aiFromEnv(): AiHealth? = System.getenv("MEKA_AI_SECRET")?.takeIf { it.isNotBlank() }?.let { AiHealth.fromSecret(it) }
