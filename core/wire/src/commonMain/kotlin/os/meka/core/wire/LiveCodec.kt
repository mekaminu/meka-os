package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Ada's Live mode, slice 1: the device ↔ Navilife server exchange for a Gemini Live session.
 *
 * - [STATUS_PATH]: the month's Live meter (Settings' line under the switch). Nothing in but the version.
 * - [SESSION_PATH]: a short-lived token for one session, with the voice he picked. Out: the token (Google's, never the
 *   key), the model, the WebSocket URL, until when it can open and talk, and the meter.
 * - [USAGE_PATH]: when a session ends, how many seconds it heard and spoke, so the month's count drops from the
 *   window it was reserved at to what it really used. Nothing of what was said.
 */
object LiveCodec {
    const val STATUS_PATH = "/v1/ai/live/status"
    const val SESSION_PATH = "/v1/ai/live/session"
    const val USAGE_PATH = "/v1/ai/live/usage"

    /** The month's meter: [level] is ok · heads_up · over; [configured] false until the Gemini key is in. */
    data class Meter(val configured: Boolean, val level: String, val spentPence: Long, val capPence: Long)

    data class SessionRequest(val voice: String? = null)

    /**
     * [state]: [READY] (the rest is set) · [OFF] (no key on the server) · [OVER] (the month's cap) · [FAILED] (Google
     * refused the token; normal Ada answers).
     */
    data class Session(
        val state: String,
        val meter: Meter,
        val sessionId: String? = null,
        val token: String? = null,
        val model: String? = null,
        val url: String? = null,
        val voice: String? = null,
        val startByMs: Long? = null,
        val expiresAtMs: Long? = null,
        val reason: String? = null,
    ) {
        companion object {
            const val READY = "ready"
            const val OFF = "off"
            const val OVER = "over"
            const val FAILED = "failed"
            val STATES = setOf(READY, OFF, OVER, FAILED)
        }
    }

    data class UsageRequest(val sessionId: String, val inSeconds: Long, val outSeconds: Long)

    /** [counted]: false when the server didn't know the session or had already counted it. */
    data class Usage(val counted: Boolean, val meter: Meter)

    /** The longest session id accepted. */
    const val MAX_ID = 64

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeStatusRequest(): String = buildJsonObject { put("w", WireCodec.VERSION) }.toString()
    fun decodeStatusRequest(body: String) = wrap("live status request") { checkVersion(json.parseToJsonElement(body).jsonObject) }

    fun encodeMeter(m: Meter): String = buildJsonObject { put("w", WireCodec.VERSION); putMeter(m) }.toString()
    fun decodeMeter(body: String): Meter = wrap("live meter") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        meter(o)
    }

    fun encodeSessionRequest(r: SessionRequest): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        r.voice?.let { put("voice", it) }
    }.toString()

    fun decodeSessionRequest(body: String): SessionRequest = wrap("live session request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        SessionRequest(str(o, "voice")?.take(40))
    }

    fun encodeSession(s: Session): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", s.state)
        putMeter(s.meter)
        s.sessionId?.let { put("sessionId", it) }
        s.token?.let { put("token", it) }
        s.model?.let { put("model", it) }
        s.url?.let { put("url", it) }
        s.voice?.let { put("voice", it) }
        s.startByMs?.let { put("startByMs", it) }
        s.expiresAtMs?.let { put("expiresAtMs", it) }
        s.reason?.let { put("reason", it) }
    }.toString()

    /** An unknown state, or a ready one missing its token, model or URL, reads as [Session.FAILED]: normal Ada answers. */
    fun decodeSession(body: String): Session = wrap("live session") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val s = Session(
            state = str(o, "state")?.takeIf { it in Session.STATES } ?: Session.FAILED,
            meter = meter(o),
            sessionId = str(o, "sessionId"),
            token = str(o, "token"),
            model = str(o, "model"),
            url = str(o, "url")?.takeIf { it.startsWith("wss://") },
            voice = str(o, "voice"),
            startByMs = long(o, "startByMs"),
            expiresAtMs = long(o, "expiresAtMs"),
            reason = str(o, "reason"),
        )
        if (s.state == Session.READY && (s.sessionId == null || s.token == null || s.model == null || s.url == null || s.expiresAtMs == null)) {
            s.copy(state = Session.FAILED, token = null, reason = s.reason ?: "Live sent an incomplete session")
        } else s
    }

    fun encodeUsageRequest(r: UsageRequest): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("sessionId", r.sessionId)
        put("in", r.inSeconds)
        put("out", r.outSeconds)
    }.toString()

    fun decodeUsageRequest(body: String): UsageRequest = wrap("live usage request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val id = str(o, "sessionId")?.takeIf { it.length in 1..MAX_ID } ?: throw IllegalArgumentException("sessionId")
        UsageRequest(id, (long(o, "in") ?: 0).coerceAtLeast(0), (long(o, "out") ?: 0).coerceAtLeast(0))
    }

    fun encodeUsage(u: Usage): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("counted", u.counted)
        putMeter(u.meter)
    }.toString()

    fun decodeUsage(body: String): Usage = wrap("live usage") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        Usage((o["counted"] as? JsonPrimitive)?.contentOrNull == "true", meter(o))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putMeter(m: Meter) {
        put("configured", m.configured)
        put("level", m.level)
        put("spentPence", m.spentPence)
        put("capPence", m.capPence)
    }

    private fun meter(o: JsonObject) = Meter(
        configured = (o["configured"] as? JsonPrimitive)?.contentOrNull == "true",
        level = str(o, "level")?.takeIf { it in LEVELS } ?: "ok",
        spentPence = (long(o, "spentPence") ?: 0).coerceAtLeast(0),
        capPence = (long(o, "capPence") ?: 0).coerceAtLeast(0),
    )

    private val LEVELS = setOf("ok", "heads_up", "over")

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun long(o: JsonObject, k: String): Long? = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

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
