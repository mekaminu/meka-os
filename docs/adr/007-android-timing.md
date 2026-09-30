# ADR-007: Android timing mechanisms

**Status:** Accepted, 2026-10-01. Amendment requested by owner: no exact alarms by default.

## Context
Reminders have different precision needs. Exact alarms cost battery and need a user-granted special permission: `SCHEDULE_EXACT_ALARM` is not pre-granted for apps targeting API 33+. `USE_EXACT_ALARM` is auto-granted but Play-policy-restricted. We distribute outside Play (ADR-010), but we choose the least-privilege path anyway so store distribution stays possible.

## Decision: the least expensive mechanism that meets the UX requirement

| Need | Example | Tolerance | Mechanism |
|---|---|---|---|
| Server-originated, time-sensitive | Fixture time changed; approval request | Seconds, needs network anyway | **FCM high-priority data message**, which wakes the app and posts the notification |
| User-facing clock precision | Leave-time for Logan's football; calendar "starts in 10 min" | Within about 1 min | **`AlarmManager.setExactAndAllowWhileIdle`** if `canScheduleExactAlarms()`, else **`setWindow`** with a 5-min window, and the UI says "reminders may be up to a few minutes late" with a one-tap grant flow |
| Wake-up style | Morning plan ready at a chosen time | Exact, user-visible | `setAlarmClock` only if the owner opts in to that feature (it shows an alarm icon) |
| Soft milestones | Fasting 16h/18h/24h; habit nudges; digest | 5–15 min | **`setWindow` / inexact `set`**, which the system batches |
| Periodic background | Calendar refresh, listener health heartbeat, op-log push retry | 15 min+ | **WorkManager** periodic work (15-min minimum, **unverified** in current docs; long-standing value), expedited one-off work for pending sync on connectivity |

Rules:
- `SCHEDULE_EXACT_ALARM` is declared in the manifest, but no exact alarm is scheduled unless a reminder is classified `precision = CLOCK`. Classification lives in the shared notification governor, so it is testable.
- On `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` and on boot, all pending reminders are re-registered from the local DB, which is the source of truth. When the permission is revoked the system cancels exact alarms, and we downgrade to windowed alarms.
- Fasting milestones never use exact alarms.
- We do not use `USE_EXACT_ALARM`.

## Consequences
- Precision is a property of each reminder record, so the UI can be honest about it.
- The battery/background test suite (Stage 8) asserts that no exact alarm is registered for non-`CLOCK` reminders.
