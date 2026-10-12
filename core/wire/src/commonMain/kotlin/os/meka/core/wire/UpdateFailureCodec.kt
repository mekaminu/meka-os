package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Phone update failures (build plan: phone updates sometimes fail to install). `POST` [REPORT_PATH], signed by the
 * Fold, reports one failure; `POST` [LIST_PATH] (a keyed device, or GitHub's release publisher) reads the household's
 * last few, newest first, so a build run can see why an install failed. Nothing personal: the stage, Android's status
 * and message, the build numbers, a free-storage band and the network type.
 */
object UpdateFailureCodec {
    const val REPORT_PATH = "/v1/releases/failure"
    const val LIST_PATH = "/v1/releases/failures"
    const val MESSAGE_MAX = 300
    const val BAND_MAX = 24
    /** The most a list answer carries. */
    const val LIST_MAX = 10

    private val json = Json { ignoreUnknownKeys = true }
    private val stages = setOf("download", "check", "install")
    private val networks = setOf("wifi", "mobile", "other", "none")

    data class Report(
        val stage: String,
        val status: Int?,
        val message: String?,
        val fromCode: Long,
        val toCode: Long,
        val atMs: Long,
        val storage: String?,
        val network: String?,
        val attempts: Int,
    )

    fun encodeReport(r: Report): String = obj(r).toString()

    fun decodeReport(body: String): Report = wrap("update failure") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        report(o)
    }

    fun encodeListRequest(): String = buildJsonObject { put("w", WireCodec.VERSION) }.toString()

    fun decodeListRequest(body: String) = wrap("update failure list request") {
        if (body.isBlank()) return@wrap
        checkVersion(json.parseToJsonElement(body).jsonObject)
    }

    fun encodeList(reports: List<Report>): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("failures", buildJsonArray { reports.take(LIST_MAX).forEach { add(obj(it, version = false)) } })
    }.toString()

    /** A report the reader can't parse is left out, not fatal. */
    fun decodeList(body: String): List<Report> = wrap("update failure list") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        (o["failures"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
            runCatching { report((e as JsonObject)) }.getOrNull()
        }.take(LIST_MAX)
    }

    private fun obj(r: Report, version: Boolean = true): JsonObject = buildJsonObject {
        if (version) put("w", WireCodec.VERSION)
        put("stage", r.stage)
        r.status?.let { put("status", it) }
        r.message?.let { put("message", it.take(MESSAGE_MAX)) }
        put("from", r.fromCode)
        put("to", r.toCode)
        put("at", r.atMs)
        r.storage?.let { put("storage", it.take(BAND_MAX)) }
        r.network?.let { put("network", it) }
        put("attempts", r.attempts)
    }

    private fun report(o: JsonObject): Report {
        val stage = o.str("stage")?.takeIf { it in stages } ?: throw IllegalArgumentException("stage")
        val from = o.long("from")?.takeIf { it >= 0 } ?: throw IllegalArgumentException("from")
        val to = o.long("to")?.takeIf { it > 0 } ?: throw IllegalArgumentException("to")
        val at = o.long("at")?.takeIf { it > 0 } ?: throw IllegalArgumentException("at")
        val attempts = (o["attempts"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.coerceIn(1, 10) ?: 1
        return Report(
            stage = stage,
            status = (o["status"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in -1..99 },
            message = o.str("message")?.take(MESSAGE_MAX)?.takeIf { it.isNotBlank() },
            fromCode = from,
            toCode = to,
            atMs = at,
            storage = o.str("storage")?.take(BAND_MAX)?.takeIf { it.isNotBlank() },
            network = o.str("network")?.takeIf { it in networks },
            attempts = attempts,
        )
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

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
