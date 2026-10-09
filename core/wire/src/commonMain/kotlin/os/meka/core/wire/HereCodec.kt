package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * "Where I am now" on the wire (build plan "Places…", item 3): a keyed device asks the server for the forecast at an
 * approximate point (`POST /v1/weather/here`). The point is rounded to two decimal places (about 1 km) on the device;
 * the server refuses anything finer rather than rounding it itself, so a precise location never reaches it. The
 * server asks Open-Meteo for that point and answers; nothing is stored or logged.
 *
 * The forecast comes back in the compact text the mirrored weather uses (`hours` / `days`, the domain's WeatherCodec),
 * so the device reads it with the same code; [Response.away] says whether the point is away from every place the
 * server already forecasts (home and work), which only the server knows (it has their coordinates).
 */
object HereCodec {
    /** Longest compact text accepted (72 hours of "−12,61,100;" and 7 days, with room). */
    const val MAX_HOURS_TEXT = 1_200
    const val MAX_DAYS_TEXT = 400
    const val MAX_REASON = 200

    private val json = Json { ignoreUnknownKeys = true }

    data class Request(val lat: Double, val lon: Double)

    /** [state]: ok (with [hours], [days] and [away]) · off (no weather on this server) · failed ([reason]). */
    data class Response(
        val state: String,
        val hours: String? = null,
        val days: String? = null,
        val away: Boolean = false,
        val reason: String? = null,
    ) {
        companion object {
            const val OK = "ok"
            const val OFF = "off"
            const val FAILED = "failed"
        }
    }

    /** Two decimal places at most (the device rounds; see the domain's HereRules.round). */
    fun isRounded(v: Double): Boolean {
        if (v.isNaN() || v.isInfinite()) return false
        val scaled = v * 100.0
        return abs(scaled - scaled.roundToLong()) < 1e-6
    }

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("lat", r.lat)
        put("lon", r.lon)
    }.toString()

    /** Refused, never rounded here: a point outside the Earth or finer than two decimal places. */
    fun decodeRequest(body: String): Request = wrap("here request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val lat = o.num("lat")
        val lon = o.num("lon")
        require(lat in -90.0..90.0 && isRounded(lat)) { "lat" }
        require(lon in -180.0..180.0 && isRounded(lon)) { "lon" }
        Request(lat, lon)
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        r.hours?.let { put("hours", it) }
        r.days?.let { put("days", it) }
        if (r.state == Response.OK) put("away", r.away)
        r.reason?.let { put("reason", it) }
    }.toString()

    /** Oversized forecast text is dropped (the device then has no forecast for here), never cut. */
    fun decodeResponse(body: String): Response = wrap("here response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        Response(
            state = o.str("state"),
            hours = o.optStr("hours", MAX_HOURS_TEXT),
            days = o.optStr("days", MAX_DAYS_TEXT),
            away = (o["away"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false,
            reason = o.optStr("reason", MAX_REASON),
        )
    }

    private fun JsonObject.num(k: String): Double =
        (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: throw IllegalArgumentException(k)

    private fun JsonObject.str(k: String): String =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException(k)

    private fun JsonObject.optStr(k: String, max: Int): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.length <= max }

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
