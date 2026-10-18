package os.meka.core.domain

/*
 * Scheduling assistant, slice 3 (build plan; non-AI, pure): **the other person's time zone, when their email names one**.
 * "I'm based in New York", "3pm Eastern time", "we're in Singapore", "10:00 CET": the times Suggest times offers are
 * then also written in their time ("• Tuesday 20 October, 13:00–14:00 (08:00–09:00 New York time)") and chosen inside
 * their working day where his allows it. Their words only name a place from this fixed list (ADR-006): nothing they
 * write reaches the reply but the list's own label. Abbreviations count only in capitals ("EST", "CET"), places only
 * with a word that says they are there or that it's their time ("in Paris", "Paris time"), so "the Paris trip" doesn't.
 */

/** A place their email names: an IANA zone id and the words the reply uses ("New York"). */
data class NamedZone(val id: String, val label: String)

/** Their zone as a calendar, and its label, for writing the times. */
class TheirZone(val cal: LocalCalendar, val label: String)

object MeetingZoneRules {
    /** Their working day, local: offered times start at or after this and end by [DAY_END_MIN] where possible. */
    const val DAY_START_MIN = 8 * 60
    const val DAY_END_MIN = 18 * 60

    internal val PLACES = listOf(
        "New York" to "America/New_York", "Boston" to "America/New_York", "Washington" to "America/New_York",
        "Toronto" to "America/Toronto", "Chicago" to "America/Chicago", "Denver" to "America/Denver",
        "Los Angeles" to "America/Los_Angeles", "San Francisco" to "America/Los_Angeles", "Seattle" to "America/Los_Angeles",
        "Vancouver" to "America/Vancouver", "São Paulo" to "America/Sao_Paulo", "Sao Paulo" to "America/Sao_Paulo",
        "Paris" to "Europe/Paris", "Berlin" to "Europe/Berlin", "Amsterdam" to "Europe/Amsterdam", "Brussels" to "Europe/Brussels",
        "Madrid" to "Europe/Madrid", "Barcelona" to "Europe/Madrid", "Rome" to "Europe/Rome", "Milan" to "Europe/Rome",
        "Zurich" to "Europe/Zurich", "Stockholm" to "Europe/Stockholm", "Copenhagen" to "Europe/Copenhagen", "Warsaw" to "Europe/Warsaw",
        "Athens" to "Europe/Athens", "Istanbul" to "Europe/Istanbul", "Dubai" to "Asia/Dubai", "Abu Dhabi" to "Asia/Dubai",
        "Mumbai" to "Asia/Kolkata", "Bangalore" to "Asia/Kolkata", "Bengaluru" to "Asia/Kolkata", "Delhi" to "Asia/Kolkata",
        "Singapore" to "Asia/Singapore", "Hong Kong" to "Asia/Hong_Kong", "Shanghai" to "Asia/Shanghai", "Tokyo" to "Asia/Tokyo",
        "Sydney" to "Australia/Sydney", "Melbourne" to "Australia/Melbourne", "Auckland" to "Pacific/Auckland",
        "Lagos" to "Africa/Lagos", "Nairobi" to "Africa/Nairobi", "Johannesburg" to "Africa/Johannesburg", "Cape Town" to "Africa/Johannesburg",
    )
    /** Named regions: "Eastern time", "Central European time". */
    private val REGIONS = listOf(
        Regex("""\b(?:US\s+)?eastern\s+(?:standard\s+|daylight\s+)?time\b""", RegexOption.IGNORE_CASE) to NamedZone("America/New_York", "New York"),
        Regex("""\b(?:US\s+)?pacific\s+(?:standard\s+|daylight\s+)?time\b""", RegexOption.IGNORE_CASE) to NamedZone("America/Los_Angeles", "Los Angeles"),
        Regex("""\bcentral\s+european\s+(?:summer\s+)?time\b""", RegexOption.IGNORE_CASE) to NamedZone("Europe/Paris", "Paris"),
        Regex("""\b(?:US\s+)?central\s+(?:standard\s+|daylight\s+)?time\b""", RegexOption.IGNORE_CASE) to NamedZone("America/Chicago", "Chicago"),
        Regex("""\b(?:US\s+)?mountain\s+(?:standard\s+|daylight\s+)?time\b""", RegexOption.IGNORE_CASE) to NamedZone("America/Denver", "Denver"),
        Regex("""\bindia(?:n)?\s+(?:standard\s+)?time\b""", RegexOption.IGNORE_CASE) to NamedZone("Asia/Kolkata", "India"),
        Regex("""\bgulf\s+standard\s+time\b""", RegexOption.IGNORE_CASE) to NamedZone("Asia/Dubai", "Dubai"),
    )
    /** Capitals only, as whole words. ("IST" is Irish or Indian, so it isn't read.) */
    private val ABBREVIATIONS = mapOf(
        "EST" to NamedZone("America/New_York", "New York"), "EDT" to NamedZone("America/New_York", "New York"),
        "ET" to NamedZone("America/New_York", "New York"), "PST" to NamedZone("America/Los_Angeles", "Los Angeles"),
        "PDT" to NamedZone("America/Los_Angeles", "Los Angeles"), "PT" to NamedZone("America/Los_Angeles", "Los Angeles"),
        "CST" to NamedZone("America/Chicago", "Chicago"), "CDT" to NamedZone("America/Chicago", "Chicago"),
        "CET" to NamedZone("Europe/Paris", "Paris"), "CEST" to NamedZone("Europe/Paris", "Paris"),
        "SGT" to NamedZone("Asia/Singapore", "Singapore"), "HKT" to NamedZone("Asia/Hong_Kong", "Hong Kong"),
        "JST" to NamedZone("Asia/Tokyo", "Tokyo"), "AEST" to NamedZone("Australia/Sydney", "Sydney"),
        "AEDT" to NamedZone("Australia/Sydney", "Sydney"), "WAT" to NamedZone("Africa/Lagos", "Lagos"),
        "SAST" to NamedZone("Africa/Johannesburg", "Johannesburg"), "GST" to NamedZone("Asia/Dubai", "Dubai"),
    )
    private val ABBR = Regex("""\b(${ABBREVIATIONS.keys.sortedByDescending { it.length }.joinToString("|")})\b""")
    private val PLACE_WORDS = PLACES.map { it.first }.distinct().sortedByDescending { it.length }.joinToString("|") { it.replace(" ", "\\s+") }
    private val PLACE = Regex(
        """\b(?:(?:I['’]?m|I\s+am|we['’]?re|we\s+are|based|located|living|live|work|working|sitting|here)\s+(?:over\s+)?in|time\s+in|out\s+of)\s+""" +
            """(?:the\s+)?(${PLACE_WORDS})\b|""" +
            """\b(${PLACE_WORDS})\s+(?:time|timezone|time\s+zone|hours)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** The first place [texts] name, or null. */
    fun named(texts: List<String?>): NamedZone? {
        for (t in texts) {
            if (t.isNullOrBlank()) continue
            val hits = mutableListOf<Pair<Int, NamedZone>>()
            PLACE.find(t)?.let { m ->
                val word = (m.groups[1] ?: m.groups[2])!!.value.replace(Regex("""\s+"""), " ")
                PLACES.firstOrNull { it.first.equals(word, ignoreCase = true) }?.let { hits += m.range.first to NamedZone(it.second, it.first) }
            }
            REGIONS.forEach { (r, z) -> r.find(t)?.let { hits += it.range.first to z } }
            ABBR.find(t)?.let { m -> ABBREVIATIONS[m.groupValues[1]]?.let { hits += m.range.first to it } }
            hits.minByOrNull { it.first }?.let { return it.second }
        }
        return null
    }

    /** Whether [at]..[at]+[minutes] falls inside their working day, on one day of theirs. */
    fun inTheirDay(at: Long, minutes: Int, theirs: LocalCalendar): Boolean {
        val start = theirs.minuteOfDay(at)
        val endAt = at + minutes * 60_000L
        return start >= DAY_START_MIN && theirs.epochDayOf(endAt - 1) == theirs.epochDayOf(at) && start + minutes <= DAY_END_MIN
    }

    /**
     * " (08:00–09:00 New York time)" after a time of his, with their weekday when it's a different day for them
     * (" (Wed 00:30–01:30 Sydney time)"); empty when their clock reads the same as his.
     */
    fun theirPart(s: FreeSlot, cal: LocalCalendar, theirs: TheirZone?): String {
        val t = theirs ?: return ""
        val day = t.cal.epochDayOf(s.startMs)
        val start = t.cal.minuteOfDay(s.startMs)
        if (day == cal.epochDayOf(s.startMs) && start == cal.minuteOfDay(s.startMs)) return ""
        val dayPart = if (day != cal.epochDayOf(s.startMs)) "${LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(day) - 1]} " else ""
        return " ($dayPart${LocalClock.formatMinute(start)}–${LocalClock.formatMinute(t.cal.minuteOfDay(s.endMs))} ${t.label} time)"
    }
}
