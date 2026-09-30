# ADR-005: Identity, device keys and sessions

**Status:** Accepted, 2026-10-01

## Context
There is a single owner today, and the owner's wife may be a user later (ADR-008). Stolen-device and compromised-cloud scenarios are in the threat model. No API keys may ship in client binaries.

## Options
- **Cognito user pools.** Managed, but heavy for one user, and passkey support comes through its own hosted UI flows.
- **Third-party auth such as Auth0 or Clerk.** Adds a SaaS dependency and a subscription, which is against the product philosophy.
- **Self-hosted passkey (WebAuthn) for people + per-device Ed25519 keys for devices.** A small surface and strong security.

## Decision
- **People:** a `Principal` (person account) authenticates with a passkey (WebAuthn).
  - The server verifies it with a maintained WebAuthn library, pinned in `backend/`.
  - The first enrolment is bootstrapped by a one-time enrolment code printed by the deploy pipeline.
- **Devices:** each device generates an Ed25519 key pair in hardware-backed storage (Android Keystore, macOS Keychain/Secure Enclave where supported).
  - Enrolling a device requires an authenticated principal session and produces a `Device` record.
- **Requests:** every sync request is signed by the device key over method, path, body hash and timestamp.
  - The server issues short-lived (15 min) access tokens bound to the device.
  - Revoking a device invalidates its tokens immediately and stops sync.
- **Recovery:** a recovery key (a 24-word phrase shown once) can enrol a new device if both devices are lost. Restore drills are part of the V1 checklist.
- **App lock:** biometric/device credential on open and after N minutes in the background. This is separate from DB-key authentication (ADR-002).
- **Integration secrets:** no provider API keys ship in clients. OAuth for integrations runs server-side (authorisation code + PKCE), and refresh tokens stay in Secrets Manager.

## Consequences
- M0 implements the device-key request signing and a development enrolment path. Passkey enrolment lands before the first cloud deployment holding real data.
- Multi-user later means adding principals to a `Household` with roles. No redesign is needed.
