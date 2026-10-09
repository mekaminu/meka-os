package os.meka.core.domain

import os.meka.core.sync.Replica
import kotlin.math.roundToInt

/**
 * Weather (build plan "Weather and a voice Meka likes", item 1; Meka 2026-10-08: Talk said "I only know what's in the
 * app" when asked about tomorrow's weather). The server reads Open-Meteo's public forecast for one fixed place
 * (Biggleswade, home; no key, no account, nothing about Meka sent) every 30 minutes and mirrors it into one
 * server-written `context_mode` entity with the fixed id [WeatherStore.ENTITY_ID] (ADR-008 addendum), only when it
 * changed, so every device has it offline. The apps never write it.
 *
 * Numbers only: the lines the apps show and the lines Ask sends are written here from them, so nothing in the forecast
 * is text someone else wrote.
 */
object WeatherFields {
    /** "490000|14,61,70;15,3,10;…": the first hour (Unix hours) and then one hour after another: °C, WMO code, rain %. */
    const val HOURS = "hours"
    /** "2026-10-09=9,15,61,70;…": local day = min °C, max °C, WMO code, rain % (the day's highest chance). */
    const val DAYS = "days"
    /** "Biggleswade". */
    const val PLACE = "place"
}

data class WeatherHour(val startMs: Long, val tempC: Int, val code: Int, val rainChance: Int)

data class WeatherDay(val epochDay: Long, val minC: Int, val maxC: Int, val code: Int, val rainChance: Int)

data class WeatherForecast(val place: String, val hours: List<WeatherHour>, val days: List<WeatherDay>) {
    val isEmpty: Boolean get() = hours.isEmpty() && days.isEmpty()

    companion object {
        val EMPTY = WeatherForecast("", emptyList(), emptyList())
    }
}

/** The compact text the server writes and the devices read. Unreadable parts are skipped (it came from the network). */
object WeatherCodec {
    const val HOUR_MS = 3_600_000L
    /** Hours kept: today from local midnight, tomorrow and a little of the day after. */
    const val MAX_HOURS = 72
    const val MAX_DAYS = 7
    private const val MAX_PLACE = 60

    fun encodeHours(hours: List<WeatherHour>): String {
        val sorted = hours.sortedBy { it.startMs }.distinctBy { it.startMs / HOUR_MS }.take(MAX_HOURS)
        if (sorted.isEmpty()) return ""
        val first = sorted.first().startMs.floorDiv(HOUR_MS)
        // Consecutive hours only: a gap ends the run (Open-Meteo gives every hour).
        val run = sorted.withIndex().takeWhile { (i, h) -> h.startMs.floorDiv(HOUR_MS) == first + i }.map { it.value }
        return "$first|" + run.joinToString(";") { "${it.tempC},${it.code},${it.rainChance}" }
    }

    fun decodeHours(s: String?): List<WeatherHour> {
        if (s.isNullOrBlank()) return emptyList()
        val bar = s.indexOf('|')
        if (bar <= 0) return emptyList()
        val first = s.substring(0, bar).toLongOrNull() ?: return emptyList()
        return s.substring(bar + 1).split(';').take(MAX_HOURS).mapIndexedNotNull { i, part ->
            val n = part.split(',').map { it.trim().toIntOrNull() }
            if (n.size != 3 || n.any { it == null }) return@mapIndexedNotNull null
            WeatherHour((first + i) * HOUR_MS, clampTemp(n[0]!!), n[1]!!.coerceIn(0, 99), n[2]!!.coerceIn(0, 100))
        }
    }

    fun encodeDays(days: List<WeatherDay>): String =
        days.distinctBy { it.epochDay }.sortedBy { it.epochDay }.take(MAX_DAYS).joinToString(";") { d ->
            "${iso(d.epochDay)}=${d.minC},${d.maxC},${d.code},${d.rainChance}"
        }

    fun decodeDays(s: String?): List<WeatherDay> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq < 0) return@mapNotNull null
            val day = BankHolidays.parseDate(part.substring(0, eq)) ?: return@mapNotNull null
            val n = part.substring(eq + 1).split(',').map { it.trim().toIntOrNull() }
            if (n.size != 4 || n.any { it == null }) return@mapNotNull null
            val lo = clampTemp(n[0]!!)
            val hi = clampTemp(n[1]!!)
            WeatherDay(day, minOf(lo, hi), maxOf(lo, hi), n[2]!!.coerceIn(0, 99), n[3]!!.coerceIn(0, 100))
        }.distinctBy { it.epochDay }.sortedBy { it.epochDay }.take(MAX_DAYS)
    }

    fun place(s: String?): String = s?.replace(Regex("[\\r\\n]"), " ")?.trim()?.take(MAX_PLACE).orEmpty()

    /** A reading as stored: whole degrees, rain chance to the nearest 10 % (so a 1 % wobble writes nothing). */
    fun temp(c: Double): Int = clampTemp(c.roundToInt())
    fun chance(p: Double): Int = ((p / 10.0).roundToInt() * 10).coerceIn(0, 100)

    private fun clampTemp(c: Int) = c.coerceIn(-60, 60)

    private fun iso(epochDay: Long): String {
        val d = CivilDate.fromEpochDay(epochDay)
        return "${d.year}-${d.month.toString().padStart(2, '0')}-${d.day.toString().padStart(2, '0')}"
    }
}

/** What the apps show: Today's header line, tomorrow's line (the brief, the bedside clock) and the place. */
data class WeatherView(
    /** "14° · light rain from 16:00"; null when there's no forecast for now. */
    val nowLine: String?,
    /** "Tomorrow 9–15°, light rain from 15:00 — take a coat"; null when tomorrow isn't known. */
    val tomorrowLine: String?,
    /** For screen readers: "14 degrees, light rain from 16:00". */
    val nowSpoken: String?,
    val place: String,
) {
    companion object {
        val EMPTY = WeatherView(null, null, null, "")
    }
}

/** Non-AI, pure: what the numbers say, in a few calm words. */
object WeatherRules {
    /** A wet hour from this chance up, whatever its code says; and a wet code counts from [WET_CODE_CHANCE]. */
    const val WET_CHANCE = 60
    const val WET_CODE_CHANCE = 40
    /** Tomorrow's line looks at the waking day only. */
    const val DAY_FROM_MIN = 7 * 60
    const val DAY_TO_MIN = 22 * 60

    /** WMO weather interpretation codes (Open-Meteo's `weather_code`) in plain words. */
    fun words(code: Int): String = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "cloudy"
        45, 48 -> "fog"
        51, 53 -> "drizzle"
        55 -> "heavy drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80 -> "showers"
        81 -> "heavy showers"
        82 -> "violent showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorms"
        96, 99 -> "thunderstorms with hail"
        else -> "cloudy"
    }

    /** Drizzle, rain, snow, showers and storms (not fog or cloud). */
    fun wetCode(code: Int): Boolean = code in 51..99

    fun isWet(h: WeatherHour): Boolean = h.rainChance >= WET_CHANCE || (wetCode(h.code) && h.rainChance >= WET_CODE_CHANCE)

    /** What falls in a wet hour: its own words, or "rain" when only the chance says so. */
    private fun wetWords(h: WeatherHour) = if (wetCode(h.code)) words(h.code) else "rain"

    private fun snowy(code: Int) = code in 71..77 || code in 85..86

    /** The hour that holds [ms], if the forecast has it. */
    fun hourAt(f: WeatherForecast, ms: Long): WeatherHour? {
        val h = ms.floorDiv(WeatherCodec.HOUR_MS) * WeatherCodec.HOUR_MS
        return f.hours.firstOrNull { it.startMs == h }
    }

    fun view(f: WeatherForecast, nowMs: Long, cal: LocalCalendar): WeatherView {
        if (f.isEmpty) return WeatherView.EMPTY
        val now = nowLine(f, nowMs, cal)
        return WeatherView(
            nowLine = now,
            tomorrowLine = tomorrowLine(f, cal.epochDayOf(nowMs) + 1, cal),
            nowSpoken = now?.replace("°", " degrees"),
            place = f.place,
        )
    }

    /**
     * Today's header: the temperature now, then what the rest of today does. "14° · light rain from 16:00",
     * "12° · light rain until 15:00", "12° · light rain" (all the rest of today), "16° · partly cloudy, dry today".
     */
    fun nowLine(f: WeatherForecast, nowMs: Long, cal: LocalCalendar): String? {
        val h = hourAt(f, nowMs) ?: return null
        val today = cal.epochDayOf(nowMs)
        val rest = f.hours.filter { it.startMs > h.startMs && cal.epochDayOf(it.startMs) == today }
        val head = "${h.tempC}°"
        if (isWet(h)) {
            val dry = rest.firstOrNull { !isWet(it) }
            return if (dry == null) "$head · ${wetWords(h)}" else "$head · ${wetWords(h)} until ${clock(dry.startMs, cal)}"
        }
        val wet = rest.firstOrNull(::isWet)
        return if (wet != null) "$head · ${wetWords(wet)} from ${clock(wet.startMs, cal)}"
        else "$head · ${words(h.code)}${if (rest.isNotEmpty()) ", dry today" else ""}"
    }

    /**
     * Tomorrow at a glance: "Tomorrow 9–15°, light rain from 15:00 — take a coat", "Tomorrow 9–15°, rain all day — take
     * a coat", "Tomorrow 2–6°, light snow from 08:00 — wrap up", "Tomorrow 11–18°, partly cloudy".
     */
    fun tomorrowLine(f: WeatherForecast, day: Long, cal: LocalCalendar): String? = dayLine("Tomorrow", f, day, cal)

    /** [label] then the day's range and what its waking hours do; the hours when known, else the day's own summary. */
    fun dayLine(label: String, f: WeatherForecast, day: Long, cal: LocalCalendar): String? {
        val d = f.days.firstOrNull { it.epochDay == day } ?: return null
        val head = "$label ${d.minC}–${d.maxC}°"
        val waking = f.hours.filter { cal.epochDayOf(it.startMs) == day && cal.minuteOfDay(it.startMs) in DAY_FROM_MIN until DAY_TO_MIN }
        if (waking.isNotEmpty()) {
            val wet = waking.filter(::isWet)
            if (wet.isEmpty()) return "$head, ${if (wetCode(d.code)) "mostly dry" else words(d.code)}"
            val first = wet.first()
            val what = wetWords(first)
            val tail = if (wet.any { snowy(it.code) }) " — wrap up" else " — take a coat"
            val allDay = wet.size == waking.size
            return if (allDay) "$head, $what all day$tail" else "$head, $what from ${clock(first.startMs, cal)}$tail"
        }
        return if (d.rainChance >= WET_CHANCE || (wetCode(d.code) && d.rainChance >= WET_CODE_CHANCE)) {
            "$head, ${if (wetCode(d.code)) words(d.code) else "rain"} likely${if (snowy(d.code)) " — wrap up" else " — take a coat"}"
        } else "$head, ${if (wetCode(d.code)) "mostly dry" else words(d.code)}"
    }

    /** One hour as Ask hears it and a row would say it: "16:00 14° light rain, 70 % chance of rain". */
    fun hourLine(h: WeatherHour, cal: LocalCalendar): String =
        "${clock(h.startMs, cal)} ${h.tempC}° ${words(h.code)}, ${h.rainChance} % chance of rain"

    /**
     * The forecast as Ask MEKA sends it (so "what's the weather tomorrow?" and "will it rain at training?" have an
     * answer): where, now, each day ahead, then every third hour from now through tomorrow. At most [MAX_ASK_LINES].
     */
    fun askLines(f: WeatherForecast, nowMs: Long, cal: LocalCalendar): List<String> {
        if (f.isEmpty) return emptyList()
        val out = mutableListOf<String>()
        val today = cal.epochDayOf(nowMs)
        nowLine(f, nowMs, cal)?.let { out += "now in ${f.place.ifEmpty { "Biggleswade" }}: $it" }
        f.days.filter { it.epochDay >= today }.forEach { d ->
            val label = when (d.epochDay) {
                today -> "today (${CivilDate.shortLabel(d.epochDay)})"
                today + 1 -> "tomorrow (${CivilDate.shortLabel(d.epochDay)})"
                else -> CivilDate.shortLabel(d.epochDay)
            }
            out += "$label: ${d.minC}–${d.maxC}° ${words(d.code)}, up to ${d.rainChance} % chance of rain"
        }
        val from = nowMs.floorDiv(WeatherCodec.HOUR_MS) * WeatherCodec.HOUR_MS
        f.hours.filter { it.startMs > from && cal.epochDayOf(it.startMs) <= today + 1 }
            .filter { cal.minuteOfDay(it.startMs) / 60 % 3 == 0 }
            .forEach { h ->
                val day = if (cal.epochDayOf(h.startMs) == today) "today" else "tomorrow"
                out += "$day ${hourLine(h, cal)}"
            }
        return out.take(MAX_ASK_LINES)
    }

    const val MAX_ASK_LINES = 24

    private fun clock(ms: Long, cal: LocalCalendar) = LocalClock.formatMinute(cal.minuteOfDay(ms))
}

/** Reads the mirrored forecast. */
class WeatherStore(private val replica: Replica) {
    fun forecast(): WeatherForecast {
        val e = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID) ?: return WeatherForecast.EMPTY
        return WeatherForecast(
            place = WeatherCodec.place(e[WeatherFields.PLACE]?.textOrNull),
            hours = WeatherCodec.decodeHours(e[WeatherFields.HOURS]?.textOrNull),
            days = WeatherCodec.decodeDays(e[WeatherFields.DAYS]?.textOrNull),
        )
    }

    companion object {
        const val ENTITY_ID = "weather"
    }
}
