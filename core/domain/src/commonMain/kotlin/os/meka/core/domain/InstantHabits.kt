package os.meka.core.domain

/**
 * Taps feel instant for habits (Meka 2026-10-25 01:45: "it should be instantaneous"; the instant-taps item, (2)). A
 * habit's tick on the face's dots, Today's chips, Goals and Needs you's "Habits today" showed only once the core's call
 * came back from its one thread. Now the apps keep the ticks on their way (habit id → done) and draw [shown] over the
 * core's [GoalsView] in the same frame. A tick is forgotten when its call returns ([forget]; the core publishes its own
 * view before the call returns), so a failed call puts the habit back as the core has it and the bar says why
 * ([InstantTapRules.failedLine]).
 *
 * Only what the tick changes is drawn: done today, this week's count and strip, the pace and its line. The streak and the
 * order are left to the core, which settles them a moment later. Non-AI and pure, so the Fold and the Mac draw the same.
 */
object InstantHabitRules {
    /** [taps] with [id] ticked ([done] true) or un-ticked for today. A newer tap replaces an older one. */
    fun tick(taps: Map<String, Boolean>, id: String, done: Boolean): Map<String, Boolean> = taps + (id to done)

    /**
     * The call that set [id] to [done] returned (done or failed). A newer tap for the same habit (a quick tick then
     * un-tick) is kept until its own call returns, so the first call's return can't flash the habit back.
     */
    fun forget(taps: Map<String, Boolean>, id: String, done: Boolean): Map<String, Boolean> =
        if (taps[id] == done) taps - id else taps

    /** The habits as the screens draw them: the core's [goals] with [taps] applied. */
    fun shown(goals: GoalsView, taps: Map<String, Boolean>): GoalsView {
        if (taps.isEmpty() || goals.habits.isEmpty()) return goals
        var changed = false
        val habits = goals.habits.map { h ->
            val done = taps[h.id]
            if (done == null || done == h.doneToday) h else { changed = true; apply(h, done, goals.today) }
        }
        return if (changed) goals.copy(habits = habits) else goals
    }

    /** [h] ticked or un-ticked for [today] (local epoch day; 0 when the view doesn't know it, so the strip is left). */
    fun apply(h: HabitItem, done: Boolean, today: Long): HabitItem {
        val count = (h.doneThisWeek + if (done) 1 else -1).coerceAtLeast(0)
        val pace = when {
            done -> HabitPace.DONE_TODAY
            count >= h.weekTarget -> HabitPace.WEEK_MET
            !h.weekly -> HabitPace.DUE
            today > 0 -> {
                val left = maxOf(0, h.weekTarget - count)
                val days = GoalRules.daysLeft(today)
                when {
                    left > days -> HabitPace.BEHIND
                    left >= days - 1 -> HabitPace.DUE
                    else -> HabitPace.ON_TRACK
                }
            }
            else -> HabitPace.DUE
        }
        val index = if (today > 0) (today - GoalRules.weekStart(today)).toInt() else -1
        val week = if (index in h.week.indices) h.week.mapIndexed { i, b -> if (i == index) done else b } else h.week
        return h.copy(
            doneToday = done,
            doneThisWeek = count,
            pace = pace,
            week = week,
            meta = GoalRules.habitMeta(pace, count, h.weekTarget, h.targetPerWeek, today.takeIf { it > 0 }),
        )
    }
}
