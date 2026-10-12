package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The recovery key on the wire (build plan V1 "Recovery key"; ADR-005 amendment 2026-10-12).
 *
 * - `POST /v1/recovery/set` from a keyed phone or Mac: `{"verifier"}` (64 hex: SHA-256 of the key's secret; the words
 *   never leave the device) → the new status. Replaces any key the household had.
 * - `POST /v1/recovery/status` from a keyed device → `{"state": none · ready · used, "createdAtMs", "createdBy",
 *   "usedAtMs", "usedBy"}` (device names, never ids or hashes).
 * - `POST /v1/recovery/enrol` from a device that isn't in a household yet, signed with its own new P-256 key (no
 *   bearer): `{"secret","deviceId","name","publicKey"}` → `{"householdId","deviceId","secret"}`, the device's own secret
 *   handed over once. Refusals are `{"error": key · wait · taken · revoked}`.
 *
 * Unknown keys are ignored both ways.
 */
object RecoveryCodec {
    const val NONE = "none"
    const val READY = "ready"
    const val USED = "used"

    const val ERR_KEY = "key"
    const val ERR_WAIT = "wait"
    const val ERR_TAKEN = "taken"
    const val ERR_REVOKED = "revoked"

    private val json = Json { ignoreUnknownKeys = true }

    data class Status(
        val state: String,
        val createdAtMs: Long? = null,
        val createdBy: String? = null,
        val usedAtMs: Long? = null,
        val usedBy: String? = null,
    )

    data class Enrol(val secret: String, val deviceId: String, val name: String, val publicKey: String)
    data class Enrolled(val householdId: String, val deviceId: String, val secret: String)

    /** A phone's or Mac's own id ("android" or "mac" and 8–32 lower-case hex digits): watches link from a device instead. */
    fun isDeviceId(id: String): Boolean {
        val rest = when {
            id.startsWith("android") -> id.removePrefix("android")
            id.startsWith("mac") -> id.removePrefix("mac")
            else -> return false
        }
        return rest.length in 8..32 && rest.all(::isHex)
    }

    fun isHash(s: String) = s.length == 64 && s.all(::isHex)
    private fun isHousehold(s: String) = s.length in 1..64 && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f'

    fun encodeSet(verifier: String): String = buildJsonObject { put("verifier", verifier) }.toString()

    fun decodeSet(body: String): String = wrap("recovery set") {
        json.parseToJsonElement(body).jsonObject.str("verifier")?.takeIf(::isHash) ?: throw IllegalArgumentException("verifier")
    }

    fun encodeStatus(s: Status): String = buildJsonObject {
        put("state", s.state)
        s.createdAtMs?.let { put("createdAtMs", it) }
        s.createdBy?.let { put("createdBy", it) }
        s.usedAtMs?.let { put("usedAtMs", it) }
        s.usedBy?.let { put("usedBy", it) }
    }.toString()

    /** An unknown state reads as none (make one). Names longer than 40 characters are dropped. */
    fun decodeStatus(body: String): Status = wrap("recovery status") {
        val o = json.parseToJsonElement(body).jsonObject
        val state = o.str("state")?.takeIf { it == READY || it == USED } ?: NONE
        if (state == NONE) return@wrap Status(NONE)
        Status(
            state,
            createdAtMs = o.long("createdAtMs"),
            createdBy = o.str("createdBy")?.let(DeviceLinkCodec::cleanName),
            usedAtMs = o.long("usedAtMs"),
            usedBy = o.str("usedBy")?.let(DeviceLinkCodec::cleanName),
        )
    }

    fun encodeEnrol(e: Enrol): String = buildJsonObject {
        put("secret", e.secret); put("deviceId", e.deviceId); put("name", e.name); put("publicKey", e.publicKey)
    }.toString()

    fun decodeEnrol(body: String): Enrol = wrap("recovery enrol") {
        val o = json.parseToJsonElement(body).jsonObject
        Enrol(
            secret = o.str("secret")?.takeIf(::isHash) ?: throw IllegalArgumentException("secret"),
            deviceId = o.str("deviceId")?.takeIf(::isDeviceId) ?: throw IllegalArgumentException("deviceId"),
            name = o.str("name")?.let(DeviceLinkCodec::cleanName) ?: throw IllegalArgumentException("name"),
            publicKey = o.str("publicKey")?.takeIf { it.length in 40..400 } ?: throw IllegalArgumentException("publicKey"),
        )
    }

    fun encodeEnrolled(e: Enrolled): String = buildJsonObject {
        put("householdId", e.householdId); put("deviceId", e.deviceId); put("secret", e.secret)
    }.toString()

    fun decodeEnrolled(body: String): Enrolled = wrap("recovery enrolled") {
        val o = json.parseToJsonElement(body).jsonObject
        Enrolled(
            householdId = o.str("householdId")?.takeIf(::isHousehold) ?: throw IllegalArgumentException("householdId"),
            deviceId = o.str("deviceId")?.takeIf(::isDeviceId) ?: throw IllegalArgumentException("deviceId"),
            secret = o.str("secret")?.takeIf(::isHash) ?: throw IllegalArgumentException("secret"),
        )
    }

    fun encodeError(reason: String): String = DeviceLinkCodec.encodeError(reason)
    fun decodeError(body: String): String? = DeviceLinkCodec.decodeError(body)

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
