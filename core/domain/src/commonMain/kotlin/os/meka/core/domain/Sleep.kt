package os.meka.core.domain

/**
 * Health Connect, slice 3 (build plan V1, "Health Connect, opt-in and off by default": sleep for energy-aware planning).
 * With Sleep switched on on the Fold, last night's sleep is read from Health Connect on the phone (never leaves it, never
 * synced) and a short night makes Plan my day lighter: fewer hours of tasks, anything due today still goes in. Pure.
 */
data class SleepSession(val startMs: Long, val endMs: Long)

object SleepRules {
    /** Under this many minutes asleep is a short night: a lighter plan. */
    const val SHORT_MIN = 6 * 60
    /** Under this many minutes asleep is a very short night: lighter still. */
    const val VERY_SHORT_MIN = 5 * 60
    /** The most task time Plan my day fills after a short night, and after a very short one. */
    const val SHORT_CAP_MIN = 3 * 60
    const val VERY_SHORT_CAP_MIN = 2 * 60
    /** What tasks left out by the cap are called under the plan. */
    const val LIGHTER = "Left for a lighter day"

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN

    /**
     * Minutes asleep last night: the sessions that ended between 18:00 the evening before [dayStartMs] (today's
     * midnight) and 14:00 today, overlaps counted once (the watch and the phone can both log a night). Null when none
     * did (nothing logged: the plan stays as usual).
     */
    fun lastNight(sessions: List<SleepSession>, dayStartMs: Long): Int? {
        val from = dayStartMs - 6 * HOUR
        val to = dayStartMs + 14 * HOUR
        val night = sessions.filter { it.endMs > it.startMs && it.endMs > from && it.endMs <= to }.sortedBy { it.startMs }
        if (night.isEmpty()) return null
        var total = 0L
        var curStart = night[0].startMs
        var curEnd = night[0].endMs
        for (s in night.drop(1)) {
            if (s.startMs <= curEnd) curEnd = maxOf(curEnd, s.endMs)
            else { total += curEnd - curStart; curStart = s.startMs; curEnd = s.endMs }
        }
        total += curEnd - curStart
        return (total / MIN).toInt()
    }

    /** The most task minutes to plan after [minutes] asleep; null for an ordinary night or none logged. */
    fun taskCap(minutes: Int?): Int? = when {
        minutes == null -> null
        minutes < VERY_SHORT_MIN -> VERY_SHORT_CAP_MIN
        minutes < SHORT_MIN -> SHORT_CAP_MIN
        else -> null
    }

    /** "5 h 10 m" */
    fun hm(minutes: Int): String = "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} m"

    /** The note under the plan after a short night, or null. */
    fun planNote(minutes: Int?): String? {
        val cap = taskCap(minutes) ?: return null
        return "☾ Short night · ${hm(minutes!!)} asleep: a lighter plan, up to ${cap / 60} hours of tasks. Anything due today still goes in."
    }

    /** The Sleep switch's state on the Fold (the same steps as Workouts). */
    fun settingLine(access: WorkoutRules.Access, minutes: Int?): String = when (access) {
        WorkoutRules.Access.UNAVAILABLE -> "Health Connect isn't on this phone"
        WorkoutRules.Access.OFF -> "Off · turn on and after a short night (under 6 h) Plan my day plans a lighter day. Read on this phone only."
        WorkoutRules.Access.NEEDS_PERMISSION -> "Allow Navilife to read sleep in Health Connect"
        WorkoutRules.Access.ON -> when {
            minutes == null -> "On · no sleep logged last night, so today's plan is as usual. Read on this phone only."
            taskCap(minutes) != null -> "On · last night ${hm(minutes)} · a lighter plan today. Read on this phone only."
            else -> "On · last night ${hm(minutes)}. Read on this phone only."
        }
    }
}
