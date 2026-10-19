package os.meka.core.domain

/**
 * The commute counts everywhere, completeness (the watch shows leave-by, 2026-10-19): on an office day's commute the
 * Galaxy Watch shows when to leave, read from the watch's own copy of the day (the live board and Work mode's hours
 * and commute, all synced; nothing new is stored or sent). Morning, from 06:00 until work starts: "LEAVE BY 07:32 · IN
 * 12 MIN" · "For the 07:42 · on time" with the live board and About you's Time to the train, else "LEAVE ABOUT 07:40"
 * · "Work at 09:00 · 1 h 20 commute"; once he's on the train, "ON THE 07:42" · "16 min to <change>". Evening, from 45
 * min before work ends: "LEAVE WORK BY 17:37" · "For the 17:52 · 2 min late", else "HOME ABOUT 18:50" · "Leave work at
 * 17:30". Trouble on the route (a cancelled or late train, a line not running well) is the [note] and lights it, as
 * do the last ten minutes before leaving. Nothing on work-from-home days, leave, trips or days off. Pure, no AI.
 */
data class WatchCommute(
    /** "LEAVE BY 07:32 · IN 12 MIN" · "LEAVE ABOUT 07:40" · "ON THE 07:42" · "LEAVE WORK BY 17:37" · "HOME ABOUT 18:50". */
    val label: String,
    /** Trouble on the route, a later train than usual, or ten minutes or less to leave: the accent. */
    val lit: Boolean,
    /** "For the 07:42 · on time" · "Work at 09:00 · 1 h 20 commute" · "16 min to Oakfield Junction". */
    val line: String,
    /** What's wrong on the route ("07:30 cancelled · Metro minor delays"); null when all's well. */
    val note: String?,
    /** The tile's one line: "Leave by 07:32 · the 07:42" · "Leave about 07:40" · "On the 07:42 · 16 min to go". */
    val short: String,
    val spoken: String,
)

object WatchCommuteRules {
    /** From this many minutes before leaving, the label counts down ("· IN 12 MIN"). */
    const val COUNT_FROM_MIN = 60
    /** The last minutes before leaving light the row. */
    const val LIT_MIN = 10

    private fun t(min: Int) = LocalClock.formatMinute(min.mod(1440))

    /** Minutes from [nowMin] forward to [min], wrapping, but anything more than half a day ahead counts as passed (negative). */
    private fun until(nowMin: Int, min: Int): Int = (min - nowMin).mod(1440).let { if (it > 720) it - 1440 else it }

    private fun countdown(left: Int): String = when {
        left <= 0 -> " · NOW"
        left <= COUNT_FROM_MIN -> " · IN $left MIN"
        else -> ""
    }

    /**
     * The row, or null outside an office day's commute. [office] today's office window ([PlacesRules.officeWindow]),
     * [bands] its commute bands ([CommuteRules.bands]), [leave] the live leave-by ([RouteRules.leaveBy]) for this
     * commute, [route] Today's train line ([RouteRules.todayLine]), [ride] the train he's on ([TrainRideRules.ride]).
     */
    fun view(
        office: OfficeWindow?, bands: List<CommuteBand>, leave: LeaveBy?, route: RouteLine?, ride: TrainRide?,
        nowMs: Long, cal: LocalCalendar,
    ): WatchCommute? {
        office ?: return null
        val commute = RouteRules.commute(office, nowMs, cal)
        ride?.let { r ->
            val to = r.to?.let { "to $it" } ?: "to go"
            return WatchCommute(
                label = "ON THE ${t(r.depMin)}", lit = false, line = "${r.leftMin} min $to", note = null,
                short = TrainRideRules.words(r), spoken = TrainRideRules.spoken(r) + ".",
            )
        }
        commute ?: return null
        val morning = commute == RouteRules.Commute.MORNING
        val nowMin = cal.minuteOfDay(nowMs)
        val trouble = route?.takeIf { it.lit }?.text?.let { troubleNote(it, leave) }
        if (leave != null && leave.morning == morning) {
            val left = until(nowMin, leave.leaveMin)
            val dep = t(leave.train.depMin)
            val state = RouteRules.words(leave.train).removePrefix("$dep ") // "on time" · "2 min late"
            val train = "$dep · $state"
            val head = (if (morning) "LEAVE BY " else "LEAVE WORK BY ") + t(leave.leaveMin)
            val line = "For the $train" + if (leave.later) " · later than usual" else ""
            val lit = trouble != null || leave.later || left <= LIT_MIN
            val short = (if (morning) "Leave by " else "Leave work by ") + t(leave.leaveMin) + " · the " + t(leave.train.depMin)
            return WatchCommute(
                label = head + countdown(left), lit = lit, line = line, note = trouble, short = short,
                spoken = listOfNotNull(
                    (if (morning) "Leave by " else "Leave work by ") + t(leave.leaveMin) + " for the $dep, $state",
                    "later than usual".takeIf { leave.later }, trouble?.replace(" · ", ", ")?.replace(" — ", ". "),
                ).joinToString(". ") + ".",
            )
        }
        if (morning) {
            val band = bands.firstOrNull { it.toWork } ?: return null
            val left = until(nowMin, band.startMinute)
            val commuteMin = (band.endMinute - band.startMinute).mod(1440)
            val onWay = left < 0 && nowMs < band.endMs
            val label = if (onWay) "AT WORK ABOUT ${t(band.endMinute)}" else "LEAVE ABOUT ${t(band.startMinute)}" + countdown(left)
            val line = if (onWay) "On your way · ${CommuteRules.label(commuteMin)} commute"
            else "Work at ${t(band.endMinute)} · ${CommuteRules.label(commuteMin)} commute"
            return WatchCommute(
                label = label, lit = trouble != null || (!onWay && left <= LIT_MIN), line = line, note = trouble,
                short = if (onWay) "At work about ${t(band.endMinute)}" else "Leave about ${t(band.startMinute)}",
                spoken = listOfNotNull(
                    if (onWay) "At work about ${t(band.endMinute)}" else "Leave about ${t(band.startMinute)} for work at ${t(band.endMinute)}",
                    trouble?.replace(" · ", ", ")?.replace(" — ", ". "),
                ).joinToString(". ") + ".",
            )
        }
        val band = bands.firstOrNull { !it.toWork } ?: return null
        val before = nowMs < office.endMs
        val line = if (before) "Leave work at ${t(band.startMinute)}" else "On your way home"
        return WatchCommute(
            label = "HOME ABOUT ${t(band.endMinute)}", lit = trouble != null, line = line, note = trouble,
            short = CommuteRules.homeLine(band.endMinute),
            spoken = listOfNotNull(
                CommuteRules.homeLine(band.endMinute) + if (before) ", leaving work at ${t(band.startMinute)}" else "",
                trouble?.replace(" · ", ", ")?.replace(" — ", ". "),
            ).joinToString(". ") + ".",
        )
    }

    /**
     * What's wrong, from Today's lit train line: the line's own name and the leave-by's words (already in the row) are
     * dropped, so "South Rail · leave by 07:32 for the 07:42 · 07:30 cancelled · 07:42 on time" becomes "07:30
     * cancelled · 07:42 on time". A line of TfL statuses is kept whole.
     */
    fun troubleNote(text: String, leave: LeaveBy?): String {
        var s = text
        leave?.let { s = s.replace(it.words + " · ", "") }
        val name = RouteRules.route()?.let { RouteRules.name(it.firstId, it) }
        if (name != null && s.startsWith("$name · ") && s.drop(name.length + 3).firstOrNull()?.isDigit() == true) {
            s = s.drop(name.length + 3)
        }
        return s
    }
}
