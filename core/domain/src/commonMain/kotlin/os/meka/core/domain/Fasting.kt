package os.meka.core.domain

import os.meka.core.sync.EntitySnapshot
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Fasting tracker (build plan M1). No AI and no health claims: a timer, a goal, an eating window and what you did.
 *
 * - A fast is a `fast` entity: when it started, its goal in hours and, once broken, when it ended. Nothing about a
 *   fast is guessed; you start it (now, or "I started an hour ago") and you end it.
 * - The plan is one `fasting_plan` entity (id [PLAN_ID]): the usual goal and the eating window (local times). Presets
 *   are the common 14:10 · 16:8 · 18:6 · 20:4; 16:8 with 12:00–20:00 until changed.
 * - Two devices that start a fast while both are offline end up with two open fasts: the earlier start is the fast
 *   that counts, ending ends them all, and history merges fasts that overlap, so nothing is counted twice.
 * - History is the last seven local days, each fast counted on the day it ended.
 * - The planner keeps a short meal block free when the fast's goal lands today, and before the eating window closes
 *   ([plannerMeals]); meal blocks are shown, not applied.
 * - Fasting v2 (Meka, 2026-10-07: "some weeks I fast for 5 days; just track, remind me and keep the history"): a fast
 *   is either the **daily window** kind or an **extended** fast of days ("5-day fast", or "until Friday 18:00", stored
 *   as the `goalAtMs` moment). A running extended fast says "Day 3 of 5 · 62 h". Ending early is fine and recorded as
 *   is. [FastingHistory] keeps every fast (planned against actual), the streak of fasts that reached their goal and a
 *   twelve-week heat strip of hours fasted per day. Tracking only: no food, calorie or weight advice, ever.
 */

data class FastingPlanChoice(val label: String, val targetHours: Int, val eatingStartMin: Int, val eatingEndMin: Int)

data class FastingPlan(val targetHours: Int, val eatingStartMin: Int, val eatingEndMin: Int) {
    /** "16:8 · eating 12:00–20:00". */
    val line: String get() = "${FastingRules.planLabel(this)} · eating ${FastingRules.hm(eatingStartMin)}–${FastingRules.hm(eatingEndMin)}"
}

/** The fast that is running now. Times are epoch ms; the apps tick the timer themselves from [startedAtMs]. */
data class FastNow(
    val id: String,
    val startedAtMs: Long,
    /** The goal in whole hours (rounded up for a fast "until" a moment). */
    val targetHours: Int,
    val goalAtMs: Long,
    val reachedGoal: Boolean,
    /** "Started 20:05 yesterday". */
    val startedLine: String,
    /** "Goal 16 h · at 12:05" or "Goal reached at 12:05"; extended: "Goal 5 days · Sat 20:00". */
    val goalLine: String,
    /** True for an extended fast (days, not the daily window). */
    val extended: Boolean = false,
    /** "Fasting · goal 16 h" · "5-day fast" · "36 h fast" · "Fast until Fri 18:00". */
    val title: String = "Fasting · goal $targetHours h",
    /** Extended only: "Day 3 of 5 · 62 h", past the goal "Day 6 · 122 h · goal reached". */
    val dayLine: String? = null,
    /** When the goal is, short: "12:05" (daily window) or "Sat 20:00" (extended). */
    val goalWhen: String = "",
    /**
     * Extended only, once its goal is reached: "You did it · 5 days". The apps play the "you did it" moment (a ring
     * burst) once per fast when this first appears.
     */
    val doneLine: String? = null,
) {
    /** Share of the goal done, 0 to 1. */
    fun progress(nowMs: Long): Float = FastingRules.progressTo(startedAtMs, goalAtMs, nowMs)
}

/** The most recent finished fast. */
data class LastFast(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val hours: Double,
    val reachedGoal: Boolean,
    /** "Last fast 16 h 20 m · goal reached". */
    val line: String,
    /** True for a few minutes after it ended, so a mistaken "End fast" can be undone. */
    val canResume: Boolean,
)

/** A finished fast, for the weekly review. */
data class EndedFast(val startedAtMs: Long, val endedAtMs: Long, val targetHours: Int, val goalMs: Long = targetHours * 3_600_000L) {
    val reachedGoal: Boolean get() = endedAtMs - startedAtMs >= goalMs
}

/** One finished fast in the history: planned against actual. */
data class FastRecord(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** How long it was meant to be. */
    val plannedMs: Long,
    val actualMs: Long,
    val reachedGoal: Boolean,
    val extended: Boolean,
    /** "5-day fast" · "16 h fast" · "Fast until Fri 18:00". */
    val title: String,
    /** "Mon 5 Oct 20:00 – Sat 10 Oct 14:00". */
    val whenLine: String,
    /** "4 d 18 h of 5 days · ended early" · "16 h 20 m · goal reached". */
    val resultLine: String,
)

/** One day of the heat strip. */
data class FastingHeatDay(
    val epochDay: Long,
    /** Hours fasted within this local day (a running fast counts up to now). */
    val hours: Double,
    /** 0 none · 1 under 8 h · 2 under 16 h · 3 under a whole day · 4 the whole day. */
    val level: Int,
    val isToday: Boolean,
    /** Days after today in the last column, drawn empty. */
    val isFuture: Boolean,
)

/** Every fast, newest first, with the streak and the heat strip (Fasting v2). */
data class FastingHistory(
    /** Finished fasts, newest first (at most [FastingRules.HISTORY_MAX]). */
    val fasts: List<FastRecord>,
    /** How many fasts in a row, counting back from the latest, reached their goal. */
    val streak: Int,
    /** "3 fasts in a row reached the goal"; null at 0. */
    val streakLine: String?,
    /** "42 fasts · 31 reached the goal · longest 4 d 18 h"; null with none. */
    val totalsLine: String?,
    /** Twelve Monday–Sunday weeks, oldest first, each 7 days. */
    val heat: List<List<FastingHeatDay>>,
) {
    companion object {
        val EMPTY = FastingHistory(emptyList(), 0, null, null, emptyList())
    }
}

/** One of the last seven days. */
data class FastingDay(
    val epochDay: Long,
    /** "Mon". */
    val label: String,
    /** The longest fast that ended that day, in hours (0 when none). */
    val hours: Double,
    val reachedGoal: Boolean,
    val isToday: Boolean,
)

data class FastingView(
    val plan: FastingPlan,
    val current: FastNow?,
    val last: LastFast?,
    /** "Eating window open until 20:00", "Eating window opens at 12:00", "Eating window closed at 20:00". */
    val windowLine: String,
    /** The last seven days, oldest first. */
    val week: List<FastingDay>,
    /** "5 fasts in 7 days · average 16 h 12 m · 4 reached the goal"; null with none. */
    val weekLine: String?,
    /** Every fast, the streak and the heat strip. */
    val history: FastingHistory = FastingHistory.EMPTY,
    /** "Until …" goals offered for a new extended fast: the next six days at 18:00. */
    val untilChoices: List<FastUntilChoice> = emptyList(),
    /**
     * Days a custom "until" end can fall on ("Pick a day and time"): from the day 12 hours from now falls on to the
     * day ten days from now falls on ("Today", "Tomorrow", "Sat 10"); the first and last are only partly valid.
     */
    val untilDays: List<FastUntilDay> = emptyList(),
    /** Local time for [untilAt]; the device's own. */
    private val untilCalendar: LocalCalendar = LocalCalendar.UTC,
) {
    val isFasting: Boolean get() = current != null

    /** The moment [minuteOfDay] on [day] for a custom "until" end (a time skipped by a clock change moves forward). */
    fun untilAt(day: FastUntilDay, minuteOfDay: Int): Long = untilCalendar.toEpochMs(day.epochDay, minuteOfDay.mod(24 * 60))

    companion object {
        val EMPTY = FastingView(FastingRules.DEFAULT_PLAN, null, null, "", emptyList(), null)
    }
}

/** "Until Fri 18:00" for a new extended fast. */
data class FastUntilChoice(val label: String, val untilMs: Long)

/** A day for a custom "until" end: "Today", "Tomorrow", "Sat 10 Oct". */
data class FastUntilDay(val label: String, val epochDay: Long)

/** Whether a custom "until" end can start a fast now, and the line that says so ("Goal 3 d 20 h · starts now"). */
data class FastUntilPick(val ok: Boolean, val line: String)

data class ExtendedFastChoice(val label: String, val hours: Int)

object FastingRules {
    val PLAN_CHOICES = listOf(
        FastingPlanChoice("14:10", 14, 10 * 60, 20 * 60),
        FastingPlanChoice("16:8", 16, 12 * 60, 20 * 60),
        FastingPlanChoice("18:6", 18, 13 * 60, 19 * 60),
        FastingPlanChoice("20:4", 20, 14 * 60, 18 * 60),
    )
    val DEFAULT_PLAN = FastingPlan(16, 12 * 60, 20 * 60)
    /** Goals offered for the fast that's running. */
    val TARGET_CHOICES = listOf(12, 14, 16, 18, 20, 24)
    /** "When did you start?": minutes ago. */
    val STARTED_AGO_CHOICES = listOf(0, 30, 60, 120, 180)
    /** Nudges for a start time that was a little off. */
    val MOVE_START_CHOICES = listOf(-60, -30, 30)
    /** Extended fasts offered (Fasting v2). */
    val EXTENDED_CHOICES = listOf(
        ExtendedFastChoice("24 h", 24), ExtendedFastChoice("36 h", 36), ExtendedFastChoice("2 days", 48),
        ExtendedFastChoice("3 days", 72), ExtendedFastChoice("5 days", 120), ExtendedFastChoice("7 days", 168),
    )
    /** The longest goal: ten days. */
    const val MAX_TARGET_HOURS = 240
    /** The shortest "until" goal. */
    const val MIN_UNTIL_HOURS = 12
    /** The "Until …" choices are this hour of day. */
    const val UNTIL_HOUR = 18
    /** A custom "until" time moves in steps of this many minutes. */
    const val UNTIL_STEP_MIN = 30
    /** Finished fasts kept in the history list (all of them stay in the data and the export). */
    const val HISTORY_MAX = 30
    /** Weeks in the heat strip. */
    const val HEAT_WEEKS = 12
    /** A start can be set at most this far back. */
    const val MAX_BACKDATE_MIN = 48 * 60
    /** "End fast" can be undone for this long. */
    const val RESUME_WINDOW_MS = 10 * 60_000L
    /** The meal block the planner keeps free. */
    const val MEAL_MIN = 30
    private const val HOUR_MS = 3_600_000L

    fun planAt(index: Int): FastingPlanChoice = PLAN_CHOICES[index.coerceIn(0, PLAN_CHOICES.size - 1)]

    /** "16:8" for a preset (or any plan whose window matches its goal), else "16 h". */
    fun planLabel(p: FastingPlan): String {
        val match = PLAN_CHOICES.firstOrNull { it.targetHours == p.targetHours && it.eatingStartMin == p.eatingStartMin && it.eatingEndMin == p.eatingEndMin }
        return match?.label ?: "${p.targetHours} h"
    }

    fun startedAgoLabel(min: Int): String = when {
        min == 0 -> "Now"
        min < 60 -> "$min min ago"
        else -> "${min / 60} h ago"
    }

    fun moveLabel(min: Int): String = (if (min < 0) "−" else "+") + (if (kotlin.math.abs(min) >= 60) "${kotlin.math.abs(min) / 60} h" else "${kotlin.math.abs(min)} min")

    /** "08:05" for a local minute of the day. */
    fun hm(minuteOfDay: Int): String {
        val m = minuteOfDay.mod(24 * 60)
        return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
    }

    /** The running timer, "14:05:09" (hours can pass 24). */
    fun clock(elapsedMs: Long): String {
        val s = (elapsedMs.coerceAtLeast(0) / 1000)
        return "${s / 3600}:${((s / 60) % 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** "16 h 20 m", "45 m". */
    fun duration(ms: Long): String {
        val totalMin = (ms.coerceAtLeast(0) / 60_000L)
        val h = totalMin / 60; val m = totalMin % 60
        return if (h == 0L) "$m m" else "$h h ${m.toString().padStart(2, '0')} m"
    }

    /** Share of the goal done, 0 to 1 (capped). */
    fun progress(startedAtMs: Long, targetHours: Int, nowMs: Long): Float =
        ((nowMs - startedAtMs).toDouble() / (targetHours.coerceAtLeast(1) * HOUR_MS)).coerceIn(0.0, 1.0).toFloat()

    fun goalAt(startedAtMs: Long, targetHours: Int): Long = startedAtMs + targetHours * HOUR_MS

    /** Share of the way from [startedAtMs] to [goalAtMs], 0 to 1 (capped). */
    fun progressTo(startedAtMs: Long, goalAtMs: Long, nowMs: Long): Float =
        ((nowMs - startedAtMs).toDouble() / (goalAtMs - startedAtMs).coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat()

    /** The earliest end a fast "until" a moment can have if it starts at [nowMs]. */
    fun untilEarliest(nowMs: Long): Long = nowMs + MIN_UNTIL_HOURS * HOUR_MS

    /** The latest end: ten days on. */
    fun untilLatest(nowMs: Long): Long = nowMs + MAX_TARGET_HOURS * HOUR_MS

    /** Whether a fast starting at [nowMs] can run until [untilMs]: "Goal 3 d 20 h · starts now", or why not. */
    fun untilPick(nowMs: Long, untilMs: Long): FastUntilPick = when {
        untilMs < untilEarliest(nowMs) -> FastUntilPick(false, "Pick an end at least $MIN_UNTIL_HOURS hours away")
        untilMs > untilLatest(nowMs) -> FastUntilPick(false, "Pick an end within ten days")
        else -> FastUntilPick(true, "Goal ${longDuration(untilMs - nowMs)} · starts now")
    }

    /** [minuteOfDay] moved by [steps] of [UNTIL_STEP_MIN], kept within the day (23:30 + 1 step is 00:00). */
    fun stepUntilTime(minuteOfDay: Int, steps: Int): Int {
        val snapped = minuteOfDay.mod(24 * 60) / UNTIL_STEP_MIN * UNTIL_STEP_MIN
        return (snapped + steps * UNTIL_STEP_MIN).mod(24 * 60)
    }

    /** The command bar's "Start a 5-day fast" / "Start a 36 h fast". */
    fun startTitle(hours: Int): String = "Start a ${extendedTitle(hours)}"

    /** "5-day fast", "36 h fast", "24 h fast". */
    fun extendedTitle(targetHours: Int): String =
        if (targetHours >= 48 && targetHours % 24 == 0) "${targetHours / 24}-day fast" else "$targetHours h fast"

    /** "5 days", "36 h", "1 day". */
    fun daysLabel(hours: Int): String = when {
        hours % 24 == 0 && hours >= 24 -> "${hours / 24} ${if (hours == 24) "day" else "days"}"
        else -> "$hours h"
    }

    /** "4 d 18 h" from two days up, else [duration]. */
    fun longDuration(ms: Long): String {
        val totalH = ms.coerceAtLeast(0) / HOUR_MS
        return if (totalH >= 48) "${totalH / 24} d ${(totalH % 24).toString().padStart(2, '0')} h" else duration(ms)
    }

    /** "Day 3 of 5 · 62 h"; past the goal "Day 6 · 122 h · goal reached". */
    fun dayLine(startedAtMs: Long, goalAtMs: Long, nowMs: Long): String {
        val h = (nowMs - startedAtMs).coerceAtLeast(0) / HOUR_MS
        return if (nowMs >= goalAtMs) "${dayOf(startedAtMs, goalAtMs, nowMs)} · $h h · goal reached" else "${dayOf(startedAtMs, goalAtMs, nowMs)} · $h h"
    }

    /** "Day 3 of 5"; past the goal "Day 6". Changes only once a day ([nextDayAt]). */
    fun dayOf(startedAtMs: Long, goalAtMs: Long, nowMs: Long): String {
        val day = (nowMs - startedAtMs).coerceAtLeast(0) / (24 * HOUR_MS) + 1
        val totalDays = ((goalAtMs - startedAtMs).coerceAtLeast(1) + 24 * HOUR_MS - 1) / (24 * HOUR_MS)
        return if (nowMs >= goalAtMs) "Day $day" else "Day $day of $totalDays"
    }

    /** When [dayOf] next changes. */
    fun nextDayAt(startedAtMs: Long, nowMs: Long): Long =
        startedAtMs + ((nowMs - startedAtMs).coerceAtLeast(0) / (24 * HOUR_MS) + 1) * 24 * HOUR_MS

    /** "You did it · 5 days" (whole days) or "You did it · 36 h". */
    fun doneLine(startedAtMs: Long, goalAtMs: Long): String =
        "You did it · ${daysLabel((((goalAtMs - startedAtMs).coerceAtLeast(0) + HOUR_MS - 1) / HOUR_MS).toInt())}"

    /** A check-in closer than this to the goal is left out: the goal's own "you did it" follows soon enough. */
    const val CHECK_IN_GAP_MS = 6 * HOUR_MS

    /**
     * When an extended fast checks in: once a day, as each new day of the fast begins (24 h, 48 h … after its start),
     * never within [CHECK_IN_GAP_MS] of the goal. At most nine (goals go up to ten days). Daily-window fasts don't.
     */
    fun checkInTimes(startedAtMs: Long, goalAtMs: Long): List<Long> =
        (1..MAX_TARGET_HOURS / 24).map { startedAtMs + it * 24 * HOUR_MS }.takeWhile { it <= goalAtMs - CHECK_IN_GAP_MS }

    /** 0 none · 1 under 8 h · 2 under 16 h · 3 under a whole day · 4 the whole day. */
    fun heatLevel(hours: Double): Int = when {
        hours <= 0.0 -> 0
        hours < 8.0 -> 1
        hours < 16.0 -> 2
        hours < 23.5 -> 3
        else -> 4
    }

    /** True when [minuteOfDay] is inside the eating window (which may cross midnight). */
    fun inWindow(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean =
        if (startMin <= endMin) minuteOfDay in startMin until endMin else minuteOfDay >= startMin || minuteOfDay < endMin

    fun windowLine(minuteOfDay: Int, plan: FastingPlan): String {
        val s = plan.eatingStartMin; val e = plan.eatingEndMin
        return when {
            inWindow(minuteOfDay, s, e) -> "Eating window open until ${hm(e)}"
            s > e || minuteOfDay < s -> "Eating window opens at ${hm(s)}"
            else -> "Eating window closed at ${hm(e)}"
        }
    }

    internal fun dayLabel(epochDay: Long): String =
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[CivilDate.isoDayOfWeek(epochDay) - 1]
}

/** Fasting commands and the [view] projection over a [Replica]. Every write is an op: offline-first, synced. */
class Fasting(
    private val replica: Replica,
    private val ids: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    private data class Fast(val id: String, val start: Long, val end: Long?, val target: Int, val goalAt: Long? = null, val extended: Boolean = false) {
        /** When the goal is reached. */
        val goal: Long get() = goalAt ?: (start + target * HOUR)
    }

    // ---- Plan ----

    fun plan(): FastingPlan {
        val s = replica.entity(EntityTypes.FASTING_PLAN, PLAN_ID)?.takeIf { !it.deleted } ?: return FastingRules.DEFAULT_PLAN
        val d = FastingRules.DEFAULT_PLAN
        return FastingPlan(
            targetHours = (s[FastingPlanFields.TARGET_HOURS].longOrNull?.toInt() ?: d.targetHours).coerceIn(1, FastingRules.MAX_TARGET_HOURS),
            eatingStartMin = (s[FastingPlanFields.EATING_START_MIN].longOrNull?.toInt() ?: d.eatingStartMin).mod(24 * 60),
            eatingEndMin = (s[FastingPlanFields.EATING_END_MIN].longOrNull?.toInt() ?: d.eatingEndMin).mod(24 * 60),
        )
    }

    fun setPlan(targetHours: Int, eatingStartMin: Int, eatingEndMin: Int) {
        checkTarget(targetHours)
        if (eatingStartMin !in 0 until 24 * 60 || eatingEndMin !in 0 until 24 * 60) throw ValidationException("Pick a time of day")
        if (eatingStartMin == eatingEndMin) throw ValidationException("The eating window needs a start and an end")
        replica.commitLocal(
            EntityTypes.FASTING_PLAN, PLAN_ID,
            linkedMapOf(
                FastingPlanFields.TARGET_HOURS to targetHours.fv(),
                FastingPlanFields.EATING_START_MIN to eatingStartMin.fv(),
                FastingPlanFields.EATING_END_MIN to eatingEndMin.fv(),
            ),
        )
    }

    fun choosePlan(index: Int) {
        val c = FastingRules.planAt(index)
        setPlan(c.targetHours, c.eatingStartMin, c.eatingEndMin)
    }

    // ---- Fasts ----

    /** Starts a fast [startedMinutesAgo] minutes ago with the plan's goal unless [targetHours] is given. */
    fun start(startedMinutesAgo: Int = 0, targetHours: Int? = null): String =
        begin(startedMinutesAgo, targetHours ?: plan().targetHours, extended = false, goalAt = null)

    /** Starts an extended fast of [hours] (e.g. 120 for a 5-day fast). */
    fun startExtended(hours: Int, startedMinutesAgo: Int = 0): String =
        begin(startedMinutesAgo, hours, extended = true, goalAt = null)

    /** Starts an extended fast that runs until [untilMs] ("until Friday 18:00"). */
    fun startUntil(untilMs: Long, startedMinutesAgo: Int = 0): String {
        val start = nowMs() - startedMinutesAgo * 60_000L
        FastingRules.untilPick(start, untilMs).takeIf { !it.ok }?.let { throw ValidationException(it.line) }
        return begin(startedMinutesAgo, ((untilMs - start + HOUR - 1) / HOUR).toInt(), extended = true, goalAt = untilMs)
    }

    private fun begin(startedMinutesAgo: Int, target: Int, extended: Boolean, goalAt: Long?): String {
        if (open().isNotEmpty()) throw ValidationException("You're already fasting")
        if (startedMinutesAgo !in 0..FastingRules.MAX_BACKDATE_MIN) throw ValidationException("Pick a start in the last two days")
        checkTarget(target)
        val start = nowMs() - startedMinutesAgo * 60_000L
        // A fast can't begin inside one you've already finished.
        if (finished().any { start < it.end!! }) throw ValidationException("That overlaps your last fast")
        val id = ids()
        val fields = linkedMapOf(
            FastFields.STARTED_AT to start.fv(),
            FastFields.TARGET_HOURS to target.fv(),
            ActionableFields.CREATED_AT to nowMs().fv(),
            ActionableFields.PROVENANCE_SOURCE to "user".fv(),
            ActionableFields.PROVENANCE_TRUST to Trust.TRUSTED_USER.name.fv(),
        )
        if (extended) fields[FastFields.KIND] = KIND_EXTENDED.fv()
        if (goalAt != null) fields[FastFields.GOAL_AT] = goalAt.fv()
        replica.commitLocal(EntityTypes.FAST, id, fields)
        return id
    }

    /** Ends the running fast now (every open one, see the file comment). */
    fun end() {
        val open = open()
        if (open.isEmpty()) throw ValidationException("You're not fasting")
        val now = nowMs()
        open.forEach { replica.commitLocal(EntityTypes.FAST, it.id, mapOf(FastFields.ENDED_AT to maxOf(now, it.start).fv())) }
    }

    /** Undoes "End fast" for a few minutes after it, when no other fast has started. */
    fun resume(id: String) {
        val f = live(id) ?: throw ValidationException("Fast not found")
        val end = f.end ?: return
        if (open().isNotEmpty()) throw ValidationException("You're already fasting")
        if (nowMs() - end > FastingRules.RESUME_WINDOW_MS) throw ValidationException("That fast ended a while ago")
        replica.commitLocal(EntityTypes.FAST, id, mapOf(FastFields.ENDED_AT to FieldValue.Null))
    }

    /** Changes the running fast's goal (in hours from its start; an "until" goal gives way to it). */
    fun setTarget(hours: Int) {
        checkTarget(hours)
        val f = current() ?: throw ValidationException("You're not fasting")
        val fields = linkedMapOf(FastFields.TARGET_HOURS to hours.fv())
        if (f.goalAt != null) fields[FastFields.GOAL_AT] = FieldValue.Null
        replica.commitLocal(EntityTypes.FAST, f.id, fields)
    }

    /** Moves the running fast's start by [deltaMinutes] (negative: earlier). */
    fun moveStart(deltaMinutes: Int) {
        val f = current() ?: throw ValidationException("You're not fasting")
        val start = f.start + deltaMinutes * 60_000L
        val now = nowMs()
        if (start > now) throw ValidationException("That's in the future")
        // Only an earlier start is limited (a fast of days is already further back than that).
        if (deltaMinutes < 0 && now - start > FastingRules.MAX_BACKDATE_MIN * 60_000L && now - f.start <= FastingRules.MAX_BACKDATE_MIN * 60_000L)
            throw ValidationException("Pick a start in the last two days")
        if (finished().any { start < it.end!! }) throw ValidationException("That overlaps your last fast")
        replica.commitLocal(EntityTypes.FAST, f.id, mapOf(FastFields.STARTED_AT to start.fv()))
    }

    /** Throws the running fast away (started by mistake); it leaves no history. */
    fun discard() {
        val open = open()
        if (open.isEmpty()) throw ValidationException("You're not fasting")
        open.forEach { replica.commitLocal(EntityTypes.FAST, it.id, mapOf(ActionableFields.DELETED to true.fv())) }
    }

    /** Throws away fast [id] while it still runs (an Ask card's Undo); false once it has ended or gone. */
    fun discardIfOpen(id: String): Boolean {
        if (open().none { it.id == id }) return false
        replica.commitLocal(EntityTypes.FAST, id, mapOf(ActionableFields.DELETED to true.fv()))
        return true
    }

    // ---- Reads ----

    fun view(): FastingView {
        val now = nowMs()
        val plan = plan()
        val today = calendar.epochDayOf(now)
        val cur = current()?.let { f ->
            val goal = f.goal
            val reached = now >= goal
            val goalText = if (f.extended) dayTime(goal) else at(goal, today)
            FastNow(
                id = f.id,
                startedAtMs = f.start,
                targetHours = f.target,
                goalAtMs = goal,
                reachedGoal = reached,
                startedLine = "Started ${at(f.start, today)}",
                goalLine = when {
                    reached -> "Goal reached at $goalText"
                    f.extended && f.goalAt != null -> "Goal $goalText"
                    f.extended -> "Goal ${FastingRules.daysLabel(f.target)} · $goalText"
                    else -> "Goal ${f.target} h · at $goalText"
                },
                extended = f.extended,
                title = if (f.extended) titleOf(f) else "Fasting · goal ${f.target} h",
                dayLine = if (f.extended) FastingRules.dayLine(f.start, goal, now) else null,
                goalWhen = if (f.extended) dayTime(goal) else FastingRules.hm(calendar.minuteOfDay(goal)),
                doneLine = if (f.extended && reached) FastingRules.doneLine(f.start, goal) else null,
            )
        }
        val history = merged()
        val last = history.maxByOrNull { it.end!! }?.let { f ->
            val ms = f.end!! - f.start
            val reached = f.end >= f.goal
            LastFast(
                id = f.id, startedAtMs = f.start, endedAtMs = f.end, hours = ms / 3_600_000.0, reachedGoal = reached,
                line = "Last fast ${FastingRules.longDuration(ms)}" + when {
                    reached -> " · goal reached"
                    f.extended -> " · goal ${FastingRules.daysLabel(((f.goal - f.start) / HOUR).toInt())}"
                    else -> " · goal ${f.target} h"
                },
                canResume = cur == null && now - f.end in 0..FastingRules.RESUME_WINDOW_MS,
            )
        }
        val days = (today - 6..today).map { day ->
            val ended = history.filter { calendar.epochDayOf(it.end!!) == day }
            val best = ended.maxByOrNull { it.end!! - it.start }
            FastingDay(
                epochDay = day,
                label = FastingRules.dayLabel(day),
                hours = best?.let { (it.end!! - it.start) / 3_600_000.0 } ?: 0.0,
                reachedGoal = ended.any { it.end!! >= it.goal },
                isToday = day == today,
            )
        }
        val inWeek = history.filter { calendar.epochDayOf(it.end!!) in (today - 6)..today }
        val weekLine = inWeek.takeIf { it.isNotEmpty() }?.let { fs ->
            val avg = fs.sumOf { it.end!! - it.start } / fs.size
            val met = fs.count { it.end!! >= it.goal }
            "${fs.size} ${if (fs.size == 1) "fast" else "fasts"} in 7 days · average ${FastingRules.duration(avg)} · $met reached the goal"
        }
        return FastingView(
            plan, cur, last, FastingRules.windowLine(calendar.minuteOfDay(now), plan), days, weekLine,
            history = history(history, current(), now, today),
            untilChoices = (1..6).map { ahead ->
                val ms = calendar.toEpochMs(today + ahead, FastingRules.UNTIL_HOUR * 60)
                FastUntilChoice("Until ${dayTime(ms)}", ms)
            },
            untilDays = untilDays(now, today),
            untilCalendar = calendar,
        )
    }

    /** Days with at least one valid custom "until" end: from the day 12 hours on falls in to the day ten days on does. */
    private fun untilDays(now: Long, today: Long): List<FastUntilDay> {
        val first = calendar.epochDayOf(FastingRules.untilEarliest(now))
        val last = calendar.epochDayOf(FastingRules.untilLatest(now))
        return (first..last).map { d ->
            val label = when (d - today) {
                0L -> "Today"
                1L -> "Tomorrow"
                else -> CivilDate.shortLabel(d).substringBeforeLast(' ') // "Sat 10"
            }
            FastUntilDay(label, d)
        }
    }

    /** Every finished fast (newest first), the streak, the totals and the heat strip. */
    private fun history(merged: List<Fast>, running: Fast?, now: Long, today: Long): FastingHistory {
        val newest = merged.sortedByDescending { it.end!! }
        val records = newest.take(FastingRules.HISTORY_MAX).map { f ->
            val planned = f.goal - f.start
            val actual = f.end!! - f.start
            val reached = f.end >= f.goal
            val plannedText = if (f.extended) FastingRules.daysLabel(((planned + HOUR - 1) / HOUR).toInt()) else "${f.target} h"
            FastRecord(
                id = f.id, startedAtMs = f.start, endedAtMs = f.end, plannedMs = planned, actualMs = actual,
                reachedGoal = reached, extended = f.extended,
                title = if (f.extended) titleOf(f) else "${f.target} h fast",
                whenLine = "${dateTime(f.start)} – ${if (calendar.epochDayOf(f.start) == calendar.epochDayOf(f.end)) FastingRules.hm(calendar.minuteOfDay(f.end)) else dateTime(f.end)}",
                resultLine = if (reached) "${FastingRules.longDuration(actual)} · goal reached"
                else "${FastingRules.longDuration(actual)} of $plannedText · ended early",
            )
        }
        val streak = newest.takeWhile { it.end!! >= it.goal }.size
        val totals = newest.takeIf { it.isNotEmpty() }?.let { fs ->
            val met = fs.count { it.end!! >= it.goal }
            val longest = fs.maxOf { it.end!! - it.start }
            "${fs.size} ${if (fs.size == 1) "fast" else "fasts"} · $met reached the goal · longest ${FastingRules.longDuration(longest)}"
        }
        // The heat strip: Monday-to-Sunday weeks ending with this one; a running fast counts up to now.
        val monday = today - (CivilDate.isoDayOfWeek(today) - 1)
        val first = monday - 7L * (FastingRules.HEAT_WEEKS - 1)
        val spans = merged.map { it.start to it.end!! } + listOfNotNull(running?.let { it.start to now })
        val heat = (0 until FastingRules.HEAT_WEEKS).map { w ->
            (0 until 7).map { d ->
                val day = first + 7L * w + d
                val from = calendar.toEpochMs(day, 0)
                val to = minOf(calendar.toEpochMs(day + 1, 0), now)
                val ms = if (day > today) 0L else spans.sumOf { (s, e) -> (minOf(e, to) - maxOf(s, from)).coerceAtLeast(0) }
                val hours = ms / HOUR.toDouble()
                FastingHeatDay(day, hours, FastingRules.heatLevel(hours), day == today, day > today)
            }
        }
        return FastingHistory(
            fasts = records,
            streak = streak,
            streakLine = when (streak) {
                0 -> null
                1 -> "Your last fast reached its goal"
                else -> "$streak fasts in a row reached the goal"
            },
            totalsLine = totals,
            heat = heat,
        )
    }

    /**
     * Meals the planner keeps free today: breaking the fast when its goal lands later today, and a last meal before
     * the eating window closes when that is still ahead and you're not fasting through it.
     */
    fun plannerMeals(day: DayWindow): List<DayPlanner.MealBlock> {
        val now = nowMs()
        val meal = FastingRules.MEAL_MIN * 60_000L
        val out = mutableListOf<DayPlanner.MealBlock>()
        val cur = current()
        var eatingFrom = now
        if (cur != null) {
            val goal = cur.goal
            if (goal >= now && goal in day) out += DayPlanner.MealBlock("Break your fast", goal, goal + meal)
            eatingFrom = maxOf(goal, now) + meal
        }
        val plan = plan()
        val close = calendar.toEpochMs(day.epochDay, plan.eatingEndMin)
        if (close in day && close - meal >= eatingFrom) out += DayPlanner.MealBlock("Last meal before your fast", close - meal, close)
        return out
    }

    // ---- Helpers ----

    private fun all(): List<Fast> = replica.entities(EntityTypes.FAST).mapNotNull { s -> s.toFast() }

    private fun EntitySnapshot.toFast(): Fast? {
        if (deleted) return null
        val start = this[FastFields.STARTED_AT].longOrNull ?: return null
        val end = this[FastFields.ENDED_AT].longOrNull
        val target = (this[FastFields.TARGET_HOURS].longOrNull?.toInt() ?: FastingRules.DEFAULT_PLAN.targetHours).coerceIn(1, FastingRules.MAX_TARGET_HOURS)
        val goalAt = this[FastFields.GOAL_AT].longOrNull?.takeIf { it > start }
        val extended = this[FastFields.KIND].textOrNull == KIND_EXTENDED
        return Fast(ref.entityId, start, end?.let { maxOf(it, start) }, target, goalAt, extended)
    }

    private fun live(id: String): Fast? = replica.entity(EntityTypes.FAST, id)?.toFast()

    private fun open(): List<Fast> = all().filter { it.end == null }

    /** Finished fasts (overlapping ones merged), oldest first: for the weekly review. */
    fun ended(): List<EndedFast> = merged().map { EndedFast(it.start, it.end!!, it.target, it.goal - it.start) }

    private fun finished(): List<Fast> = all().filter { it.end != null }

    /** The fast that counts: the earliest-started open one. */
    private fun current(): Fast? = open().minWithOrNull(compareBy<Fast> { it.start }.thenBy { it.id })

    /** Finished fasts with overlapping ones merged (the earlier start's goal kept). */
    private fun merged(): List<Fast> {
        val out = mutableListOf<Fast>()
        for (f in finished().sortedWith(compareBy<Fast> { it.start }.thenBy { it.id })) {
            val prev = out.lastOrNull()
            if (prev != null && f.start < prev.end!!) out[out.size - 1] = prev.copy(end = maxOf(prev.end, f.end!!))
            else out += f
        }
        return out
    }

    /** "5-day fast", "Fast until Fri 18:00". */
    private fun titleOf(f: Fast): String = if (f.goalAt != null) "Fast until ${dayTime(f.goalAt)}" else FastingRules.extendedTitle(f.target)

    /** "Sat 20:00". */
    private fun dayTime(ms: Long): String =
        "${FastingRules.dayLabel(calendar.epochDayOf(ms))} ${FastingRules.hm(calendar.minuteOfDay(ms))}"

    /** "Mon 5 Oct 20:00". */
    private fun dateTime(ms: Long): String {
        return "${CivilDate.shortLabel(calendar.epochDayOf(ms))} ${FastingRules.hm(calendar.minuteOfDay(ms))}"
    }

    /** "20:05", "20:05 yesterday", "12:05 tomorrow", "Mon 08:00". */
    private fun at(ms: Long, today: Long): String {
        val t = FastingRules.hm(calendar.minuteOfDay(ms))
        return when (calendar.epochDayOf(ms) - today) {
            0L -> t
            -1L -> "$t yesterday"
            1L -> "$t tomorrow"
            else -> "${FastingRules.dayLabel(calendar.epochDayOf(ms))} $t"
        }
    }

    private fun checkTarget(hours: Int) {
        if (hours !in 1..FastingRules.MAX_TARGET_HOURS) throw ValidationException("Pick a goal up to ten days")
    }

    companion object {
        const val PLAN_ID = "default"
        const val KIND_EXTENDED = "extended"
        private const val HOUR = 3_600_000L
    }
}
