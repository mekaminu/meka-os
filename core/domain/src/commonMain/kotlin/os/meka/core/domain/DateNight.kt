package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Date night (V1, Meka approved 2026-10-10), slice 1: a protected evening every two weeks. Meka picks the evening once
 * (a weekday, a start time and which week it starts); from then on the planner ([DayPlanner]) and Gym bookings
 * ([SessionRules.book]) keep that evening clear, Today's header says "Date night tonight from 19:00" on the day, and
 * one night can be skipped (that evening is free to plan again). Slice 2: the nudge a week before — a Needs you card
 * ("Date night · Fri 23 Oct" · "Book somewhere and arrange cover" · Booked · Skip this one) and a heads-up at 09:00
 * seven days ahead ([DateNightRules.notices]), both gone once the night is booked or skipped.
 *
 * Stored as one `context_mode` entity, id [DateNightRules.ENTITY_ID] (ADR-008 addendum 2026-10-10): `weekday`
 * (ISO 1..7; 0 = off), `startMin`, `anchorDay` (the epoch day of one date night: the fortnight's phase), `skipped`
 * (comma-separated epoch days), `booked` (the nights Meka has booked, the same way; slice 2). All LWW, so the latest choice on any device wins. Plain, non-AI rules; nothing leaves
 * Meka's own synced data.
 */
object DateNightFields {
    const val WEEKDAY = "weekday"
    const val START = "startMin"
    const val ANCHOR = "anchorDay"
    const val SKIPPED = "skipped"
    const val BOOKED = "booked"
}

/** The setting as stored. [weekday] 0 is off. */
data class DateNightSetting(
    val weekday: Int, val startMin: Int, val anchorDay: Long, val skipped: Set<Long> = emptySet(), val booked: Set<Long> = emptySet(),
) {
    val on: Boolean get() = weekday in 1..7
}

/** One coming date night in the pane: "Fri 23 Oct" · "19:00 · kept clear", or "Skipped · the evening is free to plan". */
data class DateNightRow(val day: Long, val label: String, val line: String, val skipped: Boolean, val spoken: String)

/**
 * The nudge before a kept night that isn't booked (slice 2), in Needs you on both apps: "In 7 days · from 19:00" ·
 * "Date night · Fri 23 Oct" · "Book somewhere and arrange cover" · [bookedLabel] · [skipLabel].
 */
data class DateNightNudge(
    val day: Long,
    val line: String,
    val title: String,
    val question: String,
    /** What the buttons do: "Booked takes this off Needs you. Skip this one frees the evening." */
    val detail: String,
    val spoken: String,
    val bookedLabel: String = DateNightRules.BOOKED_LABEL,
    val skipLabel: String = DateNightRules.SKIP_LABEL,
)

/** A choice of first night ("Fri 16 Oct" or "Fri 23 Oct"): which fortnight the evenings fall in. */
data class DateNightStart(val day: Long, val label: String, val chosen: Boolean)

/** Today's header on the day: "Date night tonight from 19:00". */
data class DateNightLine(val text: String, val spoken: String)

data class DateNightView(
    val on: Boolean,
    /** The chosen weekday (ISO 1..7), or the default when off, so the chips have one lit. */
    val weekday: Int,
    val startMin: Int,
    /** "Every other Friday from 19:00 · next Fri 23 Oct", or [DateNightRules.OFF_LINE]. */
    val summary: String,
    /** The next few nights, soonest first; empty when off. */
    val nights: List<DateNightRow>,
    /** The two possible first nights for the chosen weekday; empty when off. */
    val starts: List<DateNightStart>,
    /** The week-before card for Needs you (slice 2); null when there's nothing to book. */
    val nudge: DateNightNudge? = null,
) {
    companion object {
        val EMPTY = DateNightView(false, DateNightRules.DEFAULT_WEEKDAY, DateNightRules.DEFAULT_START, DateNightRules.OFF_LINE, emptyList(), emptyList())
    }
}

object DateNightRules {
    const val ENTITY_ID = "date_night"
    const val TITLE = "Date night"
    const val EVERY_DAYS = 14
    const val DEFAULT_WEEKDAY = 5
    const val DEFAULT_START = 19 * 60
    /** How long the evening is kept clear from its start (cut at midnight). */
    const val LENGTH_MIN = 4 * 60
    /** Today's header says it from noon on the day until the evening is over. */
    const val TODAY_FROM_MIN = 12 * 60
    /** Coming nights shown in the pane. */
    const val SHOWN = 4

    val START_CHOICES: List<Int> = listOf(18 * 60, 18 * 60 + 30, 19 * 60, 19 * 60 + 30, 20 * 60)
    val WEEKDAY_CHOICES: List<String> = LocalClock.DAY_SHORT

    const val OFF_LINE = "Off · pick an evening and MEKA keeps it clear every two weeks"
    const val NOTE = "The planner and Gym bookings leave the evening clear. Skip one and that evening is free to plan again."
    const val OFF_LABEL = "Turn off"
    const val SKIP_LABEL = "Skip this one"
    const val KEEP_LABEL = "Keep it"
    const val BOOKED_LABEL = "Booked"
    /** The nudge shows from this many days before a kept night, until it starts. */
    const val NUDGE_DAYS = 7
    /** The heads-up's time, [NUDGE_DAYS] before the night. */
    const val NUDGE_NOTICE_MIN = 9 * 60
    const val NUDGE_QUESTION = "Book somewhere and arrange cover"
    const val NUDGE_DETAIL = "Booked takes this off Needs you. Skip this one frees the evening to plan."

    fun read(fields: Map<String, FieldValue>?): DateNightSetting? {
        if (fields == null) return null
        val weekday = fields[DateNightFields.WEEKDAY]?.longOrNull?.toInt() ?: return null
        val start = fields[DateNightFields.START]?.longOrNull?.toInt()?.takeIf { it in 0 until LocalClock.MINUTES_PER_DAY } ?: DEFAULT_START
        val anchor = fields[DateNightFields.ANCHOR]?.longOrNull ?: return null
        return DateNightSetting(
            weekday, start, anchor, decodeDays(fields[DateNightFields.SKIPPED]?.textOrNull), decodeDays(fields[DateNightFields.BOOKED]?.textOrNull),
        )
    }

    fun decodeDays(text: String?): Set<Long> = text.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    fun encodeDays(days: Set<Long>): String = days.sorted().joinToString(",")

    /** [day] is one of the fortnightly evenings (skipped or not). */
    fun isNight(s: DateNightSetting?, day: Long): Boolean =
        s != null && s.on && CivilDate.isoDayOfWeek(day) == s.weekday && (day - s.anchorDay).mod(EVERY_DAYS.toLong()) == 0L

    /** [day] is a date night that is kept clear (not skipped). */
    fun kept(s: DateNightSetting?, day: Long): Boolean = isNight(s, day) && day !in s!!.skipped

    /** The first [weekday] on or after [today], or a week later. */
    fun firstNight(weekday: Int, today: Long, nextWeek: Boolean = false): Long =
        today + (weekday - CivilDate.isoDayOfWeek(today)).mod(7) + if (nextWeek) 7 else 0

    /** The next [count] date nights from [from] (inclusive), skipped ones included. */
    fun nights(s: DateNightSetting?, from: Long, count: Int): List<Long> {
        if (s == null || !s.on) return emptyList()
        var d = firstNight(s.weekday, from)
        if (!isNight(s, d)) d += 7
        return (0 until count).map { d + it * EVERY_DAYS.toLong() }
    }

    /** The evening kept clear on [day] (start to start + [LENGTH_MIN], cut at midnight), or null. */
    fun slot(s: DateNightSetting?, day: Long, cal: LocalCalendar): DayPlanner.Slot? {
        if (!kept(s, day)) return null
        val end = s!!.startMin + LENGTH_MIN
        val endMs = if (end >= LocalClock.MINUTES_PER_DAY) cal.toEpochMs(day + 1, 0) else cal.toEpochMs(day, end)
        return DayPlanner.Slot(cal.toEpochMs(day, s.startMin), endMs)
    }

    /** The planner's block for [day] ("Date night", shown with the kept-free blocks), or null. */
    fun block(s: DateNightSetting?, day: Long, cal: LocalCalendar): DayPlanner.MealBlock? =
        slot(s, day, cal)?.let { DayPlanner.MealBlock(TITLE, it.startMs, it.endMs) }

    /** "Tonight", "Tomorrow", "Fri 23 Oct". */
    fun dayLabel(day: Long, today: Long): String = when (day) {
        today -> "Tonight"
        today + 1 -> "Tomorrow"
        else -> CivilDate.shortLabel(day)
    }

    /** "Every other Friday from 19:00". */
    fun everyLine(s: DateNightSetting): String =
        "Every other ${CivilDate.DAY_LONG[s.weekday - 1]} from ${LocalClock.formatMinute(s.startMin)}"

    /** "In 7 days", "In 2 days", "Tomorrow", "Tonight". */
    fun inDays(day: Long, today: Long): String = when (val n = day - today) {
        0L -> "Tonight"
        1L -> "Tomorrow"
        else -> "In $n days"
    }

    /**
     * The week-before card: the soonest kept night from [NUDGE_DAYS] days ahead that isn't booked, until its evening
     * starts ([minuteOfDay] on the night itself). Null when off, booked, skipped or further off.
     */
    fun nudge(s: DateNightSetting?, today: Long, minuteOfDay: Int): DateNightNudge? {
        if (s == null || !s.on) return null
        val day = nights(s, today, 2).firstOrNull { d ->
            d - today <= NUDGE_DAYS && kept(s, d) && d !in s.booked && !(d == today && minuteOfDay >= s.startMin)
        } ?: return null
        val hhmm = LocalClock.formatMinute(s.startMin)
        val line = "${inDays(day, today)} · from $hhmm"
        val title = "$TITLE · ${CivilDate.shortLabel(day)}"
        return DateNightNudge(day, line, title, NUDGE_QUESTION, NUDGE_DETAIL, spoken = "$TITLE on ${CivilDate.shortLabel(day)} from $hhmm. $NUDGE_QUESTION.")
    }

    /**
     * The nudge's heads-up (source [NoticeSource.DATE_NIGHT]) through the governor like every notice, so quiet hours and
     * Meka's tier apply: at [NUDGE_NOTICE_MIN] [NUDGE_DAYS] days before, standing until the evening starts. Gone once
     * the night is booked or skipped (rules re-run each evaluation). A night set up less than a week ahead posts at once.
     */
    fun notices(s: DateNightSetting?, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val n = nudge(s, cal.epochDayOf(nowMs), cal.minuteOfDay(nowMs)) ?: return emptyList()
        return listOf(Notice(
            key = "date-night:${n.day}:nudge", source = NoticeSource.DATE_NIGHT, tier = NoticeTier.HEADS_UP,
            title = n.title, text = "${n.question} · from ${LocalClock.formatMinute(s!!.startMin)}",
            atMs = cal.toEpochMs(n.day - NUDGE_DAYS, NUDGE_NOTICE_MIN), target = NoticeTarget.NEEDS_YOU,
            expiresAtMs = cal.toEpochMs(n.day, s.startMin),
        ))
    }

    /** The undo bar's line after Booked: "Date night Fri 23 Oct · booked"; after taking it back, "… · not booked". */
    fun bookedLine(day: Long, today: Long, booked: Boolean): String {
        val label = if (day == today) "tonight" else dayLabel(day, today).let { if (it == "Tomorrow") "tomorrow" else it }
        return "$TITLE $label · ${if (booked) "booked" else "not booked yet"}"
    }

    fun view(s: DateNightSetting?, today: Long, minuteOfDay: Int = 0): DateNightView {
        if (s == null || !s.on) return DateNightView.EMPTY.copy(weekday = s?.weekday?.takeIf { it in 1..7 } ?: DEFAULT_WEEKDAY, startMin = s?.startMin ?: DEFAULT_START)
        val coming = nights(s, today, SHOWN)
        val rows = coming.map { d ->
            val label = dayLabel(d, today)
            val skipped = d in s.skipped
            val booked = !skipped && d in s.booked
            val line = when {
                skipped -> "Skipped · the evening is free to plan"
                booked -> "${LocalClock.formatMinute(s.startMin)} · kept clear · booked"
                else -> "${LocalClock.formatMinute(s.startMin)} · kept clear"
            }
            val spoken = when {
                skipped -> "$label: skipped."
                else -> "$label: date night from ${LocalClock.formatMinute(s.startMin)}, kept clear${if (booked) ", booked" else ""}."
            }
            DateNightRow(d, label, line, skipped, spoken)
        }
        val next = coming.firstOrNull { it !in s.skipped }
        val nextPart = when (next) {
            null -> "the next ${SHOWN} are skipped"
            today -> "tonight"
            else -> "next ${CivilDate.shortLabel(next)}"
        }
        val first = firstNight(s.weekday, today)
        val starts = listOf(first, first + 7).map { DateNightStart(it, CivilDate.shortLabel(it), isNight(s, it)) }
        return DateNightView(true, s.weekday, s.startMin, "${everyLine(s)} · $nextPart", rows, starts, nudge(s, today, minuteOfDay))
    }

    /** Today's header on a kept date night, from noon until the evening is over; null otherwise. */
    fun todayLine(s: DateNightSetting?, today: Long, minuteOfDay: Int): DateNightLine? {
        if (!kept(s, today)) return null
        val start = s!!.startMin
        if (minuteOfDay < TODAY_FROM_MIN || minuteOfDay >= minOf(start + LENGTH_MIN, LocalClock.MINUTES_PER_DAY)) return null
        val hhmm = LocalClock.formatMinute(start)
        return if (minuteOfDay < start) DateNightLine("Date night tonight from $hhmm", "Date night is tonight from $hhmm. Nothing is planned over it.")
        else DateNightLine("Date night tonight", "It's date night. Nothing is planned over it.")
    }

    /** The line after a skip or keep: "Skipped Fri 23 Oct · the evening is free to plan", "Fri 23 Oct is kept clear again". */
    fun skipLine(day: Long, today: Long, skip: Boolean): String {
        val label = dayLabel(day, today)
        return if (skip) "Skipped ${if (day == today) "tonight" else label} · the evening is free to plan"
        else "${if (day == today) "Tonight" else label} is kept clear again"
    }
}

/** The date-night setting on a replica. Every write is an op: offline-first, synced. */
class DateNight(
    private val replica: Replica,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    fun setting(): DateNightSetting? = DateNightRules.read(replica.entity(EntityTypes.CONTEXT_MODE, DateNightRules.ENTITY_ID)?.fields)

    private fun today(): Long = calendar.epochDayOf(nowMs())

    /**
     * Sets the evening: [weekday] (ISO 1..7), [startMin] and the first night [firstDay] (which must fall on that
     * weekday, from today on). Returns false (nothing written) when they don't make sense or nothing changes.
     */
    fun set(weekday: Int, startMin: Int, firstDay: Long): Boolean {
        if (weekday !in 1..7 || startMin !in 0 until LocalClock.MINUTES_PER_DAY) return false
        if (CivilDate.isoDayOfWeek(firstDay) != weekday || firstDay < today()) return false
        val old = setting()
        val sameNights = old != null && old.weekday == weekday && DateNightRules.isNight(old, firstDay)
        if (sameNights && old!!.startMin == startMin) return false
        val fields = mutableMapOf<String, FieldValue>(
            DateNightFields.WEEKDAY to weekday.toLong().fv(),
            DateNightFields.START to startMin.toLong().fv(),
        )
        if (!sameNights) {
            fields[DateNightFields.ANCHOR] = firstDay.fv()
            // A new rhythm starts with nothing skipped or booked.
            if (old?.skipped?.isNotEmpty() == true) fields[DateNightFields.SKIPPED] = "".fv()
            if (old?.booked?.isNotEmpty() == true) fields[DateNightFields.BOOKED] = "".fv()
        }
        replica.commitLocal(EntityTypes.CONTEXT_MODE, DateNightRules.ENTITY_ID, fields)
        return true
    }

    /** Turns date night off (the evenings are free to plan); the time is kept for turning it on again. */
    fun off(): Boolean {
        setting()?.takeIf { it.on } ?: return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, DateNightRules.ENTITY_ID, mapOf(DateNightFields.WEEKDAY to 0L.fv()))
        return true
    }

    /** Skips one coming date night (its evening is free to plan), or keeps it again. False when nothing changes. */
    fun skip(day: Long, skip: Boolean): Boolean {
        val s = setting() ?: return false
        val today = today()
        if (!DateNightRules.isNight(s, day) || day < today) return false
        if ((day in s.skipped) == skip) return false
        // Nights already gone are dropped as the list is written.
        val kept = s.skipped.filter { it >= today }.toSet()
        val next = if (skip) kept + day else kept - day
        replica.commitLocal(EntityTypes.CONTEXT_MODE, DateNightRules.ENTITY_ID, mapOf(DateNightFields.SKIPPED to DateNightRules.encodeDays(next).fv()))
        return true
    }

    /**
     * Marks a coming kept night as booked (somewhere booked, cover arranged: the nudge leaves both apps), or takes that
     * back. False when nothing changes.
     */
    fun book(day: Long, booked: Boolean): Boolean {
        val s = setting() ?: return false
        val today = today()
        if (!DateNightRules.isNight(s, day) || day < today) return false
        if ((day in s.booked) == booked) return false
        val kept = s.booked.filter { it >= today }.toSet()
        val next = if (booked) kept + day else kept - day
        replica.commitLocal(EntityTypes.CONTEXT_MODE, DateNightRules.ENTITY_ID, mapOf(DateNightFields.BOOKED to DateNightRules.encodeDays(next).fv()))
        return true
    }
}
