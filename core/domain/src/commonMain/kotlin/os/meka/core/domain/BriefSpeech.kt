package os.meka.core.domain

/**
 * The spoken morning brief (build plan V1, "Weather and a voice Meka likes" and "Spoken morning brief"; non-AI, pure):
 * the brief written out as MEKA would say it, for MEKA's voice (or the device's own) to read aloud from the brief's
 * Listen button, and on its own after the wake alarm is dismissed.
 *
 * It says what the pane shows, in the pane's order and nothing more: the greeting and the date, work, the weather,
 * the day (its first few timed things by name), what's overdue, who to chase, what needs doing on the lists, habits and
 * a fast, and every headline the pane shows (Barça first, as listed), each "From BBC Sport: …". Abbreviations are written out ("Fri 9 Oct" → "Friday 9 October", "09:30" → "9:30",
 * "9–15°" → "9 to 15 degrees") and the pane's " · " separators become pauses, so either voice reads it naturally.
 * The day's part is bounded by [MAX_CHARS], cut at a sentence, so a crowded morning stays a minute or so of listening;
 * the news is never cut (Meka, 2026-10-09: Listen read 3 of the 5 headlines shown), only an overlong title is shortened
 * at a word ([MAX_HEADLINE_CHARS]). Each story is its own sentence, so the voice pauses between them.
 */
object BriefSpeech {
    /** Timed things named one by one; the rest are only counted. */
    const val MAX_NAMED = 4
    /** Chases named; the rest are counted in the waiting line. */
    const val MAX_CHASES = 2
    /** Things on the lists named. */
    const val MAX_ATTENTION = 3
    /** A headline's title longer than this is shortened at a word (titles are rarely half this). */
    const val MAX_HEADLINE_CHARS = 240
    /** About a minute of speech for the day's part (the news comes on top); whole sentences only. */
    const val MAX_CHARS = 1_200

    const val SIGN_OFF = "That's your morning."

    /** The brief [v] as one block of sentences to be read aloud to [name]. */
    fun script(v: MorningBriefView, name: String = "Meka"): String {
        val out = mutableListOf<String>()
        out += "${v.greeting}, $name."
        if (v.dateLabel.isNotBlank()) out += "It's ${spokenDate(v.dateLabel)}."
        v.workLine?.let { out += sentence(it.replace(WORK_HOURS) { m -> "Work is from ${m.groupValues[1]} to ${m.groupValues[2]}" }) }
        v.weatherLine?.let { out += sentence("Today, " + it) }

        if (v.day.isEmpty()) {
            out += "Nothing's planned yet."
        } else {
            out += sentence(dayLine(v))
            val timed = v.day.filter { it.time != null && it.time != ALL_DAY }
            val allDay = v.day.filter { it.time == ALL_DAY }
            if (timed.isNotEmpty()) {
                val named = timed.take(MAX_NAMED).map { r -> "${clean(r.title)} ${spokenWhen(r.time!!)}" }
                val more = timed.size - named.size
                out += sentence(listing(named) + if (more > 0) ", and ${count(more, "more thing")}" else "")
            }
            if (allDay.isNotEmpty()) out += sentence("All day: " + listing(allDay.take(MAX_NAMED).map { clean(it.title) }))
        }
        if (v.overdueCount > 0) out += sentence("${count(v.overdueCount, "task")} ${if (v.overdueCount == 1) "is" else "are"} overdue")

        v.waitingLine?.let { line ->
            out += sentence("You're " + line.replaceFirstChar { it.lowercase() })
            val chases = v.waiting.filter { it.state == DueState.DUE }.take(MAX_CHASES)
            if (chases.isNotEmpty()) out += sentence("Chase " + listing(chases.map { w -> clean(w.who?.let { "$it about ${w.title}" } ?: w.title) }))
        }
        if (v.attention.isNotEmpty()) {
            out += sentence("On your lists: " + listing(v.attention.take(MAX_ATTENTION).map { a -> clean(a.title) }))
        }
        v.habitsLine?.let { out += sentence("Habits: $it") }
        v.fastingLine?.let { out += sentence(it) }

        out += SIGN_OFF
        return (fit(out).dropLast(1) + news(v.headlines) + SIGN_OFF).joinToString(" ")
    }

    /** Every headline shown, in the pane's order: "In the news." then "From BBC Sport: Barça win again." each. */
    fun news(headlines: List<BriefHeadline>): List<String> {
        if (headlines.isEmpty()) return emptyList()
        return listOf("In the news.") + headlines.map { h ->
            val source = h.meta.substringBefore(" · ").trim()
            val title = shorten(clean(h.title))
            sentence(if (source.isEmpty()) title else "From $source: $title")
        }
    }

    /** [title] cut at the last word that fits [MAX_HEADLINE_CHARS]. */
    private fun shorten(title: String): String {
        if (title.length <= MAX_HEADLINE_CHARS) return title
        val cut = title.lastIndexOf(' ', MAX_HEADLINE_CHARS).takeIf { it > 0 } ?: MAX_HEADLINE_CHARS
        return title.substring(0, cut).trimEnd(',', ';', ':', ' ')
    }

    /** "Fri 9 Oct" → "Friday 9 October"; anything else is left as it is. */
    fun spokenDate(label: String): String = label.trim().split(' ').joinToString(" ") { w -> DAYS[w] ?: MONTHS[w] ?: w }

    /** "09:30" → "9:30", "00:15" → "0:15"; every clock time in [text] loses its leading zero. */
    fun spokenTimes(text: String): String = text.replace(LEADING_ZERO) { m -> m.groupValues[1] + m.groupValues[2] }

    /**
     * The pane's text made speakable: clock times without the leading zero, "9–15°" as "9 to 15 degrees", a lone "14°"
     * as "14 degrees", dashes and the " · " separators as commas, "↻" dropped.
     */
    fun clean(text: String): String = spokenTimes(text)
        .replace(RANGE_DEGREES) { m -> "${m.groupValues[1]} to ${m.groupValues[2]} degrees" }
        .replace(DEGREES) { m -> "${m.groupValues[1]} degrees" }
        .replace(TIME_RANGE) { m -> "${m.groupValues[1]} to ${m.groupValues[2]}" }
        .replace(" — ", ", ").replace(" – ", ", ").replace(" · ", ", ")
        .replace("↻", "")
        .replace(SPACES, " ")
        .trim()

    private fun dayLine(v: MorningBriefView): String {
        val things = listOfNotNull(
            v.eventCount.takeIf { it > 0 }?.let { count(it, "event") },
            v.taskCount.takeIf { it > 0 }?.let { count(it, "task") },
        )
        if (things.isEmpty()) return "Today: " + v.daySummary
        val first = v.daySummary.split(" · ").firstOrNull { it.startsWith("first at ") }
        return "You've got " + things.joinToString(" and ") + (first?.let { ", $it" } ?: "")
    }

    /** "at 9:30" for a start time, "until 10:00" for something already running ("Until 10:00"). */
    private fun spokenWhen(time: String): String =
        if (time.startsWith("Until ")) "until " + spokenTimes(time.removePrefix("Until ")) else "at " + spokenTimes(time)

    /** "a, b and c". */
    private fun listing(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    /** [text] cleaned, ending with a full stop unless it already ends a sentence. */
    private fun sentence(text: String): String {
        val c = clean(text).trimEnd(',', ';', ' ')
        return if (c.isEmpty() || c.last() in ".!?…") c else "$c."
    }

    /** The sentences kept while they fit in [MAX_CHARS]; the sign-off always ends them. */
    private fun fit(sentences: List<String>): List<String> {
        val body = sentences.filter { it.isNotBlank() }.dropLast(1)
        val kept = mutableListOf<String>()
        var length = SIGN_OFF.length
        for (s in body) {
            if (length + 1 + s.length > MAX_CHARS) break
            kept += s
            length += 1 + s.length
        }
        return kept + SIGN_OFF
    }

    private const val ALL_DAY = "All day"
    private val WORK_HOURS = Regex("^Work (\\d{1,2}:\\d{2})–(\\d{1,2}:\\d{2})")
    private val LEADING_ZERO = Regex("(^|[^\\d:])0(\\d:\\d{2})")
    private val RANGE_DEGREES = Regex("(-?\\d+)–(-?\\d+)°")
    private val DEGREES = Regex("(-?\\d+)°")
    private val TIME_RANGE = Regex("(\\d{1,2}:\\d{2})–(\\d{1,2}:\\d{2})")
    private val SPACES = Regex("\\s+")
    private val DAYS = mapOf(
        "Mon" to "Monday", "Tue" to "Tuesday", "Wed" to "Wednesday", "Thu" to "Thursday",
        "Fri" to "Friday", "Sat" to "Saturday", "Sun" to "Sunday",
    )
    private val MONTHS = mapOf(
        "Jan" to "January", "Feb" to "February", "Mar" to "March", "Apr" to "April", "May" to "May", "Jun" to "June",
        "Jul" to "July", "Aug" to "August", "Sep" to "September", "Oct" to "October", "Nov" to "November", "Dec" to "December",
    )
}
