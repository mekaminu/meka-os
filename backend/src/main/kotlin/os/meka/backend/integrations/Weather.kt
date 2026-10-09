package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import os.meka.core.domain.PlacesRules
import os.meka.core.domain.WeatherCodec
import os.meka.core.domain.WeatherDay
import os.meka.core.domain.WeatherForecast
import os.meka.core.domain.WeatherHour
import java.net.URLEncoder
import java.time.Instant
import java.util.Optional
import java.time.ZoneId

/**
 * A public weather forecast for one place (build plan "Weather and a voice Meka likes", item 1): read-only, no
 * sign-in, no key; the only things sent are the place's coordinates and, when Meka sets a town (the place setting),
 * its name for the lookup. The server mirrors it every 30 minutes into one
 * `context_mode/weather` entity, so the devices have it offline.
 */
interface WeatherProvider {
    val id: String
    /** Shown on the Calendars screen with the other feeds ("Open-Meteo · Biggleswade"). */
    val label: String
    /** Home: forecast when no place is set (Weather place setting), at fixed coordinates, no lookup. */
    val home: WeatherLocation
    /**
     * Work (Places item 2): forecast too, into `context_mode/weather_work`, when no work place is set; null when this
     * provider doesn't forecast work.
     */
    val work: WeatherLocation? get() = null
    fun forecast(at: WeatherLocation = home): WeatherForecast
    /**
     * A town name Meka typed (Weather place setting) as a place to forecast; null when nothing matches. Only the name is
     * sent. Throws when the lookup itself fails (tried again at the next poll).
     */
    fun locate(name: String): WeatherLocation?
}

/** A place the forecast can be for: its name as the geocoder gives it, and where it is. */
data class WeatherLocation(val name: String, val latitude: Double, val longitude: Double)

/**
 * Open-Meteo (https://open-meteo.com, free for non-commercial use, CC BY 4.0) for Biggleswade (home) or the town Meka set. Hourly
 * temperature, WMO weather code and chance of rain, and each day's range, for 7 days in Europe/London time.
 */
class OpenMeteoWeather internal constructor(
    private val fetch: (String) -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    val place: String = "Biggleswade",
    private val latitude: Double = 52.0868,
    private val longitude: Double = -0.2645,
    private val zone: ZoneId = ZoneId.of("Europe/London"),
    /** Canary Wharf, London (Meka 2026-10-09), at fixed coordinates, no lookup. */
    override val work: WeatherLocation? = WeatherLocation(PlacesRules.WORK, 51.5054, -0.0235),
) : WeatherProvider {
    constructor() : this({ url -> GovUkBankHolidays.httpGet(url) })

    override val id = "weather"
    override val label: String get() = "Open-Meteo · $place"
    override val home = WeatherLocation(place, latitude, longitude)

    val url: String get() = url(home)

    fun url(at: WeatherLocation): String = "https://api.open-meteo.com/v1/forecast?latitude=${at.latitude}&longitude=${at.longitude}" +
        "&hourly=temperature_2m,weather_code,precipitation_probability" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
        "&timezone=Europe%2FLondon&timeformat=unixtime&forecast_days=7"

    override fun forecast(at: WeatherLocation): WeatherForecast = parse(fetch(url(at))).copy(place = at.name)

    /** Names already looked up (a place doesn't move): found or not, so a name is sent once per server start. */
    private val located = HashMap<String, Optional<WeatherLocation>>()

    fun geocodeUrl(name: String): String = "https://geocoding-api.open-meteo.com/v1/search?name=" +
        URLEncoder.encode(name, Charsets.UTF_8) + "&count=10&language=en&format=json"

    /**
     * Open-Meteo's geocoder (same provider, no key). Of its matches, the first in Great Britain (home: "Bedford" is
     * Bedfordshire's, not Texas's), else its first.
     */
    override fun locate(name: String): WeatherLocation? {
        val key = name.trim().lowercase()
        synchronized(located) { located[key] }?.let { return it.orElse(null) }
        val found = parseLocation(fetch(geocodeUrl(name.trim())))
        synchronized(located) {
            // Kept small: a few names are ever typed; past [MAX_LOOKUPS] it starts again.
            if (located.size >= MAX_LOOKUPS) located.clear()
            located[key] = Optional.ofNullable(found)
        }
        return found
    }

    internal fun parseLocation(body: String): WeatherLocation? {
        require(body.length <= MAX_BYTES) { "lookup too large" }
        val o = Json.parseToJsonElement(body).jsonObject
        val results = (o["results"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        fun str(r: JsonObject, k: String) = (r[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun num(r: JsonObject, k: String) = (r[k] as? JsonPrimitive)?.doubleOrNull
        val usable = results.filter { r ->
            val lat = num(r, "latitude"); val lon = num(r, "longitude")
            !str(r, "name").isNullOrBlank() && lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0
        }
        val r = usable.firstOrNull { str(it, "country_code").equals("GB", ignoreCase = true) } ?: usable.firstOrNull() ?: return null
        return WeatherLocation(WeatherCodec.place(str(r, "name")), num(r, "latitude")!!, num(r, "longitude")!!)
    }

    /**
     * Hours from today's local midnight ([WeatherCodec.MAX_HOURS] of them), days from today. An hour or day with a
     * missing reading is skipped; no hours at all is an error (never "no weather").
     */
    internal fun parse(body: String): WeatherForecast {
        require(body.length <= MAX_BYTES) { "forecast too large" }
        val o = Json.parseToJsonElement(body).jsonObject
        val hourly = o["hourly"] as? JsonObject ?: error("no hourly forecast")
        val daily = o["daily"] as? JsonObject
        val midnight = Instant.ofEpochMilli(nowMs()).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val times = longs(hourly["time"])
        val temps = doubles(hourly["temperature_2m"])
        val codes = doubles(hourly["weather_code"])
        val rain = doubles(hourly["precipitation_probability"])
        val end = midnight + WeatherCodec.MAX_HOURS * WeatherCodec.HOUR_MS
        // One hour after another (the devices store a run): a missing reading borrows its neighbour's.
        val hours = times.indices.mapNotNull { i ->
            val t = times[i]?.times(1000) ?: return@mapNotNull null
            if (t < midnight || t >= end) return@mapNotNull null
            val c = temps.getOrNull(i) ?: temps.getOrNull(i - 1) ?: temps.getOrNull(i + 1) ?: return@mapNotNull null
            WeatherHour(t, WeatherCodec.temp(c), codes.getOrNull(i)?.toInt() ?: 3, WeatherCodec.chance(rain.getOrNull(i) ?: 0.0))
        }
        check(hours.isNotEmpty()) { "empty forecast" }
        val days = daily?.let { d ->
            val dt = longs(d["time"])
            val code = doubles(d["weather_code"])
            val hi = doubles(d["temperature_2m_max"])
            val lo = doubles(d["temperature_2m_min"])
            val p = doubles(d["precipitation_probability_max"])
            dt.indices.mapNotNull { i ->
                val t = dt[i] ?: return@mapNotNull null
                val max = hi.getOrNull(i) ?: return@mapNotNull null
                val min = lo.getOrNull(i) ?: return@mapNotNull null
                val day = Instant.ofEpochSecond(t).atZone(zone).toLocalDate().toEpochDay()
                WeatherDay(day, WeatherCodec.temp(min), WeatherCodec.temp(max), code.getOrNull(i)?.toInt() ?: 3, WeatherCodec.chance(p.getOrNull(i) ?: 0.0))
            }.filter { it.epochDay >= Instant.ofEpochMilli(midnight).atZone(zone).toLocalDate().toEpochDay() }.take(WeatherCodec.MAX_DAYS)
        }.orEmpty()
        return WeatherForecast(place, hours, days)
    }

    private fun longs(e: Any?): List<Long?> = (e as? JsonArray)?.map { (it as? JsonPrimitive)?.longOrNull }.orEmpty()
    private fun doubles(e: Any?): List<Double?> = (e as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull }.orEmpty()

    companion object {
        private const val MAX_BYTES = 200_000
        private const val MAX_LOOKUPS = 32
    }
}
