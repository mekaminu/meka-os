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
- **M0, as built:** each device gets a random 256-bit bearer secret at enrolment.
  - The server stores only its SHA-256 and binds every request to that device and household.
  - Revocation is immediate.
  - This is tested in `SyncApiTest`.
- **Before any real personal data reaches the cloud:** Ed25519 request signing and passkey enrolment replace the bearer secret. Until then the cloud stack holds test data only.
- Multi-user later means adding principals to a `Household` with roles. No redesign is needed.

## Amendment 2026-10-01: enrolment
- **Enrolment code.** `POST /v1/enrol`, authorised by a 48-character enrolment code that CDK generates in Secrets Manager (`meka-os-<env>/enrol-token`). The endpoint is disabled when no code is configured.
- **What a device gets.** Each device receives its own 256-bit secret, shown once, stored on the device and kept server-side only as a SHA-256 hash.
- **Re-enrolling** a device id rotates its secret and clears any revocation.
- **Revoking everything.** To revoke all future enrolments, rotate the enrolment code. To revoke one device, revoke that device.
- **Household.** Both devices use the household id `home`.
