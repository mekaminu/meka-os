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
| Capture | Field expands from the pill; captured item flies into the list |
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
- [ ] Work mode + "while you were at work" summary (approved 2026-10-06): work hours from a schedule plus a manual Work switch; Android notification listener captures WhatsApp, SMS and missed calls during work mode (official notification access only, no WhatsApp libraries); after work, one screen grouped by person with urgent items first ("urgent"/"emergency" breaks through immediately). Summaries are non-AI (grouping, ordering, counts) until the AI layer lands, then one line per person. Never marks anything read, never replies. Family list and an "always ring/always notify" list chosen from contacts.
- [!] Call assistant for mobile calls at work (Needs Meka #9): during work mode, MEKA's call screening lets family, the always-ring list and repeat callers (2nd call within 3 min) ring; other calls are declined, and the carrier's "forward when busy" sends them to a cloud phone number where a voice assistant says it is Meka's assistant, that Meka is at work and will call back, takes a message, and asks if it's urgent. Messages land in the after-work summary; urgent ones alert Meka immediately. Fixed greeting written by Meka; the assistant always says it is an automated assistant; one switch turns it off.
- [ ] Repeating tasks and routines (daily/weekly/monthly/yearly; "every 2nd Tuesday"; skip/snooze one occurrence)
- [ ] Capture from anywhere: Android share sheet, home-screen widget, quick-settings tile, voice (on-device speech); Mac menu bar (exists) + Services
- [ ] Lists: Waiting for (with chase dates), Someday (with kinds), Decisions (with review dates)
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
