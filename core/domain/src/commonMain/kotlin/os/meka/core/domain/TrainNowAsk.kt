package os.meka.core.domain

/**
 * Obi answers live train status (build plan, take first, Meka 2026-11-17: "can u check elizabeth line from Farringdon
 * to canary wharf is now ok" was answered "I can't check live status from here"). Non-AI, pure.
 *
 * Today's trains on his route ([RouteRules.askLines]: TfL's status for each line and the live board) used to ride at
 * the end of the forecast, the first lines cut when Ask's context is full, and labelled as weather. They now go as
 * their own `trains` lines, first in the context's tail for a question about trains ([isAbout]), and, when the status
 * isn't fresh, a line says when it was last read so Obi never invents it ([statusLines]). A TfL line the question names
 * that isn't on his route ([namedLines]) is asked of TfL on demand by the server (the same free, keyless API, only the
 * lines' ids sent, only status codes kept) and given to the model in Navilife's words ([lineWords]).
 */
object TrainNowAskRules {
    /** The most TfL lines one question asks about on demand. */
    const val MAX_NAMED = 3

    /**
     * TfL's lines by the words people use: the phrase (lower case) → TfL's id and the name Obi says. Names that are also
     * ordinary words or stations ("central", "circle", "district", "victoria", "northern", "metropolitan") only count
     * followed by "line".
     */
    private val LINES: List<Triple<Regex, String, String>> = listOf(
        line("""elizabeth( line)?|crossrail""", "elizabeth", "Elizabeth line"),
        line("""jubilee( line)?""", "jubilee", "Jubilee line"),
        line("""bakerloo( line)?""", "bakerloo", "Bakerloo line"),
        line("""piccadilly line""", "piccadilly", "Piccadilly line"),
        line("""central line""", "central", "Central line"),
        line("""circle line""", "circle", "Circle line"),
        line("""district line""", "district", "District line"),
        line("""victoria line""", "victoria", "Victoria line"),
        line("""northern line""", "northern", "Northern line"),
        line("""metropolitan line|met line""", "metropolitan", "Metropolitan line"),
        line("""hammersmith (and|&) city( line)?|h ?& ?c line""", "hammersmith-city", "Hammersmith & City line"),
        line("""waterloo (and|&) city( line)?""", "waterloo-city", "Waterloo & City line"),
        line("""dlr|docklands light railway""", "dlr", "DLR"),
        line("""liberty line""", "liberty", "Liberty line"),
        line("""lioness line""", "lioness", "Lioness line"),
        line("""mildmay( line)?""", "mildmay", "Mildmay line"),
        line("""suffragette( line)?""", "suffragette", "Suffragette line"),
        line("""weaver line""", "weaver", "Weaver line"),
        line("""windrush( line)?""", "windrush", "Windrush line"),
        line("""trams?""", "tram", "Trams"),
    )

    private fun line(phrase: String, id: String, name: String) =
        Triple(Regex("""(?<![a-z])(?:$phrase)(?![a-z])"""), id, name)

    /** Words that make a question about trains today: the mode, a station, delays, "running", the commute. */
    private val ABOUT = Regex(
        """(?<![a-z])(trains?|rail(way)?|tube|underground|overground|station|platform|delays?|delayed|cancell?ed|disruption|signal failure|commute|line (ok|okay|running|working|open|closed))(?![a-z])""",
    )

    private fun norm(q: String) = q.lowercase().replace('’', '\'').replace(Regex("""\s+"""), " ")

    /**
     * Whether [question] is about trains, the tube or a line today: the words of [ABOUT], a TfL line by name, or one of
     * the lines on his route ([route]'s names as typed in About you).
     */
    fun isAbout(question: String, route: CommuteRoute? = RouteRules.route()): Boolean {
        val q = norm(question)
        if (ABOUT.containsMatchIn(q) || TrainDayAskRules.isAbout(question)) return true
        if (LINES.any { it.first.containsMatchIn(q) }) return true
        val names = route?.let { listOfNotNull(it.first, it.onward, it.fallback) }.orEmpty()
        return names.any { n -> RouteRules.lineId(n).replace('-', ' ').let { it.length >= 3 && Regex("""(?<![a-z])${Regex.escape(it)}(?![a-z])""").containsMatchIn(q.replace('-', ' ')) } }
    }

    /** The TfL ids of the lines [question] names ("is the Elizabeth line ok" → elizabeth), in order, at most [MAX_NAMED]. */
    fun namedLines(question: String): List<String> {
        val q = norm(question)
        return LINES.mapNotNull { (re, id, _) -> re.find(q)?.let { it.range.first to id } }
            .sortedBy { it.first }.map { it.second }.distinct().take(MAX_NAMED)
    }

    /** The name Obi says for TfL's [id] ("elizabeth" → "Elizabeth line"); null for one not in the list. */
    fun nameOf(id: String): String? = LINES.firstOrNull { it.second == id }?.third

    /**
     * Of [named] (from [namedLines]), the ones not on his [route]: those the server asks TfL about on demand.
     * Without a route, all of them.
     */
    fun offRoute(named: List<String>, route: CommuteRoute? = RouteRules.route()): List<String> {
        val ids = route?.ids.orEmpty().toSet()
        return named.filter { it !in ids }
    }

    /**
     * The lines the server adds for TfL's answer on demand: "Elizabeth line: good service" for each line TfL answered,
     * in Navilife's words (status codes only, never TfL's text). Ids not in the list keep TfL's id in words.
     */
    fun lineWords(states: List<LineState>): List<String> =
        states.map { s -> "${nameOf(s.id) ?: RouteRules.name(s.id, null)}: ${RouteRules.words(s.severity)}" }

    /**
     * The status lines for a train question when the route's TfL status isn't fresh ([RouteRules.fresh]), so Obi says
     * how old it is rather than nothing: "train lines on Meka's route: TfL status last read at 06:40, over an hour ago:
     * Elizabeth line good service" or "train lines on Meka's route (Elizabeth line): TfL status not read yet today".
     * Empty without a route, or when the status is fresh (then [RouteRules.askLines] carries it). With TfL's planned
     * status for today held (read the evening before), one more line: "TfL's plans for this morning (checked 18:30): …".
     */
    fun statusLines(s: LineStatusSnapshot, nowMs: Long, cal: LocalCalendar, route: CommuteRoute? = RouteRules.route()): List<String> {
        val r = route ?: return emptyList()
        val out = mutableListOf<String>()
        if (!RouteRules.fresh(s, nowMs)) {
            val checked = s.checkedMs
            val known = r.ids.mapNotNull { id -> s.severityOf(id)?.let { "${RouteRules.name(id, r)} ${RouteRules.words(it)}" } }
            out += if (checked != null && cal.epochDayOf(checked) == cal.epochDayOf(nowMs) && known.isNotEmpty()) {
                "train lines on Meka's route: TfL status last read at ${LocalClock.formatMinute(cal.minuteOfDay(checked))}, over an hour ago: " + known.joinToString("; ")
            } else {
                "train lines on Meka's route (" + r.ids.joinToString(", ") { RouteRules.name(it, r) } + "): TfL status not read yet today"
            }
        }
        val today = cal.epochDayOf(nowMs)
        val read = s.plannedCheckedMs
        if (s.plannedDay == today && read != null) {
            val planned = r.ids.mapNotNull { id -> s.plannedSeverityOf(id)?.let { "${RouteRules.name(id, r)} ${RouteRules.words(it)}" } }
            if (planned.isNotEmpty()) out += "TfL's plans for this morning's commute (checked ${LocalClock.formatMinute(cal.minuteOfDay(read))}): " + planned.joinToString("; ")
        }
        return out
    }
}

/**
 * The safety net for live answers (the same take-first item, fault 2). Non-AI, pure: whether an answer says Obi can't
 * check something live, so the server runs the web search (within the day's cap) and asks once more, and what Obi says
 * when the search can't run.
 */
object CantCheckRules {
    private val SAYS = Regex(
        """(can't|cannot|can not|unable to|not able to|don't have (a )?(way|access) to|no way to) (check|look( (it|that|this))? up|see|get|access|pull up|confirm)( (live|real[- ]time|current|the latest|up[- ]to[- ]date|that live|it live|this live))?""" +
            """|(don't|do not) have (access to )?(any )?(live|real[- ]time|current) (status|data|info|information|access|updates|feeds?)|no (live|real[- ]time) (access|data|feed)""",
        RegexOption.IGNORE_CASE,
    )

    /** Whether [answer] says Obi can't check or look something up live. */
    fun says(answer: String): Boolean = SAYS.containsMatchIn(answer.replace('’', '\''))

    /** What the answer ends with when the search couldn't run: the day's searches used, or the search failing. */
    const val USED_UP = "Today's web searches are used up, so I couldn't look it up live."
    const val FAILED = "The web search didn't answer just now, so I couldn't look it up live."
}
