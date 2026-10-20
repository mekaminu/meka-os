package os.meka.core.domain

/**
 * The companion (build plan, take first), slice 8: **trains on another day**. Non-AI, pure.
 *
 * Today's trains are already in Ask (TfL's status and the live board, [RouteRules.askLines]). A question about trains
 * on another day ("train or drive to the match on Saturday?", "are the trains OK tomorrow?", "any engineering works on
 * Thursday?") also carries, for each coming day it names ([DayAskRules.range], at most [MAX_DAYS]): whether it is an
 * office day and when he'd leave ([CommuteAskRules.dayLine]), and TfL's planned status for that morning when the server
 * has read it (only tomorrow's, from 17:00 the evening before, [LineStatusSnapshot.planned]). Live departures are only
 * known for the next couple of hours, so the head line says so and the model never invents a time. Everything here is
 * Meka's own setting or TfL's public status in Navilife's words, so it never makes the context untrusted. Looked up on
 * the device; nothing new is read or kept on the server.
 */
object TrainDayAskRules {
    /** Coming days one question carries train lines for. */
    const val MAX_DAYS = 3

    private val ABOUT = Regex(
        """\b(trains?|rail(way)?|tube|station|engineering works?|planned works?|line closures?|rail strikes?|commute by train)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Whether [question] is about trains ("train or drive…", "are the trains OK…", "engineering works"). */
    fun isAbout(question: String): Boolean = ABOUT.containsMatchIn(question)

    /**
     * The trains lines for [r]'s coming days (not today, not gone), empty when there are none or without a route in
     * About you; each line short enough for the context (at most [AskRules.MAX_LINE] characters):
     * "Trains on the days asked about · live times are only known for the next couple of hours",
     * "Trains · his route South Rail to Oakfield Junction, then Metro; or North Rail to Riverside",
     * then two lines a day: "Trains Fri 23 Oct · office day · leave 07:40 · work 09:00–17:30 · home about 18:50" and
     * "Trains Fri 23 Oct · TfL's plans for the morning (checked 18:30): South Rail planned closure; Metro line good
     * service" or "Trains Fri 23 Oct · TfL's plans for the morning not read yet (read from 17:00 the evening before)".
     */
    fun askLines(r: AskDays, s: LineStatusSnapshot, work: WorkHours, nowMs: Long, cal: LocalCalendar): List<String> {
        val route = RouteRules.route() ?: return emptyList()
        val today = cal.epochDayOf(nowMs)
        val days = r.days.filter { it > today }.take(MAX_DAYS)
        if (days.isEmpty()) return emptyList()
        val out = mutableListOf(
            "Trains on the days asked about · live times are only known for the next couple of hours",
            "Trains · his route ${route.text}",
        )
        days.forEach { d ->
            val label = "Trains ${CivilDate.shortLabel(d)}"
            out += "$label · ${CommuteAskRules.dayLine(work, d, cal)}"
            out += "$label · ${planned(s, route, d, nowMs, cal)}"
        }
        return out
    }

    private fun planned(s: LineStatusSnapshot, route: CommuteRoute, day: Long, nowMs: Long, cal: LocalCalendar): String {
        val read = s.plannedCheckedMs
        val fresh = s.plannedDay == day && read != null && nowMs - read <= CommuteDisruptionRules.FRESH_MS
        val known = if (!fresh) emptyList() else route.ids.mapNotNull { id ->
            s.plannedSeverityOf(id)?.let { "${RouteRules.name(id, route)} ${RouteRules.words(it)}" }
        }
        if (known.isEmpty()) return "TfL's plans for the morning not read yet (read from 17:00 the evening before)"
        return "TfL's plans for the morning (checked ${LocalClock.formatMinute(cal.minuteOfDay(read!!))}): " + known.joinToString("; ")
    }
}
