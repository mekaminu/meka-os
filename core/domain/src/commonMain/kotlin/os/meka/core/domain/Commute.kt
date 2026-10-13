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
 * Slice 1 uses it for the wake alarm (work start − morning commute − time to get ready). Slice 2: Today's commute bands
 * ([CommuteRules.bands], a thin row before and after the office block, not events) and the Calendar's line under an
 * office day's heading ([CommuteRules.calendarLine]). Slice 3: clashes against arrival home (and after he's left),
 * through [WorkClashRules] with the day's bands, on Today, the Calendar, the brief and the week ahead. Later slices: the
 * Slice 4: the planner keeps the commute clear ([plannerNote]), Leaving now's journey home is the commute home
 * ([LeavingRules.withCommute]), the brief says when to leave and when he's home ([briefLine]), and Shut down's tomorrow
 * line adds them ([withDayLine]). Later: the train-anchored wake (Darwin key), one-off overrides, Ask/Talk.
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

/**
 * One commute on a day, clipped to that day: to work ([toWork]) ends when the shift starts, home starts when it ends.
 * [startMinute]/[endMinute] are the clock times ("07:40", "18:50") even when clipped at midnight.
 */
data class CommuteBand(
    val toWork: Boolean,
    val startMs: Long,
    val endMs: Long,
    val startMinute: Int,
    val endMinute: Int,
) {
    /** "07:40–09:00" */
    val label: String get() = "${LocalClock.formatMinute(startMinute)}–${LocalClock.formatMinute(endMinute)}"
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

    /** Today's row titles for the two bands. */
    const val TO_WORK_TITLE = "Commute"
    const val HOME_TITLE = "Commute home"
    private const val DAY_MIN = 24 * 60
    private const val MIN_MS = 60_000L

    /**
     * The commute bands on [epochDay] (slice 2): on an office day, the morning commute ending as the day's shift starts
     * and the commute home starting as it ends, each clipped to the day (a band that would cross midnight is cut
     * there). None on WFH, leave, bank holidays or days off. A night shift's tail carried from the day before has no
     * band of its own.
     */
    fun bands(work: WorkHours, epochDay: Long, calendar: LocalCalendar): List<CommuteBand> {
        if (!officeDay(work, epochDay)) return emptyList()
        val h = work.hoursOn(epochDay)
        val dayStart = calendar.toEpochMs(epochDay, 0)
        val dayEnd = calendar.toEpochMs(epochDay + 1, 0)
        val shiftStart = calendar.toEpochMs(epochDay, h.startMinute)
        val to = work.commute.toWorkMin
        val home = work.commute.homeMin
        return buildList {
            val toStart = maxOf(dayStart, shiftStart - to * MIN_MS)
            if (shiftStart > toStart) {
                add(CommuteBand(true, toStart, shiftStart, (h.startMinute - to).mod(DAY_MIN), h.startMinute))
            }
            if (!h.crossesMidnight) {
                val shiftEnd = calendar.toEpochMs(epochDay, h.endMinute)
                val homeEnd = minOf(dayEnd, shiftEnd + home * MIN_MS)
                if (homeEnd > shiftEnd) {
                    add(CommuteBand(false, shiftEnd, homeEnd, h.endMinute, (h.endMinute + home).mod(DAY_MIN)))
                }
            }
        }
    }

    /** "Home about 18:50" */
    fun homeLine(minute: Int): String = "Home about ${LocalClock.formatMinute(minute)}"

    /**
     * A band's line on Today's timeline: the commute home says "Home about 18:50"; while either is on, "Now · at work
     * about 09:00" / "Now · home about 18:50" (lit); the morning one says nothing more until then (its time says it).
     */
    fun detail(b: CommuteBand, running: Boolean): String? = when {
        running && b.toWork -> "Now · at work about ${LocalClock.formatMinute(b.endMinute)}"
        running -> "Now · home about ${LocalClock.formatMinute(b.endMinute)}"
        b.toWork -> null
        else -> homeLine(b.endMinute)
    }

    /**
     * The Calendar's quiet line under an office day's heading: "Leave 07:40 · home about 18:50" (just one half when
     * the other is cut off by midnight); null on any other day.
     */
    fun calendarLine(work: WorkHours, epochDay: Long, calendar: LocalCalendar): String? {
        val bs = bands(work, epochDay, calendar)
        if (bs.isEmpty()) return null
        return listOfNotNull(
            bs.firstOrNull { it.toWork }?.let { "Leave ${LocalClock.formatMinute(it.startMinute)}" },
            bs.firstOrNull { !it.toWork }?.let { "home about ${LocalClock.formatMinute(it.endMinute)}" },
        ).joinToString(" · ").replaceFirstChar { it.uppercase() }
    }

    /**
     * Slice 4: a work line with the day's commute after it, "Work 09:00–17:30 · leave 07:40 · home about 18:50", on an
     * office day; [workLine] unchanged on any other day (or when it isn't a "Work …" line, e.g. annual leave).
     */
    fun withDayLine(workLine: String?, work: WorkHours, epochDay: Long, calendar: LocalCalendar): String? {
        if (workLine == null || !workLine.startsWith("Work ")) return workLine
        val line = calendarLine(work, epochDay, calendar) ?: return workLine
        return "$workLine · ${line.replaceFirstChar { it.lowercase() }}"
    }

    /**
     * Slice 4, the planner's note when the commute is kept clear: "◐ Your commute is kept clear (07:40–09:00 and
     * 17:30–18:50). Nothing is planned in it."; null when no band is still ahead of [nowMs].
     */
    fun plannerNote(bands: List<CommuteBand>, nowMs: Long): String? {
        val ahead = bands.filter { it.endMs > nowMs }
        if (ahead.isEmpty()) return null
        return "◐ Your commute is kept clear (${ahead.joinToString(" and ") { it.label }}). Nothing is planned in it."
    }

    /**
     * Slice 4, the brief's getting-there sentence on an office day: "Leave about 7:40, home about 6:50." (either half
     * alone when the other is cut by midnight); [clock] says a minute the brief's way.
     */
    fun briefLine(leaveMin: Int?, homeMin: Int?, clock: (Int) -> String): String? = when {
        leaveMin != null && homeMin != null -> "Leave about ${clock(leaveMin)}, home about ${clock(homeMin)}."
        leaveMin != null -> "Leave about ${clock(leaveMin)}."
        homeMin != null -> "Home about ${clock(homeMin)}."
        else -> null
    }
}
