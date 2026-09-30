package os.meka.core.domain

import os.meka.core.sync.DELETED_FIELD
import os.meka.core.sync.FieldValue
import os.meka.core.sync.MergePolicy
import os.meka.core.sync.SchemaRegistry

/** Entity type names are part of the wire format: never rename, only add. */
object EntityTypes {
    const val HOUSEHOLD = "household"
    const val PERSON = "person"
    const val TASK = "task"
    const val CHECKLIST_ITEM = "checklist_item"
    const val COMMITMENT = "commitment" // direction OWED_BY_ME | OWED_TO_ME (WaitingFor)
    const val OBLIGATION = "obligation"
    const val DECISION = "decision"
    const val GOAL = "goal"
    const val HABIT = "habit"
    const val HABIT_COMPLETION = "habit_completion"
    const val EVENT = "event"
    const val RELATION = "relation"
    const val CONTEXT_MODE = "context_mode"

    val ALL = listOf(
        HOUSEHOLD, PERSON, TASK, CHECKLIST_ITEM, COMMITMENT, OBLIGATION, DECISION,
        GOAL, HABIT, HABIT_COMPLETION, EVENT, RELATION, CONTEXT_MODE,
    )
}

enum class Lifecycle { INBOX, ACTIVE, SOMEDAY, WAITING, DONE, CANCELLED;
    val isTerminal: Boolean get() = this == DONE || this == CANCELLED
    /** Someday, done and cancelled items never consume planning capacity (owner amendment 9). */
    val consumesCapacity: Boolean get() = this == INBOX || this == ACTIVE || this == WAITING
}

enum class SomedayKind { IDEA, PURCHASE, PROJECT, TRIP, BOOK, RESEARCH, APPLICATION, HOME_IMPROVEMENT, OTHER }
enum class CommitmentDirection { OWED_BY_ME, OWED_TO_ME }
enum class GoalHorizon { SHORT, MEDIUM, LONG }
enum class DecisionStatus { ACTIVE, REVISITING, SUPERSEDED }
enum class Visibility { HOUSEHOLD, PRIVATE }
enum class MembershipRole { OWNER, ADULT, CHILD_PROFILE }
enum class Trust { TRUSTED_USER, DERIVED, UNTRUSTED_EXTERNAL }

/** Field names shared by all actionable primitives (ADR-008). */
object ActionableFields {
    const val TITLE = "title"
    const val NOTES = "notes"
    const val LIFECYCLE = "lifecycle"
    const val OWNER_PERSON_ID = "ownerPersonId"
    const val DUE_AT = "dueAtMs"
    const val PRIORITY = "priority"
    const val GOAL_ID = "goalId"
    const val SOMEDAY_KIND = "somedayKind"
    const val VISIBILITY = "visibility"
    const val PROVENANCE_SOURCE = "provenanceSource"
    const val PROVENANCE_TRUST = "provenanceTrust"
    const val CREATED_AT = "createdAtMs"
    const val COMPLETED_AT = "completedAtMs"
    const val DELETED = DELETED_FIELD
}

object TaskFields {
    const val SCHEDULED_AT = "scheduledAtMs"
    const val ESTIMATE_MINUTES = "estimateMinutes"
    const val CONTEXT_MODE = "contextMode"
}

object ChecklistFields {
    const val TASK_ID = "taskId"
    const val TEXT = "text"
    const val CHECKED = "checked"
    const val POSITION = "position"
}

object CommitmentFields {
    const val DIRECTION = "direction"
    const val COUNTERPARTY_PERSON_ID = "counterpartyPersonId"
    const val COUNTERPARTY_LABEL = "counterpartyLabel"
    const val EXPECTED_AT = "expectedAtMs"
    const val LAST_INTERACTION_AT = "lastInteractionAtMs"
    const val FOLLOW_UP_AT = "followUpAtMs"
    const val CONFIDENCE = "confidencePct"
    const val SOURCE_REF = "sourceRef"
}

object DecisionFields {
    const val STATEMENT = "statement"
    const val DECIDED_AT = "decidedAtMs"
    const val RATIONALE = "rationale"
    const val CONTEXT = "context"
    const val RELATED_GOAL_IDS = "relatedGoalIds" // comma-separated ids; relations are also written as RELATION entities
    const val RELATED_PERSON_IDS = "relatedPersonIds"
    const val REVIEW_AT = "reviewAtMs"
    const val STATUS = "status"
    const val SUPERSEDES = "supersedesDecisionId"
}

object GoalFields {
    const val TARGET = "target"
    const val HORIZON = "horizon"
    const val PROGRESS_PCT = "progressPct"
}

object HabitFields {
    const val TARGET_PER_WEEK = "targetPerWeek"
    const val PREFERRED_TIMING = "preferredTiming"
}

object PersonFields {
    const val DISPLAY_NAME = "displayName"
    const val RELATIONSHIP = "relationship" // SELF, SPOUSE, CHILD, FRIEND, COACH ...
    const val IS_PRINCIPAL = "isPrincipal"
}

object HouseholdFields {
    const val NAME = "name"
    const val TIMEZONE = "timezone"
}

/**
 * Merge policy per field (ADR-003). Anything that a person would be surprised to see silently
 * overwritten is [MergePolicy.UserVisible]; bookkeeping is [MergePolicy.Lww].
 */
object MekaSchema : SchemaRegistry {
    private val lifecycleTerminal = MergePolicy.TerminalWins(
        setOf(FieldValue.Text(Lifecycle.DONE.name), FieldValue.Text(Lifecycle.CANCELLED.name)),
    )

    private val userVisible = setOf(
        ActionableFields.TITLE, ActionableFields.DUE_AT, TaskFields.SCHEDULED_AT,
        ChecklistFields.TEXT, DecisionFields.STATEMENT, DecisionFields.REVIEW_AT,
        CommitmentFields.EXPECTED_AT, CommitmentFields.FOLLOW_UP_AT, GoalFields.TARGET,
        PersonFields.DISPLAY_NAME, HouseholdFields.NAME,
    )

    override fun policyFor(entityType: String, field: String): MergePolicy = when {
        field == ActionableFields.DELETED -> MergePolicy.TrueWins
        field == ActionableFields.LIFECYCLE -> lifecycleTerminal
        entityType == EntityTypes.CHECKLIST_ITEM && field == ChecklistFields.CHECKED -> MergePolicy.TrueWins
        field in userVisible -> MergePolicy.UserVisible
        else -> MergePolicy.Lww
    }
}
