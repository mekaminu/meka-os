package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Protected time (V1), slice 1: evenings and weekends defended by rules. Meka turns on either or both once (Ask →
 * More → Protected time); from then on MEKA never puts a task into that time itself:
 *
 * - **Evenings** from a set time (18:00–20:00, default 19:00) to midnight on every day that isn't wholly protected. A
 *   work day whose shift ends later starts its evening at the shift's end; a night shift has no protected evening.
 * - **Weekends**: Saturday, Sunday and bank holidays, all day, unless the work schedule has a shift that day.
 *
 * Plan my day ([DayPlanner.plan], `protected`) places no task there (habit room, booked sessions and kept-free blocks
 * are Meka's own time and are left as they are), and says so under the plan ([ProtectedRules.planNote]); on a protected
 * day the tasks that would have been planned "wait for Mon 12 Oct" instead of "won't fit today". Move to later
 * ([LateTaskRules.laterSlot]) never moves a task into it. Nothing Meka schedules himself is moved or refused.
 *
 * Stored as one `context_mode` entity, id [ProtectedRules.ENTITY_ID] (ADR-008 addendum 2026-10-10): `evenings` (Bool),
 * `fromMin` (local minutes), `weekends` (Bool). All LWW. Off until Meka turns it on. Plain, non-AI rules; nothing
 * leaves Meka's own synced data.
 *
 * Later slices: work notifications wait until morning (what counts as a work notification is decided with the email
 * work), work email waits until morning (with email triage), and Ask/Talk knowing the rules.
 */
object ProtectedFields {
    const val EVENINGS = "evenings"
    const val FROM = "fromMin"
    const val WEEKENDS = "weekends"
}

/** The setting as stored. */
data class ProtectedSetting(val evenings: Boolean, val fromMin: Int, val weekends: Boolean) {
    val on: Boolean get() = evenings || weekends
}

/** What the pane shows: the two switches, the evening's time, and the summary. */
data class ProtectedView(
    val evenings: Boolean,
    val fromMin: Int,
    val weekends: Boolean,
    /** "Evenings from 19:00 and weekends · nothing is planned into them", or [ProtectedRules.OFF_LINE]. */
    val summary: String,
    /** "Today is protected · tasks wait for Mon 12 Oct" / "Protected from 19:00 tonight"; null when off. */
    val todayLine: String?,
) {
    val on: Boolean get() = evenings || weekends

    companion object {
        val EMPTY = ProtectedView(false, ProtectedRules.DEFAULT_FROM, false, ProtectedRules.OFF_LINE, null)
    }
}

object ProtectedRules {
    const val ENTITY_ID = "protected_time"
    const val DEFAULT_FROM = 19 * 60
    val FROM_CHOICES: List<Int> = listOf(18 * 60, 18 * 60 + 30, 19 * 60, 19 * 60 + 30, 20 * 60)

    const val EVENINGS_LABEL = "Evenings"
    const val WEEKENDS_LABEL = "Weekends and bank holidays"
    const val OFF_LINE = "Off · the planner may use evenings and weekends"
    const val NOTE = "Plan my day and Move to later never put a task into protected time. Habits, booked sessions, " +
        "date night and anything you plan yourself are left as they are. A weekend shift in Work mode keeps that day open."
    /** How far ahead [nextOpenDay] looks for a day that isn't wholly protected (a long run of holidays). */
    private const val LOOK_AHEAD_DAYS = 14

    fun read(fields: Map<String, FieldValue>?): ProtectedSetting? {
        if (fields == null) return null
        val from = fields[ProtectedFields.FROM]?.longOrNull?.toInt()?.takeIf { it in 0 until LocalClock.MINUTES_PER_DAY } ?: DEFAULT_FROM
        return ProtectedSetting(
            evenings = fields[ProtectedFields.EVENINGS]?.boolOrNull == true,
            fromMin = from,
            weekends = fields[ProtectedFields.WEEKENDS]?.boolOrNull == true,
        )
    }

    /** Saturday, Sunday or a bank holiday with no shift in the work schedule, while weekends are protected. */
    fun wholeDay(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar): Boolean {
        if (s == null || !s.weekends) return false
        val weekendish = CivilDate.isoDayOfWeek(day) >= 6 || holidays.isHoliday(day)
        return weekendish && !worksOn(schedule, holidays, day)
    }

    /** A shift really falls on [day] (a weekend shift counts; a bank holiday doesn't). */
    private fun worksOn(schedule: WorkSchedule, holidays: HolidayCalendar, day: Long): Boolean =
        WorkModeRules.isWorkDay(schedule, holidays, day)

    /**
     * When [day]'s protected evening starts (local minutes), or null: [ProtectedSetting.fromMin], or the shift's end
     * when that is later; none on a night shift or when evenings are off.
     */
    fun eveningFrom(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar): Int? {
        if (s == null || !s.evenings) return null
        if (!worksOn(schedule, holidays, day)) return s.fromMin
        val hours = schedule.hoursOn(CivilDate.isoDayOfWeek(day))
        if (hours.crossesMidnight) return null
        return maxOf(s.fromMin, hours.endMinute).takeIf { it < LocalClock.MINUTES_PER_DAY }
    }

    /** [day]'s protected time as slots for the planner: the whole day, the evening to midnight, or none. */
    fun slots(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar, cal: LocalCalendar): List<DayPlanner.Slot> {
        val end = cal.toEpochMs(day + 1, 0)
        if (wholeDay(s, day, schedule, holidays)) return listOf(DayPlanner.Slot(cal.toEpochMs(day, 0), end))
        val from = eveningFrom(s, day, schedule, holidays) ?: return emptyList()
        return listOf(DayPlanner.Slot(cal.toEpochMs(day, from), end))
    }

    /** The first day after [day] that isn't wholly protected (Monday after a weekend), or null within two weeks. */
    fun nextOpenDay(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar): Long? =
        (1..LOOK_AHEAD_DAYS).map { day + it }.firstOrNull { !wholeDay(s, it, schedule, holidays) }

    /** "Evenings from 19:00 and weekends · nothing is planned into them". */
    fun summary(s: ProtectedSetting?): String {
        if (s == null || !s.on) return OFF_LINE
        val what = when {
            s.evenings && s.weekends -> "Evenings from ${LocalClock.formatMinute(s.fromMin)}, weekends and bank holidays"
            s.evenings -> "Evenings from ${LocalClock.formatMinute(s.fromMin)}"
            else -> "Weekends and bank holidays"
        }
        return "$what · nothing is planned into them"
    }

    /** "Waits for Mon 12 Oct" on a wholly protected day; null otherwise (the plan says "Won't fit today"). */
    fun waitLabel(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar): String? {
        if (!wholeDay(s, day, schedule, holidays)) return null
        return "Waits for ${openLabel(s, day, schedule, holidays)}"
    }

    /** "tomorrow", "Mon 12 Oct", or "a day that isn't protected" past two weeks. */
    private fun openLabel(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar): String {
        val next = nextOpenDay(s, day, schedule, holidays) ?: return "a day that isn't protected"
        return if (next == day + 1) "tomorrow" else CivilDate.shortLabel(next)
    }

    /**
     * The note under Plan my day: "Protected time: today is kept free, so no tasks are planned." or "Protected time:
     * nothing is planned after 19:00."; null when [day] has none.
     */
    fun planNote(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar): String? {
        if (wholeDay(s, day, schedule, holidays)) return "Protected time: today is kept free, so no tasks are planned."
        val from = eveningFrom(s, day, schedule, holidays) ?: return null
        return "Protected time: nothing is planned after ${LocalClock.formatMinute(from)}."
    }

    /** The pane's line about today: "Today is protected · tasks wait for Mon 12 Oct" or "Protected from 19:00 tonight". */
    fun todayLine(s: ProtectedSetting?, today: Long, schedule: WorkSchedule, holidays: HolidayCalendar): String? {
        if (wholeDay(s, today, schedule, holidays)) return "Today is protected · tasks wait for ${openLabel(s, today, schedule, holidays)}"
        val from = eveningFrom(s, today, schedule, holidays) ?: return null
        return "Protected from ${LocalClock.formatMinute(from)} tonight"
    }

    fun view(s: ProtectedSetting?, today: Long, schedule: WorkSchedule, holidays: HolidayCalendar): ProtectedView =
        ProtectedView(
            evenings = s?.evenings == true,
            fromMin = s?.fromMin ?: DEFAULT_FROM,
            weekends = s?.weekends == true,
            summary = summary(s),
            todayLine = todayLine(s, today, schedule, holidays),
        )
}

/** The protected-time setting on a replica. Every write is an op: offline-first, synced. */
class ProtectedTime(private val replica: Replica) {
    fun setting(): ProtectedSetting? = ProtectedRules.read(replica.entity(EntityTypes.CONTEXT_MODE, ProtectedRules.ENTITY_ID)?.fields)

    /** Sets both switches and the evening's start. False (nothing written) when it doesn't make sense or nothing changes. */
    fun set(evenings: Boolean, fromMin: Int, weekends: Boolean): Boolean {
        if (fromMin !in 0 until LocalClock.MINUTES_PER_DAY) return false
        val old = setting()
        if (old == ProtectedSetting(evenings, fromMin, weekends)) return false
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, ProtectedRules.ENTITY_ID,
            mapOf(
                ProtectedFields.EVENINGS to evenings.fv(),
                ProtectedFields.FROM to fromMin.toLong().fv(),
                ProtectedFields.WEEKENDS to weekends.fv(),
            ),
        )
        return true
    }
}
