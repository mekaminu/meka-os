package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The morning brief written by MEKA's AI on the wire ("A morning brief worth listening to", slice 2):
 * `POST /v1/ai/brief-script` carries the brief's facts — the sentences MEKA would read before the news
 * (`BriefScriptRules.body` in core/domain) — with today's date and whether it's the weekend. Nothing else of the
 * device's. The answer is the brief as one piece of spoken text, which the device checks sentence by sentence
 * (`BriefAiRules.check`) before reading it.
 *
 * A request outside the limits is refused, not trimmed (the device cuts before sending).
 */
object BriefScriptCodec {
    const val MAX_FACTS = 40
    const val MAX_FACT = 300
    /** What is read of a model's answer at most (the device keeps fewer). */
    const val MAX_SCRIPT = 2_400
    const val MAX_REASON = 300

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val json = Json { ignoreUnknownKeys = true }

    /** [date] the device's local ISO date, [now] its readable "Monday 12 October 2026 · 07:30". */
    data class Request(val date: String, val now: String, val weekend: Boolean, val facts: List<String>)

    /** [state]: the same states as Ask's; [script] is empty unless answered. */
    data class Response(val state: String, val script: String = "", val reason: String? = null)

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("date", r.date)
        put("now", r.now)
        put("weekend", r.weekend)
        putJsonArray("facts") { r.facts.forEach { add(JsonPrimitive(it)) } }
    }.toString()

    fun decodeRequest(body: String): Request = wrap("brief script request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val date = o.str("date")
        require(DATE.matches(date)) { "date" }
        val now = o.str("now")
        require(now.length <= MessageRequestCodec.MAX_NOW) { "now" }
        val weekend = (o["weekend"] as? JsonPrimitive)?.booleanOrNull ?: throw IllegalArgumentException("weekend")
        val a = o["facts"] as? JsonArray ?: throw IllegalArgumentException("facts")
        require(a.size in 1..MAX_FACTS) { "facts" }
        val facts = a.map { e ->
            val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: throw IllegalArgumentException("facts")
            require(s.isNotEmpty() && s.length <= MAX_FACT) { "facts" }
            s
        }
        Request(date, now, weekend, facts)
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        r.reason?.let { put("reason", it) }
        put("script", r.script)
    }.toString()

    fun decodeResponse(body: String): Response = wrap("brief script response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val script = (o["script"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty().take(MAX_SCRIPT)
        val reason = (o["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.length <= MAX_REASON }
        Response(o.str("state"), script, reason)
    }

    /**
     * A model's answer, which should be one JSON object `{"script": "…"}` (possibly in a code fence). Anything that isn't
     * that object is an empty script (the device then reads its own brief); a longer one is cut at [MAX_SCRIPT].
     */
    fun parseModelAnswer(text: String): Response {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return Response(AskCodec.Response.ANSWERED)
        val o = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
            ?: return Response(AskCodec.Response.ANSWERED)
        val script = (o["script"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty().take(MAX_SCRIPT)
        return Response(AskCodec.Response.ANSWERED, script)
    }

    private fun JsonObject.str(k: String): String =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException(k)

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
