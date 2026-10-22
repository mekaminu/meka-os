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
 * | The call assistant's credit low or paused ([CallCreditRules.card]) | Top up (opens the billing page) | Later | Open Twilio |
 * | An account's backlog clean-up ready ([MailBacklogRules.cards]) | Approve (synced; Undo while nothing moved) | Later | Review (Mail) |
 * | An account's Empty Junk ([MailJunkClearRules.card]) | Approve | Later | Review (its own sheet: who, per-sender ticks, Approve) |
 *
 * "Later" on a card that can't be snoozed only sets it aside for this screen ([NeedsYouStackRules.ordered]); nothing is
 * written. Done and Tomorrow are ordinary task edits (synced, undoable from the undo bar).
 */
enum class DecisionMove { YES, LATER, OPEN }

/** What a move on a card does. The core does the task edits; the apps do the opening and the setting aside. */
enum class DecisionEffect { COMPLETE_TASK, SNOOZE_TASK, OPEN_TASK, OPEN_LISTS, SET_ASIDE,
    /** Opens [DecisionCard.link] in the browser (the call assistant's Top up). */
    OPEN_LINK,
    /** Inbox fixes, slice 2: approves the backlog clean-up whose `mail_backlog` id is the card's id. */
    APPROVE_BACKLOG,
    /** Inbox fixes, slice 2: opens Mail (Ask → Settings → Mail). */
    OPEN_MAIL,
    /**
     * Review opens its own list (Meka, 2026-10-17): opens the card's own review sheet — what would move, grouped by
     * sender, with a tick per sender and Approve inside ([MailJunkReviewRules.review] for Empty Junk).
     */
    OPEN_REVIEW,
    /** Clean-ups (2026-10-19): Allow on a new phone or Mac asking to join ([DeviceJoinRules]); the card's id is the ask. */
    ALLOW_DEVICE,
    /** Not this one on a new device's ask: the server refuses it. */
    REFUSE_DEVICE,
    /** Week board item (6) part 2: opens Plan your week (Calendar → Board's guided flow); nothing moves until Accept. */
    OPEN_PLAN_WEEK }

enum class DecisionKind { CONFLICT, OVERDUE, DUE_TODAY, LISTS, CREDIT, MAIL_CLEANUP, DEVICE_JOIN, PLAN_WEEK }

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
    /** Where [DecisionEffect.OPEN_LINK] goes; null for every other card. */
    val link: String? = null,
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
    const val NOTHING_TO_DECIDE = "Nothing to decide"

    /**
     * The empty line with messages still waiting (Fold review 2026-10-12 17:56, item 3: "Nothing needs you" right under
     * 9 unread messages read as a contradiction): "Nothing to decide · 9 messages to catch up on"; [EMPTY_LINE] when
     * none are waiting.
     */
    fun emptyLine(toCatchUp: Int): String =
        if (toCatchUp <= 0) EMPTY_LINE
        else "$NOTHING_TO_DECIDE · $toCatchUp ${if (toCatchUp == 1) "message" else "messages"} to catch up on"

    /**
     * Today's Needs you (conflicts, overdue, due today unscheduled, in that order), then the lists card. A lit [credit]
     * card (the call assistant paused or under £2) goes first; a merely low one after the tasks.
     */
    fun build(
        today: Today, listsDueLine: String?, nowMs: Long, calendar: LocalCalendar, credit: DecisionCard? = null,
        mail: List<DecisionCard> = emptyList(),
        devices: List<DecisionCard> = emptyList(),
    ): NeedsYouStack {
        val todayDay = calendar.epochDayOf(nowMs)
        val cards = today.needsYou.map { card(it, todayDay, calendar) }.toMutableList()
        if (credit != null) { if (credit.urgent) cards.add(0, credit) else cards += credit }
        // Clean-ups (2026-10-19): a new device asking to join goes on top; its ask runs out in minutes.
        cards.addAll(0, devices)
        // Inbox fixes, slice 2: each account's backlog clean-up waiting for Approve, after the tasks.
        cards += mail
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

    /**
     * [stack] with Sunday's Plan your week card ([PlanWeekNudgeRules.card]) after the task and credit cards, before the
     * mail clean-ups and the lists card; any earlier one replaced, none when [card] is null.
     */
    fun withPlanWeek(stack: NeedsYouStack, card: DecisionCard?): NeedsYouStack {
        val rest = stack.cards.filter { it.kind != DecisionKind.PLAN_WEEK }
        if (card == null) return if (rest.size == stack.cards.size) stack else NeedsYouStack(rest)
        val at = rest.indexOfFirst { it.kind == DecisionKind.MAIL_CLEANUP || it.kind == DecisionKind.LISTS }.let { if (it < 0) rest.size else it }
        return NeedsYouStack(rest.take(at) + card + rest.drop(at))
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
        DecisionEffect.APPROVE_BACKLOG -> MailBacklogRules.APPROVED_BAR
        DecisionEffect.ALLOW_DEVICE -> DeviceJoinRules.ALLOWED_BAR
        DecisionEffect.REFUSE_DEVICE -> DeviceJoinRules.REFUSED_BAR
        DecisionEffect.OPEN_TASK, DecisionEffect.OPEN_LISTS, DecisionEffect.OPEN_LINK, DecisionEffect.OPEN_MAIL, DecisionEffect.OPEN_REVIEW,
        DecisionEffect.OPEN_PLAN_WEEK -> card.title
    }

    /** The three buttons under the top card, in order: "← Tomorrow" · "↑ Open" · "Done →". */
    fun buttonLabels(card: DecisionCard): List<String> = listOf("← ${card.laterLabel}", "↑ ${card.openLabel}", "${card.yesLabel} →")

    /**
     * How the buttons share the card's width: by their words (each label's length plus [BUTTON_PAD] for its sides), so
     * "← Tomorrow" gets more than "↑ Open" (the quality gate's Needs you shots, 2026-10-22: in equal thirds on the
     * cover screen "← Tomorrow" was cut to "← Tomorr…").
     */
    fun buttonWeights(labels: List<String>): List<Float> = labels.map { it.length + BUTTON_PAD }

    /** A button's sides (8 dp each, about a character each at the label's size), counted in characters. */
    const val BUTTON_PAD = 2f

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
