# ADR-006: AI boundary, policy engine and prompt-injection defence

**Status:** Accepted, 2026-10-01

## Context
MEKA OS reads untrusted content (email, messages, web, news) and can take actions (send, archive, schedule, approve). A model that has read attacker-controlled text must not be able to cause an action outside policy. Costs must stay controlled.

## Decision

### 1. Policy is code, not prompt
A deterministic `PolicyEngine` in shared Kotlin (`core/policy`) decides every action. The same code runs on device and server.
- **Input:** the `ActionRequest`, which has an action type, domain, target, provenance and reversibility.
- **Output:** `Permit`, `RequireApproval(reason)` or `Deny(reason)`.
- **Autonomy levels** are set per (domain, action):
  - 0 Observe
  - 1 Suggest
  - 2 Prepare
  - 3 Ask approval, then execute
  - 4 Execute under explicit rule
- **Hard rules that configuration cannot override:**
  - A global kill switch denies all autonomous execution.
  - Personal message sending is capped at Level 3.
  - Financial transactions and trading approvals are always Level 3 with biometric confirmation.
  - Level 4 is only for actions marked reversible and on an allowlist.
  - Daily caps apply per action type.
  - Any request whose provenance includes untrusted content may never lift its own autonomy level.

### 2. Quarantined extraction (dual-LLM pattern)
- Untrusted content passes only through extractors that have **no tools**. They output JSON validated against a versioned schema, such as `ExtractedCommitment`, with fields that are length-limited and type-checked.
- Extracted fields carry `provenance = untrusted(source)`.
- Planning and actioning components never receive raw untrusted text. Where a draft reply must quote content, the quote is a data field that is rendered, never interpreted.

### 3. Actions are proposals
- A model proposes an `ActionRequest`, which the policy decides.
- Execution goes through the idempotent executor. The idempotency key is created at approval time, and the external side effect is recorded before it is acknowledged.
- Every step writes an `AgentAction` audit record, so "Why did you do this?" is answerable.

### 4. Provider router
- `LanguageModelProvider` is an interface with implementations for on-device and cloud models. The router chooses by task type, data sensitivity, connectivity and remaining budget.
- **On-device on Mac:** Apple Foundation Models (macOS 26+, Apple silicon with Apple Intelligence on). It gives an 8K context on OS 27, guided generation with `@Generable`, and tool calling (not used by extractors). macOS 27 also adds a `LanguageModel` protocol and an optional Private Cloud Compute model.
- **On-device on Android:** ML Kit GenAI / Gemini Nano via AICore. The Prompt API is in beta, and supported devices include the Galaxy Z Fold7 and later but **not the Fold6**. A fallback is required.
- **Cloud:** small and frontier models behind the same interface. Keys live server-side only.

### 5. Cost controls
- Deterministic pre-filters run before any model: sender rules, `List-Unsubscribe`, receipt templates.
- There are per-feature token meters and a monthly budget, with a soft alert at 70% and degrade-to-local at 100%.
- Embeddings are computed on device where possible.

### 6. Evaluation
- Extraction precision is measured on a locally held labelled set (`evals/`, git-ignored data).
- No domain may move above Level 1 until precision reaches at least 90% on its eval set.

## Consequences
- **M0 contains no AI,** by instruction. M0 does contain the `PolicyEngine`, the autonomy model, the kill switch and the audit record types, with tests, so every later AI feature plugs into an enforced boundary.
- **Prompt-injection tests,** such as a malicious email asking to forward documents, run in CI from M2 onwards.

## Addendum, 2026-10-07: the activity log (AgentAction)
The audit record of §3 is live as the `agent_action` entity (`ActivityLog` in core/domain), ahead of any automatic action. Each entry holds when, what kind (REMINDED, DIGEST, CHANGED), what MEKA did and why in words (the rule or setting behind it, e.g. "Cancel-by dates · Heads-up"), the rule's machine name, the autonomy level for actions, and for a change the before and after value of every field it touched. `ActivityLog.act` is the only way an automatic action may change the owner's data: it reads each field's current value, makes the change and records both, so `undo` can restore exactly what is still as MEKA left it (a field changed since, or in conflict, is left alone; an entity MEKA created is deleted, not blanked). Entries are written by MEKA from the owner's own data, are shown as plain text only, and stay in his synced data; the text never reaches telemetry. Reminders and digests are logged from what each device actually posted; one entry per notice key across devices.
