package os.meka.core.domain

import os.meka.core.sync.EntitySnapshot
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Goals and habits (build plan M1, ADR-008 "MVP-light"). No AI: progress, pace and streaks are counted, never guessed.
 *
 * - A Habit has a weekly target (1–7 times, Monday to Sunday), a preferred time of day and a length in minutes.
 *   Each tick is a `habit_completion` with the id `<habitId>.d<epochDay>`, so ticking the same day on the Fold and the
 *   Mac while both are offline ends up as one tick, and unticking is just `done = false`.
 * - Pace, daily habits: by the end of each day you should have done that day (pro rata from the day the habit was
 *   added). Behind = a day missed; Due = today's go keeps you on pace.
 * - Pace, N-a-week habits (Fold review 2026-10-09 13:45, item 1): days off are never misses. On track while the week
 *   can still be met with a spare day; Due once the days left (today included) are no more than one more than the goes
 *   left ("1 left — 2 days to go"); Behind only once the week can no longer be met.
 * - Streaks: a daily habit counts days in a row; any other target counts weeks in a row that met it.
 * - A Goal's progress is counted from what's linked to it (tasks done, habits' share of this week, goals under it, its
 *   milestones ticked), or set by hand when nothing is linked. A numeric goal ("Save £2,000") takes its progress from
 *   its number instead ([GoalMetricRules]).
 * - The planner makes room for habits that are behind or due today ([plannerHabits]).
 */

enum class HabitTiming { MORNING, AFTERNOON, EVENING, ANYTIME }

/** Where a habit stands this week, most pressing last. */
enum class HabitPace { DONE_TODAY, WEEK_MET, ON_TRACK, DUE, BEHIND }

data class HabitItem(
    val id: String,
    val title: String,
    val targetPerWeek: Int,
    val timing: HabitTiming,
    val minutes: Int,
    val goalId: String?,
    /** Ticks this week (Monday to Sunday). */
    val doneThisWeek: Int,
    /** This week's target, pro rata in the week the habit was added. */
    val weekTarget: Int,
    val doneToday: Boolean,
    val pace: HabitPace,
    val streak: Int,
    /** "day" for a daily habit, else "week". */
    val streakUnit: String,
    /** Monday to Sunday of this week: true where ticked. */
    val week: List<Boolean>,
    /** "2 of 3 this week", "Behind · 1 of 5 this week", "Done today". */
    val meta: String,
    /** "12-day streak", "3-week streak"; null below 2. */
    val streakLine: String?,
    val hasConflict: Boolean,
    /** Gym: MEKA books this habit's sessions into the week ([SessionRules]); the planner then doesn't place it. */
    val booked: Boolean = false,
    /** Gym: the rotating labels (Push · Pull · Legs); empty for none. */
    val rotation: List<String> = emptyList(),
    /** Gym: "Booked Thu 17:45 · Sat 10:00", "Week done", "No room left this week" (set from [SessionsView]). */
    val sessionLine: String? = null,
    /** Gym: the workout app's link, opened from Today's card ([SessionRules.cleanAppLink]); null for none. */
    val appLink: String? = null,
) {
    /** "Hevy"; null without a link. */
    val appName: String? get() = appLink?.let { SessionRules.appName(it) }
    val needsRoomToday: Boolean get() = pace == HabitPace.BEHIND || pace == HabitPace.DUE
    /** N a week (not every day): shown as the week's goes, never as a daily strip (Fold review 2026-10-09 13:45). */
    val weekly: Boolean get() = targetPerWeek < 7
    /** Goes filled of [weekTarget] this week (the slots: two rings, one filled). */
    val slotsFilled: Int get() = doneThisWeek.coerceIn(0, weekTarget)
    /** "1/2" on Today's chip for an N-a-week habit; null for a daily one. */
    val countLabel: String? get() = if (weekly) "$slotsFilled/$weekTarget" else null
    /** "Push · Pull · Legs", "No rotation". */
    val rotationLabel: String get() = SessionRules.rotationLabel(rotation)
}

data class GoalItem(
    val id: String,
    val title: String,
    /** What "done" looks like ("Run a half marathon in under 2 h"). */
    val target: String?,
    val horizon: GoalHorizon,
    val progressPct: Int,
    /** True when progress comes from linked tasks and habits; false when it is set by hand. */
    val counted: Boolean,
    val tasksDone: Int,
    val tasksTotal: Int,
    val habitCount: Int,
    val done: Boolean,
    /** "This month · by Sat 31 Oct · 3 of 5 tasks · 2 habits", "This year · set by hand". */
    val meta: String,
    val hasConflict: Boolean,
    /** Why it matters, in Meka's words. */
    val why: String? = null,
    /** Local epoch day it should be done by. */
    val dueDay: Long? = null,
    /** The bigger goal it sits under (only while that goal is open), and its title. */
    val parentId: String? = null,
    val parentTitle: String? = null,
    /** 0 at the top; 1 under a goal; 2 under one of those… The list shows each goal right after its parent. */
    val depth: Int = 0,
    /** Open goals under this one. */
    val childCount: Int = 0,
    /** The next step: the first open task linked to it (planned or due first, then oldest). */
    val nextStepTaskId: String? = null,
    val nextStep: String? = null,
    /** Its milestones, oldest first (Goals, properly, slice 2). */
    val milestones: List<GoalMilestone> = emptyList(),
    /** A numeric goal's number ("£800 of £2,000"), its chart and updates (slice 3); null for an ordinary goal. */
    val metric: GoalMetric? = null,
    /**
     * The last day (local epoch day) anything moved it on (Goals, properly, slice 7): a linked task done, a milestone
     * ticked, a number update, a tick of a linked habit (or of the habit feeding its number), a goal under it moving,
     * or the goal finished. Null when nothing has yet.
     */
    val lastMovedDay: Long? = null,
    /** The day (local epoch day) it was added, and the day it was finished (Goals, properly, item 6: the rhythm). */
    val createdDay: Long? = null,
    val doneDay: Long? = null,
) {
    val milestonesDone: Int get() = milestones.count { it.done }
    /** The next step is a milestone (no open task is linked), not a task. */
    val nextIsMilestone: Boolean get() = nextStep != null && nextStepTaskId == null
    /** "Next · Buy running shoes", "Next milestone · Run 10k", "No next step yet · add one". */
    val nextLine: String get() = when {
        nextStep == null -> "No next step yet · add one"
        nextIsMilestone -> "Next milestone · $nextStep"
        else -> "Next · $nextStep"
    }
}

/** A milestone on the way to a goal: "Run 10k", ticked when reached. */
data class GoalMilestone(val id: String, val goalId: String, val title: String, val done: Boolean)

data class GoalsView(
    /** Behind first, then due today, then the rest; done-today and week-met last. */
    val habits: List<HabitItem>,
    /** Open goals, short horizon first; finished goals are kept but not listed. */
    val goals: List<GoalItem>,
    val finishedGoals: Int,
    /** This week's goals: Today's "This week: 2 of 3 goals moving" and the watch face's goal ring (slice 7). */
    val week: WeekGoals = WeekGoals.EMPTY,
    /** Last month looked back on, for the first week of a month ([GoalRhythmRules.month]); null otherwise. */
    val month: GoalMonth? = null,
) {
    val behind: Int get() = habits.count { it.pace == HabitPace.BEHIND }
    val dueToday: Int get() = habits.count { it.pace == HabitPace.DUE }
    val doneToday: Int get() = habits.count { it.doneToday }
    /** "Add Gym" is offered (no booked habit, and none already named gym/workout/training). */
    val offersAddGym: Boolean get() = SessionRules.offersAddGym(habits)
    /** The hand-made gym habit to offer "Let MEKA book “gym”" on; null when there is none or a habit is already booked. */
    val bookOffer: HabitItem? get() = SessionRules.bookOffer(habits)

    /** "1 habit behind · 2 to do today"; "All habits on track" or null with no habits. */
    val paceLine: String? get() {
        if (habits.isEmpty()) return null
        val parts = listOfNotNull(
            behind.takeIf { it > 0 }?.let { "$it ${if (it == 1) "habit" else "habits"} behind" },
            dueToday.takeIf { it > 0 }?.let { "$it to do today" },
        )
        return parts.joinToString(" · ").ifEmpty { "All habits on track" }
    }

    /** The habits with each booked one's week line from [sessions]. */
    fun withSessions(sessions: SessionsView): GoalsView =
        if (sessions.lines.isEmpty()) this else copy(habits = habits.map { h -> sessions.lines[h.id]?.let { h.copy(sessionLine = it) } ?: h })

    companion object {
        val EMPTY = GoalsView(emptyList(), emptyList(), 0)
    }
}

/** A choice offered by the pickers. */
data class TargetChoice(val label: String, val perWeek: Int)

object GoalRules {
    val TARGET_CHOICES = listOf(
        TargetChoice("Every day", 7), TargetChoice("5 a week", 5), TargetChoice("4 a week", 4),
        TargetChoice("3 a week", 3), TargetChoice("Twice a week", 2), TargetChoice("Once a week", 1),
    )
    val TIMINGS: List<HabitTiming> = enumValues<HabitTiming>().toList()
    val HORIZONS: List<GoalHorizon> = enumValues<GoalHorizon>().toList()
    val MINUTE_CHOICES = listOf(5, 10, 15, 30, 45, 60, 90)
    const val DEFAULT_MINUTES = 15
    /** The longest "why it matters" kept. */
    const val MAX_WHY = 1_000
    /** At most this many milestones on one goal; the longest milestone title. */
    const val MAX_MILESTONES = 12
    const val MAX_MILESTONE_TITLE = 120

    /** The progress ring's sweep in degrees (0–360) for [pct]. */
    fun ringSweep(pct: Int): Float = pct.coerceIn(0, 100) * 3.6f

    /** Index-based access for Swift, which then never needs to name the cases. */
    fun horizonAt(index: Int): GoalHorizon = HORIZONS[index.coerceIn(0, HORIZONS.size - 1)]
    fun timingAt(index: Int): HabitTiming = TIMINGS[index.coerceIn(0, TIMINGS.size - 1)]

    fun targetLabel(perWeek: Int): String = TARGET_CHOICES.firstOrNull { it.perWeek == perWeek }?.label ?: "$perWeek a week"

    fun timingLabel(t: HabitTiming): String = when (t) {
        HabitTiming.MORNING -> "Morning"
        HabitTiming.AFTERNOON -> "Afternoon"
        HabitTiming.EVENING -> "Evening"
        HabitTiming.ANYTIME -> "Any time"
    }

    fun horizonLabel(h: GoalHorizon): String = when (h) {
        GoalHorizon.WEEK -> "This week"
        GoalHorizon.MONTH -> "This month"
        GoalHorizon.QUARTER -> "This quarter"
        GoalHorizon.YEAR -> "This year"
        GoalHorizon.SOMEDAY -> "Someday"
    }

    /** A stored horizon; SHORT, MEDIUM and LONG (before 2026-10-11) read as month, quarter and year. */
    fun parseHorizon(stored: String?): GoalHorizon = when (stored) {
        null -> GoalHorizon.MONTH
        "SHORT" -> GoalHorizon.MONTH
        "MEDIUM" -> GoalHorizon.QUARTER
        "LONG" -> GoalHorizon.YEAR
        else -> enumValues<GoalHorizon>().firstOrNull { it.name == stored } ?: GoalHorizon.MONTH
    }

    /** The last day of [h]'s period containing [today] (Sunday, month end, quarter end, 31 Dec); null for someday. */
    fun horizonEnd(h: GoalHorizon, today: Long): Long? {
        val d = CivilDate.fromEpochDay(today)
        return when (h) {
            GoalHorizon.WEEK -> today + (7 - CivilDate.isoDayOfWeek(today))
            GoalHorizon.MONTH -> CivilDate.toEpochDay(d.year, d.month, CivilDate.lengthOfMonth(d.year, d.month))
            GoalHorizon.QUARTER -> {
                val m = ((d.month - 1) / 3) * 3 + 3
                CivilDate.toEpochDay(d.year, m, CivilDate.lengthOfMonth(d.year, m))
            }
            GoalHorizon.YEAR -> CivilDate.toEpochDay(d.year, 12, 31)
            GoalHorizon.SOMEDAY -> null
        }
    }

    /** A due-date choice: "End of this week · Sun 18 Oct" and its day (null: "No date"). */
    data class DueChoice(val label: String, val day: Long?)

    /** The due-date chips: no date, then the end of this week, month, quarter and year. */
    fun dueChoices(today: Long): List<DueChoice> =
        listOf(DueChoice("No date", null)) + listOf(
            GoalHorizon.WEEK to "End of week", GoalHorizon.MONTH to "End of month",
            GoalHorizon.QUARTER to "End of quarter", GoalHorizon.YEAR to "End of year",
        ).map { (h, label) -> horizonEnd(h, today)!!.let { DueChoice("$label · ${CivilDate.shortLabel(it)}", it) } }

    /** "by Sat 31 Oct"; "overdue · was Sat 31 Oct" once the day has passed. */
    fun dueLabel(day: Long, today: Long): String =
        if (day < today) "overdue · was ${CivilDate.shortLabel(day)}" else "by ${CivilDate.shortLabel(day)}"

    /**
     * Goals [goalId] may sit under: open goals that aren't it or anything under it, longest horizon first. A goal sits
     * under a bigger one (year → month → week), so a goal with a shorter horizon than this one isn't offered.
     */
    fun parentChoices(goals: List<GoalItem>, goalId: String): List<GoalItem> {
        val me = goals.firstOrNull { it.id == goalId } ?: return emptyList()
        val below = descendants(goals, goalId) + goalId
        return goals.filter { it.id !in below && it.horizon.ordinal >= me.horizon.ordinal }
            .sortedWith(compareByDescending<GoalItem> { it.horizon.ordinal }.thenBy { it.title.lowercase() })
    }

    private fun descendants(goals: List<GoalItem>, id: String): Set<String> {
        val out = mutableSetOf<String>()
        var frontier = setOf(id)
        while (frontier.isNotEmpty()) {
            frontier = goals.filter { it.parentId in frontier && it.id !in out }.map { it.id }.toSet()
            out += frontier
        }
        return out
    }

    /**
     * "Link existing task": open tasks not already linked to [goalId] whose title holds [query] (all when blank),
     * planned or due first, at most [limit]. A task linked to another goal is offered too (linking moves it).
     */
    fun linkCandidates(tasks: List<Task>, goalId: String, query: String, limit: Int = 6): List<Task> {
        val q = query.trim().lowercase()
        return tasks.asSequence()
            .filter { it.lifecycle == Lifecycle.ACTIVE && it.goalId != goalId }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) }
            .sortedWith(stepOrder)
            .take(limit).toList()
    }

    /** Planned or due first (soonest first), then the oldest. */
    internal val stepOrder: Comparator<Task> = compareBy<Task> { it.scheduledAtMs ?: it.dueAtMs ?: Long.MAX_VALUE }
        .thenBy { it.createdAtMs }.thenBy { it.id }

    /** Local minutes of the day a timing prefers, as [start, end). */
    fun timingWindow(t: HabitTiming): IntRange = when (t) {
        HabitTiming.MORNING -> 6 * 60 until 12 * 60
        HabitTiming.AFTERNOON -> 12 * 60 until 17 * 60
        HabitTiming.EVENING -> 17 * 60 until 22 * 60
        HabitTiming.ANYTIME -> 0 until 24 * 60
    }

    /** Monday of the ISO week [day] is in. */
    fun weekStart(day: Long): Long = day - (CivilDate.isoDayOfWeek(day) - 1)

    /** This week's target, pro rata from [createdDay] when the habit was added this week. */
    fun weekTarget(target: Int, today: Long, createdDay: Long): Int {
        val ws = weekStart(today)
        val start = maxOf(ws, minOf(createdDay, today))
        val span = (ws + 7 - start).toInt()
        return minOf(target, (target * span + 6) / 7)
    }

    /** Where a habit stands today (see the file comment). */
    fun pace(target: Int, doneThisWeek: Int, doneToday: Boolean, today: Long, createdDay: Long): HabitPace {
        if (target < 7) return weeklyPace(target, doneThisWeek, doneToday, today, createdDay)
        val ws = weekStart(today)
        val start = maxOf(ws, minOf(createdDay, today))
        val span = (ws + 7 - start).toInt()
        val eff = weekTarget(target, today, createdDay)
        val elapsed = (today - start).toInt()
        val expectedBefore = (eff * elapsed + span - 1) / span
        val expectedByToday = (eff * (elapsed + 1) + span - 1) / span
        return when {
            doneToday -> HabitPace.DONE_TODAY
            doneThisWeek >= eff -> HabitPace.WEEK_MET
            doneThisWeek < expectedBefore -> HabitPace.BEHIND
            doneThisWeek < expectedByToday -> HabitPace.DUE
            else -> HabitPace.ON_TRACK
        }
    }

    /** Days left in the week, today included (Monday 7 … Sunday 1). */
    fun daysLeft(today: Long): Int = (weekStart(today) + 7 - today).toInt()

    /** Goes still needed this week (never below 0). */
    fun goesLeft(target: Int, doneThisWeek: Int, today: Long, createdDay: Long): Int =
        maxOf(0, weekTarget(target, today, createdDay) - doneThisWeek)

    /** N-a-week habits: slack-based, so a day off is never a miss (see the file comment). */
    fun weeklyPace(target: Int, doneThisWeek: Int, doneToday: Boolean, today: Long, createdDay: Long): HabitPace {
        val left = goesLeft(target, doneThisWeek, today, createdDay)
        val days = daysLeft(today)
        return when {
            doneToday -> HabitPace.DONE_TODAY
            left == 0 -> HabitPace.WEEK_MET
            left > days -> HabitPace.BEHIND
            left >= days - 1 -> HabitPace.DUE
            else -> HabitPace.ON_TRACK
        }
    }

    /** "1 left — 2 days to go", "2 left — 1 day to go". */
    fun leftLine(left: Int, days: Int): String = "$left left — $days ${if (days == 1) "day" else "days"} to go"

    /**
     * Daily habits: days in a row up to today (or yesterday, while today is still open). Other targets: weeks in a
     * row that met the target, counting this week once it is met. The week the habit was added counts pro rata.
     */
    fun streak(target: Int, doneDays: Set<Long>, today: Long, createdDay: Long): Int {
        if (target >= 7) {
            var d = if (today in doneDays) today else today - 1
            var n = 0
            while (d in doneDays && n < MAX_STREAK) { n++; d-- }
            return n
        }
        val thisWeek = weekStart(today)
        fun met(ws: Long): Boolean {
            if (ws + 6 < createdDay) return false
            val need = weekTarget(target, ws + 6, createdDay)
            return (0..6).count { (ws + it) in doneDays } >= need
        }
        var n = if (met(thisWeek)) 1 else 0
        var ws = thisWeek - 7
        while (met(ws) && n < MAX_STREAK) { n++; ws -= 7 }
        return n
    }

    fun habitMeta(pace: HabitPace, done: Int, weekTarget: Int, target: Int, today: Long? = null): String =
        if (target < 7 && today != null) weeklyMeta(pace, done, weekTarget, today) else when (pace) {
        HabitPace.DONE_TODAY -> if (target >= 7) "Done today" else "Done today · $done of $weekTarget this week"
        HabitPace.WEEK_MET -> "Week done · $done of $weekTarget"
        HabitPace.BEHIND -> "Behind · $done of $weekTarget this week"
        HabitPace.DUE -> if (target >= 7) "To do today" else "Today keeps you on pace · $done of $weekTarget"
        HabitPace.ON_TRACK -> "On track · $done of $weekTarget this week"
    }

    /** N-a-week habits: never "Behind"; the goes left and the days to go once the week is tight. */
    fun weeklyMeta(pace: HabitPace, done: Int, weekTarget: Int, today: Long): String = when (pace) {
        HabitPace.DONE_TODAY -> "Done today · $done of $weekTarget this week"
        HabitPace.WEEK_MET -> "Week done · $done of $weekTarget"
        HabitPace.DUE, HabitPace.BEHIND -> leftLine(maxOf(0, weekTarget - done), daysLeft(today))
        HabitPace.ON_TRACK -> "$done of $weekTarget this week"
    }

    fun streakLine(streak: Int, unit: String): String? = if (streak < 2) null else "$streak-$unit streak"

    const val MAX_STREAK = 3660
}

/** A habit the planner should make room for today. */
data class PlannerHabit(val id: String, val title: String, val minutes: Int, val timing: HabitTiming, val behind: Boolean)

/** Goals and habits commands and the [view] projection over a [Replica]. Every write is an op: offline-first, synced. */
class Goals(
    private val replica: Replica,
    private val ids: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    private fun today(): Long = calendar.epochDayOf(nowMs())

    // ---- Habits ----

    fun addHabit(
        title: String,
        perWeek: Int = 7,
        timing: HabitTiming = HabitTiming.ANYTIME,
        minutes: Int = GoalRules.DEFAULT_MINUTES,
        goalId: String? = null,
    ): String {
        val t = cleanTitle(title, "Name the habit")
        checkTarget(perWeek); checkMinutes(minutes)
        goalId?.let(::requireGoal)
        val id = ids()
        val fields = linkedMapOf<String, FieldValue>(
            ActionableFields.TITLE to t.fv(),
            ActionableFields.LIFECYCLE to Lifecycle.ACTIVE.name.fv(),
            HabitFields.TARGET_PER_WEEK to perWeek.fv(),
            HabitFields.PREFERRED_TIMING to timing.name.fv(),
            HabitFields.MINUTES to minutes.fv(),
            ActionableFields.CREATED_AT to nowMs().fv(),
            ActionableFields.VISIBILITY to Visibility.PRIVATE.name.fv(),
            ActionableFields.PROVENANCE_SOURCE to "user".fv(),
            ActionableFields.PROVENANCE_TRUST to Trust.TRUSTED_USER.name.fv(),
        )
        goalId?.let { fields[ActionableFields.GOAL_ID] = it.fv() }
        replica.commitLocal(EntityTypes.HABIT, id, fields)
        return id
    }

    fun editHabit(
        id: String, title: String? = null, perWeek: Int? = null, timing: HabitTiming? = null, minutes: Int? = null,
    ) {
        requireHabit(id)
        val changes = linkedMapOf<String, FieldValue>()
        title?.let { changes[ActionableFields.TITLE] = cleanTitle(it, "Name the habit").fv() }
        perWeek?.let { checkTarget(it); changes[HabitFields.TARGET_PER_WEEK] = it.fv() }
        timing?.let { changes[HabitFields.PREFERRED_TIMING] = it.name.fv() }
        minutes?.let { checkMinutes(it); changes[HabitFields.MINUTES] = it.fv() }
        if (changes.isNotEmpty()) replica.commitLocal(EntityTypes.HABIT, id, changes)
    }

    /** Links a habit to a goal (null unlinks). */
    fun setHabitGoal(id: String, goalId: String?) {
        requireHabit(id)
        goalId?.let(::requireGoal)
        replica.commitLocal(EntityTypes.HABIT, id, mapOf(ActionableFields.GOAL_ID to (goalId?.fv() ?: FieldValue.Null)))
    }

    /** Ticks (or unticks) a habit for a local day, today by default. Days in the future can't be ticked. */
    fun setHabitDone(id: String, done: Boolean, day: Long = today()) {
        requireHabit(id)
        if (day > today()) throw ValidationException("That day hasn't happened yet")
        replica.commitLocal(
            EntityTypes.HABIT_COMPLETION, completionId(id, day),
            linkedMapOf(
                HabitCompletionFields.HABIT_ID to id.fv(),
                HabitCompletionFields.DAY to day.fv(),
                HabitCompletionFields.DONE to done.fv(),
                HabitCompletionFields.AT to nowMs().fv(),
            ),
        )
    }

    /** Gym: MEKA books (or stops booking) this habit's sessions into the week. */
    fun setHabitBooked(id: String, on: Boolean) {
        requireHabit(id)
        replica.commitLocal(EntityTypes.HABIT, id, mapOf(HabitFields.BOOK_SLOTS to on.fv()))
    }

    /** Gym: the rotating labels ([SessionRules.ROTATIONS]); empty for none. Labels are trimmed, at most 7 of 24 characters. */
    fun setHabitRotation(id: String, labels: List<String>) {
        requireHabit(id)
        val clean = SessionRules.cleanRotation(labels)
        replica.commitLocal(EntityTypes.HABIT, id, mapOf(HabitFields.ROTATION to (clean.takeIf { it.isNotEmpty() }?.joinToString("|")?.fv() ?: FieldValue.Null)))
    }

    /**
     * Gym: the workout app's link ([SessionRules.cleanAppLink]); blank clears it. Returns false (nothing written) when it
     * isn't an http(s) address.
     */
    fun setHabitAppLink(id: String, link: String?): Boolean {
        requireHabit(id)
        val blank = link.isNullOrBlank()
        val clean = SessionRules.cleanAppLink(link)
        if (!blank && clean == null) return false
        replica.commitLocal(EntityTypes.HABIT, id, mapOf(HabitFields.APP_LINK to (clean?.fv() ?: FieldValue.Null)))
        return true
    }

    /**
     * Gym: "Went" ([went] = true: today is ticked with the session's [label] and an optional one-line [note]) or
     * "Didn't go" (today is marked missed and the session is rebooked on another day, never nagged).
     */
    fun answerSession(id: String, went: Boolean, label: String?, note: String?) {
        requireHabit(id)
        val day = today()
        val fields = linkedMapOf<String, FieldValue>(
            HabitCompletionFields.HABIT_ID to id.fv(),
            HabitCompletionFields.DAY to day.fv(),
            HabitCompletionFields.DONE to went.fv(),
            HabitCompletionFields.MISSED to (!went).fv(),
            HabitCompletionFields.AT to nowMs().fv(),
        )
        if (went) {
            label?.trim()?.takeIf { it.isNotEmpty() }?.let { fields[HabitCompletionFields.LABEL] = it.take(SessionRules.MAX_LABEL).fv() }
            SessionRules.cleanNote(note)?.let { fields[HabitCompletionFields.NOTE] = it.fv() }
        }
        replica.commitLocal(EntityTypes.HABIT_COMPLETION, completionId(id, day), fields)
    }

    /** Gym: the note on today's session (blank clears it). */
    fun setSessionNote(id: String, note: String?) {
        requireHabit(id)
        replica.commitLocal(
            EntityTypes.HABIT_COMPLETION, completionId(id, today()),
            mapOf(HabitCompletionFields.NOTE to (SessionRules.cleanNote(note)?.fv() ?: FieldValue.Null)),
        )
    }

    /** Gym: Undo for [answerSession]: today is neither went nor missed again. */
    fun clearSessionAnswer(id: String) {
        requireHabit(id)
        val day = today()
        replica.commitLocal(
            EntityTypes.HABIT_COMPLETION, completionId(id, day),
            linkedMapOf(
                HabitCompletionFields.HABIT_ID to id.fv(),
                HabitCompletionFields.DAY to day.fv(),
                HabitCompletionFields.DONE to false.fv(),
                HabitCompletionFields.MISSED to false.fv(),
                HabitCompletionFields.AT to nowMs().fv(),
            ),
        )
    }

    fun deleteHabit(id: String) {
        requireHabit(id)
        replica.commitLocal(EntityTypes.HABIT, id, mapOf(ActionableFields.DELETED to true.fv()))
    }

    // ---- Goals ----

    fun addGoal(
        title: String,
        target: String? = null,
        horizon: GoalHorizon = GoalHorizon.MONTH,
        parentId: String? = null,
        dueDay: Long? = null,
        why: String? = null,
    ): String {
        parentId?.let(::requireGoal)
        val t = cleanTitle(title, "Name the goal")
        val id = ids()
        val fields = linkedMapOf<String, FieldValue>(
            ActionableFields.TITLE to t.fv(),
            ActionableFields.LIFECYCLE to Lifecycle.ACTIVE.name.fv(),
            GoalFields.HORIZON to horizon.name.fv(),
            GoalFields.PROGRESS_PCT to 0.fv(),
            ActionableFields.CREATED_AT to nowMs().fv(),
            ActionableFields.VISIBILITY to Visibility.PRIVATE.name.fv(),
            ActionableFields.PROVENANCE_SOURCE to "user".fv(),
            ActionableFields.PROVENANCE_TRUST to Trust.TRUSTED_USER.name.fv(),
        )
        target?.trim()?.takeIf { it.isNotEmpty() }?.let { fields[GoalFields.TARGET] = it.take(MAX_TITLE).fv() }
        parentId?.let { fields[GoalFields.PARENT_ID] = it.fv() }
        dueDay?.let { fields[GoalFields.DUE_DAY] = it.fv() }
        why?.trim()?.takeIf { it.isNotEmpty() }?.let { fields[GoalFields.WHY] = it.take(GoalRules.MAX_WHY).fv() }
        replica.commitLocal(EntityTypes.GOAL, id, fields)
        return id
    }

    fun editGoal(id: String, title: String? = null, target: String? = null, horizon: GoalHorizon? = null) {
        requireGoal(id)
        val changes = linkedMapOf<String, FieldValue>()
        title?.let { changes[ActionableFields.TITLE] = cleanTitle(it, "Name the goal").fv() }
        target?.let { v -> changes[GoalFields.TARGET] = v.trim().takeIf { it.isNotEmpty() }?.take(MAX_TITLE)?.fv() ?: FieldValue.Null }
        horizon?.let { changes[GoalFields.HORIZON] = it.name.fv() }
        if (changes.isNotEmpty()) replica.commitLocal(EntityTypes.GOAL, id, changes)
    }

    /** Why the goal matters (blank clears it). */
    fun setGoalWhy(id: String, why: String?) {
        requireGoal(id)
        val v = why?.trim()?.takeIf { it.isNotEmpty() }?.take(GoalRules.MAX_WHY)
        replica.commitLocal(EntityTypes.GOAL, id, mapOf(GoalFields.WHY to (v?.fv() ?: FieldValue.Null)))
    }

    /** The day it should be done by (null clears it). */
    fun setGoalDue(id: String, dueDay: Long?) {
        requireGoal(id)
        replica.commitLocal(EntityTypes.GOAL, id, mapOf(GoalFields.DUE_DAY to (dueDay?.fv() ?: FieldValue.Null)))
    }

    /**
     * Puts the goal under a bigger one (null: at the top). Refuses itself and anything already under it, so the goals
     * never form a loop on this device; a loop made by two offline devices at once is cut when read ([goals]).
     */
    fun setGoalParent(id: String, parentId: String?) {
        requireGoal(id)
        if (parentId != null) {
            requireGoal(parentId)
            var at: String? = parentId
            val seen = mutableSetOf<String>()
            while (at != null && seen.add(at)) {
                if (at == id) throw ValidationException("A goal can't sit under itself")
                at = replica.entity(EntityTypes.GOAL, at)?.takeIf { !it.deleted }?.get(GoalFields.PARENT_ID)?.textOrNull
            }
        }
        replica.commitLocal(EntityTypes.GOAL, id, mapOf(GoalFields.PARENT_ID to (parentId?.fv() ?: FieldValue.Null)))
    }

    /** Hand-set progress, used while nothing is linked to the goal. */
    fun setGoalProgress(id: String, pct: Int) {
        requireGoal(id)
        replica.commitLocal(EntityTypes.GOAL, id, mapOf(GoalFields.PROGRESS_PCT to pct.coerceIn(0, 100).fv()))
    }

    /** Done is terminal, so it wins over a concurrent edit on another device. */
    fun finishGoal(id: String) {
        requireGoal(id)
        replica.commitLocal(
            EntityTypes.GOAL, id,
            mapOf(ActionableFields.LIFECYCLE to Lifecycle.DONE.name.fv(), ActionableFields.COMPLETED_AT to nowMs().fv()),
        )
    }

    /** Deletes the goal; linked tasks and habits stay, unlinked. */
    fun deleteGoal(id: String) {
        requireGoal(id)
        replica.entities(EntityTypes.HABIT).filter { it[ActionableFields.GOAL_ID].textOrNull == id }.forEach {
            replica.commitLocal(EntityTypes.HABIT, it.ref.entityId, mapOf(ActionableFields.GOAL_ID to FieldValue.Null))
        }
        replica.entities(EntityTypes.TASK).filter { it[ActionableFields.GOAL_ID].textOrNull == id }.forEach {
            replica.commitLocal(EntityTypes.TASK, it.ref.entityId, mapOf(ActionableFields.GOAL_ID to FieldValue.Null))
        }
        milestones(id).forEach { replica.commitLocal(EntityTypes.GOAL_MILESTONE, it.id, mapOf(GoalMilestoneFields.DELETED to true.fv())) }
        replica.entities(EntityTypes.GOAL_ENTRY).filter { !it.deleted && it[GoalEntryFields.GOAL_ID].textOrNull == id }.forEach {
            replica.commitLocal(EntityTypes.GOAL_ENTRY, it.ref.entityId, mapOf(GoalEntryFields.DELETED to true.fv()))
        }
        // Goals under it move up to its own parent (or the top).
        val up = live(EntityTypes.GOAL, id)?.get(GoalFields.PARENT_ID)?.textOrNull
        replica.entities(EntityTypes.GOAL).filter { !it.deleted && it[GoalFields.PARENT_ID].textOrNull == id }.forEach {
            replica.commitLocal(EntityTypes.GOAL, it.ref.entityId, mapOf(GoalFields.PARENT_ID to (up?.fv() ?: FieldValue.Null)))
        }
        replica.commitLocal(EntityTypes.GOAL, id, mapOf(ActionableFields.DELETED to true.fv()))
    }

    // ---- Milestones (Goals, properly, slice 2) ----

    /** Adds a milestone to the goal (at most [GoalRules.MAX_MILESTONES]); a title already there isn't added twice. */
    fun addMilestone(goalId: String, title: String): String {
        requireGoal(goalId)
        val t = title.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) throw ValidationException("Name the milestone")
        if (t.length > GoalRules.MAX_MILESTONE_TITLE) throw ValidationException("That's too long for a milestone")
        val mine = milestones(goalId)
        mine.firstOrNull { it.title.equals(t, ignoreCase = true) }?.let { return it.id }
        if (mine.size >= GoalRules.MAX_MILESTONES) throw ValidationException("A goal keeps up to ${GoalRules.MAX_MILESTONES} milestones")
        val id = ids()
        replica.commitLocal(
            EntityTypes.GOAL_MILESTONE, id,
            linkedMapOf(
                GoalMilestoneFields.GOAL_ID to goalId.fv(),
                GoalMilestoneFields.TITLE to t.fv(),
                GoalMilestoneFields.DONE to false.fv(),
                // After the goal's last milestone even when added within the same millisecond, so they keep their order.
                GoalMilestoneFields.CREATED_AT to maxOf(nowMs(), lastCreated(goalId) + 1).fv(),
            ),
        )
        return id
    }

    /** Ticks (or unticks) a milestone; the latest tap on any device wins. */
    fun setMilestoneDone(id: String, done: Boolean) {
        live(EntityTypes.GOAL_MILESTONE, id) ?: throw ValidationException("Milestone not found")
        replica.commitLocal(
            EntityTypes.GOAL_MILESTONE, id,
            mapOf(GoalMilestoneFields.DONE to done.fv(), GoalMilestoneFields.DONE_AT to (if (done) nowMs().fv() else FieldValue.Null)),
        )
    }

    /** Removes a milestone for good; returns its title (Undo adds it again as a new one), null if it was already gone. */
    fun removeMilestone(id: String): String? {
        val s = live(EntityTypes.GOAL_MILESTONE, id) ?: return null
        replica.commitLocal(EntityTypes.GOAL_MILESTONE, id, mapOf(GoalMilestoneFields.DELETED to true.fv()))
        return s[GoalMilestoneFields.TITLE].textOrNull
    }

    private fun lastCreated(goalId: String): Long =
        replica.entities(EntityTypes.GOAL_MILESTONE).filter { it[GoalMilestoneFields.GOAL_ID].textOrNull == goalId }
            .maxOfOrNull { it[GoalMilestoneFields.CREATED_AT].longOrNull ?: 0L } ?: Long.MIN_VALUE + 1

    /** The goal's milestones, oldest first. */
    fun milestones(goalId: String): List<GoalMilestone> = allMilestones()[goalId].orEmpty()

    private fun allMilestones(): Map<String, List<GoalMilestone>> =
        replica.entities(EntityTypes.GOAL_MILESTONE).filter { !it.deleted }
            .mapNotNull { s ->
                val g = s[GoalMilestoneFields.GOAL_ID].textOrNull ?: return@mapNotNull null
                Triple(s[GoalMilestoneFields.CREATED_AT].longOrNull ?: 0L, s.ref.entityId,
                    GoalMilestone(s.ref.entityId, g, s[GoalMilestoneFields.TITLE].textOrNull ?: "", s[GoalMilestoneFields.DONE].boolOrNull == true))
            }
            .sortedWith(compareBy<Triple<Long, String, GoalMilestone>> { it.first }.thenBy { it.second })
            .map { it.third }.groupBy { it.goalId }

    // ---- Numeric goals (Goals, properly, slice 3) ----

    /**
     * The number to reach as typed ("£2,000", "12 books"; [GoalMetricRules.parseTarget]); blank makes it an ordinary
     * goal again (its updates are kept, so setting a number again brings them back). Returns false, writing nothing,
     * when it isn't a number.
     */
    fun setMetric(goalId: String, text: String?): Boolean {
        val s = live(EntityTypes.GOAL, goalId) ?: throw ValidationException("Goal not found")
        if (text.isNullOrBlank()) {
            replica.commitLocal(EntityTypes.GOAL, goalId, mapOf(GoalFields.METRIC_TARGET to FieldValue.Null))
            return true
        }
        val (v, unit) = GoalMetricRules.parseTarget(text) ?: return false
        val changes = linkedMapOf<String, FieldValue>(GoalFields.METRIC_TARGET to v.fv(), GoalFields.METRIC_UNIT to unit.fv())
        if (s[GoalFields.METRIC_SINCE_DAY].longOrNull == null) changes[GoalFields.METRIC_SINCE_DAY] = today().fv()
        replica.commitLocal(EntityTypes.GOAL, goalId, changes)
        return true
    }

    /**
     * An update to a numeric goal, counted today: "+200", "-1", or "=1200" (sets the number: the difference is written).
     * Returns the update's id (Undo removes it), or null when it changes nothing.
     */
    fun addMetricEntry(goalId: String, text: String): String? {
        val s = live(EntityTypes.GOAL, goalId) ?: throw ValidationException("Goal not found")
        if (s[GoalFields.METRIC_TARGET].longOrNull == null) throw ValidationException("Give the goal a number first")
        val input = GoalMetricRules.parseInput(text) ?: throw ValidationException("Type a number, like +200 or =1200")
        val amount = if (input.set) input.x100 - metricCurrent(goalId) else input.x100
        if (amount == 0L) return null
        val id = ids()
        replica.commitLocal(
            EntityTypes.GOAL_ENTRY, id,
            linkedMapOf(
                GoalEntryFields.GOAL_ID to goalId.fv(),
                GoalEntryFields.DAY to today().fv(),
                GoalEntryFields.AMOUNT to amount.fv(),
                GoalEntryFields.AT to nowMs().fv(),
            ),
        )
        return id
    }

    /** Removes an update (Undo of [addMetricEntry], or Remove in the list); false if it was already gone. */
    fun removeMetricEntry(id: String): Boolean {
        live(EntityTypes.GOAL_ENTRY, id) ?: return false
        replica.commitLocal(EntityTypes.GOAL_ENTRY, id, mapOf(GoalEntryFields.DELETED to true.fv()))
        return true
    }

    /** Each tick of [habitId] adds 1 to the goal's number from today on (null stops it; ticks already counted go too). */
    fun setMetricHabit(goalId: String, habitId: String?) {
        requireGoal(goalId)
        habitId?.let(::requireHabit)
        replica.commitLocal(EntityTypes.GOAL, goalId, mapOf(GoalFields.METRIC_HABIT_ID to (habitId?.fv() ?: FieldValue.Null)))
    }

    private fun metricCurrent(goalId: String): Long {
        val s = live(EntityTypes.GOAL, goalId) ?: return 0L
        return metricOf(goalId, s, completedDays(), emptyMap(), today(), null)?.currentX100 ?: 0L
    }

    /** Each goal's updates (id, day, amount), oldest first. */
    private fun entriesByGoal(): Map<String, List<Triple<String, Long, Long>>> {
        data class Row(val goalId: String, val id: String, val day: Long, val x: Long, val at: Long)
        return replica.entities(EntityTypes.GOAL_ENTRY).filter { !it.deleted }.mapNotNull { e ->
            Row(
                e[GoalEntryFields.GOAL_ID].textOrNull ?: return@mapNotNull null, e.ref.entityId,
                e[GoalEntryFields.DAY].longOrNull ?: return@mapNotNull null,
                e[GoalEntryFields.AMOUNT].longOrNull ?: 0L, e[GoalEntryFields.AT].longOrNull ?: 0L,
            )
        }.sortedWith(compareBy<Row> { it.at }.thenBy { it.id })
            .groupBy({ it.goalId }, { Triple(it.id, it.day, it.x) })
    }

    /** A goal's number, or null when it has none. [entries] null reads them. */
    private fun metricOf(
        goalId: String, s: EntitySnapshot, ticks: Map<String, Set<Long>>, habitTitles: Map<String, String>, today: Long,
        entries: Map<String, List<Triple<String, Long, Long>>>?,
    ): GoalMetric? {
        val target = s[GoalFields.METRIC_TARGET].longOrNull?.takeIf { it > 0 } ?: return null
        val unit = s[GoalFields.METRIC_UNIT].textOrNull ?: ""
        val since = s[GoalFields.METRIC_SINCE_DAY].longOrNull ?: today
        val mine = (entries ?: entriesByGoal())[goalId].orEmpty()
        val habitId = s[GoalFields.METRIC_HABIT_ID].textOrNull?.takeIf { live(EntityTypes.HABIT, it) != null }
        val habitDays = habitId?.let { h -> ticks[h].orEmpty().filter { it in since..today } }.orEmpty()
        val changes = mine.map { it.second to it.third } + habitDays.map { it to 100L }
        val current = changes.sumOf { it.second }
        val due = s[GoalFields.DUE_DAY].longOrNull
        return GoalMetric(
            targetX100 = target, unit = unit, currentX100 = current, sinceDay = since, dueDay = due,
            points = GoalMetricRules.points(since, today, changes),
            habitId = habitId, habitTitle = habitId?.let { habitTitles[it] },
            recent = mine.asReversed().take(GoalMetricRules.RECENT).map { (id, day, x) ->
                GoalEntryItem(id, x, day, GoalMetricRules.entryLabel(x, unit, day, today))
            },
            paceLine = GoalMetricRules.paceLine(current, target, unit, since, due, today),
        )
    }

    /** Links a task to a goal (null unlinks); it then counts towards the goal's progress. */
    fun setTaskGoal(taskId: String, goalId: String?) {
        val s = replica.entity(EntityTypes.TASK, taskId)
        if (s == null || s.deleted) throw ValidationException("Task not found")
        goalId?.let(::requireGoal)
        replica.commitLocal(EntityTypes.TASK, taskId, mapOf(ActionableFields.GOAL_ID to (goalId?.fv() ?: FieldValue.Null)))
    }

    // ---- Reads ----

    fun habits(): List<HabitItem> {
        val today = today()
        val ws = GoalRules.weekStart(today)
        val doneByHabit = completedDays()
        return replica.entities(EntityTypes.HABIT).map { s ->
            val id = s.ref.entityId
            val target = (s[HabitFields.TARGET_PER_WEEK].longOrNull?.toInt() ?: 7).coerceIn(1, 7)
            val createdDay = calendar.epochDayOf(s[ActionableFields.CREATED_AT].longOrNull ?: nowMs())
            val days = doneByHabit[id].orEmpty()
            val doneWeek = days.count { it in ws..today }
            val doneToday = today in days
            val weekTarget = GoalRules.weekTarget(target, today, createdDay)
            val pace = GoalRules.pace(target, doneWeek, doneToday, today, createdDay)
            val unit = if (target >= 7) "day" else "week"
            val streak = GoalRules.streak(target, days, today, createdDay)
            HabitItem(
                id = id,
                title = s[ActionableFields.TITLE].textOrNull ?: "",
                targetPerWeek = target,
                timing = s[HabitFields.PREFERRED_TIMING].textOrNull?.let { enumOrNull<HabitTiming>(it) } ?: HabitTiming.ANYTIME,
                minutes = (s[HabitFields.MINUTES].longOrNull?.toInt() ?: GoalRules.DEFAULT_MINUTES).coerceIn(1, MAX_MINUTES),
                goalId = s[ActionableFields.GOAL_ID].textOrNull,
                doneThisWeek = doneWeek,
                weekTarget = weekTarget,
                doneToday = doneToday,
                pace = pace,
                streak = streak,
                streakUnit = unit,
                week = (0..6).map { (ws + it) in days },
                meta = GoalRules.habitMeta(pace, doneWeek, weekTarget, target, today),
                streakLine = GoalRules.streakLine(streak, unit),
                hasConflict = replica.conflictsFor(EntityTypes.HABIT, id).isNotEmpty(),
                booked = s[HabitFields.BOOK_SLOTS].boolOrNull == true,
                rotation = SessionRules.decodeRotation(s[HabitFields.ROTATION].textOrNull),
                appLink = SessionRules.cleanAppLink(s[HabitFields.APP_LINK].textOrNull),
            )
        }.sortedWith(
            compareBy<HabitItem> { paceOrder(it.pace) }
                .thenBy { GoalRules.TIMINGS.indexOf(it.timing) }
                .thenBy { it.title.lowercase() }
                .thenBy { it.id },
        )
    }

    /**
     * Open goals, each right after the goal it sits under (top-level goals by horizon, then oldest), and how many are
     * finished. Progress counts what's linked — tasks done, habits' share of this week — and each goal under it (a
     * finished one as 100 %); with nothing linked it is set by hand.
     */
    fun goals(tasks: List<Task>, habits: List<HabitItem> = habits()): Pair<List<GoalItem>, Int> =
        collect(tasks, habits).let { it.open to it.finished }

    private class GoalSets(val open: List<GoalItem>, val finished: Int, val finishedThisWeek: List<GoalItem>, val finishedRecently: List<GoalItem>)

    /**
     * [goals], plus this week's finished week goals (they still count as moving this week) and the goals finished since
     * last month began (the month's review).
     */
    private fun collect(tasks: List<Task>, habits: List<HabitItem>): GoalSets {
        val today = today()
        val snaps = replica.entities(EntityTypes.GOAL).associateBy { it.ref.entityId }
        fun isDone(id: String) = snaps[id]?.get(ActionableFields.LIFECYCLE)?.textOrNull == Lifecycle.DONE.name
        // The parent as stored, if it is still there; a loop (two offline devices) is cut at the goal that closes it.
        val parentOf = HashMap<String, String?>()
        for (id in snaps.keys.sorted()) {
            var p = snaps[id]?.get(GoalFields.PARENT_ID)?.textOrNull?.takeIf { it in snaps && it != id }
            if (p != null) {
                val seen = mutableSetOf(id)
                var at: String? = p
                while (at != null) {
                    if (!seen.add(at)) { p = null; break }
                    at = snaps[at]?.get(GoalFields.PARENT_ID)?.textOrNull?.takeIf { it in snaps }
                }
            }
            parentOf[id] = p
        }
        val children = parentOf.entries.filter { it.value != null }.groupBy({ it.value!! }, { it.key })
        val linkedTasks = snaps.keys.associateWith { id -> tasks.filter { it.goalId == id && it.lifecycle != Lifecycle.CANCELLED && it.lifecycle != Lifecycle.SOMEDAY } }
        val linkedHabits = snaps.keys.associateWith { id -> habits.filter { it.goalId == id } }
        val milestonesOf = allMilestones()
        val ticks = completedDays()
        val habitTitles = habits.associate { it.id to it.title }
        val entries = entriesByGoal()
        val metrics = snaps.mapValues { (id, s) -> metricOf(id, s, ticks, habitTitles, today, entries) }
        val milestoneMoves = milestoneDoneDays()
        val movedMemo = HashMap<String, Long?>()
        fun moved(id: String, depth: Int = 0): Long? {
            if (movedMemo.containsKey(id)) return movedMemo[id]
            val s = snaps.getValue(id)
            val metricHabit = s[GoalFields.METRIC_HABIT_ID].textOrNull
            val metricSince = s[GoalFields.METRIC_SINCE_DAY].longOrNull ?: Long.MIN_VALUE
            val days = buildList {
                linkedTasks.getValue(id).forEach { t -> if (t.isDone) t.completedAtMs?.let { add(calendar.epochDayOf(it)) } }
                milestoneMoves[id]?.let { add(it) }
                entries[id].orEmpty().forEach { add(it.second) }
                linkedHabits.getValue(id).forEach { h -> ticks[h.id]?.maxOrNull()?.let { add(it) } }
                metricHabit?.let { h -> ticks[h]?.filter { it >= metricSince }?.maxOrNull()?.let { add(it) } }
                if (isDone(id)) s[ActionableFields.COMPLETED_AT].longOrNull?.let { add(calendar.epochDayOf(it)) }
                if (depth <= 32) children[id].orEmpty().forEach { k -> moved(k, depth + 1)?.let { add(it) } }
            }
            val v = days.filter { it <= today }.maxOrNull()
            movedMemo[id] = v
            return v
        }
        val pctMemo = HashMap<String, Int>()
        fun pct(id: String, depth: Int = 0): Int = pctMemo[id] ?: run {
            val s = snaps.getValue(id)
            val kids = children[id].orEmpty()
            val scores = linkedTasks.getValue(id).map { if (it.isDone) 1.0 else 0.0 } +
                linkedHabits.getValue(id).map { minOf(1.0, it.doneThisWeek.toDouble() / it.weekTarget.coerceAtLeast(1)) } +
                kids.map { k -> if (depth > 32) 0.0 else pct(k, depth + 1) / 100.0 } +
                milestonesOf[id].orEmpty().map { if (it.done) 1.0 else 0.0 }
            val v = when {
                isDone(id) -> 100
                // A numeric goal's number is what done looks like: it decides the progress.
                metrics[id] != null -> metrics.getValue(id)!!.pct
                scores.isNotEmpty() -> (scores.sum() * 100 / scores.size).toInt()
                else -> (s[GoalFields.PROGRESS_PCT].longOrNull?.toInt() ?: 0).coerceIn(0, 100)
            }
            pctMemo[id] = v
            v
        }
        fun item(id: String, depth: Int): GoalItem {
            val s = snaps.getValue(id)
            val lt = linkedTasks.getValue(id)
            val lh = linkedHabits.getValue(id)
            val openKids = children[id].orEmpty().filter { !isDone(it) }
            val allKids = children[id].orEmpty()
            val tasksDone = lt.count { it.isDone }
            val ms = milestonesOf[id].orEmpty()
            val metric = metrics[id]
            val counted = lt.isNotEmpty() || lh.isNotEmpty() || allKids.isNotEmpty() || ms.isNotEmpty() || metric != null
            val done = isDone(id)
            val horizon = GoalRules.parseHorizon(s[GoalFields.HORIZON].textOrNull)
            val due = s[GoalFields.DUE_DAY].longOrNull
            val createdDay = s[ActionableFields.CREATED_AT].longOrNull?.let { calendar.epochDayOf(it) }
            val quiet = GoalRhythmRules.quietLabel(GoalRhythmRules.quietDays(horizon, done, moved(id), createdDay, today))
            val kidsDone = allKids.count { isDone(it) }
            val meta = listOfNotNull(
                GoalRules.horizonLabel(horizon),
                due?.let { GoalRules.dueLabel(it, today) },
                metric?.line,
                ms.takeIf { it.isNotEmpty() }?.let { m -> "${m.count { it.done }} of ${m.size} ${if (m.size == 1) "milestone" else "milestones"}" },
                lt.takeIf { it.isNotEmpty() }?.let { "$tasksDone of ${it.size} ${if (it.size == 1) "task" else "tasks"}" },
                lh.takeIf { it.isNotEmpty() }?.let { "${it.size} ${if (it.size == 1) "habit" else "habits"}" },
                allKids.takeIf { it.isNotEmpty() }?.let {
                    if (kidsDone > 0) "$kidsDone of ${it.size} goals done" else "${it.size} ${if (it.size == 1) "goal" else "goals"} under it"
                },
                if (!counted && !done) "set by hand" else null,
                quiet,
            ).joinToString(" · ")
            val next = lt.filter { it.lifecycle == Lifecycle.ACTIVE }.minWithOrNull(GoalRules.stepOrder)
            val parent = parentOf[id]?.takeIf { !isDone(it) }
            return GoalItem(
                id = id,
                title = s[ActionableFields.TITLE].textOrNull ?: "",
                target = s[GoalFields.TARGET].textOrNull,
                horizon = horizon,
                progressPct = pct(id),
                counted = counted,
                tasksDone = tasksDone,
                tasksTotal = lt.size,
                habitCount = lh.size,
                done = done,
                meta = meta,
                hasConflict = replica.conflictsFor(EntityTypes.GOAL, id).isNotEmpty(),
                why = s[GoalFields.WHY].textOrNull,
                dueDay = due,
                parentId = parent,
                parentTitle = parent?.let { snaps[it]?.get(ActionableFields.TITLE)?.textOrNull },
                depth = depth,
                childCount = openKids.size,
                nextStepTaskId = next?.id,
                // With no open task, the first milestone not yet reached is the next step.
                nextStep = next?.title ?: ms.firstOrNull { !it.done }?.title,
                milestones = ms,
                metric = metric,
                lastMovedDay = moved(id),
                createdDay = createdDay,
                doneDay = if (done) s[ActionableFields.COMPLETED_AT].longOrNull?.let { calendar.epochDayOf(it) } else null,
            )
        }
        val order = compareBy<String> { GoalRules.parseHorizon(snaps[it]?.get(GoalFields.HORIZON)?.textOrNull).ordinal }
            .thenBy { snaps[it]?.get(ActionableFields.CREATED_AT)?.longOrNull ?: 0L }.thenBy { it }
        val open = mutableListOf<GoalItem>()
        fun walk(id: String, depth: Int) {
            open += item(id, depth)
            children[id].orEmpty().filter { !isDone(it) }.sortedWith(order).forEach { walk(it, depth + 1) }
        }
        // Top level: no parent, or a parent that is finished (the goal then shows on its own).
        snaps.keys.filter { !isDone(it) && (parentOf[it] == null || isDone(parentOf[it]!!)) }.sortedWith(order).forEach { walk(it, 0) }
        val finishedThisWeek = snaps.keys.filter { id ->
            isDone(id) && GoalRules.parseHorizon(snaps[id]?.get(GoalFields.HORIZON)?.textOrNull) == GoalHorizon.WEEK &&
                (snaps[id]?.get(ActionableFields.COMPLETED_AT)?.longOrNull?.let { calendar.epochDayOf(it) } ?: Long.MIN_VALUE) >= GoalRules.weekStart(today)
        }.sortedWith(compareBy<String> { snaps[it]?.get(ActionableFields.COMPLETED_AT)?.longOrNull ?: 0L }.thenBy { it })
            .map { item(it, 0) }
        val since = GoalRhythmRules.previousMonthStart(today)
        val finishedRecently = snaps.keys.filter { id ->
            isDone(id) && (snaps[id]?.get(ActionableFields.COMPLETED_AT)?.longOrNull?.let { calendar.epochDayOf(it) } ?: Long.MIN_VALUE) >= since
        }.sorted().map { item(it, 0) }
        return GoalSets(open, snaps.keys.count { isDone(it) }, finishedThisWeek, finishedRecently)
    }

    fun view(tasks: List<Task>): GoalsView {
        val h = habits()
        val sets = collect(tasks, h)
        val today = today()
        return GoalsView(
            h, sets.open, sets.finished, GoalWeekRules.build(sets.open, sets.finishedThisWeek, today),
            GoalRhythmRules.month(sets.open, sets.finishedRecently, today),
        )
    }

    /** Each goal's latest milestone tick (local epoch day). */
    private fun milestoneDoneDays(): Map<String, Long> =
        replica.entities(EntityTypes.GOAL_MILESTONE)
            .filter { !it.deleted && it[GoalMilestoneFields.DONE].boolOrNull == true }
            .mapNotNull { s ->
                val g = s[GoalMilestoneFields.GOAL_ID].textOrNull ?: return@mapNotNull null
                val at = s[GoalMilestoneFields.DONE_AT].longOrNull ?: return@mapNotNull null
                g to calendar.epochDayOf(at)
            }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.max() }

    /** Habits the planner should make room for today: behind first, then due. */
    fun plannerHabits(): List<PlannerHabit> =
        habits().filter { it.needsRoomToday && !it.booked } // booked ones come as fixed sessions (see [SessionRules])
            .map { PlannerHabit(it.id, it.title, it.minutes, it.timing, it.pace == HabitPace.BEHIND) }

    /** Gym: every booked habit with what [SessionRules.book] needs (ticks, missed days, the last label, notes). */
    fun sessionHabits(): List<SessionHabit> {
        val booked = habits().filter { it.booked }
        if (booked.isEmpty()) return emptyList()
        val created = booked.associate { h -> h.id to (replica.entity(EntityTypes.HABIT, h.id)?.get(ActionableFields.CREATED_AT)?.longOrNull ?: 0L) }
        val byHabit = replica.entities(EntityTypes.HABIT_COMPLETION).groupBy { it[HabitCompletionFields.HABIT_ID].textOrNull }
        return booked.map { h ->
            val rows = byHabit[h.id].orEmpty().mapNotNull { s -> s[HabitCompletionFields.DAY].longOrNull?.let { it to s } }
            val done = rows.filter { it.second[HabitCompletionFields.DONE].boolOrNull == true }
            val lastLabel = done.filter { it.second[HabitCompletionFields.LABEL].textOrNull != null }.maxByOrNull { it.first }
                ?.second?.get(HabitCompletionFields.LABEL)?.textOrNull
            val today = done.firstOrNull { it.first == today() }?.second
            SessionHabit(
                id = h.id, title = h.title, perWeek = h.targetPerWeek, timing = h.timing, minutes = h.minutes,
                createdAtMs = created[h.id] ?: 0L,
                doneDays = done.map { it.first }.toSet(),
                missedDays = rows.filter { it.second[HabitCompletionFields.DONE].boolOrNull != true && it.second[HabitCompletionFields.MISSED].boolOrNull == true }
                    .map { it.first }.toSet(),
                rotation = h.rotation,
                appLink = h.appLink,
                lastLabel = lastLabel,
                todayLabel = today?.get(HabitCompletionFields.LABEL)?.textOrNull,
                todayNote = today?.get(HabitCompletionFields.NOTE)?.textOrNull,
            )
        }
    }

    // ---- Helpers ----

    private fun completedDays(): Map<String, Set<Long>> =
        replica.entities(EntityTypes.HABIT_COMPLETION)
            .filter { it[HabitCompletionFields.DONE].boolOrNull == true }
            .mapNotNull { s ->
                val h = s[HabitCompletionFields.HABIT_ID].textOrNull ?: return@mapNotNull null
                val d = s[HabitCompletionFields.DAY].longOrNull ?: return@mapNotNull null
                h to d
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    private fun paceOrder(p: HabitPace) = when (p) {
        HabitPace.BEHIND -> 0
        HabitPace.DUE -> 1
        HabitPace.ON_TRACK -> 2
        HabitPace.DONE_TODAY -> 3
        HabitPace.WEEK_MET -> 4
    }

    private fun cleanTitle(text: String, emptyMessage: String): String {
        val t = text.trim()
        if (t.isEmpty()) throw ValidationException(emptyMessage)
        if (t.length > MAX_TITLE) throw ValidationException("That's too long for a title")
        return t
    }

    private fun checkTarget(perWeek: Int) {
        if (perWeek !in 1..7) throw ValidationException("Pick between once and seven times a week")
    }

    private fun checkMinutes(minutes: Int) {
        if (minutes !in 1..MAX_MINUTES) throw ValidationException("Pick up to four hours")
    }

    private fun live(type: String, id: String): EntitySnapshot? = replica.entity(type, id)?.takeIf { !it.deleted }

    private fun requireHabit(id: String) { live(EntityTypes.HABIT, id) ?: throw ValidationException("Habit not found") }

    fun goalExists(id: String): Boolean = live(EntityTypes.GOAL, id) != null

    private fun requireGoal(id: String) { live(EntityTypes.GOAL, id) ?: throw ValidationException("Goal not found") }

    companion object {
        const val MAX_TITLE = 500
        const val MAX_MINUTES = 240
        fun completionId(habitId: String, day: Long): String = "$habitId.d$day"
    }
}
