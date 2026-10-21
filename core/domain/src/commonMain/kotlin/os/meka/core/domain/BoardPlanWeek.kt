package os.meka.core.domain

/** One day of the week being planned: its chip ("Mon 26"), its place, what's fixed on it and how much time it has. */
data class PlanDay(
    val day: Long,
    /** "Today" · "Tomorrow" · "Mon 26" */
    val label: String,
    /** "Office" · "Home" · "Away"; null on a day off. */
    val placeLabel: String?,
    /** What's already fixed: "09:30 Dentist", "All day Half term", "Due · Trip form". */
    val fixed: List<String>,
    /** "4h free" · "1h free · office evening"; null before today. */
    val freeLine: String?,
    /** Already stacking up ([PressureRules]): the spread puts nothing new here. */
    val stacking: Boolean,
) {
    /** "Mon 26 · Office · 09:30 Dentist, Due · Trip form · 4h free" for screen readers. */
    val spoken: String
        get() = listOfNotNull(label, placeLabel, fixed.takeIf { it.isNotEmpty() }?.joinToString(", "), freeLine, if (stacking) "stacking up" else null)
            .joinToString(", ")
}

/**
 * One task to place: why it's here ("Carried 2 days", "From last week", "No day yet", "Due Thu"), the day Navilife
 * suggests and the day he has chosen ([chosen] null = leave it where it is).
 */
data class PlanRow(
    val taskId: String,
    val title: String,
    val why: String,
    /** The days it may go to: the week's days from today, never after its due day. */
    val days: List<Long>,
    val suggested: Long,
    val chosen: Long?,
    /** Minutes the spread counted for it (its estimate, else Navilife's guess, else 30). */
    val minutes: Int,
)

/**
 * Plan your week (Week board item (6), slice 1; Meka 2026-10-19 20:36), non-AI and pure: a guided flow in four steps —
 * what's left from last week, this week's fixed events and deadlines, the week's goals, then a suggested spread — and
 * nothing moves until he accepts ([PlanWeekRules.accepted]).
 */
data class WeekPlan(
    val weekStart: Long,
    /** "Plan this week" · "Plan next week" */
    val title: String,
    /** "26 Oct – 1 Nov" */
    val range: String,
    val days: List<PlanDay>,
    /** The tasks to place, in the order the spread placed them (due first, then the longest carried, then priority). */
    val rows: List<PlanRow>,
    val goals: List<BoardGoal>,
    /** Week board item (8): the one line carried from last week's review ("From your review · Run 5k"), on step 1. */
    val carryLine: String? = null,
) {
    /** The days a row can go to, by chip. */
    fun day(d: Long): PlanDay? = days.firstOrNull { it.day == d }

    /** The chip on [d]: "Mon 26" (or "Today", "Tomorrow"). */
    fun dayLabel(d: Long): String = day(d)?.label ?: CivilDate.shortLabel(d)

    /** [taskId] to [day] (null leaves it where it is). Unknown rows or days outside its own are ignored. */
    fun choose(taskId: String, day: Long?): WeekPlan = copy(
        rows = rows.map { r -> if (r.taskId == taskId && (day == null || day in r.days)) r.copy(chosen = day) else r },
    )

    /** For Swift: [choose] with -1 to leave it. */
    fun chooseDay(taskId: String, day: Long): WeekPlan = choose(taskId, day.takeIf { it >= 0 })

    /** The rows going somewhere. */
    val placing: List<PlanRow> get() = rows.filter { it.chosen != null }

    /** Step 1's rows: what's left from last week (carried, parked, unplaced deadlines). */
    val leftLine: String
        get() = when (rows.size) {
            0 -> PlanWeekRules.NOTHING_LEFT
            1 -> "1 task to place"
            else -> "${rows.size} tasks to place"
        }

    /** The last step's line: "6 tasks over 4 days · office evenings kept light" · "Nothing to place". */
    val spreadLine: String
        get() {
            val p = placing
            if (p.isEmpty()) return if (rows.isEmpty()) PlanWeekRules.NOTHING_LEFT else PlanWeekRules.NOTHING_CHOSEN
            val days = p.map { it.chosen }.distinct().size
            val tasks = if (p.size == 1) "1 task" else "${p.size} tasks"
            val over = if (days == 1) "on 1 day" else "over $days days"
            val office = this.days.any { it.placeLabel == "Office" }
            return "$tasks $over" + if (office) " · office evenings kept light" else ""
        }

    /** Accept is offered when something would move. */
    val canAccept: Boolean get() = placing.isNotEmpty()

    /** The four steps' headers. */
    val steps: List<String> get() = PlanWeekRules.STEPS

    /** Step 3's line: "2 goals · Run 5k 2 of 4 done" · "No goals picked for this week". */
    val goalsLine: String
        get() = if (goals.isEmpty()) PlanWeekRules.NO_GOALS else goals.joinToString(" · ") { "${it.title} ${it.line}" }
}

/**
 * Plan your week (Week board and roll-over, item (6), slice 1). Non-AI and pure.
 *
 * - **Which week:** this week from Monday to Saturday; on Sunday, next week (Sunday evening is when he plans).
 * - **To place** (step 1, "Left from last week"): open, one-off tasks that are carried over from an earlier day, parked
 *   in this week's (or the planned week's) lane with no day, or due in the planned week with no day of their own.
 *   Repeats keep their days; done, Someday and waiting tasks aren't asked about.
 * - **Fixed** (step 2): each day's events (as the Board's chips, all of them) and the tasks due that day.
 * - **Goals** (step 3): the week's goals as on the Board's goals lane.
 * - **The suggested spread** (step 4): rows in turn (due first, earliest first; then the longest carried; then the
 *   highest priority; then his order), each to the allowed day with the most room left — its free time less what is
 *   planned there and what the spread has added (estimate, else Navilife's guess, else [DEFAULT_MIN]). An office day
 *   counts at most [OFFICE_EVENING_MIN] of free time, so his office evenings stay light; a day already stacking up
 *   counts none. Ties go to the day with fewer cards, then the earlier. He can change any row's day or leave it out;
 *   nothing moves until Accept, and one Undo puts every move back.
 */
object PlanWeekRules {
    const val OPEN = "Plan your week"
    const val ACCEPT = "Accept plan"
    const val NOT_NOW = "Not now"
    const val NEXT = "Next"
    const val BACK = "Back"
    const val LEAVE = "Leave"
    const val NOTHING_LEFT = "Nothing left over · the week is yours to shape"
    const val NOTHING_CHOSEN = "Nothing chosen · every task stays where it is"
    const val NO_GOALS = "No goals picked for this week · add one with + Goal on the Board"
    const val NOTHING_FIXED = "Nothing fixed"
    const val UNDONE = "Plan undone · every task back where it was"
    const val DEFAULT_MIN = 30
    const val OFFICE_EVENING_MIN = 90
    val STEPS = listOf("Left from last week", "Fixed this week", "Goals", "Suggested spread")

    /** The week planned from local [today]: this week's Monday, or next week's on a Sunday. */
    fun weekStart(today: Long): Long {
        val dow = CivilDate.isoDayOfWeek(today)
        val monday = today - (dow - 1)
        return if (dow == 7) monday + 7 else monday
    }

    fun plan(board: WeekBoard, events: List<CalendarEvent>, today: Long, calendar: LocalCalendar): WeekPlan {
        val start = weekStart(today)
        val week = board.week(start)
        val first = maxOf(start, today)
        val last = start + 6
        val window = (first..last).toList()
        val title = if (start <= today) "Plan this week" else "Plan next week"
        val range = CalendarAgenda.rangeLabel(start, last)
        if (week == null) return WeekPlan(start, title, range, emptyList(), emptyList(), emptyList())

        val dueOn: (Task) -> Long? = { t -> t.dueAtMs?.let(calendar::epochDayOf) }
        val days = window.mapNotNull { board.day(it) }.map { d ->
            val due = board.days.flatMap { it.openCards }.filter { dueOn(it.task) == d.epochDay }.map { "Due · ${it.title}" }
            val chips = allChips(d.epochDay, events, calendar)
            val office = d.place == BoardPlace.OFFICE
            val free = d.capacity?.let { c ->
                val room = if (office) minOf(c.freeMin, OFFICE_EVENING_MIN) else c.freeMin
                "${TaskEstimateRules.label(room)} free" + if (office) " · office evening" else ""
            }
            PlanDay(d.epochDay, label(d.epochDay, today), d.placeLabel, chips + due, free, d.pressure != null)
        }

        // Step 1: what's left to place.
        val todayCol = board.day(today)
        val candidates = LinkedHashMap<String, Pair<BoardCard, String>>()
        todayCol?.openCards?.filter { it.carried && !it.task.isRepeating }?.forEach { candidates[it.id] = it to (it.line ?: "Carried over") }
        val monday = today - (CivilDate.isoDayOfWeek(today) - 1)
        val lanes = listOfNotNull(board.week(monday), week.takeIf { start != monday })
        lanes.flatMap { it.lane }.forEach { candidates.putIfAbsent(it.id, it to (it.line ?: "No day yet")) }
        board.days.flatMap { it.openCards }.filter { c ->
            val t = c.task
            val due = dueOn(t)
            !t.isRepeating && t.scheduledAtMs == null && t.showsFromDay == null && due != null && due in first..last
        }.forEach { c -> candidates.putIfAbsent(c.id, c to "Due ${PressureRules.shortDay(dueOn(c.task)!!, today)}") }

        val ordered = candidates.values.filter { it.first.canMove }.sortedWith(
            compareBy<Pair<BoardCard, String>>(
                { dueOn(it.first.task) ?: Long.MAX_VALUE },
                { if (it.first.carried) it.first.task.scheduledAtMs ?: Long.MAX_VALUE else Long.MAX_VALUE },
                { -it.first.task.priority },
            ),
        )

        val added = HashMap<Long, Int>()
        val addedCards = HashMap<Long, Int>()
        val rows = mutableListOf<PlanRow>()
        for ((card, why) in ordered) {
            val due = dueOn(card.task)
            val allowed = when {
                due == null -> window
                due < first -> listOf(first)
                else -> window.filter { it <= due }
            }
            if (allowed.isEmpty()) continue
            val minutes = card.task.estimateMinutes ?: card.suggestedMinutes ?: DEFAULT_MIN
            val best = allowed.mapNotNull { board.day(it) }.maxWithOrNull(
                compareBy<BoardDay>({ room(it) - (added[it.epochDay] ?: 0) })
                    .thenByDescending { it.openCards.size + (addedCards[it.epochDay] ?: 0) }
                    .thenByDescending { it.epochDay },
            )?.epochDay ?: allowed.first()
            added[best] = (added[best] ?: 0) + minutes
            addedCards[best] = (addedCards[best] ?: 0) + 1
            rows += PlanRow(card.id, card.title, why, allowed, best, best, minutes)
        }
        return WeekPlan(start, title, range, days, rows, week.goals)
    }

    /** Room left on a day for the spread: free time (capped on an office day, none when stacking up) less what's planned. */
    private fun room(d: BoardDay): Int {
        if (d.pressure != null) return -10_000
        val c = d.capacity ?: return -10_000
        val free = if (d.place == BoardPlace.OFFICE) minOf(c.freeMin, OFFICE_EVENING_MIN) else c.freeMin
        return free - c.plannedMin
    }

    /** All the day's events as the Board's chips read them: "All day Half term", "09:30 Dentist". */
    private fun allChips(d: Long, events: List<CalendarEvent>, calendar: LocalCalendar): List<String> {
        val w = CalendarAgenda.window(d, calendar)
        return events.filter { it.overlaps(w) }
            .sortedWith(compareBy<CalendarEvent>({ !it.allDay }, { maxOf(it.startAtMs, w.startMs) }, { it.title }))
            .map { e ->
                val time = when {
                    e.allDay || (e.startAtMs <= w.startMs && e.endAtMs >= w.endMs) -> "All day"
                    e.startAtMs < w.startMs -> "Until ${LocalClock.formatMinute(calendar.minuteOfDay(e.endAtMs))}"
                    else -> LocalClock.formatMinute(calendar.minuteOfDay(e.startAtMs))
                }
                "$time ${e.title}"
            }
    }

    /** The chip: "Today" · "Tomorrow" · "Mon 26". */
    fun label(d: Long, today: Long): String = when (d) {
        today -> "Today"
        today + 1 -> "Tomorrow"
        else -> "${LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(d) - 1]} ${CivilDate.fromEpochDay(d).day}"
    }

    /** The undo bar after Accept: "Week planned · 6 tasks over 4 days". */
    fun acceptedLine(moved: Int, days: Int): String {
        val tasks = if (moved == 1) "1 task" else "$moved tasks"
        val over = if (days == 1) "on 1 day" else "over $days days"
        return "Week planned · $tasks $over"
    }

    /** What a row says: "Thu 29 · suggested" when his choice is Navilife's, else the day, or "Stays where it is". */
    fun rowLine(plan: WeekPlan, row: PlanRow): String {
        val chosen = row.chosen ?: return "${row.why} · stays where it is"
        val day = plan.dayLabel(chosen)
        return "${row.why} · " + if (chosen == row.suggested) "$day, suggested" else day
    }
}
