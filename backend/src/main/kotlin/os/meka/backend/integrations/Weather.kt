package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import os.meka.core.domain.WeatherCodec
import os.meka.core.domain.WeatherDay
import os.meka.core.domain.WeatherForecast
import os.meka.core.domain.WeatherHour
import java.time.Instant
import java.time.ZoneId

/**
 * A public weather forecast for one fixed place (build plan "Weather and a voice Meka likes", item 1): read-only, no
 * sign-in, no key; the only thing sent is the place's coordinates. The server mirrors it every 30 minutes into one
 * `context_mode/weather` entity, so the devices have it offline.
 */
interface WeatherProvider {
    val id: String
    /** Shown on the Calendars screen with the other feeds ("Open-Meteo · Biggleswade"). */
    val label: String
    fun forecast(): WeatherForecast
}

/**
 * Open-Meteo (https://open-meteo.com, free for non-commercial use, CC BY 4.0) for Biggleswade, home. Hourly
 * temperature, WMO weather code and chance of rain, and each day's range, for 7 days in Europe/London time.
 */
class OpenMeteoWeather internal constructor(
    private val fetch: (String) -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    val place: String = "Biggleswade",
    private val latitude: Double = 52.0868,
    private val longitude: Double = -0.2645,
    private val zone: ZoneId = ZoneId.of("Europe/London"),
) : WeatherProvider {
    constructor() : this({ url -> GovUkBankHolidays.httpGet(url) })

    override val id = "weather"
    override val label: String get() = "Open-Meteo · $place"

    val url: String get() = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
        "&hourly=temperature_2m,weather_code,precipitation_probability" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
        "&timezone=Europe%2FLondon&timeformat=unixtime&forecast_days=7"

    override fun forecast(): WeatherForecast = parse(fetch(url))

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
    }
}
