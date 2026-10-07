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

## Addendum, 2026-10-06: evening shutdown
No new entity type. The shutdown is one `context_mode` entity with the fixed id `shutdown` and two LWW fields: `shutdownDay` (the local epoch day it was done) and `shutdownAtMs`. A shutdown counts only for the day it names, so a stale value from an offline device never hides today's card. Carrying an item over is the existing per-occurrence snooze (`deferredToDay`, times of day kept); skipping and Someday are the existing commands. "Left from today" (open tasks Today shows, plus ones planned for an earlier day that were never done) and the tomorrow preview (events, tasks whose day, plan or due date is tomorrow, work hours) are computed, never stored.

## Addendum, 2026-10-06: renewals and bills radar
Obligation is live (`Renewals` in core/domain), entered by hand. Fields (additive): `kind` (MOT, CAR_TAX, INSURANCE, BOILER, SUBSCRIPTION, BILL, LICENCE, WARRANTY, OTHER), `dueAtMs` (the shared actionable field, a whole local day stored as 09:00 local like chase dates), `recurrence` (the task RRULE subset; the apps offer monthly, every 3 months and yearly, anchored on the due day, and keep any other rule as text), `costMinor` + `currency` (pence, GBP), `leadDays` (how early it shows; defaults by kind), `cancelByAtMs` (UserVisible), `subjectLabel` (free text until it can reference People or Vault entities) and `lastDoneAtMs`. An obligation rolls forward in place rather than as a series: "renewed" sets `dueAtMs` to the rule's next day after the current one and moves `cancelByAtMs` by the same number of days; two offline devices compute the same values, so they converge without a conflict. A one-off is DONE; "cancelled it" / "stop tracking" is CANCELLED (kept). Where it stands (overdue, cancel-by within 7 days, inside the lead time, later) and the monthly/yearly cost of repeating items are computed, never stored. Renewals are part of `ListsView`, so what needs doing counts in Needs you.

## Addendum, 2026-10-06: notification settings (Quiet)
No new entity type. Quiet is the `context_mode` entity with the fixed id `quiet`, three LWW text fields: `quietHours` ("on;start;end" in local minutes; default 22:00–07:00), `digestTimes` ("750,1080"; empty for no digests) and `noticeTiers` ("SHUTDOWN=DIGEST,…": sources the owner moved to a lower tier; a raised tier is ignored when read, so synced data can never make something interrupt more than its default). Notices themselves are never stored: the governor (`Governor` / `NoticeSources` in core/domain) derives them from lists, renewals, fasting, the shutdown and Today on every evaluation. What each device has posted, and what it posts at all (everything / digests only / off), stays on that device.

## Addendum, 2026-10-06: morning brief
No new entity type. Reading the brief ("Got it") is one `context_mode` entity with the fixed id `brief` and two LWW fields: `briefSeenDay` (local epoch day) and `briefSeenAtMs`; like the shutdown, it counts only for the day it names. The brief itself (today's events and open tasks in time order, waiting-for items, renewals and decisions that need doing, habit pace, a running fast) is computed from the other views, never stored. News headlines will be added by server-side ingestion (NewsProvider, ADR-009) in a later slice.

## Addendum, 2026-10-06: news headlines
One entity type was added (additive): `headline` (`title`, `url`, `source`, `topic`, `publishedAtMs`, `removed`), written only by the server's news ingestion, so every field is LWW and never conflicts (like `event`). The server keeps a fixed set of slots per topic (entity id derived from account, topic and slot), so the number of headline entities is bounded; a slot is rewritten only when a different story takes it, and an empty slot has `removed = true`. Which topics the brief shows is the `context_mode` entity `news` with one LWW field, `newsTopics` (comma-separated topic ids; unknown ids ignored when read, empty means no news). Headline text is untrusted (ADR-006): the apps show it as plain text and open a link only when it is https.

## Addendum, 2026-10-06: weekly review
No new entity type. "Done reviewing" is one `context_mode` entity with the fixed id `review` and two LWW fields: `reviewedWeek` (the Monday of the reviewed week, as a local epoch day) and `reviewedAtMs`; it counts only for the week it names. The review itself (tasks finished in the week by `completedAtMs`, habit ticks against the week's target, fasts by end time, waiting-for items received, decisions made, obligations done by `lastDoneAtMs`, what was planned or due and is still open, and the week ahead) is computed from stored data, never stored. Which week the screen shows is a per-device screen choice, not synced.

## Addendum, 2026-10-06: interruptions count
One entity type was added (additive): `interruption_day` (`day`, `device`, `critical`, `action`, `headsUp`), one per device per local day with id `<deviceId>.d<epochDay>`, written only by the device named in its id, so every field is LWW and two devices never conflict. Counts only: no titles, sources or text. When counting began is the `context_mode` entity `interruptions` with one LWW field, `countingSince` (a local epoch day, written once, the first time any device could post). The weekly review adds the counts up across devices for its week.

## Addendum, 2026-10-06: event notes and call links
Two fields were added to `event` (additive, LWW, server-written like the rest): `description` (the event's notes as plain text, at most 2,000 characters; the server strips Google's HTML and keeps a link's address beside its text; Microsoft sends a plain preview) and `joinUrl` (the provider's own video-call link — Google `hangoutLink` or a `video` conference entry point, Microsoft `onlineMeeting.joinUrl` — mirrored only when https). Neither is written for an event that has never had one, so existing events gain no ops. Notes are untrusted (ADR-006): the apps show them as plain text only. The event detail's Join button uses `joinUrl`, or else the first https link to a known call service (Meet, Teams, Zoom, Webex, Whereby, FaceTime) in the location or notes, matched by host (the host or a subdomain; no user-info or ports).

## Addendum, 2026-10-07: activity log
One entity type was added (additive): `agent_action` (`atMs`, `kind`, `summary`, `detail`, `why`, `source`, `level`, `changes`, `undoneAtMs`, `undoNote`), all LWW: an entry is written once when MEKA acts and afterwards only its undo marks change. Ids: reminders `n` + FNV-1a-64 of the notice key and digests `g` + FNV-1a-64 of day and digest name (so two devices posting the same notice write one entry); actions a random id. `changes` lists each touched field as tab-separated type, id, field, before, after (values tagged `-`, `t:`, `i:`, `b:`; text escaped), read all or nothing. An entity MEKA created records `deleted` false-from-true so undoing deletes it. What the Activity screen shows (the last 30 days by day, the week's counts) is computed, never stored.

## Addendum, 2026-10-07: calendar actions
Events stay a read-only mirror written by the server. What Meka does with an event in MEKA lives beside it. One entity type was added (additive): `event_mark`, one per event with the event's id, fields `hidden` (Bool) and `hiddenAtMs`, all LWW (the latest tap on any device wins). Hidden events are left out of Today's timeline, Plan my day, the brief, the evening shutdown and the weekly review; the Calendar tab lists them under their day so they can be shown again. Search still finds them. One field was added to `task` (additive): `eventId`, the event a prep task is for. A prep task's id is `p<eventId>`, so adding it twice, or on two offline devices, makes one task; adding it again after it was deleted or finished brings the same task back (deleted false, ACTIVE, completedAt cleared). It is due at the event's start and planned 30 minutes before it for 15 minutes (unplanned when that time has passed; an all-day event's is due 09:00 local on its first day).

## Addendum, 2026-10-07: event reminders
Two fields were added to `event_mark` (additive, LWW like the rest): `remindMin` (remind me this many minutes before, 1–240; null for none) and `travelMin` (Leave by: how many minutes it takes to get there, 1–240; null for none). Nothing is stored per reminder: the notices are derived on every governor evaluation (`ReminderRules.notices`), keyed by the event's id, its start and the minutes, so a moved event reminds again at its new time and each device posts a reminder once. Hidden, all-day and started events don't remind; a leave-by needs a place (not just a link). The travel time is Meka's own; no maps service is asked.
