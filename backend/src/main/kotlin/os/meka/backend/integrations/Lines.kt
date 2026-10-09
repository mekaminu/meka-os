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
 * The status of the train lines on Meka's route (build plan "Places, location weather, per-day work hours and trains",
 * item 4): read-only, no sign-in, no key; the only thing sent is the lines' names. The server mirrors it into one
 * `context_mode/line_status` entity every few minutes in the day, so the devices have it at their next sync.
 */
interface LineStatusProvider {
    val id: String
    /** Shown on the Calendars screen with the other feeds ("TfL · Thameslink, Great Northern, Elizabeth line"). */
    val label: String
    /** Each line's worst status in force now. Throws when the source can't be read (tried again at the next poll). */
    fun statuses(): List<LineState>
}

/**
 * TfL's unified API (https://api.tfl.gov.uk, open data, free; anonymous use is allowed at a low rate, and this asks
 * once every few minutes): `Line/{ids}/Status` for the route's lines ([RouteRules.LINES]), which covers the Elizabeth
 * line and the National Rail operators Thameslink and Great Northern. Only status codes are kept, never TfL's words.
 */
class TflLineStatus internal constructor(
    private val fetch: (String) -> String,
    private val lines: List<String> = RouteRules.LINES,
) : LineStatusProvider {
    constructor() : this({ url -> GovUkBankHolidays.httpGet(url) })

    override val id = "lines"
    override val label: String get() = "TfL · " + lines.joinToString(", ") { RouteRules.name(it) }

    val url: String get() = "https://api.tfl.gov.uk/Line/${lines.joinToString(",")}/Status"

    override fun statuses(): List<LineState> = parse(fetch(url))

    /**
     * One entry per line asked about that TfL answered for, with the worst status in force now (a status whose every
     * validity period says it isn't now — planned works later — is left out). Nothing usable is an error.
     */
    internal fun parse(body: String): List<LineState> {
        require(body.length <= MAX_BYTES) { "status too large" }
        val arr = Json.parseToJsonElement(body) as? JsonArray ?: error("not a list of lines")
        val out = arr.mapNotNull { it as? JsonObject }.mapNotNull { line ->
            val id = (line["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            if (id !in lines) return@mapNotNull null
            val statuses = (line["lineStatuses"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val now = statuses.filter { s ->
                val periods = (s["validityPeriods"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                periods.isEmpty() || periods.any { (it["isNow"] as? JsonPrimitive)?.booleanOrNull != false }
            }.mapNotNull { (it["statusSeverity"] as? JsonPrimitive)?.intOrNull }
            // A line with nothing in force now is running as normal.
            LineState(id, RouteRules.worst(now) ?: GOOD_SERVICE)
        }.distinctBy { it.id }
        check(out.isNotEmpty()) { "no line status" }
        return out
    }

    companion object {
        private const val MAX_BYTES = 300_000
        const val GOOD_SERVICE = 10
    }
}
