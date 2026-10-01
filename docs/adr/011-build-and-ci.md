# ADR-011: Build, CI and verification under network constraints

**Status:** Accepted, 2026-10-01. The CI host depends on an owner action.

## Context
Stage 0 found that the AI build workspace and the Cowork VM cannot reach Maven Central, Google Maven or the Gradle plugin portal (organisation egress policy, HTTP 403). npm, PyPI and GitHub are reachable. The owner's Mac has Xcode, Android Studio and Docker, but the assistant cannot type into terminals or IDEs there.

## Decision
- **Gradle** (wrapper 9.8.0) is the single build for core, Android and backend. Versions are in `gradle/libs.versions.toml`.
- **Xcode:** the SwiftUI app builds from an Xcode project, which is generated from `macos/project.yml` by XcodeGen so it is diff-friendly. A pre-build step runs `./gradlew :core:facade:assembleMekaKitReleaseXCFramework`.
- **CI is GitHub Actions** (`.github/workflows/`). This is the authoritative verification:
  - `core-and-backend` on `ubuntu-latest`: `./gradlew check`, which covers core JVM tests, Android unit tests, lint, ktlint, backend tests with a Postgres service container, and SQLDelight migration verification.
  - `android` on `ubuntu-latest`: `assembleDebug`, plus instrumented fold/unfold tests on an emulator nightly.
  - `macos` on `macos-26` (Xcode 26.6; deployment target macOS 26): Kotlin/Native tests, the XCFramework, and building and testing the SwiftUI app.
    - Runs only on `main` merges, nightly and on manual dispatch, to protect macOS minutes (about 10x the Linux price).
    - We move to the Xcode 27 image when it leaves public preview.
  - `infra`: `npm ci && npx cdk synth` plus snapshot tests.
- **Local fast loop in the assistant's workspace:** `tools/core-verify.sh` compiles `core/*/src/commonMain` and `commonTest` with the Kotlin compiler, stdlib, coroutines and serialization runtime bundled in the local Gradle distribution. It runs the tests under JUnit4 through a tiny `kotlin.test` shim.
  - This verifies the correctness-critical logic (sync, merge, policy) without network.
  - The **same test sources** run under the real `kotlin.test` in CI, so the local loop is not a separate test suite.
  - Code in `commonMain` of `core/domain`, `core/sync` and `core/policy` therefore stays dependency-free (stdlib only). That is a good constraint for a correctness kernel anyway.

## Owner actions that unlock the full pipeline
Any one of these is enough:
1. Connect GitHub to Claude and create a private `meka-os` repository (**recommended**). CI then runs everything, including the macOS build.
2. Ask the organisation admin to allow `repo.maven.apache.org`, `dl.google.com`, `maven.google.com`, `plugins.gradle.org` and `services.gradle.org` for Claude's workspace.
3. Build locally: `./gradlew check`, then `open macos/MekaOS.xcodeproj`.
