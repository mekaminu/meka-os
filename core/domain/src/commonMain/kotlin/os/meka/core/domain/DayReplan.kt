package os.meka.core.domain

/**
 * Ada plans with you, part 1, slice 3 (Meka approved 2026-10-21 19:54: "re-plannable at any time ('I lost the
 * afternoon, re-plan')"): Obi re-plans the rest of today. Pure and non-AI, the same on the Fold and the Mac; the result
 * is a [WeekPlan] (with [WeekPlan.replanDay] set), so it is drawn as Obi's draft ([WeekDraftRules]): faint cards on the
 * days ahead with Keep all · Change · Start over, and nothing moves until Keep all.
 *
 * - **What stays today:** today's free time from now ([BoardCapacity.freeMin]) is filled first by what can't or
 *   shouldn't move (repeats, and anything due today or earlier), then by the rest in his order of importance (due
 *   soonest, then highest priority, then his order on the Board). Each counts its estimate, else Navilife's guess,
 *   else [PlanWeekRules.DEFAULT_MIN].
 * - **What moves:** the open, one-off tasks that no longer fit, each to the day ahead (up to [AHEAD] days, never after
 *   its due day) with the most room ([PlanWeekRules.room]: office evenings kept light, a day stacking up takes nothing),
 *   ties to the day with fewer cards, then the earlier. A task due today or overdue never moves: moving it would make
 *   it late.
 * - Nothing to move (it all still fits, or nothing can go anywhere): no draft, and Obi says so ([SPOKEN_FITS]).
 */
object DayReplanRules {
    /** How many days ahead a task may be moved to. */
    const val AHEAD = 6
    const val TITLE = "Re-plan today"
    /** The bar's title (in place of "Obi's draft"). */
    const val DRAFT_TITLE = "Obi's re-plan"
    /** Why each moved task is on the draft. */
    const val WHY = "Didn't fit today"
    /** The undo bar after Keep all on a re-plan. */
    const val KEPT_PREFIX = "Today re-planned · "
    /** What Obi says before re-planning (the conversation's memory says [OPENED]). */
    const val OPENING = "Let's look at the rest of today."
    const val OPENED = "Re-planned the rest of today on the Board."
    /** Said when everything left still fits (or nothing can move): the Board opens as it is. */
    const val SPOKEN_FITS = "What's left today still fits your free time, so nothing needs to move. I've opened the Board."
    /** Said when today has no Board column (shouldn't happen) or the re-plan failed. */
    const val SPOKEN_NONE = "I couldn't re-plan today just now. I've opened the Board."

    /**
     * The re-plan for [today] on [board] ([events] are the Board's, for each day's fixed line), or null when nothing
     * needs to move. The plan's rows are only the tasks that move, each with its suggested day chosen.
     */
    fun plan(board: WeekBoard, events: List<CalendarEvent>, today: Long, calendar: LocalCalendar): WeekPlan? {
        val todayCol = board.day(today) ?: return null
        val dueOn: (Task) -> Long? = { t -> t.dueAtMs?.let(calendar::epochDayOf) }
        val minutesOf: (BoardCard) -> Int = { c -> c.task.estimateMinutes ?: c.suggestedMinutes ?: PlanWeekRules.DEFAULT_MIN }
        val free = todayCol.capacity?.freeMin ?: 0

        val open = todayCol.openCards
        val pinned: (BoardCard) -> Boolean = { c -> !c.canMove || c.task.isRepeating || (dueOn(c.task)?.let { it <= today } ?: false) }
        var used = open.filter(pinned).sumOf(minutesOf)
        val order = open.withIndex().filter { !pinned(it.value) }.sortedWith(
            compareBy<IndexedValue<BoardCard>>(
                { dueOn(it.value.task) ?: Long.MAX_VALUE },
                { -it.value.task.priority },
                { it.index },
            ),
        ).map { it.value }
        val moving = mutableListOf<BoardCard>()
        for (c in order) {
            val m = minutesOf(c)
            if (used + m <= free) used += m else moving += c
        }
        if (moving.isEmpty()) return null

        val window = (today + 1..today + AHEAD).filter { d -> board.day(d)?.let { !it.isPast } == true }
        val added = HashMap<Long, Int>()
        val addedCards = HashMap<Long, Int>()
        val rows = mutableListOf<PlanRow>()
        for (c in moving) {
            val due = dueOn(c.task)
            val allowed = if (due == null) window else window.filter { it <= due }
            if (allowed.isEmpty()) continue
            val minutes = minutesOf(c)
            val best = allowed.mapNotNull { board.day(it) }.maxWithOrNull(
                compareBy<BoardDay>({ PlanWeekRules.room(it) - (added[it.epochDay] ?: 0) })
                    .thenByDescending { it.openCards.size + (addedCards[it.epochDay] ?: 0) }
                    .thenByDescending { it.epochDay },
            )?.epochDay ?: allowed.first()
            added[best] = (added[best] ?: 0) + minutes
            addedCards[best] = (addedCards[best] ?: 0) + 1
            val why = due?.let { "$WHY · due ${PressureRules.shortDay(it, today)}" } ?: WHY
            rows += PlanRow(c.id, c.title, why, allowed, best, best, minutes)
        }
        if (rows.isEmpty()) return null
        val days = window.mapNotNull { board.day(it) }.map { PlanWeekRules.planDay(board, it, events, today, calendar) }
        return WeekPlan(
            weekStart = PlanWeekRules.weekStart(today), title = TITLE,
            range = CalendarAgenda.rangeLabel(window.first(), window.last()),
            days = days, rows = rows, goals = emptyList(), replanDay = today,
        )
    }

    /** The bar's line on a re-plan: "✦ Obi's re-plan · 2 tasks off today, over 2 days". */
    fun line(plan: WeekPlan): String {
        val p = plan.placing
        val tasks = if (p.size == 1) "1 task" else "${p.size} tasks"
        val days = p.mapNotNull { it.chosen }.distinct().size
        val over = if (days == 1) "to 1 day" else "over $days days"
        return "${WeekDraftRules.MARK} $DRAFT_TITLE · $tasks off today, $over"
    }

    /** The undo bar after Keep all: "Today re-planned · 2 tasks moved". */
    fun keptLine(moved: Int): String = KEPT_PREFIX + if (moved == 1) "1 task moved" else "$moved tasks moved"

    /**
     * What Obi says after "I lost the afternoon, re-plan": "I've moved what won't fit today. Book MOT tomorrow, and Trip
     * form on Thursday, plus 1 more. The rest still fits today. Keep all, change any of it, or start over."
     * [SPOKEN_FITS] when there's no draft.
     */
    fun spoken(draft: WeekDraft?): String {
        val p = draft?.plan ?: return SPOKEN_FITS
        val placing = p.placing
        if (placing.isEmpty()) return SPOKEN_FITS
        val firsts = placing.take(2).map { r -> "${r.title.trim().trimEnd('.', '!', '?')} ${WeekDraftRules.spokenDay(p, r.chosen!!)}" }
        val list = if (firsts.size == 2) "${firsts[0]}, and ${firsts[1]}" else firsts.single()
        val more = (placing.size - 2).takeIf { it > 0 }?.let { ", plus $it more" } ?: ""
        return "I've moved what won't fit today. $list$more. The rest still fits today. ${WeekDraftRules.SPOKEN_CLOSE}"
    }
}
