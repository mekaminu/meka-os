package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Earned trust (build plan V1): per kind of Ask/Talk card, when Meka has said yes to [TrustRules.STREAK] in a row
 * without taking one back, MEKA offers "want me to just do these?". On his yes that kind runs on its own from then on
 * (ADR-006 Level 4 under the rule his yes made, only for a question that carried nothing untrusted), each one shown
 * with Undo and kept in Activity; an Undo of one sends that kind back to asking first.
 *
 * Only MEKA's own lists qualify (adding or ticking off a task, adding to Shopping, keeping a win), and only for a
 * question that carried nothing from other people (no calendar lines, shared links or Jeanette's items, ADR-006):
 * otherwise the card still waits for his tap. Never personal messages, money or trading (those are not Ask cards at all and the policy engine's ceilings
 * hold them at Level 3), and, for now, nothing that reaches a calendar, rings an alarm, starts a fast, books leave or
 * changes a family evening or a renewal date.
 */
enum class TrustKind(val key: String, val label: String, val plural: String) {
    ADD_TASK("add_task", "Add a task", "add tasks"),
    COMPLETE_TASK("tick_off", "Tick off a task", "tick off tasks"),
    ADD_SHOPPING("shopping", "Add to shopping", "add to the shopping list"),
    ADD_WIN("win", "Keep a win", "keep wins"),
    ;

    companion object {
        fun of(key: String): TrustKind? = entries.firstOrNull { it.key == key }
    }
}

/** Where one kind stands: yeses in a row, and whether Meka said "just do it" or "keep asking". */
data class TrustState(val streak: Int = 0, val trusted: Boolean = false, val declined: Boolean = false)

/** The offer after the [TrustRules.STREAK]th yes in a row. */
data class TrustOffer(val kindKey: String, val line: String, val yes: String, val no: String)

/** Something MEKA did on its own under earned trust: the line ("Added “Milk” to shopping · done for you") and its Activity entry. */
data class AskAutoDone(val proposal: AskProposal, val line: String, val activityId: String)

object TrustFields {
    fun streak(k: TrustKind) = "${k.key}.streak"
    fun state(k: TrustKind) = "${k.key}.state"
    const val TRUSTED = "trusted"
    const val DECLINED = "declined"
}

/** Pure rules, unit-tested without a replica. */
object TrustRules {
    /** Yeses in a row before MEKA offers. */
    const val STREAK = 5
    const val YES = "Just do it"
    const val NO = "Keep asking"
    const val DONE_TAIL = " · done for you"

    /** The kind a card belongs to, or null when it can never run on its own. */
    fun kindOf(p: AskProposal): TrustKind? = when (p) {
        is AskProposal.AddTask -> TrustKind.ADD_TASK
        is AskProposal.CompleteTask -> TrustKind.COMPLETE_TASK
        is AskProposal.AddShopping -> TrustKind.ADD_SHOPPING
        is AskProposal.AddWin -> TrustKind.ADD_WIN
        // A saved link's title is someone else's words, so a question about the queue is always untrusted (ADR-006):
        // it could never run on its own, so it isn't offered.
        is AskProposal.QueueDone -> null
        // A move can carry a planned block in Google; an alarm or timer rings the phone; a fast is health; leave is
        // work days; dinner and date night are Jeanette's evening too; a renewal's next date hides a real one if wrong;
        // a fact is what MEKA answers from later, so a misheard one should always be seen first.
        is AskProposal.MoveTask, is AskProposal.StartFast, is AskProposal.Timer, is AskProposal.Alarm,
        is AskProposal.PlanDinner, is AskProposal.SkipDateNight, is AskProposal.RenewalDone, is AskProposal.BookLeave,
        is AskProposal.AddFact -> null
        // Errands (slice 2b) aren't an earned-trust kind yet: a misheard place would wait somewhere Meka never goes.
        is AskProposal.AddErrand -> null
        // Checklist cards (Checklists slice 3b) aren't an earned-trust kind yet: a misheard task name would add to the wrong list.
        is AskProposal.AddChecklist -> null
        // A goal (Goals slice 8) is something Meka works towards for weeks, so he always sees it before it's added.
        is AskProposal.AddGoal -> null
        // Skipping the life admin hour (slice 3) frees an hour Meka chose to keep, so he always sees it first.
        is AskProposal.SkipLifeAdmin -> null
        // A gift idea (Gift planner slice 2) is Meka's own note about someone, so he always sees it before it's kept.
        is AskProposal.AddGiftIdea -> null
        // A promise (People slice 3) is Meka's word to someone, so he always sees it before it's kept.
        is AskProposal.AddPromise -> null
    }

    fun read(fields: Map<String, FieldValue>?): Map<TrustKind, TrustState> = TrustKind.entries.associateWith { k ->
        val state = (fields?.get(TrustFields.state(k)) as? FieldValue.Text)?.value
        TrustState(
            streak = ((fields?.get(TrustFields.streak(k)) as? FieldValue.Int64)?.value ?: 0L).toInt().coerceAtLeast(0),
            trusted = state == TrustFields.TRUSTED,
            declined = state == TrustFields.DECLINED,
        )
    }

    /** After a yes (a tap or a spoken yes): one more in a row. Nothing changes once he has answered the offer. */
    fun approved(s: TrustState): TrustState = if (s.trusted || s.declined) s else s.copy(streak = (s.streak + 1).coerceAtMost(STREAK))

    /** After an Undo of a card he said yes to: the run starts again. */
    fun takenBack(s: TrustState): TrustState = if (s.trusted || s.declined) s else s.copy(streak = 0)

    /** After an Undo of something MEKA did on its own: back to asking first, and trust has to be earned again. */
    fun autoUndone(s: TrustState): TrustState = TrustState()

    /** The offer, once the run reaches [STREAK] and he hasn't answered it. */
    fun offer(k: TrustKind, s: TrustState): TrustOffer? =
        if (s.trusted || s.declined || s.streak < STREAK) null
        else TrustOffer(
            k.key,
            "That's $STREAK “${k.label}” cards in a row you've said yes to. Want MEKA to just ${k.plural} from now on? " +
                "Each one shows here with Undo and in Activity; a question that carries other people's words (an " +
                "invitation on your calendar, a shared link) still asks first.",
            YES, NO,
        )

    fun answered(yes: Boolean): TrustState = if (yes) TrustState(STREAK, trusted = true) else TrustState(STREAK, declined = true)

    fun answerLine(k: TrustKind, yes: Boolean): String =
        if (yes) "MEKA will ${k.plural} without asking. Undo any one to go back to asking."
        else "MEKA will keep asking before it ${k.plural.replaceFirst(" ", "s ")}."

    fun autoUndoneLine(k: TrustKind, outcome: UndoOutcome): String =
        outcome.line + ". MEKA will ask before it ${k.plural.replaceFirst(" ", "s ")} again."

    /** The Ask answer's line for something done on its own. */
    fun autoLine(doneLine: String): String = doneLine + DONE_TAIL

    /** Activity's "why" for it. */
    fun why(k: TrustKind): String = "You said “just do it” for “${k.label}” after $STREAK in a row"

    fun source(k: TrustKind): String = "trust:${k.key}"

    /** The policy rule his yes made (ADR-006: Level 4 needs an explicit rule). */
    fun ruleId(k: TrustKind): String = "trust.${k.key}"

    /** Fields to write for [k] moving from [before] to [after] (nothing when unchanged). */
    fun fields(k: TrustKind, before: TrustState, after: TrustState): Map<String, FieldValue> = buildMap {
        if (after.streak != before.streak) put(TrustFields.streak(k), after.streak.toLong().fv())
        val s = when { after.trusted -> TrustFields.TRUSTED; after.declined -> TrustFields.DECLINED; else -> "" }
        val b = when { before.trusted -> TrustFields.TRUSTED; before.declined -> TrustFields.DECLINED; else -> "" }
        if (s != b) put(TrustFields.state(k), s.fv())
    }
}

/** Earned trust on a replica: one synced settings entity, so a yes on the Fold holds on the Mac too. */
class EarnedTrust(private val replica: Replica) {
    fun states(): Map<TrustKind, TrustState> = TrustRules.read(replica.entity(EntityTypes.CONTEXT_MODE, SETTINGS_ID)?.fields)

    fun state(k: TrustKind): TrustState = states().getValue(k)

    fun trusted(): Set<TrustKind> = states().filterValues { it.trusted }.keys

    private fun update(k: TrustKind, f: (TrustState) -> TrustState): TrustState {
        val before = state(k)
        val after = f(before)
        val fields = TrustRules.fields(k, before, after)
        if (fields.isNotEmpty()) replica.commitLocal(EntityTypes.CONTEXT_MODE, SETTINGS_ID, fields)
        return after
    }

    /** A yes to a card of kind [k]; the offer when this yes completes the run. */
    fun approved(k: TrustKind): TrustOffer? = TrustRules.offer(k, update(k, TrustRules::approved))

    fun takenBack(k: TrustKind) { update(k, TrustRules::takenBack) }

    fun autoUndone(k: TrustKind) { update(k, TrustRules::autoUndone) }

    fun answer(k: TrustKind, yes: Boolean) { update(k) { TrustRules.answered(yes) } }

    /**
     * "What MEKA may do" (policy engine UI): back to the start for [k] — a trusted kind asks first again and a "keep
     * asking" may be offered again after another run of yeses. Returns the state before, or null when there was nothing
     * to reset (still earning).
     */
    fun reset(k: TrustKind): TrustState? {
        val before = state(k)
        if (!before.trusted && !before.declined) return null
        update(k, TrustRules::autoUndone)
        return before
    }

    companion object {
        const val SETTINGS_ID = "trust"
    }
}
