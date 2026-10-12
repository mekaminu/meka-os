package os.meka.core.domain

/**
 * Health Connect, slice 1 (build plan V1, "Health Connect, opt-in and off by default"; Gym's "it ticks itself from a
 * Galaxy Watch workout"): a workout the phone's Health Connect holds ticks the habit it matches. Off by default and
 * switched on on the Fold only; the workouts are read on the phone and never leave it. Only the tick (the habit's
 * completion, with a one-line note) syncs, and each tick is an automatic action in Activity with Undo.
 */
enum class WorkoutKind(val noun: String) {
    GYM("workout"), RUN("run"), WALK("walk"), CYCLE("ride"), SWIM("swim"), YOGA("yoga session"), FOOTBALL("football"), OTHER("workout"),
}

/** One exercise session from Health Connect: its id there, what kind it was, when it started and ended. */
data class Workout(val id: String, val kind: WorkoutKind, val startMs: Long, val endMs: Long) {
    val minutes: Int get() = ((endMs - startMs) / 60_000L).toInt().coerceAtLeast(0)
}

/** What [WorkoutRules.ticks] needs of a habit: [answeredToday] when today already has an answer (ticked, missed or undone). */
data class WorkoutHabit(val id: String, val title: String, val minutes: Int, val booked: Boolean, val answeredToday: Boolean)

/** A habit to tick from [workoutId]. */
data class WorkoutTick(val habitId: String, val habitTitle: String, val workoutId: String, val kind: WorkoutKind, val minutes: Int)

/** A tick that was made: Activity's entry ([activityId], undoable there) and the line the Fold shows. */
data class WorkoutTickDone(val habitId: String, val line: String, val activityId: String)

object WorkoutRules {
    /** Activity's "Why". */
    const val WHY = "Health Connect is on: a workout today matched the habit"
    /** Activity's source (its "Done for you" filter). */
    const val SOURCE = "health"
    /** How often the Fold reads Health Connect while MEKA is in front. */
    const val READ_EVERY_MS = 5 * 60_000L

    private val KEYWORDS: Map<WorkoutKind, List<String>> = mapOf(
        WorkoutKind.GYM to listOf("gym", "weights", "lift", "strength", "hiit", "circuit", "crossfit"),
        WorkoutKind.RUN to listOf("run", "jog", "5k", "10k", "parkrun"),
        WorkoutKind.WALK to listOf("walk", "hike"),
        WorkoutKind.CYCLE to listOf("cycle", "cycling", "bike", "ride", "spin"),
        WorkoutKind.SWIM to listOf("swim"),
        WorkoutKind.YOGA to listOf("yoga", "stretch", "pilates", "mobility"),
        WorkoutKind.FOOTBALL to listOf("football", "five-a-side", "5-a-side", "soccer"),
    )
    private val GENERIC = listOf("workout", "exercise", "training", "fitness")

    /**
     * How well a [kind] of workout fits a habit: 2 when the title names that kind ("Gym" for strength training, "Run"
     * for a run) or the habit is a booked session and the workout is a gym-type one; 1 for a generic title ("Exercise",
     * "Workout") and any workout but a walk; null when it doesn't fit.
     */
    fun fit(kind: WorkoutKind, title: String, booked: Boolean): Int? {
        // Split by hand, not with a regex (Kotlin/Native's engine has read patterns differently before).
        val words = title.lowercase().map { if (it.isLetterOrDigit() || it == '-') it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }
        fun names(list: List<String>) = list.any { k -> words.any { w -> w == k || (k.length >= 3 && w.startsWith(k)) } }
        val own = KEYWORDS[kind]
        if (own != null && names(own)) return 2
        if (booked && (kind == WorkoutKind.GYM || kind == WorkoutKind.OTHER)) return 2
        val namesOther = KEYWORDS.any { (k, list) -> k != kind && names(list) }
        if (!namesOther && names(GENERIC) && kind != WorkoutKind.WALK) return 1
        return null
    }

    /** The shortest workout that counts for a habit of [habitMinutes]: half of it, between 15 and 30 minutes. */
    fun enough(habitMinutes: Int): Int = (habitMinutes / 2).coerceIn(15, 30)

    /**
     * Which habits today's [workouts] tick: only workouts that ended today ([dayOf] their end is [today]) and before
     * [nowMs]; each habit at most once and only while today has no answer yet (a tick taken back, a "Didn't go" or a
     * hand tick is never overridden); each workout ticks at most one habit, the best fit, then the earliest workout.
     */
    fun ticks(workouts: List<Workout>, habits: List<WorkoutHabit>, today: Long, nowMs: Long, dayOf: (Long) -> Long): List<WorkoutTick> {
        val open = habits.filter { !it.answeredToday }.toMutableList()
        val out = mutableListOf<WorkoutTick>()
        for (w in workouts.filter { it.endMs <= nowMs && it.endMs > it.startMs && dayOf(it.endMs) == today }.sortedBy { it.startMs }) {
            val best = open.mapNotNull { h -> fit(w.kind, h.title, h.booked)?.takeIf { w.minutes >= enough(h.minutes) }?.let { h to it } }
                .maxByOrNull { it.second }?.first ?: continue
            open.remove(best)
            out += WorkoutTick(best.id, best.title, w.id, w.kind, w.minutes)
        }
        return out
    }

    /** "52-min workout" */
    fun what(t: WorkoutTick): String = "${t.minutes}-min ${t.kind.noun}"

    /** The session's note: "52-min workout · from Health Connect". */
    fun note(t: WorkoutTick): String = "${what(t)} · from Health Connect"

    /** Activity's summary and the Fold's line: "Ticked Gym · 52-min workout". */
    fun summary(t: WorkoutTick): String = "Ticked ${t.habitTitle} · ${what(t)}"

    /** The switch's state on the Fold. */
    enum class Access { UNAVAILABLE, OFF, NEEDS_PERMISSION, ON }

    /** The line under Goals' "Workouts" switch. */
    fun settingLine(access: Access, habitTitles: List<String>): String = when (access) {
        Access.UNAVAILABLE -> "Health Connect isn't on this phone"
        Access.OFF -> "Off · turn on and a workout your watch logs ticks the habit it matches. Read on this phone only."
        Access.NEEDS_PERMISSION -> "Allow MEKA to read exercise in Health Connect"
        Access.ON -> when {
            habitTitles.isEmpty() -> "On · add a habit like Gym or Run and its workouts tick it"
            else -> "On · workouts tick ${andList(habitTitles.take(3))}. Read on this phone only."
        }
    }

    /** The habits a workout could tick (for [settingLine]). */
    fun tickable(habits: List<WorkoutHabit>): List<String> =
        habits.filter { h -> WorkoutKind.entries.any { fit(it, h.title, h.booked) != null } }.map { it.title }

    private fun andList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " or " + items.last()
    }
}
