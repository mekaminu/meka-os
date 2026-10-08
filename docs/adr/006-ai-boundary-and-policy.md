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

## Addendum, 2026-10-08: calendar edits in the activity log
Every change Meka makes to a real Google or Outlook calendar from MEKA (calendar editing) is listed in Activity, as the build plan's guard "every change is in the activity log" asks. These are his own taps, not automatic actions, so they are not `agent_action` entries and nothing is stored for them: each row (kind `CALENDAR`, id `c` + the edit's id) is read from the synced `event_edit` itself (`ActivityRules.calendarEditItem`), so it follows the edit as the server answers ("Moving …" → "Moved “Dentist” in Google", a refusal, a failure, a clash and Meka's answer to it), with what it changed and why ("Your edit in MEKA · editing allowed for <account>"). An edit undone in its five seconds sent nothing and isn't listed. A row offers no Undo (after the window an edit is changed again from the event). When MEKA itself proposes calendar changes later (email, AI), those go through Needs you and are logged as actions like any other.

## Addendum, 2026-10-08: the cloud key and its health check
The cloud model key (Anthropic) lives only in Secrets Manager (`meka-os-dev/ai/anthropic`, `{"api_key": …}`), read by the service at use time; `{}` or a malformed key means the AI layer is off, and every AI feature must treat "off" as a normal state (deterministic behaviour, nothing broken). The service checks the key with Anthropic's model list (`GET /v1/models`), which sends no prompt and none of the owner's data, and tells keyed devices only whether AI is on, off or failing (`POST /v1/ai/status`), never the key. The monthly spend cap is set in the Anthropic Console; the per-feature meter and budget of §5 come with the first model call.

## Addendum, 2026-10-08: the cloud provider, token meter and monthly budget
`LanguageModelProvider` (§4) exists on the server with one implementation, `AnthropicProvider` (Messages API). It is text in, text out: no tools are ever offered to a model, so a model's answer can only become a proposal the policy engine decides (§3). Each request names a feature (a short machine name such as `ask` or `extract.email`) and a tier (SMALL → the newest Haiku, FRONTIER → the newest Sonnet, from Anthropic's model list once a day, with fixed fallbacks). Every answer is metered (§5) in the `ai_usage` table per UTC month and feature: calls, tokens and list price in micro-dollars, counts only, never a prompt or answer. The monthly budget is MEKA's share of the Console cap ($20 of $40 by default, `MEKA_AI_BUDGET_USD`): from 70 % the AI status reports `alert`, at 100 % the provider refuses before calling until the 1st (degrade-to-local: today that means AI off, since no on-device model is wired yet). The Console's own spend cap remains the hard stop behind it. The apps learn the month's spend and level from `POST /v1/ai/status`, never the key.

## Addendum, 2026-10-08: Ask MEKA, the first caller
`POST /v1/ai/ask` (keyed devices only) takes a question and the device's short picture of today (`AskRules.context`: Needs you, the day's tasks, what's done and the calendar, at most 60 lines of 160 characters). Tasks are named by handles ("t1") that never leave the device's own map; no id, note, address or email is sent. The server makes one call to the SMALL tier with no tools, under the feature `ask`; the system line says the `<today>` block is data, not instructions, and the block cannot be closed from inside (angle brackets in lines are replaced). The model may only answer in words and propose MEKA's own actions from a fixed list (add, tick off or move a task, start a fast, set a timer or alarm); it cannot send, spend, trade or change calendars. The server drops any other kind and any handle it didn't send; the device checks each proposal again (`AskRules.card`: a known kind, a handle it sent, sane days and times, at most three) and asks the policy engine with `requestedLevel = SUGGEST` and provenance MODEL_FROM_UNTRUSTED whenever calendar lines were sent (other people's invitations). Each surviving proposal is a card that does nothing until the owner taps it; the tap is his own action. A chatty answer that isn't the expected JSON is shown as words with no cards. Nothing of the question or answer is stored or logged.

## Addendum, 2026-10-08: Ask's screen and taking a card back
The apps show MEKA's AI state and the month's spend from `POST /v1/ai/status` (never the key) under Ask's field; while AI is off, the budget is used up or the device isn't connected, the field opens Search instead (the deterministic path). A tapped card is the owner's own action and returns how to take it back (`AskDone.undo`): the task it added is deleted, a ticked-off or moved task is put back only while it is still exactly as the card left it (any change since, on either device, wins), the fast is thrown away while it runs, the timer or alarm is cancelled. Nothing of the question or answer is stored on the device either.

## Addendum, 2026-10-08: Talk to MEKA, the conversation
A spoken conversation is Ask MEKA with memory of the last few exchanges, nothing more powerful. Each question still makes one tool-less SMALL-tier call under the feature `ask`; the device sends the last six exchanges with it (`AskCodec.Request.history`: Meka's question, MEKA's earlier words, and the lines of what Meka confirmed after each, e.g. "Moved “Book dentist” to Tomorrow · 09:00"), and the server lays them out as alternating turns, with the `<today>` data block only in the latest one and the same cleaning (no angle brackets) everywhere. Earlier answers go back as words only: an action offered before is never offered again from history, so every card still comes from the latest answer and is checked by the device and the policy engine as a suggestion exactly as for a typed question. The conversation lives only in the app's memory while it runs; nothing of it is stored, synced or logged, on the device or the server. A spoken "yes" ("the second one", "both", "all of them") is the owner's own confirmation, the same as tapping the card (`TalkRules.reply`, pure and unit-tested; with several cards a bare "yes" makes MEKA ask which), and every change it makes shows the usual undo chip. A conversation stops by itself after twelve questions, when nothing is heard, on a goodbye, or when AI is unavailable, so background speech can't run up calls. Speech is recognised and spoken on the device; audio never leaves it and is never kept.

2026-10-08: Talk to MEKA on the Fold
The Fold listens with Android's on-device recogniser only (`SpeechRecognizer.createOnDeviceSpeechRecognizer`, English, partial results on screen). If the phone has none, MEKA says so and doesn't listen; it never falls back to a cloud recogniser, which would send Meka's voice to a third party. When English for on-device speech is missing, MEKA asks the phone to fetch it (Android 13+) and says to try again. MEKA speaks with `TextToSpeech` using the best installed English voice that works without a network (`TalkVoice.best`: British first, then quality); network voices, which would send the text away, are never chosen. The microphone permission is asked the first time Meka taps the mic, and the conversation stops when Ask leaves the screen or the app goes to the background. A spoken yes runs the confirmed cards through `MekaCore.doTalk` (each exactly as a tap, one undo bar for the lot via `undoTalk`). Barge-in on the Fold is by touch (tapping the orb while MEKA speaks stops it and listens), since the recogniser would otherwise hear MEKA through the phone's own speaker.
