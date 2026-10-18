package os.meka.core.domain

/*
 * Travel mode completeness, slice 3 (2026-10-18; non-AI, pure): **the destination's weather in the brief**. While he's
 * away on a trip named after a place Navilife knows ([TripZoneRules.placeOf]: "Barcelona", "Tenerife with the boys"),
 * and the day before it starts, Navilife's server forecasts that place too (Open-Meteo, like home's: only the place's
 * name goes to its geocoder, matched to the place's own time zone, so "Florence" is Italy's, not Stoke's) into one more
 * server-written `context_mode` entity, [WeatherStore.TRIP_ENTITY_ID]. The brief then says "In Barcelona today, 18–24°,
 * clear." in place of home's weather (the pane's weather line reads "Barcelona · 18–24°, clear"), the day before it
 * adds "Tomorrow in Barcelona, 17–22°, light rain from 15:00 — take a coat.", and Ask has the destination's days.
 * Times in those lines are the destination's own clock. Nothing new is stored on the devices; the server writes the
 * forecast only while such a trip is on or starts tomorrow, and clears it after.
 */
object TripWeatherRules {
    /** Ask's destination lines at most (they share [WeatherRules.MAX_ASK_LINES] with home's). */
    const val MAX_ASK_LINES = 4

    /**
     * The place to forecast: the trip he's away on today ([TripRules.awayDays]), else the one he's away on tomorrow (the
     * day before it starts, or the day of an evening flight); null when neither names a place on the list.
     */
    fun wanted(trips: List<TripItem>, today: Long): NamedZone? {
        val away = TripRules.awayDays(trips.filter { it.endDay >= today - 1 })
        return (away[today] ?: away[today + 1])?.let(TripZoneRules::placeOf)
    }

    /** The forecast is for [want] (the server answered for that place and found it). */
    fun fresh(f: WeatherForecast, want: NamedZone?): Boolean =
        want != null && !f.isEmpty && f.found && f.asked?.equals(want.label, ignoreCase = true) == true

    /** What the brief says about the destination today, and the pane's line, or tomorrow's sentence. */
    data class BriefWeather(
        /** "In Barcelona today, 18–24°, clear." in place of home's weather; null unless he's away there today. */
        val today: String?,
        /** The pane's weather line while away: "Barcelona · 18–24°, clear". */
        val paneLine: String?,
        /** "Tomorrow in Barcelona, 17–22°, light rain from 15:00 — take a coat." the day before; null otherwise. */
        val tomorrow: String?,
    ) {
        companion object { val NONE = BriefWeather(null, null, null) }
    }

    /**
     * The brief's destination weather from the trip forecast [f], for [awayToday] / [awayTomorrow] (the trip names from
     * [TripRules.awayDays]); [clocks] gives the destination's clock so its times are local. Nothing when the forecast
     * isn't (yet) for that place or the day isn't in it.
     */
    fun brief(f: WeatherForecast, awayToday: String?, awayTomorrow: String?, today: Long, clocks: TripClocks?): BriefWeather {
        val name = awayToday ?: awayTomorrow ?: return BriefWeather.NONE
        val want = TripZoneRules.placeOf(name) ?: return BriefWeather.NONE
        if (!fresh(f, want)) return BriefWeather.NONE
        val cal = localCal(want, clocks)
        if (awayToday != null) {
            val glance = WeatherRules.dayGlance(f, today, cal) ?: return BriefWeather.NONE
            return BriefWeather("In ${want.label} today, $glance.", "${want.label} · $glance", null)
        }
        val glance = WeatherRules.dayGlance(f, today + 1, cal) ?: return BriefWeather.NONE
        return BriefWeather(null, null, "Tomorrow in ${want.label}, $glance.")
    }

    /**
     * Ask's lines for the destination's days ahead: "Barcelona (local time) Mon 19 Oct: 18–24° clear, up to 10 % chance
     * of rain"; empty unless the forecast is for the trip's place.
     */
    fun askLines(f: WeatherForecast, trips: List<TripItem>, today: Long, clocks: TripClocks?): List<String> {
        val want = wanted(trips, today) ?: return emptyList()
        if (!fresh(f, want)) return emptyList()
        return f.days.filter { it.epochDay >= today }.take(MAX_ASK_LINES).map { d ->
            "${want.label} (local time) ${CivilDate.shortLabel(d.epochDay)}: ${d.minC}–${d.maxC}° ${WeatherRules.words(d.code)}, " +
                "up to ${d.rainChance} % chance of rain"
        }
    }

    private fun localCal(want: NamedZone, clocks: TripClocks?): LocalCalendar =
        clocks?.zone(want.id) ?: clocks?.home ?: LocalCalendar.UTC
}
