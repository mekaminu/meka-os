# MEKA OS build plan (the build board)

This file is the single source of truth for what is built, what is next and what is blocked. Unattended build runs
read it first and update it last. The owner reads it to see progress.

Status marks: `[x]` done · `[~]` in progress (partly landed) · `[ ]` not started · `[!]` blocked on the owner (see "Needs Meka").

## Rules every build run follows

1. **Hard constraints (never break, never "temporarily" relax):**
   - MEKA OS never holds trading or exchange credentials. Trading is event/approval based only.
   - Personal message sending is never more than "ask me, then send" (autonomy Level 3).
   - No unofficial WhatsApp client libraries. Messaging goes through the Android notification listener only.
   - Financial actions and trading approvals always need biometric confirmation.
   - Untrusted content (email, messages, web, news) never raises its own autonomy level (ADR-006).
2. **One item per run.** Take the first unchecked, unblocked item below. If it is too big for one run, land a
   working slice, mark it `[~]` and write what remains under the item.
3. **Main stays green.** Run `tools/core-verify.sh` and the relevant Gradle tests locally where possible, push, then
   watch CI. Pushes to `main` deploy the backend after CI passes. If CI fails, fix it in the same run or revert.
4. **Database migrations are additive only** (new tables/columns). Never drop or rewrite the owner's data.
5. **No new paid services, no new AWS resources that cost more than ~£2/month, no new third-party data sharing**
   without the owner's yes. Put the question under "Needs Meka" and skip to the next item.
6. **Animation is part of done.** Every new screen follows the motion catalogue below and honours reduced motion.
7. **Both apps.** A feature is done when it works on the Fold and the Mac (Mac may use a simpler layout).
8. **Dark is the default theme**; every screen must look right in Dark and Light.
9. **Commit messages** start with the milestone (`M1:`, `V1:`, `V2:`). Update this file in the same commit.
10. **Actions minutes are finite** (2,000 free standard minutes a month, then Meka's $10/month budget, which stops at
    the limit). Docs-only commits don't trigger CI. Land at most **8 code pushes to `main` per UTC day** (count the day's
    `ci` runs with event `push` first; if 8 already ran, stop with "Daily CI allowance used."), and don't chain the next
    run with fire_trigger. The `macos` job (about 10x the cost of Linux) runs weekly and on manual dispatch only, and
    build runs dispatch it **at most once a day, at night**: the first run at or after 21:00 UTC dispatches `ci` once if any
    commit since the last green dispatched/scheduled `ci` run touched `macos/`, `core/` or the build files. A Mac-touching
    item can be marked done before then ("Mac checked nightly"); if the nightly run is red, the next run fixes it first.
    If CI jobs sit queued with no runner, the allowance is used up: stop without pushing code; it resets on the 1st.
11. **Stop rule.** If something can't be undone and could reasonably go either way, don't do it: write it under
    "Needs Meka" and move on.

## Motion catalogue (applies to every screen)

Tokens live in `design/tokens/tokens.json` (`motion`). Reduced motion = short cross-fade, no movement.

| Moment | Motion |
|---|---|
| App open | Greeting fades up, sections stagger in 40 ms apart |
| Theme change | Every colour blends across (`themeBlend`) |
| Complete a task | Ring fills, check draws, row compresses and leaves; light haptic (Android) |
| Up next changes | Card content cross-slides; old item settles into the list |
| Plan my day | Timeline blocks cascade in; Apply sends blocks into Today with a shared transition |
| Sync | Status dot breathes while syncing; brass pull-to-sync ring |
| Switch section (shell) | Content slides an eighth of the width the way you moved (Mac: pushes along the sidebar); lit pill springs across; tick haptic |
| Sheets and panes | Spring up from the bottom (phone) / scale-fade (Mac); Fold unfold morphs list→two-pane |
| Habit tick | Circle pops with spring, streak number rolls |
| Fasting | Ring sweeps continuously; soft glow at goal |
| Goals | Progress bars fill on appear |
| Weekly review | Numbers count up; tiles stagger in |
| Approvals | Swipe to approve with spring; card slides away; fingerprint success morphs to a check; medium haptic |
| Email triage | Items sort into their groups with a stagger |
| Assistant | Thinking shimmer; answer lines fade in; suggestion cards rise |
| Capture | Field expands from the pill; captured item flies into the list. Capture sheet (share/widget/tile): springs up from the bottom, a check pops on Add and the sheet drops away; light haptic |
| Repeat and steps | Repeat picker unfolds from its row (Mac: menu); Skip/Tomorrow leave the list like a completion; a step tick pops with a spring and a light haptic |
| Lists | Tab pill springs across (Mac: segmented control); a row unfolds its actions in place; Got it / Do it now leave like a completion with a light haptic; due chases and reviews are lit in the accent colour |
| Loading | Skeleton shimmer, never a spinner on its own |

## Milestones

### M0 · Foundation — done
- [x] Shared core, offline-first sync with conflicts, live sync, AWS backend with CI/CD
- [x] Hardware-bound device keys, signed requests, reconnect after sign-out
- [x] Cable-free installs on the Fold

### M1 · Run my day
- [x] Google Calendar (read-only), FC Barcelona fixtures, day planner v1
- [x] Theme: Dark default, Light, Auto, animated blend (both apps). *Landed 2026-10-05; rule 8 keeps every later screen checked in both themes.*
- [x] Motion foundation: stagger, count-up, skeleton shimmer, pane spring, haptics helpers (`MekaMotionKit` on both apps, `choreography` tokens); applied to Today (intro stagger, up-next cross-slide, complete haptic), Plan (cascade, count-up, skeleton) and Calendars (skeleton, stagger). *Landed 2026-10-05. Shared-element transitions (Plan → Today, list → detail) moved to the App shell item, where navigation lands.*
- [x] App shell: Fold bottom bar + two-pane when unfolded; Mac sidebar (Today · Needs you · Lists · Goals · Review · Vault). Includes shared-element transitions (Plan Apply → Today, list → detail) with `SharedTransitionLayout` / `matchedGeometryEffect`
  - *Slice 1, 2026-10-05 (CI green 2026-10-05, run 36, all four jobs):* `ShellNav` rules (unit-tested on both apps, rule for rule); Fold: bottom bar with five destinations when closed, rail with all six when open (Today and Needs you keep their two panes), bar steps aside for the keyboard, each destination keeps its state; Mac: `NavigationSplitView` sidebar with ⌘1–⌘6 in a Go menu; real Needs you screen on both apps with a calm count badge; Lists, Goals, Review and Vault show what lands there. Motion: content slides the way you moved, lit pill springs, tick haptic; reduced motion cross-fades.
  - *Slice 2, 2026-10-06:* `SharedMotion` rules (unit-tested on both apps). Fold: Today and Needs you sit in a `SharedTransitionLayout`; on the closed Fold a task's title travels from its row into the detail pane and back; Plan Apply sends each planned block's title into its row in Today, which is then softly lit for 1.2 s; opening the Fold grows the detail pane out beside the list (and folding shrinks it away) while the list keeps its scroll position. Mac (simpler, as rule 7 allows; the plan is a sheet, so titles can't fly out of it): the selection highlight glides between rows (`matchedGeometryEffect`), the detail slides across to the new task, and planned tasks glide into place and are lit for 1.2 s. Reduced motion: no travel, cross-fades only.
  - *Check on the Fold:* the title flights and the unfold morph can only be judged on the device; adjust timings in `MekaShared.kt` if anything feels slow.
- [!] Work mode + "while you were at work" summary (approved 2026-10-06; what's left waits on Needs Meka #10 and #3): work hours from a schedule plus a manual Work switch; Android notification listener captures WhatsApp, SMS and missed calls during work mode (official notification access only, no WhatsApp libraries); after work, one screen grouped by person with urgent items first ("urgent"/"emergency" breaks through immediately). Summaries are non-AI (grouping, ordering, counts) until the AI layer lands, then one line per person. Never marks anything read, never replies. Family list and an "always ring/always notify" list chosen from contacts.
  - *Slice 1, 2026-10-06 (Mac checked nightly):* core `WorkMode` (schedule incl. night shifts, a Work switch that lasts until the schedule next changes, max 16 h, synced as the `context_mode/work` entity) and `AfterWorkSummaries` (grouped by person: urgent first, then family, then latest; whole-word "urgent"/"emergency"; re-posted notifications de-duplicated; phone-number formats matched), unit-tested; `MekaCore.workMode` + switch/schedule commands, re-evaluated every 30 s. Fold: `WorkCaptureService` (official notification access) reads WhatsApp, the SMS apps and missed calls only while at work and keeps them in a Keystore-sealed file on the phone (never synced, dropped after 7 days); urgent messages and the always-notify list post a high-importance "Urgent while at work" alert; Work screen (switch, hours, notification access, alerts, Family and Always-notify picked from contacts); Needs you shows "N held for later" during work and the "While you were at work" card after, opening one screen grouped by person with an expandable thread; Done clears MEKA's copy only. Mac: "At work / Off work" in Today opens the Work sheet (switch and hours). Default hours Mon–Fri 09:00–17:30 until changed.
  - *Slice 2, 2026-10-06 (Fold only; the summary itself is Fold-only until #10):* core `AfterWorkNudge` (nudge once on the change from at work to off work, only when something is held and the app isn't on screen; "Ada, Mum and 3 others · 1 urgent · 5 messages · 1 missed call"), unit-tested. Fold: `AfterWorkNudger` checks every work-mode change (clock tick, background sync — so a switch flipped on the Mac counts — the listener, process start) and records the last state, so it nudges once even if the phone was off when work ended; an inexact `setWindow` alarm (10-min window, no exact-alarm permission, ADR-007) wakes it at the end of work. One quiet "After-work summary" channel; names stay off the lock screen ("Your after-work summary is ready"). Tapping opens Needs you with the summary, which clears the nudge.
  - *Remaining (blocked):* the summary on the Mac (Needs Meka #10); one AI line per person when the AI layer lands (#3). Family/always-ring calls belong to the call assistant item.
  - *Check on the Fold:* grant notification access in the Work screen (Android may first need App info → ⋮ → Allow restricted settings, as the app is sideloaded), allow alerts and let the "Urgent while at work" channel through Do Not Disturb. Missed-call and SMS layouts vary by Samsung build: if a sender shows wrongly, the title/text rules are in `NotificationRules.kt`.
- [!] Call assistant for mobile calls at work (Needs Meka #9): during work mode, MEKA's call screening lets family, the always-ring list and repeat callers (2nd call within 3 min) ring; other calls are declined, and the carrier's "forward when busy" sends them to a cloud phone number where a voice assistant says it is Meka's assistant, that Meka is at work and will call back, takes a message, and asks if it's urgent. Messages land in the after-work summary; urgent ones alert Meka immediately. Fixed greeting written by Meka; the assistant always says it is an automated assistant; one switch turns it off.
- [x] Repeating tasks and routines (daily/weekly/monthly/yearly; "every 2nd Tuesday"; skip/snooze one occurrence)
  - *2026-10-06 (Mac checked nightly):* core `Recurrence` (RRULE subset: every N days, weekly on chosen days incl. every other week, monthly on a date — the 31st falls on a short month's last day — or on the 1st–4th/last weekday, yearly incl. 29 Feb), `CivilDate` and a platform `LocalCalendar` so times of day survive the clock change; unit-tested. A repeating task is a series of occurrences: Done or Skip creates the next one with an id made from the series and day, so two devices finishing the same one offline end up with one next occurrence; missed ones aren't back-filled (an open one from an earlier day stays, marked "since Mon 5 Oct"). "Tomorrow" snoozes just this occurrence (or any one-off task) out of Today and the planner until its day. Steps on a repeating task make it a routine: each new occurrence brings them back unticked. Fold: Repeat row in the task detail unfolds the picker (Every day · Every weekday · Every Tue · Every 2 weeks on Tue · Monthly on the 6th · Monthly on the 1st Tue · Every year on 6 Oct), steps with spring ticks, and Done · Skip · Tomorrow · Delete; rows show "↻ Every weekday". Mac: the same as a Repeat menu, steps list and buttons.
  - *Later, if wanted:* a custom rule editor (e.g. "every 3 days" can be stored and shown, but the picker offers presets only); "after completion" repeats (next one counted from the day you finish).
- [x] Capture from anywhere: Android share sheet, home-screen widget, quick-settings tile, voice (on-device speech); Mac menu bar (exists) + Services
  - *2026-10-06 (Mac checked nightly):* core `QuickCapture` (non-AI: first line the title, the rest kept in the notes; a bare link becomes "Open bbc.co.uk" with the link kept; a share's subject becomes the title; long titles cut at a word with the whole text in the notes), unit-tested; `MekaCore.capture(text, subject)` used by every entry point incl. the in-app capture fields. Shared text is untrusted: it only ever becomes a task's title and notes. Fold: one capture sheet (springs up over whatever is on screen, field focused, "Kept in notes" preview, check pops and the sheet drops away; reduced motion fades) opened by the share sheet, "Capture in MEKA" on selected text, a quick-settings tile and a home-screen widget (pill + mic, follows the phone's light/dark). Voice is the phone's recogniser asked to stay on the device (`EXTRA_PREFER_OFFLINE`, no microphone permission in MEKA). Nothing saves until Add. Mac: Services → "Add to MEKA" on selected text in any app (waits for the core if the request launched the app); the menu-bar and Today fields use the same rule.
  - *Check on the Fold:* add the tile (pull down twice → ✎ → drag "Capture") and the widget (long-press home → Widgets → Meka). If the mic says voice isn't available, install/enable Google's speech services and download the English offline pack (Settings → General management → Voice input). On the Mac, "Add to MEKA" may need ticking once in System Settings → Keyboard → Keyboard Shortcuts → Services → Text.
- [x] Lists: Waiting for (with chase dates), Someday (with kinds), Decisions (with review dates)
  - *2026-10-06 (Mac checked nightly):* core `Lists` (non-AI). Waiting for is a `commitment` owed to you: what, from whom, a chase date (presets Tomorrow · In 3 days · Next week · In 2 weeks · No date; default 3 days); "Chased" records it and sets the next chase 3 days on; "Got it" closes it (Done wins over a chase made at the same time on the other device). Someday is a task with lifecycle SOMEDAY grouped by kind (Ideas · To buy · Projects · Trips · To read · To look into · Applications · Home · Other), never in Today or the planner; "Do it now" puts it back in Today, and a one-off task's detail has "Someday" to send it there (repeating tasks can't). Decisions: what you decided, why, an optional review date (a month · 3 months · 6 months · a year); when due: "Still right" with the next review, Revisit, or Replace (the old one is kept as superseded, so it isn't re-made). Chase and review dates are days (stored as 09:00 local). Due chases and reviews show as one "From your lists" card in Needs you and count in its badge. Unit-tested incl. two-device sync. Fold: Lists tab with a springing tab pill, rows that unfold their actions, date chips and add fields. Mac: the same with a segmented control and menus.
  - *Later, if wanted:* pick "from whom" from contacts / People (V1); a received item asking to become a task; showing superseded decisions as history; "promises spotted in email → Waiting for" is V1.
- [ ] Goals and habits (progress, streaks, planner makes room for habits that are behind)
- [ ] Fasting tracker (timer, eating window, planner-aware, weekly history)
- [ ] Evening shutdown (tick, carry over, tomorrow preview)
- [ ] Renewals and bills radar, manual entry (Obligation entity: MOT, insurance, boiler, subscriptions, cancel-by dates)
- [ ] Notification governor v1: tiers, quiet hours, digest at 12:30 and 18:00 (local notifications)
- [!] Push notifications via Firebase (needs Firebase project — Needs Meka #2)
- [ ] Morning brief: day summary, waiting-on, news headlines from RSS topics (server-side fetch)
- [ ] Weekly review with north-star metrics (ADR-013)
- [ ] Search everything (tasks, events, lists, decisions; documents when the Vault lands)
- [ ] Self-updating phone app (checks the server for a newer signed APK; you tap Install once)
- [ ] Export and backup (one-tap export of all data as JSON + documents)
- [ ] Mac database encryption (spike S6); fix the Mac keychain prompts (no Apple-signed cert: use Secure Enclave-wrapped key files)
- [!] Outlook calendar (code done; needs Microsoft registration — Needs Meka #1)
- [ ] Clean-ups: orphan KMS key, restrict enrolment token after enrolment, remove OIDC diagnostic step, ADR-004/005 updates

### V1 · The assistant layer
- [ ] "What MEKA did and why" activity log with undo (lands before any automatic action)
- [ ] Policy engine UI: "What MEKA may do" per area, kill switch, locked rows
- [ ] Approvals inbox ("Needs you") with biometric confirmation for financial/trading items
- [!] AI layer (extraction, drafting, Ask MEKA) — needs Anthropic API key and monthly spend cap (Needs Meka #3)
- [!] Email triage for Gmail and Outlook — needs mail read permission (Needs Meka #4)
- [ ] Promises spotted in email → Waiting for; receipts → Vault; renewals radar fills itself
- [ ] Messaging drafts via Android notification listener (Level 3 max, never automatic)
- [ ] People: birthdays, last contact, promises both ways, gift ideas
- [ ] Travel mode: bookings → trip timeline; documents offline
- [ ] Photo of a letter → task/obligation (on-device text recognition)
- [ ] Calendar write, opt-in and off by default: put planned blocks in your real calendar (new permission when you turn it on)
- [ ] Health Connect, opt-in and off by default: sleep/steps/workouts for energy-aware planning and auto-ticking habits
- [!] Trading approvals from Kestrel (event-based; needs a small change in the Kestrel repo — Needs Meka #5)
- [ ] Recovery key
- [!] Notarised Mac release (Apple Developer Program — Needs Meka #6)

### V2 · Home and family
- [ ] Vault (encrypted documents, expiry dates onto the plan)
- [ ] Household: second person, private by default, shared lists, busy-only family calendar
- [ ] Full news hub (moved from V1 on 2026-10-05)
- [ ] Balance view: time across work, family, health and self against your targets
- [ ] Galaxy Watch: approve message drafts from the wrist (trades still need the phone)

## Needs Meka

Things only the owner can do. Build runs skip these and carry on with the rest.

1. **Microsoft registration** for Outlook (10 min, steps already given).
2. **Firebase project** (free) for push notifications: create it with your Google account, download `google-services.json`.
3. **AI layer:** an Anthropic API key and a monthly spend cap you're comfortable with.
4. **Mail permission:** reconnect Google and Outlook with mail read access when email triage lands.
5. **Kestrel change:** OK to add an "approval request" event to Kestrel and let MEKA send back approve/decline.
6. **Apple Developer Program** (paid yearly) for a notarised Mac app. Optional: dev builds keep working without it.
7. **Install updates** on the Fold until the self-updating app lands (double-click Install on Fold).
8. **Actions allowance** — resolved 2026-10-06: Meka added a $10/month Actions budget (stops at the limit). Same day he raised the cap to 8 code pushes a day with the macOS check batched nightly (rule 10).
9. **Call assistant:** OK to use a paid cloud phone service (a phone number plus per-minute charges and voice AI, likely a few pounds a month at light use) and to switch on your network's "forward when busy" to that number. Also the greeting wording.
10. **After-work summary on the Mac:** OK to sync who messaged you at work and what they said to your Mac through your own server (stored with the rest of your data)? Until you say yes it stays on the Fold only. Also: are Mon–Fri 09:00–17:30 your work hours? (You can change them in Work mode on either device.)
