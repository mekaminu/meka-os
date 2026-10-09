package os.meka.backend.integrations

import os.meka.core.domain.Coord
import os.meka.core.domain.HereRules
import os.meka.core.domain.WeatherCodec
import os.meka.core.wire.HereCodec

/**
 * "Where I am now" on the server (build plan "Places…", item 3): a keyed device sends an approximate point (already
 * rounded to about 1 km; [HereCodec.decodeRequest] refuses anything finer) and gets that point's forecast back, with
 * whether it is away from every place the server already forecasts ([places]: home and work, as the household set
 * them). One Open-Meteo call per request; the point is neither stored nor logged, and nothing is written.
 */
class HereWeather(
    private val source: WeatherProvider,
    /** Home's and work's coordinates for the household (the places it forecasts), so "away" is the server's call. */
    private val places: (householdId: String) -> List<WeatherLocation>,
) {
    fun at(householdId: String, request: HereCodec.Request): HereCodec.Response {
        val point = Coord(request.lat, request.lon)
        // Belt and braces: the codec already refused a finer point; never forward one.
        if (!HereRules.isRounded(point)) return HereCodec.Response(HereCodec.Response.FAILED, reason = "That location isn't rounded")
        val f = try {
            source.forecast(WeatherLocation(HereRules.NEAR_YOU, point.lat, point.lon))
        } catch (e: Exception) {
            return HereCodec.Response(HereCodec.Response.FAILED, reason = "The forecast for where you are isn't available right now")
        }
        val known = runCatching { places(householdId) }.getOrNull().orEmpty().map { Coord(it.latitude, it.longitude) }
        val hours = WeatherCodec.encodeHours(f.hours)
        val days = WeatherCodec.encodeDays(f.days)
        if (hours.length > HereCodec.MAX_HOURS_TEXT || days.length > HereCodec.MAX_DAYS_TEXT) {
            return HereCodec.Response(HereCodec.Response.FAILED, reason = "The forecast for where you are was too long")
        }
        return HereCodec.Response(
            HereCodec.Response.OK,
            hours = hours,
            days = days,
            // Without known places (none resolved) the defaults stand in, so "away" still means something.
            away = HereRules.isAway(point, known.ifEmpty { listOf(HereRules.HOME, HereRules.WORK) }),
        )
    }
}
