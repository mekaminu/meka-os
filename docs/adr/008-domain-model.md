# ADR-008: Domain model — Household and actionable primitives

**Status:** Accepted, 2026-10-01

## Context
- Everything lives in one life graph.
- Meka is the only user for MVP/V1, but his wife may become a real user. Family entities must therefore belong to a household, not to one user.
- Task is not the only actionable primitive.
- The owner has asked for Decision, Waiting For and Someday/Maybe as first-class concepts.

## Decision

### Tenancy
- `Household` is the tenancy and sync boundary. Every entity carries `householdId`.
- `Person` is anyone in the graph: family, friends, the coach, a colleague.
- `Principal` is a person who can sign in. There is one today; the model allows more.
- `Membership(principalId, householdId, role)` holds the role: `OWNER`, `ADULT` or `CHILD_PROFILE` (non-login).
- Visibility is per entity: `HOUSEHOLD` (shared) or `PRIVATE(principalId)`. M0 writes everything as `PRIVATE(owner)` except family entities, so that sharing later is a policy change, not a migration.

### Actionable primitives
These share an `Actionable` core: `id`, `householdId`, `title`, `lifecycle`, `ownerPersonId`, `dueAt?`, `priority`, `goalIds`, `provenance`, `createdAt`, `updatedAt`.

| Entity | Purpose | Key fields |
|---|---|---|
| **Task** | Something I will do | `estimateMinutes`, `scheduledAt`, `contextModes`, child `ChecklistItem`s |
| **Commitment** (`direction = OWED_BY_ME`) | Something I promised someone | `counterpartyPersonId`, `sourceRef`, `confidence`, `lastInteractionAt` |
| **WaitingFor** (`direction = OWED_TO_ME`) | Something I'm waiting on from someone or something | `counterparty`, `expectedAt`, `lastInteractionAt`, `followUpAt`, chase suggestions |
| **Obligation** | A dated or recurring duty attached to an entity (MOT, boiler, subscription, school form, warranty) | `subjectRef`, `recurrence`, `cost`, `documentRefs`, `leadTime` |
| **Decision** | A decision made, so it isn't re-made | `statement`, `decidedAt`, `rationale`, `context`, `relatedGoalIds`, `relatedPersonIds`, `reviewAt`, `status` (ACTIVE / SUPERSEDED / REVISITING), `supersedesDecisionId`, `provenance` |
| **Goal** (MVP-light) | Why activities matter | `target`, `horizon` (SHORT/MEDIUM/LONG), `priority`, `progress`, related tasks/habits |
| **Habit** (MVP-light) | A recurring behaviour | `targetFrequency`, `preferredTiming`, completion history as child `HabitCompletion` entities |
| **Event** | Something at a time (calendar, fixture, class) | `startsAt`, `endsAt`, `timezone`, `location`, `source` |

Commitment and WaitingFor are one table (`commitment`) with a `direction` field. They are the same shape viewed from opposite sides, and "what am I waiting for from the school?" and "what have I promised the school?" are one query.

**Lifecycle** is shared by all actionables:
- States: `INBOX`, `ACTIVE`, `SOMEDAY`, `WAITING`, `DONE`, `CANCELLED`.
- **Someday/Maybe** is `lifecycle = SOMEDAY` plus a `somedayKind` (IDEA, PURCHASE, PROJECT, TRIP, BOOK, RESEARCH, APPLICATION, HOME_IMPROVEMENT).
- The planner's capacity calculation excludes SOMEDAY, and the resurfacing agent (V1) proposes promotions.

### Graph relationships
- **`Relation`** entities are first-class and typed: `(fromRef, type, toRef, provenance)`. Examples: `Logan -[CHILD_OF]-> Meka`, `Logan -[MEMBER_OF]-> Norwich FC U10`, `Commitment -[DERIVED_FROM]-> Message#…`, `Task -[SERVES]-> Goal`.
- **ContextMode** holds the modes Work, Personal, Focus and Quiet, which are user-visible. Commute, Travel and Training are inferred states.
- **Provenance** records the source (user, voice, notification:package, email:messageId, agent:actionId), trust (`TRUSTED_USER`, `UNTRUSTED_EXTERNAL`, `DERIVED`) and the extractor model/version.

### Schema versioning
- Every entity type has a `schemaVersion`, and ops carry the writer's version.
- Readers upgrade ops from older versions. Unknown newer fields are preserved, not dropped.

## Consequences
M0 implements the full generic sync substrate and these entity types for real:
- `Task`
- `ChecklistItem`
- `Household`
- `Person`

The other types are defined as field schemas and merge policies, so the planner and UI can adopt them without sync changes.

## Addendum, 2026-10-06: repeating tasks
A repeating Task is a series of occurrences rather than one task that rolls forward. Each occurrence carries `recurrence` (an iCalendar RRULE subset), `seriesId` and `occurrenceDay` (local epoch day); a snooze sets `deferredToDay` without changing the series day. Completing or skipping an occurrence writes the next one under the deterministic id `<seriesId>.d<epochDay>`, so concurrent completions on two offline devices converge on a single next occurrence and history stays one task per day. Rules this version can't read are kept as text and not acted on. Obligation (`recurrence`) will reuse the same rule type.


## Addendum, 2026-10-06: lists
Waiting for, Someday and Decisions are live (`Lists` in core/domain). Waiting for uses `commitment` with `direction = OWED_TO_ME` and `lifecycle = WAITING` (DONE when it arrives); `followUpAtMs` is the chase date and `lastInteractionAtMs` the last chase. Someday stays a Task with `lifecycle = SOMEDAY` and `somedayKind`. A Decision's `status` moves ACTIVE → REVISITING → ACTIVE, or to SUPERSEDED when a new decision with `supersedesDecisionId` replaces it; superseded decisions are kept. Chase and review dates are whole local days, stored as 09:00 local on that day so they read back as the same day on every device and across clock changes. Concurrent edits to user-visible fields of these entities resolve deterministically; any later edit clears the conflict (they are not yet offered as choices in Needs you).

## Addendum, 2026-10-06: goals and habits
Goals and Habits are live (`Goals` in core/domain), still MVP-light. A Habit has `targetPerWeek` (1–7, ISO weeks Monday to Sunday), `preferredTiming` (MORNING / AFTERNOON / EVENING / ANYTIME), `minutes` (added field) and an optional `goalId`. Each tick is a `habit_completion` with the deterministic id `<habitId>.d<epochDay>` and fields `habitId`, `day`, `done`, `atMs`, so ticks of the same day on two offline devices converge on one entity and unticking is `done = false` (LWW). Pace and streaks are computed, never stored: the week's target is pro rata in the week a habit was added; a daily habit's streak counts days, any other target counts weeks that met it. A Goal's progress is counted from linked Tasks (`goalId`; done ÷ linked, Someday and cancelled excluded) and linked Habits (this week's share), averaged per item; with nothing linked it is the hand-set `progressPct`. Finishing a goal sets `lifecycle = DONE` (terminal wins); deleting one unlinks its tasks and habits first. The planner makes room for habits that are behind or due today, before tasks, in their preferred part of the day; habit blocks are part of the suggestion only.

## Addendum, 2026-10-06: fasting
Two entity types were added (additive): `fast` (`startedAtMs`, `targetHours`, `endedAtMs` — Null while running — plus provenance) and `fasting_plan` (one entity, id `default`: `targetHours`, `eatingStartMin`, `eatingEndMin` as local minutes of the day; the window may cross midnight). All fields are LWW; discarding a fast sets `deleted` (true wins). Fasts started on two offline devices are both kept: the earliest open fast is the current one, ending ends every open fast, and history merges overlapping finished fasts so nothing is counted twice. History and averages are computed, never stored; a fast counts on the local day it ended. `Fasting.plannerMeals` gives the planner meal blocks (break the fast at its goal when that lands today; the last meal before the eating window closes) that are kept free like events and shown, not applied.
