package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A new phone or Mac asking to join, on the wire (build plan M1 "Clean-ups": approve a new device from your Fold;
 * ADR-005 amendment 2026-10-19). Once the household has a phone or Mac that holds its signing key and was in use in the
 * last [DeviceJoinCodec.APPROVER_SEEN_DAYS] days, the enrolment code alone no longer adds a **new** device id:
 *
 * 1. `POST /v1/enrol` (the code, as before) answers 202 with [Waiting]: a request id only the asking device knows, a
 *    six-digit code it shows, and when the ask runs out.
 * 2. Meka's Fold or Mac shows "A new Mac wants to join · 482 913" in Needs you and answers `POST /v1/devices/joins/answer`
 *    ([Answer], signed with that device's key).
 * 3. The asking device polls `POST /v1/enrol/status` (the code again, with [requestId][Waiting.requestId]) and gets
 *    waiting · refused · expired · enrolled (its secret, handed over exactly once).
 *
 * The server and the devices both read and write these bodies with this codec; unknown keys are ignored.
 */
object DeviceJoinCodec {
    const val CODE_DIGITS = 6
    const val STATUS_PATH = "/v1/enrol/status"
    const val ANSWER_PATH = "/v1/devices/joins/answer"
    /** How long an ask waits for Meka's answer. */
    const val ASK_MS = 10 * 60_000L
    /** An approving device must have sent something in this many days; otherwise the code alone still works (both lost). */
    const val APPROVER_SEEN_DAYS = 14

    const val WAITING = "waiting"
    const val REFUSED = "refused"
    const val EXPIRED = "expired"
    const val ENROLLED = "enrolled"
    /** What the approving device's answer did: allowed · refused · gone (ran out or already answered). */
    const val ALLOWED = "allowed"
    const val GONE = "gone"

    /** The request's kind as the card names it. */
    const val KIND_PHONE = "phone"
    const val KIND_MAC = "mac"

    private val json = Json { ignoreUnknownKeys = true }

    data class Waiting(val requestId: String, val code: String, val expiresAtMs: Long)

    /** [state] waiting · refused · expired · enrolled; [secret] only when enrolled (handed over once). */
    data class Status(val state: String, val secret: String? = null)

    data class Answer(val requestId: String, val allow: Boolean)

    /** An ask the server made: "join" and 32 lower-case hex digits. */
    fun isRequestId(id: String): Boolean = id.length == 36 && id.startsWith("join") && id.drop(4).all(::isHex)

    fun isCode(code: String): Boolean = code.length == CODE_DIGITS && code.all { it in '0'..'9' }

    /** phone or mac from a device id's shape ("android…" / "mac…"); null for anything else (a watch links instead). */
    fun kindOf(deviceId: String): String? = when {
        deviceId.startsWith("android") -> KIND_PHONE
        deviceId.startsWith("mac") -> KIND_MAC
        else -> null
    }

    private fun isSecret(s: String) = s.length == 64 && s.all(::isHex)
    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f'

    fun encodeWaiting(w: Waiting): String = buildJsonObject {
        put("state", WAITING); put("requestId", w.requestId); put("code", w.code); put("expiresAtMs", w.expiresAtMs)
    }.toString()

    /** The enrol route's 202 body; null when [body] isn't one (an older server's answer). */
    fun decodeWaiting(body: String): Waiting? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        if (o.str("state") != WAITING) return null
        Waiting(
            requestId = o.str("requestId")?.takeIf(::isRequestId) ?: return null,
            code = o.str("code")?.takeIf(::isCode) ?: return null,
            expiresAtMs = o.long("expiresAtMs") ?: return null,
        )
    }.getOrNull()

    fun encodeStatusRequest(requestId: String): String = buildJsonObject { put("requestId", requestId) }.toString()

    fun decodeStatusRequest(body: String): String = wrap("join status request") {
        json.parseToJsonElement(body).jsonObject.str("requestId")?.takeIf(::isRequestId) ?: throw IllegalArgumentException("requestId")
    }

    fun encodeStatus(s: Status): String = buildJsonObject {
        put("state", s.state)
        s.secret?.let { put("secret", it) }
    }.toString()

    /** An enrolled answer must carry a well-formed secret; anything unknown reads as expired (ask again). */
    fun decodeStatus(body: String): Status = wrap("join status") {
        val o = json.parseToJsonElement(body).jsonObject
        when (o.str("state")) {
            WAITING -> Status(WAITING)
            REFUSED -> Status(REFUSED)
            ENROLLED -> Status(ENROLLED, o.str("secret")?.takeIf(::isSecret) ?: throw IllegalArgumentException("secret"))
            else -> Status(EXPIRED)
        }
    }

    fun encodeAnswer(a: Answer): String = buildJsonObject { put("requestId", a.requestId); put("allow", a.allow) }.toString()

    fun decodeAnswer(body: String): Answer = wrap("join answer") {
        val o = json.parseToJsonElement(body).jsonObject
        Answer(
            requestId = o.str("requestId")?.takeIf(::isRequestId) ?: throw IllegalArgumentException("requestId"),
            allow = (o["allow"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw IllegalArgumentException("allow"),
        )
    }

    /** `{"state": allowed · refused · gone}`. */
    fun encodeAnswered(state: String): String = buildJsonObject { put("state", state) }.toString()

    fun decodeAnswered(body: String): String = wrap("join answered") {
        when (val s = json.parseToJsonElement(body).jsonObject.str("state")) {
            ALLOWED, REFUSED -> s
            else -> GONE
        }
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private inline fun <T> wrap(what: String, block: () -> T): T = try {
        block()
    } catch (e: WireFormatException) {
        throw e
    } catch (e: Exception) {
        throw WireFormatException("bad $what: ${e.message}", e)
    }
}
