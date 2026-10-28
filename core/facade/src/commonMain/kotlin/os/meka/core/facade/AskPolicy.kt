package os.meka.core.facade

import os.meka.core.domain.AskProposal
import os.meka.core.domain.TrustKind
import os.meka.core.domain.TrustRules
import os.meka.core.policy.ActionRequest
import os.meka.core.policy.ActionType
import os.meka.core.policy.AutonomyLevel
import os.meka.core.policy.PolicyConfig
import os.meka.core.policy.PolicyDecision
import os.meka.core.policy.PolicyDomain
import os.meka.core.policy.PolicyEngine
import os.meka.core.policy.Provenance

/**
 * The policy gate every action Ask, Talk, Hello and the companion propose goes through (ADR-006; the companion,
 * slice 6). A model's proposal is a suggestion: the policy engine decides, outside the model, whether it may be
 * shown as a card (always waiting for Meka's tap) and whether it may ever run on its own under a "just do it" rule.
 * Nothing a model proposes can send a message or an email, pay, trade or share a document: those action types are
 * refused here whatever the mapping says, so a future proposal kind can't reach them by accident.
 */
object AskPolicy {
    /** Action types no model proposal may ever lead to, card or not. */
    val NEVER: Set<ActionType> = setOf(
        ActionType.SEND_EMAIL, ActionType.SEND_PERSONAL_MESSAGE, ActionType.FINANCIAL_TRANSACTION,
        ActionType.TRADING_APPROVAL, ActionType.SHARE_DOCUMENT, ActionType.ARCHIVE_EMAIL, ActionType.LABEL_EMAIL,
    )

    /** May [p] be shown as a card he taps? Never Permit (that would run it untapped), never a [NEVER] type. */
    fun allowed(p: AskProposal, untrusted: Boolean): Boolean {
        val (type, domain) = action(p)
        if (type in NEVER) return false
        val decision = PolicyEngine(PolicyConfig()).decide(
            ActionRequest(
                type, domain,
                provenance = provenance(untrusted),
                reversible = true,
                requestedLevel = AutonomyLevel.SUGGEST,
            ),
        )
        return decision !is PolicyDecision.Deny && decision !is PolicyDecision.Permit
    }

    /**
     * Earned trust: may [p] run on its own? Only when the policy engine permits Level 4 under the explicit rule Meka's
     * "just do it" made for [kind] (reversible, an allowlisted rule, never from untrusted provenance), the kill switch
     * is off ([paused]) and [kind] is the proposal's own kind.
     */
    fun auto(p: AskProposal, kind: TrustKind, untrusted: Boolean, paused: Boolean): Boolean {
        if (TrustRules.kindOf(p) != kind) return false
        val (type, domain) = action(p)
        if (type in NEVER || type.external) return false
        val rule = TrustRules.ruleId(kind)
        // The kill switch ("What MEKA may do" → Pause) holds every kind at asking first.
        val config = PolicyConfig(
            levels = mapOf((domain to type) to AutonomyLevel.AUTO_UNDER_RULE), autoRules = mapOf(rule to type),
            killSwitch = paused,
        )
        val decision = PolicyEngine(config).decide(
            ActionRequest(
                type, domain,
                provenance = provenance(untrusted),
                reversible = true,
                requestedLevel = AutonomyLevel.AUTO_UNDER_RULE,
                ruleId = rule,
            ),
        )
        return decision is PolicyDecision.Permit
    }

    private fun provenance(untrusted: Boolean) =
        if (untrusted) Provenance.MODEL_FROM_UNTRUSTED else Provenance.MODEL_FROM_TRUSTED

    /** What [p] does, as the policy engine sees it. Exhaustive: a new proposal kind must say what it is. */
    fun action(p: AskProposal): Pair<ActionType, PolicyDomain> = when (p) {
        is AskProposal.AddTask -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        is AskProposal.CompleteTask, is AskProposal.MoveTask -> ActionType.RESCHEDULE_ITEM to PolicyDomain.TASKS
        // Changes an event in Google or Outlook: always a card he taps (never earned trust, TrustRules.kindOf).
        is AskProposal.MoveEvent -> ActionType.CREATE_CALENDAR_EVENT to PolicyDomain.CALENDAR
        is AskProposal.StartFast -> ActionType.CREATE_TASK to PolicyDomain.HEALTH
        is AskProposal.Timer, is AskProposal.Alarm -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // Meka's own list (his wife sees it, but adding to it sends nothing to anyone).
        is AskProposal.AddShopping -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // The week's dinners: Meka's own plan (nothing is sent or bought).
        is AskProposal.PlanDinner -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // Meka's own evening setting (nothing is booked, cancelled or sent).
        is AskProposal.SkipDateNight -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // Meka's own weekly hour (nothing is booked, cancelled or sent).
        is AskProposal.SkipLifeAdmin -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // A note in Meka's own Gifts (nothing is ordered, bought or sent).
        is AskProposal.AddGiftIdea -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // A promise in Meka's own Lists (nothing is sent to anyone).
        is AskProposal.AddPromise -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // A booking in Meka's own trip (nothing is booked, paid or sent).
        is AskProposal.AddBooking -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // Things on Meka's own trip lists, To buy also on his shopping list (nothing is bought, booked or sent).
        is AskProposal.AddTripItems -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // Marks Meka's own radar item done, like its button (nothing is paid, booked or sent).
        is AskProposal.RenewalDone -> ActionType.RESCHEDULE_ITEM to PolicyDomain.TASKS
        // Keeps a line in Meka's own wins log (nothing is sent to anyone).
        is AskProposal.AddWin -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // Marks one of Meka's own saved links read, like its Done button (nothing is opened, fetched or sent).
        is AskProposal.QueueDone -> ActionType.RESCHEDULE_ITEM to PolicyDomain.TASKS
        // Marks days off in Meka's own planner, like the Leave pane (nothing is sent to his employer or anyone).
        is AskProposal.BookLeave -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // Keeps a fact on Meka's own page (nothing is sent to anyone).
        is AskProposal.AddFact -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // Adds to Meka's own errands list (nothing is sent; where a place is stays on the Fold).
        is AskProposal.AddErrand -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // Adds to one of Meka's own task checklists (nothing is sent to anyone).
        is AskProposal.AddChecklist -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // Adds one of Meka's own goals (nothing is sent to anyone).
        is AskProposal.AddGoal -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // Changes Meka's own reading list, like its buttons (nothing is fetched, bought or sent).
        is AskProposal.ReadingUpdate -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // His own name in his own About you, like typing it there (nothing is sent to anyone).
        is AskProposal.SetName -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        // Ticks or plans lessons in Meka's own Study plan, like its buttons (nothing is sent to anyone).
        is AskProposal.StudyTick, is AskProposal.StudyPlan -> ActionType.RESCHEDULE_ITEM to PolicyDomain.TASKS
    }
}
