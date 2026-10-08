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
 * - Pace: by the end of each day of the week you should have done your share of the week's target (pro rata from the
 *   day the habit was added). Behind = short of yesterday's share; Due = today's go keeps you on pace.
 * - Streaks: a daily habit counts days in a row; any other target counts weeks in a row that met it.
 * - A Goal's progress is counted from what's linked to it (tasks done, habits' share of this week), or set by hand when
 *   nothing is linked.
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
) {
    val needsRoomToday: Boolean get() = pace == HabitPace.BEHIND || pace == HabitPace.DUE
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
    /** "Months · 3 of 5 tasks · 2 habits", "Years · set by hand". */
    val meta: String,
    val hasConflict: Boolean,
)

data class GoalsView(
    /** Behind first, then due today, then the rest; done-today and week-met last. */
    val habits: List<HabitItem>,
    /** Open goals, short horizon first; finished goals are kept but not listed. */
    val goals: List<GoalItem>,
    val finishedGoals: Int,
) {
    val behind: Int get() = habits.count { it.pace == HabitPace.BEHIND }
    val dueToday: Int get() = habits.count { it.pace == HabitPace.DUE }
    val doneToday: Int get() = habits.count { it.doneToday }

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
        GoalHorizon.SHORT -> "Weeks"
        GoalHorizon.MEDIUM -> "Months"
        GoalHorizon.LONG -> "Years"
    }

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

    fun habitMeta(pace: HabitPace, done: Int, weekTarget: Int, target: Int): String = when (pace) {
        HabitPace.DONE_TODAY -> if (target >= 7) "Done today" else "Done today · $done of $weekTarget this week"
        HabitPace.WEEK_MET -> "Week done · $done of $weekTarget"
        HabitPace.BEHIND -> "Behind · $done of $weekTarget this week"
        HabitPace.DUE -> if (target >= 7) "To do today" else "Today keeps you on pace · $done of $weekTarget"
        HabitPace.ON_TRACK -> "On track · $done of $weekTarget this week"
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

    fun addGoal(title: String, target: String? = null, horizon: GoalHorizon = GoalHorizon.MEDIUM): String {
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
        replica.commitLocal(EntityTypes.GOAL, id, mapOf(ActionableFields.DELETED to true.fv()))
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
                meta = GoalRules.habitMeta(pace, doneWeek, weekTarget, target),
                streakLine = GoalRules.streakLine(streak, unit),
                hasConflict = replica.conflictsFor(EntityTypes.HABIT, id).isNotEmpty(),
                booked = s[HabitFields.BOOK_SLOTS].boolOrNull == true,
                rotation = SessionRules.decodeRotation(s[HabitFields.ROTATION].textOrNull),
            )
        }.sortedWith(
            compareBy<HabitItem> { paceOrder(it.pace) }
                .thenBy { GoalRules.TIMINGS.indexOf(it.timing) }
                .thenBy { it.title.lowercase() }
                .thenBy { it.id },
        )
    }

    fun goals(tasks: List<Task>, habits: List<HabitItem> = habits()): Pair<List<GoalItem>, Int> {
        val items = replica.entities(EntityTypes.GOAL).map { s ->
            val id = s.ref.entityId
            val linkedTasks = tasks.filter { it.goalId == id && it.lifecycle != Lifecycle.CANCELLED && it.lifecycle != Lifecycle.SOMEDAY }
            val linkedHabits = habits.filter { it.goalId == id }
            val tasksDone = linkedTasks.count { it.isDone }
            val counted = linkedTasks.isNotEmpty() || linkedHabits.isNotEmpty()
            val done = s[ActionableFields.LIFECYCLE].textOrNull == Lifecycle.DONE.name
            val pct = when {
                done -> 100
                counted -> {
                    val scores = linkedTasks.map { if (it.isDone) 1.0 else 0.0 } +
                        linkedHabits.map { minOf(1.0, it.doneThisWeek.toDouble() / it.weekTarget.coerceAtLeast(1)) }
                    (scores.sum() * 100 / scores.size).toInt()
                }
                else -> (s[GoalFields.PROGRESS_PCT].longOrNull?.toInt() ?: 0).coerceIn(0, 100)
            }
            val horizon = s[GoalFields.HORIZON].textOrNull?.let { enumOrNull<GoalHorizon>(it) } ?: GoalHorizon.MEDIUM
            val meta = listOfNotNull(
                GoalRules.horizonLabel(horizon),
                linkedTasks.takeIf { it.isNotEmpty() }?.let { "$tasksDone of ${it.size} ${if (it.size == 1) "task" else "tasks"}" },
                linkedHabits.takeIf { it.isNotEmpty() }?.let { "${it.size} ${if (it.size == 1) "habit" else "habits"}" },
                if (!counted && !done) "set by hand" else null,
            ).joinToString(" · ")
            GoalItem(
                id = id,
                title = s[ActionableFields.TITLE].textOrNull ?: "",
                target = s[GoalFields.TARGET].textOrNull,
                horizon = horizon,
                progressPct = pct,
                counted = counted,
                tasksDone = tasksDone,
                tasksTotal = linkedTasks.size,
                habitCount = linkedHabits.size,
                done = done,
                meta = meta,
                hasConflict = replica.conflictsFor(EntityTypes.GOAL, id).isNotEmpty(),
            ) to (s[ActionableFields.CREATED_AT].longOrNull ?: 0L)
        }
        val open = items.filter { !it.first.done }
            .sortedWith(compareBy<Pair<GoalItem, Long>> { it.first.horizon.ordinal }.thenBy { it.second }.thenBy { it.first.id })
            .map { it.first }
        return open to (items.size - open.size)
    }

    fun view(tasks: List<Task>): GoalsView {
        val h = habits()
        val (g, finished) = goals(tasks, h)
        return GoalsView(h, g, finished)
    }

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

    private fun requireGoal(id: String) { live(EntityTypes.GOAL, id) ?: throw ValidationException("Goal not found") }

    companion object {
        const val MAX_TITLE = 500
        const val MAX_MINUTES = 240
        fun completionId(habitId: String, day: Long): String = "$habitId.d$day"
    }
}
