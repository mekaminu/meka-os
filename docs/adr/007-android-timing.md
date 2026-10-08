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

## Addendum, 2026-10-07: the first CLOCK reminders
Event reminders (Remind me / Leave by on a calendar event) are the first notices classified `precision = CLOCK`. The Fold now declares `SCHEDULE_EXACT_ALARM`; the governor's alarm is `setExactAndAllowWhileIdle` only while the next wake is a CLOCK reminder and `canScheduleExactAlarms()` is true, else `setWindow` with a 5-minute window. Android 13+ doesn't grant the permission by default, so nothing changes until Meka allows "Alarms & reminders": the event detail says "Reminders may be up to 5 min late · Allow on time" and opens that setting. `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` re-runs the governor (with boot and update). The governor drops a reminder that couldn't post before the event started rather than sending it late. Still no `USE_EXACT_ALARM`.

## Addendum, 2026-10-07: ongoing notifications
The ongoing ("live") notifications for the next event's countdown and a running fast (Outside the app) never use an exact alarm: their clocks are chronometers the system ticks itself, so MEKA only re-posts when what they say changes. Their own alarm is `setWindow` with a 5-minute window, for when the list changes by itself (an event comes into its 30-minute window, starts or leaves it; a fasting goal is reached; midnight). A countdown can therefore run a few minutes past zero before it turns into "Started 14:00". No new permission beyond `POST_PROMOTED_NOTIFICATIONS` (normal, Android 16's live-updates treatment).

## Addendum, 2026-10-07: home-screen widgets
The Next up, Needs you and Fast widgets (Outside the app, slice 2) are plain RemoteViews redrawn only when what they say changes; their countdown and fast timer are `Chronometer`s the launcher ticks. Their alarm is `setWindow` (`RTC`, not a wakeup: a sleeping phone isn't looking at its home screen; the alarm runs when it wakes) with a 5-minute window at the view's next change (an event entering the hour or the last quarter hour, starting, ten minutes in, ending; a fasting goal; midnight), never more than 30 minutes away, and only while a MEKA data widget is placed. `updatePeriodMillis` is 0. No new permission.

## Addendum, 2026-10-08: the wake alarm
The wake alarm (Alarms, slice 1) is the table's "wake-up style" row, which Meka opted into by asking for alarms (2026-10-07): `setAlarmClock` (the alarm icon, and the phone's "next alarm" the bedside clock reads) while "Alarms & reminders" is allowed, else `setWindow` with a 5-minute window and the shutdown pane says "May ring up to 5 min late · Allow on time". One alarm at a time, re-registered from the synced data at process start (boot, update). It rings in a `mediaPlayback` foreground service started from that alarm (sound on the alarm stream with a 30-second volume ramp, vibration), with a full-screen notification (`USE_FULL_SCREEN_INTENT`) for the ringing screen over the lock screen; it stops after 10 minutes unanswered and never rings late after the phone was off. Still no `USE_EXACT_ALARM`.
