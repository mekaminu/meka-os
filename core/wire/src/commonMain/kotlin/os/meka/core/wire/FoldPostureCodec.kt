package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The Fold's posture changes (bedside clock, slice 2). `POST` [REPORT_PATH], signed by the Fold, sends its last
 * [CHANGES_MAX] posture changes (newest first: when, and what WindowManager, the window and the hinge sensor said);
 * `POST` [LATEST_PATH] (a keyed device, or GitHub's release publisher) reads the household's newest report so a build
 * run can see what the Fold 8 actually reports. Nothing personal: the phone's model name, the times and the readings.
 */
object FoldPostureCodec {
    const val REPORT_PATH = "/v1/diagnostics/fold"
    const val LATEST_PATH = "/v1/diagnostics/fold/latest"
    const val CHANGES_MAX = 20
    const val ENTRY_MAX = 200
    const val MODEL_MAX = 40

    private val json = Json { ignoreUnknownKeys = true }

    data class Change(val atMs: Long, val entry: String)

    data class Report(val model: String?, val sentAtMs: Long, val changes: List<Change>)

    fun encodeReport(r: Report): String = obj(r).toString()

    fun decodeReport(body: String): Report = wrap("fold posture report") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        report(o)
    }

    fun encodeLatestRequest(): String = buildJsonObject { put("w", WireCodec.VERSION) }.toString()

    fun decodeLatestRequest(body: String) = wrap("fold posture request") {
        if (body.isBlank()) return@wrap
        checkVersion(json.parseToJsonElement(body).jsonObject)
    }

    /** The newest report, or none yet. */
    fun encodeLatest(r: Report?): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("report", r?.let { obj(it, version = false) } ?: JsonNull)
    }.toString()

    /** Null when none was reported (or the stored one can't be read). */
    fun decodeLatest(body: String): Report? = wrap("fold posture answer") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        (o["report"] as? JsonObject)?.let { runCatching { report(it) }.getOrNull() }
    }

    private fun obj(r: Report, version: Boolean = true): JsonObject = buildJsonObject {
        if (version) put("w", WireCodec.VERSION)
        r.model?.let { put("model", it.take(MODEL_MAX)) }
        put("sent", r.sentAtMs)
        put("changes", buildJsonArray {
            r.changes.take(CHANGES_MAX).forEach { c ->
                add(buildJsonObject { put("at", c.atMs); put("entry", c.entry.take(ENTRY_MAX)) })
            }
        })
    }

    private fun report(o: JsonObject): Report {
        val sent = o.long("sent")?.takeIf { it > 0 } ?: throw IllegalArgumentException("sent")
        val changes = (o["changes"] as? JsonArray ?: throw IllegalArgumentException("changes")).mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val at = c.long("at")?.takeIf { it > 0 } ?: return@mapNotNull null
            val entry = c.str("entry")?.replace('\n', ' ')?.take(ENTRY_MAX)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Change(at, entry)
        }.take(CHANGES_MAX)
        return Report(o.str("model")?.take(MODEL_MAX)?.takeIf { it.isNotBlank() }, sent, changes)
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
