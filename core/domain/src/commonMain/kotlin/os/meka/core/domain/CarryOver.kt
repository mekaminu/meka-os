package os.meka.core.domain

/** What Meka chose on the three-carry prompt ([RollOverRules.ASK_DAYS]): each answers it for good. */
enum class CarryChoice {
    /** Opens the task with its steps ready to add; the task stays where it is. */
    BREAK_DOWN,
    /** Moves it to Someday, out of Today. */
    SOMEDAY,
    /** Drops it (cancelled, with Undo). */
    DROP,
    /** Keeps it carrying, and the prompt doesn't come back. */
    KEEP,
}

/**
 * One open task carried over from an earlier day, shown at the top of Today under "From yesterday" (Week board and
 * roll-over, slice 1). [days] is how many days ago it was planned; [line] the grey line under it ("Carried 2 days");
 * [asks] whether the row offers Break it down · Someday · Drop · Keep (once, from [RollOverRules.ASK_DAYS] days).
 */
data class CarriedTask(val task: Task, val days: Long, val line: String, val asks: Boolean)

/**
 * Roll-over (Week board and roll-over, slice 1; Meka approved 2026-10-19 20:36). An open task planned for an earlier day
 * and not done no longer drops out of sight: at the start of each day it leads Today under "From yesterday", oldest
 * first, each saying how long it has carried; today's planned tasks follow as before. Nothing is rewritten: the task
 * keeps its planned day, so the count is read, not stored, and moving it to a day resets it. At [ASK_DAYS] carries
 * Navilife asks once (Break it down · Someday · Drop · Keep); the answer is kept on the task ([TaskFields.CARRY_ASKED_DAY])
 * so it never asks again. Done, dropped, Someday and waiting tasks never carry; nor does a task Needs you already shows,
 * one waiting for its day (a snooze or a later occurrence), or one with no planned day (those are Anytime today). A
 * repeating occurrence carries with its repeat line and is never asked about (its steps are a routine). Non-AI, pure.
 */
object RollOverRules {
    const val LABEL = "From yesterday"
    /** The prompt appears once a task has carried this many days. */
    const val ASK_DAYS = 3L
    const val ASK_LINE = "Still here. Break it down, put it on Someday, or drop it?"
    const val BREAK_DOWN = "Break it down"
    const val SOMEDAY = "Someday"
    const val DROP = "Drop"
    const val KEEP = "Keep"

    /**
     * Today's carried tasks from [tasks] (all of them; the rules pick the open ones), leaving out [shownIds] (Needs you).
     * [today] the local day; [dayOf] reads a local epoch day.
     */
    fun carried(tasks: List<Task>, shownIds: Set<String>, today: DayWindow, dayOf: (Long) -> Long): List<CarriedTask> =
        tasks.asSequence()
            .filter { it.id !in shownIds && carries(it, today) }
            .sortedWith(compareBy<Task> { it.scheduledAtMs }.thenBy { it.createdAtMs }.thenBy { it.id })
            .map { t ->
                val days = (today.epochDay - dayOf(t.scheduledAtMs!!)).coerceAtLeast(1)
                CarriedTask(t, days, line(t, days), asks(t, days))
            }
            .toList()

    /** True when [t] is open, planned for a day before [today] and not waiting for a later day. */
    fun carries(t: Task, today: DayWindow): Boolean =
        (t.lifecycle == Lifecycle.ACTIVE || t.lifecycle == Lifecycle.INBOX) &&
            !t.waitsForItsDay(today.epochDay) &&
            t.scheduledAtMs != null && t.scheduledAtMs < today.startMs

    /** "Carried from yesterday" · "Carried 4 days"; a repeating one adds its repeat ("Carried 2 days · ↻ Every weekday"). */
    fun line(t: Task, days: Long): String {
        val carried = if (days <= 1) "Carried from yesterday" else "Carried $days days"
        return t.repeatLabel?.let { "$carried · ↻ $it" } ?: carried
    }

    /** The prompt shows from [ASK_DAYS] carries on a one-off task not asked about before. */
    fun asks(t: Task, days: Long): Boolean = days >= ASK_DAYS && !t.isRepeating && t.carryAskedDay == null

    /** What the undo bar says after [choice] on [title] (no bar for Break it down or Keep). */
    fun undoLine(choice: CarryChoice, title: String): String? = when (choice) {
        CarryChoice.SOMEDAY -> "Moved “${TaskTitleRules.display(title)}” to Someday"
        CarryChoice.DROP -> "Dropped “${TaskTitleRules.display(title)}”"
        CarryChoice.BREAK_DOWN, CarryChoice.KEEP -> null
    }
}
