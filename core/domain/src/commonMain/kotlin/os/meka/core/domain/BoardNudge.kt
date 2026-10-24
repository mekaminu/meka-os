package os.meka.core.domain

/** What Obi's line in the Week board's header is about ([BoardNudgeRules]); OVER and STACKING are drawn in amber. */
enum class BoardNudgeKind { OVER, STACKING, TO_PLACE, FITS, CLEAR }

/**
 * Obi's one line in the Week board's header (Option B, slice 4): "Mon is 1h over · move “Gym” to Fri?". [fix], when
 * there is one, is what a tap does (a Board move with Undo, through the facade's easePressure); [spoken] is for
 * screen readers ("Obi says: …").
 */
data class BoardNudge(val kind: BoardNudgeKind, val line: String, val spoken: String, val fix: PressureFix? = null) {
    /** Amber in the header: a day over its free time, or stacking up. */
    val warns: Boolean get() = kind == BoardNudgeKind.OVER || kind == BoardNudgeKind.STACKING

    /** The fix's task (Swift reads this rather than the nullable data class). */
    val fixTaskId: String? get() = fix?.taskId

    /** The fix's day, or -1 for none. */
    val fixDay: Long get() = fix?.toDay ?: -1L
}

/**
 * The Week board redesign, Option B (approved mockup, Meka 2026-10-24 08:30), slice 4: Obi's one-line nudge beside the
 * week's title, on the Fold (folded and unfolded) and the Mac. Non-AI and pure: it reads only the board itself (the
 * days' budgets and stacking-up warnings, the week's lane), so it is the same on both apps and says nothing new.
 *
 * For the week shown, the first of these that holds:
 * 1. **A day over its free time** (the budget pill amber), the soonest from today: "Mon is 1h over · move “Gym” to
 *    Fri?" when one card would fit elsewhere ([overFix]), else "… · move something?". With nothing free at all:
 *    "Mon has no free time · …".
 * 2. **A day stacking up** ([PressureRules]): "Thu is stacking up · move “Gym” to Sat?" (its first one-tap fix).
 * 3. **Tasks still to place** in the week's lane: "3 tasks still need a day".
 * 4. **Something planned and it all fits**: "Everything fits this week" / "Next week fits so far".
 * 5. **Nothing planned yet** from today: "A clear week so far".
 * A week wholly in the past has no line. One line, plain, never "great", never a list; a playful word is never needed.
 */
object BoardNudgeRules {
    const val SPEAKER = "Obi"
    /** How far ahead a fix may move a card (the same as the stacking-up fixes). */
    const val LIGHTER_DAYS = PressureRules.LIGHTER_DAYS

    /** The nudge for [week] on [board], or null for a week already gone. */
    fun of(board: WeekBoard, week: BoardWeek, calendar: LocalCalendar): BoardNudge? {
        val today = board.todayEpochDay
        val ahead = week.days.filter { !it.isPast && it.epochDay >= today }
        if (ahead.isEmpty()) return null

        ahead.firstOrNull { d -> d.capacity?.let { it.over && it.plannedMin > 0 } == true }?.let { d ->
            val c = d.capacity!!
            val name = dayName(d.epochDay, today)
            val head = if (c.freeMin <= 0) "$name has no free time" else "$name is ${TaskEstimateRules.label(c.plannedMin - c.freeMin)} over"
            val fix = overFix(board, d, c.plannedMin - c.freeMin.coerceAtLeast(0), today, calendar)
            return nudge(BoardNudgeKind.OVER, head, fix)
        }

        ahead.firstOrNull { it.pressure != null }?.let { d ->
            val name = dayName(d.epochDay, today)
            return nudge(BoardNudgeKind.STACKING, "$name is stacking up", d.pressure!!.fixes.firstOrNull())
        }

        val lane = week.lane.count { !it.done }
        if (lane > 0) {
            return plain(BoardNudgeKind.TO_PLACE, if (lane == 1) "1 task still needs a day" else "$lane tasks still need a day")
        }

        val planned = ahead.any { d -> d.openCards.isNotEmpty() }
        val thisWeek = today in week.startEpochDay until week.startEpochDay + 7
        return when {
            planned && thisWeek -> plain(BoardNudgeKind.FITS, "Everything fits this week")
            planned -> plain(BoardNudgeKind.FITS, "${week.title} fits so far")
            else -> plain(BoardNudgeKind.CLEAR, "A clear week so far")
        }
    }

    /** "Today" · "Tomorrow" · "Mon" (the mockup's short day) · "Mon 2 Nov" past a week. */
    fun dayName(d: Long, today: Long): String = when {
        d == today -> "Today"
        d == today + 1 -> "Tomorrow"
        d < today + 7 -> LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(d) - 1]
        else -> CivilDate.shortLabel(d)
    }

    /**
     * One card off an over day [d] that ends the overrun ([over] minutes) on its own: of its open cards that can move,
     * don't repeat and aren't due that day, with an estimate, the smallest that covers [over], else the largest (the
     * last in his order on a tie); to the day from today within [LIGHTER_DAYS] of [d] with the most free time left
     * that it fits in whole, not over itself and not stacking up (the earlier day on a tie), never past its due day.
     */
    fun overFix(board: WeekBoard, d: BoardDay, over: Int, today: Long, calendar: LocalCalendar): PressureFix? {
        val cards = d.openCards.withIndex().filter { (_, c) ->
            val due = c.task.dueAtMs?.let(calendar::epochDayOf)
            c.canMove && !c.task.isRepeating && (c.task.estimateMinutes ?: 0) > 0 && (due == null || due > d.epochDay)
        }
        if (cards.isEmpty()) return null
        val est = { c: IndexedValue<BoardCard> -> c.value.task.estimateMinutes ?: 0 }
        val covering = cards.filter { est(it) >= over }
        val pick = (if (covering.isNotEmpty()) covering.sortedWith(compareBy({ est(it) }, { -it.index }))
        else cards.sortedWith(compareBy({ -est(it) }, { -it.index }))).first().value
        val need = pick.task.estimateMinutes ?: return null
        val due = pick.task.dueAtMs?.let(calendar::epochDayOf)
        val target = board.days.asSequence()
            .filter { it.epochDay != d.epochDay && it.epochDay >= today && !it.isPast && it.epochDay <= d.epochDay + LIGHTER_DAYS }
            .filter { it.pressure == null && (due == null || it.epochDay <= due) }
            .mapNotNull { day -> day.capacity?.let { c -> day to (c.freeMin - c.plannedMin) } }
            .filter { (_, spare) -> spare >= need }
            .sortedWith(compareBy<Pair<BoardDay, Int>>({ -it.second }, { it.first.epochDay }))
            .firstOrNull()?.first ?: return null
        return PressureFix(pick.id, pick.title, target.epochDay, "Move “${pick.title}” to ${PressureRules.shortDay(target.epochDay, today)}", earlier = false)
    }

    private fun nudge(kind: BoardNudgeKind, head: String, fix: PressureFix?): BoardNudge {
        val ask = fix?.let { lowerFirst(it.label) } ?: "move something"
        val line = "$head · $ask?"
        return BoardNudge(kind, line, spoken(line), fix)
    }

    private fun plain(kind: BoardNudgeKind, line: String) = BoardNudge(kind, line, spoken(line))

    /** "Obi says: Mon is 1h over, move “Gym” to Fri?" for screen readers. */
    fun spoken(line: String): String = "$SPEAKER says: " + line.replace(" · ", ", ")

    private fun lowerFirst(s: String): String = if (s.isEmpty()) s else s[0].lowercaseChar() + s.substring(1)
}
