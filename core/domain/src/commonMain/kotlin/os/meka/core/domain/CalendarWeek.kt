package os.meka.core.domain

import kotlin.math.roundToInt

/**
 * Calendar → **Week**, Option A (approved by Meka 2026-11-10 07:38: "Yes square cornered is better"; mockups
 * `docs/design/calendar-week/WeekOpen.dc.html` and `WeekFolded.dc.html`), non-AI and pure: a calm time grid as Calendar's
 * first view, beside Board and Agenda. Hours 08:00 to 21:00 at [HOUR_DP] per hour ([FOLDED_HOUR_DP] folded); each
 * timed event a square-cornered block with its calendar's colour on a 2 dp left edge, its title in the regular weight
 * over its time; one under [SHORT_MIN] minutes is a single line; the commute stays off the grid; work hours are a plain
 * faint band; an event that clashes with work ([WorkClashRules]) carries a small amber dot after its title and a thin
 * amber outline. The strip above the grid holds the day's all-day events and its open tasks as square chips (tasks
 * with a teal edge), and anything wholly before 08:00 or after 21:00 as a chip with its time. Today's date sits in the
 * gold pill and the gold now line crosses today's column. "Booked this week 5.8 h" counts the week's timed events.
 *
 * The open Fold and the Mac show 5 days (Monday to Friday) or 7, a week at a time; the folded phone shows 3 days from the
 * chosen one under the week's strip of seven, and a swipe moves three days.
 *
 * Titles are untrusted content (ADR-006): only ever shown as text.
 */
enum class CalendarMode(val id: String, val label: String) {
    WEEK("week", "Week"),
    BOARD("board", "Board"),
    AGENDA("agenda", "Agenda"),
    ;

    companion object {
        /** The stored choice; nothing (or anything unknown) is Week, Calendar's first view. */
        fun of(id: String?): CalendarMode = entries.firstOrNull { it.id == id } ?: WEEK
    }
}

enum class WeekChipKind { TASK, ALL_DAY, OUTSIDE }

/** One square chip in a day's strip above the grid: a task, an all-day event, or an event outside the grid's hours. */
data class WeekChip(
    val key: String,
    /** "Cancel a subscription" · "Half term" · "06:30 Flight" */
    val text: String,
    val kind: WeekChipKind,
    /** The calendar's tone for an event chip ([CalendarTones]); 0 for a task (teal). */
    val tone: Int,
    val event: CalendarEvent? = null,
    val taskId: String? = null,
    val spoken: String,
)

/** One event block on the grid. */
data class WeekBlock(
    val key: String,
    val title: String,
    /** "16:55–17:55" */
    val time: String,
    val startMinute: Int,
    val endMinute: Int,
    val tone: Int,
    val event: CalendarEvent,
    /** It clashes with work: the amber dot and outline. */
    val clash: Boolean,
    val column: Int = 0,
    val columns: Int = 1,
) {
    /**
     * Too short for the title over its time at [hourDp] per hour: the title and start time on one line. (Screenshot
     * check 2026-11-10: a 30 min Dentist and a 45 min call drew two lines in 18 and 28 dp and lost both to the clip.)
     */
    fun oneLine(hourDp: Float): Boolean = heightDp(hourDp) < CalendarWeekRules.TWO_LINE_DP

    /** "Training, 16:55 to 17:55, clashes with work" */
    val spoken: String get() = "$title, " + time.replace("–", " to ") + if (clash) ", clashes with work" else ""

    /** Its top on the grid in dp at [hourDp] per hour (clamped to the grid). */
    fun topDp(hourDp: Float): Float = CalendarWeekRules.minuteDp(startMinute, hourDp)

    /** Its height in dp, never under [CalendarWeekRules.MIN_BLOCK_DP], less a 2 dp gap below it. */
    fun heightDp(hourDp: Float): Float =
        maxOf(CalendarWeekRules.MIN_BLOCK_DP, CalendarWeekRules.minuteDp(endMinute, hourDp) - topDp(hourDp) - 2f)
}

/** One day's column. */
data class WeekGridDay(
    val epochDay: Long,
    /** "Mon" */
    val weekday: String,
    /** "9" */
    val number: String,
    val isToday: Boolean,
    val blocks: List<WeekBlock>,
    val chips: List<WeekChip>,
    /** The faint work band, in minutes of the day (clamped to the grid); null on a day off. */
    val workStartMinute: Int?,
    val workEndMinute: Int?,
    /** Minutes past midnight now, on today inside the grid's hours; null otherwise. */
    val nowMinute: Int?,
) {
    /** How full the day is, for the folded week strip's load bar: its timed events plus its strip's chips. */
    val load: Int get() = blocks.size + chips.size

    /** The load bar's width under the day's number in the folded strip (0 on an empty day). */
    val loadBarDp: Float get() = CalendarWeekRules.loadBarDp(load)

    /** Screen readers: "Monday 9 November, today, 2 events, 1 task". */
    val spoken: String get() {
        val tasks = chips.count { it.kind == WeekChipKind.TASK }
        val events = blocks.size + chips.count { it.kind != WeekChipKind.TASK }
        return listOfNotNull(
            CivilDate.longLabel(epochDay),
            "today".takeIf { isToday },
            when (events) { 0 -> "no events"; 1 -> "1 event"; else -> "$events events" },
            when (tasks) { 0 -> null; 1 -> "1 task"; else -> "$tasks tasks" },
        ).joinToString(", ")
    }
}

/** Where a held event would land: [days] and [minutes] from where it is, on [day] at [startMinute]. */
data class WeekDrop(
    val days: Int,
    val minutes: Int,
    val day: Long,
    val startMinute: Int,
    /** "Move to Thu 12 Nov · 15:30" */
    val hint: String,
)

/** The grid for a run of days. */
data class WeekGrid(
    val startEpochDay: Long,
    /** "November 2026" (open) */
    val title: String,
    /** "November" (folded) */
    val month: String,
    /** "Week 46" */
    val weekLabel: String,
    /** The days in view. */
    val days: List<WeekGridDay>,
    /** "5.8 h": the week's timed events (Monday to Sunday), commutes and all-day events aside. */
    val booked: String,
) {
    /** "Booked this week 5.8 h" */
    val bookedLine: String get() = "${CalendarWeekRules.BOOKED} $booked"

    /** Folded: "Week 46 · booked 5.8 h" */
    val foldedLine: String get() = "$weekLabel · booked $booked"
}

object CalendarWeekRules {
    const val FIRST_HOUR = 8
    /** The grid ends at 21:00: the last row is 20:00. */
    const val END_HOUR = 21
    const val HOUR_DP = 40f
    const val FOLDED_HOUR_DP = 38f
    /** The hour labels' column: 56 open, 40 folded. */
    const val LABEL_DP = 56f
    const val FOLDED_LABEL_DP = 40f
    const val SHORT_MIN = 30
    /** The height two lines need: an 11 sp title (13 sp line) over a 10 sp time (12 sp line) with 4 dp above and below. */
    const val TWO_LINE_DP = 33f
    /** A one-line block narrower than this (the folded three days, or overlaps side by side) shows only its title. */
    const val ONE_LINE_TIME_DP = 110f

    /** Whether a one-line block [widthDp] wide has room for its start time after the title (title first, always). */
    fun oneLineShowsTime(widthDp: Float): Boolean = widthDp >= ONE_LINE_TIME_DP
    const val MIN_BLOCK_DP = 18f
    /** The 2 dp coloured left edge on every block and chip. */
    const val EDGE_DP = 2f
    /** Square corners (Meka 2026-11-10: "square cornered is better"). */
    const val CORNER_DP = 0f
    const val MAX_CHIPS = 3
    /** The folded strip's load bar: [LOAD_STEP_DP] a thing, at most [LOAD_MAX_DP], [LOAD_HEIGHT_DP] tall (the mockup's bars). */
    const val LOAD_STEP_DP = 5f
    const val LOAD_MAX_DP = 14f
    const val LOAD_HEIGHT_DP = 3f

    /** 0 → no bar, 1 → 5, 2 → 10, 3 or more → 14. */
    fun loadBarDp(load: Int): Float = minOf(LOAD_MAX_DP, load.coerceAtLeast(0) * LOAD_STEP_DP)
    const val BOOKED = "Booked this week"
    const val FOLDED_DAYS = 3
    const val DEFAULT_OPEN_DAYS = 5
    val OPEN_DAYS = listOf(5, 7)

    /** "5 days" · "7 days" */
    fun daysLabel(n: Int): String = "$n days"

    /** Minute [minute] of the day as a top in dp on the grid at [hourDp] per hour, clamped to the grid. */
    fun minuteDp(minute: Int, hourDp: Float): Float =
        (minute.coerceIn(FIRST_HOUR * 60, END_HOUR * 60) - FIRST_HOUR * 60) * hourDp / 60f

    /** The grid's height in dp. */
    fun heightDp(hourDp: Float): Float = (END_HOUR - FIRST_HOUR) * hourDp

    /** The hour labels down the side: "08:00" … "20:00". */
    val hours: List<String> get() = (FIRST_HOUR until END_HOUR).map { LocalClock.formatMinute(it * 60) }

    /** The folded grid's narrow 40 dp column: "08" … "20", as the folded mockup. */
    val foldedHours: List<String> get() = (FIRST_HOUR until END_HOUR).map { it.toString().padStart(2, '0') }

    /** An hour label's top: centred on its line (7 dp up, as the mockups), the first one kept inside the grid. */
    fun hourLabelTopDp(index: Int, hourDp: Float): Float = maxOf(0f, index * hourDp - 7f)

    /** The all-day strip's label: "all day" open; nothing in the folded 40 dp column (the folded mockup leaves it blank). */
    fun allDayLabel(folded: Boolean): String = if (folded) "" else "all day"

    /** The Monday of the week holding [epochDay]. */
    fun monday(epochDay: Long): Long = epochDay - (CivilDate.isoDayOfWeek(epochDay) - 1)

    /**
     * Where the grid starts for [count] days showing [anchor]: 5 or 7 days start on its week's Monday; folded (3 days)
     * the anchor itself.
     */
    fun start(anchor: Long, count: Int): Long = if (count >= DEFAULT_OPEN_DAYS) monday(anchor) else anchor

    /** One step back or on: a week for 5 or 7 days, three days folded. */
    fun step(start: Long, count: Int, delta: Int): Long = start + delta * (if (count >= DEFAULT_OPEN_DAYS) 7 else count)

    /** The open Fold's (and the Mac's) day count: 5 unless he chose 7. */
    fun openDays(stored: Int?): Int = if (stored == 7) 7 else DEFAULT_OPEN_DAYS

    /**
     * The grid of [count] days from [start]. [events] are the calendar's (duplicates folded, hidden calendars out),
     * [board] gives each day's open tasks, [work] his hours (the band and clashes), [toneOf] each event's colour.
     */
    fun build(
        start: Long,
        count: Int,
        events: List<CalendarEvent>,
        board: WeekBoard?,
        work: WorkHours?,
        nowMs: Long,
        calendar: LocalCalendar,
        toneOf: (CalendarEvent) -> Int = { 1 },
    ): WeekGrid {
        val today = calendar.epochDayOf(nowMs)
        val days = (0 until count).map { i -> day(start + i, events, board, work, today, nowMs, calendar, toneOf) }
        val monday = monday(start)
        val weekMinutes = (monday until monday + 7).sumOf { d -> timedMinutes(d, events, calendar) }
        val ymd = CivilDate.fromEpochDay(start)
        val month = CivilDate.MONTH_LONG[ymd.month - 1]
        return WeekGrid(
            startEpochDay = start,
            title = "$month ${ymd.year}",
            month = month,
            weekLabel = "Week ${BoardMatchRules.isoWeek(start)}",
            days = days,
            booked = hoursLabel(weekMinutes),
        )
    }

    /** "5.8 h" · "0 h" · "12 h" */
    fun hoursLabel(minutes: Int): String {
        val tenths = (minutes * 10 + 30) / 60
        return if (tenths % 10 == 0) "${tenths / 10} h" else "${tenths / 10}.${tenths % 10} h"
    }

    private fun timedMinutes(d: Long, events: List<CalendarEvent>, calendar: LocalCalendar): Int {
        val w = CalendarAgenda.window(d, calendar)
        return events.sumOf { e ->
            if (!e.overlaps(w) || e.allDay || (e.startAtMs <= w.startMs && e.endAtMs >= w.endMs)) 0
            else ((minOf(e.endAtMs, w.endMs) - maxOf(e.startAtMs, w.startMs)) / 60_000L).toInt().coerceAtLeast(0)
        }
    }

    private fun day(
        d: Long, events: List<CalendarEvent>, board: WeekBoard?, work: WorkHours?, today: Long, nowMs: Long,
        calendar: LocalCalendar, toneOf: (CalendarEvent) -> Int,
    ): WeekGridDay {
        val w = CalendarAgenda.window(d, calendar)
        val dayMin = 24 * 60
        val workBlocks = work?.blocks(d, calendar).orEmpty()
        val commute = work?.let { CommuteRules.bands(it, d, calendar) }.orEmpty()
        val raw = mutableListOf<DayBlock>()
        val byKey = mutableMapOf<String, Pair<CalendarEvent, Boolean>>()
        val allDay = mutableListOf<WeekChip>()
        val outside = mutableListOf<Pair<Int, WeekChip>>()
        for (e in events.sortedWith(compareBy({ it.startAtMs }, { it.id }))) {
            if (!e.overlaps(w)) continue
            val tone = toneOf(e)
            if (e.allDay || (e.startAtMs <= w.startMs && e.endAtMs >= w.endMs)) {
                allDay += WeekChip("a:${e.id}", e.title, WeekChipKind.ALL_DAY, tone, event = e, spoken = "${e.title}, all day")
                continue
            }
            val start = if (e.startAtMs <= w.startMs) 0 else calendar.minuteOfDay(e.startAtMs)
            val end = if (e.endAtMs >= w.endMs) dayMin else calendar.minuteOfDay(e.endAtMs)
            if (end <= start) continue
            if (end <= FIRST_HOUR * 60 || start >= END_HOUR * 60) {
                val at = LocalClock.formatMinute(start)
                outside += start to WeekChip("o:${e.id}", "$at ${e.title}", WeekChipKind.OUTSIDE, tone, event = e, spoken = "${e.title}, at $at")
                continue
            }
            val key = "e:${e.id}"
            byKey[key] = e to (WorkClashRules.clash(e, workBlocks, calendar, commute = commute) != null)
            raw += DayBlock(key, e.title, start, end, DayBlockKind.EVENT, event = e, tone = tone)
        }
        val blocks = BoardTimelineRules.columns(raw).map { b ->
            val (e, clash) = byKey.getValue(b.key)
            WeekBlock(b.key, b.title, b.label, b.startMinute, b.endMinute, b.tone, e, clash, b.column, b.columns)
        }
        val tasks = board?.day(d)?.openCards.orEmpty().map { c ->
            val text = listOfNotNull(c.time, c.title).joinToString(" ")
            WeekChip("t:${c.id}", text, WeekChipKind.TASK, 0, taskId = c.id, spoken = "Task ${c.title}" + (c.time?.let { ", at $it" } ?: ""))
        }
        val chips = (allDay + outside.sortedBy { it.first }.map { it.second } + tasks)
        val band = workBlocks.lastOrNull { calendar.epochDayOf(it.startMs) == d }
            ?.let { calendar.minuteOfDay(it.startMs) to (if (it.endMs >= w.endMs) dayMin else calendar.minuteOfDay(it.endMs)) }
            ?.takeIf { (s, e) -> e > FIRST_HOUR * 60 && s < END_HOUR * 60 && e > s }
        val now = if (d == today) calendar.minuteOfDay(nowMs).takeIf { it >= FIRST_HOUR * 60 && it < END_HOUR * 60 } else null
        return WeekGridDay(
            epochDay = d,
            weekday = LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(d) - 1],
            number = CivilDate.fromEpochDay(d).day.toString(),
            isToday = d == today,
            blocks = blocks,
            chips = chips,
            workStartMinute = band?.first?.coerceAtLeast(FIRST_HOUR * 60),
            workEndMinute = band?.second?.coerceAtMost(END_HOUR * 60),
            nowMinute = now,
        )
    }

    /** A held block snaps to quarter hours as it is dragged. */
    const val SNAP_MIN = 15

    /**
     * Hold an event and drag it (slice 3): the block let go [dxDp] across and [dyDp] down from where it sat, in the
     * day at [dayIndex] of the [count] days from [startEpochDay], with columns [columnDp] wide at [hourDp] an hour.
     * Whole columns move it a day (only onto a day in view), the rest a quarter hour at a time, kept on the grid (it
     * may start no later than 20:45). Null when it lands where it was. [today] words the hint
     * ("Move to Thu 12 Nov · 15:30"). The move itself goes through `MekaCore.moveEventBy` (EventMoveRules: its own
     * event, no guests, not started; five seconds' Undo).
     */
    fun drop(
        block: WeekBlock, dayIndex: Int, startEpochDay: Long, count: Int, dxDp: Float, dyDp: Float, columnDp: Float,
        hourDp: Float, today: Long,
    ): WeekDrop? = dropAt(block.startMinute, dayIndex, startEpochDay, count, dxDp, dyDp, columnDp, hourDp, today)

    /** [drop] for a block starting at [startMinute] of its day. */
    fun dropAt(
        startMinute: Int, dayIndex: Int, startEpochDay: Long, count: Int, dxDp: Float, dyDp: Float, columnDp: Float,
        hourDp: Float, today: Long,
    ): WeekDrop? {
        if (columnDp <= 0f || hourDp <= 0f || count <= 0) return null
        val days = (dxDp / columnDp).roundToInt().coerceIn(-dayIndex, (count - 1 - dayIndex).coerceAtLeast(-dayIndex))
        val snapped = (dyDp / hourDp * 60f / SNAP_MIN).roundToInt() * SNAP_MIN
        val lowest = minOf(startMinute, FIRST_HOUR * 60)
        val highest = maxOf(startMinute, END_HOUR * 60 - SNAP_MIN)
        val start = if (snapped == 0) startMinute else (startMinute + snapped).coerceIn(lowest, highest)
        val minutes = start - startMinute
        if (days == 0 && minutes == 0) return null
        val day = startEpochDay + dayIndex + days
        return WeekDrop(days, minutes, day, start, "Move to " + TaskWhenRules.label(day, start, today))
    }

    /** Where a held block shows while dragged: [WeekDrop.days] columns across and [WeekDrop.minutes] down, in dp. */
    fun dropOffsetDp(drop: WeekDrop?, columnDp: Float, hourDp: Float): Pair<Float, Float> =
        if (drop == null) 0f to 0f else drop.days * columnDp to drop.minutes * hourDp / 60f

    /** The chips a day's strip shows: at most [MAX_CHIPS], the last one "+2 more" when there are more. */
    fun shownChips(chips: List<WeekChip>): List<WeekChip> =
        if (chips.size <= MAX_CHIPS) chips
        else chips.take(MAX_CHIPS - 1) + WeekChip("more", "+${chips.size - (MAX_CHIPS - 1)} more", WeekChipKind.TASK, 0, spoken = "${chips.size - (MAX_CHIPS - 1)} more")
}
