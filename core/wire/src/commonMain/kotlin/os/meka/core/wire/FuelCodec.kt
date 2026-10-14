package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Cheapest fuel nearby on the wire (Car and parking, slice 6): a keyed device asks `POST /v1/car/fuel` with a point
 * rounded to two decimal places (about 1 km; anything finer is refused, as for "Where I am now") and one fuel code
 * ("E10", "E5", "B7", "SDV"); the server answers with the cheapest forecourts near it from its copy of the government's
 * Fuel Finder data. The point is neither stored nor logged.
 */
object FuelCodec {
    const val PATH = "/v1/car/fuel"
    const val MAX_STATIONS = 10
    private const val MAX_TEXT = 120
    const val MAX_REASON = 200
    val FUELS = setOf("E10", "E5", "B7", "SDV")

    private val json = Json { ignoreUnknownKeys = true }

    data class Request(val lat: Double, val lon: Double, val fuel: String)

    data class Station(
        val name: String,
        val brand: String?,
        val postcode: String?,
        val lat: Double,
        val lon: Double,
        val pence: Double,
        val updatedMs: Long?,
        val distanceM: Double,
    )

    /** [state]: ok (with [stations], maybe none) · off (no Fuel Finder key) · loading (first fetch) · failed ([reason]). */
    data class Response(
        val state: String,
        val stations: List<Station> = emptyList(),
        val reason: String? = null,
    ) {
        companion object {
            const val OK = "ok"
            const val OFF = "off"
            const val LOADING = "loading"
            const val FAILED = "failed"
        }
    }

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("lat", r.lat)
        put("lon", r.lon)
        put("fuel", r.fuel)
    }.toString()

    /** Refused, never rounded here: a point outside the Earth or finer than two decimal places, or an unknown fuel. */
    fun decodeRequest(body: String): Request = wrap("fuel request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val lat = o.num("lat")
        val lon = o.num("lon")
        require(lat in -90.0..90.0 && HereCodec.isRounded(lat)) { "lat" }
        require(lon in -180.0..180.0 && HereCodec.isRounded(lon)) { "lon" }
        val fuel = (o["fuel"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("fuel")
        require(fuel in FUELS) { "fuel" }
        Request(lat, lon, fuel)
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        if (r.state == Response.OK) put("stations", buildJsonArray {
            r.stations.take(MAX_STATIONS).forEach { s ->
                add(buildJsonObject {
                    put("name", s.name.take(MAX_TEXT))
                    s.brand?.let { put("brand", it.take(MAX_TEXT)) }
                    s.postcode?.let { put("postcode", it.take(12)) }
                    put("lat", s.lat)
                    put("lon", s.lon)
                    put("pence", s.pence)
                    s.updatedMs?.let { put("updated", it) }
                    put("dist", s.distanceM)
                })
            }
        })
        r.reason?.let { put("reason", it.take(MAX_REASON)) }
    }.toString()

    /** A station that doesn't read is dropped, never guessed at; at most [MAX_STATIONS] are kept. */
    fun decodeResponse(body: String): Response = wrap("fuel response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val stations = (o["stations"] as? JsonArray).orEmpty().mapNotNull { e ->
            val s = e as? JsonObject ?: return@mapNotNull null
            runCatching {
                Station(
                    name = s.text("name") ?: return@mapNotNull null,
                    brand = s.text("brand"),
                    postcode = s.text("postcode"),
                    lat = s.num("lat"),
                    lon = s.num("lon"),
                    pence = s.num("pence"),
                    updatedMs = (s["updated"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull,
                    distanceM = s.num("dist"),
                )
            }.getOrNull()
        }.take(MAX_STATIONS)
        Response(
            state = (o["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("state"),
            stations = stations,
            reason = (o["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.take(MAX_REASON),
        )
    }

    private fun JsonObject.text(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)

    private fun JsonObject.num(k: String): Double =
        (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { !it.isNaN() && !it.isInfinite() } ?: throw IllegalArgumentException(k)

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
