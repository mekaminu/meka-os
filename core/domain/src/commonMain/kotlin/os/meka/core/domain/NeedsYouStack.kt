package os.meka.core.domain

/**
 * Needs you as a swipeable stack (four tabs, slice 2). Non-AI, pure: the same cards in the same order on both apps.
 *
 * Every card is one decision with its "why". Three moves: right = yes/do ([DecisionMove.YES]), left = later
 * ([DecisionMove.LATER]), up = open ([DecisionMove.OPEN]). What each move does depends on the card:
 *
 * | Card | Right | Left | Up |
 * |---|---|---|---|
 * | A task edited on two devices | Choose (opens it) | Later (to the back of the stack) | Open |
 * | An overdue task | Done | Tomorrow (snoozed, as in the task detail) | Open |
 * | Due today, no time planned | Done | Tomorrow | Open |
 * | From your lists (chases, reviews, renewals due) | Go through (opens Lists) | Later | Open Lists |
 *
 * "Later" on a card that can't be snoozed only sets it aside for this screen ([NeedsYouStackRules.ordered]); nothing is
 * written. Done and Tomorrow are ordinary task edits (synced, undoable from the undo bar).
 */
enum class DecisionMove { YES, LATER, OPEN }

/** What a move on a card does. The core does the task edits; the apps do the opening and the setting aside. */
enum class DecisionEffect { COMPLETE_TASK, SNOOZE_TASK, OPEN_TASK, OPEN_LISTS, SET_ASIDE }

enum class DecisionKind { CONFLICT, OVERDUE, DUE_TODAY, LISTS }

data class DecisionCard(
    /** The task's id, or [NeedsYouStackRules.LISTS_ID]. */
    val id: String,
    val kind: DecisionKind,
    val title: String,
    /** Why it needs you: "Overdue · was due yesterday 17:00". */
    val why: String,
    /** The task behind the card; null for the lists card. */
    val taskId: String?,
    val yesLabel: String,
    val laterLabel: String,
    val openLabel: String,
    val yes: DecisionEffect,
    val later: DecisionEffect,
    val open: DecisionEffect,
    /** Overdue and conflicting cards are lit in the critical colour. */
    val urgent: Boolean,
) {
    fun effect(move: DecisionMove): DecisionEffect = when (move) {
        DecisionMove.YES -> yes
        DecisionMove.LATER -> later
        DecisionMove.OPEN -> open
    }

    fun label(move: DecisionMove): String = when (move) {
        DecisionMove.YES -> yesLabel
        DecisionMove.LATER -> laterLabel
        DecisionMove.OPEN -> openLabel
    }
}

data class NeedsYouStack(val cards: List<DecisionCard>) {
    val isEmpty: Boolean get() = cards.isEmpty()

    companion object {
        val EMPTY = NeedsYouStack(emptyList())
    }
}

object NeedsYouStackRules {
    const val LISTS_ID = "lists"

    /**
     * The empty state (Fold review 2026-10-08, item 7): a light line beside the breathing check ring, not a bold grey
     * sentence beside an empty circle (which read like an unticked task), and a caption saying what lands here.
     */
    const val EMPTY_LINE = "Nothing needs you"
    const val EMPTY_CAPTION = "Approvals, replies and decisions land here."

    /** Today's Needs you (conflicts, overdue, due today unscheduled, in that order), then the lists card. */
    fun build(today: Today, listsDueLine: String?, nowMs: Long, calendar: LocalCalendar): NeedsYouStack {
        val todayDay = calendar.epochDayOf(nowMs)
        val cards = today.needsYou.map { card(it, todayDay, calendar) }.toMutableList()
        if (listsDueLine != null) {
            cards += DecisionCard(
                id = LISTS_ID, kind = DecisionKind.LISTS, title = "From your lists", why = listsDueLine, taskId = null,
                yesLabel = "Go through", laterLabel = "Later", openLabel = "Open Lists",
                yes = DecisionEffect.OPEN_LISTS, later = DecisionEffect.SET_ASIDE, open = DecisionEffect.OPEN_LISTS,
                urgent = false,
            )
        }
        return NeedsYouStack(cards)
    }

    private fun card(item: NeedsYouItem, todayDay: Long, calendar: LocalCalendar): DecisionCard {
        val t = item.task
        return when (item.reason) {
            NeedsYouReason.CONFLICT -> DecisionCard(
                t.id, DecisionKind.CONFLICT, t.title, "Changed on two devices · choose which to keep", t.id,
                "Choose", "Later", "Open", DecisionEffect.OPEN_TASK, DecisionEffect.SET_ASIDE, DecisionEffect.OPEN_TASK, urgent = true,
            )
            NeedsYouReason.OVERDUE -> DecisionCard(
                t.id, DecisionKind.OVERDUE, t.title, "Overdue · was due ${due(t.dueAtMs, todayDay, calendar)}", t.id,
                "Done", "Tomorrow", "Open", DecisionEffect.COMPLETE_TASK, DecisionEffect.SNOOZE_TASK, DecisionEffect.OPEN_TASK, urgent = true,
            )
            NeedsYouReason.DUE_TODAY_UNSCHEDULED -> DecisionCard(
                t.id, DecisionKind.DUE_TODAY, t.title, "Due today${at(t.dueAtMs, calendar)} · no time planned", t.id,
                "Done", "Tomorrow", "Open", DecisionEffect.COMPLETE_TASK, DecisionEffect.SNOOZE_TASK, DecisionEffect.OPEN_TASK, urgent = false,
            )
        }
    }

    /** "at 14:00" today, "yesterday 17:00", "Mon 5 Oct 17:00"; midnight leaves the time out. */
    private fun due(dueAtMs: Long?, todayDay: Long, calendar: LocalCalendar): String {
        if (dueAtMs == null) return "earlier"
        val day = calendar.epochDayOf(dueAtMs)
        val minute = calendar.minuteOfDay(dueAtMs)
        val time = if (minute == 0) null else LocalClock.formatMinute(minute)
        return when (day) {
            todayDay -> if (time == null) "today" else "at $time"
            todayDay - 1 -> "yesterday" + (time?.let { " $it" } ?: "")
            else -> CivilDate.shortLabel(day) + (time?.let { " $it" } ?: "")
        }
    }

    private fun at(dueAtMs: Long?, calendar: LocalCalendar): String {
        val minute = dueAtMs?.let(calendar::minuteOfDay) ?: return ""
        return if (minute == 0) "" else " at ${LocalClock.formatMinute(minute)}"
    }

    /**
     * The stack as shown: cards set aside on this screen go to the back, in the order they were set aside (the most
     * recent last). Ids no longer in the stack are ignored. Pure.
     */
    fun ordered(stack: NeedsYouStack, setAside: List<String>): List<DecisionCard> {
        val ids = stack.cards.map { it.id }.toSet()
        val aside = setAside.filter { it in ids }.distinct()
        val asideSet = aside.toSet()
        val byId = stack.cards.associateBy { it.id }
        return stack.cards.filter { it.id !in asideSet } + aside.map { byId.getValue(it) }
    }

    /** Under the stack: "3 more waiting" (null when only the top card is left). */
    fun moreLine(shown: Int): String? = when {
        shown <= 1 -> null
        else -> "${shown - 1} more waiting"
    }

    /** The undo bar's message after a move: "Done · Pay council tax", "Tomorrow · Pay council tax". */
    fun message(card: DecisionCard, move: DecisionMove): String = when (card.effect(move)) {
        DecisionEffect.COMPLETE_TASK -> "Done · ${card.title}"
        DecisionEffect.SNOOZE_TASK -> "Tomorrow · ${card.title}"
        DecisionEffect.SET_ASIDE -> "Set aside for later"
        DecisionEffect.OPEN_TASK, DecisionEffect.OPEN_LISTS -> card.title
    }

    /** The hint under the top card: "→ Done · ← Tomorrow · ↑ Open". */
    fun hint(card: DecisionCard): String = "→ ${card.yesLabel} · ← ${card.laterLabel} · ↑ ${card.openLabel}"
}

/**
 * What a Done or Tomorrow from the stack changed, so the undo bar can put it back. [after] is what the move wrote; undo
 * only puts back [before] while the task still is as the move left it (the same rule as the activity log).
 */
data class DecisionUndo(
    val taskId: String,
    val effect: DecisionEffect,
    val before: TaskTiming,
    val after: TaskTiming,
)

/** The fields Done and Tomorrow touch. */
data class TaskTiming(
    val lifecycle: Lifecycle,
    val deferredToDay: Long?,
    val scheduledAtMs: Long?,
    val dueAtMs: Long?,
    val remindAtMs: Long? = null,
) {
    companion object {
        fun of(t: Task) = TaskTiming(t.lifecycle, t.deferredToDay, t.scheduledAtMs, t.dueAtMs, t.remindAtMs)
    }
}
