package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Spam-call lookup on the wire (2026-10-17): the Fold asks `POST /v1/calls/lookup` about one caller it doesn't know,
 * saying how long it can wait ([Request.waitMs]); the server answers from its 30-day cache or searches the web for the
 * number (Brave Search) and says what the report sites make of it. "pending" means the search is still going (the
 * answer is cached for the next ask); "off" that the server has no search key; "failed" that the search failed (no
 * credit left, refused, timed out): all three mean "no lookup", never spam. The number is never logged.
 */
object NumberLookupCodec {
    const val PATH = "/v1/calls/lookup"
    const val MAX_WAIT_MS = 8_000L
    private const val MAX_NUMBER = 40
    private const val MAX_WHY = 80

    private val json = Json { ignoreUnknownKeys = true }

    data class Request(val number: String, val waitMs: Long) {
        override fun toString() = "NumberLookupCodec.Request(waitMs=$waitMs)"
    }

    /** [verdict] scam · nuisance · legit · unknown (with [state] ok). */
    data class Response(
        val state: String,
        val verdict: String? = null,
        val reports: Int = 0,
        val sure: Boolean = false,
        val why: String? = null,
    ) {
        companion object {
            const val OK = "ok"
            const val OFF = "off"
            const val PENDING = "pending"
            const val FAILED = "failed"
            val VERDICTS = setOf("scam", "nuisance", "legit", "unknown")
        }
    }

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("number", r.number.trim().take(MAX_NUMBER))
        put("wait", r.waitMs.coerceIn(0, MAX_WAIT_MS))
    }.toString()

    fun decodeRequest(body: String): Request = wrap("lookup request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val n = (o["number"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: throw IllegalArgumentException("number")
        require(n.length in 3..MAX_NUMBER && n.any { it.isDigit() } && n.all { it.isDigit() || it in "+-() ." }) { "number" }
        val wait = (o["wait"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0L
        Request(n, wait.coerceIn(0, MAX_WAIT_MS))
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        if (r.state == Response.OK && r.verdict != null) {
            put("verdict", r.verdict)
            put("reports", r.reports.coerceIn(0, 99_999))
            put("sure", r.sure)
            r.why?.let { put("why", it.take(MAX_WHY)) }
        }
    }.toString()

    fun decodeResponse(body: String): Response = wrap("lookup response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val state = (o["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("state")
        val verdict = (o["verdict"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in Response.VERDICTS }
        Response(
            state = state,
            verdict = verdict,
            reports = ((o["reports"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: 0).coerceIn(0, 99_999),
            sure = (o["sure"] as? JsonPrimitive)?.booleanOrNull ?: false,
            why = (o["why"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_WHY),
        )
    }

    private fun checkVersion(o: JsonObject) {
        val w = (o["w"] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("w")
        if (w != WireCodec.VERSION) throw IllegalArgumentException("unsupported wire version $w")
    }

    private inline fun <T> wrap(what: String, block: () -> T): T = try {
        block()
    } catch (e: WireFormatException) {
        throw e
    } catch (e: Exception) {
        throw WireFormatException("bad $what: ${e.message}", e)
    }
}
