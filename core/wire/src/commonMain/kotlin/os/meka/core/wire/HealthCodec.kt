package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The Health screen's server half (Reliability first, item 3): `POST /v1/health/household`, signed by a keyed device.
 * The server says what only it knows: whether wake-ups are set up and this device has an address, whether the call
 * assistant's phone service and MEKA's voice are configured, its own clock and how many Macs are connected (Setup).
 * Never a secret, a token, an address or a device's name.
 * Calendars and feeds come from `/v1/integrations/list` and the AI from `/v1/ai/status`, as before.
 */
object HealthCodec {
    private val json = Json { ignoreUnknownKeys = true }

    data class Response(
        /** [PUSH_ON] (set up, and this device has an address) · [PUSH_MISSING] (set up, no address) · [PUSH_OFF]. */
        val push: String,
        /** The call assistant's phone service (Twilio) is configured on the server. */
        val calls: Boolean,
        /** MEKA's voice (Amazon Polly) is configured on the server. */
        val speech: Boolean,
        /** The server's clock when it answered. */
        val atMs: Long,
        /** How many Macs are connected to the household (Setup's "Mac" step); null from an older server. */
        val macs: Int? = null,
    ) {
        companion object {
            const val PUSH_ON = "on"
            const val PUSH_MISSING = "missing"
            const val PUSH_OFF = "off"
        }
    }

    fun encodeRequest(): String = buildJsonObject { put("w", WireCodec.VERSION) }.toString()

    fun decodeRequest(body: String) = wrap("health request") {
        if (body.isBlank()) return@wrap
        checkVersion(json.parseToJsonElement(body).jsonObject)
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("push", r.push)
        put("calls", r.calls)
        put("speech", r.speech)
        put("at", r.atMs)
        r.macs?.let { put("macs", it) }
    }.toString()

    /** An unknown push state reads as off (an older or newer server), never as fine. */
    fun decodeResponse(body: String): Response = wrap("health response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val push = (o["push"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it == Response.PUSH_ON || it == Response.PUSH_MISSING } ?: Response.PUSH_OFF
        Response(
            push = push,
            calls = o.bool("calls"),
            speech = o.bool("speech"),
            atMs = (o["at"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw IllegalArgumentException("at"),
            macs = (o["macs"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 },
        )
    }

    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false

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
