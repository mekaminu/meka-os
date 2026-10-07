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

## Amendment 2026-10-07: device keys as built, and what the enrolment code may do
- **Device keys.** Devices sign with **P-256 ECDSA** (SHA256withECDSA), not Ed25519: it is what the Android Keystore's StrongBox/TEE and the Mac's Secure Enclave both hold in hardware. Each request signs `MEKA1 · method · path · time · nonce · SHA-256(body)`, with a time window and a nonce replay check (`RequestVerifier`). A device registers its key once (`POST /v1/devices/key`, signed with that key); after that its unsigned requests are refused. The bearer secret stays as a second factor.
- **Where the secrets live.** Fold: Android Keystore. Mac: the signing key in the Secure Enclave; the device id, household, secret and server address in small files sealed with a Secure Enclave key-agreement key (`SealedKeyFiles`), so ad-hoc rebuilds don't trigger Keychain prompts. A Mac without a Secure Enclave keeps the Keychain.
- **The enrolment code is narrower than the server's own enrol path.** `POST /v1/enrol`:
  - never brings back a **revoked** device (403 `revoked`); revocation is undone only on the server (`enrol-device` command), which is the owner's own act;
  - can't start a **second household** once one exists (403 `household`);
  - still re-enrols a device that isn't revoked, rotating its secret and clearing its signing key ("Reconnect" after a lost key or a reinstall that kept the device id).
  The apps show both refusals in words (core `Enrolment.refusal`).
- **Not yet restricted:** enrolling a *new* device id into the household. Passkey enrolment (or approving a new device from an existing one) replaces the shared code for that; until then, rotating the code in Secrets Manager (`meka-os-<env>/enrol-token`) after setting up a device is the way to shut it.
