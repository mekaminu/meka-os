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
