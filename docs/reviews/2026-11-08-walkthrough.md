# Weekly walk-through, 2026-11-08 (quality gate (5), week 3; started early Sunday as nothing else was buildable)

**How:** the 293 screenshots that changed since last week's set (`screens` branch, 93fc4b9 → 96ad204d, CI run 1523 →
1613), split in four groups (Money/calls/AI/mail · Today/Ask/helpers · Board/Calendar/planning · Life screens) and read by
four fresh reviewers who did not build the screens, folded and open, dark and light, against `docs/design/` and the
layout rules, each re-checking last week's items in its area.

**Not covered yet (finish in tonight's Sunday run):** the image viewer's request limit stopped two reviewers part way:
**31 Money/AI shots** (account and card sheets, accounts-data-*, ask-obi-*, bills-layout/tidy, empty*, own-category,
plan-idea/layout, sorter, `inner-dark-one`, `inner-dark-plan-layout`, `inner-dark-trading-*`, `inner-light-many`,
plan-abroad-*, plan-add-goal, plan-challenges-*, plan-forecast-six, plan-forecast-tiles-unfolded) and **~40 shots in
group 2** (routines light/inner; team quiet/sheet/light; working-on answered/one/found/not-booked/light/inner;
needs-you mail light/inner; work empty/one/light; ask field/tiles-by-hand/light/inner; voice inner; voice-notes
light/inner; activity light/inner; health not-connected; notifications except cover-light-many; setup empty/inner).
The Mac isn't covered (same reason as before).

**From 2 Nov, now fixed:** Trip lists' clipped "2"; What-if's "left each month" and the forecast's totals; Study's bare
"2"; Tomorrow's edit time and light Repeat row; an email as a calendar's name; the Obi sheet's mic; AI's total; Board's
Obi pill destination, lone Not now and cut "Send the quote"; Study on the Board; Fasting's "6½h short".

## A · Found and fixing (no mockup needed), ranked

1. **Today under the capture bar** (high, third week): the last Anytime row on the cover, the TODAY · AT WORK timeline
   on the open Fold and, while fasting, Up next's buttons (`today/cover-*-many.png`, `today/inner-*-many.png`,
   `inner-dark-one.png`, `inner-dark-fasting.png`).
2. **Open position P&L impossible** (high): entry $60,000, now $60,000, "−$10.00 not yet taken"
   (`money/cover-dark-trading-kestrel.png`, `cover-dark-trading-risk.png`).
3. **September review clips "£2,125.91" to "£2,125."** on the cover (`money-layout-review-cover.png`).
4. **Your team's tiles vs its cards** (high): WORKING 4 while every card says Ready or Not set up; Money "Not set up"
   with "TODAY 1 heads-up" (`team/cover-dark-busy.png`, `inner-dark-busy.png`).
5. **Obi is working on vs its card** (high): "nothing needs you" over "CALL CARD · NEEDS YOUR TAP"
   (`working-on/cover-light-found.png`).
6. **Board on Saturday** (high, still): underline on Sat 24 while the Today pane says Wed 21; "Today is 4h over · move
   'Batch cook…'" with no such card and Saturday at 1h / 12h; the nudge's Move to names no day, "over" alone on a line;
   Boiler service's chips cut to "Ot…", "H…" (`board/inner-dark-unfolded-on-saturday.png`, `inner-dark-folded-again.png`,
   `inner-dark-scrolled.png`, `inner-dark-areas.png`).
7. **Tomorrow's banner keeps "2 tasks"** after one is moved; "Gym before work" opens a sheet titled "Gym, legs day"
   (`tomorrow/fold-*-moved.png`, `fold-*-edit.png`).
8. **Counts that differ between screens** (high, mostly the shots' fixtures, some real): habits "1 of 2" on Today vs
   "0 of 1" on Ask; setup "12 steps left" vs "TO SET UP · 10"; the brief's "4 tasks" vs "1 to do today" and the Focus
   task repeated; Spending's "Bills to come £0" vs Bills' £142 before payday; Health's Call assistant Off vs Setup's On;
   People's "1 to chase" vs two "Waiting for 1"; Appointments "all in hand" over a card asking when he last went. Find
   which are real (the screen's own sums) and fix those; make the rest's fixtures agree (dates of the same trip,
   birthday, GP, Wed/Thu events and the fast across Trips/Trip lists, People/Gifts, Emergency/Facts, Brief/Calendar/
   Board/Bedside/Tomorrow/Fasting).
9. **Study plan's figures** (med, still): "2 sessions a week" at 45 min beside "about 1 h a week"; "Fits: you need 2 of
   the 4 sessions a week the 2 times you picked hold." garbled; THIS WEEK titles cut ("Welcome and se…"), Today 20:30
   then a Wed row when today is Wed, suggestions "Mon, Wed, Mon, …" (`study/cover-*-plan.png`, `cover-dark-this-week.png`).
10. **Accounts' headline** (med, still): £42,000 big, labelled Outside these accounts, then "Not counted yet", label
    repeated (`money/cover-dark-accounts.png` and the worth/columns/light shots).
11. **Cut and repeated labels** (med): Forecast "OUT /" losing MONTH (`money/plan-forecast-tiles-cover.png`); Mail's
    Autopilot line twice (`mail/cover-dark-by-hand.png`); Needs you's "Draft replies to 2" over "nothing is drafted"
    (`needs-you/cover-dark-mail.png`); Trading's 1D/1W/1M chips with no chart (`money/cover-dark-trading-risk.png`).
12. **Routines' rows** (med): the MON–FRI tile wider so Morning brief's title starts ~10 dp right; subtitles cut
    ("What came i…"); Running at Step 1 of 4 with an empty bar and LAST RUN "Not yet" (`routines/cover-dark-*.png`).
13. **Small targets** (med): No-spend circles ~24 dp (`money-plan-goals-cover.png`), Kids' chore circles ~31 dp
    (`kids/cover-dark-kids-by-hand.png`), On pills and Week ahead chips ~31 dp, About you's Watch for requests ~32 dp;
    bare "^"/"⋯"/"˅" glyphs on Trip lists and Study (`trip-lists/cover-*-pack.png`, `study/cover-dark-course.png`).
14. **Lines ending on "·" or a lone word** (med): Trips "4 days ·", Leave, Reading; School "Oct", Kids "months"/"next",
    Emergency "key"/"house", Car "sent", Facts "from AI"; Ask's People tile "waiting for / 1 · +1"; Setup "· 1 /";
    Voice "left / off · 3 notes"; Week ahead "leads", "Fri · 5 events", "1 h 45", "0 h of / 8 h"; Money's "21st",
    "Dec / 2027", "minute or / two", "for the / month"; Calendars' "→", "work's"; Study's lone "1".
15. **Kids' size fields** (med): a filled field shrinks for Clear, ~55 dp left of the empty ones; "All still fit" with
    2 of 6 kept (`kids/cover-*-many.png`).
16. **Lists' tab row on the cover** (med): "Decisions" in a smaller font, Shopping against the pill's edge, counts
    dropped; rows inset ~8 dp from headings (`lists/cover-*-many.png`).
17. **Edit category sheet** (med): a sliver of a row above the title, the icon grid off the left edge
    (`money/cover-dark-own-category-cap.png`).
18. **Light contrast** (low, still): Fasting's future rings/knob/stage dots; the bedside's BBC tiles and meta.
19. **Smaller ones** (low): Car "Walk there in Maps" vs Find my car; Leave's − buttons not aligned and 21 Dec–1 Jan
    counted as 10 work days over the bank holidays; Kids' half term as two days; Trip cards' bottom padding; bedside "No
    alarm set" at 07:40 vs 06:30 in the evening shots; Tomorrow's banner against the top edge; Work mode's "Each day" in
    sentence case; errand buttons right-aligned; habit line's em dash; AI card chevron pointing down while open; the
    Holiday goal sheet saying "£800 of £3,000" three times; Ask Obi re-offering the question just asked; "no ideas just
    now" then "Nothing to look at just now"; empty progress tracks with nothing set; "All 10 ›" off the chips' baseline;
    Bills to come's label smaller than its neighbours; Trading equity's figure repeated.
20. **Shots that don't show their state** (med, tests): byte-identical pairs, so one state each went unread:
    routines new-list = empty = by-hand; notifications by-hand = empty; working-on found = use-found, not-booked =
    try-again; by-hand shots of about-you, activity, needs-you, team, voice (×2), voice-notes, working-on (×2), people;
    money many = one-column, inner many = columns-spending, trading equity = tiles (cover and inner),
    plan-forecast-six = plan-goals-empty; mail one = many (still); board timeline = timeline-aligned; fasting one = many
    above the fold; board folded-again draws the cover layout in an inner frame.

## B · For the whole-app layout review (waits on the Navilife look, Needs Meka #51)

Half-empty open-Fold pages (Today, Brief, Week ahead, Calendar, Bills, Money empty, Trips, People, Bins, School,
Emergency, Kids, Bedside news); lopsided Plan and Clear my cards columns; Board's Sat/Sun on a second row against the
mockup's one row; open inputs and chip rows on most Life pages, Week ahead, Study, Brief's Topics running off the edge,
Work mode's commute chips, Calendars' Weather; explanations shown open rather than behind ⓘ (Obi is working on, About
you, Routines); Trip lists' open Who's going editor and missing ⋯; the call card's Party size under a PEOPLE tile;
Money's cover tabs running off the edge (still); Mail's button band; AI's long list against "5 rows + See all".
