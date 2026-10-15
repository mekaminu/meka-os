package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import os.meka.core.domain.LineState
import os.meka.core.domain.RouteRules

/**
 * The status of the train lines on the household's route (About you's Train route, rule 13 release 2 slice 6) (build plan "Places, location weather, per-day work hours and trains",
 * item 4): read-only, no sign-in, no key; the only thing sent is the lines' names. The server mirrors it into one
 * `context_mode/line_status` entity every few minutes in the day, so the devices have it at their next sync.
 */
interface LineStatusProvider {
    val id: String
    /** Shown on the Calendars screen with the other feeds. */
    val label: String
    /**
     * Each of [lines]' (TfL ids, from the household's route) worst status in force now. Throws when the source can't
     * be read (tried again at the next poll).
     */
    fun statuses(lines: List<String>): List<LineState>
    /**
     * The commute counts everywhere, slice 7: each line's worst status planned to be in force at some point between
     * [fromMs] and [toMs] (the next morning's commute). Throws when it can't be read; empty when the source can't say.
     */
    fun planned(lines: List<String>, fromMs: Long, toMs: Long): List<LineState> = emptyList()
}

/**
 * TfL's unified API (https://api.tfl.gov.uk, open data, free; anonymous use is allowed at a low rate, and this asks
 * once every few minutes): `Line/{ids}/Status` for the route's lines (TfL ids made from About you's line names by
 * [RouteRules.lineId]), which covers the Underground, the Elizabeth line, the Overground and the National Rail
 * operators TfL reports on. Only status codes are kept, never TfL's words.
 */
class TflLineStatus internal constructor(
    private val fetch: (String) -> String,
) : LineStatusProvider {
    constructor() : this({ url -> GovUkBankHolidays.httpGet(url) })

    override val id = "lines"
    override val label: String get() = "TfL · the lines on your train route"

    fun url(lines: List<String>): String = "https://api.tfl.gov.uk/Line/${ids(lines).joinToString(",")}/Status"

    override fun statuses(lines: List<String>): List<LineState> = parse(fetch(url(lines)), lines)

    /** `Line/{ids}/Status/{from}/to/{to}`: the statuses TfL has planned for that window (UTC times, to the minute). */
    fun plannedUrl(lines: List<String>, fromMs: Long, toMs: Long): String =
        "https://api.tfl.gov.uk/Line/${ids(lines).joinToString(",")}/Status/${isoMinute(fromMs)}/to/${isoMinute(toMs)}"

    override fun planned(lines: List<String>, fromMs: Long, toMs: Long): List<LineState> =
        parse(fetch(plannedUrl(lines, fromMs, toMs)), lines, window = fromMs to toMs)

    /** Only well-formed ids go into the URL (they came from a household's typed route). */
    private fun ids(lines: List<String>): List<String> {
        val ok = lines.filter { ID.matches(it) }.distinct().take(MAX_LINES)
        require(ok.isNotEmpty()) { "no lines to ask about" }
        return ok
    }

    /**
     * One entry per line asked about that TfL answered for, with the worst status in force now (a status whose every
     * validity period says it isn't now — planned works later — is left out). Nothing usable is an error.
     */
    internal fun parse(body: String, lines: List<String>, window: Pair<Long, Long>? = null): List<LineState> {
        require(body.length <= MAX_BYTES) { "status too large" }
        val arr = Json.parseToJsonElement(body) as? JsonArray ?: error("not a list of lines")
        val out = arr.mapNotNull { it as? JsonObject }.mapNotNull { line ->
            val id = (line["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            if (id !in lines) return@mapNotNull null
            val statuses = (line["lineStatuses"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val now = statuses.filter { s ->
                val periods = (s["validityPeriods"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                if (window == null) periods.isEmpty() || periods.any { (it["isNow"] as? JsonPrimitive)?.booleanOrNull != false }
                // Planned: a status counts when one of its periods overlaps the window (an unreadable period counts).
                else periods.isEmpty() || periods.any { p -> overlaps(p, window) }
            }.mapNotNull { (it["statusSeverity"] as? JsonPrimitive)?.intOrNull }
            // A line with nothing in force now is running as normal.
            LineState(id, RouteRules.worst(now) ?: GOOD_SERVICE)
        }.distinctBy { it.id }
        check(out.isNotEmpty()) { "no line status" }
        return out
    }

    private fun overlaps(p: JsonObject, window: Pair<Long, Long>): Boolean {
        val from = instant(p["fromDate"]) ?: return true
        val to = instant(p["toDate"]) ?: return true
        return from < window.second && to > window.first
    }

    private fun instant(e: Any?): Long? {
        val t = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return runCatching { java.time.Instant.parse(t).toEpochMilli() }.getOrNull()
            // TfL sometimes leaves the zone off; its times are UTC.
            ?: runCatching { java.time.LocalDateTime.parse(t).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    private fun isoMinute(ms: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:00'Z'").withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.ofEpochMilli(ms))

    companion object {
        private const val MAX_BYTES = 300_000
        private const val MAX_LINES = 6
        private val ID = Regex("[a-z0-9-]{1,40}")
        const val GOOD_SERVICE = 10
    }
}
