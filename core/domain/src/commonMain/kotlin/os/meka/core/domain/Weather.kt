package os.meka.core.domain

import os.meka.core.sync.FieldValue
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
    /** "Biggleswade": the place the forecast is for, as the geocoder names it. */
    const val PLACE = "place"
    /**
     * The place name the server was asked for (Weather place setting: what Meka typed, normalised, from
     * [WeatherPlaceFields.NAME]); absent while it forecasts home by default. Additive.
     */
    const val ASKED = "asked"
    /** False when [ASKED] couldn't be found, so the forecast is still for home; absent or true otherwise. Additive. */
    const val FOUND = "found"
}

/**
 * Where the forecast is for (Weather place setting): one app-written `context_mode` entity with the fixed id
 * [WeatherPlaceStore.ENTITY_ID], a town name Meka types (no GPS, nothing else about him). The server reads it at its next
 * poll, looks the name up with Open-Meteo's geocoder (only the name is sent) and mirrors that place's forecast instead of
 * home's. LWW: the latest change on either device wins.
 */
object WeatherPlaceFields {
    /** "Bedford"; Null (or absent) for home ([WeatherPlaceRules.HOME]). */
    const val NAME = "name"
}

data class WeatherHour(val startMs: Long, val tempC: Int, val code: Int, val rainChance: Int)

data class WeatherDay(val epochDay: Long, val minC: Int, val maxC: Int, val code: Int, val rainChance: Int)

data class WeatherForecast(
    val place: String,
    val hours: List<WeatherHour>,
    val days: List<WeatherDay>,
    /** The place name the server was asked for ([WeatherFields.ASKED]); null for home. */
    val asked: String? = null,
    /** False when [asked] couldn't be found (the forecast is home's). */
    val found: Boolean = true,
) {
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
    /** The place setting (Calendars → Weather): what the field holds and the line under it. */
    val placeChoice: WeatherPlaceView = WeatherPlaceView.HOME,
    /** The work place setting (Calendars → Weather → Work, Places item 2): Canary Wharf unless Meka types another. */
    val workChoice: WeatherPlaceView = WeatherPlaceView.WORK,
) {
    companion object {
        val EMPTY = WeatherView(null, null, null, "")
    }
}

/**
 * The Weather place setting as the apps show it: [name] fills the field ("Biggleswade" for home), [line] says what the
 * forecast is doing, lit in the accent colour ([lit]) when it couldn't follow the name.
 */
data class WeatherPlaceView(val name: String, val line: String, val lit: Boolean = false, val pending: Boolean = false) {
    companion object {
        val HOME = WeatherPlaceView(WeatherPlaceRules.HOME, "Forecast for ${WeatherPlaceRules.HOME} · from Open-Meteo")
        val WORK = WeatherPlaceView(PlacesRules.WORK, PlacesRules.workLine(PlacesRules.WORK))
    }
}

/** Non-AI, pure: the place name Meka types and what the forecast says about it. */
object WeatherPlaceRules {
    /** Home: the server forecasts it when no place is set (its coordinates are fixed, no lookup). */
    const val HOME = "Biggleswade"
    const val MAX_NAME = 60

    /**
     * A typed name as stored: spaces collapsed, trimmed, at most [MAX_NAME]; null for one that can't be a place (blank,
     * no letter, or characters a town name doesn't have).
     */
    fun normalize(input: String): String? {
        val s = input.replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty() || s.length > MAX_NAME || s.none { it.isLetter() }) return null
        if (s.any { !(it.isLetterOrDigit() || it == ' ' || it == '-' || it == '\'' || it == '.' || it == ',' || it == '’') }) return null
        return s
    }

    fun isHome(name: String?): Boolean = name == null || name.equals(HOME, ignoreCase = true)

    /** Whether [input] is something [normalize] would keep (or home); the apps don't save anything else. */
    fun accepts(input: String): Boolean = normalize(input) != null

    /**
     * [wanted] is the stored name (null for home); [f] the mirrored forecast. "Forecast for Bedford · from Open-Meteo";
     * "Finding “St Neots”… the forecast follows within a few minutes" until the server has answered; "Couldn't find
     * “Xyzzy” — showing Biggleswade. Try the nearest town." (lit).
     */
    fun view(wanted: String?, f: WeatherForecast): WeatherPlaceView {
        val shown = f.place.ifEmpty { HOME }
        if (isHome(wanted)) {
            // Back to home: the server forecasts home again at its next poll.
            return if (f.asked == null || f.isEmpty) WeatherPlaceView(HOME, "Forecast for $HOME · from Open-Meteo")
            else WeatherPlaceView(HOME, "Going back to $HOME… the forecast follows within a few minutes", pending = true)
        }
        val name = wanted!!
        return when {
            !f.asked.equals(name, ignoreCase = true) ->
                WeatherPlaceView(name, "Finding “$name”… the forecast follows within a few minutes", pending = true)
            !f.found -> WeatherPlaceView(name, "Couldn't find “$name” — showing $shown. Try the nearest town.", lit = true)
            else -> WeatherPlaceView(name, "Forecast for $shown · from Open-Meteo")
        }
    }
}

/** The place setting, synced (either app can change it). */
class WeatherPlaceStore(private val replica: Replica) {
    /** The stored name, or null for home. */
    fun wanted(): String? = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(WeatherPlaceFields.NAME)?.textOrNull
        ?.let(WeatherPlaceRules::normalize)?.takeUnless(WeatherPlaceRules::isHome)

    /**
     * Sets the place from what Meka typed; blank or home's name goes back to home. Returns false (and writes nothing)
     * for a name that can't be a place. Writes nothing when it's already the place.
     */
    fun set(input: String): Boolean {
        val back = input.isBlank() || WeatherPlaceRules.isHome(input.trim())
        val name = if (back) null else WeatherPlaceRules.normalize(input) ?: return false
        val current = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(WeatherPlaceFields.NAME)
        if (name == null && (current == null || current == FieldValue.Null)) return true
        if (name != null && current?.textOrNull == name) return true
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(WeatherPlaceFields.NAME to (name?.let { FieldValue.Text(it) } ?: FieldValue.Null)))
        return true
    }

    companion object {
        const val ENTITY_ID = "weather_place"
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
    internal fun wetWords(h: WeatherHour) = if (wetCode(h.code)) words(h.code) else "rain"

    internal fun snowy(code: Int) = code in 71..77 || code in 85..86

    /** The hour that holds [ms], if the forecast has it. */
    fun hourAt(f: WeatherForecast, ms: Long): WeatherHour? {
        val h = ms.floorDiv(WeatherCodec.HOUR_MS) * WeatherCodec.HOUR_MS
        return f.hours.firstOrNull { it.startMs == h }
    }

    fun view(
        f: WeatherForecast,
        nowMs: Long,
        cal: LocalCalendar,
        wanted: String? = null,
        /** The work place's forecast and setting, and today's office hours (Places item 2); null when not an office day. */
        work: WeatherForecast = WeatherForecast.EMPTY,
        workWanted: String? = null,
        office: OfficeWindow? = null,
    ): WeatherView {
        val choice = WeatherPlaceRules.view(wanted, f)
        val workChoice = PlacesRules.workView(workWanted, work)
        if (f.isEmpty) return WeatherView.EMPTY.copy(placeChoice = choice, workChoice = workChoice)
        val now = office?.let { PlacesRules.placesLine(f, work, nowMs, it, cal) } ?: nowLine(f, nowMs, cal)
        return WeatherView(
            nowLine = now,
            tomorrowLine = tomorrowLine(f, cal.epochDayOf(nowMs) + 1, cal),
            nowSpoken = now?.replace("°", " degrees"),
            place = f.place,
            placeChoice = choice,
            workChoice = workChoice,
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
        val head = "$label ${d.minC}–${d.maxC}°".trim()
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

    /**
     * A day's line with no label, for a place that already names the day (Weather slice 2): the morning brief under
     * today's date, the shutdown's and the bedside clock's "Tomorrow · Sat 10 Oct". "9–15°, light rain from 15:00 — take a
     * coat"; null when the day isn't in the forecast.
     */
    fun dayGlance(f: WeatherForecast, day: Long, cal: LocalCalendar): String? = dayLine("", f, day, cal)

    /**
     * Today's wet hours still to come as faint bands on the Day ring's track (Weather slice 2): each run of wet hours
     * ([isWet]) that ends after now, merged, clipped to today; [DayBand.current] while it's raining now. Empty when dry.
     */
    fun rainBands(f: WeatherForecast, nowMs: Long, cal: LocalCalendar): List<DayBand> {
        val today = cal.epochDayOf(nowMs)
        val nowMin = cal.minuteOfDay(nowMs)
        val out = mutableListOf<DayBand>()
        var start = -1
        var end = -1
        fun close() {
            if (start >= 0 && end > nowMin) out += DayBand(start, end, current = nowMin in start until end)
            start = -1
        }
        f.hours.sortedBy { it.startMs }.filter { cal.epochDayOf(it.startMs) == today }.forEach { h ->
            val from = cal.minuteOfDay(h.startMs)
            val to = minOf(from + 60, MINUTES_PER_DAY)
            if (!isWet(h)) { close(); return@forEach }
            if (start >= 0 && from == end) end = to else { close(); start = from; end = to }
        }
        close()
        return out
    }

    private const val MINUTES_PER_DAY = 24 * 60

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

    /** Rain from this long before a plan starts counts (the walk or drive there). */
    const val NUDGE_LEAD_MS = 30 * 60_000L
    private const val MAX_PLACE_CHARS = 40

    /**
     * Weather nudges (Weather item 1: "Rain at 17:30 — Training at SG18"): one [NoticeSource.WEATHER] notice for each of
     * today's plans still to come that you go out for — a timed event with a place and no video link, not a fixture
     * (watched, not attended) and not hidden from my day, and every booked session — when an hour from
     * [NUDGE_LEAD_MS] before it starts until it ends is wet ([isWet]). Digest by default (the governor sums them in the
     * next digest while the plan is still ahead), due from the start of the day and stale once the plan starts; keyed
     * by the plan and its start, so a moved plan is told again and a dry forecast simply stops producing it.
     */
    fun notices(
        f: WeatherForecast,
        events: List<CalendarEvent>,
        marks: EventMarks,
        sessions: SessionsView,
        nowMs: Long,
        cal: LocalCalendar,
    ): List<Notice> {
        if (f.hours.isEmpty()) return emptyList()
        val today = cal.epochDayOf(nowMs)
        val dayStart = cal.toEpochMs(today, 0)
        data class Plan(val key: String, val what: String, val place: String?, val startMs: Long, val endMs: Long)
        val plans = marks.visible(events)
            .filter { !it.allDay && !it.isFixture && it.startAtMs > nowMs && cal.epochDayOf(it.startAtMs) == today }
            .filter { !it.location.isNullOrBlank() && it.joinUrl.isNullOrBlank() }
            .map { Plan("event:${it.id}", it.title.trim().ifEmpty { "Your plan" }, it.location, it.startAtMs, it.endAtMs) } +
            sessions.sessions.filter { it.day == today && it.startMs > nowMs }
                .map { s -> Plan("session:${s.habitId}", listOfNotNull(s.title, s.label).joinToString(" · "), null, s.startMs, s.endMs) }
        return plans.distinctBy { it.key to it.startMs }.sortedBy { it.startMs }.mapNotNull { p ->
            val wet = f.hours.sortedBy { it.startMs }.firstOrNull { h ->
                h.startMs + WeatherCodec.HOUR_MS > p.startMs - NUDGE_LEAD_MS && h.startMs < maxOf(p.endMs, p.startMs + 1) && isWet(h)
            } ?: return@mapNotNull null
            val atMs = maxOf(wet.startMs, p.startMs)
            val where = p.place?.let(::shortPlace)?.let { " at $it" }.orEmpty()
            Notice(
                key = "weather:${p.key}:${p.startMs}", source = NoticeSource.WEATHER, tier = NoticeTier.DIGEST,
                title = "${wetWords(wet).replaceFirstChar { it.uppercaseChar() }} at ${clock(atMs, cal)} — ${p.what}$where",
                text = "", atMs = dayStart, target = NoticeTarget.TODAY, expiresAtMs = p.startMs,
            )
        }
    }

    /** A calendar place as a nudge says it: up to its first comma, at most [MAX_PLACE_CHARS] characters. */
    fun shortPlace(location: String): String? {
        val first = location.replace(Regex("[\\r\\n]+"), " ").substringBefore(',').trim()
        if (first.isEmpty()) return null
        return if (first.length <= MAX_PLACE_CHARS) first else first.take(MAX_PLACE_CHARS - 1).trimEnd() + "…"
    }

    internal fun clock(ms: Long, cal: LocalCalendar) = LocalClock.formatMinute(cal.minuteOfDay(ms))
}

/** Reads the mirrored forecast. */
class WeatherStore(private val replica: Replica, private val entityId: String = ENTITY_ID) {
    fun forecast(): WeatherForecast {
        val e = replica.entity(EntityTypes.CONTEXT_MODE, entityId) ?: return WeatherForecast.EMPTY
        return WeatherForecast(
            place = WeatherCodec.place(e[WeatherFields.PLACE]?.textOrNull),
            hours = WeatherCodec.decodeHours(e[WeatherFields.HOURS]?.textOrNull),
            days = WeatherCodec.decodeDays(e[WeatherFields.DAYS]?.textOrNull),
            asked = e[WeatherFields.ASKED]?.textOrNull?.let(WeatherCodec::place)?.ifEmpty { null },
            found = (e[WeatherFields.FOUND] as? FieldValue.Bool)?.value ?: true,
        )
    }

    companion object {
        const val ENTITY_ID = "weather"
        /** The work place's forecast (Places item 2), written by the server the same way; the apps never write it. */
        const val WORK_ENTITY_ID = "weather_work"
    }
}
