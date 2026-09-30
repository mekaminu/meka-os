package os.meka.core.policy

/** ADR-006 autonomy levels. Ordinal order matters: higher = more autonomous. */
enum class AutonomyLevel { OBSERVE, SUGGEST, PREPARE, ASK_THEN_EXECUTE, AUTO_UNDER_RULE }

enum class PolicyDomain { TASKS, CALENDAR, EMAIL, MESSAGING, FAMILY, HEALTH, FIXTURES, NEWS, FINANCE, TRADING, HOME, DOCUMENTS }

/** What an action does to the outside world. Anything not listed here cannot be executed at all. */
enum class ActionType(val external: Boolean) {
    CREATE_TASK(false),
    RESCHEDULE_ITEM(false),
    ARCHIVE_EMAIL(true),
    LABEL_EMAIL(true),
    SEND_EMAIL(true),
    SEND_PERSONAL_MESSAGE(true),
    CREATE_CALENDAR_EVENT(true),
    FINANCIAL_TRANSACTION(true),
    TRADING_APPROVAL(true),
    SHARE_DOCUMENT(true),
}

enum class Provenance { USER, DETERMINISTIC_RULE, MODEL_FROM_TRUSTED, MODEL_FROM_UNTRUSTED }

data class ActionRequest(
    val type: ActionType,
    val domain: PolicyDomain,
    val provenance: Provenance,
    val reversible: Boolean,
    /** The autonomy level the proposer asks for; the engine may only lower it. */
    val requestedLevel: AutonomyLevel = AutonomyLevel.AUTO_UNDER_RULE,
    /** Id of an explicit Level-4 rule the user created, if the proposer claims one applies. */
    val ruleId: String? = null,
)

sealed class PolicyDecision {
    data class Permit(val level: AutonomyLevel) : PolicyDecision()
    data class RequireApproval(val reason: String, val biometric: Boolean) : PolicyDecision()
    data class PrepareOnly(val reason: String) : PolicyDecision()
    data class SuggestOnly(val reason: String) : PolicyDecision()
    data class Deny(val reason: String) : PolicyDecision()
}

data class PolicyConfig(
    /** User-chosen level per (domain, action). Missing = [defaultLevel]. */
    val levels: Map<Pair<PolicyDomain, ActionType>, AutonomyLevel> = emptyMap(),
    val defaultLevel: AutonomyLevel = AutonomyLevel.SUGGEST,
    /** Rules the user explicitly created that permit Level 4 for an action type. */
    val autoRules: Map<String, ActionType> = emptyMap(),
    val killSwitch: Boolean = false,
    val dailyCaps: Map<ActionType, Int> = mapOf(ActionType.ARCHIVE_EMAIL to 200, ActionType.LABEL_EMAIL to 200),
)

/**
 * Deterministic policy (ADR-006). Models propose; only this decides. Hard ceilings below cannot be raised by
 * configuration, and nothing derived from untrusted content can raise its own autonomy.
 */
class PolicyEngine(private val config: PolicyConfig) {

    fun decide(req: ActionRequest, executedToday: Int = 0): PolicyDecision {
        val configured = config.levels[req.domain to req.type] ?: config.defaultLevel
        var level = minOf(configured, req.requestedLevel, hardCeiling(req.type))

        // Untrusted provenance: the proposal may be prepared and surfaced, never auto-executed.
        if (req.provenance == Provenance.MODEL_FROM_UNTRUSTED) level = minOf(level, AutonomyLevel.ASK_THEN_EXECUTE)

        // Level 4 additionally requires reversibility and a matching explicit user rule.
        if (level == AutonomyLevel.AUTO_UNDER_RULE) {
            val ruleOk = req.ruleId != null && config.autoRules[req.ruleId] == req.type
            if (!req.reversible || !ruleOk) level = AutonomyLevel.ASK_THEN_EXECUTE
        }

        if (config.killSwitch && level == AutonomyLevel.AUTO_UNDER_RULE) {
            return PolicyDecision.RequireApproval("Automation is paused", biometric = needsBiometric(req.type))
        }
        if (level == AutonomyLevel.AUTO_UNDER_RULE) {
            val cap = config.dailyCaps[req.type]
            if (cap != null && executedToday >= cap) {
                return PolicyDecision.RequireApproval("Daily limit of $cap reached", biometric = false)
            }
        }

        return when (level) {
            AutonomyLevel.OBSERVE -> PolicyDecision.Deny("Observe-only for ${req.domain}")
            AutonomyLevel.SUGGEST -> PolicyDecision.SuggestOnly("Suggestions only for ${req.domain}")
            AutonomyLevel.PREPARE -> PolicyDecision.PrepareOnly("Prepare only for ${req.domain}")
            AutonomyLevel.ASK_THEN_EXECUTE -> PolicyDecision.RequireApproval(
                "Needs your approval", biometric = needsBiometric(req.type),
            )
            AutonomyLevel.AUTO_UNDER_RULE -> PolicyDecision.Permit(level)
        }
    }

    private fun needsBiometric(type: ActionType) =
        type == ActionType.FINANCIAL_TRANSACTION || type == ActionType.TRADING_APPROVAL || type == ActionType.SHARE_DOCUMENT

    companion object {
        /** Ceilings that no configuration can exceed. */
        fun hardCeiling(type: ActionType): AutonomyLevel = when (type) {
            ActionType.SEND_PERSONAL_MESSAGE, ActionType.SEND_EMAIL -> AutonomyLevel.ASK_THEN_EXECUTE
            ActionType.FINANCIAL_TRANSACTION, ActionType.TRADING_APPROVAL, ActionType.SHARE_DOCUMENT -> AutonomyLevel.ASK_THEN_EXECUTE
            else -> AutonomyLevel.AUTO_UNDER_RULE
        }
    }
}
