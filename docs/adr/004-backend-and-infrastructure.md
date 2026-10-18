# ADR-004: Backend, infrastructure and isolation from trading

**Status:** Accepted, 2026-10-01

## Context
The backend exists for sync, ingestion (calendar, email, fixtures, news), heavy AI and push. It serves one household. The owner's trading systems (Kestrel, Merlin, AuctionTrader) may share the AWS account but must be strongly isolated, and MEKA OS must never hold exchange credentials. Discovery on 2026-10-01 found no existing AWS configuration on the owner's Mac and no cloud resources for AuctionTrader.

## Options
- **Serverless functions (Lambda) + DynamoDB.** Cheapest idle cost, but a poor fit for a relational life graph and ordered op-log queries. Cold starts affect sync.
- **Microservices on ECS/EKS.** Too much operational surface for one user.
- **One small Ktor container + Postgres + SQS + S3.** One deployable, same Kotlin core, relational queries.

## Decision
- **Service:**
  - One Ktor 3.6 service with modules `sync`, `ingest`, `orchestrator`, `executor` and `push`.
  - Deployed as one container on ECS Fargate, ARM64, 0.25 vCPU / 0.5 GB to start.
  - Placed in public subnets with a security group, and **no NAT gateway**, which avoids the classic idle-cost trap. Outbound goes directly via the internet gateway. VPC endpoints are added only where they are cheaper.
- **Data and messaging:**
  - Postgres on RDS (db.t4g.micro, encrypted, automated backups, 14-day PITR). Aurora Serverless v2 is the alternative once scale-to-zero pricing beats it for this workload; revisit at V1.
  - SQS for background jobs, with a dead-letter queue and an alarm.
  - S3 with SSE-KMS for blobs. Vault documents (V2) are encrypted client-side before upload.
- **Secrets:** Secrets Manager holds integration OAuth refresh tokens, envelope-encrypted with a MEKA-specific KMS key.
- **Push:** FCM for Android and APNs for macOS.
- **Infrastructure as code:** AWS CDK in TypeScript (`infra/`), with one stack family per environment (`MekaOs-Dev`, `MekaOs-Prod`).

**Isolation from trading:**
- **Account:** a separate AWS account is preferred via AWS Organizations; this is cheap and the strongest boundary. If the same account is used, isolation is by stack, IAM role, KMS key, secret prefix, database instance and CI deployment role.
- **Tagging:** every MEKA resource is tagged `app=meka-os`. IAM policies are scoped to those tags and to the `meka-os/*` resource name prefix.
- **Deploy role:** the CI deploy role for MEKA can only assume `cdk-meka-*` roles.
- **No shared credentials:** no shared database, no shared secrets, no shared VPC security groups.
- **Trading integration** uses a signed event inbox only. Trading systems POST HMAC-signed events to `/v1/trading/events`. MEKA returns approvals as Ed25519-signed approval tokens, which the trading system verifies itself (ADR-006). MEKA never holds exchange keys.

## Consequences
- Estimated steady-state infrastructure is about **£35–45/month**: Fargate at about £8, RDS micro at about £13, ALB at about £16, plus small amounts for S3, SQS, KMS and logs. AI costs are separate (ADR-006).
  - **Cost lever, to revisit at V1:** API Gateway HTTP API + VPC Link in front of the service instead of the ALB, saving about £15/month at our request volume.
  - A dev stack without a certificate synthesises an *internal* ALB, so it is never exposed.
- M0 deploys only `sync` plus Postgres. Ingestion comes later.
- An AWS account and credentials are an owner action. CDK synth and the unit-level snapshot tests run in CI without credentials.

## Revisit if
- A second active user (the owner's wife) arrives. The per-household model already supports it (ADR-008).

## Amendment 2026-10-01: account, entry point, deploys
- **Account.** A dedicated AWS Organizations member account, `meka-os`, sits under the owner's existing account. The existing account holds Kestrel and its Hyperliquid key. MEKA reads untrusted content all day, so it must not share an account with anything that can move money. Member accounts cost nothing and appear on the same bill.
- **Public entry.** CloudFront (`*.cloudfront.net`, TLS 1.2+, HTTPS only) sits in front of an ALB on HTTP. This gives HTTPS without buying a domain.
  - The ALB accepts traffic only from CloudFront's origin-facing prefix list, and only when the request carries a secret `X-Origin-Verify` header. Anything else gets a 403.
  - A custom domain can be added later as a CloudFront alias.
- **Deploys.**
  - **Auth:** GitHub OIDC, assuming `meka-os-github-deploy`. Only the `main` branch of `mekaminu/meka-os` can assume it. No AWS keys are stored anywhere.
  - **Bootstrap:** a one-time CloudFormation template (`infra/bootstrap/meka-os-account-setup.yaml`) creates that role plus a monthly budget alert.
  - **Workflow:** `deploy.yml` runs after CI passes on main:
    1. CDK bootstrap
    2. deploy the registry stack
    3. push the linux/arm64 image
    4. deploy the service stack
    5. smoke-test `/health` through CloudFront
  - **Scope of the deploy role:** it is administrator *inside the dedicated account only*. That is accepted because the account holds nothing but MEKA OS.

## Amendment 2026-10-07: deploy only what changed, measured from the last deploy
- The service stack records the commit it was deployed from as the `DeployedCommit` output (CI passes `-c commit=<sha>`).
- `deploy.yml` decides "server changed" by diffing that commit against the one CI just passed, not against `HEAD~1`. A server commit whose CI failed, followed by an app-only fix, now still deploys. If no deployed commit is recorded (or it isn't an ancestor, or the stack can't be read), it deploys.
- Watch item: a KMS key left over from an early stack attempt (the data key is `RETAIN`ed by design, so a deleted stack leaves its key behind). Only the key behind `alias/meka-os-dev-data` is in use; any other `app=meka-os` key with no alias can be scheduled for deletion by the owner (KMS keeps it 7–30 days, cancellable). Build runs don't delete keys.

## Amendment 2026-10-07: push is a content-free "sync now"
- FCM HTTP v1 from the service, authenticated with the Firebase service account the owner pastes into `meka-os-dev/fcm/service-account` (read at use time, cached 5 min; `{}` = push off, nothing sent). The service signs its own RS256 JWT-bearer assertion with the JDK and exchanges it at `https://oauth2.googleapis.com/token` (cached until 5 min before expiry); no Google client library.
- Devices register their FCM token at `POST /v1/push/token` (signed, keyed devices only; an empty token removes it). One address per device in `push_token` (V6, additive); revoked devices are never woken; a token FCM reports as unregistered is forgotten.
- When a device stores ops, the household's other devices with an address get one data-only message, `{"t":"sync"}`, normal priority, collapse key `sync`, TTL 1 h. Coalesced per device: at most one wake every 20 s, with one trailing wake at the end of the gap so the last edit always gets through. Sent off the request; a failure only means the device catches up at its next periodic sync.
- Nothing about the owner's data goes to Google: no titles, ids or counts, only that a device was woken. The change itself travels over the normal signed sync.
- APNs for the Mac waits on the Apple Developer Program (Needs Meka #6); the Mac app's long-poll keeps it current while it's open.

## Amendment 2026-10-09: MEKA's voice is Amazon Polly in MEKA's own account

- Approved by Meka 2026-10-08 (build plan V1, "Weather and a voice", item 3). No new provider or key: the ECS task role gets `polly:SynthesizeSpeech` and `polly:DescribeVoices` only (Polly has no resource-level scoping for these, so the resource is `*`); env `MEKA_SPEECH_ENGINE=polly` turns the routes on. Infra test pins the two actions.
- `POST /v1/speech/voices` and `POST /v1/speech/speak` (signed, keyed devices only; the release publisher is refused). Only MEKA's own reply text is sent (≤ 600 characters per request, sentence by sentence), never what Meka said. Polly runs in the service's region (eu-west-2); en-GB voices on their best engine there (generative, else neural; standard-only voices aren't offered).
- Nothing of the text or audio is stored or logged. `speech_usage` (V11, additive) counts clips and characters per UTC month; at 1M characters the server answers `over` and the device's own voice speaks until the 1st (≈ $30 at generative's list price at the very most; Meka's use is a few thousand characters a day).
- The call assistant's `<Say>` uses the same voice (2026-10-09): the synced `context_mode/voice` choice when the server offers it, else the server's default, as Twilio names it (`Polly.<Name>-Generative` where Twilio has it, else `-Neural`; a name Twilio lacks falls back to `Polly.Amy-Neural`). Twilio's own Polly use is billed by Twilio, not in MEKA's AWS account.

## Addendum 2026-10-09: the Health screen's server half

- `POST /v1/health/household` (signed, keyed devices only; the release publisher is refused; Reliability first, item 3). It answers only what the server alone knows: whether wake-ups are set up and the calling device has a push address (`on` · `missing` · `off`), whether the call assistant's phone service and MEKA's voice are configured (booleans), and the server's clock (devices warn when theirs is more than 5 minutes off). Never a secret, token, address or count of anyone else's devices. No new resource, table or provider; calendars and feeds still come from `/v1/integrations/list` and the AI from `/v1/ai/status`. An older server without the route answers 404 and the screen says "Couldn't check".

## Addendum 2026-10-09: Setup's "Mac" step

- `POST /v1/health/household` also answers `macs`: how many Macs are connected to the caller's own household (devices not revoked whose id starts with `mac`, as the Mac app makes them). Only the count, never a device's name or id, and only for the caller's household, so the line above still holds for anyone else's devices. The Setup checklist (Ask → More → Setup) shows "Mac · Connected" or "Install MEKA on your Mac". Optional on the wire: an older server leaves it out and the step says "Couldn't check". No new table, column or resource.

## Addendum 2026-10-10: callers' messages transcribed by Amazon Transcribe in MEKA's own account

- Call assistant polish 8d i (Meka asked 2026-10-09 after Twilio's transcript of his test read "Of the origin."). Once a caller's recording is kept in MEKA's blob bucket (8c), the server starts an Amazon Transcribe batch job on it (en-GB, MP3), with the result written back to the same private bucket beside the recording (`voice/<household>/<held id>.transcript.json`, encrypted with MEKA's KMS key, inside the `voice/` 30-day lifecycle backstop). The server polls it (every 4 s, at most 5 minutes), reads the result once, then deletes the result object (every version) and the job, whatever happened. Nothing leaves MEKA's AWS account; no new bucket, key or provider.
- The task role gets `transcribe:StartTranscriptionJob`, `GetTranscriptionJob` and `DeleteTranscriptionJob` only (resource `*`, as for Polly); Transcribe reads the input and writes the output with the task role's existing bucket and key grants. Env `MEKA_TRANSCRIBE_ENGINE=transcribe` turns it on; infra test pins the three actions.
- With it on, a message's words are written once Transcribe has had its go (the apps show "Transcribing…" meanwhile); Twilio's words are the fallback when Transcribe fails, times out or hears nothing, and "no words came through" when neither has any. A message dismissed meanwhile gets no words; Done also deletes a transcript result still in the bucket. Cost: about $0.024 a minute of audio, pennies a month. The words remain display-only untrusted content (ADR-006).

## Addendum 2026-10-14: Google's Chirp 3 HD voices beside Polly

- Meka asked for it 2026-10-14 08:04 ("build this, the next thing to build"; Needs Meka #33 enables the API). A second `SpeechEngine`, `GoogleSpeech`, calls Cloud Text-to-Speech `v1/voices` and `v1/text:synthesize` (MP3, plain text) for the **Chirp 3: HD** voices, British (`en-GB-Chirp3-HD-<Name>`, id `<Name>`) and American (`en-US-…`, id `<Name>-US`). The API key is read at use time from the new secret `meka-os-dev/speech/google` (`{"api_key": …}`, `{}` until pasted), else the travel times' `meka-os-dev/maps/google` (the same key can be allowed for both); neither = Google's voices hidden and Polly as before. The key goes in the `X-Goog-Api-Key` header, never a URL or log. Only MEKA's own words are sent, as to Polly.
- Google's voices are listed first in `POST /v1/speech/voices` (each with `provider: "google"`, engine `chirp3hd`) with a `google` block (state on · nokey · failed, the month's characters, the free 1M, the 900k fallback point, the last failure). Polly's first voice stays the default: a Google voice speaks only when chosen.
- Cost: Google's characters are metered in `speech_usage_google` (V26, same shape as `speech_usage`, additive). Past 900k in a UTC month, or when Google fails or takes over 5 s, the same words are said once by the default Polly voice, so the free tier is never exceeded without Meka's yes. Polly's 1M cap counts only Polly's characters.
- ~~Calls keep Twilio's Polly `<Say>`~~ (slice 1). **Slice 3 (2026-10-14):** callers hear a chosen Google voice. The phone service only speaks its own Polly voices, so the server makes the call assistant's nine fixed lines once per Google voice (`CallClips`, ~700 characters on Google's meter, under the same 900k fallback), keeps them in MEKA's own bucket under `voice/clips/<voice>/` (the existing 30-day `voice/` lifecycle renews them monthly, so a deploy costs nothing) and in memory, and answers with `<Play>` of `GET /v1/voice/clip/<key>.mp3` — a salted hash, new each start, serving only those clips (404 otherwise). A line whose clip isn't ready is said with Polly's `<Say>` and the voice's lines are made in the background (at most every 5 minutes after a failure; a warm-up also runs every 30 minutes), so a call never waits on Google. Health's server half gains `speechGoogle` (on · failed) and `speechGoogleFailedAt` (when a chosen Google voice last failed and Polly said it), shown in Health's "Navilife's voice" row.

## Amendment 2026-10-18: the Vault's copies (Vault slice 2b)
- A Vault document's photo or PDF is sealed on the phone or Mac with the household's Vault key (ADR-005 amendment 2026-10-18) before it is sent, so the service and S3 only ever hold ciphertext. `POST /v1/vault/file/put|get|delete` (keyed phones and Macs only, never a watch or the publisher) keep it in the existing blob bucket under `vault/<household>/<copy id>.bin` (SSE-KMS on top, versioned; a delete removes every version). No new resource; at most 1.45 MB a copy, a few documents, so pennies a month.
