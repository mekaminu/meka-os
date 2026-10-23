package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/*
 * Car and parking, slice 2 (build plan V1, "Car and parking"; non-AI, pure): the parking timer, typed.
 *
 * He types how long he paid for, or until when ("2 h", "90 min", "until 15:30", "till 6pm", "1h30 RingGo 81234") on
 * either app; the car's timer runs until then and a heads-up comes **10 minutes before it ends** ("Parking ends at
 * 15:30" · "10 min left · extend it in your parking app, or move the car"). +15 min / +30 min / +1 h extend it (the
 * reminder comes again for the new time), Ended clears it (Undo). Unlike where the car is parked (the Fold's own,
 * never synced), the timer holds no location: only when it ends and the words he typed beside it (a RingGo location
 * code), so it is synced and the Mac shows it too (the item's "Mac shows the parking timer only").
 *
 * Stored on the car's own `context_mode` entity ([CarRules.CAR_IDS]; ADR-008 addendum 2026-10-14, additive, LWW):
 * [ParkingTimerFields]. One timer per car, so both cars can be paid for at once.
 */
object ParkingTimerFields {
    /** When the paid time ends (epoch ms); null when no timer runs. */
    const val ENDS_AT = "parkingEndsAt"
    /** When he set it (epoch ms), for "1 h 20 of 2 h left". */
    const val STARTED_AT = "parkingStartedAt"
    /** The words he typed beside the time ("RingGo 81234"), or null. */
    const val NOTE = "parkingNote"
}

/** One car's parking timer. */
data class ParkingTimer(val carId: String, val startedAtMs: Long, val endsAtMs: Long, val note: String? = null)

/** What typing read: when it ends and what else he typed. */
data class TimerTyped(val endsAtMs: Long, val note: String?)

/** A car's timer as both apps show it. */
data class TimerRow(
    val carId: String,
    /** "the Golf", "Car 2", or "Car" with one car. */
    val title: String,
    /** "Paid until 15:30 · 1 h 20 left", "Paid until 15:30 · 8 min left", "Ended 15:30 · 5 min ago". */
    val line: String,
    /** Running with [ParkingTimerRules.REMIND_MIN] or less left, or ended: brass. */
    val lit: Boolean,
    val ended: Boolean,
    /** "15:30", "tomorrow 08:00". */
    val endText: String,
    val note: String?,
    val timer: ParkingTimer,
)

/** Every car's timer now ([rows]) and the Car tile's line while one runs. */
data class TimersView(val rows: List<TimerRow>, val tile: LifeTileLine?) {
    val running: Boolean get() = rows.any { !it.ended }

    companion object {
        val EMPTY = TimersView(emptyList(), null)
    }
}

object ParkingTimerRules {
    /** The heads-up comes this long before the paid time ends. */
    const val REMIND_MIN = 10
    /** An ended timer still shows ("Ended 15:30 · 5 min ago") this long, then goes by itself. */
    const val SHOW_ENDED_MS = 2 * 60 * 60_000L
    /** The longest a typed timer runs. */
    const val MAX_MS = 24 * 60 * 60_000L
    const val MAX_NOTE = 40
    private const val MIN_MS = 60_000L

    const val HEADING = "PARKING TIMER"
    const val HINT = "Paid until 15:30, or 2 h"
    const val SET = "Start"
    /**
     * The timer's own button (quality gate, Car's re-shot 2026-10-23: a button reading "Ended" beside "2 h left" read as
     * a status that contradicts the line above it): "Stop timer" while it runs, "Clear" once it has ended.
     */
    const val STOP = "Stop timer"
    const val CLEAR = "Clear"
    const val NOT_READ = "Type how long (2 h, 90 min) or until when (15:30, 6pm)"
    const val EMPTY_LINE = "No timer · type how long you paid for and Navilife reminds you 10 min before it ends"
    /** The extend chips, in minutes. */
    val EXTEND_MIN = listOf(15, 30, 60)

    /** [STOP] while [row] runs, [CLEAR] once it has ended. */
    fun endAction(row: TimerRow): String = if (row.ended) CLEAR else STOP

    fun extendLabel(min: Int): String = if (min % 60 == 0) "+${min / 60} h" else "+$min min"

    // ---- typing ----

    private val NUMBER_WORDS = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6)
    private val FILLER = setOf("paid", "for", "until", "till", "til", "to", "by", "at", "ends", "ending", "parked", "parking", "and")
    private val HOUR_WORDS = setOf("h", "hr", "hrs", "hour", "hours")
    private val MIN_WORDS = setOf("m", "min", "mins", "minute", "minutes")

    /**
     * Reads what he typed: a length ("2 h", "2h", "90 min", "1h30", "1 h 30", "1.5 hours", "an hour", "half an hour")
     * or an end time ("until 15:30", "15.30", "till 6pm", "6:30pm"; a time already gone today means tomorrow). Other
     * words are kept as the note ("RingGo 81234"). Null when no length or time reads, or it's over 24 h.
     */
    fun parse(text: String?, nowMs: Long, cal: LocalCalendar): TimerTyped? {
        val raw = text.orEmpty().replace('\t', ' ').replace('\n', ' ').trim()
        if (raw.isEmpty()) return null
        val words = tokens(raw.lowercase())
        val original = tokens(raw)
        var lengthMin = 0.0
        var endMinute: Int? = null
        val used = BooleanArray(words.size)
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val next = words.getOrNull(i + 1)
            // "half an hour"
            if (w == "half" && next in setOf("an", "a") && words.getOrNull(i + 2) in HOUR_WORDS) {
                lengthMin += 30.0; used[i] = true; used[i + 1] = true; used[i + 2] = true; i += 3; continue
            }
            // "15:30", "15.30", "6pm", "6:30pm", "6 pm"
            val clock = clockOf(w, next)
            if (clock != null && (w.contains(':') || w.endsWith("am") || w.endsWith("pm") || next == "am" || next == "pm" ||
                    (w.contains('.') && next !in HOUR_WORDS && next !in MIN_WORDS))) {
                endMinute = clock.first; used[i] = true
                if (clock.second) used[i + 1] = true
                i += if (clock.second) 2 else 1; continue
            }
            // "1h30", "2h", "90m", "90min", "1.5h"
            val glued = gluedLength(w)
            if (glued != null) { lengthMin += glued; used[i] = true; i++; continue }
            // "2 h", "90 min", "an hour", "1.5 hours", "1 h 30"
            val n = w.toDoubleOrNull() ?: NUMBER_WORDS[w]?.toDouble()
            if (n != null && next != null && (next in HOUR_WORDS || next in MIN_WORDS)) {
                lengthMin += if (next in HOUR_WORDS) n * 60 else n
                used[i] = true; used[i + 1] = true; i += 2
                // "1 h 30": a bare number straight after hours is minutes.
                val after = words.getOrNull(i)?.toIntOrNull()
                if (next in HOUR_WORDS && after != null && after in 1..59 && words.getOrNull(i + 1).let { it == null || it !in HOUR_WORDS }) {
                    lengthMin += after; used[i] = true; i++
                    if (words.getOrNull(i) in MIN_WORDS) { used[i] = true; i++ }
                }
                continue
            }
            // "until 18" — a bare hour after until/till.
            val bare = w.toIntOrNull()
            if (bare != null && bare in 0..23 && words.getOrNull(i - 1) in setOf("until", "till", "til")) {
                endMinute = bare * 60; used[i] = true; i++; continue
            }
            i++
        }
        val endsAt = when {
            endMinute != null -> {
                val today = cal.epochDayOf(nowMs)
                var at = cal.toEpochMs(today, endMinute)
                if (at <= nowMs) at = cal.toEpochMs(today + 1, endMinute)
                at
            }
            lengthMin >= 1.0 -> nowMs + (lengthMin * MIN_MS).toLong()
            else -> return null
        }
        if (endsAt - nowMs > MAX_MS || endsAt <= nowMs) return null
        val note = original.indices.filter { !used[it] && words[it] !in FILLER }.joinToString(" ") { original[it] }
        return TimerTyped(endsAt, cleanNote(note))
    }

    private fun tokens(s: String): List<String> =
        s.replace(",", " ").replace(";", " ").split(' ').map { it.trim() }.filter { it.isNotEmpty() }

    /** A clock time from [w] (and [next] when it is "am"/"pm"): the minute of the day and whether [next] was used. */
    private fun clockOf(w: String, next: String?): Pair<Int, Boolean>? {
        var s = w
        var suffix: String? = null
        var usedNext = false
        if (s.endsWith("am") || s.endsWith("pm")) { suffix = s.takeLast(2); s = s.dropLast(2) }
        else if (next == "am" || next == "pm") { suffix = next; usedNext = true }
        val parts = s.split(':', '.')
        if (parts.isEmpty() || parts.size > 2) return null
        var h = parts[0].toIntOrNull() ?: return null
        val m = if (parts.size == 2) (parts[1].takeIf { it.length == 2 }?.toIntOrNull() ?: return null) else 0
        if (m !in 0..59) return null
        if (suffix != null) {
            if (h !in 1..12) return null
            h = (h % 12) + if (suffix == "pm") 12 else 0
        } else if (parts.size != 2) return null
        if (h !in 0..23) return null
        return h * 60 + m to usedNext
    }

    /** "2h", "1h30", "1.5h", "90m", "90min", "45mins": minutes, or null. */
    private fun gluedLength(w: String): Double? {
        val hi = w.indexOf('h')
        if (hi > 0) {
            val n = w.substring(0, hi).toDoubleOrNull() ?: return null
            val rest = w.substring(hi).removePrefix("hours").removePrefix("hour").removePrefix("hrs").removePrefix("hr").removePrefix("h")
            if (rest.isEmpty()) return n * 60
            val m = rest.removeSuffix("mins").removeSuffix("min").removeSuffix("m").toIntOrNull() ?: return null
            return if (m in 0..59) n * 60 + m else null
        }
        for (suffix in listOf("minutes", "minute", "mins", "min", "m")) {
            if (w.endsWith(suffix) && w.length > suffix.length) return w.dropLast(suffix.length).toDoubleOrNull()
        }
        return null
    }

    fun cleanNote(text: String?): String? = text.orEmpty().replace('\t', ' ').replace('\n', ' ').trim().take(MAX_NOTE).trim().ifEmpty { null }

    // ---- what the apps show ----

    /** "1 h 20", "2 h", "8 min", "1 min". */
    fun leftText(ms: Long): String {
        val mins = ((ms + MIN_MS - 1) / MIN_MS).coerceAtLeast(1)
        return when {
            mins < 60 -> "$mins min"
            mins % 60 == 0L -> "${mins / 60} h"
            else -> "${mins / 60} h ${(mins % 60).toString().padStart(2, '0')}"
        }
    }

    /** "15:30", or "tomorrow 08:00" when it ends on a later day than [nowMs]. */
    fun endText(endsAtMs: Long, nowMs: Long, cal: LocalCalendar): String {
        val hhmm = LocalClock.formatMinute(cal.minuteOfDay(endsAtMs))
        return when (cal.epochDayOf(endsAtMs) - cal.epochDayOf(nowMs)) {
            0L -> hhmm
            1L -> "tomorrow $hhmm"
            else -> "${CivilDate.shortLabel(cal.epochDayOf(endsAtMs))} $hhmm"
        }
    }

    /** Whether [t] still shows at [nowMs]: running, or ended less than [SHOW_ENDED_MS] ago. */
    fun shows(t: ParkingTimer, nowMs: Long): Boolean = nowMs < t.endsAtMs + SHOW_ENDED_MS

    fun row(t: ParkingTimer, title: String, nowMs: Long, cal: LocalCalendar): TimerRow {
        val left = t.endsAtMs - nowMs
        return if (left > 0) {
            TimerRow(t.carId, title, "Paid until ${endText(t.endsAtMs, nowMs, cal)} · ${leftText(left)} left",
                left <= REMIND_MIN * MIN_MS, false, endText(t.endsAtMs, nowMs, cal), t.note, t)
        } else {
            TimerRow(t.carId, title, "Ended ${endText(t.endsAtMs, nowMs, cal)} · ${ParkingRules.ago(-left / MIN_MS)}", true, true, endText(t.endsAtMs, nowMs, cal), t.note, t)
        }
    }

    /** The cars' timers that still show, in the cars' order. */
    fun view(timers: List<ParkingTimer>, cars: CarsView, nowMs: Long, cal: LocalCalendar): TimersView {
        val order = cars.cars.map { it.carId }
        val rows = timers.filter { shows(it, nowMs) }
            .sortedBy { order.indexOf(it.carId).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .map { row(it, ParkingRules.titleOf(it.carId, cars), nowMs, cal) }
        return TimersView(rows, tileLine(rows))
    }

    /** The Car tile while a timer runs (lit): "Paid until 15:30 · 40 min left", "The Golf · paid until …", "2 parking timers". */
    fun tileLine(rows: List<TimerRow>): LifeTileLine? {
        val running = rows.filter { !it.ended }
        return when {
            running.isEmpty() -> null
            running.size > 1 -> LifeTileLine("${running.size} parking timers · next ends ${running.minBy { it.timer.endsAtMs }.endText}", true)
            else -> running.first().let { r ->
                val who = if (r.title == ParkingRules.TITLE) "" else "${r.title.replaceFirstChar { it.titlecaseChar() }} · "
                LifeTileLine(who + if (who.isEmpty()) r.line else r.line.replaceFirstChar { it.lowercaseChar() }, true)
            }
        }
    }

    /** "Parking timer until 15:30 · reminds you at 15:20", or "… · ends in under 10 min" when set that late. */
    fun setLine(t: ParkingTimer, title: String, nowMs: Long, cal: LocalCalendar): String {
        val who = if (title == ParkingRules.TITLE) "" else " · $title"
        val remindAt = t.endsAtMs - REMIND_MIN * MIN_MS
        val tail = if (remindAt > nowMs) "reminds you at ${LocalClock.formatMinute(cal.minuteOfDay(remindAt))}" else "ends in under $REMIND_MIN min"
        return "Parking timer until ${endText(t.endsAtMs, nowMs, cal)}$who · $tail"
    }

    /** "Parking timer ended · the Golf". */
    fun clearedLine(title: String): String = "Parking timer ended" + if (title == ParkingRules.TITLE) "" else " · $title"

    /**
     * The heads-up [REMIND_MIN] minutes before each running timer ends (at once when set later than that), keyed by the
     * end so an extended timer reminds again: "Parking ends at 15:30" (· the Golf with two cars) · "10 min left ·
     * extend it in your parking app, or move the car". Stale once the time is up.
     */
    fun notices(timers: List<ParkingTimer>, cars: CarsView, nowMs: Long, cal: LocalCalendar): List<Notice> =
        timers.filter { it.endsAtMs > nowMs }.map { t ->
            val title = ParkingRules.titleOf(t.carId, cars)
            val at = maxOf(t.endsAtMs - REMIND_MIN * MIN_MS, t.startedAtMs)
            val who = if (title == ParkingRules.TITLE || cars.cars.size <= 1) "" else " · $title"
            Notice(
                key = "parking:${t.carId}:${t.endsAtMs}", source = NoticeSource.PARKING, tier = NoticeTier.HEADS_UP,
                title = "Parking ends at ${LocalClock.formatMinute(cal.minuteOfDay(t.endsAtMs))}$who",
                text = "${leftText(t.endsAtMs - at)} left · extend it in your parking app, or move the car" + (t.note?.let { " · $it" } ?: ""),
                atMs = at, target = NoticeTarget.TODAY, expiresAtMs = t.endsAtMs, precision = NoticePrecision.CLOCK,
            )
        }
}

/** The cars' parking timers on a replica. Every write is an op: offline-first, synced. */
class ParkingTimers(private val replica: Replica) {
    fun all(): List<ParkingTimer> = CarRules.CAR_IDS.mapNotNull { id ->
        val e = replica.entity(EntityTypes.CONTEXT_MODE, id) ?: return@mapNotNull null
        val ends = e.get(ParkingTimerFields.ENDS_AT)?.longOrNull ?: return@mapNotNull null
        ParkingTimer(id, e.get(ParkingTimerFields.STARTED_AT)?.longOrNull ?: ends, ends, e.get(ParkingTimerFields.NOTE)?.textOrNull?.let(ParkingTimerRules::cleanNote))
    }

    fun timer(carId: String): ParkingTimer? = all().firstOrNull { it.carId == carId }

    /**
     * Starts (or replaces) [carId]'s timer from typed text, or (slice 5) a pasted parking confirmation ("RingGo …
     * Expires 15:30 …", read by [ParkingConfirmationRules]; a long paste is read as one first). The timer, or null
     * when it doesn't read (or the pasted session has already ended).
     */
    fun set(carId: String, text: String?, nowMs: Long, cal: LocalCalendar): ParkingTimer? {
        if (carId !in CarRules.CAR_IDS) return null
        fun shared() = ParkingConfirmationRules.read(text, null, nowMs, cal)?.takeIf { !it.ended }
        if (ParkingConfirmationRules.flat(text).length > PASTE_CHARS && ParkingConfirmationRules.looksLikeOne(text)) {
            // A pasted confirmation is read only as one: an ended session must not become tomorrow's timer.
            val pasted = shared() ?: return null
            put(ParkingConfirmationRules.timer(pasted, carId, nowMs)); return timer(carId)
        }
        val typed = ParkingTimerRules.parse(text, nowMs, cal)
        if (typed == null) {
            val late = shared() ?: return null
            put(ParkingConfirmationRules.timer(late, carId, nowMs)); return timer(carId)
        }
        put(ParkingTimer(carId, nowMs, typed.endsAtMs, typed.note))
        return timer(carId)
    }

    private companion object {
        /** Longer than this, what's in the timer field is a pasted confirmation, not something he typed. */
        const val PASTE_CHARS = 60
    }

    /** Adds [minutes] to [carId]'s timer (from now, if it had already ended). Null without a timer. */
    fun extend(carId: String, minutes: Int, nowMs: Long): ParkingTimer? {
        val t = timer(carId) ?: return null
        if (minutes <= 0) return t
        val ends = (maxOf(t.endsAtMs, nowMs) + minutes * 60_000L).coerceAtMost(nowMs + ParkingTimerRules.MAX_MS)
        put(t.copy(endsAtMs = ends))
        return timer(carId)
    }

    /** Ends [carId]'s timer; the old one is returned for Undo ([put] puts it back). */
    fun clear(carId: String): ParkingTimer? {
        val t = timer(carId) ?: return null
        replica.commitLocal(EntityTypes.CONTEXT_MODE, carId, mapOf(
            ParkingTimerFields.ENDS_AT to FieldValue.Null, ParkingTimerFields.STARTED_AT to FieldValue.Null, ParkingTimerFields.NOTE to FieldValue.Null,
        ))
        return t
    }

    fun put(t: ParkingTimer) {
        replica.commitLocal(EntityTypes.CONTEXT_MODE, t.carId, mapOf(
            ParkingTimerFields.ENDS_AT to t.endsAtMs.fv(), ParkingTimerFields.STARTED_AT to t.startedAtMs.fv(),
            ParkingTimerFields.NOTE to t.note.fv(),
        ))
    }
}
