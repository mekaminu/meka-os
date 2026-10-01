# MEKA OS — architecture and repository plan (M0)

## System context

```mermaid
flowchart LR
  Meka((Meka)) --> Android[Android app<br/>Compose · Fold-aware]
  Meka --> Mac[macOS app<br/>SwiftUI · menu-bar capture]
  Android <-->|ops over HTTPS| Sync[Sync service<br/>Ktor on Fargate]
  Mac <-->|ops over HTTPS| Sync
  Sync --> PG[(Postgres<br/>op log)]
  Trading[Kestrel / Merlin / AuctionTrader] -. signed events only, M3+ .-> Sync
  Sync -. later: ingestion .-> Providers[Calendar · Email · Fixtures · News]
```

## Inside each device

```mermaid
flowchart TB
  UI[Compose / SwiftUI<br/>presentation only] --> Facade[core/facade · MekaCore<br/>StateFlows + suspend commands]
  Facade --> Domain[core/domain<br/>Tasks · Today · schema]
  Facade --> Policy[core/policy<br/>autonomy · hard ceilings · kill switch]
  Domain --> Sync[core/sync<br/>Replica · HLC · heads-based merge]
  Facade --> Wire[core/wire<br/>versioned JSON]
  Sync --> Store[core/data<br/>SQLDelight + SQLCipher]
```

Write path: UI → `MekaCore` command → `Tasks` → `Replica.commitLocal` → op appended and field heads updated, in one transaction → `Today` recomputed → push queued.

Read path: SQL → heads per field → merge policy chooses the winner → typed `Task` → `TodayProjection`.

## Sync

```mermaid
sequenceDiagram
  participant A as Android (offline)
  participant S as Sync service
  participant M as Mac
  A->>A: edit → op{id, hlc, baseOpIds}
  Note over A: queued; UI already correct
  A->>S: push(ops) — retried until acked
  S->>S: store once (op_id UNIQUE), assign seq
  M->>S: pull(after = cursor)
  S-->>M: ops[seq…]
  M->>M: apply (idempotent) → same heads → same state
```

## Repository

```
meka-os/
├─ core/
│  ├─ sync/       stdlib-only kernel: Hlc, Op, FieldState/Merge, Replica, SyncService, SyncClient
│  ├─ domain/     stdlib-only: schema + merge policy per field, Tasks, TodayProjection, ids
│  ├─ policy/     stdlib-only: PolicyEngine (ADR-006)
│  ├─ wire/       JSON wire codec (kotlinx-serialization tree API)
│  ├─ testing/    SyncWorld (multi-device + faulty transport), ReplicaStoreContract
│  ├─ data/       SQLDelight schema, SqlReplicaStore, Android/Mac database openers
│  └─ facade/     MekaCore (UI API), HttpSyncTransport, MacCoreFactory (Swift entry)
├─ android/app/   Compose app: Today, detail pane, quick capture, Keystore DB key, WorkManager sync
├─ macos/         SwiftUI app (XcodeGen project.yml), Keychain identity, bridge tests
├─ backend/       Ktor sync API, Postgres op store, device auth, migrations
├─ infra/         AWS CDK stack + invariant tests
├─ design/        tokens.json → generated Kotlin/Swift tokens (+ contrast gate)
├─ tools/         core-verify.sh (offline kernel verification), kotlin.test shim
├─ docs/          ADRs, spikes, this file
└─ .github/       CI
```

## Verification status

| Area | How it is verified | Where it has run |
|---|---|---|
| Kernel: sync, merge, domain, policy, wire, store contract (47 tests) | `tools/core-verify.sh` | ✅ Assistant workspace |
| Postgres schema, sequencing, uniqueness | Executed against a real Postgres instance | ✅ Assistant workspace |
| SQLite schema and queries | Executed against SQLite | ✅ Assistant workspace |
| Infra invariants (10 tests) and `cdk synth` | jest + CDK | ✅ Assistant workspace |
| Design tokens and the contrast gate | `node design/generate.mjs --check` | ✅ Assistant workspace |
| Gradle build: Android app, facade, backend API tests, SqlReplicaStore contract on real SQLite | `./gradlew …` in CI | ⏳ Blocked: Maven unreachable from the assistant's workspace (ADR-011) |
| SwiftUI app and Swift↔Kotlin bridge test | Xcode in CI (macOS runner) | ⏳ Same |

## M0 → M1

M0 is done when CI is green on all jobs and the acceptance scenarios pass on real devices:
- Android offline → edit → online
- both devices offline → conflicting edits → reconnect

M1 then adds:
- calendar read (first provider)
- Barcelona fixtures and plan reconciliation
- the deterministic day planner v1
- the notification governor v1
- fasting
- lightweight Goals and Habits
