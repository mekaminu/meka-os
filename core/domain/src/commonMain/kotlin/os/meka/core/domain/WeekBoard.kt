package os.meka.core.domain

/** What kind of day a Board column is (from his work days and trips): the small marker under its date. */
enum class BoardPlace {
    /** A work day at the office (the commute counts). */
    OFFICE,
    /** A work day from home. */
    HOME,
    /** Away on a trip. */
    AWAY,
}

/**
 * One task on the Week board. [time] is "14:30" for a task planned at a time; [line] the quiet line under it
 * ("Carried 2 days", "↻ Every weekday", "Done"); [carried] marks a task carried over from an earlier day (shown in the
 * accent); [done] a task ticked off that day (faded, at the foot, can't be moved).
 */
data class BoardCard(
    val task: Task,
    val title: String,
    val time: String?,
    val line: String?,
    val done: Boolean,
    val carried: Boolean,
    /** Navilife's guess at how long it takes ([TaskEstimateRules.suggest]) while it has no estimate; null otherwise. */
    val suggestedMinutes: Int? = null,
) {
    val id: String get() = task.id
    val canMove: Boolean get() = !done

    /** Its time estimate, "30m"; null for none (slice 3). */
    val estimate: String? get() = task.estimateMinutes?.takeIf { !done }?.let(TaskEstimateRules::label)

    /** The quiet line under the title: "14:30 · 30m · Carried 2 days". Empty when there's nothing to say. */
    val meta: String get() = listOfNotNull(time, estimate, line).joinToString(" · ")

    /** The card menu's "How long?" rows: 15m · 30m · 1h · 2h, one ticked or Navilife's guess marked. */
    val estimateChoices: List<EstimateChoice> get() = TaskEstimateRules.choices(task.estimateMinutes, suggestedMinutes)

    /** Screen readers: "Book MOT, 14:30, about 30m, carried 2 days". */
    val accessibilityLabel: String get() = listOfNotNull(title, time, estimate?.let { "about $it" }, line?.lowercase()).joinToString(", ")
}

/** A day's event as a faded chip above its cards: "09:30 Dentist", "All day Half term". */
data class BoardChip(val id: String, val time: String, val title: String, val tone: Int, val event: CalendarEvent)

/** One column of the Board: a day. */
data class BoardDay(
    val epochDay: Long,
    /** "Mon" */
    val weekday: String,
    /** "19" */
    val number: String,
    val isToday: Boolean,
    /** Before today: shows what was done that day; nothing can be dropped on it. */
    val isPast: Boolean,
    val place: BoardPlace?,
    /** "Office" · "Home" · "Away"; null on a day off. */
    val placeLabel: String?,
    /** The day's events, at most [WeekBoardRules.MAX_CHIPS]; [moreChips] counts the rest ("+2 more"). */
    val chips: List<BoardChip>,
    val moreChips: Int,
    /** Open tasks in his order, then what was done that day. */
    val cards: List<BoardCard>,
    /** "3 tasks" · "2 tasks · 1 done" · "Nothing planned" */
    val countLine: String,
    /** "Thursday 22 October, office day, 2 events, 3 tasks" */
    val accessibilityLabel: String,
    /** "3h planned · 4h free" (slice 3); null before today. */
    val capacity: BoardCapacity? = null,
) {
    /** "Today" · "Mon 19" */
    val title: String get() = if (isToday) "Today" else "$weekday $number"
    val canDrop: Boolean get() = !isPast
    /** "+2 more" when some events didn't fit; null otherwise. */
    val moreLabel: String? get() = if (moreChips > 0) "+$moreChips more" else null
    /** The open cards, in his order (what a move counts places among). */
    val openCards: List<BoardCard> get() = cards.filter { !it.done }
}

/** Seven columns, Monday to Sunday. */
data class BoardWeek(
    val startEpochDay: Long,
    /** "This week" · "Next week" · "2–8 Nov" */
    val title: String,
    /** "19–25 Oct" */
    val range: String,
    val days: List<BoardDay>,
)

data class WeekBoard(val todayEpochDay: Long, val weeks: List<BoardWeek>) {
    /** Every column, week after week. */
    val days: List<BoardDay> get() = weeks.flatMap { it.days }

    fun day(epochDay: Long): BoardDay? = weeks.firstOrNull { epochDay in it.startEpochDay until it.startEpochDay + 7 }
        ?.days?.firstOrNull { it.epochDay == epochDay }

    /** The week index holding [epochDay], or 0. */
    fun weekIndexOf(epochDay: Long): Int = weeks.indexOfFirst { epochDay in it.startEpochDay until it.startEpochDay + 7 }.coerceAtLeast(0)

    /** The day a card is on now, or null when it isn't on the board. */
    fun dayOf(taskId: String): BoardDay? = days.firstOrNull { d -> d.cards.any { it.id == taskId } }

    companion object {
        val EMPTY = WeekBoard(0, emptyList())
    }
}

/** A move worked out by [WeekBoardRules.move]: where the task goes and the new places of that day's cards. */
data class BoardMove(
    val taskId: String,
    val fromDay: Long,
    val toDay: Long,
    /** The time it keeps on the new day; null for none. */
    val minuteOfDay: Int?,
    /** The cards whose place changes (the moved one included), [WeekBoardRules.RANK_STEP] apart. */
    val ranks: Map<String, Long>,
) {
    val dayChanged: Boolean get() = fromDay != toDay
}

/** What Undo needs after a Board move: the task's timing before and after, and the old places of the cards it moved. */
data class BoardUndo(val taskId: String, val before: TaskTiming, val after: TaskTiming, val oldRanks: Map<String, Long?>)

/**
 * The Week board (Week board and roll-over, slice 2; Meka approved 2026-10-19 20:36), non-AI and pure: Calendar's Board
 * view, a column per day from this week's Monday for [WEEKS] weeks, each with that day's tasks as cards in his order.
 *
 * - A task's column is the day it is on ([TaskWhenRules.dayOf]): its planned day, else the day it shows from, else
 *   today, so a task with no day sits in Today's column and one carried over from an earlier day leads Today's column
 *   saying so ([RollOverRules.line]). Someday, waiting and dropped tasks aren't on the board; a task done in the
 *   board's days sits faded at the foot of the day it was done.
 * - Order: his order once he has moved cards in that day ([TaskFields.BOARD_RANK]); otherwise carried first (oldest
 *   first), then by time, then the rest as they were added. A card he hasn't placed follows the placed ones.
 * - Each column shows its events as faded chips (all day first, then by time; [MAX_CHIPS] then "+2 more") and an
 *   Office · Home · Away marker from his work days and trips.
 * - [move]: drag a card to another day (today or later, keeping its time) or up and down within a day; the day's open
 *   cards are re-placed [RANK_STEP] apart and only those whose place changed are written. Nothing moves into the past.
 */
object WeekBoardRules {
    const val WEEKS = 5
    const val MAX_CHIPS = 3
    const val RANK_STEP = 1_000L
    const val VIEW_AGENDA = "Agenda"
    const val VIEW_BOARD = "Board"
    const val EMPTY_DAY = "Nothing planned"
    const val DROP_HERE = "Drop here"

    fun build(
        tasks: List<Task>,
        events: List<CalendarEvent>,
        nowMs: Long,
        calendar: LocalCalendar,
        work: WorkHours? = null,
        hidden: Set<String> = emptySet(),
        toneOf: (CalendarEvent) -> Int = { if (it.isFixture) CalendarTones.FIXTURE else 1 },
        weeks: Int = WEEKS,
    ): WeekBoard {
        val today = calendar.epochDayOf(nowMs)
        val firstDay = today - (CivilDate.isoDayOfWeek(today) - 1)
        val lastDay = firstDay + 7L * weeks - 1
        val shown = DuplicateEvents.merge(events).filter { !DuplicateEvents.hiddenIn(it, hidden) }

        val sized = TaskEstimateRules.Sized.of(tasks)
        val byDay = HashMap<Long, MutableList<BoardCard>>()
        for (t in tasks) {
            val day = columnOf(t, today, calendar) ?: continue
            if (day !in firstDay..lastDay) continue
            byDay.getOrPut(day) { mutableListOf() } += card(t, today, calendar, sized)
        }

        val boardWeeks = (0 until weeks).map { w ->
            val ws = firstDay + 7L * w
            BoardWeek(
                startEpochDay = ws,
                title = when (w) {
                    0 -> "This week"
                    1 -> "Next week"
                    else -> CalendarAgenda.rangeLabel(ws, ws + 6)
                },
                range = CalendarAgenda.rangeLabel(ws, ws + 6),
                days = (ws until ws + 7).map { d -> day(d, today, nowMs, byDay[d].orEmpty(), shown, calendar, work, toneOf) },
            )
        }
        return WeekBoard(today, boardWeeks)
    }

    /** The day [t] sits on, or null when it isn't on the board (Someday, waiting, dropped, or done with no time). */
    fun columnOf(t: Task, today: Long, calendar: LocalCalendar): Long? = when (t.lifecycle) {
        Lifecycle.ACTIVE, Lifecycle.INBOX -> TaskWhenRules.dayOf(t, today, calendar)
        Lifecycle.DONE -> t.completedAtMs?.let(calendar::epochDayOf)
        else -> null
    }

    private fun card(t: Task, today: Long, calendar: LocalCalendar, sized: TaskEstimateRules.Sized): BoardCard {
        val done = t.lifecycle == Lifecycle.DONE
        val carriedDays = if (!done && t.scheduledAtMs != null && t.showsFromDay.let { it == null || it <= today }) {
            today - calendar.epochDayOf(t.scheduledAtMs)
        } else 0L
        val carried = carriedDays > 0
        val minute = if (done) null else TaskWhenRules.minuteOf(t, today, calendar)
        val line = when {
            done -> "Done"
            carried -> RollOverRules.line(t, carriedDays)
            else -> t.repeatLabel?.let { "↻ $it" }
        }
        return BoardCard(
            t, TaskTitleRules.display(t.title), minute?.let(LocalClock::formatMinute), line, done, carried,
            suggestedMinutes = if (done) null else TaskEstimateRules.suggest(t, sized),
        )
    }

    /** His order: placed cards by place; then carried (oldest first), timed by time, the rest as added. Done at the foot. */
    fun order(cards: List<BoardCard>): List<BoardCard> {
        val open = cards.filter { !it.done }.sortedWith(
            compareBy<BoardCard>({ it.task.boardRank ?: Long.MAX_VALUE })
                .thenBy { if (it.carried) 0 else 1 }
                .thenBy { if (it.carried) it.task.scheduledAtMs ?: 0L else 0L }
                .thenBy { it.time ?: "99:99" }
                .thenBy { it.task.createdAtMs }
                .thenBy { it.id },
        )
        val done = cards.filter { it.done }.sortedWith(compareBy<BoardCard>({ it.task.completedAtMs ?: 0L }).thenBy { it.id })
        return open + done
    }

    private fun day(
        d: Long, today: Long, nowMs: Long, cards: List<BoardCard>, events: List<CalendarEvent>, calendar: LocalCalendar, work: WorkHours?,
        toneOf: (CalendarEvent) -> Int,
    ): BoardDay {
        val w = CalendarAgenda.window(d, calendar)
        val dayEvents = events.filter { it.overlaps(w) }
            .sortedWith(compareBy<CalendarEvent>({ !it.allDay }, { maxOf(it.startAtMs, w.startMs) }, { it.title }))
        val chips = dayEvents.map { e ->
            val time = when {
                e.allDay || (e.startAtMs <= w.startMs && e.endAtMs >= w.endMs) -> "All day"
                e.startAtMs < w.startMs -> "Until ${LocalClock.formatMinute(calendar.minuteOfDay(e.endAtMs))}"
                else -> LocalClock.formatMinute(calendar.minuteOfDay(e.startAtMs))
            }
            BoardChip(e.id, time, e.title, toneOf(e), e)
        }
        val place = work?.let { placeOf(it, d) }
        val ordered = order(cards)
        val open = ordered.count { !it.done }
        val done = ordered.size - open
        val counts = buildList {
            if (open > 0) add(plural(open, "task"))
            if (done > 0) add("$done done")
        }
        val countLine = if (counts.isEmpty()) EMPTY_DAY else counts.joinToString(" · ")
        val capacity = TaskEstimateRules.capacity(d, today, nowMs, ordered.filter { !it.done }.map { it.task }, events, calendar, work)
        val spoken = buildList {
            add(CivilDate.longLabel(d))
            if (d == today) add("today")
            place?.let { add(placeLabel(it).lowercase() + " day") }
            if (dayEvents.isNotEmpty()) add(plural(dayEvents.size, "event"))
            add(countLine.lowercase())
            capacity?.let { add(it.spoken) }
        }.joinToString(", ")
        val ymd = CivilDate.fromEpochDay(d)
        return BoardDay(
            epochDay = d,
            weekday = LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(d) - 1],
            number = ymd.day.toString(),
            isToday = d == today,
            isPast = d < today,
            place = place,
            placeLabel = place?.let(::placeLabel),
            chips = chips.take(MAX_CHIPS),
            moreChips = (chips.size - MAX_CHIPS).coerceAtLeast(0),
            cards = ordered,
            countLine = countLine,
            accessibilityLabel = spoken,
            capacity = capacity,
        )
    }

    /** Away on a trip, else office or home on a work day; null on a day off. */
    fun placeOf(work: WorkHours, d: Long): BoardPlace? = when {
        d in work.away -> BoardPlace.AWAY
        !work.isWorkDay(d) -> null
        work.atOffice(d) -> BoardPlace.OFFICE
        else -> BoardPlace.HOME
    }

    fun placeLabel(p: BoardPlace): String = when (p) {
        BoardPlace.OFFICE -> "Office"
        BoardPlace.HOME -> "Home"
        BoardPlace.AWAY -> "Away"
    }

    /**
     * Moving [taskId] to [toDay] at place [toIndex] among that day's open cards (0 = top; past the end = last). Null
     * when it can't move (not on the board, done, a day before today or off the board) or nothing would change.
     */
    fun move(board: WeekBoard, taskId: String, toDay: Long, toIndex: Int, today: Long, calendar: LocalCalendar): BoardMove? {
        val from = board.dayOf(taskId) ?: return null
        val card = from.cards.first { it.id == taskId }
        if (!card.canMove || toDay < today) return null
        val target = board.day(toDay) ?: return null
        val others = target.openCards.filter { it.id != taskId }
        val at = toIndex.coerceIn(0, others.size)
        val order = others.map { it.id }.toMutableList().apply { add(at, taskId) }
        if (from.epochDay == toDay && order == target.openCards.map { it.id }) return null
        val current = (target.openCards + card).associate { it.id to it.task.boardRank }
        val ranks = LinkedHashMap<String, Long>()
        order.forEachIndexed { i, id ->
            val rank = (i + 1) * RANK_STEP
            if (current[id] != rank) ranks[id] = rank
        }
        val minute = TaskWhenRules.minuteOf(card.task, today, calendar)
        return BoardMove(taskId, from.epochDay, toDay, minute, ranks)
    }

    /** The undo bar: "Moved “Book MOT” to Thu 22 Oct" (or Today, Tomorrow); "Moved “Book MOT” up" within a day. */
    fun moveLine(title: String, move: BoardMove, today: Long, movedUp: Boolean): String {
        val t = TaskTitleRules.display(title)
        if (!move.dayChanged) return "Moved “$t” ${if (movedUp) "up" else "down"}"
        val day = when (move.toDay) {
            today -> "Today"
            today + 1 -> "Tomorrow"
            else -> CivilDate.shortLabel(move.toDay)
        }
        return "Moved “$t” to $day"
    }

    /** Keyboard and menu moves: one day earlier or later (never before today), or one place up or down. */
    fun nudge(board: WeekBoard, taskId: String, days: Int, places: Int, today: Long, calendar: LocalCalendar): BoardMove? {
        val from = board.dayOf(taskId) ?: return null
        val index = from.openCards.indexOfFirst { it.id == taskId }
        if (index < 0) return null
        if (days != 0) {
            val to = from.epochDay + days
            val target = board.day(to) ?: return null
            return move(board, taskId, to, target.openCards.size, today, calendar)
        }
        val to = index + places
        if (to < 0 || to >= from.openCards.size) return null
        return move(board, taskId, from.epochDay, to, today, calendar)
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
}
