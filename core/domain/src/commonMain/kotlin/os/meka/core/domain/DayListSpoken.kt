package os.meka.core.domain

/**
 * "Say them" in the Day list (slice 1, the last part): several tasks said in one go — "call the garage then half an
 * hour of email then pick up the boys at half past three for family" — become one line each in the add bar's own
 * syntax ("Call the garage" · "Email 30m" · "Pick up the boys 15:30 #family"), which [DayListRules.pasted] then reads
 * like a pasted list. Non-AI and pure, on the device (the Fold's speech or the Mac's Dictation); nothing is recorded.
 *
 * - **Between tasks**: "then", "and then", "after that", "also", "next task", a full stop, a semicolon or a comma.
 *   Plain "and" never splits ("fish and chips").
 * - **Lengths**: "half an hour", "an hour and a half", "a quarter of an hour", "twenty minutes", "2 hours", with or
 *   without "for".
 * - **Times**: "at 9", "at three thirty", "at 3:30", "9 a.m.", "half past three", "quarter to ten", "at noon"; an
 *   hour from 1 to 6 said without am/pm is the afternoon ("at 3" is 15:00), 7 to 12 is as said.
 * - **Areas**: "for work", "for the family", "for the house", "for money", "for my side hustle" at the end of a task,
 *   or "hashtag work" anywhere.
 */
object DayListSpoken {
    const val SAY = "Say them"
    const val STOP = "Stop"
    const val LISTENING = "Listening · say “then” between tasks"
    const val HINT = "Say them one after another, “then” between · “half an hour”, “at 3”, “for work” work too"
    const val HEARD_NOTHING = "Didn’t catch a task. Tap Say them and try again"
    /** The Mac's field while Dictation listens (Return adds them). */
    const val MAC_PLACEHOLDER = "Say them, “then” between · Return adds them"

    private const val UNITS = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen"
    private const val TENS = "twenty|thirty|forty|fifty|sixty|ninety"
    private const val NUM = "(?:\\d{1,3}|(?:$TENS)(?:[ -](?:one|two|three|four|five|six|seven|eight|nine))?|$UNITS|an?)"
    private const val HOUR = "(?:1[0-2]|0?[1-9]|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"
    private const val MINS = "(?:[0-5]\\d|(?:twenty|thirty|forty|fifty)(?:[ -](?:one|two|three|four|five|six|seven|eight|nine))?|fifteen|ten|five|o[ ']?(?:one|two|three|four|five|six|seven|eight|nine))"
    private const val FOR = "(?:(?:for|about|around|takes?)\\s+)?"
    /** "half an hour of email": the "of" (or "on") goes with the length. */
    private const val OF = "(?:\\s+(?:of|on)(?=\\s))?"
    private val O = setOf(RegexOption.IGNORE_CASE)

    private val HOUR_AND_HALF = Regex("""\b$FOR(?:(?:an?|one)\s+hours?\s+and\s+a\s+half|($NUM)\s+and\s+a\s+half\s+hours?)\b$OF""", O)
    private val HALF_HOUR = Regex("""\b${FOR}half\s+(?:an?\s+)?hour\b$OF""", O)
    private val QUARTER_HOUR = Regex("""\b$FOR(?:a\s+)?quarter\s+(?:of\s+)?an?\s+hour\b$OF""", O)
    private val HOURS = Regex("""\b$FOR($NUM)\s+(?:hours?|hrs?)(?:\s+(?:and\s+)?($NUM)\s+(?:minutes?|mins?))?\b$OF""", O)
    private val MINUTES = Regex("""\b$FOR($NUM)\s+(?:minutes?|mins?)\b$OF""", O)
    private val PAST_TO = Regex("""\b(?:at\s+)?(half|quarter)\s+(past|to)\s+($HOUR)(?:\s*(a\.?\s?m\.?|p\.?\s?m\.?)(?!\w))?""", O)
    private val AM_PM = Regex("""\b(?:at\s+)?($HOUR)(?:(?:[:.]|\s+)($MINS))?\s*(a\.?\s?m\.?|p\.?\s?m\.?)(?!\w)""", O)
    private val AT = Regex("""\bat\s+(noon|midday|$HOUR)(?:(?:[:.]|\s+)($MINS))?(?:\s+o'?\s?clock)?(?![A-Za-z0-9_:])""", O)
    private val OCLOCK = Regex("""\b($HOUR)\s+o'?\s?clock\b""", O)
    private val AT_24H = Regex("""\bat\s+((?:1[3-9]|2[0-3]):[0-5]\d)\b""", O)

    private val SPLIT = Regex("""(?i)\s*(?:[;\n]+|\.(?!\d)|,?\s+(?:and\s+then|then|after\s+that|and\s+also|also|next\s+task|next\s+one|new\s+task)\s+|,\s+)\s*""")
    private val LEAD = Regex("""(?i)^(?:(?:and|then|so|um+|uh+|er+|okay|ok|right|first|firstly|also)\b[,\s]*)+""")
    private val AREA_END = Regex("""(?i)\s+(?:for|hashtag|tag)\s+(?:the\s+|my\s+)?(work|job|office|family|kids|house|home|money|bills|finance|side\s+hustle|side|hustle)\s*$""")
    private val LEADING = Regex("""^(?:(?:\d{1,2}h\d{0,2}|\d{1,3}m|\d{1,2}:\d{2})\s+)+""")
    private val HASHTAG = Regex("""(?i)\bhashtag\s+([A-Za-z][A-Za-z-]*)""")

    private val words = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90,
    )

    /** "twenty five" → 25, "45" → 45, "an" → 1, "o five" → 5; null when it isn't a number. */
    internal fun number(s: String): Int? {
        val t = s.trim().lowercase().replace('-', ' ').removePrefix("o'").removePrefix("o ").trim()
        t.toIntOrNull()?.let { return it }
        val parts = t.split(Regex("\\s+"))
        var total = 0
        for (p in parts) total += words[p] ?: return null
        return total
    }

    /** An hour of the day said without am/pm: 1 to 6 is the afternoon, 7 to 12 as said. */
    private fun dayHour(h: Int): Int = if (h in 1..6) h + 12 else h

    private fun clock(h: Int, m: Int): String = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"

    private fun meridiem(h: Int, ampm: String?): Int {
        val pm = ampm?.lowercase()?.startsWith("p")
        return when (pm) {
            true -> if (h == 12) 12 else h + 12
            false -> if (h == 12) 0 else h
            null -> dayHour(h)
        }
    }

    private fun length(m: Int): String = if (m >= 60) (if (m % 60 == 0) "${m / 60}h" else "${m / 60}h${m % 60}") else "${m}m"

    /** Lengths and times said in words, rewritten in the add bar's syntax over the whole text. */
    internal fun normalise(text: String): String {
        var s = " " + text.replace(Regex("\\s+"), " ").trim() + " "
        s = HOUR_AND_HALF.replace(s) { m ->
            val n = m.groupValues[1].takeIf { it.isNotEmpty() }?.let(::number) ?: 1
            " " + length(n * 60 + 30) + " "
        }
        s = HALF_HOUR.replace(s, " 30m ")
        s = QUARTER_HOUR.replace(s, " 15m ")
        s = HOURS.replace(s) { m ->
            val h = number(m.groupValues[1]) ?: return@replace m.value
            val mm = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::number) ?: 0
            if (h * 60 + mm !in 1..TaskEstimateRules.MAX_MIN) m.value else " " + length(h * 60 + mm) + " "
        }
        s = MINUTES.replace(s) { m ->
            val n = number(m.groupValues[1]) ?: return@replace m.value
            if (n !in 1..TaskEstimateRules.MAX_MIN) m.value else " " + length(n) + " "
        }
        s = PAST_TO.replace(s) { m ->
            val h = number(m.groupValues[3]) ?: return@replace m.value
            val q = if (m.groupValues[1].lowercase() == "half") 30 else 15
            val ampm = m.groupValues[4].takeIf { it.isNotEmpty() }
            if (m.groupValues[2].lowercase() == "past") " " + clock(meridiem(h, ampm), q) + " "
            else {
                val hh = meridiem(h, ampm)
                " " + clock((hh + 23) % 24, 60 - q) + " "
            }
        }
        s = AM_PM.replace(s) { m ->
            val h = number(m.groupValues[1]) ?: return@replace m.value
            val mm = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::number) ?: 0
            if (h !in 1..12 || mm !in 0..59) m.value else " " + clock(meridiem(h, m.groupValues[3]), mm) + " "
        }
        s = AT_24H.replace(s) { m -> " " + m.groupValues[1] + " " }
        s = AT.replace(s) { m ->
            val w = m.groupValues[1].lowercase()
            val h = if (w == "noon" || w == "midday") 12 else number(w) ?: return@replace m.value
            val mm = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::number) ?: 0
            if (h !in 1..12 || mm !in 0..59) m.value else " " + clock(dayHour(h), mm) + " "
        }
        s = OCLOCK.replace(s) { m ->
            val h = number(m.groupValues[1]) ?: return@replace m.value
            if (h !in 1..12) m.value else " " + clock(dayHour(h), 0) + " "
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** One task said: fillers off the front, an area said at the end (or as "hashtag") into "#area", a capital first. */
    private fun tidy(part: String): String? {
        var s = part.trim().replace(LEAD, "").trim()
        AREA_END.find(s)?.let { m -> s = s.removeRange(m.range).trim() + " #" + m.groupValues[1].replace(Regex("\\s+"), "") }
        s = HASHTAG.replace(s) { "#" + it.groupValues[1] }
        s = s.trim().trim(',', '.', ';', '-').trim()
        // "30m email" reads "Email 30m": the length or time said first goes after the words.
        LEADING.find(s)?.let { m -> if (m.value.length < s.length) s = s.substring(m.value.length).trim() + " " + m.value.trim() }
        if (s.isEmpty() || DayListRules.parse(s) == null) return null
        return s.replaceFirstChar { it.uppercaseChar() }
    }

    /** The tasks said, one line each in the add bar's syntax (at most [DayListRules.MAX_PASTE]). */
    fun lines(said: String): List<String> =
        (normalise(said) + " ").split(SPLIT).mapNotNull(::tidy).take(DayListRules.MAX_PASTE)

    /** What the Day list adds for [said]: [lines] joined as a pasted list, or null when nothing was heard. */
    fun asList(said: String): String? = lines(said).takeIf { it.isNotEmpty() }?.joinToString("\n")
}
