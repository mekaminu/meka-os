package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Linking a watch on the wire (build plan "Galaxy Watch", slice 1; ADR-005 amendment 2026-10-10): a device that isn't
 * in the household yet (the watch) makes its own P-256 key and asks MEKA's server for a short code
 * (`POST /v1/link/start`, signed with that key); Meka types the code on a keyed device (`POST /v1/devices/link`), which
 * enrols the watch with that key; the watch then picks up its device secret once (`POST /v1/link/status`, signed with
 * the same key). A keyed device lists and unlinks watches (`POST /v1/devices/watches · unlink`). The server and the
 * devices both read and write these bodies with this codec; unknown keys are ignored.
 */
object DeviceLinkCodec {
    const val CODE_DIGITS = 8
    const val MAX_NAME = 40
    const val MAX_WATCHES = 20
    const val WAITING = "waiting"
    const val EXPIRED = "expired"
    const val LINKED = "linked"

    /** Refusals in `{"error": …}`: the code isn't one waiting · too many wrong codes · that watch id was unlinked · busy. */
    const val ERR_CODE = "code"
    const val ERR_WAIT = "wait"
    const val ERR_REVOKED = "revoked"
    const val ERR_BUSY = "busy"
    const val ERR_UNKNOWN = "unknown"

    private val json = Json { ignoreUnknownKeys = true }

    data class Start(val deviceId: String, val name: String, val publicKey: String)
    data class Started(val linkId: String, val code: String, val expiresAtMs: Long)

    /** [state] waiting · expired · linked; the rest only once linked (the secret is handed over exactly once). */
    data class Status(
        val state: String,
        val householdId: String? = null,
        val deviceId: String? = null,
        val secret: String? = null,
    )

    data class Linked(val deviceId: String, val name: String)
    data class Watch(val id: String, val name: String, val linkedAtMs: Long)

    // ---- what each id and value may be ----

    /** A watch's own device id: "watch-" and 8–32 lower-case letters or digits (it makes it on first open). */
    fun isWatchId(id: String): Boolean {
        val rest = id.removePrefix("watch-")
        return id.startsWith("watch-") && rest.length in 8..32 && rest.all { it in 'a'..'z' || it in '0'..'9' }
    }

    /** A link the server made: "lnk" and 24 lower-case hex digits. */
    fun isLinkId(id: String): Boolean = id.length == 27 && id.startsWith("lnk") && id.drop(3).all(::isHex)

    /** Exactly [CODE_DIGITS] digits. */
    fun isCode(code: String): Boolean = code.length == CODE_DIGITS && code.all { it in '0'..'9' }

    /** A name to show ("Galaxy Watch"): 1–40 characters, no control characters, trimmed. */
    fun cleanName(raw: String): String? {
        if (raw.any { it.isISOControl() }) return null
        val t = raw.trim().replace(Regex(" +"), " ")
        if (t.isEmpty() || t.length > MAX_NAME) return null
        return t
    }

    private fun isSecret(s: String) = s.length == 64 && s.all(::isHex)
    private fun isHousehold(s: String) = s.length in 1..64 && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f'

    // ---- the watch's side ----

    fun encodeStart(s: Start): String = buildJsonObject {
        put("deviceId", s.deviceId); put("name", s.name); put("publicKey", s.publicKey)
    }.toString()

    fun decodeStart(body: String): Start = wrap("link start") {
        val o = json.parseToJsonElement(body).jsonObject
        Start(
            deviceId = o.str("deviceId")?.takeIf(::isWatchId) ?: throw IllegalArgumentException("deviceId"),
            name = o.str("name")?.let(::cleanName) ?: throw IllegalArgumentException("name"),
            publicKey = o.str("publicKey")?.takeIf { it.length in 40..400 } ?: throw IllegalArgumentException("publicKey"),
        )
    }

    fun encodeStarted(s: Started): String = buildJsonObject {
        put("linkId", s.linkId); put("code", s.code); put("expiresAtMs", s.expiresAtMs)
    }.toString()

    fun decodeStarted(body: String): Started = wrap("link started") {
        val o = json.parseToJsonElement(body).jsonObject
        Started(
            linkId = o.str("linkId")?.takeIf(::isLinkId) ?: throw IllegalArgumentException("linkId"),
            code = o.str("code")?.takeIf(::isCode) ?: throw IllegalArgumentException("code"),
            expiresAtMs = o.long("expiresAtMs") ?: throw IllegalArgumentException("expiresAtMs"),
        )
    }

    fun encodeStatusRequest(linkId: String): String = buildJsonObject { put("linkId", linkId) }.toString()

    fun decodeStatusRequest(body: String): String = wrap("link status request") {
        json.parseToJsonElement(body).jsonObject.str("linkId")?.takeIf(::isLinkId) ?: throw IllegalArgumentException("linkId")
    }

    fun encodeStatus(s: Status): String = buildJsonObject {
        put("state", s.state)
        s.householdId?.let { put("householdId", it) }
        s.deviceId?.let { put("deviceId", it) }
        s.secret?.let { put("secret", it) }
    }.toString()

    /** A linked answer must carry all three, well formed; anything else unknown reads as expired (start again). */
    fun decodeStatus(body: String): Status = wrap("link status") {
        val o = json.parseToJsonElement(body).jsonObject
        when (o.str("state")) {
            WAITING -> Status(WAITING)
            LINKED -> Status(
                LINKED,
                householdId = o.str("householdId")?.takeIf(::isHousehold) ?: throw IllegalArgumentException("householdId"),
                deviceId = o.str("deviceId")?.takeIf(::isWatchId) ?: throw IllegalArgumentException("deviceId"),
                secret = o.str("secret")?.takeIf(::isSecret) ?: throw IllegalArgumentException("secret"),
            )
            else -> Status(EXPIRED)
        }
    }

    // ---- Meka's side (a keyed device of the household) ----

    fun encodeApprove(code: String): String = buildJsonObject { put("code", code) }.toString()

    fun decodeApprove(body: String): String = wrap("link approve") {
        json.parseToJsonElement(body).jsonObject.str("code")?.takeIf(::isCode) ?: throw IllegalArgumentException("code")
    }

    fun encodeLinked(l: Linked): String = buildJsonObject { put("deviceId", l.deviceId); put("name", l.name) }.toString()

    fun decodeLinked(body: String): Linked = wrap("linked") {
        val o = json.parseToJsonElement(body).jsonObject
        Linked(
            deviceId = o.str("deviceId")?.takeIf(::isWatchId) ?: throw IllegalArgumentException("deviceId"),
            name = o.str("name")?.let(::cleanName) ?: throw IllegalArgumentException("name"),
        )
    }

    fun encodeWatches(watches: List<Watch>): String = buildJsonObject {
        putJsonArray("watches") {
            watches.take(MAX_WATCHES).forEach { w ->
                addJsonObject { put("id", w.id); put("name", w.name); put("linkedAtMs", w.linkedAtMs) }
            }
        }
    }.toString()

    /** The household's linked watches; entries that don't read are left out, at most [MAX_WATCHES]. */
    fun decodeWatches(body: String): List<Watch> = wrap("watches") {
        val arr = json.parseToJsonElement(body).jsonObject["watches"] as? JsonArray ?: throw IllegalArgumentException("watches")
        arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Watch(
                id = o.str("id")?.takeIf(::isWatchId) ?: return@mapNotNull null,
                name = o.str("name")?.let(::cleanName) ?: return@mapNotNull null,
                linkedAtMs = o.long("linkedAtMs") ?: return@mapNotNull null,
            )
        }.take(MAX_WATCHES)
    }

    fun encodeUnlink(id: String): String = buildJsonObject { put("id", id) }.toString()

    fun decodeUnlink(body: String): String = wrap("unlink") {
        json.parseToJsonElement(body).jsonObject.str("id")?.takeIf(::isWatchId) ?: throw IllegalArgumentException("id")
    }

    fun encodeError(reason: String): String = buildJsonObject { put("error", reason) }.toString()

    /** The refusal's reason, or null when [body] isn't one. */
    fun decodeError(body: String): String? = runCatching { json.parseToJsonElement(body).jsonObject.str("error") }.getOrNull()

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
