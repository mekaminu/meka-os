package os.meka.core.domain

import os.meka.core.sync.EntitySnapshot
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Alarms (build plan M1, Meka 2026-10-07), slice 1: the **smart wake alarm**. Non-AI, pure rules plus a small store.
 *
 * - MEKA suggests a wake time from tomorrow's first commitment (the first timed event from 04:00, or the start of work
 *   on a work day, whichever comes first) less Meka's get-ready buffer (prep and commute; 60 min until changed),
 *   rounded down to five minutes and never before 04:00. Nothing early tomorrow: no suggestion, he can still set one.
 * - Nothing rings until Meka sets it (in the evening shutdown, or from Ask → More → Alarms): the set time then stays
 *   put even if the calendar changes; when the suggestion moves earlier than the alarm (a new early meeting), the view
 *   says so and offers the new time.
 * - An alarm is one synced `alarm` entity per wake day (id [AlarmRules.wakeId]), so setting it on the Mac rings on the
 *   Fold, and Dismiss on either device stops it on both. Snooze is 9 minutes; an alarm nobody answers rings for 10
 *   minutes and is then left (never rings hours late after the phone was off).
 * - The wake day is tomorrow, or today before 04:00 (setting it just after midnight).
 */
object AlarmFields {
    /** [AlarmKind] name. */
    const val KIND = "kind"
    /** The local day it rings on (epoch day). */
    const val DAY = "day"
    /** The local minute of the day it rings at. */
    const val MINUTE = "minute"
    /** Turned off (it stays stored, so turning it on again keeps the day). */
    const val OFF = "off"
    /** Snoozed: rings again at this moment (epoch ms); null when not snoozed. */
    const val SNOOZED_UNTIL = "snoozedUntilMs"
    /** Dismissed at (epoch ms): it has done its job for the day. */
    const val DISMISSED_AT = "dismissedAtMs"
    /** What it's for, when it was set: "Standup at 08:00". Shown on the ringing screen. */
    const val NOTE = "note"
    const val SET_AT = "setAtMs"
    /**
     * The exact moment it rings (epoch ms), for a timer (Alarms, slice 2): `day`/`minute` then hold its local day and
     * minute too, so a reader that doesn't know this field still rings it within the minute.
     */
    const val AT_MS = "atMs"
    /** A timer's length in seconds ("20 min"); null for alarms. */
    const val LENGTH_SEC = "lengthSec"
}

/** The alarm settings (one `context_mode` entity, id [Alarms.SETTINGS_ID]). LWW. */
object AlarmSettingsFields {
    /** Minutes to get ready and get there before the first commitment. */
    const val WAKE_BUFFER = "wakeBufferMin"
}

/**
 * WAKE: the smart wake alarm (slice 1). ALARM and TIMER: quick alarms and timers typed into capture ("alarm 6:30",
 * "timer 20 min"; slice 2, [QuickAlarmRules]). LEAVE: a leave-by that rings as an alarm (slice 3, [LeaveAlarmRules]);
 * derived from the event and its Leave by, stored only once it is snoozed or dismissed.
 */
enum class AlarmKind { WAKE, ALARM, TIMER, LEAVE }

data class Alarm(
    val id: String,
    val kind: AlarmKind,
    val epochDay: Long,
    val minute: Int,
    /** When it rings (its day and minute in the local calendar). */
    val atMs: Long,
    val off: Boolean,
    val snoozedUntilMs: Long?,
    val dismissedAtMs: Long?,
    val note: String?,
    /** A timer's length in seconds; null for alarms. */
    val lengthSec: Int? = null,
) {
    /** The next time it rings: after a snooze, the snooze's end. */
    val ringAtMs: Long get() = snoozedUntilMs ?: atMs
}

/** Tomorrow's first commitment: what the wake time is worked out from. */
data class WakeCommitment(val minute: Int, val title: String, val isWork: Boolean) {
    /** "Standup at 08:00" · "Work at 09:00". */
    val line: String get() = "$title at ${LocalClock.formatMinute(minute)}"
}

data class WakeSuggestion(
    val minute: Int,
    val commitment: WakeCommitment,
    /** "Standup at 08:00 · 1 h to get ready". */
    val line: String,
)

/** The wake alarm for the next morning, as the evening shutdown and Alarms show it. */
data class WakeView(
    val epochDay: Long,
    /** "Tomorrow · Fri 9 Oct" (or "This morning · Fri 9 Oct" just after midnight). */
    val dayLabel: String,
    val suggestion: WakeSuggestion?,
    /** The alarm's minute when it's set and on; null when there's none. */
    val setMinute: Int?,
    /** The time the control shows: the alarm, else the suggestion, else 07:00. */
    val minute: Int,
    /** "06:45". */
    val timeLabel: String,
    /**
     * Set: "Alarm set · Standup at 08:00, 1 h to get ready" (or "Alarm set · rings in 8 h 15" with nothing early);
     * not set: "Suggested from Standup at 08:00 · 1 h to get ready" · "Nothing early tomorrow · set one if you like".
     */
    val line: String,
    /** The suggestion is earlier than the set alarm (something new and early): offer [suggestion]'s time. */
    val suggestionEarlier: Boolean,
    /** "Use 06:15" when [suggestionEarlier], else null. */
    val useSuggestionLabel: String?,
    val bufferMin: Int,
    /** "1 h to get ready" · "45 min to get ready". */
    val bufferLine: String,
    val bufferChoices: List<Int>,
) {
    val isSet: Boolean get() = setMinute != null

    companion object {
        val EMPTY = WakeView(
            0, "Tomorrow", null, null, AlarmRules.DEFAULT_WAKE_MIN, LocalClock.formatMinute(AlarmRules.DEFAULT_WAKE_MIN),
            AlarmRules.NOTHING_EARLY, false, null, AlarmRules.DEFAULT_BUFFER_MIN,
            AlarmRules.bufferLine(AlarmRules.DEFAULT_BUFFER_MIN), AlarmRules.BUFFER_CHOICES,
        )
    }
}

/** The alarm that rings next (for the platform's alarm) or is ringing now (for the ringing screen). */
data class AlarmRing(
    val id: String,
    val ringAtMs: Long,
    /** "06:45" (the time it was set for, even when snoozed). */
    val timeLabel: String,
    /** "Wake up". */
    val title: String,
    /** "Standup at 08:00", or "Good morning" with nothing noted. */
    val line: String,
    val snoozed: Boolean,
    /** "Snoozed until 06:54" while snoozed, else null. */
    val snoozeLine: String?,
    val kind: AlarmKind = AlarmKind.WAKE,
) {
    /** Dismissing the wake alarm brings up the morning brief; a quick alarm or a timer just stops. */
    val opensBrief: Boolean get() = kind == AlarmKind.WAKE
}

object AlarmRules {
    const val SNOOZE_MIN = 9
    /** An alarm nobody answers rings this long, then stops; past this it never rings (the phone was off). */
    const val RING_FOR_MS = 10 * 60_000L
    /** Commitments before this are the night before's business, not the morning's. */
    const val EARLIEST_COMMITMENT_MIN = 4 * 60
    const val EARLIEST_WAKE_MIN = 4 * 60
    const val DEFAULT_WAKE_MIN = 7 * 60
    const val STEP_MIN = 5
    const val DEFAULT_BUFFER_MIN = 60
    val BUFFER_CHOICES = listOf(30, 45, 60, 75, 90, 120)
    const val NOTHING_EARLY = "Nothing early tomorrow · set one if you like"
    const val TITLE = "Wake up"

    /** The `alarm` id of the wake alarm for [epochDay]: the same on every device. */
    fun wakeId(epochDay: Long): String = "wake.d$epochDay"

    /** The morning a wake alarm set now is for: today before 04:00, else tomorrow. */
    fun wakeDay(nowMs: Long, cal: LocalCalendar): Long {
        val today = cal.epochDayOf(nowMs)
        return if (cal.minuteOfDay(nowMs) < EARLIEST_COMMITMENT_MIN) today else today + 1
    }

    /** The first thing on [epochDay] from 04:00: a timed event or the start of a work shift (events win a tie). */
    fun firstCommitment(epochDay: Long, events: List<CalendarEvent>, work: WorkHours, cal: LocalCalendar): WakeCommitment? {
        val from = cal.toEpochMs(epochDay, EARLIEST_COMMITMENT_MIN)
        val end = cal.toEpochMs(epochDay + 1, 0)
        val event = events.filter { !it.allDay && it.startAtMs >= from && it.startAtMs < end }
            .minWithOrNull(compareBy<CalendarEvent> { it.startAtMs }.thenBy { it.title })
        val shift = work.blocks(epochDay, cal).firstOrNull { it.startMs >= from && it.startMs < end }
        return when {
            event != null && (shift == null || event.startAtMs <= shift.startMs) ->
                WakeCommitment(cal.minuteOfDay(event.startAtMs), event.title.trim().ifEmpty { "First event" }, isWork = false)
            shift != null -> WakeCommitment(shift.startMinute, WorkHours.TITLE, isWork = true)
            else -> null
        }
    }

    /** The commitment less the buffer, rounded down to five minutes, never before 04:00. */
    fun suggest(commitment: WakeCommitment?, bufferMin: Int): WakeSuggestion? {
        commitment ?: return null
        val raw = commitment.minute - bufferMin
        val minute = (raw.floorDiv(STEP_MIN) * STEP_MIN).coerceAtLeast(EARLIEST_WAKE_MIN)
        return WakeSuggestion(minute, commitment, "${commitment.line} · ${bufferLine(bufferMin)}")
    }

    /** "1 h to get ready" · "45 min to get ready" · "1 h 15 to get ready". */
    fun bufferLine(min: Int): String = "${duration(min)} to get ready"

    /** "45 min" · "1 h" · "1 h 15". */
    fun duration(min: Int): String = when {
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} h"
        else -> "${min / 60} h ${(min % 60).toString().padStart(2, '0')}"
    }

    /** A buffer Meka may choose: 15 min to 3 h in fives. */
    fun validBuffer(min: Int): Boolean = min in 15..180 && min % STEP_MIN == 0

    /** [minute] moved by [steps] five-minute steps, round the clock. */
    fun step(minute: Int, steps: Int): Int = (minute + steps * STEP_MIN).mod(LocalClock.MINUTES_PER_DAY)

    fun view(
        nowMs: Long,
        cal: LocalCalendar,
        wakeDay: Long,
        alarm: Alarm?,
        events: List<CalendarEvent>,
        work: WorkHours,
        bufferMin: Int,
    ): WakeView {
        val suggestion = suggest(firstCommitment(wakeDay, events, work, cal), bufferMin)
        val set = alarm?.takeIf { !it.off && it.dismissedAtMs == null }?.minute
        val minute = set ?: suggestion?.minute ?: DEFAULT_WAKE_MIN
        val earlier = set != null && suggestion != null && suggestion.minute < set
        val today = cal.epochDayOf(nowMs)
        val dayWord = if (wakeDay == today) "This morning" else "Tomorrow"
        val line = when {
            set != null && suggestion != null && !earlier -> "Alarm set · ${suggestion.commitment.line}, ${bufferLine(bufferMin)}"
            set != null && earlier -> "Alarm set · ${suggestion!!.commitment.line} now, so ${LocalClock.formatMinute(suggestion.minute)} gives you ${duration(bufferMin)}"
            set != null -> "Alarm set · rings in ${duration(((cal.toEpochMs(wakeDay, set) - nowMs + 59_999) / 60_000).toInt().coerceAtLeast(0))}"
            suggestion != null -> "Suggested from ${suggestion.line}"
            else -> NOTHING_EARLY.replace("tomorrow", if (wakeDay == today) "this morning" else "tomorrow")
        }
        return WakeView(
            epochDay = wakeDay,
            dayLabel = "$dayWord · ${CivilDate.shortLabel(wakeDay)}",
            suggestion = suggestion,
            setMinute = set,
            minute = minute,
            timeLabel = LocalClock.formatMinute(minute),
            line = line,
            suggestionEarlier = earlier,
            useSuggestionLabel = if (earlier) "Use ${LocalClock.formatMinute(suggestion!!.minute)}" else null,
            bufferMin = bufferMin,
            bufferLine = bufferLine(bufferMin),
            bufferChoices = (BUFFER_CHOICES + bufferMin).distinct().sorted(),
        )
    }

    /** Whether [alarm] should be ringing at [nowMs]. */
    fun ringing(alarm: Alarm, nowMs: Long): Boolean =
        !alarm.off && alarm.dismissedAtMs == null && nowMs >= alarm.ringAtMs && nowMs < alarm.ringAtMs + RING_FOR_MS

    /** The alarm that rings next, or is ringing now; null when none is on. */
    fun next(alarms: List<Alarm>, nowMs: Long): AlarmRing? = alarms
        .filter { !it.off && it.dismissedAtMs == null && it.ringAtMs + RING_FOR_MS > nowMs }
        .minWithOrNull(compareBy<Alarm> { it.ringAtMs }.thenBy { it.id })
        ?.let { ring(it) }

    fun ring(a: Alarm): AlarmRing {
        val note = a.note?.trim()?.takeIf { it.isNotEmpty() }
        return AlarmRing(
            id = a.id,
            ringAtMs = a.ringAtMs,
            // A timer shows its length ("20 min"); an alarm the time it was set for.
            timeLabel = if (a.kind == AlarmKind.TIMER && a.lengthSec != null) QuickAlarmRules.length(a.lengthSec) else LocalClock.formatMinute(a.minute),
            title = when (a.kind) {
                AlarmKind.WAKE -> TITLE
                AlarmKind.ALARM -> QuickAlarmRules.ALARM_TITLE
                AlarmKind.TIMER -> QuickAlarmRules.TIMER_TITLE
                AlarmKind.LEAVE -> LeaveAlarmRules.TITLE
            },
            line = note ?: when (a.kind) {
                AlarmKind.WAKE -> "Good morning"
                AlarmKind.ALARM -> QuickAlarmRules.ALARM_TITLE
                AlarmKind.TIMER -> QuickAlarmRules.TIMES_UP
                AlarmKind.LEAVE -> LeaveAlarmRules.TITLE
            },
            snoozed = a.snoozedUntilMs != null,
            snoozeLine = null, // [ringIn] fills it in with the local time
            kind = a.kind,
        )
    }

    /** [ring] with the snooze line in local time. */
    fun ringIn(a: Alarm, cal: LocalCalendar): AlarmRing =
        ring(a).copy(snoozeLine = a.snoozedUntilMs?.let { "Snoozed until ${LocalClock.formatMinute(cal.minuteOfDay(it))}" })

    fun from(e: EntitySnapshot, cal: LocalCalendar): Alarm? {
        val kind = AlarmKind.entries.firstOrNull { it.name == e[AlarmFields.KIND].textOrNull } ?: return null
        val day = e[AlarmFields.DAY].longOrNull ?: return null
        val minute = e[AlarmFields.MINUTE].longOrNull?.toInt()?.takeIf { it in 0 until LocalClock.MINUTES_PER_DAY } ?: return null
        return Alarm(
            id = e.ref.entityId,
            kind = kind,
            epochDay = day,
            minute = minute,
            atMs = e[AlarmFields.AT_MS].longOrNull ?: cal.toEpochMs(day, minute),
            off = e[AlarmFields.OFF].boolOrNull == true,
            snoozedUntilMs = e[AlarmFields.SNOOZED_UNTIL].longOrNull,
            dismissedAtMs = e[AlarmFields.DISMISSED_AT].longOrNull,
            note = e[AlarmFields.NOTE].textOrNull,
            lengthSec = e[AlarmFields.LENGTH_SEC].longOrNull?.toInt()?.takeIf { it > 0 },
        )
    }
}

/**
 * Reads and writes the synced alarms. [derived] gives the leave-by alarms worked out from the calendar right now
 * ([LeaveAlarmRules.alarms]): one rings until it is snoozed or dismissed, which stores it under its own id; a stored
 * leave-by whose event moved, lost its place or its alarm is left out (it no longer matches anything derived).
 */
class Alarms(
    private val replica: Replica,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
    private val derived: () -> List<Alarm> = { emptyList() },
) {
    fun all(): List<Alarm> {
        val stored = replica.entities(EntityTypes.ALARM).mapNotNull { AlarmRules.from(it, calendar) }
        val leave = derived()
        if (leave.isEmpty()) return stored.filter { it.kind != AlarmKind.LEAVE }
        val leaveIds = leave.mapTo(HashSet()) { it.id }
        val storedIds = stored.mapTo(HashSet()) { it.id }
        return stored.filter { it.kind != AlarmKind.LEAVE || it.id in leaveIds } + leave.filter { it.id !in storedIds }
    }

    fun alarm(id: String): Alarm? = replica.entity(EntityTypes.ALARM, id)?.let { AlarmRules.from(it, calendar) }
        ?.takeIf { it.kind != AlarmKind.LEAVE || derived().any { d -> d.id == id } }
        ?: derived().firstOrNull { it.id == id }

    /** A derived leave-by is written out in full the first time it changes (snooze, dismiss), so every device sees it. */
    private fun commit(a: Alarm, change: Map<String, os.meka.core.sync.FieldValue>) {
        val fields = if (a.kind == AlarmKind.LEAVE && replica.entity(EntityTypes.ALARM, a.id) == null) mapOf(
            AlarmFields.KIND to a.kind.name.fv(),
            AlarmFields.DAY to a.epochDay.fv(),
            AlarmFields.MINUTE to a.minute.fv(),
            AlarmFields.AT_MS to a.atMs.fv(),
            AlarmFields.OFF to false.fv(),
            AlarmFields.NOTE to a.note.fv(),
            AlarmFields.SET_AT to nowMs().fv(),
        ) + change else change
        replica.commitLocal(EntityTypes.ALARM, a.id, fields)
    }

    fun bufferMin(): Int = replica.entity(EntityTypes.CONTEXT_MODE, SETTINGS_ID)?.get(AlarmSettingsFields.WAKE_BUFFER)?.longOrNull?.toInt()
        ?.takeIf { AlarmRules.validBuffer(it) } ?: AlarmRules.DEFAULT_BUFFER_MIN

    /** Returns false (and writes nothing) for a buffer outside 15 min–3 h in fives. */
    fun setBuffer(min: Int): Boolean {
        if (!AlarmRules.validBuffer(min)) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, SETTINGS_ID, mapOf(AlarmSettingsFields.WAKE_BUFFER to min.fv()))
        return true
    }

    fun wakeDay(): Long = AlarmRules.wakeDay(nowMs(), calendar)

    /**
     * Sets the wake alarm for the next morning at [minute] (local), noting what it's for. Returns false for a time
     * that has already passed (just after midnight, a time before now this morning).
     */
    fun setWake(minute: Int, note: String?): Boolean {
        if (minute !in 0 until LocalClock.MINUTES_PER_DAY) return false
        val day = wakeDay()
        if (calendar.toEpochMs(day, minute) <= nowMs()) return false
        replica.commitLocal(
            EntityTypes.ALARM, AlarmRules.wakeId(day),
            mapOf(
                AlarmFields.KIND to AlarmKind.WAKE.name.fv(),
                AlarmFields.DAY to day.fv(),
                AlarmFields.MINUTE to minute.fv(),
                AlarmFields.OFF to false.fv(),
                AlarmFields.SNOOZED_UNTIL to (null as Long?).fv(),
                AlarmFields.DISMISSED_AT to (null as Long?).fv(),
                AlarmFields.NOTE to note?.trim()?.take(NOTE_MAX).fv(),
                AlarmFields.SET_AT to nowMs().fv(),
            ),
        )
        return true
    }

    /** Turns the next morning's wake alarm off (on every device). */
    fun wakeOff() {
        val id = AlarmRules.wakeId(wakeDay())
        if (replica.entity(EntityTypes.ALARM, id) == null) return
        replica.commitLocal(EntityTypes.ALARM, id, mapOf(AlarmFields.OFF to true.fv()))
    }

    /** Snoozes a ringing (or just-rung) alarm for 9 minutes. Returns the alarm as it now rings, or null if it can't. */
    fun snooze(id: String): AlarmRing? {
        val a = alarm(id) ?: return null
        val now = nowMs()
        if (a.off || a.dismissedAtMs != null || now < a.ringAtMs - 60_000L || now >= a.ringAtMs + AlarmRules.RING_FOR_MS) return null
        commit(a, mapOf(AlarmFields.SNOOZED_UNTIL to (now + AlarmRules.SNOOZE_MIN * 60_000L).fv()))
        return alarm(id)?.let { AlarmRules.ringIn(it, calendar) }
    }

    /** Dismisses it on every device. Returns false when there was nothing to dismiss. */
    fun dismiss(id: String): Boolean {
        val a = alarm(id) ?: return false
        if (a.dismissedAtMs != null) return false
        commit(a, mapOf(AlarmFields.DISMISSED_AT to nowMs().fv()))
        return true
    }

    /**
     * Sets a quick alarm or timer typed into capture (slice 2). An alarm for the same minute is one alarm on every
     * device (id from its day and minute), so setting it twice or on both devices rings once. Returns its id, or null
     * for a moment already gone.
     */
    fun setQuick(q: QuickAlarmRequest): String? {
        val now = nowMs()
        if (q.atMs <= now) return null
        val day = calendar.epochDayOf(q.atMs)
        val minute = calendar.minuteOfDay(q.atMs)
        val id = when (q.kind) {
            AlarmKind.TIMER -> "timer.t$now.s${q.lengthSec ?: 0}"
            else -> "alarm.d$day.m$minute"
        }
        replica.commitLocal(
            EntityTypes.ALARM, id,
            mapOf(
                AlarmFields.KIND to q.kind.name.fv(),
                AlarmFields.DAY to day.fv(),
                AlarmFields.MINUTE to minute.fv(),
                AlarmFields.AT_MS to (if (q.kind == AlarmKind.TIMER) q.atMs else null).fv(),
                AlarmFields.LENGTH_SEC to q.lengthSec?.toLong().fv(),
                AlarmFields.OFF to false.fv(),
                AlarmFields.SNOOZED_UNTIL to (null as Long?).fv(),
                AlarmFields.DISMISSED_AT to (null as Long?).fv(),
                AlarmFields.NOTE to q.label?.trim()?.take(NOTE_MAX).fv(),
                AlarmFields.SET_AT to now.fv(),
            ),
        )
        return id
    }

    /** Cancels a quick alarm or timer (or turns any alarm off) on every device. False when it's already off or gone. */
    fun cancel(id: String): Boolean {
        val a = alarm(id) ?: return false
        if (a.off || a.dismissedAtMs != null) return false
        commit(a, mapOf(AlarmFields.OFF to true.fv()))
        return true
    }

    /** The quick alarms and timers still to ring (or ringing), soonest first, for Today. */
    fun quickItems(): List<QuickAlarmItem> = QuickAlarmRules.items(all(), nowMs(), calendar)

    fun wakeView(events: List<CalendarEvent>, work: WorkHours): WakeView {
        val day = wakeDay()
        return AlarmRules.view(nowMs(), calendar, day, alarm(AlarmRules.wakeId(day)), events, work, bufferMin())
    }

    fun next(): AlarmRing? {
        val now = nowMs()
        val list = all()
        val ring = AlarmRules.next(list, now) ?: return null
        return list.firstOrNull { it.id == ring.id }?.let { AlarmRules.ringIn(it, calendar) }
    }

    companion object {
        const val SETTINGS_ID = "alarms"
        const val NOTE_MAX = 120
    }
}
