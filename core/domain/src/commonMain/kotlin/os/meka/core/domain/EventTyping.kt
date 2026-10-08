package os.meka.core.domain

/**
 * Edit your calendars from MEKA (build plan M1), slice 2d-ii: **natural typing in Add event's title**. Non-AI, pure.
 *
 * "Dentist Fri 3pm" fills the sheet: the day, the start, a length ("3-4pm", "for 2h") or all day, read from the words
 * at the **end** of the title only, so "Lunch with Monday's team" keeps its words and only a trailing run of when-words
 * counts. What's left ("Dentist") is the title the event is saved with. Nothing is read when what's left would be empty.
 *
 * Understood (case-insensitive, in any order at the end, each kind once):
 * - days: today · tonight (19:00 unless a time is given) · tomorrow (tmrw, tmr) · Mon…Sun (the coming one, today
 *   included unless its time has passed) · next Fri (Friday of next week) · this Fri · 12 Oct · Oct 12 · 12th Oct ·
 *   12/10 (day/month, UK) · 12/10/2027; "on" may lead; a weekday before a date is allowed ("Fri 9 Oct").
 * - times: 3pm · 3:30pm · 3.30pm · 15:00 · at 3pm · @3pm · noon · midday. A bare "3" is never a time ("Room 3").
 * - ranges: 3-4pm · 3pm-4:30pm · 15:00–16:30 · from 3 to 5pm · 11-1pm (11:00–13:00); past midnight runs into the next day.
 * - lengths: for 2h · for 90 min · for 1h30 · for an hour · for half an hour ("for 2" is "Dinner for 2", not a length).
 * - all day: "all day", "all-day".
 * A time with no day that has already passed today means tomorrow (applied by [AddEventForm.typeTitle]).
 */
data class TypedWhen(
    /** The words read, as typed ("Fri 3pm"). */
    val words: String,
    /** The title without them ("Dentist"). */
    val title: String,
    val day: Long?,
    val minute: Int?,
    val allDay: Boolean,
    val lengthMin: Int?,
)

object EventTypingRules {
    const val TONIGHT_MINUTE = 19 * 60
    const val NOON = 12 * 60

    private val opt = setOf(RegexOption.IGNORE_CASE)
    private const val T = """(\d{1,2})(?:([:.])(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?"""
    private const val WEEKDAY = """(mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)"""
    private const val MONTH = """(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"""
    private const val ORD = """(?:st|nd|rd|th)?"""
    private const val LEAD = """(?:on\s+)?(?:$WEEKDAY,?\s+)?(?:the\s+)?"""
    private const val END = """(?=$|\s)"""

    private fun tail(piece: String) = Regex("""(?:^|\s)(?:$piece)\s*$""", opt)

    private val length = tail("""for\s+(an?\s+hour|half\s+an\s+hour|\d+(?:[.,]\d+)?\s*(?:hours?|hrs?|h|minutes?|mins?|m)(?:\s*\d+\s*(?:minutes?|mins?|m)?)?)""")
    private val allDay = tail("""all[\s-]?day""")
    private val range = tail("""(?:from\s+|at\s+)?$T\s*(?:-|–|—|to|until|till)\s*$T""")
    private val time = tail("""(?:at\s+|@\s*)?$T$END""")
    private val noon = tail("""(?:at\s+)?(noon|midday)""")
    private val dateDm = tail("""$LEAD(\d{1,2})$ORD\s+(?:of\s+)?$MONTH""")
    private val dateMd = tail("""$LEAD$MONTH\s+(\d{1,2})$ORD""")
    private val dateSlash = tail("""$LEAD(\d{1,2})/(\d{1,2})(?:/(\d{4}|\d{2}))?""")
    private val weekday = tail("""(?:on\s+)?(?:(this|next)\s+)?$WEEKDAY""")
    private val relative = tail("""(today|tonight|tomorrow|tmrw|tmr)""")
    private val trailing = Regex("""[\s,;:·\-–—]+$""")

    private enum class Kind { LENGTH, ALL_DAY, TIME, DAY }

    /** What [text]'s trailing words say about when, or null when they say nothing (or nothing would be left). */
    fun parse(text: String, today: Long, nowMinute: Int): TypedWhen? {
        var rest = text.trimEnd()
        val seen = mutableSetOf<Kind>()
        var day: Long? = null
        var minute: Int? = null
        var endMinute: Int? = null
        var lengthMin: Int? = null
        var all = false
        var tonight = false
        var weekdayOnly: Int? = null // a plain weekday: pushed a week on when its time has passed today
        while (rest.isNotEmpty()) {
            // Each step reads one piece off the end; the first that fits wins, then it starts again.
            fun take(kind: Kind, re: Regex, read: (MatchResult) -> Boolean): Boolean {
                if (kind in seen) return false
                val m = re.find(rest) ?: return false
                if (!read(m)) return false
                seen += kind
                rest = rest.substring(0, m.range.first)
                return true
            }
            val cur = CivilDate.isoDayOfWeek(today)
            val read = take(Kind.LENGTH, length) { m -> lengthOf(m.groupValues[1])?.also { lengthMin = it } != null } ||
                take(Kind.ALL_DAY, allDay) { all = true; true } ||
                take(Kind.TIME, range) { m ->
                    val g = m.groupValues
                    rangeOf(g[1], g[2], g[3], g[4], g[5], g[6], g[7], g[8])?.also { (s, e) -> minute = s; endMinute = e } != null
                } ||
                take(Kind.TIME, time) { m ->
                    val g = m.groupValues
                    minuteOf(g[1], g[2], g[3], g[4])?.also { minute = it } != null
                } ||
                take(Kind.TIME, noon) { minute = NOON; true } ||
                take(Kind.DAY, dateDm) { m ->
                    dateOf(m.groupValues[2].toInt(), monthOf(m.groupValues[3]), null, today)?.also { day = it } != null
                } ||
                take(Kind.DAY, dateMd) { m ->
                    dateOf(m.groupValues[3].toInt(), monthOf(m.groupValues[2]), null, today)?.also { day = it } != null
                } ||
                take(Kind.DAY, dateSlash) { m ->
                    val y = m.groupValues[4].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
                    dateOf(m.groupValues[2].toInt(), m.groupValues[3].toInt(), y, today)?.also { day = it } != null
                } ||
                take(Kind.DAY, weekday) { m ->
                    // "Walk in the sun", "Lie-in sat": a lone lower-case sun/sat isn't a day.
                    if (m.groupValues[1].isEmpty() && m.groupValues[2] in setOf("sun", "sat")) return@take false
                    val w = weekdayOf(m.groupValues[2])
                    day = when (m.groupValues[1].lowercase()) {
                        "next" -> today - (cur - 1) + 7 + (w - 1)
                        else -> today + (w - cur).mod(7)
                    }
                    if (m.groupValues[1].isEmpty()) weekdayOnly = w
                    true
                } ||
                take(Kind.DAY, relative) { m ->
                    when (m.groupValues[1].lowercase()) {
                        "today" -> day = today
                        "tonight" -> { day = today; tonight = true }
                        else -> day = today + 1
                    }
                    true
                }
            if (!read) break
        }
        if (seen.isEmpty()) return null
        if (all && minute != null) return null // "all day 3pm" says two things
        val title = rest.replace(trailing, "").trim()
        if (title.isEmpty()) return null
        if (tonight && minute == null && !all) minute = TONIGHT_MINUTE
        val m = minute
        if (weekdayOnly != null && day == today && m != null && m <= nowMinute) day = today + 7
        val d = day
        if (d != null && (d < today || d > today + TaskWhenRules.MAX_DAYS_AHEAD)) return null
        val e = endMinute
        if (m != null && e != null) lengthMin = (if (e > m) e - m else e + 24 * 60 - m)
        lengthMin = lengthMin?.takeIf { it in AddEventRules.MIN_LENGTH..AddEventRules.MAX_LENGTH }
        val words = text.trimEnd().substring(rest.length).trim().replace(Regex("""\s+"""), " ")
        return TypedWhen(words, title, d, m, all, lengthMin)
    }

    /**
     * The form after Meka typed [p]: its day, start (or all day) and length. A time with no day that has passed today
     * moves to tomorrow.
     */
    fun apply(form: AddEventForm, p: TypedWhen): AddEventForm {
        var f = form
        p.day?.let { f = f.withDay(it) }
        when {
            p.allDay -> f = f.copy(minute = null)
            p.minute != null -> f = f.copy(minute = p.minute)
        }
        if (p.day == null && p.minute != null && f.day == f.today && p.minute <= f.nowMinute) f = f.withDay(f.today + 1)
        p.lengthMin?.let { f = f.withLength(it) }
        return f
    }

    /** "Saves as “Dentist”". */
    fun titleLine(p: TypedWhen): String = "Saves as “${p.title.take(60)}${if (p.title.length > 60) "…" else ""}”"

    /** "Keep “Fri 3pm” in the title". */
    fun keepLabel(p: TypedWhen): String = "Keep “${p.words}” in the title"

    private fun minuteOf(h: String, sep: String, m: String, mer: String): Int? {
        var hour = h.toIntOrNull() ?: return null
        val min = if (m.isEmpty()) 0 else m.toIntOrNull() ?: return null
        if (min > 59) return null
        val ap = mer.lowercase().replace(".", "")
        when {
            ap.isEmpty() -> {
                // Without am/pm only "15:00" is a time ("Room 3", "v2.10" aren't).
                if (sep != ":" || hour > 23) return null
            }
            hour !in 1..12 -> return null
            ap == "am" -> if (hour == 12) hour = 0
            else -> if (hour != 12) hour += 12
        }
        return hour * 60 + min
    }

    /** "3-4pm" → 15:00–16:00; "3:30-5pm" → 15:30–17:00; "11-1pm" → 11:00–13:00; both bare ("3-4") → nothing. */
    private fun rangeOf(h1: String, s1: String, m1: String, a1: String, h2: String, s2: String, m2: String, a2: String): Pair<Int, Int>? {
        val end = minuteOf(h2, s2, m2, a2) ?: return null
        val hour1 = h1.toIntOrNull() ?: return null
        val start = if (a1.isEmpty() && a2.isNotEmpty() && hour1 in 1..12) {
            // The start borrows the end's am/pm: the later reading that still starts before the end.
            listOf(a2, "am", "pm").firstNotNullOfOrNull { ap -> minuteOf(h1, ".", m1, ap)?.takeIf { it < end } }
                ?: minuteOf(h1, s1, m1, "")
        } else minuteOf(h1, s1, m1, a1)
        return start?.let { it to end }
    }

    private fun lengthOf(s: String): Int? {
        val t = s.trim().lowercase().replace(Regex("""\s+"""), " ")
        return when {
            t == "an hour" || t == "a hour" -> 60
            t == "half an hour" -> 30
            else -> QuickAlarmRules.duration(t)?.takeIf { it.second.isEmpty() }?.first?.let { it / 60 }
        }
    }

    private fun weekdayOf(s: String): Int = when (s.lowercase().take(3)) {
        "mon" -> 1; "tue" -> 2; "wed" -> 3; "thu" -> 4; "fri" -> 5; "sat" -> 6; else -> 7
    }

    private fun monthOf(s: String): Int = when (s.lowercase().take(3)) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6
        "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; else -> 12
    }

    /** That date this year (next year once it has passed), or the given year; null when there's no such date. */
    private fun dateOf(d: Int, m: Int, year: Int?, today: Long): Long? {
        if (m !in 1..12) return null
        val thisYear = CivilDate.fromEpochDay(today).year
        fun on(y: Int): Long? = if (d in 1..CivilDate.lengthOfMonth(y, m)) CivilDate.toEpochDay(y, m, d) else null
        if (year != null) return on(year)
        val first = on(thisYear)
        return if (first != null && first >= today) first else on(thisYear + 1)
    }
}
