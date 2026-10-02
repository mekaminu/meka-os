package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.Op
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PullResponse
import os.meka.core.sync.PushRequest
import os.meka.core.sync.PushResponse
import os.meka.core.sync.SequencedOp

class WireFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Versioned JSON wire format for sync (ADR-003). Hand-written with the JSON tree API so the kernel needs no
 * compiler plugin and the format is explicit: renaming a Kotlin property can never silently change the protocol.
 *
 * Every document carries `"w": 1`. Decoders reject unknown major versions and ignore unknown fields.
 */
object WireCodec {
    const val VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeOp(op: Op): JsonObject = buildJsonObject {
        put("id", op.opId)
        put("hh", op.householdId)
        put("et", op.entityType)
        put("eid", op.entityId)
        put("f", op.field)
        put("v", encodeValue(op.value))
        put("hlc", op.hlc.encode())
        put("base", JsonArray(op.baseOpIds.map { JsonPrimitive(it) }))
        put("dev", op.deviceId)
        put("sv", op.schemaVersion)
    }

    fun decodeOp(e: JsonElement): Op = wrap("op") {
        val o = e.jsonObject
        Op(
            opId = o.str("id"),
            householdId = o.str("hh"),
            entityType = o.str("et"),
            entityId = o.str("eid"),
            field = o.str("f"),
            value = decodeValue(o.getValue("v")),
            hlc = Hlc.decode(o.str("hlc")),
            baseOpIds = o["base"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            deviceId = o.str("dev"),
            schemaVersion = o["sv"]?.jsonPrimitive?.int ?: 1,
        )
    }

    private fun encodeValue(v: FieldValue): JsonObject = buildJsonObject {
        when (v) {
            is FieldValue.Text -> { put("t", "s"); put("s", v.value) }
            is FieldValue.Int64 -> { put("t", "i"); put("i", v.value.toString()) } // string: JS-safe 64-bit
            is FieldValue.Bool -> { put("t", "b"); put("b", v.value) }
            FieldValue.Null -> put("t", "n")
        }
    }

    private fun decodeValue(e: JsonElement): FieldValue {
        val o = e.jsonObject
        return when (val t = o.str("t")) {
            "s" -> FieldValue.Text(o.str("s"))
            "i" -> FieldValue.Int64(o.str("i").toLong())
            "b" -> FieldValue.Bool(o.getValue("b").jsonPrimitive.boolean)
            "n" -> FieldValue.Null
            else -> throw WireFormatException("unknown value type '$t'")
        }
    }

    fun encodePushRequest(r: PushRequest): String = doc {
        put("hh", r.householdId); put("dev", r.deviceId)
        put("ops", JsonArray(r.ops.map(::encodeOp)))
    }

    fun decodePushRequest(s: String): PushRequest = parse(s) { o ->
        PushRequest(o.str("hh"), o.str("dev"), o.getValue("ops").jsonArray.map(::decodeOp))
    }

    fun encodePushResponse(r: PushResponse): String = doc {
        put("ack", JsonArray(r.acknowledged.map { JsonPrimitive(it) }))
        put("rej", JsonObject(r.rejected.mapValues { JsonPrimitive(it.value) }))
    }

    fun decodePushResponse(s: String): PushResponse = parse(s) { o ->
        PushResponse(
            o.getValue("ack").jsonArray.map { it.jsonPrimitive.content },
            o["rej"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
        )
    }

    fun encodePullRequest(r: PullRequest): String = doc {
        put("hh", r.householdId); put("dev", r.deviceId); put("after", r.afterSeq); put("limit", r.limit)
    }

    fun decodePullRequest(s: String): PullRequest = parse(s) { o ->
        PullRequest(o.str("hh"), o.str("dev"), o.getValue("after").jsonPrimitive.long, o["limit"]?.jsonPrimitive?.int ?: 500)
    }

    fun encodePullResponse(r: PullResponse): String = doc {
        put("ops", JsonArray(r.ops.map { buildJsonObject { put("seq", it.seq); put("op", encodeOp(it.op)) } }))
        put("more", r.hasMore)
    }

    fun decodePullResponse(s: String): PullResponse = parse(s) { o ->
        PullResponse(
            o.getValue("ops").jsonArray.map { SequencedOp(it.jsonObject.getValue("seq").jsonPrimitive.long, decodeOp(it.jsonObject.getValue("op"))) },
            o.getValue("more").jsonPrimitive.boolean,
        )
    }

    /** Device enrolment (ADR-005 M0): request carries identity; response carries the device secret, once. */
    data class EnrolRequest(val householdId: String, val deviceId: String, val name: String)

    private val idPattern = Regex("^[a-z0-9]{1,64}$")

    fun encodeEnrolRequest(r: EnrolRequest): String = doc { put("hh", r.householdId); put("dev", r.deviceId); put("name", r.name) }

    fun decodeEnrolRequest(s: String): EnrolRequest = parse(s) { o ->
        val r = EnrolRequest(o.str("hh"), o.str("dev"), o.str("name").take(80))
        if (!idPattern.matches(r.householdId) || !idPattern.matches(r.deviceId)) throw WireFormatException("ids must be [a-z0-9]{1,64}")
        r
    }

    fun encodeEnrolResponse(secret: String): String = doc { put("secret", secret) }
    fun decodeEnrolResponse(s: String): String = parse(s) { o -> o.str("secret") }

    /** Connected calendar/email accounts (ADR-008). Tokens never cross the wire; this is display state only. */
    data class IntegrationAccount(val provider: String, val email: String, val status: String, val lastSyncAtMs: Long?)

    fun encodeConnectUrl(url: String): String = doc { put("url", url) }
    fun decodeConnectUrl(s: String): String = parse(s) { o -> o.str("url") }

    fun encodeAccounts(accounts: List<IntegrationAccount>): String = doc {
        put("accounts", JsonArray(accounts.map { a ->
            buildJsonObject {
                put("provider", a.provider); put("email", a.email); put("status", a.status)
                a.lastSyncAtMs?.let { put("lastSync", it) }
            }
        }))
    }

    fun decodeAccounts(s: String): List<IntegrationAccount> = parse(s) { o ->
        o.getValue("accounts").jsonArray.map { e ->
            val a = e.jsonObject
            IntegrationAccount(a.str("provider"), a.str("email"), a.str("status"), a["lastSync"]?.jsonPrimitive?.long)
        }
    }

    private fun doc(body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        buildJsonObject { put("w", VERSION); body() }.toString()

    private fun <T> parse(s: String, read: (JsonObject) -> T): T = wrap("document") {
        val o = json.parseToJsonElement(s).jsonObject
        val w = o["w"]?.jsonPrimitive?.int ?: throw WireFormatException("missing wire version")
        if (w != VERSION) throw WireFormatException("unsupported wire version $w")
        read(o)
    }

    private fun JsonObject.str(k: String): String =
        (this[k] ?: throw WireFormatException("missing '$k'")).jsonPrimitive.content

    private inline fun <T> wrap(what: String, block: () -> T): T = try {
        block()
    } catch (e: WireFormatException) {
        throw e
    } catch (e: Exception) {
        throw WireFormatException("malformed $what: ${e.message}", e)
    }
}
