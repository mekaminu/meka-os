package os.meka.core.domain

/** What lands on a day to make it stack up (Week board, item (10)). */
enum class PressureKind {
    /** An open task due that day. */
    DUE,
    /** A bill, renewal or club fee due that day (Lists → Renewals). */
    BILL,
    /** A task carried over from an earlier day, sitting on that day. */
    CARRIED,
    /** An event starting in the evening (18:00 or later). */
    LATE,
}

/** One thing on a stacking-up day: "Trip form", "Council tax", "Gym 19:00". */
data class PressureItem(val kind: PressureKind, val title: String, val taskId: String? = null, val evening: Boolean = false)

/** A bill or renewal due on a day, as the pressure warning needs it. */
data class PressureBill(val title: String, val dueDay: Long)

/**
 * One tap that eases a stacking-up day: move one of its tasks to a lighter day ("Move “Gym” to Fri"), or do one of its
 * due tasks today ("Do “Trip form” tonight"). [toDay] is where the task goes; nothing moves until he taps, with Undo.
 */
data class PressureFix(val taskId: String, val title: String, val toDay: Long, val label: String, val earlier: Boolean) {
    /** The undo bar's line after the tap: "Moved “Gym” to Fri" · "“Trip form” is on for today". */
    val doneLine: String get() = if (earlier) "“$title” is on for today" else "Moved “$title” ${label.substringAfter("” ")}"
}

/**
 * A day that is stacking up: three or more due things, bills, carried tasks or evening events on it ([evening] when
 * three or more of them are in the evening). [line] "Thursday is stacking up: Trip form, Council tax and Gym" for
 * Today and the brief; [short] the Board column's "Stacking up: …"; [spoken] for the hello and the spoken brief.
 */
data class DayPressure(
    val epochDay: Long,
    /** "Today" · "Tomorrow" · "Thursday" · "Thu 29 Oct" */
    val dayName: String,
    val evening: Boolean,
    val items: List<PressureItem>,
    val line: String,
    val short: String,
    val spoken: String,
    /** At most two one-tap suggestions, a move to a lighter day first. */
    val fixes: List<PressureFix>,
)

/**
 * Pressure warnings (Week board and roll-over, item (10); Meka 2026-10-20 19:56), non-AI and pure, on his own tasks,
 * calendar and bills.
 *
 * - **What counts on a day** from today on the Board: open tasks due that day (unless he placed them on an earlier day:
 *   given a time, a day or a place on the Board there), bills and renewals due that day (not subscriptions or
 *   warranties, which renew by themselves), tasks carried over sitting on it, and events starting from 18:00.
 * - **Stacking up** at [MIN_ITEMS] or more; "evening" when that many are in the evening (late events and tasks planned
 *   from 18:00), and then the line names only those.
 * - **Fixes**, at most two, each a Board move with Undo: move the day's lightest-to-lose task (carried first, then lowest
 *   priority, then the last in his order; never one due that day or repeating) to the lightest day of the next six that
 *   isn't stacking up and isn't past its due day; and, for a later day, do one of its due tasks today (when today isn't
 *   stacking up itself).
 * - [soonest]: the first stacking-up day from today within [SOON_DAYS], for Today's line, the brief and the hello.
 */
object PressureRules {
    const val MIN_ITEMS = 3
    const val EVENING_MIN = 18 * 60
    /** Today, tomorrow and the day after. */
    const val SOON_DAYS = 2
    const val LIGHTER_DAYS = 6
    /** "tonight" rather than "today" from this time. */
    const val TONIGHT_FROM_MIN = 17 * 60
    const val SHOWN = 3

    private val BILL_KINDS = ObligationKind.entries.toSet() - setOf(ObligationKind.SUBSCRIPTION, ObligationKind.WARRANTY)

    /** Renewals that count as due things, for [days]. */
    fun bills(items: List<RenewalItem>): List<PressureBill> =
        items.filter { it.kind in BILL_KINDS || it.forChild != null }.map { PressureBill(it.title.trim(), it.dueDay) }

    /** Each stacking-up day on [board] from today, by its epoch day. */
    fun days(
        board: WeekBoard,
        events: List<CalendarEvent>,
        bills: List<PressureBill>,
        nowMs: Long,
        calendar: LocalCalendar,
    ): Map<Long, DayPressure> {
        val today = calendar.epochDayOf(nowMs)
        val days = board.days.filter { it.epochDay >= today }
        if (days.isEmpty()) return emptyMap()
        val itemsByDay = days.associate { it.epochDay to items(board, it, events, bills, today, calendar) }
        val stacked = itemsByDay.filterValues { stacks(it) }.keys
        val minute = calendar.minuteOfDay(nowMs)
        return stacked.associateWith { d ->
            val all = itemsByDay.getValue(d)
            val evening = all.count { it.evening } >= MIN_ITEMS
            val named = if (evening) all.filter { it.evening } else all
            val name = dayName(d, today, evening)
            val titles = names(named)
            val fixes = listOfNotNull(
                moveFix(board, d, today, stacked, calendar),
                earlierFix(board, d, today, all, stacked, minute),
            )
            DayPressure(
                epochDay = d,
                dayName = name,
                evening = evening,
                items = all,
                line = "$name is stacking up: $titles",
                short = (if (evening) "Evening stacking up: " else "Stacking up: ") + titles,
                spoken = "$name is stacking up: ${TalkRules.speakable(titles).trimEnd('.')}.",
                fixes = fixes,
            )
        }
    }

    /** The first stacking-up day from today within [SOON_DAYS], or null. */
    fun soonest(pressure: Map<Long, DayPressure>, today: Long): DayPressure? =
        pressure.filterKeys { it in today..today + SOON_DAYS }.minByOrNull { it.key }?.value

    private fun stacks(items: List<PressureItem>): Boolean = items.size >= MIN_ITEMS

    /** What lands on [day]: due tasks, bills, carried tasks on it, evening events; each task once. */
    fun items(
        board: WeekBoard, day: BoardDay, events: List<CalendarEvent>, bills: List<PressureBill>, today: Long, calendar: LocalCalendar,
    ): List<PressureItem> {
        val d = day.epochDay
        val out = mutableListOf<PressureItem>()
        val seen = HashSet<String>()
        val open = board.weeks.flatMap { w -> w.days.flatMap { dd -> dd.openCards.map { dd.epochDay to it } } + w.lane.map { null to it } }
        for ((col, c) in open) {
            val due = c.task.dueAtMs?.let(calendar::epochDayOf) ?: continue
            if (due != d) continue
            if (col != null && col < d && placed(c.task)) continue
            if (seen.add(c.id)) out += PressureItem(PressureKind.DUE, c.title, c.id, evening = col == d && eveningCard(c, d, calendar))
        }
        for (b in bills) if (b.dueDay == d && b.title.isNotEmpty()) out += PressureItem(PressureKind.BILL, b.title)
        for (c in day.openCards) {
            if (c.carried && seen.add(c.id)) out += PressureItem(PressureKind.CARRIED, c.title, c.id, evening = eveningCard(c, d, calendar))
        }
        for (c in day.openCards) {
            if (c.id !in seen && eveningCard(c, d, calendar) && seen.add(c.id)) out += PressureItem(PressureKind.LATE, c.title, c.id, evening = true)
        }
        val w = CalendarAgenda.window(d, calendar)
        events.filter { !it.allDay && it.startAtMs >= w.startMs && it.startAtMs < w.endMs && calendar.minuteOfDay(it.startAtMs) >= EVENING_MIN }
            .sortedWith(compareBy<CalendarEvent>({ it.startAtMs }, { it.title }))
            .distinctBy { it.title.trim().lowercase() to it.startAtMs }
            .forEach { out += PressureItem(PressureKind.LATE, it.title.trim(), evening = true) }
        return out
    }

    /** An open task planned in the evening on its day (18:00 or later). */
    private fun eveningCard(c: BoardCard, day: Long, calendar: LocalCalendar): Boolean {
        val at = c.task.scheduledAtMs?.takeIf { calendar.epochDayOf(it) == day } ?: return false
        return calendar.minuteOfDay(at) >= EVENING_MIN
    }

    /** He placed it on its day himself: a time, a day it shows from, or a place on the Board. */
    private fun placed(t: Task): Boolean = t.scheduledAtMs != null || t.deferredToDay != null || t.boardRank != null

    /** "Trip form, Council tax and Gym" · "Trip form, Council tax, Gym and 2 more". */
    fun names(items: List<PressureItem>): String {
        val titles = items.map { TaskTitleRules.display(it.title) }
        val shown = titles.take(SHOWN)
        val more = titles.size - shown.size
        return when {
            more > 0 -> shown.joinToString(", ") + " and $more more"
            shown.size == 1 -> shown[0]
            else -> shown.dropLast(1).joinToString(", ") + " and " + shown.last()
        }
    }

    /** "Today" / "This evening" · "Tomorrow" / "Tomorrow evening" · "Thursday" / "Thursday evening" · "Thu 29 Oct". */
    fun dayName(d: Long, today: Long, evening: Boolean): String {
        val base = when {
            d == today -> if (evening) return "This evening" else "Today"
            d == today + 1 -> "Tomorrow"
            d < today + 7 -> CivilDate.DAY_LONG[CivilDate.isoDayOfWeek(d) - 1]
            else -> CivilDate.shortLabel(d)
        }
        return if (evening) "$base evening" else base
    }

    /** "Tomorrow" · "Fri" · "Fri 30 Oct" for a fix's label. */
    fun shortDay(d: Long, today: Long): String = when {
        d == today -> "today"
        d == today + 1 -> "tomorrow"
        d < today + 7 -> LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(d) - 1]
        else -> CivilDate.shortLabel(d)
    }

    /** Move the day's easiest task to lose to the lightest of the next days that isn't stacking up. */
    private fun moveFix(board: WeekBoard, d: Long, today: Long, stacked: Set<Long>, calendar: LocalCalendar): PressureFix? {
        val day = board.day(d) ?: return null
        val movable = day.openCards.withIndex().filter { (_, c) ->
            val due = c.task.dueAtMs?.let(calendar::epochDayOf)
            c.canMove && !c.task.isRepeating && (due == null || due > d)
        }
        val pick = movable.sortedWith(
            compareBy<IndexedValue<BoardCard>>({ if (it.value.carried) 0 else 1 }, { it.value.task.priority }, { -it.index }),
        ).firstOrNull()?.value ?: return null
        val due = pick.task.dueAtMs?.let(calendar::epochDayOf)
        val target = (d + 1..d + LIGHTER_DAYS)
            .filter { it >= today && it !in stacked && (due == null || it <= due) }
            .mapNotNull { board.day(it) }
            .minWithOrNull(compareBy<BoardDay>({ it.openCards.size }, { it.epochDay }))
            ?: return null
        return PressureFix(pick.id, pick.title, target.epochDay, "Move “${pick.title}” to ${shortDay(target.epochDay, today)}", earlier = false)
    }

    /** For a later day: do one of its due tasks today, when today isn't stacking up. */
    private fun earlierFix(board: WeekBoard, d: Long, today: Long, items: List<PressureItem>, stacked: Set<Long>, minute: Int): PressureFix? {
        if (d <= today || today in stacked || board.day(today) == null) return null
        val pick = items.asSequence()
            .filter { it.kind == PressureKind.DUE && it.taskId != null }
            .mapNotNull { board.find(it.taskId!!) }
            .firstOrNull { it.card.canMove && !it.card.task.isRepeating && it.source != BoardSource.SOMEDAY }
            ?: return null
        val word = if (minute >= TONIGHT_FROM_MIN) "tonight" else "today"
        return PressureFix(pick.card.id, pick.card.title, today, "Do “${pick.card.title}” $word", earlier = true)
    }

    /**
     * "Do it today" on a task already sitting in [day]'s column without a place of its own (a task due later with no
     * day sits in Today's): keeps the day's order and gives each open card its place, so the task is placed there for
     * good and stops counting on its due day. Null when it isn't in that column or is placed already.
     */
    fun claim(board: WeekBoard, taskId: String, day: Long): BoardMove? {
        val column = board.day(day) ?: return null
        val card = column.openCards.firstOrNull { it.id == taskId } ?: return null
        if (card.task.boardRank != null) return null
        val ranks = LinkedHashMap<String, Long>()
        column.openCards.forEachIndexed { i, c ->
            val rank = (i + 1) * WeekBoardRules.RANK_STEP
            if (c.task.boardRank != rank) ranks[c.id] = rank
        }
        return BoardMove(taskId, day, day, null, ranks, BoardSource.DAY)
    }

    /** Puts [pressure] on the board's days. */
    fun onBoard(board: WeekBoard, pressure: Map<Long, DayPressure>): WeekBoard {
        if (pressure.isEmpty()) return board
        return board.copy(weeks = board.weeks.map { w -> w.copy(days = w.days.map { d -> pressure[d.epochDay]?.let { d.copy(pressure = it, accessibilityLabel = d.accessibilityLabel + ", " + it.short.lowercase()) } ?: d }) })
    }
}
