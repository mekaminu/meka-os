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
    /** One fast (start, goal, end). */
    const val FAST = "fast"
    /** The usual goal and eating window; one entity, id `default`. */
    const val FASTING_PLAN = "fasting_plan"
    /** A news headline mirrored by the server (one per topic and slot, see [News]). */
    const val HEADLINE = "headline"
    /** How many notifications one device posted at an interrupting tier on one day (see [Interruptions]). */
    const val INTERRUPTION_DAY = "interruption_day"
    /** One thing MEKA did on its own, with why and, for a change, what it was before (see [ActivityLog]). */
    const val AGENT_ACTION = "agent_action"
    /** MEKA-only marks on a mirrored calendar event (hidden from my day); id = the event's id (see [EventActions]). */
    const val EVENT_MARK = "event_mark"
    /** MEKA-only marks on a calendar (hidden from Today); id from the calendar's key (see [CalendarRules]). */
    const val CALENDAR_MARK = "calendar_mark"
    /** A message or missed call the Fold held during work mode (after-work summary); id from the capture's id. */
    const val HELD_MESSAGE = "held_message"
    /** An alarm (Alarms): the wake alarm for one morning, id `wake.d<epochDay>` (see [AlarmRules]). */
    const val ALARM = "alarm"
    /** A change Meka made to a real calendar event, sent by the server after the undo window (see [CalendarEdits]). */
    const val EVENT_EDIT = "event_edit"
    /** A Needs you card MEKA proposed from a watched person's message (see [RequestCards]); id from the card's id. */
    const val REQUEST_CARD = "request_card"
    /** A message the messages assistant triaged (lane, gist, draft; never the text, see [TriageCards]); id from the message's id. */
    const val TRIAGE_CARD = "triage_card"
    /** One busy group's digest card for one slot (count, who wrote, the AI's gist; never the messages, see [GroupGists]). */
    const val GROUP_GIST = "group_gist"
    /** A number on the call block list (spam protection); id the number's key (see [BlockedCallers]). */
    const val BLOCKED_CALLER = "blocked_caller"
    /** One thing on the shared shopping list (see [Shopping]); Got is a field, never a delete. */
    const val SHOPPING_ITEM = "shopping_item"
    /** A ground Meka drives to for football, with the travel time he last set there (see [FootballRules]). */
    const val VENUE = "venue"

    val ALL = listOf(
        HOUSEHOLD, PERSON, TASK, CHECKLIST_ITEM, COMMITMENT, OBLIGATION, DECISION,
        GOAL, HABIT, HABIT_COMPLETION, EVENT, RELATION, CONTEXT_MODE, FAST, FASTING_PLAN, HEADLINE, INTERRUPTION_DAY,
        AGENT_ACTION, EVENT_MARK, CALENDAR_MARK, HELD_MESSAGE, ALARM, EVENT_EDIT, REQUEST_CARD,
        TRIAGE_CARD, GROUP_GIST, BLOCKED_CALLER, SHOPPING_ITEM, VENUE,
    )
}

enum class Lifecycle { INBOX, ACTIVE, SOMEDAY, WAITING, DONE, CANCELLED;
    val isTerminal: Boolean get() = this == DONE || this == CANCELLED
    /** Someday, done and cancelled items never consume planning capacity (owner amendment 9). */
    val consumesCapacity: Boolean get() = this == INBOX || this == ACTIVE || this == WAITING
}

enum class SomedayKind { IDEA, PURCHASE, PROJECT, TRIP, BOOK, RESEARCH, APPLICATION, HOME_IMPROVEMENT, OTHER }
enum class CommitmentDirection { OWED_BY_ME, OWED_TO_ME }
enum class ObligationKind { MOT, CAR_TAX, INSURANCE, BOILER, SUBSCRIPTION, BILL, LICENCE, WARRANTY, OTHER }
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
    /** Repeat rule (iCalendar RRULE subset, see [Recurrence]); Null when the task doesn't repeat. */
    const val RECURRENCE = "recurrence"
    /** The series this occurrence belongs to (the id of the task the repeat was first set on). */
    const val SERIES_ID = "seriesId"
    /** The local day (epoch day) this occurrence belongs to in its series. Never changed by a snooze. */
    const val OCCURRENCE_DAY = "occurrenceDay"
    /** A snoozed occurrence waits until this local day (epoch day) before it shows again. */
    const val DEFERRED_TO_DAY = "deferredToDay"
    /** A prep task's calendar event (calendar actions): the event it prepares for. */
    const val EVENT_ID = "eventId"
    /** Remind me (task detail): when to remind, epoch ms; Null for no reminder. Moves with the task's When. */
    const val REMIND_AT = "remindAtMs"
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

/**
 * An obligation (renewals and bills radar): a dated, often repeating duty. The due date is `dueAtMs` (stored as
 * 09:00 local on its day, like chase dates). Fields added 2026-10-06; never rename, only add.
 */
object ObligationFields {
    /** [ObligationKind] name. */
    const val KIND = "kind"
    /** What it's for ("Golf AB12 CDE", "Home"); free text until People/Vault entities can be referenced. */
    const val SUBJECT_LABEL = "subjectLabel"
    /** Repeat rule (the same RRULE subset as tasks, see [Recurrence]); Null for a one-off. */
    const val RECURRENCE = "recurrence"
    /** Cost per occurrence in pence; Null when not known. */
    const val COST_MINOR = "costMinor"
    /** ISO currency code of [COST_MINOR]; GBP for now. */
    const val CURRENCY = "currency"
    /** How many days before the due date MEKA starts showing it. */
    const val LEAD_DAYS = "leadDays"
    /** Last day to cancel or switch before it renews (09:00 local on that day); Null when there is none. */
    const val CANCEL_BY_AT = "cancelByAtMs"
    /** When it was last renewed, paid or done. */
    const val LAST_DONE_AT = "lastDoneAtMs"
}

object GoalFields {
    const val TARGET = "target"
    const val HORIZON = "horizon"
    const val PROGRESS_PCT = "progressPct"
}

object HabitFields {
    const val TARGET_PER_WEEK = "targetPerWeek"
    const val PREFERRED_TIMING = "preferredTiming"
    /** How long one go takes, so the planner can make room for it. */
    const val MINUTES = "minutes"
    /** Gym (2026-10-08, additive, LWW): MEKA books this habit's sessions into the week around the calendar ([SessionRules]). */
    const val BOOK_SLOTS = "bookSlots"
    /** Gym (additive, LWW): the rotating labels, "Push|Pull|Legs"; absent or empty for none. */
    const val ROTATION = "rotation"
    /** Gym (2026-10-08, additive, LWW): a link to the workout app ("https://hevy.com"), opened from Today's card; http(s) only. */
    const val APP_LINK = "appLink"
}

/** One habit on one local day. Id `<habitId>.d<epochDay>` so two devices ticking the same day write one entity. */
object HabitCompletionFields {
    const val HABIT_ID = "habitId"
    const val DAY = "day"
    const val DONE = "done"
    const val AT = "atMs"
    /** Gym (additive, LWW): "Didn't go" on a booked session; the session is rebooked on another day. Done wins over it. */
    const val MISSED = "missed"
    /** Gym (additive, LWW): the rotation label the session was ("Push"). */
    const val LABEL = "label"
    /** Gym (additive, LWW): an optional one-line note ("5 km", "push day"). */
    const val NOTE = "note"
}

/** A fast. `endedAtMs` is Null while it runs. All LWW: a fast has one author at a time. */
object FastFields {
    const val STARTED_AT = "startedAtMs"
    const val ENDED_AT = "endedAtMs"
    const val TARGET_HOURS = "targetHours"
    /** Fasting v2 (additive): "extended" for a fast of days (a 5-day fast, a fast until Friday 18:00); absent for the daily window. */
    const val KIND = "kind"
    /** Fasting v2 (additive): the goal as a moment ("until Friday 18:00"); absent when the goal is [TARGET_HOURS] from the start. */
    const val GOAL_AT = "goalAtMs"
}

/** The fasting plan: usual goal and the eating window as local minutes of the day. */
object FastingPlanFields {
    const val TARGET_HOURS = "targetHours"
    const val EATING_START_MIN = "eatingStartMin"
    const val EATING_END_MIN = "eatingEndMin"
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
        CommitmentFields.EXPECTED_AT, CommitmentFields.FOLLOW_UP_AT, GoalFields.TARGET, ObligationFields.CANCEL_BY_AT,
        PersonFields.DISPLAY_NAME, HouseholdFields.NAME,
    )

    override fun policyFor(entityType: String, field: String): MergePolicy = when {
        // Calendar events are a mirror of the provider, written by one author (the server): plain LWW, never conflicts.
        entityType == EntityTypes.EVENT -> MergePolicy.Lww
        // Headlines likewise: only the server's news ingestion writes them.
        entityType == EntityTypes.HEADLINE -> MergePolicy.Lww
        // Interruption counts: each entity is written only by the device named in its id.
        entityType == EntityTypes.INTERRUPTION_DAY -> MergePolicy.Lww
        // Activity entries: written by MEKA when it acts; only the undo marks change afterwards.
        entityType == EntityTypes.AGENT_ACTION -> MergePolicy.Lww
        // Event marks: a switch per event; the latest tap on any device wins.
        entityType == EntityTypes.EVENT_MARK -> MergePolicy.Lww
        // Calendar marks likewise: a switch per calendar.
        entityType == EntityTypes.CALENDAR_MARK -> MergePolicy.Lww
        // Held messages: written once by the Fold; Done on either device clears them for good.
        entityType == EntityTypes.HELD_MESSAGE && field == HeldMessageFields.CLEARED -> MergePolicy.TrueWins
        entityType == EntityTypes.HELD_MESSAGE && field == HeldMessageFields.URGENT -> MergePolicy.TrueWins
        entityType == EntityTypes.HELD_MESSAGE -> MergePolicy.Lww
        // Alarms: the latest set, snooze or dismiss on any device wins.
        entityType == EntityTypes.ALARM -> MergePolicy.Lww
        // Calendar edits: written once by the device that made them; Undo only ever sets true, the outcome only the server.
        entityType == EntityTypes.EVENT_EDIT -> MergePolicy.Lww
        // Request cards: written once by the Fold; Add or Not a task on either device clears them for good.
        entityType == EntityTypes.REQUEST_CARD && field == RequestCardFields.RESOLVED -> MergePolicy.TrueWins
        entityType == EntityTypes.REQUEST_CARD -> MergePolicy.Lww
        // Triage cards likewise: written once by the Fold; Sent, Not now or Seen on either device clears them for good.
        entityType == EntityTypes.TRIAGE_CARD && field == TriageCardFields.RESOLVED -> MergePolicy.TrueWins
        entityType == EntityTypes.TRIAGE_CARD -> MergePolicy.Lww
        // Group digest cards: written once by the Fold; Caught up (and its Undo) on either device, last one wins.
        entityType == EntityTypes.GROUP_GIST -> MergePolicy.Lww
        // The block list: Block and Unblock on either device, the latest wins.
        entityType == EntityTypes.BLOCKED_CALLER -> MergePolicy.Lww
        // Shopping: Got and Put back on any device (or Jeanette's page), the latest wins; a removal is for good.
        entityType == EntityTypes.SHOPPING_ITEM && field == ShoppingFields.DELETED -> MergePolicy.TrueWins
        entityType == EntityTypes.SHOPPING_ITEM -> MergePolicy.Lww
        // Venues: the travel time last set for a ground on any device wins.
        entityType == EntityTypes.VENUE -> MergePolicy.Lww
        field == ActionableFields.DELETED -> MergePolicy.TrueWins
        field == ActionableFields.LIFECYCLE -> lifecycleTerminal
        entityType == EntityTypes.CHECKLIST_ITEM && field == ChecklistFields.CHECKED -> MergePolicy.TrueWins
        field in userVisible -> MergePolicy.UserVisible
        else -> MergePolicy.Lww
    }
}
