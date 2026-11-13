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
 * Obi's response times from each device (Needs Meka #43 "Yes", 2026-11-11). `POST` [REPORT_PATH], signed by a device,
 * sends its last day's summary once a day; `POST` [LATEST_PATH] (a keyed device, or GitHub's release publisher) reads
 * the household's newest summary per device for the daily build report. Numbers only: counts, averages and the slowest
 * time to Obi's voice for hellos and answers, the device's label ("phone", "Mac") and when it was sent.
 */
object DeviceSpeedCodec {
    const val REPORT_PATH = "/v1/diagnostics/speed"
    const val LATEST_PATH = "/v1/diagnostics/speed/latest"
    const val DEVICES_MAX = 8
    const val DEVICE_MAX = 12
    /** A time over a minute isn't a turn's (the devices drop those too). */
    const val MS_MAX = 60_000L
    const val COUNT_MAX = 10_000

    data class Report(
        val device: String,
        val sentAtMs: Long,
        val helloCount: Int,
        val helloAvgMs: Long?,
        val helloMaxMs: Long?,
        val answerCount: Int,
        val answerAvgMs: Long?,
        val answerMaxMs: Long?,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeReport(r: Report): String = obj(r, version = true).toString()

    fun decodeReport(body: String): Report = wrap("device speed report") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        report(o)
    }

    fun encodeLatestRequest(): String = buildJsonObject { put("w", WireCodec.VERSION) }.toString()

    fun decodeLatestRequest(body: String) = wrap("device speed request") {
        if (body.isBlank()) return@wrap
        checkVersion(json.parseToJsonElement(body).jsonObject)
    }

    /** The newest summary per device, newest first. */
    fun encodeLatest(reports: List<Report>): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("reports", buildJsonArray { reports.take(DEVICES_MAX).forEach { add(obj(it, version = false)) } })
    }.toString()

    /** The summaries; one that can't be read is skipped. */
    fun decodeLatest(body: String): List<Report> = wrap("device speed answer") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        (o["reports"] as? JsonArray ?: throw IllegalArgumentException("reports")).mapNotNull { e ->
            runCatching { report(e as JsonObject) }.getOrNull()
        }.take(DEVICES_MAX)
    }

    private fun obj(r: Report, version: Boolean): JsonObject = buildJsonObject {
        if (version) put("w", WireCodec.VERSION)
        put("device", clean(r.device))
        put("sent", r.sentAtMs)
        put("hn", r.helloCount.coerceIn(0, COUNT_MAX))
        r.helloAvgMs?.let { put("ha", it) }
        r.helloMaxMs?.let { put("hm", it) }
        put("an", r.answerCount.coerceIn(0, COUNT_MAX))
        r.answerAvgMs?.let { put("aa", it) }
        r.answerMaxMs?.let { put("am", it) }
    }

    private fun report(o: JsonObject): Report {
        val sent = o.long("sent")?.takeIf { it > 0 } ?: throw IllegalArgumentException("sent")
        val device = o.str("device")?.let(::clean)?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("device")
        fun ms(k: String): Long? = o.long(k)?.takeIf { it in 0..MS_MAX }
        fun count(k: String): Int = (o.long(k) ?: 0L).coerceIn(0L, COUNT_MAX.toLong()).toInt()
        return Report(device, sent, count("hn"), ms("ha"), ms("hm"), count("an"), ms("aa"), ms("am"))
    }

    /** Letters and spaces only, so nothing but a label can ride in this field. */
    private fun clean(s: String): String = s.filter { it.isLetter() || it == ' ' }.trim().take(DEVICE_MAX)

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
