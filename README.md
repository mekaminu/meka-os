# MEKA OS

A personal operating system that remembers the administration so Meka doesn't have to. It's offline-first, runs on Android (Samsung Fold) and macOS, and shares one life graph between them.

> North star: *How many things did Meka no longer have to think about this week?*

**Status:** M0, the walking skeleton with provable sync. See [docs/architecture.md](docs/architecture.md) for the plan and verification status, [docs/adr/](docs/adr/000-index.md) for every decision, and [docs/spikes.md](docs/spikes.md) for the risk spikes.

## Quick start

| Goal | Command |
|---|---|
| Verify the sync/merge/policy kernel with no network | `./tools/core-verify.sh` |
| Full build and tests | `./gradlew checkKernelPurity check` |
| Android app on the Fold over USB | `./gradlew :android:app:installDebug` (ADB installs are exempt from developer verification, ADR-010) |
| Mac app | `brew install xcodegen && xcodegen generate --spec macos/project.yml && open macos/MekaOS.xcodeproj` |
| Regenerate design tokens | `node design/generate.mjs` |
| Infra checks | `cd infra && npm ci && npx jest && npx cdk synth` |
| Run the sync service locally | `docker run -e POSTGRES_PASSWORD=dev -p 5432:5432 postgres:17`, then `MEKA_DB_URL=jdbc:postgresql://localhost/postgres MEKA_DB_USER=postgres MEKA_DB_PASSWORD=dev ./gradlew :backend:run` |
| Enrol a device (development) | `./gradlew :backend:run --args="enrol-device <household> <deviceId>"`. Prints the device secret once. |

## Principles

These come from the brief and are enforced in code where possible:
- **Silence is the default.** "You're clear." is a successful state.
- **One graph, many lenses.** Every write is an op on one life graph.
- **Local is the truth for the moment.** Nothing core waits on the network.
- **Policy is code, not prompt.** Models propose and only `PolicyEngine` permits.
- **Every action is idempotent, queued and audited.**
- **Honesty about capability.** No faked integrations.
