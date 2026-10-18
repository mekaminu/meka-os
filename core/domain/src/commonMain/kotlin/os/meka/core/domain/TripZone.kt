package os.meka.core.domain

/*
 * Travel mode completeness, slice 2 (2026-10-18; non-AI, pure): **bookings in local time with UK time beside**. A trip
 * whose name is a place on the list below ("Barcelona", "Lisbon weekend", "Tenerife with the boys") knows the
 * destination's clock. Booking times are as typed, which is the time on the ticket: the way there (the first timed
 * flight or train on or before the first day) leaves from home, so it is UK time already; everything else happens at
 * the destination, so where the clocks differ its line adds UK time: "18:20 · Flight BA479 · ref XYZ789 · 17:20 UK
 * time" (with the day when it's a different day in the UK). The trip's row says how far apart the clocks are ("1 h
 * ahead of UK time"), and Ask's trip lines carry both. A place not on the list, or one on UK time, adds nothing.
 * Nothing stored; the zone comes from the trip's name each time.
 */

/** The UK's clock and a way to get a destination's clock by IANA id (null when the device doesn't know the zone). */
class TripClocks(val home: LocalCalendar, private val zoneOf: (String) -> LocalCalendar?) {
    /** The destination's clock and its label ("Barcelona"), or null. */
    fun local(t: TripItem): TheirZone? = TripZoneRules.placeOf(t.name)?.let { z -> zoneOf(z.id)?.let { TheirZone(it, z.label) } }
}

object TripZoneRules {
    const val HOME_LABEL = "UK time"

    /** Holiday places beyond the meeting list (cities and islands first, then countries with one clock). */
    private val EXTRA = listOf(
        "Lisbon" to "Europe/Lisbon", "Porto" to "Europe/Lisbon", "Faro" to "Europe/Lisbon", "Algarve" to "Europe/Lisbon",
        "Madeira" to "Atlantic/Madeira", "Dublin" to "Europe/Dublin",
        "Malaga" to "Europe/Madrid", "Málaga" to "Europe/Madrid", "Seville" to "Europe/Madrid", "Valencia" to "Europe/Madrid",
        "Alicante" to "Europe/Madrid", "Benidorm" to "Europe/Madrid", "Ibiza" to "Europe/Madrid", "Majorca" to "Europe/Madrid",
        "Mallorca" to "Europe/Madrid", "Palma" to "Europe/Madrid", "Menorca" to "Europe/Madrid", "Marbella" to "Europe/Madrid",
        "Tenerife" to "Atlantic/Canary", "Lanzarote" to "Atlantic/Canary", "Gran Canaria" to "Atlantic/Canary",
        "Fuerteventura" to "Atlantic/Canary", "Canaries" to "Atlantic/Canary", "Canary Islands" to "Atlantic/Canary",
        "Nice" to "Europe/Paris", "Lyon" to "Europe/Paris", "Bordeaux" to "Europe/Paris", "Disneyland Paris" to "Europe/Paris",
        "Munich" to "Europe/Berlin", "Vienna" to "Europe/Vienna", "Prague" to "Europe/Prague", "Budapest" to "Europe/Budapest",
        "Krakow" to "Europe/Warsaw", "Venice" to "Europe/Rome", "Florence" to "Europe/Rome", "Naples" to "Europe/Rome",
        "Sicily" to "Europe/Rome", "Sardinia" to "Europe/Rome", "Lake Garda" to "Europe/Rome",
        "Crete" to "Europe/Athens", "Corfu" to "Europe/Athens", "Rhodes" to "Europe/Athens", "Zante" to "Europe/Athens",
        "Cyprus" to "Asia/Nicosia", "Paphos" to "Asia/Nicosia", "Malta" to "Europe/Malta",
        "Dubrovnik" to "Europe/Zagreb", "Split" to "Europe/Zagreb", "Croatia" to "Europe/Zagreb",
        "Antalya" to "Europe/Istanbul", "Dalaman" to "Europe/Istanbul", "Bodrum" to "Europe/Istanbul", "Turkey" to "Europe/Istanbul",
        "Reykjavik" to "Atlantic/Reykjavik", "Iceland" to "Atlantic/Reykjavik",
        "Marrakech" to "Africa/Casablanca", "Morocco" to "Africa/Casablanca",
        "Cairo" to "Africa/Cairo", "Sharm el Sheikh" to "Africa/Cairo", "Hurghada" to "Africa/Cairo", "Egypt" to "Africa/Cairo",
        "Accra" to "Africa/Accra", "Ghana" to "Africa/Accra", "Abuja" to "Africa/Lagos", "Nigeria" to "Africa/Lagos", "Kenya" to "Africa/Nairobi",
        "Orlando" to "America/New_York", "Miami" to "America/New_York", "Las Vegas" to "America/Los_Angeles", "Cancun" to "America/Cancun",
        "Doha" to "Asia/Qatar", "Bali" to "Asia/Makassar", "Bangkok" to "Asia/Bangkok", "Phuket" to "Asia/Bangkok", "Thailand" to "Asia/Bangkok",
        "Japan" to "Asia/Tokyo", "India" to "Asia/Kolkata",
        "Spain" to "Europe/Madrid", "France" to "Europe/Paris", "Italy" to "Europe/Rome", "Portugal" to "Europe/Lisbon",
        "Greece" to "Europe/Athens", "Germany" to "Europe/Berlin", "Netherlands" to "Europe/Amsterdam", "Holland" to "Europe/Amsterdam",
        "Belgium" to "Europe/Brussels", "Switzerland" to "Europe/Zurich", "Austria" to "Europe/Vienna", "Ireland" to "Europe/Dublin",
    )

    private val ALL: List<Pair<String, String>> = (EXTRA + MeetingZoneRules.PLACES).sortedByDescending { it.first.length }

    /** The place a trip's name names, as a whole word with its capital (the longest first), or null. */
    fun placeOf(name: String): NamedZone? {
        val lower = name.lowercase()
        for ((place, id) in ALL) {
            val p = place.lowercase()
            var i = lower.indexOf(p)
            while (i >= 0) {
                val before = lower.getOrNull(i - 1)
                val after = lower.getOrNull(i + p.length)
                // Typed with its capital, so "a nice weekend" or "split stay" names nowhere.
                if ((before == null || !before.isLetter()) && (after == null || !after.isLetter()) && name.getOrNull(i)?.isUpperCase() == true) {
                    return NamedZone(id, place)
                }
                i = lower.indexOf(p, i + 1)
            }
        }
        return null
    }

    /** Minutes the destination's wall clock is ahead of the UK's at [atMs] (negative: behind). */
    fun aheadMinutes(home: LocalCalendar, local: LocalCalendar, atMs: Long): Int {
        fun wall(c: LocalCalendar) = c.epochDayOf(atMs) * 1440 + c.minuteOfDay(atMs)
        return (wall(local) - wall(home)).toInt()
    }

    /** "1 h ahead of UK time", "5 h behind UK time", "4 h 30 ahead of UK time"; null when the clocks agree. */
    fun aheadText(minutes: Int): String? {
        if (minutes == 0) return null
        val m = kotlin.math.abs(minutes)
        val amount = if (m % 60 == 0) "${m / 60} h" else if (m < 60) "$m min" else "${m / 60} h ${m % 60}"
        return "$amount ${if (minutes > 0) "ahead of" else "behind"} $HOME_LABEL"
    }

    /** The trip's clocks line, at noon on its first day: "1 h ahead of UK time"; null with no known zone or the same clock. */
    fun tripLine(t: TripItem, clocks: TripClocks?): String? {
        val local = clocks?.local(t) ?: return null
        return aheadText(aheadMinutes(clocks.home, local.cal, local.cal.toEpochMs(t.startDay, 12 * 60)))
    }

    /** The way there: the first timed flight or train on or before the trip's first day (its time is UK time). */
    fun outbound(t: TripItem): TripBooking? = t.bookings
        .filter { it.minute != null && it.day <= t.startDay && (it.kind == BookingKind.FLIGHT || it.kind == BookingKind.TRAIN) }
        .minWithOrNull(compareBy<TripBooking> { it.day }.thenBy { it.minute })

    /**
     * "17:20 UK time" (or "Sat 23:20 UK time" when it's another day in the UK) for a timed booking at the destination;
     * null for the way there, an untimed booking, an unknown zone or the same clock.
     */
    fun ukTime(b: TripBooking, t: TripItem, clocks: TripClocks?): String? {
        val minute = b.minute ?: return null
        if (outbound(t)?.id == b.id) return null
        val local = clocks?.local(t) ?: return null
        val at = local.cal.toEpochMs(b.day, minute)
        val ukDay = clocks.home.epochDayOf(at)
        val ukMin = clocks.home.minuteOfDay(at)
        if (ukDay == b.day && ukMin == minute) return null
        val day = if (ukDay != b.day) LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(ukDay) - 1] + " " else ""
        return "$day${LocalClock.formatMinute(ukMin)} $HOME_LABEL"
    }
}
