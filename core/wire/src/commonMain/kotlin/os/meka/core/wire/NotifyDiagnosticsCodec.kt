package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Notifications never seen, slice 2. Three routes, all signed by a keyed device:
 *  - `POST` [REPORT_PATH]: the phone sends its notification trace (allowed or not, MEKA's own phone setting, each
 *    channel on/off, the last [TRACE_MAX] trace entries) so a build run can read where notifications stop;
 *  - `POST` [LATEST_PATH]: the release publisher (or a keyed device) reads the household's newest report, with the
 *    server's last push to the device that sent it;
 *  - `POST` [TEST_PATH]: Send a test asks the server to push a test to this very device; the answer is what Firebase
 *    said ([RESULTS]).
 * Nothing personal: channel ids and names, counts, reasons and error class names; never a notification's words.
 */
object NotifyDiagnosticsCodec {
    const val REPORT_PATH = "/v1/diagnostics/notifications"
    const val LATEST_PATH = "/v1/diagnostics/notifications/latest"
    const val TEST_PATH = "/v1/push/test"
    const val TRACE_MAX = 40
    const val CHANNELS_MAX = 24
    const val DETAIL_MAX = 120
    const val NAME_MAX = 40
    const val MODEL_MAX = 40

    const val SENT = "sent"
    const val OFF = "off"
    const val FAILED = "failed"
    const val GONE = "gone"
    const val NO_ADDRESS = "no_address"
    val RESULTS = setOf(SENT, OFF, FAILED, GONE, NO_ADDRESS)

    private val json = Json { ignoreUnknownKeys = true }

    /** [lastPostMs]: when MEKA last posted on the channel from this phone (slice 3; older phones send none). */
    data class Channel(val id: String, val name: String, val on: Boolean, val lastPostMs: Long? = null)

    data class Entry(val atMs: Long, val kind: String, val detail: String)

    data class Report(
        val model: String?,
        val sentAtMs: Long,
        val allowed: Boolean,
        /** MEKA's "This phone" setting: "ALL" · "DIGESTS" · "OFF". */
        val phone: String,
        val channels: List<Channel>,
        /** Newest first. */
        val trace: List<Entry>,
    )

    /** The newest report and what the server knows of its last push to the device that sent it. */
    data class Latest(val report: Report?, val lastPushAtMs: Long? = null, val lastPushResult: String? = null, val hasAddress: Boolean? = null)

    fun encodeReport(r: Report): String = obj(r).toString()

    fun decodeReport(body: String): Report = wrap("notification report") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        report(o)
    }

    fun encodeEmptyRequest(): String = buildJsonObject { put("w", WireCodec.VERSION) }.toString()

    fun decodeEmptyRequest(body: String) = wrap("notification request") {
        if (body.isBlank()) return@wrap
        checkVersion(json.parseToJsonElement(body).jsonObject)
    }

    fun encodeLatest(l: Latest): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("report", l.report?.let { obj(it, version = false) } ?: JsonNull)
        if (l.lastPushAtMs != null && l.lastPushResult != null) {
            put("lastPushAt", l.lastPushAtMs); put("lastPush", l.lastPushResult)
        }
        l.hasAddress?.let { put("address", it) }
    }.toString()

    fun decodeLatest(body: String): Latest = wrap("notification answer") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val at = o.long("lastPushAt")?.takeIf { it > 0 }
        val result = o.str("lastPush")?.let { if (it in RESULTS) it else FAILED }
        Latest(
            (o["report"] as? JsonObject)?.let { runCatching { report(it) }.getOrNull() },
            lastPushAtMs = result?.let { at },
            lastPushResult = at?.let { result },
            hasAddress = (o["address"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
        )
    }

    fun encodeTestAnswer(result: String): String = buildJsonObject {
        put("w", WireCodec.VERSION); put("result", result)
    }.toString()

    /** What the server said; an unknown word reads as failed. */
    fun decodeTestAnswer(body: String): String = wrap("push test answer") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        o.str("result")?.let { if (it in RESULTS) it else FAILED } ?: throw IllegalArgumentException("result")
    }

    private fun clean(s: String, max: Int) = s.replace('\n', ' ').take(max)

    private fun obj(r: Report, version: Boolean = true): JsonObject = buildJsonObject {
        if (version) put("w", WireCodec.VERSION)
        r.model?.let { put("model", clean(it, MODEL_MAX)) }
        put("sent", r.sentAtMs)
        put("allowed", r.allowed)
        put("phone", clean(r.phone, 12))
        put("channels", buildJsonArray {
            r.channels.take(CHANNELS_MAX).forEach { c ->
                add(buildJsonObject { put("id", clean(c.id, NAME_MAX)); put("name", clean(c.name, NAME_MAX)); put("on", c.on)
                    c.lastPostMs?.takeIf { it > 0 }?.let { put("last", it) }
                })
            }
        })
        put("trace", buildJsonArray {
            r.trace.take(TRACE_MAX).forEach { e ->
                add(buildJsonObject { put("at", e.atMs); put("kind", clean(e.kind, 12)); put("detail", clean(e.detail, DETAIL_MAX)) })
            }
        })
    }

    private fun report(o: JsonObject): Report {
        val sent = o.long("sent")?.takeIf { it > 0 } ?: throw IllegalArgumentException("sent")
        val allowed = (o["allowed"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw IllegalArgumentException("allowed")
        val channels = (o["channels"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val id = c.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val on = (c["on"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return@mapNotNull null
            Channel(clean(id, NAME_MAX), clean(c.str("name") ?: id, NAME_MAX), on, c.long("last")?.takeIf { it > 0 })
        }.take(CHANNELS_MAX)
        val trace = (o["trace"] as? JsonArray ?: throw IllegalArgumentException("trace")).mapNotNull { e ->
            val t = e as? JsonObject ?: return@mapNotNull null
            val at = t.long("at")?.takeIf { it > 0 } ?: return@mapNotNull null
            val kind = t.str("kind")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Entry(at, clean(kind, 12), clean(t.str("detail").orEmpty(), DETAIL_MAX))
        }.take(TRACE_MAX)
        return Report(o.str("model")?.let { clean(it, MODEL_MAX) }?.takeIf { it.isNotBlank() }, sent, allowed, clean(o.str("phone") ?: "ALL", 12), channels, trace)
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
