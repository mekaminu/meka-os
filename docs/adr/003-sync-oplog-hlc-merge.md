# ADR-003: Sync — operation log, hybrid logical clocks and per-field merge

**Status:** Accepted, 2026-10-01

## Context
Android and Mac edit the same data while disconnected. Requirements: never lose an operation, never duplicate one, converge deterministically, and surface meaningful conflicts to the user rather than silently discarding a change. Naive last-write-wins everywhere is explicitly rejected.

## Options
- **PowerSync / ElectricSQL.** These are Postgres-shaped sync services. They dictate the schema and still leave conflict semantics to us.
- **Automerge / Yjs CRDT documents.** Excellent for rich text, but overkill for mostly structured records. They also make server-side querying and schema evolution harder.
- **Custom op-log with HLC and per-field merge policies.** Small, fully testable in shared code, and the server runs the same merge code.

## Decision
**Operations.** Every change is an immutable `Op`:
- `opId`: UUID, created on the device and used as the idempotency key
- `householdId`, `entityType`, `entityId`, `field`, `value`
- `hlc`: the operation's hybrid logical clock timestamp
- `baseOpIds`: the op ids of the field heads the author saw when making the edit (exact causality)
- `deviceId` and `schemaVersion`

**Hybrid logical clocks.** Each HLC is (wall ms, counter, nodeId). Clocks advance on local events and on receive, which gives a total order that respects causality even with skewed device clocks. A remote HLC more than 24 h ahead of local wall time is rejected (clock-attack/skew guard).

**Field state is the set of heads.**
- Each field keeps its **heads** and a **superseded set**. The superseded set is the union of every op's `baseOpIds`, and the heads are the ops whose id is not superseded.
- Both sets are pure functions of the op set. Delivery order and duplicates therefore cannot change the result.
- The winner is chosen from the heads by a per-field **merge policy**:
  - `LWW`: highest HLC wins, silently. Used for low-stakes fields such as notes and estimates.
  - `USER_VISIBLE`: highest HLC wins provisionally. If two or more heads carry different values, a `Conflict` is recorded for the user to resolve. Used for title, due date and scheduled time.
  - `TERMINAL_WINS`: a head holding a terminal status (DONE/CANCELLED) beats a concurrent reopen. A reopen made after seeing the completion supersedes it and wins normally.
  - `TRUE_WINS`: used for the `deleted` flag, so delete beats a concurrent edit. An explicit restore supersedes it.
- **Why op ids rather than a base timestamp:** the first implementation used a scalar `baseHlc` and treated `baseHlc >= other.hlc` as "I saw it". The property and acceptance tests showed that this is wrong. A device whose unrelated earlier edit has a later timestamp would falsely supersede a concurrent change from another device. That can silently drop a concurrent title edit, and it can let a reopen beat an offline completion it never saw. With base op ids, causality is exact.
- **Property-tested:** 200 random seeds with partial gossip between three devices, and every delivery permutation of a four-op field. A planted merge bug is caught by the property test.

**Collections.** Collections such as checklist items are modelled as child entities rather than list fields. Adds are therefore naturally a union, and removals are tombstones.

**Idempotency.**
- The op log deduplicates by `opId`.
- Re-delivering an identical op is a no-op.
- The same `opId` with different content is rejected as an integrity violation and logged.

**Protocol.**
- The client pushes unacknowledged ops in batches.
- The server stores each op once (`opId` unique) and assigns a per-household monotonically increasing `serverSeq`.
- The client pulls ops after its `serverSeq` cursor and applies them with the shared merge code.
- Push and pull are both safe to retry indefinitely, and backoff is exponential with jitter.

**Resolution.** Resolving a conflict writes a new op whose `baseOpIds` are all current heads, which supersedes every head.

**Compaction.** Ops older than the oldest active device's acknowledged cursor, and superseded on every field, may be archived from device storage. The server keeps the full log for export and audit.

## Consequences
- Every write goes through `Replica.commitLocal`. UI code cannot mutate tables directly.
- Unit and property tests in `core/sync` are the primary guarantee. The M0 acceptance tests are online→offline→online convergence, two-device concurrent conflict, and a retry storm with zero duplicates.

## Revisit if
- Collaborative rich-text notes arrive (V2). That would add a CRDT for note bodies only.
