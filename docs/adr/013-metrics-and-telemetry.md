# ADR-013: North-star metrics and privacy-safe telemetry

**Status:** Accepted, 2026-10-01

## North-star metrics
These are computed from the local audit log and graph. They are counted, never inferred from free text.

| Metric | Definition |
|---|---|
| **Handled without me** | Count of `AgentAction`s with outcome `COMPLETED` at autonomy Level 4, plus items auto-resolved (for example a WaitingFor closed by an incoming delivery email), with no user touch |
| **Prevented misses** | Items with a due time that were completed after a MEKA reminder or escalation, and before their due time |
| **Interruptions** | Notifications MEKA posted at Critical, Action or Heads-up tier. Digest and Silent tiers don't count. Lower is better. |
| **Approval rate** | Proposals approved unchanged ÷ proposals shown |
| **False-positive rate** | Suggestions (commitment extraction, notifications) dismissed as "not relevant" ÷ shown, by source and extractor version |
| **Time returned** | Σ per-action-type estimated minutes saved. The estimates are a user-editable table, shown as an estimate, never as precise. |

- The weekly review shows these metrics.
- Engagement (time in app, opens) is deliberately **not** a success metric. Opens are logged only to detect over-reliance on manual checking.

## Telemetry
- **Technical telemetry** is structured, and its fields come from an allowlist schema. Examples: event name, duration, error class, counts, model id, token counts and cost.
- **No free text leaves the device** in telemetry: no titles, no names, no message content.
- A PII-scrubber test feeds known PII fixtures through every telemetry event constructor and asserts nothing matches.
- The north-star metrics above stay in the user's own database and are synced like other data. They are not sent to a telemetry vendor.

## Addendum, 2026-10-06: weekly review, slice 1
The weekly review (Review tab on both apps, `WeeklyReview` in core/domain) shows the six north-star metrics with no values yet, each saying when it starts counting: they measure what MEKA does on its own (acting, reminding, asking, notifying), and the activity log and approvals that would record those arrive in V1. Interruptions come first (next slice): the governor will record what each device posts at Critical, Needs a decision and Heads-up (counts only, no text) in the user's own synced data. The rest of the review counts what was done, never inferred from free text.

## Addendum, 2026-10-06: weekly review, slice 2
A "Weekly review" card in Today and a Heads-up notice (governor source `WEEKLY_REVIEW`, lowerable) prompt the review from 18:00 on Sunday through Monday, until that week is marked reviewed on either device. Nothing new is stored: the card is computed from the existing `context_mode/review` entity. Interruptions are still the next slice.

## Addendum, 2026-10-06: interruptions counted
Interruptions are now counted. After each governor run, the platform reports what actually reached the person (`MekaCore.notificationsPosted`): on the Fold, posts to a channel that isn't turned off in the phone's settings; on the Mac, posts macOS accepted while Meka is allowed to notify. Only Critical, Needs a decision and Heads-up count; digests (and heads-ups folded into one, which post on the quiet Digests channel / as passive) never do. The counts sit in the user's own synced data (`interruption_day`, ADR-008), never text, never sent to a vendor. A device reports even when nothing posted, which starts the count (`countingSince`); weeks before that say "Counted from …" rather than a misleading 0, and the week-before comparison appears only when that week was counted in full. The work-mode alerts and the after-work nudge on the Fold are posted outside the governor and aren't counted yet; they join when they move onto the governor's channels.

## Addendum, 2026-10-10: prevented misses counted
Prevented misses are now counted (`PreventedMisses` / `PreventedMissRules`, core/domain, non-AI). Each reminder the activity log records keeps what it was about in one new LWW field on its `agent_action` entry, `about`, read from the notice's key: `task:<id>` for a task's Remind me, `renewal:<id>:<cancel-by day>` for a "Cancel or keep?" heads-up; other notices (events, the brief, fasts…) carry none, since they are about things that happen rather than things you finish. A week's count is the tasks with a due time ticked off after such a reminder and no later than their due time, plus the renewals renewed, paid, cancelled or closed after their heads-up and by the end of that cancel-by day, each once, in the week it was done. Counting begins with the first reminder logged with its subject (reminders logged before this have none), so earlier weeks say "Counted from …" and the first week "counting since …". Ids only; nothing leaves Meka's own synced data.

## Addendum, 2026-10-12: phone update failure reports
When a phone update doesn't go in, the Fold reports it to MEKA's own server (`POST /v1/releases/failure`, signed; never a vendor) so a build run can see why (Meka's request, 2026-10-12). Allowlisted fields only (`UpdateFailureCodec`): the stage, Android's install status, Android's own status message or the download's error class and message (system text about the install, never Meka's content; one line, at most 300 characters), the installed and offered build numbers, a free-storage band (never the figure), the network type (wifi · mobile · other · none) and the attempt count. The server keeps the household's newest 20; GitHub's release publisher may read the newest 10 and prints the last 14 days on its own job.

## Addendum, 2026-10-12: Fold posture reports
To find out why the bedside clock doesn't appear on Meka's Fold 8, the Fold sends its last 20 posture changes to MEKA's own server (`POST /v1/diagnostics/fold`, signed; never a vendor), about 20 s after the posture settles, retrying 15 minutes after a failed send (bedside clock, slice 2). Allowlisted fields only (`FoldPostureCodec`): the phone's model name (e.g. "SM-F966B", at most 40 characters), when it sent, and per change its time and the reading line Health already shows (WindowManager's state, orientation, separating and occlusion, the window's size in px, the hinge angle and the posture worked out; at most 200 characters). No location, no content. The server keeps only the household's newest report; GitHub's release publisher may read it and prints it as notices on its own job.
