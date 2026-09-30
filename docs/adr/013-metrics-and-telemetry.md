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
