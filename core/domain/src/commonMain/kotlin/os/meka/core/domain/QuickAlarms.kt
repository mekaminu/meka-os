package os.meka.core.domain

/**
 * Alarms, slice 2: **quick alarms and timers from capture** ("alarm 6:30", "timer 20 min"). Non-AI, pure.
 *
 * Only text Meka types into MEKA's own capture field (Today's capture bar on the Fold, the Mac's capture field, menu
 * bar and command bar) is read this way. Shared text (the share sheet, Services) is untrusted (ADR-006) and always
 * becomes a task, never an alarm: see [QuickCapture].
 *
 * What it understands, case-insensitively, on one line:
 * - Alarms: "alarm 6:30" · "alarm at 18:30" · "alarm for 7am" · "set an alarm for 6.45 pm" · "wake me at 6:30" ·
 *   "wake me up at 7am", optionally followed by what it's for ("alarm 6:30 gym", "alarm 7pm to call Mum"). A time
 *   without am/pm is on the 24-hour clock; it rings at the next such time (today if still ahead, else tomorrow). A bare
 *   hour with nothing after it ("alarm 7") is 07:00; with words after it it's taken as a task ("alarm 2 things").
 * - Timers: "timer 20 min" · "timer for 1 h 30" · "timer 90 s" · "20 min timer" · "set a timer for 1.5 h" ·
 *   "timer 1h30" · "timer 25" (minutes), optionally followed by what it's for ("timer 20 min pasta"). One second to
 *   24 hours.
 * Anything else is an ordinary capture (a task).
 */
data class QuickAlarmRequest(
    val kind: AlarmKind,
    /** When it rings. */
    val atMs: Long,
    /** A timer's length in seconds; null for an alarm. */
    val lengthSec: Int?,
    /** What it's for ("Pasta", "call Mum"), as typed; null when nothing was. */
    val label: String?,
)

/** A quick alarm or timer still to ring, as Today lists it. */
data class QuickAlarmItem(
    val id: String,
    val kind: AlarmKind,
    val ringAtMs: Long,
    /** What it's for, else "Alarm" / "Timer". */
    val title: String,
    /** "06:30 · Tomorrow" · "20 min · ends 14:52 · 18 min left" · "Snoozed until 06:54" · "Ringing". */
    val detail: String,
    /** The cancel control's screen-reader label: "Cancel the 06:30 alarm" · "Cancel the 20 min timer". */
    val cancelLabel: String,
)

/** What capture typed into MEKA became. */
sealed class CaptureOutcome {
    data class TaskAdded(val taskId: String) : CaptureOutcome()
    /** A quick alarm or timer: [line] is the confirmation for the undo bar ("Alarm set for 06:30 tomorrow"). */
    data class AlarmSet(val alarmId: String, val kind: AlarmKind, val line: String) : CaptureOutcome()
    /** Nothing to capture (blank), or an alarm whose moment had already gone. */
    object Empty : CaptureOutcome()
}

object QuickAlarmRules {
    const val ALARM_TITLE = "Alarm"
    const val TIMER_TITLE = "Timer"
    const val TIMES_UP = "Time's up"
    const val LABEL_MAX = 60
    const val MAX_TIMER_SEC = 24 * 60 * 60

    private val opt = setOf(RegexOption.IGNORE_CASE)
    private const val TIME = """(\d{1,2})(?:[:.](\d{2}))?\s*(a\.m\.?|p\.m\.?|am|pm)?"""
    private val alarm = Regex("""^(?:(?:set|add|make)\s+(?:an?\s+)?)?alarm\s+(?:for\s+|at\s+)?$TIME(?:\s+(.+))?$""", opt)
    private val wakeMe = Regex("""^wake\s+me(?:\s+up)?\s+(?:at\s+)?$TIME(?:\s+(.+))?$""", opt)
    private val timerFirst = Regex("""^(?:(?:set|start|add)\s+(?:an?\s+)?)?timer\s+(?:for\s+)?(.+)$""", opt)
    private val durationToken = Regex(
        """^(?:and\s+)?(\d+(?:[.,]\d+)?)\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)?(?![a-z])\s*""", opt,
    )
    private val timerWord = Regex("""^timer(?![a-z])\s*""", opt)
    private val labelLead = Regex("""^(?:for|to|-|–|—|·|:)\s+""", opt)
    private val space = Regex("""\s+""")

    /** The alarm or timer [text] asks for, or null when it's an ordinary capture. */
    fun parse(text: String?, nowMs: Long, cal: LocalCalendar): QuickAlarmRequest? {
        val raw = text?.trim() ?: return null
        if (raw.contains('\n') || raw.contains('\r')) return null
        val line = raw.trimEnd('.', '!').replace(space, " ")
        if (line.isEmpty()) return null
        (alarm.matchEntire(line) ?: wakeMe.matchEntire(line))?.let { m ->
            val minute = minuteOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], hasMore = m.groupValues[4].isNotBlank())
                ?: return null
            val today = cal.epochDayOf(nowMs)
            var at = cal.toEpochMs(today, minute)
            if (at <= nowMs) at = cal.toEpochMs(today + 1, minute)
            return QuickAlarmRequest(AlarmKind.ALARM, at, null, label(m.groupValues[4]))
        }
        timerFirst.matchEntire(line)?.let { m ->
            val (sec, rest) = duration(m.groupValues[1]) ?: return null
            return timer(nowMs, sec, rest)
        }
        // "20 min timer pasta"
        duration(line)?.let { (sec, rest) ->
            val after = timerWord.find(rest) ?: return null
            return timer(nowMs, sec, rest.substring(after.range.last + 1))
        }
        return null
    }

    private fun timer(nowMs: Long, sec: Int, rest: String): QuickAlarmRequest? {
        if (sec < 1 || sec > MAX_TIMER_SEC) return null
        return QuickAlarmRequest(AlarmKind.TIMER, nowMs + sec * 1000L, sec, label(rest))
    }

    private fun minuteOf(h: String, m: String, ampm: String, hasMore: Boolean): Int? {
        var hour = h.toIntOrNull() ?: return null
        val min = if (m.isEmpty()) 0 else m.toIntOrNull() ?: return null
        if (min > 59) return null
        val mer = ampm.lowercase().replace(".", "")
        when {
            mer.isEmpty() -> {
                if (hour > 23) return null
                // "alarm 2 things" reads like a task; "alarm 7" alone is 07:00.
                if (m.isEmpty() && hasMore) return null
            }
            hour !in 1..12 -> return null
            mer == "am" -> if (hour == 12) hour = 0
            else -> if (hour != 12) hour += 12
        }
        return hour * 60 + min
    }

    /** Reads a length from the start of [s]: (seconds, what's left), or null when it doesn't start with one. */
    fun duration(s: String): Pair<Int, String>? {
        var rest = s.trim()
        var total = 0.0
        var lastUnit: Char? = null
        var any = false
        while (true) {
            val m = durationToken.find(rest) ?: break
            if (m.value.isBlank()) break
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: break
            val unit = m.groupValues[2].lowercase().firstOrNull() ?: when (lastUnit) {
                'h' -> 'm' // "1h30", "1 h 30"
                'm' -> 's' // "2m30"
                null -> 'm' // "timer 25"
                else -> break
            }
            if (m.groupValues[2].isEmpty() && m.groupValues[1].contains(Regex("[.,]"))) break // "1.5" needs a unit
            if (lastUnit != null && unit == lastUnit) break
            total += n * when (unit) { 'h' -> 3600.0; 'm' -> 60.0; else -> 1.0 }
            lastUnit = unit
            any = true
            rest = rest.substring(m.range.last + 1)
            if (m.groupValues[2].isEmpty()) break // a unit-less number ends the length
        }
        if (!any) return null
        val sec = total.toLong()
        if (sec > MAX_TIMER_SEC) return null
        return sec.toInt() to rest.trim()
    }

    private fun label(s: String): String? {
        val t = s.trim().replace(labelLead, "").trim()
        if (t.isEmpty()) return null
        return if (t.length <= LABEL_MAX) t else t.substring(0, LABEL_MAX - 1).trimEnd() + "…"
    }

    /** "45 s" · "20 min" · "1 h" · "1 h 30" · "1 min 30 s". */
    fun length(sec: Int): String = when {
        sec < 60 -> "$sec s"
        sec % 60 == 0 -> AlarmRules.duration(sec / 60)
        else -> "${AlarmRules.duration(sec / 60)} ${sec % 60} s"
    }

    /** "Today" · "Tomorrow" · "Fri 9 Oct" for the day [atMs] falls on. */
    private fun dayWord(atMs: Long, nowMs: Long, cal: LocalCalendar): String {
        val d = cal.epochDayOf(atMs)
        val today = cal.epochDayOf(nowMs)
        return when (d) {
            today -> "Today"
            today + 1 -> "Tomorrow"
            else -> CivilDate.shortLabel(d)
        }
    }

    /** The undo bar's line once it's set: "Alarm set for 06:30 tomorrow" · "Timer set · 20 min · ends 14:52". */
    fun setLine(q: QuickAlarmRequest, nowMs: Long, cal: LocalCalendar): String {
        val clock = LocalClock.formatMinute(cal.minuteOfDay(q.atMs))
        val forWhat = q.label?.let { " · $it" } ?: ""
        return when (q.kind) {
            AlarmKind.TIMER -> "Timer set · ${length(q.lengthSec ?: 0)} · ends $clock$forWhat"
            else -> {
                val day = when (val w = dayWord(q.atMs, nowMs, cal)) {
                    "Today" -> ""
                    "Tomorrow" -> " tomorrow"
                    else -> " · $w"
                }
                "Alarm set for $clock$day$forWhat"
            }
        }
    }

    /** The quick alarms and timers still on (or ringing now), soonest first. */
    fun items(alarms: List<Alarm>, nowMs: Long, cal: LocalCalendar): List<QuickAlarmItem> = alarms
        .filter { it.kind != AlarmKind.WAKE && !it.off && it.dismissedAtMs == null && it.ringAtMs + AlarmRules.RING_FOR_MS > nowMs }
        .sortedWith(compareBy<Alarm> { it.ringAtMs }.thenBy { it.id })
        .map { item(it, nowMs, cal) }

    fun item(a: Alarm, nowMs: Long, cal: LocalCalendar): QuickAlarmItem {
        val note = a.note?.trim()?.takeIf { it.isNotEmpty() }
        val clock = LocalClock.formatMinute(cal.minuteOfDay(a.atMs))
        val ringing = AlarmRules.ringing(a, nowMs)
        val snoozed = a.snoozedUntilMs?.takeIf { !ringing }?.let { "Snoozed until ${LocalClock.formatMinute(cal.minuteOfDay(it))}" }
        val detail = when {
            ringing -> if (a.kind == AlarmKind.TIMER) TIMES_UP else "Ringing"
            snoozed != null -> snoozed
            a.kind == AlarmKind.TIMER -> {
                val left = ((a.ringAtMs - nowMs + 59_999) / 60_000).toInt().coerceAtLeast(1)
                "${length(a.lengthSec ?: 0)} · ends $clock · ${AlarmRules.duration(left)} left"
            }
            else -> "$clock · ${dayWord(a.atMs, nowMs, cal)}"
        }
        return QuickAlarmItem(
            id = a.id,
            kind = a.kind,
            ringAtMs = a.ringAtMs,
            title = note ?: if (a.kind == AlarmKind.TIMER) TIMER_TITLE else ALARM_TITLE,
            detail = detail,
            cancelLabel = if (a.kind == AlarmKind.TIMER) "Cancel the ${length(a.lengthSec ?: 0)} timer" else "Cancel the $clock alarm",
        )
    }
}
