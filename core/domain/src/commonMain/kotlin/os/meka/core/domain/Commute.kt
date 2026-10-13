package os.meka.core.domain

import os.meka.core.sync.FieldValue

/**
 * The commute counts everywhere (Meka, 2026-10-12 20:51), slice 1: a **door-to-door commute time** per direction in
 * Work mode (home → office, office → home), default 1 h 20 (train, the change and the walks), editable on both apps.
 * It only applies on **office days**: a work day ([WorkHours.isWorkDay]: not a bank holiday, not booked leave, not a
 * manual "Work off") that isn't a work-from-home day. WFH, leave and days off have no commute.
 *
 * Stored on the synced `context_mode/work` entity (ADR-008 addendum 2026-10-13, additive, LWW, either app writes):
 * `commuteToWorkMin` and `commuteHomeMin` (Int64 minutes, 10–240 in fives; absent = the default). Non-AI, pure.
 *
 * Slice 1 uses it for the wake alarm (work start − morning commute − time to get ready). Later slices: Today's and the
 * Calendar's commute bands, clashes against arrival home, the planner, Leaving now, the brief's leave-by.
 */
object CommuteFields {
    const val TO_WORK = "commuteToWorkMin"
    const val HOME = "commuteHomeMin"
}

/** The two door-to-door times; [chosen] is false while both are the never-touched default. */
data class CommuteSetting(val toWorkMin: Int, val homeMin: Int, val chosen: Boolean = false) {
    companion object {
        val DEFAULT = CommuteSetting(CommuteRules.DEFAULT_MIN, CommuteRules.DEFAULT_MIN, chosen = false)
    }
}

object CommuteRules {
    /** Train + change + walks, until Meka sets his own (build plan, 2026-10-12). */
    const val DEFAULT_MIN = 80
    const val MIN_MIN = 10
    const val MAX_MIN = 240
    const val STEP_MIN = 5
    /** The chips both apps offer (his own time is added when it isn't one of them). */
    val CHOICES: List<Int> = listOf(45, 60, 70, 80, 90, 100, 120)

    const val TITLE = "Commute"
    const val NOTE = "Door to door, on office days only (not on work-from-home days, leave or bank holidays). " +
        "The wake alarm counts it: work start, less the commute, less time to get ready."

    fun valid(min: Int): Boolean = min in MIN_MIN..MAX_MIN && min % STEP_MIN == 0

    /** The setting from the work entity's fields; an unreadable or out-of-range time reads as the default. */
    fun read(fields: Map<String, FieldValue>?): CommuteSetting {
        val to = fields?.get(CommuteFields.TO_WORK)?.longOrNull?.toInt()?.takeIf(::valid)
        val home = fields?.get(CommuteFields.HOME)?.longOrNull?.toInt()?.takeIf(::valid)
        return CommuteSetting(to ?: DEFAULT_MIN, home ?: DEFAULT_MIN, chosen = to != null || home != null)
    }

    /** "45 min" · "1 h" · "1 h 20". */
    fun label(min: Int): String = AlarmRules.duration(min)

    /**
     * Work mode's line: "About 1 h 20 each way · change it below" (the default), "1 h 20 each way", or
     * "1 h 20 there · 1 h 10 home".
     */
    fun summary(s: CommuteSetting): String = when {
        !s.chosen -> "About ${label(s.toWorkMin)} each way · change it below"
        s.toWorkMin == s.homeMin -> "${label(s.toWorkMin)} each way"
        else -> "${label(s.toWorkMin)} there · ${label(s.homeMin)} home"
    }

    /** The chips for one direction: [CHOICES] plus [current] when it is his own odd time, ascending. */
    fun choices(current: Int): List<Int> = (CHOICES + current).distinct().sorted()

    /** An office day: a work day that isn't from home. */
    fun officeDay(work: WorkHours, epochDay: Long): Boolean = work.isWorkDay(epochDay) && epochDay !in work.homeDays

    /** The morning commute on [epochDay] in minutes, or null when it isn't an office day. */
    fun toWorkOn(work: WorkHours, epochDay: Long): Int? = if (officeDay(work, epochDay)) work.commute.toWorkMin else null

    /** The commute home on [epochDay] in minutes, or null when it isn't an office day. */
    fun homeOn(work: WorkHours, epochDay: Long): Int? = if (officeDay(work, epochDay)) work.commute.homeMin else null
}
