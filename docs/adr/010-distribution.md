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
  - `compileSdk` is 37, because current Compose, OkHttp and SQLCipher AARs require it. `targetSdk` is 36, which matches Play's current requirement, keeping store distribution open. API 37 is adopted deliberately, because API 37 removes the large-screen orientation/resizability opt-out. We comply with that anyway (ADR-001, Fold-first).
- **macOS:**
  - Developer ID Application certificate, hardened runtime, notarisation via `notarytool`, stapled.
  - **Not sandboxed initially**, because Developer ID doesn't require it. However, the code uses only sandbox-compatible APIs: security-scoped bookmarks, no private APIs and no Apple Events to arbitrary apps. That keeps a Mac App Store build a matter of entitlements, not rewrites.
  - An Apple Developer Program membership is required for the certificate. This is an owner action.
- **Store readiness guardrails,** enforced by lint and CI:
  - no `USE_EXACT_ALARM`
  - `READ_SMS` only in a `personal` product flavour
  - no private APIs
  - no hard-coded secrets

## Addendum, 2026-10-07: self-updating phone app
- After the first ADB install, updates go over the air: the Mac builds and publishes the APK to the owner's own server (chunked, signed requests from a keyed device), and the phone downloads it into a `PackageInstaller` session. Android shows its own Install prompt every time; MEKA never installs silently.
- Integrity: the whole-file SHA-256 is checked by the server and again by the phone, and Android refuses an update not signed with the installed app's key. A compromised server therefore can't install anything the owner didn't sign.
- `REQUEST_INSTALL_PACKAGES` is a personal-distribution permission (Play restricts it). A store build drops it with the self-updater, like `READ_SMS` (a `personal` flavour when a store build is wanted).
- These are non-ADB installs, so developer verification will apply to them once enforcement reaches the UK; the Limited Distribution account above covers them.

## Addendum, 2026-10-07: GitHub publishes phone builds (hands-free phone updates)
- After a green CI run that changed the phone app since the published build, the deploy workflow builds the debug APK, signs it with the owner's Mac debug key (GitHub secret `MEKA_FOLD_SIGNING_KEY`, decoded on the runner for the build and deleted after; never printed or uploaded as an artifact; the job checks the APK carries that key's certificate) and publishes it.
- It publishes as a **release-only publisher**, not a household device: a P-256 key whose private half is in Secrets Manager, readable only by the GitHub deploy role (explicit deny for everyone else, the service included); the server knows only its public key. It may call only the release "latest" and "upload" routes; every other route is 403. Its builds are recorded as published by `github-build`.
- The phone's checks are unchanged: whole-file SHA-256, Android's own signature check against the installed app, and Meka's tap on Android's Install prompt. A quiet low-importance notification says a build is ready, at most once per build.
- `tools/publish-fold.sh` (publishing from the Mac) stays as the manual fallback; the same commit gives the same build number either way, and the server refuses a build that isn't newer.
