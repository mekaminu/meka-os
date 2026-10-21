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
    /** The goal it is tagged to ([Task.goalId]), by title; null for none or a goal no longer there (slice 7). */
    val goalTitle: String? = null,
) {
    val id: String get() = task.id

    /** "◎ Run 5k" under a card tagged to a goal (slice 7); null for none. */
    val goalTag: String? get() = BoardGoalRules.tag(goalTitle)
    val canMove: Boolean get() = !done

    /** Where it came from, for the small colour dot (slice 5 by hand); null for what he typed himself. */
    val source: CardSource? get() = BoardSourceRules.of(task)

    /** Its time estimate, "30m"; null for none (slice 3). */
    val estimate: String? get() = task.estimateMinutes?.takeIf { !done }?.let(TaskEstimateRules::label)

    /** The quiet line under the title: "14:30 · 30m · Carried 2 days". Empty when there's nothing to say. */
    val meta: String get() = listOfNotNull(time, estimate, line).joinToString(" · ")

    /** The card menu's "How long?" rows: 15m · 30m · 1h · 2h, one ticked or Navilife's guess marked. */
    val estimateChoices: List<EstimateChoice> get() = TaskEstimateRules.choices(task.estimateMinutes, suggestedMinutes)

    /** Screen readers: "Book MOT, 14:30, about 30m, carried 2 days". */
    val accessibilityLabel: String
        get() = listOfNotNull(title, time, estimate?.let { "about $it" }, line?.lowercase(), goalTitle?.let { "for goal $it" }, source?.let(BoardSourceRules::spoken)).joinToString(", ")
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
    /** Stacking up (item (10), [PressureRules]): the column glows amber with its line and fixes; null otherwise. */
    val pressure: DayPressure? = null,
    /** Slice 9: the day's free stretches, its timeline's drop targets for blocking time (none before today). */
    val freeTimes: List<BoardFreeTime> = emptyList(),
) {
    /** "Today" · "Mon 19" */
    val title: String get() = if (isToday) "Today" else "$weekday $number"
    val canDrop: Boolean get() = !isPast
    /** "+2 more" when some events didn't fit; null otherwise. */
    val moreLabel: String? get() = if (moreChips > 0) "+$moreChips more" else null
    /** The open cards, in his order (what a move counts places among). */
    val openCards: List<BoardCard> get() = cards.filter { !it.done }
}

/** Where a card sits on the Board: a day's column, a week's lane (no day yet) or the Someday tray (slice 4). */
enum class BoardSource { DAY, WEEK, SOMEDAY }

/** Seven columns, Monday to Sunday, and the week's lane of tasks parked for the week with no day yet (slice 4). */
data class BoardWeek(
    val startEpochDay: Long,
    /** "This week" · "Next week" · "2–8 Nov" */
    val title: String,
    /** "19–25 Oct" */
    val range: String,
    val days: List<BoardDay>,
    /** Tasks parked in this week with no day, in his order; this week's also holds those left from earlier weeks. */
    val lane: List<BoardCard> = emptyList(),
    /** The week's goals across the top, at most three, each with how many of its tasks are done (slice 7). */
    val goals: List<BoardGoal> = emptyList(),
) {
    /** "+ Goal" is offered while the week has fewer than three goals. */
    val canAddGoal: Boolean get() = goals.size < BoardGoalRules.MAX

    /** Every card of the week: its days' and its lane's. */
    val allCards: List<BoardCard> get() = days.flatMap { it.cards } + lane

    /** "3 to place" · "Park tasks here for the week" */
    val laneLine: String get() = if (lane.isEmpty()) WeekBoardRules.LANE_EMPTY else "${lane.size} to place"
    /** Screen readers: "This week, no day yet, 3 tasks". */
    val laneAccessibilityLabel: String
        get() = "$title, no day yet, " + if (lane.isEmpty()) "empty" else if (lane.size == 1) "1 task" else "${lane.size} tasks"
}

data class WeekBoard(
    val todayEpochDay: Long,
    val weeks: List<BoardWeek>,
    /** The Someday tray (slice 4): his Someday tasks, newest first; drag one onto a day or a week to bring it back. */
    val someday: List<BoardCard> = emptyList(),
) {
    /** Every column, week after week. */
    val days: List<BoardDay> get() = weeks.flatMap { it.days }

    /** "Someday · 12" for the tray's folded header. */
    val somedayTitle: String get() = if (someday.isEmpty()) WeekBoardRules.SOMEDAY else "${WeekBoardRules.SOMEDAY} · ${someday.size}"

    /** Item (8): from Friday the header offers Review the week ([BoardReviewRules.offersReview]). */
    val offersReview: Boolean get() = todayEpochDay > 0 && BoardReviewRules.offersReview(todayEpochDay)

    /** The week starting [startEpochDay], or null. */
    fun week(startEpochDay: Long): BoardWeek? = weeks.firstOrNull { it.startEpochDay == startEpochDay }

    /** The week whose lane holds [taskId], or null. */
    fun laneOf(taskId: String): BoardWeek? = weeks.firstOrNull { w -> w.lane.any { it.id == taskId } }

    /** Where [taskId] is: its card, where it sits and that place's key (the day, the week's Monday, or 0 for Someday). */
    fun find(taskId: String): BoardSpot? {
        dayOf(taskId)?.let { d -> return BoardSpot(d.cards.first { it.id == taskId }, BoardSource.DAY, d.epochDay) }
        laneOf(taskId)?.let { w -> return BoardSpot(w.lane.first { it.id == taskId }, BoardSource.WEEK, w.startEpochDay) }
        someday.firstOrNull { it.id == taskId }?.let { return BoardSpot(it, BoardSource.SOMEDAY, 0L) }
        return null
    }

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

/** A card and where it sits ([WeekBoard.find]). */
data class BoardSpot(val card: BoardCard, val source: BoardSource, val key: Long)

/** A card parked in a week's lane ([WeekBoardRules.park]): the week and the new places of that lane's cards. */
data class BoardPark(
    val taskId: String,
    val from: BoardSource,
    val weekStart: Long,
    /** The lane's cards whose place changes (the parked one included). */
    val ranks: Map<String, Long>,
)

/** A move worked out by [WeekBoardRules.move]: where the task goes and the new places of that day's cards. */
data class BoardMove(
    val taskId: String,
    val fromDay: Long,
    val toDay: Long,
    /** The time it keeps on the new day; null for none. */
    val minuteOfDay: Int?,
    /** The cards whose place changes (the moved one included), [WeekBoardRules.RANK_STEP] apart. */
    val ranks: Map<String, Long>,
    /** Where it came from: a day, a week's lane or the Someday tray ([fromDay] is then the week's Monday, or 0). */
    val from: BoardSource = BoardSource.DAY,
) {
    val dayChanged: Boolean get() = from != BoardSource.DAY || fromDay != toDay
}

/**
 * What Undo needs after a Board move: the task's timing before and after, the old places of the cards it moved, and
 * (slice 4) the week it was parked in and its Someday kind before.
 */
data class BoardUndo(
    val taskId: String,
    val before: TaskTiming,
    val after: TaskTiming,
    val oldRanks: Map<String, Long?>,
    val oldWeek: Long? = null,
    val newWeek: Long? = null,
    val oldKind: SomedayKind? = null,
)

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
    const val SOMEDAY = "Someday"
    const val LANE_EMPTY = "Park tasks here for the week"
    const val SOMEDAY_EMPTY = "Nothing on Someday"
    const val NO_DAY = "No day yet"
    const val TO_SOMEDAY = "Someday"
    /** The card menu's row that parks a dayed card in its week's lane. */
    const val TO_WEEK = "No day this week"

    /**
     * Parked for a week with no day yet (slice 4): a one-off open task with [Task.boardWeek] and neither a planned time
     * nor a day it shows from. It sits in that week's lane, not in a day's column, and stays off Today.
     */
    fun parked(t: Task): Boolean =
        t.boardWeek != null && t.scheduledAtMs == null && t.showsFromDay == null && !t.isRepeating &&
            (t.lifecycle == Lifecycle.ACTIVE || t.lifecycle == Lifecycle.INBOX)

    fun build(
        tasks: List<Task>,
        events: List<CalendarEvent>,
        nowMs: Long,
        calendar: LocalCalendar,
        work: WorkHours? = null,
        hidden: Set<String> = emptySet(),
        toneOf: (CalendarEvent) -> Int = { if (it.isFixture) CalendarTones.FIXTURE else 1 },
        weeks: Int = WEEKS,
        goals: List<GoalItem> = emptyList(),
        weekGoals: WeekGoals = WeekGoals.EMPTY,
        bills: List<PressureBill> = emptyList(),
    ): WeekBoard {
        val today = calendar.epochDayOf(nowMs)
        val firstDay = today - (CivilDate.isoDayOfWeek(today) - 1)
        val lastDay = firstDay + 7L * weeks - 1
        val shown = DuplicateEvents.merge(events).filter { !DuplicateEvents.hiddenIn(it, hidden) }

        val sized = TaskEstimateRules.Sized.of(tasks)
        val goalTitles = goals.associate { it.id to it.title } + weekGoals.arcs.associate { it.goalId to it.title }
        val tagged: (BoardCard) -> BoardCard = { c -> c.task.goalId?.let(goalTitles::get)?.let { c.copy(goalTitle = it) } ?: c }
        val byDay = HashMap<Long, MutableList<BoardCard>>()
        val byWeek = HashMap<Long, MutableList<BoardCard>>()
        for (t in tasks) {
            if (parked(t)) {
                val ws = maxOf(t.boardWeek!!, firstDay)
                if (ws <= lastDay) byWeek.getOrPut(ws) { mutableListOf() } += tagged(laneCard(t, firstDay, sized))
                continue
            }
            val day = columnOf(t, today, calendar) ?: continue
            if (day !in firstDay..lastDay) continue
            byDay.getOrPut(day) { mutableListOf() } += tagged(card(t, today, calendar, sized))
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
                lane = orderLane(byWeek[ws].orEmpty()),
            ).let { bw -> bw.copy(goals = BoardGoalRules.lane(ws, today, goals, weekGoals, bw.allCards)) }
        }
        val someday = tasks.filter { it.lifecycle == Lifecycle.SOMEDAY }
            .sortedWith(compareByDescending<Task> { it.createdAtMs }.thenBy { it.id })
            .map { t ->
                tagged(BoardCard(t, TaskTitleRules.display(t.title), null, ListRules.kindLabel(t.somedayKind ?: SomedayKind.IDEA), done = false, carried = false))
            }
        val board = WeekBoard(today, boardWeeks, someday)
        // Item (10): the days stacking up glow amber with a line and a one-tap fix.
        return PressureRules.onBoard(board, PressureRules.days(board, shown, bills, nowMs, calendar))
    }

    /** A lane card: no time; "From last week" when parked in an earlier week. */
    private fun laneCard(t: Task, firstDay: Long, sized: TaskEstimateRules.Sized): BoardCard {
        val weeksAgo = (firstDay - t.boardWeek!!) / 7
        val line = when {
            weeksAgo <= 0 -> null
            weeksAgo == 1L -> "From last week"
            else -> "From $weeksAgo weeks ago"
        }
        return BoardCard(t, TaskTitleRules.display(t.title), null, line, done = false, carried = weeksAgo > 0, suggestedMinutes = TaskEstimateRules.suggest(t, sized))
    }

    /** A lane's order: placed first by place, then those from earlier weeks, then as added. */
    fun orderLane(cards: List<BoardCard>): List<BoardCard> = cards.sortedWith(
        compareBy<BoardCard>({ it.task.boardRank ?: Long.MAX_VALUE })
            .thenBy { it.task.boardWeek ?: 0L }
            .thenBy { it.task.createdAtMs }
            .thenBy { it.id },
    )

    /** The day [t] sits on, or null when it isn't on the board (Someday, waiting, dropped, parked in a week's lane, or done with no time). */
    fun columnOf(t: Task, today: Long, calendar: LocalCalendar): Long? = when (t.lifecycle) {
        Lifecycle.ACTIVE, Lifecycle.INBOX -> if (parked(t)) null else TaskWhenRules.dayOf(t, today, calendar)
        // Done on the day he did it; one ticked off early stays in the later day it was planned for (by hand, slice 1:
        // Sunsama-style, the card stays in its day).
        Lifecycle.DONE -> t.completedAtMs?.let(calendar::epochDayOf)?.let { doneDay ->
            val planned = t.scheduledAtMs?.let(calendar::epochDayOf) ?: t.showsFromDay
            if (planned != null && planned > doneDay) planned else doneDay
        }
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
            freeTimes = BoardBlockRules.freeTimes(d, today, nowMs, events, calendar, work),
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
        val spot = board.find(taskId) ?: return null
        val card = spot.card
        if (!card.canMove || toDay < today) return null
        val target = board.day(toDay) ?: return null
        val order = placed(target.openCards, taskId, toIndex)
        if (spot.source == BoardSource.DAY && spot.key == toDay && order == target.openCards.map { it.id }) return null
        val ranks = ranks(order, target.openCards + card)
        val minute = if (spot.source == BoardSource.DAY) TaskWhenRules.minuteOf(card.task, today, calendar) else null
        return BoardMove(taskId, spot.key, toDay, minute, ranks, spot.source)
    }

    /**
     * Parking [taskId] in the lane of the week starting [weekStart] at place [toIndex] (slice 4): from a day (it loses
     * its day and time), from Someday (it comes back) or within the lane. Null for a done or repeating task (a repeat
     * belongs to its days), a week that isn't on the board, or no change.
     */
    fun park(board: WeekBoard, taskId: String, weekStart: Long, toIndex: Int): BoardPark? {
        val spot = board.find(taskId) ?: return null
        if (!spot.card.canMove || spot.card.task.isRepeating) return null
        val week = board.week(weekStart) ?: return null
        val order = placed(week.lane, taskId, toIndex)
        if (spot.source == BoardSource.WEEK && spot.key == weekStart && order == week.lane.map { it.id }) return null
        return BoardPark(taskId, spot.source, weekStart, ranks(order, week.lane + spot.card))
    }

    /** Whether a card can go to the Someday tray: open, not repeating, not there already. */
    fun canSomeday(board: WeekBoard, taskId: String): Boolean {
        val spot = board.find(taskId) ?: return false
        return spot.source != BoardSource.SOMEDAY && spot.card.canMove && !spot.card.task.isRepeating
    }

    /** "Moved “Book MOT” to This week" · "… to Next week" · "… to 2–8 Nov" · "Moved “Book MOT” up" in the lane. */
    fun parkLine(title: String, park: BoardPark, board: WeekBoard, movedUp: Boolean): String {
        val t = TaskTitleRules.display(title)
        if (park.from == BoardSource.WEEK && board.laneOf(park.taskId)?.startEpochDay == park.weekStart) {
            return "Moved “$t” ${if (movedUp) "up" else "down"}"
        }
        val week = board.week(park.weekStart)?.title ?: CalendarAgenda.rangeLabel(park.weekStart, park.weekStart + 6)
        return "Moved “$t” to $week"
    }

    /** "Moved “Book MOT” to Someday". */
    fun somedayLine(title: String): String = "Moved “${TaskTitleRules.display(title)}” to $SOMEDAY"

    /** [cards]' ids with [taskId] taken out and put back at [toIndex] (clamped). */
    private fun placed(cards: List<BoardCard>, taskId: String, toIndex: Int): List<String> {
        val others = cards.filter { it.id != taskId }
        val at = toIndex.coerceIn(0, others.size)
        return others.map { it.id }.toMutableList().apply { add(at, taskId) }
    }

    /** New places [RANK_STEP] apart for [order]; only those that change. */
    private fun ranks(order: List<String>, cards: List<BoardCard>): Map<String, Long> {
        val current = cards.associate { it.id to it.task.boardRank }
        val ranks = LinkedHashMap<String, Long>()
        order.forEachIndexed { i, id ->
            val rank = (i + 1) * RANK_STEP
            if (current[id] != rank) ranks[id] = rank
        }
        return ranks
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
