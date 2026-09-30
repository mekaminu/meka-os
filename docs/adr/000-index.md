# Architecture Decision Records

Each ADR records context, options considered, decision, consequences and what would make us revisit. Status values: Proposed, Accepted, Superseded.

| # | Title | Status |
|---|---|---|
| 001 | [Cross-platform stack](001-cross-platform-stack.md) | Accepted |
| 002 | [Local database and encryption at rest](002-local-database-and-encryption.md) | Accepted (Apple SQLCipher linkage is a spike) |
| 003 | [Sync: operation log, HLC and per-field merge](003-sync-oplog-hlc-merge.md) | Accepted |
| 004 | [Backend, infrastructure and isolation from trading](004-backend-and-infrastructure.md) | Accepted |
| 005 | [Identity, device keys and sessions](005-identity-and-devices.md) | Accepted |
| 006 | [AI boundary, policy engine and prompt-injection defence](006-ai-boundary-and-policy.md) | Accepted |
| 007 | [Android timing mechanisms (alarms, work, push)](007-android-timing.md) | Accepted |
| 008 | [Domain model: Household and actionable primitives](008-domain-model.md) | Accepted |
| 009 | [Provider interfaces for external services](009-provider-interfaces.md) | Accepted |
| 010 | [Distribution and signing](010-distribution.md) | Accepted |
| 011 | [Build, CI and verification under network constraints](011-build-and-ci.md) | Accepted |
| 012 | [Design tokens and motion system pipeline](012-design-tokens.md) | Accepted |
| 013 | [North-star metrics and privacy-safe telemetry](013-metrics-and-telemetry.md) | Accepted |

Facts about platforms and library versions were checked against official sources on 2026-10-01. Where a fact could not be confirmed it is marked **unverified**.
