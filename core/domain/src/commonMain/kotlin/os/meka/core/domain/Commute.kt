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
 * line adds them ([withDayLine]). Slice 5: Ask/Talk ([CommuteAskRules]). Slice 6: **one-off times for a single day**
 * ([CommuteDay], "engineering works on Tuesday, 1 h 45 there"): every use of the commute reads [CommuteSetting.forDay],
 * so the wake alarm, Today's bands, clashes, the planner, the brief, Shut down, Leaving now and Ask all follow it.
 * Slice 7: the disruption heads-up. Slice 8: **the commute in onboarding**, a Setup step ([SetupRules.commuteStep]) on
 * both apps that shows MEKA's guess until he sets his own. Later: the train-anchored wake (Darwin key).
 */
object CommuteFields {
    const val TO_WORK = "commuteToWorkMin"
    const val HOME = "commuteHomeMin"
    /**
     * Slice 6 (ADR-008 addendum 2026-10-13, additive): one-off times, "epochDay:toWorkMin:homeMin" comma-separated,
     * ascending, past days dropped on each write. Text, last writer wins (like `workHomeDays`).
     */
    const val DAYS = "commuteDays"
}

/** One day's own door-to-door times (slice 6), instead of the usual ones. */
data class CommuteDay(val epochDay: Long, val toWorkMin: Int, val homeMin: Int)

/**
 * The two door-to-door times; [chosen] is false while both are the never-touched default. [days] are one-off times for
 * single days (slice 6); [forDay] is what a given day uses.
 */
data class CommuteSetting(
    val toWorkMin: Int,
    val homeMin: Int,
    val chosen: Boolean = false,
    val days: List<CommuteDay> = emptyList(),
) {
    /** [epochDay]'s one-off times, or null when it uses the usual ones. */
    fun dayOn(epochDay: Long): CommuteDay? = days.firstOrNull { it.epochDay == epochDay }

    /** The times [epochDay] uses: its one-off ones, else the usual ones (the one-offs dropped either way). */
    fun forDay(epochDay: Long): CommuteSetting {
        val d = dayOn(epochDay) ?: return copy(days = emptyList())
        return CommuteSetting(d.toWorkMin, d.homeMin, chosen = true)
    }

    companion object {
        val DEFAULT = CommuteSetting(CommuteRules.DEFAULT_MIN, CommuteRules.DEFAULT_MIN, chosen = false)
    }
}

/**
 * Work mode's "Just one day" row (slice 6): an office day in the coming week, its times and whether they're one-off.
 * [line] is "Tue 14 Oct · 1 h 20 each way" or "Tue 14 Oct · 1 h 45 there · 1 h 20 home · just this day".
 */
data class CommuteDayRow(
    val epochDay: Long,
    val label: String,
    val toWorkMin: Int,
    val homeMin: Int,
    val changed: Boolean,
    val line: String,
)

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
        val days = decodeDays(fields?.get(CommuteFields.DAYS)?.textOrNull)
        return CommuteSetting(to ?: DEFAULT_MIN, home ?: DEFAULT_MIN, chosen = to != null || home != null, days = days)
    }

    /** [CommuteFields.DAYS] read: unreadable or out-of-range entries skipped, one per day (the last wins), ascending. */
    fun decodeDays(s: String?): List<CommuteDay> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(',').mapNotNull { e ->
            val p = e.trim().split(':')
            if (p.size != 3) return@mapNotNull null
            val day = p[0].toLongOrNull() ?: return@mapNotNull null
            val to = p[1].toIntOrNull()?.takeIf(::valid) ?: return@mapNotNull null
            val home = p[2].toIntOrNull()?.takeIf(::valid) ?: return@mapNotNull null
            CommuteDay(day, to, home)
        }.associateBy { it.epochDay }.values.sortedBy { it.epochDay }
    }

    fun encodeDays(days: List<CommuteDay>): String =
        days.sortedBy { it.epochDay }.joinToString(",") { "${it.epochDay}:${it.toWorkMin}:${it.homeMin}" }

    /**
     * Slice 6: [days] with [epochDay] set to its own times (or back to the usual ones when [to]/[home] are null or equal
     * to [usual]'s), days before [today] dropped. One entry per day.
     */
    fun withDay(days: List<CommuteDay>, usual: CommuteSetting, epochDay: Long, to: Int?, home: Int?, today: Long): List<CommuteDay> {
        val kept = days.filter { it.epochDay >= today && it.epochDay != epochDay }
        val same = to == null || home == null || (to == usual.toWorkMin && home == usual.homeMin)
        return (if (same) kept else kept + CommuteDay(epochDay, to!!, home!!)).sortedBy { it.epochDay }
    }

    /** How far ahead "Just one day" offers office days (today and the next six). */
    const val DAY_ROWS_AHEAD = 7
    const val ONE_DAY_TITLE = "Just one day"
    const val ONE_DAY_NOTE = "Engineering works or a different route? Change one day's times; the next day goes back to the usual."
    const val JUST_THIS_DAY = "just this day"

    /** "1 h 20 each way" · "1 h 45 there · 1 h 20 home" (no "about", no default wording). */
    fun timesLine(toWorkMin: Int, homeMin: Int): String =
        if (toWorkMin == homeMin) "${label(toWorkMin)} each way" else "${label(toWorkMin)} there · ${label(homeMin)} home"

    /** The office days from [today] for [DAY_ROWS_AHEAD] days, each with the times it uses (slice 6). */
    fun dayRows(work: WorkHours, today: Long): List<CommuteDayRow> =
        (today until today + DAY_ROWS_AHEAD).filter { officeDay(work, it) }.map { day ->
            val own = work.commute.dayOn(day)
            val c = work.commute.forDay(day)
            val label = CivilDate.shortLabel(day)
            CommuteDayRow(
                day, label, c.toWorkMin, c.homeMin, changed = own != null,
                line = listOfNotNull(label, timesLine(c.toWorkMin, c.homeMin), JUST_THIS_DAY.takeIf { own != null }).joinToString(" · "),
            )
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

    /** An office day: a work day that isn't from home or on a trip ([WorkHours.atOffice]). */
    fun officeDay(work: WorkHours, epochDay: Long): Boolean = work.atOffice(epochDay)

    /** The morning commute on [epochDay] in minutes, or null when it isn't an office day. */
    fun toWorkOn(work: WorkHours, epochDay: Long): Int? = if (officeDay(work, epochDay)) work.commute.forDay(epochDay).toWorkMin else null

    /** The commute home on [epochDay] in minutes, or null when it isn't an office day. */
    fun homeOn(work: WorkHours, epochDay: Long): Int? = if (officeDay(work, epochDay)) work.commute.forDay(epochDay).homeMin else null

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
        val to = work.commute.forDay(epochDay).toWorkMin
        val home = work.commute.forDay(epochDay).homeMin
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
     * A commute line as drawn on a narrow screen: "about" is kept with its time (a no-break space), so "Commute home ·
     * Home about 18:50" wraps before "about" rather than leaving "about" alone at a line's end (the quality gate's Today
     * re-shot, 2026-10-22). For the words shown only; what is spoken or read by TalkBack keeps the plain line.
     */
    fun shown(line: String): String = line.replace("about ", "about$NO_BREAK")

    /** U+00A0: a space a line never breaks at. */
    const val NO_BREAK = " "

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
     * the other is cut off by midnight), "… · just this day" when the day has one-off times; null on any other day.
     */
    fun calendarLine(work: WorkHours, epochDay: Long, calendar: LocalCalendar): String? {
        val bs = bands(work, epochDay, calendar)
        if (bs.isEmpty()) return null
        return listOfNotNull(
            bs.firstOrNull { it.toWork }?.let { "Leave ${LocalClock.formatMinute(it.startMinute)}" },
            bs.firstOrNull { !it.toWork }?.let { "home about ${LocalClock.formatMinute(it.endMinute)}" },
            JUST_THIS_DAY.takeIf { work.commute.dayOn(epochDay) != null },
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
