# Kestrel → MEKA trading approvals: integration spec

Status: MEKA's side built 2026-10-12 (build plan V1, "Trading approvals from Kestrel", slices 1 and 2: the desk, then
Meka's fingerprint signature on every yes). **Kestrel's side is not
changed by unattended build runs**: it lives in Meka's live trading system (~/Meka/Trading Bots) and is done in a
supervised session (Needs Meka #27).

## What it is for

Before Kestrel places a trade that needs Meka's say-so, it asks MEKA. The request shows on the Fold and the Mac as a
card in Needs you ("TRADE · KESTREL", the title and up to four detail lines, when it expires) with a "Needs a decision"
notification that turns Critical in its last 15 minutes. **Approve needs Meka's fingerprint** (Touch ID on the Mac);
Decline is a tap. Kestrel reads the answer and acts on it once. If nobody answers in time the request expires and
Kestrel must treat that as **no**.

## Hard rules (both sides)

- MEKA never holds, receives or forwards an exchange key, API secret or login. Kestrel keeps its own credentials; the
  request carries only what Meka needs to read to decide.
- Kestrel signs every request with its own P-256 private key. MEKA knows only the public half. Nothing Kestrel sends can
  make MEKA approve anything: title and lines are shown to Meka, never followed (ADR-006).
- Kestrel may ask only for `trade` approvals and may call only the three routes below. At most 10 requests can wait at once.
- Expired, declined, an error, no answer, or MEKA unreachable all mean **don't trade**. Only `state: "approved"` with a
  `decisionKey` **and an `answerSignature` that verifies with one of the approval keys Kestrel pinned** (below) is a yes.
- Kestrel acts on a given `decisionKey` at most once (store it with the order; an order already placed for that key is
  never placed again).

## Keys (one-off, in the supervised session)

On Kestrel's machine (the private key never leaves it):

```sh
openssl ecparam -name prime256v1 -genkey -noout -out kestrel-meka.pem
# PKCS#8 DER base64: what Kestrel signs with (keep it in Kestrel's own secret store)
openssl pkcs8 -topk8 -nocrypt -in kestrel-meka.pem -outform DER | base64 | tr -d '\n'
# X.509 SubjectPublicKeyInfo DER base64: the public half for MEKA
openssl ec -in kestrel-meka.pem -pubout -outform DER | base64 | tr -d '\n'
```

AWS console (eu-west-2) → Secrets Manager → `meka-os-dev/approvals/kestrel` (made by MEKA's stack, `{}`) → Retrieve
secret value → Edit → Plaintext `{"public_key":"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE…"}` → Save. MEKA reads it within a
minute; no redeploy. Until then every request is refused with 403.

## Signing a request

Every request is an HTTPS `POST` with a JSON body to MEKA's server (the `SyncUrl` stack output, `https://….cloudfront.net`):

| Header | Value |
| --- | --- |
| `Authorization` | `Asker kestrel` |
| `X-Meka-Time` | now, Unix milliseconds (accepted within ±5 minutes) |
| `X-Meka-Nonce` | 16–64 random characters, never reused |
| `X-Meka-Signature` | base64 of the ECDSA P-256 / SHA-256 signature (DER) over the canonical string |
| `Content-Type` | `application/json` |

Canonical string (newline-separated, no trailing newline):

```
MEKA1
POST
<path, e.g. /v1/approvals/request>
<X-Meka-Time>
<X-Meka-Nonce>
<lowercase hex SHA-256 of the exact body bytes>
```

A wrong signature, an old time, a reused nonce or a signature made for the other path is refused with 401.

## `POST /v1/approvals/request`

```json
{
  "ref": "2026-10-12T21:40:00Z-btc-1",
  "kind": "trade",
  "title": "Buy 0.05 BTC at market",
  "lines": ["About £2,410", "Stop loss 5% · take profit 12%", "Strategy: Merlin breakout"],
  "expiresAtMs": 1760306400000
}
```

- `ref`: Kestrel's own reference, unique per decision: letters, digits and `._:-`, 1–64 characters. Sending the same
  ref again (a retry) never makes a second card; it answers the first one's status.
- `kind`: `trade` (anything else is 403).
- `title`: one line, cut at 120 characters. `lines`: up to 4, each cut at 120. Plain text; shown, never obeyed.
- `expiresAtMs`: when the decision stops being useful; at least a minute ahead, at most a week (a day if left out).

Answers `200` with the status below (`state: "pending"` for a new request). `400` bad body, `403` kind not allowed or no
key configured, `429` ten requests already waiting.

## `POST /v1/approvals/status`

Body `{"ref": "<ref>"}`. Answers `200`:

```json
{
  "id": "ap…",
  "ref": "2026-10-12T21:40:00Z-btc-1",
  "state": "pending | approved | declined | expired",
  "askedAtMs": 1760305200000,
  "expiresAtMs": 1760306400000,
  "title": "Buy 0.05 BTC at market",
  "lines": ["About £2,410", "Stop loss 5% · take profit 12%", "Strategy: Merlin breakout"],
  "decidedAtMs": 1760305500000,
  "confirmedBy": "biometric",
  "decisionKey": "…",
  "answerSignature": "MEUCIQ…",
  "answerDevice": "…",
  "answerPublicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE…",
  "answerMessage": "MEKA-APPROVAL1\n…"
}
```

`title` and `lines` are as Meka saw them (cleaned and cut). `decidedAtMs`, `confirmedBy` and `decisionKey` appear only
once answered; the four `answer…` fields only on an approval whose signature MEKA's server checked (below). `404` for a
ref Kestrel never sent.

Polling: every 15 s while pending is plenty (Meka's answer reaches the server within seconds of his tap); stop at
`expiresAtMs`. Each poll is a fresh signature and nonce.

## `POST /v1/approvals/keys`

Body `{}`. Answers `200` with the approval keys of Meka's devices, each registered by that device itself:

```json
{"keys": [{"device": "…", "label": "Fold", "publicKey": "MFkwEwYH…", "sinceMs": 1760305200000}]}
```

## Meka's signature on a yes (slice 2)

Each of Meka's devices holds an **approval key**: ECDSA P-256 made in its secure hardware that only his fingerprint
unlocks, once per signature (Fold: an auth-bound Android Keystore key, strong biometrics only, destroyed when a new
fingerprint is enrolled; Mac: Secure Enclave with `.biometryCurrentSet`). Approve signs this exact text (UTF-8,
newline-separated, no trailing newline) inside the fingerprint prompt:

```
MEKA-APPROVAL1
<id>
approved
<kind>                 trade
<source>               Kestrel
<decisionKey>
<decidedAtMs>
<expiresAtMs>
<answerDevice>
<lowercase hex SHA-256 of the title and each line, joined with "\n">
```

MEKA's server only includes the `answer…` fields when every answer field was written by the answering device, that
device registered the same key itself, and the signature verifies. **Kestrel must not rely on that**: it checks itself.

**Pinning (in the supervised session):** call `/v1/approvals/keys` and store the `publicKey`s (with Meka confirming the
devices) in Kestrel's own config. From then on Kestrel trusts only those keys, so a compromised MEKA server can't forge a
yes. A key changes only when Meka changes his enrolled fingerprints (the app says "approval key renewed"); re-pin then.

**Kestrel's check on every `approved` status (any failure = don't trade):**
1. `answerPublicKey` is one of the pinned keys.
2. Rebuild the text from the status: `id`, `approved`, `trade`, `Kestrel`, `decisionKey`, `decidedAtMs`,
   `expiresAtMs`, `answerDevice`, SHA-256 of `title` and `lines`; it must equal `answerMessage`.
3. `title` (and the lines) are what Kestrel sent (Kestrel keeps its titles one line and under 120 characters so they
   arrive unchanged).
4. The ECDSA P-256 / SHA-256 signature `answerSignature` (base64 DER) verifies over the text with `answerPublicKey`.
5. `decidedAtMs` is before `expiresAtMs`, and the `decisionKey` hasn't been acted on before.
