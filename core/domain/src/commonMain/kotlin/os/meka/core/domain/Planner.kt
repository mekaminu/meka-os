package os.meka.core.domain

/**
 * Day planner v1 (M1): deterministic, explainable, no AI. It proposes; the owner applies (autonomy level 1 — suggest).
 *
 * Fits open, unscheduled tasks into today's free time between calendar events, inside working hours, highest priority
 * first (then oldest). Calendar events are fixed; nothing is ever placed over one. Fixtures keep a buffer before
 * kick-off so the evening is genuinely free. Tasks that don't fit stay unplaced and are reported, never squeezed.
 *
 * Habits that are behind or due today (see [Goals.plannerHabits]) get room first, in their preferred part of the day
 * when it has space (else the first gap that fits), so a busy day doesn't quietly crowd them out. Habit blocks are
 * part of the suggestion only: Apply schedules tasks, and a habit is ticked when it's done.
 */
object DayPlanner {
    data class Prefs(
        /** Minutes after local midnight. */
        val dayStartMin: Int = 9 * 60,
        val dayEndMin: Int = 21 * 60,
        val defaultEstimateMin: Int = 30,
        /** Breathing room left before and after every event. */
        val bufferMin: Int = 10,
        /** Kept clear before a match starts (travel, food, settling in). */
        val fixtureLeadMin: Int = 60,
        /** Slots are aligned to this many minutes so suggested times read naturally (09:00, 09:15…). */
        val granularityMin: Int = 15,
    )

    data class Slot(val startMs: Long, val endMs: Long)

    data class Placement(val task: Task, val startMs: Long, val endMs: Long)

    /** Room made for a habit today. [behind] is true when the habit is behind this week (not just due today). */
    data class HabitPlacement(val habitId: String, val title: String, val startMs: Long, val endMs: Long, val behind: Boolean)

    data class Plan(
        val placements: List<Placement>,
        /** Tasks that did not fit today, in the order they would have been placed. */
        val unplaced: List<Task>,
        /** Free time left after placing, for the "you still have…" line. */
        val freeMinutesLeft: Int,
        /** Events treated as fixed, for showing the plan alongside them. */
        val busy: List<CalendarEvent>,
        /** Room made for habits, placed before tasks. */
        val habits: List<HabitPlacement> = emptyList(),
        /** Habits that needed room but found none today. */
        val habitsUnplaced: List<PlannerHabit> = emptyList(),
    ) {
        /** Nothing to apply (habit blocks are shown, not applied). */
        val isEmpty: Boolean get() = placements.isEmpty()
        /** Nothing to show at all. */
        val isBlank: Boolean get() = placements.isEmpty() && unplaced.isEmpty() && habits.isEmpty() && habitsUnplaced.isEmpty()
    }

    private const val MIN = 60_000L

    fun plan(
        tasks: List<Task>,
        events: List<CalendarEvent>,
        nowMs: Long,
        day: DayWindow,
        prefs: Prefs = Prefs(),
        habits: List<PlannerHabit> = emptyList(),
    ): Plan {
        val g = prefs.granularityMin * MIN
        val windowStart = maxOf(day.startMs + prefs.dayStartMin * MIN, ceilTo(nowMs, day.startMs, g))
        val windowEnd = day.startMs + prefs.dayEndMin * MIN
        val timed = events.filter { !it.allDay && it.overlaps(day) }

        // Busy blocks: each event plus buffers; fixtures also block the lead-in before kick-off.
        val busy = timed.map { e ->
            val lead = if (e.provider == "fixtures") prefs.fixtureLeadMin else prefs.bufferMin
            Slot(e.startAtMs - lead * MIN, e.endAtMs + prefs.bufferMin * MIN)
        }
        var free = subtract(Slot(windowStart, windowEnd), busy)

        // Habits first (behind before due), each in its part of the day if there's room there.
        val habitPlacements = mutableListOf<HabitPlacement>()
        val habitsUnplaced = mutableListOf<PlannerHabit>()
        for (h in habits.sortedBy { if (it.behind) 0 else 1 }) {
            val need = h.minutes.coerceAtLeast(prefs.granularityMin) * MIN
            val pref = GoalRules.timingWindow(h.timing).let { Slot(day.startMs + it.first * MIN, day.startMs + (it.last + 1) * MIN) }
            val start = firstFit(subtract(free, listOf(Slot(Long.MIN_VALUE, pref.startMs), Slot(pref.endMs, Long.MAX_VALUE))), need, day.startMs, g)
                ?: firstFit(free, need, day.startMs, g)
            if (start == null) { habitsUnplaced += h; continue }
            habitPlacements += HabitPlacement(h.id, h.title, start, start + need, h.behind)
            free = subtract(free, listOf(Slot(start, ceilTo(start + need, day.startMs, g))))
        }

        val candidates = tasks
            .filter { (it.lifecycle == Lifecycle.ACTIVE || it.lifecycle == Lifecycle.INBOX) && it.scheduledAtMs == null && !it.hasConflict }
            .filter { it.dueAtMs == null || it.dueAtMs < day.endMs } // due later than today waits for its day
            .filter { !it.waitsForItsDay(day.epochDay) } // a later occurrence or a snoozed item waits too
            .sortedWith(compareBy<Task> { it.dueAtMs ?: Long.MAX_VALUE }.thenByDescending { it.priority }.thenBy { it.createdAtMs })

        val placements = mutableListOf<Placement>()
        val unplaced = mutableListOf<Task>()
        for (t in candidates) {
            val need = (t.estimateMinutes ?: prefs.defaultEstimateMin).coerceAtLeast(prefs.granularityMin) * MIN
            val start = firstFit(free, need, day.startMs, g)
            if (start == null) { unplaced += t; continue }
            val p = Placement(t, start, start + need)
            placements += p
            free = subtract(free, listOf(Slot(p.startMs, ceilTo(p.endMs, day.startMs, g))))
        }
        val left = free.sumOf { (it.endMs - it.startMs) / MIN }.toInt()
        return Plan(placements, unplaced, left, timed.sortedBy { it.startAtMs }, habitPlacements, habitsUnplaced)
    }

    /** The first aligned start in [free] with [need] ms of room, or null. */
    private fun firstFit(free: List<Slot>, need: Long, origin: Long, g: Long): Long? =
        free.map { ceilTo(it.startMs, origin, g) to it }.firstOrNull { (s, slot) -> slot.endMs - s >= need }?.first

    /** [from] minus every block, as sorted, non-overlapping slots. */
    internal fun subtract(from: Slot, blocks: List<Slot>): List<Slot> = subtract(listOf(from), blocks)

    internal fun subtract(from: List<Slot>, blocks: List<Slot>): List<Slot> {
        var result = from.filter { it.endMs > it.startMs }
        for (b in blocks.sortedBy { it.startMs }) {
            result = result.flatMap { s ->
                if (b.endMs <= s.startMs || b.startMs >= s.endMs) listOf(s)
                else listOfNotNull(
                    Slot(s.startMs, b.startMs).takeIf { it.endMs > it.startMs },
                    Slot(b.endMs, s.endMs).takeIf { it.endMs > it.startMs },
                )
            }
        }
        return result.sortedBy { it.startMs }
    }

    private fun ceilTo(t: Long, origin: Long, step: Long): Long {
        val r = ((t - origin) % step + step) % step
        return if (r == 0L) t else t + (step - r)
    }
}
