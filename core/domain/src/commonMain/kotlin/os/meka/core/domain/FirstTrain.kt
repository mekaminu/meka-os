package os.meka.core.domain

/**
 * Done for the day, the first train (2026-10-18): the evening before an office day, Shut down's Tomorrow names the
 * train he usually takes and when to leave for it: "First train · the 07:42 · leave by 07:32 · on time the last 4
 * times". Tomorrow's live board doesn't exist yet in the evening (it reaches about two hours ahead), so the train is
 * the one the morning leave-by ([RouteRules.leaveBy]) picked on his recent office mornings, kept on this device
 * ([KEY], never synced, like [TrainRideRules.KEY]) with how it ran when last seen before it left.
 */
data class MorningTrain(
    val day: Long,
    /** Its booked minute of the day. */
    val depMin: Int,
    /** Minutes late when last seen (0 on time), [CANCELLED], or null when it was delayed with no estimate. */
    val late: Int?,
) {
    companion object {
        const val CANCELLED = -1
    }
}

/** What Shut down's Tomorrow says: [text] on screen, [spoken] for the screen reader, [lit] when it often runs late. */
data class FirstTrainLine(val text: String, val spoken: String, val lit: Boolean, val depMin: Int)

/** Non-AI, pure. */
object FirstTrainRules {
    /** The device-local value (never synced) that keeps the recent morning trains across restarts. */
    const val KEY = "morning_trains"
    /** Mornings kept. */
    const val KEEP = 15
    /** Only mornings this recent count. */
    const val LOOKBACK_DAYS = 28
    /** The last mornings the usual train is picked from. */
    const val PICK_FROM = 5
    /** A remembered train further than this from tomorrow's usual time (a one-off commute, new hours) isn't named. */
    const val NEAR_MIN = 20
    /** How many times it must have been seen before the line says how it has run. */
    const val TRACK_MIN = 3

    /** "20381:462:0;20382:462:x;20383:462:" — day, booked minute, lateness ("x" cancelled, blank unknown). */
    fun encode(list: List<MorningTrain>): String = list.joinToString(";") { t ->
        "${t.day}:${t.depMin}:" + when (t.late) {
            null -> ""
            MorningTrain.CANCELLED -> "x"
            else -> t.late.toString()
        }
    }

    fun decode(text: String?): List<MorningTrain> = text.orEmpty().split(';').mapNotNull { part ->
        val f = part.split(':').takeIf { it.size == 3 } ?: return@mapNotNull null
        val day = f[0].toLongOrNull() ?: return@mapNotNull null
        val dep = f[1].toIntOrNull()?.takeIf { it in 0 until 1440 } ?: return@mapNotNull null
        val late = when (f[2]) {
            "" -> null
            "x" -> MorningTrain.CANCELLED
            else -> f[2].toIntOrNull()?.takeIf { it in 0..600 } ?: return@mapNotNull null
        }
        MorningTrain(day, dep, late)
    }.sortedBy { it.day }.takeLast(KEEP)

    /**
     * [history] after this morning's seen board: when [seen] is a morning ([TrainBoard.AM]) with his train named
     * ([TrainRideSeen.yours]), that day's entry is that train as last seen (a later read replaces it the same morning).
     */
    fun record(history: List<MorningTrain>, seen: TrainRideSeen?): List<MorningTrain> {
        val s = seen?.takeIf { it.dir == TrainBoard.AM } ?: return history
        val dep = s.yours ?: return history
        val d = s.departures.firstOrNull { it.depMin == dep }
        val late = when {
            d == null -> null
            d.cancelled -> MorningTrain.CANCELLED
            else -> d.lateMin
        }
        val entry = MorningTrain(s.day, dep, late)
        if (history.any { it == entry }) return history
        return (history.filterNot { it.day == s.day } + entry).sortedBy { it.day }.takeLast(KEEP)
    }

    /**
     * Tomorrow's line, or null: [tomorrow] is an office day whose commute leaves home at [leaveHomeMin] (null: not an
     * office day, so nothing); the train is the most common one of the last [PICK_FROM] recent mornings that sit near
     * tomorrow's usual train ([leaveHomeMin] plus [walkMin], when the time to the train is known), ties to the latest.
     * With [walkMin] it says when to leave; with [TRACK_MIN]+ sightings of that train, how it has run.
     */
    fun line(history: List<MorningTrain>, tomorrow: Long, leaveHomeMin: Int?, walkMin: Int?): FirstTrainLine? {
        val leaveHome = leaveHomeMin ?: return null
        val expect = walkMin?.let { (leaveHome + it).mod(1440) }
        val recent = history.filter { it.day < tomorrow && it.day >= tomorrow - LOOKBACK_DAYS }
            .filter { t -> expect == null || near(t.depMin, expect) }
        if (recent.isEmpty()) return null
        val last = recent.sortedBy { it.day }.takeLast(PICK_FROM)
        val dep = last.groupBy { it.depMin }.maxWith(compareBy<Map.Entry<Int, List<MorningTrain>>> { it.value.size }.thenBy { e -> e.value.maxOf { it.day } }).key
        val runs = recent.filter { it.depMin == dep && it.late != null }.sortedBy { it.day }.takeLast(10)
        val bad = runs.count { it.late == MorningTrain.CANCELLED || it.late!! >= RouteRules.LATE_MIN }
        val track = if (runs.size >= TRACK_MIN) {
            if (bad == 0) "on time the last ${runs.size} times" else "late or cancelled $bad of the last ${runs.size} times"
        } else null
        val t = LocalClock.formatMinute(dep)
        val leave = walkMin?.let { LocalClock.formatMinute((dep - it).mod(1440)) }
        val text = listOfNotNull("First train · the $t", leave?.let { "leave by $it" }, track).joinToString(" · ")
        val spoken = "Your first train tomorrow is usually the $t" + (leave?.let { "; leave by $it" }.orEmpty()) +
            (track?.let { "; it's been $it" }.orEmpty()) + "."
        return FirstTrainLine(text, spoken, lit = runs.size >= TRACK_MIN && bad * 2 >= runs.size, depMin = dep)
    }

    private fun near(a: Int, b: Int): Boolean = minOf((a - b).mod(1440), (b - a).mod(1440)) <= NEAR_MIN
}
