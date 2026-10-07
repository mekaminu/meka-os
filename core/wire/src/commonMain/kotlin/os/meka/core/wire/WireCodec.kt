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

    /** A device's public signing key (ADR-005): X.509 SPKI DER, base64. */
    fun encodeDeviceKey(publicKeyDerBase64: String): String = doc { put("pub", publicKeyDerBase64) }
    fun decodeDeviceKey(s: String): String = parse(s) { o ->
        o.str("pub").also { if (it.length !in 80..400) throw WireFormatException("unexpected public key size") }
    }

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

    /**
     * A published app build (self-updating phone app). Stored on the server in [RELEASE_CHUNK_BYTES] chunks so every
     * request stays under the API's body limit and goes through the normal signed-request checks. Integrity: the
     * SHA-256 of the whole file (checked by the server and again by the phone), and Android's own check that an update
     * is signed with the same key as the installed app.
     */
    data class AppRelease(
        val platform: String,
        val versionCode: Long,
        val versionName: String,
        val sha256: String,
        val sizeBytes: Long,
        val chunkCount: Int,
    )

    /** One chunk of a release being published; [dataB64] is the chunk's bytes in standard base64. */
    data class ReleaseChunk(val release: AppRelease, val index: Int, val dataB64: String)

    data class ChunkRef(val platform: String, val versionCode: Long, val index: Int)

    data class UploadAck(val received: Int, val complete: Boolean)

    const val RELEASE_CHUNK_BYTES = 1 shl 20
    const val RELEASE_MAX_BYTES = 200L shl 20
    private val platformPattern = Regex("^[a-z]{1,16}$")
    private val shaPattern = Regex("^[0-9a-f]{64}$")

    /** Chunks a file of [sizeBytes] is split into. */
    fun releaseChunkCount(sizeBytes: Long): Int = ((sizeBytes + RELEASE_CHUNK_BYTES - 1) / RELEASE_CHUNK_BYTES).toInt()

    /** Bytes chunk [index] of a file of [sizeBytes] holds. */
    fun releaseChunkSize(sizeBytes: Long, index: Int): Int =
        minOf(RELEASE_CHUNK_BYTES.toLong(), sizeBytes - index.toLong() * RELEASE_CHUNK_BYTES).toInt()

    private fun JsonObject.release(): AppRelease {
        val r = AppRelease(
            platform = str("platform"), versionCode = getValue("code").jsonPrimitive.long, versionName = str("name"),
            sha256 = str("sha256"), sizeBytes = getValue("size").jsonPrimitive.long, chunkCount = getValue("chunks").jsonPrimitive.int,
        )
        if (!platformPattern.matches(r.platform)) throw WireFormatException("bad platform")
        if (r.versionCode !in 1L..2_100_000_000L) throw WireFormatException("bad version code")
        if (r.versionName.isBlank() || r.versionName.length > 64) throw WireFormatException("bad version name")
        if (!shaPattern.matches(r.sha256)) throw WireFormatException("bad sha256")
        if (r.sizeBytes !in 1L..RELEASE_MAX_BYTES) throw WireFormatException("bad size")
        if (r.chunkCount != releaseChunkCount(r.sizeBytes)) throw WireFormatException("bad chunk count")
        return r
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putRelease(r: AppRelease) {
        put("platform", r.platform); put("code", r.versionCode); put("name", r.versionName)
        put("sha256", r.sha256); put("size", r.sizeBytes); put("chunks", r.chunkCount)
    }

    /** The newest complete release, or none. */
    fun encodeRelease(r: AppRelease?): String = doc { if (r != null) put("release", buildJsonObject { putRelease(r) }) }
    fun decodeRelease(s: String): AppRelease? = parse(s) { o -> o["release"]?.jsonObject?.release() }

    fun encodeReleaseChunk(c: ReleaseChunk): String = doc { putRelease(c.release); put("i", c.index); put("data", c.dataB64) }
    fun decodeReleaseChunk(s: String): ReleaseChunk = parse(s) { o ->
        val r = o.release()
        val i = o.getValue("i").jsonPrimitive.int
        if (i !in 0 until r.chunkCount) throw WireFormatException("bad chunk index")
        val data = o.str("data")
        val expected = releaseChunkSize(r.sizeBytes, i)
        if (data.length != (expected + 2) / 3 * 4) throw WireFormatException("bad chunk length")
        ReleaseChunk(r, i, data)
    }

    fun encodeChunkRef(c: ChunkRef): String = doc { put("platform", c.platform); put("code", c.versionCode); put("i", c.index) }
    fun decodeChunkRef(s: String): ChunkRef = parse(s) { o ->
        val c = ChunkRef(o.str("platform"), o.getValue("code").jsonPrimitive.long, o.getValue("i").jsonPrimitive.int)
        if (!platformPattern.matches(c.platform) || c.index < 0 || c.versionCode < 1) throw WireFormatException("bad chunk reference")
        c
    }

    /** Asks for the newest release of a platform. */
    fun encodePlatform(platform: String): String = doc { put("platform", platform) }
    fun decodePlatform(s: String): String = parse(s) { o ->
        o.str("platform").also { if (!platformPattern.matches(it)) throw WireFormatException("bad platform") }
    }

    fun encodeChunkData(dataB64: String): String = doc { put("data", dataB64) }
    fun decodeChunkData(s: String): String = parse(s) { o -> o.str("data") }

    fun encodeUploadAck(a: UploadAck): String = doc { put("received", a.received); put("complete", a.complete) }
    fun decodeUploadAck(s: String): UploadAck = parse(s) { o ->
        UploadAck(o.getValue("received").jsonPrimitive.int, o.getValue("complete").jsonPrimitive.boolean)
    }

    /** What the Android Gradle plugin writes beside an APK (`output-metadata.json`): the build's identity. */
    data class ApkMetadata(val applicationId: String, val versionCode: Long, val versionName: String, val outputFile: String)

    /** Reads `output-metadata.json`; null when it isn't one or names no single APK. Not a wire document (no "w"). */
    fun decodeApkMetadata(s: String): ApkMetadata? = runCatching {
        val o = json.parseToJsonElement(s).jsonObject
        val e = o.getValue("elements").jsonArray.singleOrNull()?.jsonObject ?: return null
        ApkMetadata(
            applicationId = o.str("applicationId"), versionCode = e.getValue("versionCode").jsonPrimitive.long,
            versionName = e.str("versionName"), outputFile = e.str("outputFile"),
        ).takeIf { it.versionCode > 0 && it.versionName.isNotBlank() && it.versionName.length <= 64 && it.outputFile.endsWith(".apk") }
    }.getOrNull()

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
