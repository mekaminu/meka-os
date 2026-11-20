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
    const val SLOW_MAX = 5
    const val NAME_MAX = 32
    const val PLACE_MAX = 24
    val FEEL_MOMENTS = setOf("COLD_START", "TAB", "SCREEN")

    data class Report(
        val device: String,
        val sentAtMs: Long,
        val helloCount: Int,
        val helloAvgMs: Long?,
        val helloMaxMs: Long?,
        val answerCount: Int,
        val answerAvgMs: Long?,
        val answerMaxMs: Long?,
        /** 2026-11-20: the day's slowest core work by the code's own names, slowest first (at most [SLOW_MAX]). */
        val slow: List<Work> = emptyList(),
        /** The same day's feel times: cold start, tab switch, first content ([FEEL_MOMENTS]), at most one each. */
        val feel: List<Feel> = emptyList(),
        /** 2026-11-20: the day's Live talks, Google's stops and network waits ([Live]); null without a Live talk. */
        val live: Live? = null,
    )

    /** The day's Live talks (on the speaker), Google's stops of Obi (on the speaker), network waits and the longest. */
    data class Live(val talks: Int, val speakerTalks: Int, val cutOffs: Int, val speakerCutOffs: Int, val waits: Int, val longestWaitMs: Long)

    data class Work(val name: String, val worstMs: Long, val count: Int, val slow: Int)

    /** [moment] is one of [FEEL_MOMENTS] (the core's FeelMoment names); [place] a tab or screen's name, when the app said. */
    data class Feel(val moment: String, val worstMs: Long, val place: String?, val count: Int, val over: Int)

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
        val work = r.slow.mapNotNull { w -> name(w.name)?.let { w.copy(name = it) } }.take(SLOW_MAX)
        if (work.isNotEmpty()) put("slow", buildJsonArray {
            work.forEach { w ->
                add(buildJsonObject {
                    put("n", w.name); put("ms", w.worstMs.coerceIn(0, MS_MAX))
                    put("c", w.count.coerceIn(0, COUNT_MAX)); put("s", w.slow.coerceIn(0, COUNT_MAX))
                })
            }
        })
        val feel = r.feel.filter { it.moment in FEEL_MOMENTS }.distinctBy { it.moment }
        if (feel.isNotEmpty()) put("feel", buildJsonArray {
            feel.forEach { f ->
                add(buildJsonObject {
                    put("m", f.moment); put("ms", f.worstMs.coerceIn(0, MS_MAX))
                    place(f.place)?.let { put("p", it) }
                    put("c", f.count.coerceIn(0, COUNT_MAX)); put("o", f.over.coerceIn(0, COUNT_MAX))
                })
            }
        })
        r.live?.takeIf { it.talks > 0 }?.let { l ->
            val talks = l.talks.coerceIn(0, COUNT_MAX)
            val cut = l.cutOffs.coerceIn(0, COUNT_MAX)
            put("live", buildJsonObject {
                put("t", talks); put("st", l.speakerTalks.coerceIn(0, talks))
                put("c", cut); put("sc", l.speakerCutOffs.coerceIn(0, cut))
                put("wn", l.waits.coerceIn(0, COUNT_MAX)); put("wl", l.longestWaitMs.coerceIn(0, MS_MAX))
            })
        }
    }

    private fun report(o: JsonObject): Report {
        val sent = o.long("sent")?.takeIf { it > 0 } ?: throw IllegalArgumentException("sent")
        val device = o.str("device")?.let(::clean)?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("device")
        fun ms(k: String): Long? = o.long(k)?.takeIf { it in 0..MS_MAX }
        fun count(k: String): Int = (o.long(k) ?: 0L).coerceIn(0L, COUNT_MAX.toLong()).toInt()
        fun JsonObject.n(k: String): Int = (long(k) ?: 0L).coerceIn(0L, COUNT_MAX.toLong()).toInt()
        val slow = (o["slow"] as? JsonArray).orEmpty().mapNotNull { e ->
            val w = e as? JsonObject ?: return@mapNotNull null
            val n = w.str("n")?.let(::name) ?: return@mapNotNull null
            val t = w.long("ms")?.takeIf { it in 0..MS_MAX } ?: return@mapNotNull null
            Work(n, t, w.n("c"), w.n("s"))
        }.take(SLOW_MAX)
        val feel = (o["feel"] as? JsonArray).orEmpty().mapNotNull { e ->
            val f = e as? JsonObject ?: return@mapNotNull null
            val m = f.str("m")?.takeIf { it in FEEL_MOMENTS } ?: return@mapNotNull null
            val t = f.long("ms")?.takeIf { it in 0..MS_MAX } ?: return@mapNotNull null
            Feel(m, t, f.str("p")?.let(::place), f.n("c"), f.n("o"))
        }.distinctBy { it.moment }
        val live = (o["live"] as? JsonObject)?.let { l ->
            val talks = l.n("t").takeIf { it > 0 } ?: return@let null
            val cut = l.n("c")
            Live(talks, l.n("st").coerceAtMost(talks), cut, l.n("sc").coerceAtMost(cut), l.n("wn"), (l.long("wl") ?: 0L).coerceIn(0L, MS_MAX))
        }
        return Report(device, sent, count("hn"), ms("ha"), ms("hm"), count("an"), ms("aa"), ms("am"), slow, feel, live)
    }

    /** A work name: letters, digits, spaces and · - ' only (the code's own names), at most [NAME_MAX]; null when empty. */
    private fun name(s: String): String? =
        s.filter { it.isLetterOrDigit() || it == ' ' || it == '·' || it == '-' || it == '\'' }.trim().take(NAME_MAX).trim().ifEmpty { null }

    /** A tab or screen's name: letters and spaces only, at most [PLACE_MAX]; null when empty. */
    private fun place(s: String?): String? = s?.filter { it.isLetter() || it == ' ' }?.trim()?.take(PLACE_MAX)?.trim()?.ifEmpty { null }

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
