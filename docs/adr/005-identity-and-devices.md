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

## Amendment 2026-10-10: the family page's browser keys (family sharing with Jeanette, slice 2)
- **Who.** A family member Meka invites (Jeanette) uses a small web page served by MEKA's own server (`/family`), not an app and not a device of the household. She is a guest of one shared thing (the shopping list), not a principal: her browser can reach nothing else.
- **The invite.** Meka makes it from a keyed device (`POST /v1/family/invites/create`; list and revoke beside it). The link is `/family#<token>`: a 256-bit random token in the fragment, so it never reaches a server log or a referrer; the server keeps only its SHA-256 (`family_invite`, migration V13, additive).
- **First open = the browser's own key.** The page makes a **non-extractable P-256 key with WebCrypto**, kept in the browser's IndexedDB, and sends `{token, publicKey}` to `POST /family/v1/claim` signed with that key (proof of possession). The first key wins; the same key again is fine (a retried first open), any other is refused (`used`). From then on the token is no use.
- **Requests.** `Authorization: Guest <invite id>` plus the apps' signature headers over the same message (`MEKA1 · method · path · time · nonce · SHA-256(body)`, ±5 min, each nonce once); WebCrypto's raw r‖s signature is checked with `SHA256withECDSAinP1363Format`. No bearer secret: the key is the only credential.
- **Revoke.** `POST /v1/family/invites/revoke` refuses her next request at once and the link can't be opened again. A browser that loses its storage (cleared data, Safari's 7-day limit on a page not added to the home screen) needs a new link.
- **Writes.** Her adds and ticks are server ops (`deviceId` `server`, `by` = her name key) written over each field's current head, so they sync into Meka's Lists like any other change; the page can touch only existing `shopping_item` entities. The page itself is static (CSP `script-src 'self'`, no third-party anything, `no-referrer`).

## Amendment 2026-10-10: linking a watch from a keyed device (Galaxy Watch, slice 1)
- **Why.** A Galaxy Watch joins as a device of its own (its own hardware key, its own replica, synced through MEKA's server), so no watch data goes through anyone else's service (no Wearable Data Layer). Typing the 48-character enrolment code on a watch isn't practical, and the code is the broad path this ADR wants narrowed; this is the "approving a new device from an existing one" path for watches.
- **Flow.** (1) The watch makes a P-256 key in its Keystore and a device id `watch-<16 hex>`, and calls `POST /v1/link/start` (`{deviceId, name, publicKey}`, signed with that key, no bearer): the server answers an 8-digit code that lasts 10 minutes. (2) Meka types the code in Ask → More → Watch on a keyed device (`POST /v1/devices/link`, signed): the server enrols the watch into that device's household **with the key that asked**, so every request it makes is signed from the first. (3) The watch polls `POST /v1/link/status` (signed with its key) and picks up `{householdId, deviceId, secret}` exactly once.
- **Limits.** Only keyed household devices can try codes, each at most 5 wrong codes per 10 minutes, so the 10^8 codes can't be guessed; a code works once; at most 10 links wait at a time (unsigned noise can't grow the list); a start must be signed by the key it names; only ids shaped `watch-…` can join this way; a revoked watch id is never brought back (a reset watch makes a new id).
- **Where it lives.** Waiting links and the secret between approval and pickup are held in the sync task's memory only (at most 10 minutes), like the verifier's nonce cache: no new table, nothing stored but the device row the enrolment writes. A restart just means the watch asks for a new code.
- **Unlink.** `POST /v1/devices/watches` lists the household's linked watches (id, name, linked time; never a secret or key); `POST /v1/devices/unlink` revokes one at once. Both need a keyed device.
