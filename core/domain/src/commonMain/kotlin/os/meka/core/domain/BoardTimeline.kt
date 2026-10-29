package os.meka.core.domain

/** What a block on the day's timeline is ([BoardTimelineRules]): drawn blue-ish in its calendar's tone, gold, or orange. */
enum class DayBlockKind { EVENT, TASK, COMMUTE }

/**
 * One block on the Week board's day timeline (Option B, slice 5): [startMinute]–[endMinute] past local midnight, clipped
 * to the day. Blocks that overlap share the width: this one is [column] of [columns].
 */
data class DayBlock(
    val key: String,
    val title: String,
    val startMinute: Int,
    val endMinute: Int,
    val kind: DayBlockKind,
    /** A task ticked off (it fades, like its card). */
    val done: Boolean = false,
    /** The task's id, for a TASK block; null otherwise. */
    val taskId: String? = null,
    /** The event, for an EVENT block (a tap opens it); null otherwise. */
    val event: CalendarEvent? = null,
    /** The calendar's tone for an EVENT block ([CalendarTones]); 0 otherwise. */
    val tone: Int = 0,
    val column: Int = 0,
    val columns: Int = 1,
) {
    val minutes: Int get() = endMinute - startMinute

    /** "09:30–10:00" */
    val label: String get() = "${LocalClock.formatMinute(startMinute)}–${LocalClock.formatMinute(endMinute)}"

    /** A short block draws its title on one line with less padding (under [BoardTimelineRules.ONE_LINE_MIN]). */
    val oneLine: Boolean get() = minutes < BoardTimelineRules.ONE_LINE_MIN

    /** Screen readers: "AI stand-up, 09:30 to 10:00" · "Task Send the quote, 12:30 to 13:15, done" · "Commute home, …". */
    val spoken: String get() {
        val span = "${LocalClock.formatMinute(startMinute)} to ${LocalClock.formatMinute(endMinute)}"
        return when (kind) {
            DayBlockKind.EVENT -> "$title, $span"
            DayBlockKind.TASK -> "Task $title, $span" + if (done) ", done" else ""
            DayBlockKind.COMMUTE -> "$title, $span"
        }
    }

    /**
     * Its top in the timeline, in dp, below the first hour [firstHour] (the alignment pass (7): after
     * [BoardTimelineRules.TOP_INSET_DP], so the first hour's line sits at the panel's top with its label whole).
     */
    fun topDp(firstHour: Int): Float = BoardTimelineRules.minuteDp(startMinute, firstHour)

    /** Its height in dp, never under [BoardTimelineRules.MIN_BLOCK_DP] so a ten-minute task can still be read. */
    val heightDp: Float get() = maxOf(minutes * BoardTimelineRules.HOUR_DP / 60f, BoardTimelineRules.MIN_BLOCK_DP)
}

/**
 * A day as an hour-by-hour timeline (Option B, slice 5, the approved mockup's "Today" panel): the hour rows from
 * [firstHour] up to [endHour], the day's timed events, the tasks planned at a time (for their estimate) and the commute
 * as blocks, its all-day events as a line above, and a gold line at [nowMinute] on today.
 */
data class BoardTimeline(
    val epochDay: Long,
    /** "Today" · "Tomorrow" · "Saturday" */
    val title: String,
    /** "Mon 27 Oct" */
    val dateLabel: String,
    val firstHour: Int,
    /** The hour the last row ends at (exclusive): rows are firstHour until endHour. */
    val endHour: Int,
    val blocks: List<DayBlock>,
    /** All-day events' titles ("Half term"). */
    val allDay: List<String>,
    /** Minutes past midnight now, on today only; null on other days. */
    val nowMinute: Int?,
) {
    /** The hour labels down the side: "07" … "22". */
    val hours: List<String> get() = (firstHour until endHour).map { it.toString().padStart(2, '0') }

    /** The whole timeline's height in dp: the rows and the inset above and below them (the alignment pass (7)). */
    val heightDp: Float get() = (endHour - firstHour) * BoardTimelineRules.HOUR_DP + 2 * BoardTimelineRules.TOP_INSET_DP

    /** The hairline of the [index]th hour row (0 = [firstHour]), in dp: its label is centred on it. */
    fun hourLineDp(index: Int): Float = BoardTimelineRules.TOP_INSET_DP + index * BoardTimelineRules.HOUR_DP

    /**
     * The gold now line's top in dp, or null when it isn't today or now is outside the rows. It is drawn behind the
     * blocks (the alignment pass (7)): an event under it keeps its title readable.
     */
    val nowTopDp: Float? get() = nowMinute?.takeIf { it >= firstHour * 60 && it < endHour * 60 }
        ?.let { BoardTimelineRules.minuteDp(it, firstHour) }

    /** The keys of the blocks the now line runs under (each drawn on an opaque face so the line can't cross its title). */
    val nowUnder: List<String> get() {
        val now = nowMinute?.takeIf { nowTopDp != null } ?: return emptyList()
        return blocks.filter { b -> now >= b.startMinute && BoardTimelineRules.minuteDp(now, firstHour) < b.topDp(firstHour) + b.heightDp }.map { it.key }
    }

    /**
     * Where the timeline opens, in hours scrolled down from [firstHour], when [visibleHours] rows fit (the alignment pass
     * (7)): at the top (the first hour, 07 or the first event) whenever now, or else the first block, sits inside the view
     * with an hour after it; otherwise an hour before it, so it is never hidden.
     */
    fun openingScrollHours(visibleHours: Float): Float {
        val focus = nowMinute ?: blocks.firstOrNull()?.startMinute ?: return 0f
        val focusHours = focus / 60f - firstHour
        if (focusHours + 1f <= visibleHours) return 0f
        return (focusHours - 1f).coerceIn(0f, ((endHour - firstHour) - visibleHours).coerceAtLeast(0f))
    }

    /** "Nothing timed yet" when there are no blocks (the rows still show, so a day looks open, not broken). */
    val emptyLine: String? get() = if (blocks.isEmpty()) BoardTimelineRules.EMPTY else null

    /** "All day: Half term" or null. */
    val allDayLine: String? get() = if (allDay.isEmpty()) null else "All day: " + allDay.joinToString(" · ")

    /** Screen readers, for the panel's heading: "Today, Mon 27 Oct, timeline, 4 things at a time". */
    val spoken: String get() {
        val n = blocks.size
        val count = when (n) { 0 -> "nothing at a time yet"; 1 -> "1 thing at a time"; else -> "$n things at a time" }
        return listOfNotNull(title, dateLabel.takeIf { it != title }, "timeline", count, allDayLine).joinToString(", ")
    }
}

/**
 * The Week board redesign, Option B (approved mockup, Meka 2026-10-24 08:30), slice 5: the day timeline. Unfolded on
 * the Fold and on the Mac a panel beside the week shows today (refinement 2), the hours 07–22 (wider when something
 * falls outside them), with what is fixed in time as blocks: his calendar's timed events in their calendar's tone, the
 * tasks he planned at a time in gold for their estimate (else Navilife's guess, else [DEFAULT_TASK_MIN]), and the
 * commute on an office day in orange. On the cover screen it is a tab beside the cards, for the day in view.
 *
 * Non-AI and pure: from the same events, tasks and work hours the board itself is built from. Overlapping blocks share
 * the width in columns. Nothing moves from here; it shows the day's shape.
 */
object BoardTimelineRules {
    const val FIRST_HOUR = 7
    /** Rows run up to 23:00, so the last row is "22". */
    const val END_HOUR = 23
    const val HOUR_DP = 44f
    /**
     * The alignment pass (Meka 2026-10-24 20:07, (7)): room above the first hour's line (and below the last) for half
     * an hour label, so "07" is centred on its line at the panel's top, not cut and not floating under it.
     */
    const val TOP_INSET_DP = 8f
    /**
     * The panel's heading starts this far down, the day columns' own top padding, so "Today" sits on the same line as
     * the days' dates beside it (the alignment pass (7)).
     */
    const val HEAD_TOP_DP = 8f

    /** Minute [minute] of the day as a top in dp below [firstHour]'s line, after [TOP_INSET_DP]. */
    fun minuteDp(minute: Int, firstHour: Int): Float = TOP_INSET_DP + (minute - firstHour * 60) * HOUR_DP / 60f
    const val MIN_BLOCK_DP = 18f
    const val ONE_LINE_MIN = 40
    const val DEFAULT_TASK_MIN = 30
    /** The panel's width beside the week: the mockup's 260 on a wide window, 200 on the unfolded Fold. */
    const val PANEL_DP = 260f
    const val NARROW_PANEL_DP = 200f
    /** Below this the panel is the narrow one. */
    const val WIDE_BOARD_DP = 1000f
    /** The panel opens by itself only while each weekday column keeps at least this much width (the density pass). */
    const val MIN_COLUMN_DP = BoardDenseRules.MIN_COLUMN_DP
    const val EMPTY = "Nothing timed yet"
    const val TAB_CARDS = "Cards"
    const val TAB_TIMELINE = "Timeline"
    const val SHOW = "Timeline"
    const val HIDE = "Hide timeline"
    const val COMMUTE_TO = "Commute to work"
    const val COMMUTE_HOME = "Commute home"

    /** The panel's width for a board [boardDp] wide. */
    fun panelDp(boardDp: Float): Float = if (boardDp >= WIDE_BOARD_DP) PANEL_DP else NARROW_PANEL_DP

    /**
     * Whether the panel starts open on a board [boardDp] wide (the gutters and gap [spareDp] aside): only when the five
     * weekday columns keep [MIN_COLUMN_DP] each beside it. He can open or close it either way.
     */
    fun opensByDefault(boardDp: Float, spareDp: Float): Boolean = BoardDenseRules.timelineByDefault(boardDp, spareDp)

    /**
     * The timeline of [day] (its cards are the board's): [events] are the board's (already merged and filtered), [work]
     * his hours for the commute, [toneOf] each event's calendar tone.
     */
    fun of(
        day: BoardDay,
        events: List<CalendarEvent>,
        work: WorkHours?,
        today: Long,
        nowMs: Long,
        calendar: LocalCalendar,
        toneOf: (CalendarEvent) -> Int = { 1 },
    ): BoardTimeline {
        val d = day.epochDay
        val w = CalendarAgenda.window(d, calendar)
        val dayMin = 24 * 60
        val raw = mutableListOf<DayBlock>()
        val allDay = mutableListOf<String>()
        for (e in events) {
            if (!e.overlaps(w)) continue
            if (e.allDay || (e.startAtMs <= w.startMs && e.endAtMs >= w.endMs)) { allDay += e.title; continue }
            val start = if (e.startAtMs <= w.startMs) 0 else calendar.minuteOfDay(e.startAtMs)
            val end = if (e.endAtMs >= w.endMs) dayMin else calendar.minuteOfDay(e.endAtMs)
            if (end <= start) continue
            raw += DayBlock("e:${e.id}", e.title, start, end, DayBlockKind.EVENT, event = e, tone = toneOf(e))
        }
        work?.let { hours ->
            CommuteRules.bands(hours, d, calendar).forEach { b ->
                val start = if (b.startMs <= w.startMs) 0 else calendar.minuteOfDay(b.startMs)
                val end = if (b.endMs >= w.endMs) dayMin else calendar.minuteOfDay(b.endMs)
                if (end > start) {
                    raw += DayBlock(if (b.toWork) "c:to" else "c:home", if (b.toWork) COMMUTE_TO else COMMUTE_HOME, start, end, DayBlockKind.COMMUTE)
                }
            }
        }
        for (c in day.cards) {
            val at = c.task.scheduledAtMs ?: continue
            if (calendar.epochDayOf(at) != d) continue
            val start = calendar.minuteOfDay(at)
            val length = (c.task.estimateMinutes?.takeIf { it > 0 } ?: c.suggestedMinutes?.takeIf { it > 0 } ?: DEFAULT_TASK_MIN)
            val end = minOf(dayMin, start + length)
            if (end <= start) continue
            raw += DayBlock("t:${c.id}", c.title, start, end, DayBlockKind.TASK, done = c.done, taskId = c.id)
        }
        val blocks = columns(raw)
        val first = minOf(FIRST_HOUR, blocks.minOfOrNull { it.startMinute / 60 } ?: FIRST_HOUR)
        val last = maxOf(END_HOUR, blocks.maxOfOrNull { (it.endMinute + 59) / 60 } ?: END_HOUR).coerceAtMost(24)
        return BoardTimeline(
            epochDay = d,
            title = when (d) {
                today -> "Today"
                today + 1 -> "Tomorrow"
                else -> CivilDate.DAY_LONG[CivilDate.isoDayOfWeek(d) - 1]
            },
            dateLabel = CivilDate.shortLabel(d),
            firstHour = first,
            endHour = last,
            blocks = blocks,
            allDay = allDay,
            nowMinute = if (d == today) calendar.minuteOfDay(nowMs) else null,
        )
    }

    /**
     * Sorts [blocks] by start (the longer first on a tie, then by key) and shares the width among those that overlap:
     * each run of blocks that overlap one another is one group, each block takes the first column free at its start,
     * and every block in the group knows how many columns the group needed.
     */
    fun columns(blocks: List<DayBlock>): List<DayBlock> {
        val sorted = blocks.sortedWith(compareBy<DayBlock>({ it.startMinute }, { -it.minutes }, { it.key }))
        val out = mutableListOf<DayBlock>()
        var group = mutableListOf<DayBlock>()
        var groupEnd = -1
        val ends = mutableListOf<Int>()
        fun flush() {
            val n = ends.size.coerceAtLeast(1)
            group.forEach { out += it.copy(columns = n) }
            group = mutableListOf()
            ends.clear()
        }
        for (b in sorted) {
            if (group.isNotEmpty() && b.startMinute >= groupEnd) flush()
            var col = ends.indexOfFirst { it <= b.startMinute }
            if (col < 0) { col = ends.size; ends += b.endMinute } else ends[col] = b.endMinute
            group += b.copy(column = col)
            groupEnd = maxOf(if (group.size == 1) b.endMinute else groupEnd, b.endMinute)
        }
        if (group.isNotEmpty()) flush()
        return out
    }
}
