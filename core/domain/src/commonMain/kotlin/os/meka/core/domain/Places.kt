package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica

/**
 * Places (build plan "Places, location weather, per-day work hours and trains", item 2; Meka 2026-10-09): home and work
 * come from About you (rule 13, release 2 slice 3: never the code), and either can be overridden in Calendars →
 * Weather. The server forecasts both (Open-Meteo, the same public source; only the place is sent) and
 * mirrors work's into `context_mode/weather_work` ([WeatherStore.WORK_ENTITY_ID]).
 *
 * On an office day (a work day that isn't a work-from-home day), until work ends, Today's weather line and the brief
 * say both places ("Oakfield 9° now · Riverside 14°, light rain from 15:00 — take a coat"), and the Day ring's
 * rain tint follows where Meka will be: home's forecast outside work hours, work's during them.
 */
object WorkPlaceFields {
    /** "Cambridge"; Null (or absent) for About you's work ([PlacesRules.aboutWork]). */
    const val NAME = "name"
}

/** Today's office hours, as instants: from the start of work until its end (an office day only). */
data class OfficeWindow(
    val startMs: Long,
    val endMs: Long,
    /** Live trains, slice 3: the day's door-to-door commute to work ([CommuteRules.toWorkOn]); null when not known. */
    val toWorkMin: Int? = null,
)

/** Non-AI, pure. */
object PlacesRules {
    /** With neither the work place nor About you naming work, there's no work forecast; the field says where to add it. */
    const val NO_WORK_LINE = "Add work in Ask → Settings → About you for office-day weather"

    /** About you's work as the weather follows it (on the device); null with none typed. */
    val aboutWork: String? get() = Household.names.work

    /** What the Work field shows when no other place is set: About you's work, else blank. */
    val defaultWork: String get() = aboutWork ?: ""

    /** Blank, or About you's work (on the device): following About you rather than a place of its own. */
    fun isDefaultWork(name: String?): Boolean =
        name.isNullOrBlank() || aboutWork?.let { name.trim().equals(it, ignoreCase = true) } == true

    /** The work place forecast: the setting ([setting]), else About you's work ([work]); null for neither (none). */
    fun effectiveWork(setting: String?, work: String?): String? =
        setting?.let(WeatherPlaceRules::normalize) ?: work?.let(WeatherPlaceRules::normalize)

    /** The line under the work field when the forecast is for [place]. */
    fun workLine(place: String): String = "Work forecast for $place · on office days"

    /**
     * The work place setting as Calendars shows it: "Work forecast for Riverside · on office days"; "Finding
     * “Cambridge”… the forecast follows within a few minutes" until the server has answered; "Couldn't find “Xyzzy” ·
     * try the nearest town" (lit); with no work at all [NO_WORK_LINE].
     */
    fun workView(wanted: String?, f: WeatherForecast, work: String? = aboutWork): WeatherPlaceView {
        val name = effectiveWork(wanted, work) ?: return WeatherPlaceView.NO_WORK
        return when {
            !f.asked.equals(name, ignoreCase = true) ->
                WeatherPlaceView(name, "Finding “$name”… the forecast follows within a few minutes", pending = true)
            !f.found -> WeatherPlaceView(name, "Couldn't find “$name” · try the nearest town", lit = true)
            else -> WeatherPlaceView(name, workLine(f.place.ifEmpty { name }))
        }
    }

    /**
     * Today's office hours: a work day ([WorkHours.isWorkDay]) that isn't a work-from-home day, with a day shift (a
     * night shift isn't an office day this line knows about). Null otherwise.
     */
    fun officeWindow(hours: WorkHours, day: Long, cal: LocalCalendar): OfficeWindow? {
        if (!hours.isWorkDay(day) || day in hours.homeDays) return null
        val h = hours.hoursOn(day)
        if (h.crossesMidnight || h.startMinute == h.endMinute) return null
        return OfficeWindow(cal.toEpochMs(day, h.startMinute), cal.toEpochMs(day, h.endMinute), CommuteRules.toWorkOn(hours, day))
    }

    /** Work's hours from [nowMs] (or the start of work) until work ends. */
    private fun workHours(work: WeatherForecast, nowMs: Long, office: OfficeWindow): List<WeatherHour> {
        val from = maxOf(nowMs, office.startMs)
        return work.hours.sortedBy { it.startMs }.filter { it.startMs + WeatherCodec.HOUR_MS > from && it.startMs < office.endMs }
    }

    /**
     * Both places on an office day, until work ends: home's temperature now, then work's from now (or from the start of
     * work) and what work's hours do. "Oakfield 9° now · Riverside 14°, light rain from 15:00 — take a coat",
     * "… Riverside 14°, light rain until 11:00 — take a coat", "… Riverside 14°, rain — take a coat" (wet until
     * work ends), "… Riverside 16°, dry". Null when either forecast lacks the hours (Today keeps home's own line).
     */
    fun placesLine(home: WeatherForecast, work: WeatherForecast, nowMs: Long, office: OfficeWindow, cal: LocalCalendar): String? {
        if (nowMs >= office.endMs) return null
        val here = WeatherRules.hourAt(home, nowMs) ?: return null
        val there = workHours(work, nowMs, office)
        val first = there.firstOrNull() ?: return null
        val head = "${home.place.ifEmpty { "Home" }} ${here.tempC}° now · ${work.place.ifEmpty { "work" }} ${first.tempC}°"
        val wet = there.filter(WeatherRules::isWet)
        if (wet.isEmpty()) return "$head, dry"
        val tail = if (wet.any { WeatherRules.snowy(it.code) }) " — wrap up" else " — take a coat"
        if (WeatherRules.isWet(first)) {
            val dry = there.firstOrNull { !WeatherRules.isWet(it) }
            val what = WeatherRules.wetWords(first)
            return if (dry == null) "$head, $what$tail" else "$head, $what until ${WeatherRules.clock(dry.startMs, cal)}$tail"
        }
        val w = wet.first()
        return "$head, ${WeatherRules.wetWords(w)} from ${WeatherRules.clock(w.startMs, cal)}$tail"
    }

    /**
     * The forecast for where Meka will be (the Day ring's rain tint): home's hours, except during today's office hours,
     * which are work's (when work's forecast has them). Home's forecast as it is on a day with no office hours.
     */
    fun whereYouAre(home: WeatherForecast, work: WeatherForecast, office: OfficeWindow?): WeatherForecast {
        if (office == null || work.hours.isEmpty()) return home
        val atWork = work.hours.associateBy { it.startMs }
        return home.copy(hours = home.hours.map { h ->
            if (h.startMs + WeatherCodec.HOUR_MS > office.startMs && h.startMs < office.endMs) atWork[h.startMs] ?: h else h
        })
    }

    /**
     * Work's forecast as Ask MEKA sends it, after home's ("will it rain in Riverside?"): now, today and tomorrow.
     * Empty without a work forecast.
     */
    fun workAskLines(work: WeatherForecast, nowMs: Long, cal: LocalCalendar): List<String> {
        if (work.isEmpty) return emptyList()
        val place = work.place.ifEmpty { "your office" }
        val today = cal.epochDayOf(nowMs)
        return listOfNotNull(
            WeatherRules.nowLine(work, nowMs, cal)?.let { "now at work in $place: $it" },
            WeatherRules.dayLine("", work, today, cal)?.let { "today at work in $place: $it" },
            WeatherRules.dayLine("", work, today + 1, cal)?.let { "tomorrow at work in $place: $it" },
        )
    }
}

/** The work place setting, synced (either app can change it): one app-written `context_mode/work_place` entity. LWW. */
class WorkPlaceStore(private val replica: Replica) {
    /** The stored name, or null for About you's work. */
    fun wanted(): String? = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(WorkPlaceFields.NAME)?.textOrNull
        ?.let(WeatherPlaceRules::normalize)?.takeUnless(PlacesRules::isDefaultWork)

    /**
     * Sets work from what Meka typed; blank or About you's work goes back to following About you. Returns false (and writes
     * nothing) for a name that can't be a place. Writes nothing when it's already the place.
     */
    fun set(input: String): Boolean {
        val back = input.isBlank() || PlacesRules.isDefaultWork(input.trim())
        val name = if (back) null else WeatherPlaceRules.normalize(input) ?: return false
        val current = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(WorkPlaceFields.NAME)
        if (name == null && (current == null || current == FieldValue.Null)) return true
        if (name != null && current?.textOrNull == name) return true
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(WorkPlaceFields.NAME to (name?.let { FieldValue.Text(it) } ?: FieldValue.Null)))
        return true
    }

    companion object {
        const val ENTITY_ID = "work_place"
    }
}
