package os.meka.core.domain

/**
 * When nothing needs Meka (Fold reviews 2026-10-09, 00:10 item 6 and 07:26 item 10): under "Nothing needs you" the
 * Needs you page (and the open Fold's / wide Mac's middle column) shows what's coming for him instead of a blank page:
 * today's habits to tick inline, who he's waiting on, and the next renewals. Non-AI, pure, unit-tested; both apps read
 * it from the goals and lists views they already have.
 */
data class MeanwhileLine(
    val id: String,
    val title: String,
    /** "Ada · since Mon 5 Oct · chase Thu 8 Oct" · "renews Thu 12 Nov · £412 a year". */
    val meta: String,
)

data class NeedsYouMeanwhile(
    /** Today's habits (done today, due today or behind), in the Goals order; ticked inline. */
    val habits: List<HabitItem>,
    /** Open waiting-for items, at most [NeedsYouMeanwhileRules.MAX_ROWS]. */
    val waiting: List<MeanwhileLine>,
    /** "+2 more" under Waiting on; null when all are shown. */
    val waitingMore: String?,
    /** The next renewals and bills by date, at most [NeedsYouMeanwhileRules.MAX_ROWS]. */
    val renewals: List<MeanwhileLine>,
    val renewalsMore: String?,
) {
    val isEmpty: Boolean get() = habits.isEmpty() && waiting.isEmpty() && renewals.isEmpty()

    /** "1 of 2 done"; null with no habits today. */
    val habitsLine: String? get() = if (habits.isEmpty()) null else "${habits.count { it.doneToday }} of ${habits.size} done"

    /** Which sections show, in order (Habits today · Waiting on · Coming up to renew); for the stagger and the tests. */
    val sections: List<String> get() = listOfNotNull(
        NeedsYouMeanwhileRules.HABITS_LABEL.takeIf { habits.isNotEmpty() },
        NeedsYouMeanwhileRules.WAITING_LABEL.takeIf { waiting.isNotEmpty() },
        NeedsYouMeanwhileRules.RENEWALS_LABEL.takeIf { renewals.isNotEmpty() },
    )

    companion object {
        val EMPTY = NeedsYouMeanwhile(emptyList(), emptyList(), null, emptyList(), null)
    }
}

object NeedsYouMeanwhileRules {
    const val HABITS_LABEL = "Habits today"
    const val WAITING_LABEL = "Waiting on"
    const val RENEWALS_LABEL = "Coming up to renew"
    const val MAX_ROWS = 3

    /** Habits on today's list, the same rule as the Day ring's tile: done today, due today or behind for the week. */
    fun habitsToday(goals: GoalsView): List<HabitItem> =
        goals.habits.filter { it.doneToday || it.pace == HabitPace.DUE || it.pace == HabitPace.BEHIND }

    fun build(goals: GoalsView?, lists: ListsView?): NeedsYouMeanwhile {
        val habits = goals?.let(::habitsToday).orEmpty()
        val waitingAll = lists?.waiting.orEmpty()
        // Due chases and renewals needing attention would already be cards in the stack, so this is mostly what's
        // coming; anything still in attention leads (by its own order), then the upcoming ones by date.
        val renewalsAll = lists?.renewals?.let { it.attention + it.upcoming }.orEmpty()
        return NeedsYouMeanwhile(
            habits = habits,
            waiting = waitingAll.take(MAX_ROWS).map { MeanwhileLine(it.id, it.title, it.meta) },
            waitingMore = more(waitingAll.size),
            renewals = renewalsAll.take(MAX_ROWS).map { MeanwhileLine(it.id, it.title, it.meta) },
            renewalsMore = more(renewalsAll.size),
        )
    }

    /** "+2 more" past [MAX_ROWS]; null otherwise. */
    fun more(total: Int): String? = (total - MAX_ROWS).takeIf { it > 0 }?.let { "+$it more" }

    /** The tick's spoken label: "Tick Stretch for today" / "Untick Stretch for today" (as in Goals). */
    fun tickLabel(h: HabitItem): String = (if (h.doneToday) "Untick " else "Tick ") + h.title + " for today"
}
