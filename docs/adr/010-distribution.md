# ADR-010: Distribution and signing

**Status:** Accepted, 2026-10-01

## Context
The owner chose personal distribution: a sideloaded Android APK, and a Developer ID-signed, notarised macOS app. The design must stay store-compatible later without compromising the personal build today.

**New fact (verified 2026-10-01): Android developer verification.**
- Enforcement began on 30 Sep 2026 in limited regions, with a global rollout in 2027.
- **Installs over ADB are exempt, including updates.**
- Other sideload routes need either:
  - the one-time "advanced flow" (developer mode, a 24-hour wait, then biometric confirmation), or
  - a free **Limited Distribution** developer account (up to 20 devices, no ID required).

## Decision
- **Android:**
  - Primary install path is `adb install` / Android Studio Run over USB or Wi-Fi from the owner's Mac.
  - Release APKs are signed with a MEKA release key. The keystore is stored outside the repo, with its password in the macOS Keychain. The same key is used forever, since changing keys breaks updates.
  - We register a free Limited Distribution account before the regional enforcement reaches the UK, so that over-the-air installs keep working.
  - `targetSdk` is 36, which matches Play's current requirement, keeping store distribution open. API 37 is adopted deliberately, because API 37 removes the large-screen orientation/resizability opt-out. We comply with that anyway (ADR-001, Fold-first).
- **macOS:**
  - Developer ID Application certificate, hardened runtime, notarisation via `notarytool`, stapled.
  - **Not sandboxed initially**, because Developer ID doesn't require it. However, the code uses only sandbox-compatible APIs: security-scoped bookmarks, no private APIs and no Apple Events to arbitrary apps. That keeps a Mac App Store build a matter of entitlements, not rewrites.
  - An Apple Developer Program membership is required for the certificate. This is an owner action.
- **Store readiness guardrails,** enforced by lint and CI:
  - no `USE_EXACT_ALARM`
  - `READ_SMS` only in a `personal` product flavour
  - no private APIs
  - no hard-coded secrets
