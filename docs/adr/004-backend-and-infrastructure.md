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
