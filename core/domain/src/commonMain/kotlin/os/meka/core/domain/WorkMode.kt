package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Work mode (build plan M1): work hours from a schedule plus a manual Work switch. While MEKA is in work mode the
 * Fold quietly holds WhatsApp, SMS and missed calls for the after-work summary ([AfterWorkSummaries]).
 *
 * Stored as one `context_mode` entity with the fixed id [ENTITY_ID], so the switch and the schedule sync between the
 * Fold and the Mac. Each of the two fields is written as one encoded value, so a concurrent edit on two devices can
 * never mix half of one schedule with half of another (plain last-writer-wins per field).
 */
object WorkFields {
    /** "days;start;end;on", e.g. "1,2,3,4,5;540;1050;1". Days are ISO (1 = Monday). Minutes are local minutes of day. */
    const val SCHEDULE = "workSchedule"
    /** "ON|OFF;setAtMs;scheduledWhenSet(0|1)", or Null when following the schedule. */
    const val SWITCH = "workSwitch"
    /** The call assistant's one switch (Bool, last writer wins; absent = off). Added 2026-10-07. */
    const val CALL_ASSISTANT = "callAssistant"
    /**
     * Work-from-home days (V1, requests slice 4: Add on a "Work from home · Thu 15 Oct" card): local epoch days,
     * comma-separated, ascending, past days dropped on each write. Text, last writer wins. Added 2026-10-09.
     */
    const val HOME_DAYS = "workHomeDays"
    /**
     * How long MEKA's server keeps callers' recordings (call assistant polish 8c, "Keep callers' recordings"): days as
     * Int64, one of [VoiceRecordingRules.KEEP_CHOICES] (0 = don't keep). Last writer wins; absent = 30. Added 2026-10-10.
     */
    const val RECORDING_DAYS = "callRecordingDays"
}

/** Local wall-clock position in the week: ISO day of week (1 = Monday … 7 = Sunday) and minute of day (0 … 1439). */
data class LocalClock(val isoDayOfWeek: Int, val minuteOfDay: Int) {
    init {
        require(isoDayOfWeek in 1..7) { "day of week must be 1..7" }
        require(minuteOfDay in 0 until MINUTES_PER_DAY) { "minute of day must be 0..1439" }
    }

    /** "09:00" */
    val hhmm: String get() = formatMinute(minuteOfDay)

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        fun formatMinute(m: Int): String = "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
        val DAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
}

/** One weekday's own hours when they differ from the usual ones (Thursday's short day, 09:00–15:30). */
data class DayHours(val startMinute: Int, val endMinute: Int) {
    init {
        require(startMinute in 0 until LocalClock.MINUTES_PER_DAY && endMinute in 0 until LocalClock.MINUTES_PER_DAY) { "minutes must be 0..1439" }
    }

    val crossesMidnight: Boolean get() = endMinute < startMinute

    /** "09:00–15:30" */
    val label: String get() = "${LocalClock.formatMinute(startMinute)}–${LocalClock.formatMinute(endMinute)}"
}

/** One work day in Work mode's hours editor: "Thu" · "09:00–15:30 · own hours". */
data class WorkDayRow(
    val isoDay: Int,
    val dayShort: String,
    val name: String,
    val startMinute: Int,
    val endMinute: Int,
    /** Its hours differ from the usual ones (lit in the accent). */
    val own: Boolean,
    val line: String,
)

/**
 * Work hours. A shift that ends at or before its start crosses midnight (it belongs to the day it starts on).
 * [enabled] = false means only the manual switch puts MEKA in work mode.
 *
 * [startMinute]–[endMinute] are the usual hours; [dayHours] holds the weekdays whose hours differ (Places item 1,
 * Meka 2026-10-09: "short day Thursday 09:00–15:30"). An override only counts for a day in [days].
 */
data class WorkSchedule(
    val days: Set<Int>,
    val startMinute: Int,
    val endMinute: Int,
    val enabled: Boolean = true,
    val dayHours: Map<Int, DayHours> = emptyMap(),
) {
    init {
        require(days.all { it in 1..7 }) { "days are ISO 1..7" }
        require(dayHours.keys.all { it in 1..7 }) { "days are ISO 1..7" }
        require(startMinute in 0 until LocalClock.MINUTES_PER_DAY && endMinute in 0 until LocalClock.MINUTES_PER_DAY) { "minutes must be 0..1439" }
    }

    /** The usual hours (the days without their own). */
    val usual: DayHours get() = DayHours(startMinute, endMinute)

    /** [isoDay]'s hours: its own when it has them, else the usual ones (whether or not it is a work day). */
    fun hoursOn(isoDay: Int): DayHours = dayHours[isoDay]?.takeIf { isoDay in days } ?: usual

    fun startOn(isoDay: Int): Int = hoursOn(isoDay).startMinute
    fun endOn(isoDay: Int): Int = hoursOn(isoDay).endMinute
    fun crossesMidnightOn(isoDay: Int): Boolean = hoursOn(isoDay).crossesMidnight

    /** Whether [isoDay] is a work day with a real shift (start ≠ end). */
    fun worksOn(isoDay: Int): Boolean = enabled && isoDay in days && hoursOn(isoDay).let { it.startMinute != it.endMinute }

    /** The usual hours' night-shift test; per day, [crossesMidnightOn]. */
    val crossesMidnight: Boolean get() = endMinute < startMinute

    /** The overrides that actually differ and fall on a work day. */
    val ownHours: Map<Int, DayHours> get() = dayHours.filter { (d, h) -> d in days && h != usual }

    fun isScheduled(c: LocalClock): Boolean {
        if (!enabled || days.isEmpty()) return false
        val today = c.isoDayOfWeek
        if (worksOn(today)) {
            val h = hoursOn(today)
            if (c.minuteOfDay >= h.startMinute && (h.crossesMidnight || c.minuteOfDay < h.endMinute)) return true
        }
        val yesterday = previousDay(today)
        if (worksOn(yesterday)) {
            val h = hoursOn(yesterday)
            if (h.crossesMidnight && c.minuteOfDay < h.endMinute) return true
        }
        return false
    }

    /** Every minute at which some day's shift starts or ends, ascending. */
    val boundaries: List<Int> get() =
        (listOf(startMinute, endMinute) + ownHours.values.flatMap { listOf(it.startMinute, it.endMinute) }).distinct().sorted()

    /** The next local time at which [isScheduled] flips, up to a week ahead; null when it never changes. */
    fun nextChange(c: LocalClock): LocalClock? {
        val now = isScheduled(c)
        val marks = boundaries
        for (offset in 0..7) {
            val day = (c.isoDayOfWeek - 1 + offset) % 7 + 1
            for (m in marks) {
                if (offset == 0 && m <= c.minuteOfDay) continue
                if (offset == 7 && m > c.minuteOfDay) continue
                val candidate = LocalClock(day, m)
                if (isScheduled(candidate) != now) return candidate
            }
        }
        return null
    }

    /** "Mon–Fri · 09:00–17:30"; with a short day "Mon–Fri · 09:00–17:30 · Thu 09:00–15:30"; "No set hours" when disabled. */
    val summary: String get() {
        if (!enabled || days.isEmpty()) return "No set hours"
        val own = ownHours.entries.sortedBy { it.key }.joinToString("") { (d, h) -> " · ${LocalClock.DAY_SHORT[d - 1]} ${h.label}" }
        return "${describeDays(days)} · ${usual.label}$own"
    }

    /** One line for MEKA's AI and plain text: "Mon–Fri 09:00–17:30, Thu 09:00–15:30"; "" when there are no set hours. */
    val plainLine: String get() {
        if (!enabled || days.isEmpty()) return ""
        return (listOf("${describeDays(days)} ${usual.label}") +
            ownHours.entries.sortedBy { it.key }.map { (d, h) -> "${LocalClock.DAY_SHORT[d - 1]} ${h.label}" }).joinToString(", ")
    }

    /**
     * "days;start;end;on" with no days of their own (as before 2026-10-09); "days;start;end;on;4=540-930,…" with them.
     * A schedule that matches the old default is written with an empty fifth part, so it reads back as chosen rather
     * than as the never-set default ([WorkMode.schedule] treats the bare old default as unset).
     */
    fun encode(): String {
        val base = "${days.sorted().joinToString(",")};$startMinute;$endMinute;${if (enabled) 1 else 0}"
        // Every day's own hours are kept, a day off's too, so turning that day back on brings them back.
        val own = dayHours.filterValues { it != usual }.entries.sortedBy { it.key }.joinToString(",") { (d, h) -> "$d=${h.startMinute}-${h.endMinute}" }
        return if (own.isEmpty() && base != LEGACY_DEFAULT) base else "$base;$own"
    }

    /** Work mode's per-day editor: one row per work day, Monday first. */
    val weekRows: List<WorkDayRow> get() = days.sorted().map { d ->
        val h = hoursOn(d)
        val own = d in ownHours
        WorkDayRow(d, LocalClock.DAY_SHORT[d - 1], DAY_NAMES[d - 1], h.startMinute, h.endMinute, own,
            if (own) "${h.label} · own hours" else "${h.label} · usual")
    }

    /** The same hours with [isoDay] on its own [hours]; the usual hours (or null) drop the day's own. */
    fun withDayHours(isoDay: Int, hours: DayHours?): WorkSchedule {
        require(isoDay in 1..7) { "days are ISO 1..7" }
        val next = if (hours == null || hours == usual) dayHours - isoDay else dayHours + (isoDay to hours)
        return copy(dayHours = next)
    }

    companion object {
        /** Until Meka sets his own hours: weekdays 09:00–17:30, Thursday 09:00–15:30 (Meka, 2026-10-09 12:54). */
        val DEFAULT = WorkSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60 + 30, true, mapOf(4 to DayHours(9 * 60, 15 * 60 + 30)))

        val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        /** Weekdays 09:00–17:30, every day alike (the default before per-day hours). */
        val WEEKDAYS = WorkSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60 + 30, true)

        /** The default as stored before per-day hours: read as never set, so it becomes [DEFAULT]. */
        const val LEGACY_DEFAULT = "1,2,3,4,5;540;1050;1"

        fun decode(s: String?): WorkSchedule? {
            val parts = s?.split(';') ?: return null
            if (parts.size != 4 && parts.size != 5) return null
            return try {
                val days = if (parts[0].isBlank()) emptySet() else parts[0].split(',').map { it.trim().toInt() }.toSet()
                val own = if (parts.size == 5 && parts[4].isNotBlank()) {
                    parts[4].split(',').associate { entry ->
                        val (d, range) = entry.split('=').also { require(it.size == 2) { "day=start-end" } }
                        val (a, b) = range.split('-').also { require(it.size == 2) { "start-end" } }
                        d.trim().toInt() to DayHours(a.trim().toInt(), b.trim().toInt())
                    }
                } else emptyMap()
                WorkSchedule(days, parts[1].toInt(), parts[2].toInt(), parts[3] == "1", own)
            } catch (e: IllegalArgumentException) { // NumberFormatException is one; so is a failed require
                null
            }
        }

        fun previousDay(isoDay: Int): Int = if (isoDay == 1) 7 else isoDay - 1

        /** "Mon–Fri", "Mon, Wed, Fri", "Every day". Runs of three or more consecutive days collapse to a range. */
        fun describeDays(days: Set<Int>): String {
            if (days.size == 7) return "Every day"
            val sorted = days.sorted()
            val runs = mutableListOf<MutableList<Int>>()
            for (d in sorted) if (runs.isNotEmpty() && runs.last().last() == d - 1) runs.last() += d else runs += mutableListOf(d)
            return runs.joinToString(", ") { r ->
                if (r.size >= 3) "${LocalClock.DAY_SHORT[r.first() - 1]}–${LocalClock.DAY_SHORT[r.last() - 1]}"
                else r.joinToString(", ") { LocalClock.DAY_SHORT[it - 1] }
            }
        }
    }
}

/**
 * A manual override. It lasts until the schedule next changes (so "Work on" pressed before a shift simply runs into
 * the shift, and "Work off" on a sick day lasts until the shift would have ended), and never longer than [MAX_MS].
 */
data class WorkSwitch(val on: Boolean, val setAtMs: Long, val scheduledWhenSet: Boolean) {
    fun isActive(scheduledNow: Boolean, nowMs: Long): Boolean =
        scheduledNow == scheduledWhenSet && nowMs >= setAtMs - SKEW_MS && nowMs - setAtMs < MAX_MS

    fun encode(): String = "${if (on) "ON" else "OFF"};$setAtMs;${if (scheduledWhenSet) 1 else 0}"

    companion object {
        const val MAX_MS = 16 * 60 * 60_000L
        /** Another device's clock may be a little ahead. */
        const val SKEW_MS = 5 * 60_000L

        fun decode(s: String?): WorkSwitch? {
            val p = s?.split(';') ?: return null
            if (p.size != 3 || (p[0] != "ON" && p[0] != "OFF")) return null
            val at = p[1].toLongOrNull() ?: return null
            return WorkSwitch(p[0] == "ON", at, p[2] == "1")
        }
    }
}

enum class WorkSource { SCHEDULE, MANUAL }

/** What the apps show: whether MEKA is in work mode, why, and until when. */
data class WorkModeState(
    val atWork: Boolean,
    val source: WorkSource,
    val schedule: WorkSchedule,
    /** When work mode next turns off (at work) or on (off work), from the schedule; null if it never will. */
    val until: LocalClock?,
    /** "At work until 17:30" · "Off work · next shift tomorrow 09:00" · "Off work · Christmas Day · next shift Mon 09:00". */
    val line: String,
    /** Today's bank holiday when it falls on a work day ("Christmas Day"): work mode stays off. */
    val holiday: String? = null,
    /** The call assistant's switch ([CallScreeningRules]): screen calls during work. Off until Meka turns it on. */
    val callAssistant: Boolean = false,
    /** How long callers' recordings are kept ([VoiceRecordingRules.keepDays]): 7, 30 or 0 (don't keep). */
    val recordingDays: Int = VoiceRecordingRules.DEFAULT_KEEP_DAYS,
    /** The assistant is paused for want of credit ([CallCreditRules]): calls ring as usual, the Work screen says why. */
    val callAssistantPaused: Boolean = false,
) {
    /** The switch overrides the schedule: offer "Back to schedule". */
    val switchedManually: Boolean get() = source == WorkSource.MANUAL
}

object WorkModeRules {
    /**
     * [epochDay] is today's local date. With it, a shift that starts on a bank holiday in [holidays] doesn't count
     * (a night shift belongs to the day it starts on), and "next shift" skips holidays. Without it, only the weekly
     * schedule counts.
     */
    fun state(
        schedule: WorkSchedule, switch: WorkSwitch?, clock: LocalClock, nowMs: Long,
        epochDay: Long? = null, holidays: HolidayCalendar = HolidayCalendar.NONE,
    ): WorkModeState {
        if (epochDay == null) return weeklyState(schedule, switch, clock, nowMs)
        val minute = clock.minuteOfDay
        val scheduled = scheduledOn(schedule, holidays, epochDay, minute)
        val active = switch?.takeIf { it.isActive(scheduled, nowMs) }
        val atWork = active?.on ?: scheduled
        val change = datedChange(schedule, holidays, epochDay, minute, atWork)
        val whenText = change?.let { (d, m) -> describeDated(d, m, epochDay, minute) }
        val holiday = holidays.title(epochDay)?.takeIf { !atWork && schedule.worksOn(clock.isoDayOfWeek) }
        val line = when {
            atWork && whenText != null -> "At work until $whenText"
            atWork -> "At work"
            else -> listOfNotNull("Off work", holiday, whenText?.let { "next shift $it" }).joinToString(" · ")
        }
        val until = change?.let { (d, m) -> LocalClock(CivilDate.isoDayOfWeek(d), m) }
        return WorkModeState(atWork, if (active != null) WorkSource.MANUAL else WorkSource.SCHEDULE, schedule, until, line, holiday)
    }

    /** Whether the schedule has MEKA at work at [minute] on [epochDay], bank holidays off. */
    fun scheduledOn(schedule: WorkSchedule, holidays: HolidayCalendar, epochDay: Long, minute: Int): Boolean {
        val iso = CivilDate.isoDayOfWeek(epochDay)
        if (!schedule.isScheduled(LocalClock(iso, minute))) return false
        // Inside today's own shift, it is today's; otherwise it is last night's shift running on.
        val todays = schedule.worksOn(iso) && minute >= schedule.startOn(iso) &&
            (schedule.crossesMidnightOn(iso) || minute < schedule.endOn(iso))
        val shiftDay = if (todays) epochDay else epochDay - 1
        return !holidays.isHoliday(shiftDay)
    }

    /** A work day: the schedule has a shift starting that day and it isn't a bank holiday. */
    fun isWorkDay(schedule: WorkSchedule, holidays: HolidayCalendar, epochDay: Long): Boolean =
        schedule.worksOn(CivilDate.isoDayOfWeek(epochDay)) && !holidays.isHoliday(epochDay)

    /** The next (day, minute) at which [scheduledOn] differs from [atWork], up to five weeks ahead (a run of holidays). */
    private fun datedChange(schedule: WorkSchedule, holidays: HolidayCalendar, day: Long, minute: Int, atWork: Boolean): Pair<Long, Int>? {
        if (!schedule.enabled || (1..7).none { schedule.worksOn(it) }) return null
        val boundaries = schedule.boundaries
        for (offset in 0..35) {
            val d = day + offset
            for (m in boundaries) {
                if (offset == 0 && m <= minute) continue
                if (scheduledOn(schedule, holidays, d, m) != atWork) return d to m
            }
        }
        return null
    }

    /** "17:30" today, "tomorrow 09:00", "Mon 09:00" within the week, else "Mon 4 Jan 09:00". */
    fun describeDated(day: Long, minute: Int, today: Long, nowMinute: Int): String {
        val hhmm = LocalClock.formatMinute(minute)
        return when (val ahead = day - today) {
            0L -> if (minute > nowMinute) hhmm else "${LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(day) - 1]} $hhmm"
            1L -> "tomorrow $hhmm"
            in 2L..6L -> "${LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(day) - 1]} $hhmm"
            else -> "${CivilDate.shortLabel(day)} $hhmm"
        }
    }

    private fun weeklyState(schedule: WorkSchedule, switch: WorkSwitch?, clock: LocalClock, nowMs: Long): WorkModeState {
        val scheduled = schedule.isScheduled(clock)
        val active = switch?.takeIf { it.isActive(scheduled, nowMs) }
        val atWork = active?.on ?: scheduled
        val until = effectiveChange(schedule, clock, atWork)
        val whenText = until?.let { describe(it, clock) }
        val line = when {
            atWork && whenText != null -> "At work until $whenText"
            atWork -> "At work"
            whenText != null -> "Off work · next shift $whenText"
            else -> "Off work"
        }
        return WorkModeState(atWork, if (active != null) WorkSource.MANUAL else WorkSource.SCHEDULE, schedule, until, line)
    }

    /**
     * The next time the effective state differs from [atWork]. An active switch expires at the schedule's next change,
     * after which the schedule decides, so this walks schedule changes until the state actually flips.
     */
    private fun effectiveChange(schedule: WorkSchedule, from: LocalClock, atWork: Boolean): LocalClock? {
        var c = from
        repeat(3) {
            val next = schedule.nextChange(c) ?: return null
            if (schedule.isScheduled(next) != atWork) return next
            c = next
        }
        return null
    }

    /** "17:30" today, "tomorrow 09:00", else "Mon 09:00". */
    fun describe(at: LocalClock, now: LocalClock): String = when (at.isoDayOfWeek) {
        now.isoDayOfWeek -> if (at.minuteOfDay > now.minuteOfDay) at.hhmm else "${LocalClock.DAY_SHORT[at.isoDayOfWeek - 1]} ${at.hhmm}"
        now.isoDayOfWeek % 7 + 1 -> "tomorrow ${at.hhmm}"
        else -> "${LocalClock.DAY_SHORT[at.isoDayOfWeek - 1]} ${at.hhmm}"
    }
}

/** One stretch of work on a day, clipped to that day: "09:00–17:30". */
data class WorkBlock(
    val startMs: Long,
    val endMs: Long,
    val startMinute: Int,
    val endMinute: Int,
    /** "Work", or "Work from home" on a home day ([WorkHours.homeDays]). */
    val title: String = WorkHours.TITLE,
) {
    /** "09:00–17:30" (the shift's own times, even when a night shift is clipped at midnight). */
    val label: String get() = "${LocalClock.formatMinute(startMinute)}–${LocalClock.formatMinute(endMinute)}"
}

/**
 * Work hours as Today and the Calendar tab show them (Fold review 2026-10-08: Today said "10 h free" and Calendar
 * "Nothing planned" on a work day). Non-AI, pure, unit-tested.
 *
 * - A work day is one the schedule has a shift starting on, bank holidays excluded ([WorkModeRules.isWorkDay]).
 * - [offDay] is the day a manual "Work off" is in force during the shift (a sick day): that day's block goes.
 * - A night shift shows on the day it starts (until midnight) and its tail on the next morning.
 * - Work isn't an event: Today shows it as a quiet block and counts free time outside it; the Calendar tab says
 *   "Work 09:00–17:30" on each work day.
 */
data class WorkHours(
    val schedule: WorkSchedule,
    val holidays: HolidayCalendar = HolidayCalendar.NONE,
    val offDay: Long? = null,
    /** Days Meka works from home (Add on a request card): the work band reads "Work from home". */
    val homeDays: Set<Long> = emptySet(),
) {
    fun isWorkDay(epochDay: Long): Boolean = epochDay != offDay && WorkModeRules.isWorkDay(schedule, holidays, epochDay)

    /** "Work from home" on a home day, else "Work". */
    fun title(epochDay: Long): String = if (epochDay in homeDays) HOME_TITLE else TITLE

    /** "Work 09:00–17:30" (or "Work from home 09:00–17:30") on a work day; null otherwise. */
    fun line(epochDay: Long): String? =
        if (isWorkDay(epochDay)) "${title(epochDay)} ${hoursOn(epochDay).label}" else null

    /** [epochDay]'s hours (its weekday's own, else the usual ones). */
    fun hoursOn(epochDay: Long): DayHours = schedule.hoursOn(CivilDate.isoDayOfWeek(epochDay))

    /** The work on [epochDay] (its window from [calendar]), in time order: at most a night shift's tail and a shift. */
    fun blocks(epochDay: Long, calendar: LocalCalendar): List<WorkBlock> = buildList {
        val dayStart = calendar.toEpochMs(epochDay, 0)
        val dayEnd = calendar.toEpochMs(epochDay + 1, 0)
        val last = hoursOn(epochDay - 1)
        if (last.crossesMidnight && last.endMinute > 0 && isWorkDay(epochDay - 1)) {
            add(WorkBlock(dayStart, calendar.toEpochMs(epochDay, last.endMinute), last.startMinute, last.endMinute, title(epochDay - 1)))
        }
        if (isWorkDay(epochDay)) {
            val h = hoursOn(epochDay)
            val end = if (h.crossesMidnight) dayEnd else calendar.toEpochMs(epochDay, h.endMinute)
            add(WorkBlock(calendar.toEpochMs(epochDay, h.startMinute), end, h.startMinute, h.endMinute, title(epochDay)))
        }
    }

    companion object {
        const val TITLE = "Work"
        const val HOME_TITLE = "Work from home"

        /**
         * From the work-mode state: an active manual "Work off" during today's shift takes today's block away;
         * [homeDays] read "Work from home".
         */
        fun of(state: WorkModeState, holidays: HolidayCalendar, todayEpochDay: Long, homeDays: Set<Long> = emptySet()): WorkHours =
            WorkHours(state.schedule, holidays, offDay = todayEpochDay.takeIf { state.switchedManually && !state.atWork }, homeDays = homeDays)

        /** [WorkFields.HOME_DAYS] read: unreadable entries skipped. */
        fun decodeHomeDays(s: String?): Set<Long> =
            s?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty()

        fun encodeHomeDays(days: Set<Long>): String = days.sorted().joinToString(",")
    }
}

/** Reads and writes the synced work-mode entity. */
class WorkMode(
    private val replica: Replica,
    /** The mirrored bank holidays ([BankHolidayStore]); none until the server has sent them. */
    private val holidays: () -> HolidayCalendar = { HolidayCalendar.NONE },
    private val nowMs: () -> Long,
) {
    /** The stored schedule; the default when never set (or stored as the old default, before per-day hours). */
    fun schedule(): WorkSchedule {
        val stored = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(WorkFields.SCHEDULE)?.textOrNull
        if (stored == WorkSchedule.LEGACY_DEFAULT) return WorkSchedule.DEFAULT
        return WorkSchedule.decode(stored) ?: WorkSchedule.DEFAULT
    }

    fun currentSwitch(): WorkSwitch? = WorkSwitch.decode(replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(WorkFields.SWITCH)?.textOrNull)

    /** [epochDay] is today's local date; with it, bank holidays are days off. */
    fun state(clock: LocalClock, epochDay: Long? = null): WorkModeState =
        WorkModeRules.state(schedule(), currentSwitch(), clock, nowMs(), epochDay, holidays()).copy(callAssistant = callAssistant(), recordingDays = recordingDays())

    /** Work hours for Today and the Calendar tab, with today's manual "Work off" (a sick day) taken into account. */
    fun hours(clock: LocalClock, epochDay: Long): WorkHours = WorkHours.of(state(clock, epochDay), holidays(), epochDay, homeDays(epochDay))

    private fun storedHomeDays(): Set<Long> =
        WorkHours.decodeHomeDays(replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(WorkFields.HOME_DAYS)?.textOrNull)

    /** Work-from-home days (synced, last writer wins) from [today] on (all of them when null). */
    fun homeDays(today: Long? = null): Set<Long> = storedHomeDays().filter { today == null || it >= today }.toSet()

    /**
     * Marks [epochDay] as a work-from-home day ([on]) or a usual one; days before [today] are dropped as it writes.
     * Returns whether anything changed.
     */
    fun setHomeDay(epochDay: Long, on: Boolean, today: Long): Boolean {
        val stored = storedHomeDays()
        val kept = stored.filter { it >= today }.toSet()
        val next = if (on) kept + epochDay else kept - epochDay
        if (next == stored) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(WorkFields.HOME_DAYS to WorkHours.encodeHomeDays(next).fv()))
        return (epochDay in stored) != on
    }

    fun callAssistant(): Boolean = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(WorkFields.CALL_ASSISTANT)?.boolOrNull == true

    /** The call assistant's one switch, synced (either app can turn it off). */
    fun setCallAssistant(on: Boolean) {
        if (callAssistant() == on && replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(WorkFields.CALL_ASSISTANT) != null) return
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(WorkFields.CALL_ASSISTANT to on.fv()))
    }

    /** How long callers' recordings are kept, in days (synced; [VoiceRecordingRules.keepDays]). */
    fun recordingDays(): Int =
        VoiceRecordingRules.keepDays(replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(WorkFields.RECORDING_DAYS)?.longOrNull)

    /** "Keep callers' recordings": one of [VoiceRecordingRules.KEEP_CHOICES]. Returns whether anything changed. */
    fun setRecordingDays(days: Int): Boolean {
        require(days in VoiceRecordingRules.KEEP_CHOICES) { "keep 7 or 30 days, or 0 for don't keep" }
        val stored = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(WorkFields.RECORDING_DAYS)
        if (stored != null && recordingDays() == days) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(WorkFields.RECORDING_DAYS to days.toLong().fv()))
        return true
    }

    fun setSchedule(s: WorkSchedule) {
        val stored = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(WorkFields.SCHEDULE)?.textOrNull
        if (stored != null && stored == s.encode()) return
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(WorkFields.SCHEDULE to s.encode().fv()))
    }

    /** The Work switch. Choosing what the schedule already says just returns to the schedule. */
    fun setSwitch(on: Boolean, clock: LocalClock, epochDay: Long? = null) {
        val scheduled = if (epochDay == null) schedule().isScheduled(clock)
        else WorkModeRules.scheduledOn(schedule(), holidays(), epochDay, clock.minuteOfDay)
        if (on == scheduled) { backToSchedule(); return }
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, ENTITY_ID,
            mapOf(WorkFields.SWITCH to WorkSwitch(on, nowMs(), scheduled).encode().fv()),
        )
    }

    fun backToSchedule() {
        if (replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(WorkFields.SWITCH) is FieldValue.Text) {
            replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(WorkFields.SWITCH to FieldValue.Null))
        }
    }

    companion object {
        const val ENTITY_ID = "work"
    }
}
