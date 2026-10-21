package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Fresh hello openers on the wire (the orb says hello like a person, slice 4): `POST /v1/ai/hello-openers` asks for
 * [Request.perPart] openers for each part of the day. Nothing about him goes: no name, no date, no data, only how many.
 * The answer is openers with "{n}" where his name goes, each with its part; the device checks every one
 * (`HelloFreshRules.check`) before it is ever said.
 */
object HelloOpenersCodec {
    const val PATH = "/v1/ai/hello-openers"
    const val MAX_PER_PART = 8
    /** Each part's word ("morning", "afternoon", "evening", "late"). */
    val PARTS = listOf("morning", "afternoon", "evening", "late")
    /** Lines are read up to this long; the device keeps much less. */
    const val MAX_TEXT = 120
    const val MAX_OPENERS = 48

    private val json = Json { ignoreUnknownKeys = true }

    data class Request(val perPart: Int)

    data class Opener(val part: String, val open: String, val ask: String)

    /** [state]: the same states as Ask's; [openers] empty unless answered. */
    data class Response(val state: String, val openers: List<Opener> = emptyList(), val reason: String? = null)

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("perPart", r.perPart)
    }.toString()

    fun decodeRequest(body: String): Request = wrap("hello openers request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val n = (o["perPart"] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("perPart")
        require(n in 1..MAX_PER_PART) { "perPart" }
        Request(n)
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        r.reason?.let { put("reason", it) }
        putJsonArray("openers") {
            r.openers.forEach { x -> addJsonObject { put("part", x.part); put("open", x.open); put("ask", x.ask) } }
        }
    }.toString()

    fun decodeResponse(body: String): Response = wrap("hello openers response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        Response(o.str("state"), openersOf(o), o.optStr("reason"))
    }

    /**
     * A model's answer, which should be one JSON object `{"openers": [{"part": …, "open": …, "ask": …}]}` (possibly in a
     * code fence). Anything that isn't that object is no openers; an unknown part is dropped.
     */
    fun parseModelAnswer(text: String): List<Opener> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val o = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return emptyList()
        return openersOf(o)
    }

    private fun openersOf(o: JsonObject): List<Opener> =
        (o["openers"] as? JsonArray).orEmpty().mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            val part = x.optStr("part")?.trim()?.lowercase()?.takeIf { it in PARTS } ?: return@mapNotNull null
            val open = x.optStr("open")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ask = x.optStr("ask")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Opener(part, open, ask)
        }.take(MAX_OPENERS)

    private fun JsonObject.str(k: String): String =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException(k)

    private fun JsonObject.optStr(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.length <= MAX_TEXT }

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
