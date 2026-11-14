# Weekly walk-through, 2026-11-08 (quality gate (5), week 3; started early Sunday as nothing else was buildable)

**How:** the 293 screenshots that changed since last week's set (`screens` branch, 93fc4b9 → 96ad204d, CI run 1523 →
1613), split in four groups (Money/calls/AI/mail · Today/Ask/helpers · Board/Calendar/planning · Life screens) and read by
four fresh reviewers who did not build the screens, folded and open, dark and light, against `docs/design/` and the
layout rules, each re-checking last week's items in its area.

**Not covered yet (read 2026-11-14, see section A2 below; the Mac still isn't covered):** the image viewer's request limit stopped two reviewers part way:
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
7. **(Fixed 2026-11-08, slice 5: the shots' fixture.)** **Tomorrow's banner keeps "2 tasks"** after one is moved; "Gym before work" opens a sheet titled "Gym, legs day"
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

## A2 · The shots not covered on 8 Nov (read 2026-11-14 on `screens` at ci run 1882, 63c0b72), ranked

65 shots (30 Money/AI, 35 helpers and settings, cover and inner, dark and light) read by four fresh reviewers. Each item
is a reviewer's reading: the fixing run confirms it in the code first (a real sum to fix, or a shot's fixture to make
agree) before changing anything.

21. **Obi's "spare" sum disagrees with the card above it** (high, real if confirmed): What if I save… says "about
    £1,105" left each month (£2,230 − £1,125) while Obi's line under it says "£1,125 a month still leaves about £1,110
    spare"; Ask Obi's answer says "£1,100 … about £1,120 spare" where £2,230 − £1,100 = £1,130
    (`money/plan-forecast-tiles-unfolded.png`, `plan-challenges-tiles-unfolded.png`, `plan-abroad-tiles-unfolded.png`,
    `cover-dark-ask-obi-answer.png`).
22. **Headers that say nothing over a card** (high): Needs you's open Fold mail state says "Nothing needs you" beside
    Mail to answer 2 and Renewals 2, and its "2 mail" leaves out the renewals (`needs-you/inner-dark-mail.png`); Obi is
    working on's not-booked shots say "Nothing to keep an eye on yet" over a call card
    (`working-on/*-not-booked.png`); Team says "5 helpers on" with Money Not set up (`team/cover-dark-quiet.png`,
    `cover-light-busy.png`).
23. **Shots that don't show their state** (high, tests): setup empty and work empty are fully set up
    (`setup/cover-dark-empty.png`, `work/cover-dark-empty.png`); notifications one and many show "Next digest 18:00 ·
    nothing yet" (`notifications/cover-dark-one.png`, `cover-dark-many.png`); voice one shows Obi remembers nothing
    (`voice/inner-dark-one.png`); trading layout on the open Fold is only the loading line
    (`money/inner-dark-trading-layout.png`); bills tidy is "£0 · no bills found yet" (`money/cover-dark-bills-tidy.png`);
    plan-abroad unfolded shows the abroad tiles only at the bottom edge.
24. **The answer doesn't reach the draft** (high, real if confirmed): an answered errand's YOU SAID "Tuesday morning"
    while the draft still says "next week, ideally a morning" (`working-on/cover-dark-answered.png`).
25. **(Fixed 2026-11-14.)** **Money's cover tab row hides the open tab** (med): on Accounts the selected tab is cut to "Ac" at the right edge;
    the row doesn't scroll to it (`money/cover-dark-empty-accounts.png`, `cover-dark-accounts-data-sheet.png`,
    `cover-dark-own-category.png`; B lists the overflow, this is the selected tab hidden).
26. **Sheets out of order or out of line** (med): Edit category's Cancel / Save sit above Hide from the ring and the
    cap field, which is cut at the bottom, colour circles ~28 dp (`money/cover-dark-own-category.png`); the Idea sheet's
    rows and Not for me start ~36 px in from its heading, amounts end short of the figure above, "12× in 30 days" lists
    6 with no "6 more" (`money/cover-dark-plan-idea.png`); Add a £ goal has two identical gold buttons, Add goal and Done
    (`money/plan-add-goal-cover.png`); the money data sheet sits ~40 px high against the top edge and its Delete all
    money data reads as plain text (`money/cover-dark-accounts-data-sheet.png`).
27. **Left edges that drift** (med): Needs you's mail rows start ~28 px left of the group cards' text, Close all sits
    mid-screen on the open Fold, the Mail chip is cut in the gutter on the cover (`needs-you/inner-dark-mail.png`,
    `cover-light-mail.png`); Ask's search results step right row by row (`ask/cover-dark-field-by-hand.png`); Voice
    notes' Keep / Delete start ~18 px right of the note text (`voice-notes/*`); the Plan coffee card's Not for me
    (`money/cover-dark-plan-layout.png`); −/+ steppers end ~40 px short of the On pills' right edge
    (`notifications/*-many.png`, `work/cover-dark-one.png`).
28. **Lines ending on "·" or a lone word** (med, A14's rule on screens it missed): Needs you's hint "for the next ·" and
    "£10.99 a month ·"; Health "can't see notifications ·"; Obi is working on "Obi found … · on" and the chaser card
    wrapping ~140 px early; Trading's alerts "5× ·"; the card sheet's lone "due", "one)", "on Plan)", "0% / ends";
    Challenges' "Cap a category for the / month" sitting on its pill's edge and "fun / money"; Activity "1 /
    reminder", "stopped.", lone "log."; Plan's "24 months" chip alone and "a year."; Ask Obi's "6 / months?", "Not /
    financial advice.".
29. **Said twice** (med): "Home insurance renews Thu 5 Nov" under both WHAT I DID and WHAT I NEED FROM YOU
    (`working-on/inner-dark-many.png`); "Obi has your answer" twice and "never sends, books or buys" twice
    (`working-on/cover-dark-answered.png`); NOT BOOKED / "Not booked ·" and the restaurant's name twice on the call card;
    "Nothing imported yet" twice (`money/cover-dark-empty-accounts.png`); Money empty's "your month shows here" twice;
    Navilife's number "takes messages for you" repeating its label (`setup/inner-*.png`); Routines' Morning brief time on
    the tile and the line; Notifications' "On · 21:30–06:45" beside an On pill.
30. **The call card's tiles** (med): WINDOW's figure smaller than WHEN's and PEOPLE's, PEOPLE taller, Party size repeating
    PEOPLE (`working-on/cover-dark-found.png`; the Party size placement is also in B).
31. **Light contrast** (low): Money's unselected tabs, grey percentages and gold "over usual" (`money/inner-light-many.png`);
    Work mode's off day circles and commute chips (`work/cover-light-many.png`); Routines' time tiles and paused tile
    (`routines/cover-light-many.png`); Voice notes' cards and Keep / Delete (`voice-notes/cover-light-many.png`); Ask's
    tiles (`ask/cover-light-many.png`).
32. **Smaller ones** (low): Spending's Left £1,942 = in − spent with Bills to come £142 beside it (say what it counts or
    take bills off; `money/inner-light-many.png`); Kestrel's "About £2,140" for $2,736 at the equity card's rate (~£2,120);
    Work's commute "About 1 h 20 each way" with no chip lit; bare −/+ and "›" glyphs as ~24–30 dp targets (Work,
    Notifications, Health); Health "Checking now" beside "5 things need a look"; Setup "All set for now" with "1
    couldn't be checked"; the card sheet's Charge card / Not a card with no pill; "Save £ a month" reading as a missing
    figure; the What if slider short of the edge; one date header with a day total and the next without; the Apr bar's
    label touching May's; No-spend circles ~17 dp; Trading's 1D/1W/1M chips with no line now on the open Fold too
    (A11 said "The line draws once…", the chips could hide until then); shots' fixtures disagreeing (forecast OUT £270 vs
    £280 under Bills' £295, Coffee missing from Spending beside Ask Obi's £57.60, statements to 13 vs 15 Oct, "1 payment
    without a category" vs the sorter's 2 left, Face On on Voice vs off on Health, Trips and Countdowns both "Seville in
    7 days").

Still showing from A (not counted again): Ask's People tile "waiting for / 1 · +1", setup counts, habits 0 of 1 vs 1 of
2 on Ask's cover, Health's Call assistant Off.

## B · For the whole-app layout review (waits on the Navilife look, Needs Meka #51)

Half-empty open-Fold pages (Today, Brief, Week ahead, Calendar, Bills, Money empty, Trips, People, Bins, School,
Emergency, Kids, Bedside news); lopsided Plan and Clear my cards columns; Board's Sat/Sun on a second row against the
mockup's one row; open inputs and chip rows on most Life pages, Week ahead, Study, Brief's Topics running off the edge,
Work mode's commute chips, Calendars' Weather; explanations shown open rather than behind ⓘ (Obi is working on, About
you, Routines); Trip lists' open Who's going editor and missing ⋯; the call card's Party size under a PEOPLE tile;
Money's cover tabs running off the edge (still); Mail's button band; AI's long list against "5 rows + See all".
