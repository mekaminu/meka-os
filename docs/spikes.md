# Technical spikes (Stage 6)

Status as of 2026-10-01. "Proven" means demonstrated by an automated test, not by reasoning.

| # | Spike | Status | Evidence / next step |
|---|---|---|---|
| S1 | Android notification ingestion (WhatsApp) | Not started (M2) | Needs the Fold in hand. Verify: restricted-settings flow for a sideloaded APK, `RemoteInput` reply from a listener, Android 15 OTP redaction, and listener survival under Samsung battery management |
| S2 | Background/offline queue | **Proven at core level** | `SyncAcceptanceTest`: offline edits are queued, a retry storm of 5 dropped responses is followed by 3 paranoid re-pushes, and no duplicates result. WorkManager wiring is written; device testing is pending |
| S3 | Fold layouts | Written, not yet run | Two-pane at ≥ 600dp, with saveable selection across fold/unfold. Hinge-aware split via `FoldingFeature` comes next. Instrumented fold/unfold test runs nightly on an emulator once CI is live |
| S4 | macOS integrations | Scoped | No cross-app automation in M0 (ADR-010). The menu-bar quick capture is written |
| S5 | Bidirectional sync | **Proven at core level** | A 200-seed convergence property test, exact-causality merge, and a planted-bug check showing the property test catches merge errors. Postgres sequencing has been verified against a real Postgres instance (gap-free sequences, unique op ids) |
| S6 | Encrypted local DB | Android: written. **macOS: linkage in, awaiting the nightly Mac run** | 2026-10-07: the app links Zetetic's official `SQLCipher.swift` 4.19.0 package (dynamic SQLCipher.framework, embedded) and no longer `-lsqlite3`, so the static MekaKit's `sqlite3_*` calls bind to SQLCipher (every function SQLiter calls is exported). `MekaCoreBridgeTests.testDatabaseEngineIsSQLCipher` asserts `PRAGMA cipher_version`, that a keyed file has no plain header, reopens with its key and is refused without it. The replica stays unkeyed until that passes; then the key file and the one-time re-key. The fallback is in ADR-002 |
| S7 | Hybrid local/cloud AI | Not started (M2) | Depends on which Fold model you have (Gemini Nano supports Fold7+, not Fold6) |
| S8 | Sports fixtures | Not started (M1) | football-data.org free tier covers La Liga and the Champions League |
| S9 | News ingestion | Not started (V1-early) | |
| S10 | Approval/action execution | **Policy proven** | `PolicyEngineTest`: hard ceilings, untrusted provenance, the kill switch, and an injected "send my documents" request requiring biometric approval. The executor comes in M2 |
