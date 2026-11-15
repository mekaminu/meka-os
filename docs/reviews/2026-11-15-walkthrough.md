# Weekly walk-through, 2026-11-15 (quality gate (5), week 4)

**How:** the 494 screenshots that changed since last week's set (`screens` branch, 96ad204d → 8bfad1f3, CI run 1613 →
1945), split in thirteen groups of 27–49 (so no reviewer hit the image viewer's limit: **every shot was read**) and read
by thirteen fresh reviewers who did not build the screens, folded and open, dark and light, against `docs/design/` and
the layout rules, each re-checking last week's items (A and A2 of `2026-11-08-walkthrough.md`) in its area. The Mac
still isn't covered (no Mac shots on the branch). Each reviewer's full report is in the appendix (C); section A is the
ranked list to fix. Each item is a reviewer's reading: the fixing run confirms it in the code first (a real sum or
rule to fix, or a shot's fixture to make agree) before changing anything.

**From 8 and 14 Nov, now fixed (reviewers' re-check):** the open position's P&L, the September review's clipped
figure, Accounts' headline, the forecast's "OUT /", Obi's "spare" sum, the cover tab row hiding the open tab, the
Idea sheet, the money data sheet, Add a £ goal's two buttons, the Mail Autopilot line twice, Kestrel's pounds, the
date-header totals, the slider edges, Trading's "5× ·", most of Money's lone words, the AI card's chevron.
**Still there:** No-spend circles (~23 dp), Edit category's cap field under its bar, Trading's 1D/1W/1M chips beside
"The line draws once…", the answered errand's draft (A24, now with a line saying Obi is putting the answer in),
Tomorrow's "Gym before work" opening "Gym, legs day" (A7), empty progress tracks with nothing set, fixture dates that
disagree across screens (A8), several byte-identical state pairs (A20).

## A · Found and fixing (no mockup needed), ranked

1. **Spending takes the bills off twice** (high, real if confirmed): "Bills 24% £142" is inside "Spent so far £583"
   and also the "Bills to come £142" tile, and "After bills £1,775" = 2,500 − 583 − 142; Bills says the £142 council
   tax is still to go out on 1 Nov, while Spending's latest payments list it on Thu 1 Oct with no word that it's last
   month's (`money/inner-dark-columns-spending.png`, `inner-light-many.png`, `cover-light-many.png`,
   `money-layout-review-cover.png`; g1b 3, g1a 16).
2. **Trading's month can't come from its trades** (high, real-looking): "+$470.00 (7.8%) over the month" beside
   Closed profit +$42.00, Funding −$38.20, Fees −$3.00 and −$10.00 not yet taken (deposits counted as gain?); the 1M
   line starts Wed 7 Oct with today 21 Oct; Obi's "Trading is 100% of everything you hold" ignores Cash and the
   pension (`money/cover-dark-trading-equity.png`, `inner-dark-trading-equity.png`, `cover-dark-trading-kestrel.png`;
   g1a 1, 7, g1b 8).
3. **Obi's money check with no figure** (high, real-looking): "OBI'S MONEY CHECK / from 2 ideas" with nothing before
   it (1 idea reads "£350 a year from 1 idea"); siblings show "Nothing to look at just now" against the same
   overspends (`money/cover-dark-plan.png`, `inner-dark-columns-plan.png`, `plan-forecast-tiles-unfolded.png`; g1a 2,
   g1b 7).
4. **Groceries' "usual" has three values** (high): Spending "£191.75 · £151 over usual", Plan "£131.50 over your
   2-month average. £191.75 so far", What if "Halve Groceries +£60 a month"; and Plan's Groceries card cuts "£131.50
   over" at the right, the "u" of its second line at the left, orphans "month", and cuts the Fuel row
   (`money/cover-dark-plan.png`, `inner-dark-columns-plan.png`, `cover-dark-many.png`, `cover-dark-plan-whatif.png`;
   g1a 3, 4, g1b 1, 2).
5. **Today's counts disagree with its own rows** (high, real-looking): one task: TASKS "0 · nothing planned" over Up
   next "Book the MOT" and "1 left"; many: "1 of 5 done today", "TODAY · 4 LEFT", Shut down "5 left", Up next "Fix the
   gate latch" not among the rows, "4 LEFT · 3H 15M" over 2 h 45 of minutes; NEXT "Free · nothing else today" over the
   17:30 commute; "1 left · move or drop them" (`today/cover-dark-one.png`, `inner-dark-one.png`, `*-many*.png`;
   g3a 2–5, 13).
6. **Today on the open Fold** (high): the fasting card crushed to a ~60 px strip under Up next (no label, time or
   End); the task list scrolls under the pinned habit chips with no fade, slicing "Pay the window cleaner"; the
   schedule still under the capture bar (A1) (`today/inner-dark-fasting*.png`, `inner-dark-many-end.png`; g3a 1, 6, 10).
7. **Needs you's header and groups** (high): "2 to decide · 3 to reply · 2 FYI" with no decide item in All and reply
   badges adding to another number; Messages 3 shown open with no rows under it; "in 7 days" on both Mon 21 Dec and
   Fri 18 Dec; the mail tiles "138 tidied" over 33 + 71 + 33 = 137 (a sibling says 41 tidied for the same day) and
   "newslett / ers" broken mid-word; a Game testing preview repeated word for word under itself; Sam's preview twice
   (`needs-you/cover-*.png`, `groups-*.png`, `decide-others-*.png`, `mail-*.png`, `open-*.png`; g2a 1–5, 7, g2b 2, 8, 10).
8. **Helpers that contradict themselves** (high, real-looking): a routine "RUNNING NOW · Step 1 of 4" with LAST RUN
   Now and LAST TIME "It hasn't run yet"; a routine added by hand starts paused ("All paused"); START WITH ONE offers
   the routine just saved; Obi is working on's empty header "Nothing to keep an eye on yet" over three standing errands
   that are on; Try again re-books the full 19:00 slot instead of the 21:00 offered; the answered errand's draft (A24)
   (`routines/cover-dark-running.png`, `-by-hand.png`, `-new-list.png`, `working-on/*`; g2b 1, 3–7).
9. **Headers that say the opposite of the page** (high): Review "2 wins" over WINS "Nothing yet"; Ask's "Nothing set
   up yet" over Lists "1 to buy" and a Christmas countdown; What Navilife may do "asks before everything" over two lit
   rows that act at once; Reading goal "1" over "No goal for the year"; Appointments "None due to book" over To book 1
   Dentist; Goals "1 habit to do today" over two open habits; Gifts "2 gift ideas noted" with no idea shown
   (`review/*`, `ask/*-one.png`, `autonomy/*-empty.png`, `reading/*`, `appointments/*-by-hand.png`, `goals/*-many.png`,
   `gifts/*-many.png`; g4b 1, 4, g5a 2, g5b 1, g6a 1, g7 2, g6b 10).
10. **Sums on Life pages** (high/med, real-looking): Leave's booked rows add to 17 days under "13 of 25 days booked",
    next year's leave in this year's list, the Christmas row cut by "Part booked"; Kids "£6 a week" beside "£7 to pay";
    School "1 day off to cover" for a 5-day half term; the Board's week "2h" folded vs "2h 30m planned" open; Calendar's
    "Booked this week 5 h" over 4 h of events (`leave/*`, `kids/cover-dark-kids-by-hand.png`, `school/*`,
    `day-list/board-*`, `calendar/week-*`; g6b 2, 3, 6, g6a 5, g4a 1, 12).
11. **Buttons cut or missing on the cover** (high/med): Emergency's + Add cut to "+ A" over its ⓘ on every cover shot;
    Facts' cover has no + Add at all; Needs you's Task chip, the Mail chip ("M"), Habits' 5th filter chip, the Day
    list's third chip ("F") and News' "Football E" cut at edges; the undo snackbar covering the New task field
    (`emergency/cover-*.png`, `facts/cover-*.png`, `needs-you/open-cover-*.png`, `habits/cover-*.png`,
    `day-list/board-open-*.png`, `day-list/fold-dark-moved.png`; g7 1, g6b 1, g2a 11, g2b 9, g4b 7, g4a 5, 6, g3b 7).
12. **Shots that don't show their state** (high, tests; byte-identical by md5 unless noted): speak-up empty = one =
    many = by-hand; talk by-hand = default; calendar week-by-hand = week-inner-dark-many; needs-you mail-by-hand = mail,
    inner many = two-columns = two-columns-boxed; lists one = promises-by-hand, shopping = shopping-by-hand; settings
    appearance = connected = by-hand; setup one = by-hand; autonomy by-hand = one; work inner by-hand = inner empty;
    errands by-hand = many; dinners by-hand = many; date-night by-hand = empty; Ask's Daily and Settings by-hand taken
    mid-crossfade; work empty fully set up; recovery many = one in kind; week-ahead empty not empty; review reflect =
    reflect-form; team "unfolded" is the cover (g3b 4, 5, g4a 7, g2a 6, g5a 1, 3, g5b 2–4, g7 3, g3b 15, g4b 5, g2b 22).
13. **Fixture facts that disagree across screens** (med, A8 still): today is Wed 21 Oct almost everywhere but Mon 26
    Oct on Study and mid-November on Car; Trips vs Trip lists' dates for the same trips; People vs Gifts birthdays;
    Goals' habits vs the Habits screen's; the course import's 2 modules · 5 lessons vs the course's 3 and 8;
    Parents' evening at 09:30; Bedside's "first thing" and morning brief vs Tomorrow, Today and Week ahead; Setup 12 vs
    11 steps; Calendars without the Kids calendar Calendar shows; the sorter's "2 LEFT" beside "3 payments without a
    category" and uncategorised money filed as a Need; 2026 SO FAR in three versions; the Rewards card only Plan knows;
    OUT / MONTH £230 / £270 / £280; statements to 15 Oct vs imported 21 Oct; a new category holding the wrong payment
    (g4a 8–10, g6a 2, g6b 5, g4b 2, 3, 14, g3a 7–9, g3b 1–3, g7 10, g1a 5, 6, 9, 10, g1b 4–6).
14. **Cut with room to spare** (med): the cover's decide cards ("expires i…", "Dentist · … · Las…", "Put back fro…"),
    Facts' boiler and Emergency's line cut even on the open Fold (a character cap?), Car's parked rows "±…" and MOT,
    Reading's "about 4 a m…", the Ask Obi hint "“What" on every cover Money shot, About you by-hand starting mid-scroll
    (g2a 8, g2b 12, g6b 4, g7 8, g6a 3, g1a 21, g5b 10).
15. **Lines ending on "·" or a lone word** (med, A14's rule on screens it missed): Money ("14 / mo", "Current /
    account", "without a / category", "minute / or two", "updated / just now", "(for Clear my cards / on Plan)"),
    Today's schedule header "· / 2 EVENTS", Tomorrow "1 / bill due", Week ahead "0 h / of 8 h", Talk "6 / seconds",
    Study "· 5 / lessons", Activity "1 / reminder", "Undo lasts 30" with no unit, Reading "· / up next", Leave
    "“Annual leave · / no work”", Kids "you first", Needs you "£10.99 a / month", "· / Today"; and the rest in C
    (g1a 14, g1b 15, g3a 14, 15, g3b 11, g4a 14, g4b 9, g5a 9, g5b 6, 8, g6a 7, g6b 9, g7 7, g2a 12, g2b 11).
16. **Said twice or garbled** (med): Accounts' "Add each account's balance…" over "Partial · 2 accounts have no
    balance yet"; "£2,400 by Oct 2027 / You put in £2,400"; "not financial advice" twice; AI "$2.05" twice; the brief's
    AI note ("works once this device is connected · read from your day as it is…"); Tomorrow's header said three
    times; Reading's "1 on the go" / "1 still going"; Kids empty's instruction twice; Dinners empty's three "no
    favourites"; the "None · + Add → …" and "None / leave to come" tile patterns; "In your data" under every About you
    field; Talk's nested quotes (g1a 11, 15, g1b 12, g3a 11, 12, g3b 8, 12, g6a 6, 14, g6b 8, 11, g7 5, 6, g5b 11).
17. **Open Fold layouts** (med, partly B): Ask Obi's sheet runs off the bottom; trading-layout's empty left half;
    Plan's empty left column; Needs you's decide cards and Goals stretched to ~1,920 px with content in the left 40 %;
    Agenda the cover layout stretched; Work's centred column (g1b 10, 11, g1a 23, g2a 15, g4b 13, g4a 18, g5b 19).
18. **Day list and Calendar rules** (med, real-looking): "Fit times from 19:30" when the list already starts 19:10; a
    12:00 task in Thursday's all-day row; an empty day saying nothing three times plus "0 of 0 done" and a drag hint
    with nothing to drag; hints naming a ≡ the row doesn't have; clash notes on one event but not on two others inside
    work hours; the now line through an event's title (g4a 2–4, 11, 13, 15).
19. **Habits and Study details** (med): streak dots that don't count today ("7 day streak" with 6 dots); the habit
    sheet's chip rows with no labels; the lesson plan sheet covering the list; Study's sums (4½ h left vs five 45 min
    sessions); the Journal's answer without its question (g4b 6, 8, 10–12).
20. **Small targets** (med/low): No-spend circles ~23 dp (A13 still), Trading's chips ~24 dp, the card sheet's pills
    ~23 dp, Obi on calls' cap pills and many On/Off, Pause, Edit, Unlink and day pills ~31–33 dp, pager circles ~32 dp,
    bare −/+, ›, ˅/^, ✓ glyphs and text-only links across Money, Needs you, Study, Notifications, Work, Car, Errands
    (g1a 13, g1b 17, g2a 16, g3b 14, g4a 20, g5b 15, g6a 9, g7 14).
21. **Light contrast** (low): white cards, chips and tiles on the off-white page with no edge (Accounts, AI, Obi on
    calls, Needs you, Today, Setup, Health, Lists' tab row, Trips, Activity, Notifications, Recovery, Bedside); faint
    Off pills in dark; Fasting's and Goals' empty rings; Car's Stop timer looking disabled (g1b 19, g2a 18, g3a 19,
    g3b 18, g4a 21, g4b 15, g5a 11, g5b 16, 17, g6a 16, g7 13).
22. **Smaller ones** (low): see each group's list in C (the watch's error in link gold, News "6 sources" naming ten,
    Gym at the same minute as leaving for work, Bins mixing days and dates, Car's "Fuel / prices below" with none, the
    ⓘ touching every title, count pills on one-item and empty sections, test strings in Facts by-hand, "Subject 0…4"
    placeholder mail, and more).

## B · For the whole-app layout review

Unchanged from 8 Nov (waits on the Navilife look, Needs Meka #51); this week's open-Fold width items (A17) and the
light-edge items (A21) add to it.

## C · Each reviewer's report

### g1-moneya
Read 41 of 41 shots. (All read; all are dark theme, so no light-contrast checks were possible in this group.)

#### Findings (ranked, most severe first)
1. **Trading's month gain can't come from its own trades** (high; real-looking): the equity card says "$6,500.00 … +$470.00 (7.8%) over the month", but LAST 30 DAYS shows Closed profit +$42.00, Funding −$38.20, Fees −$3.00, and Kestrel's card says "After fees and funding +$0.80". The only open position is "−$10.00 not yet taken". So trading accounts for about −$9, not +$470. Either deposits are counted as gain with nothing saying so, or the sum is wrong. The 1M line also starts at "Wed 7 Oct", while Bills puts today at 21 Oct ("Payday Thu 5 Nov · in 15 days"), so the line covers about 2 weeks, not a month (`money/cover-dark-trading-equity.png`, `cover-dark-trading-kestrel.png`, `cover-dark-trading-alerts-sheet.png`).
2. **Obi's money check shows an unfilled headline** (high; real-looking): on Plan the card reads only "OBI'S MONEY CHECK / from 2 ideas", with no figure. The 1-idea state reads "£350 a year from 1 idea" (`money/cover-dark-plan.png` vs `cover-dark-plan-layout.png`).
3. **Groceries' "usual" has three different values** (med-high; real if the fixture is shared): Plan says "Groceries £131.50 over your 2-month average. £191.75 so far in October", which makes usual £60.25. Spending (many) shows Groceries "£191.75 / £151 over usual", which makes usual £40.75. The What if sheet offers "Halve Groceries +£60 a month", but half of a ~£60 usual is ~£30 (`money/cover-dark-plan.png`, `cover-dark-many.png`, `cover-dark-plan-whatif.png`).
4. **Plan's Groceries card is clipped on both sides** (med): the amount "£131.50 over" loses its last letter at the right edge (reads "ovei"). The second title line "usual this month" has its first letter cut at the left. The Fuel row is cut to "Fuel over usual this m…" (`money/cover-dark-plan.png`).
5. **A new category doesn't hold the payment it was made from** (med; likely fixture): "Treats" is created from Corner Stores −£55.60 (Tue 20 Oct). The Treats sheet then says "1 payee · £3.40 of £10 this month", which is the Costa Coffee amount. "£3.40 of £10" appears twice, in the header and in MONTHLY CAP (`money/cover-dark-new-category.png`, `cover-dark-own-category-cap.png`).
6. **Counts of uncategorised payments disagree** (med): behind the sorter the screen says "3 payments without a category" and Other "3 without a category", while the sheet header says "SORT YOUR PAYMENTS · 2 LEFT". The sheet also pre-selects "Other" for an uncategorised payee. On Needs vs wants, "2 payments without a category" sits under Needs 97% £126.85 "Other", which is made of exactly those two Corner Stores payments. Uncategorised money is filed as a Need (`money/cover-dark-sorter.png`, `cover-dark-needs-wants.png`, `cover-dark-new-category.png`).
7. **"Trading is 100% of everything you hold"** (med; real-looking): Obi says this while Accounts shows Cash £1,240 and a £42,000 pension "Counted so far". Obi's risk line seems to ignore the rest of Money (`money/cover-dark-trading-equity.png` vs `cover-dark-accounts-total.png`, `cover-dark-accounts.png`).
8. **Accounts shots that don't show their state** (med; tests): `cover-dark-accounts-more.png` is `cover-dark-accounts-list.png` scrolled ~40 px, and MORE shows only its first rows in both. `cover-dark-accounts-list.png` matches `cover-dark-accounts-layout.png` except for the "2026 SO FAR" figure.
9. **"2026 SO FAR" differs across sibling shots** (med; fixture): "Spent £578.16 · in £5,000" (layout), "Spent £425.17 · in £5,000" (list/more/total), "Spent £1,050.95 · in £7,500" (accounts). None matches Spending's September £374.09 plus October £583 or £185 (`money/cover-dark-accounts*.png`).
10. **Clear my cards has a card Money doesn't know about** (med; fixture): Plan lists "Rewards card 22.9% APR £1,800" next to Platinum £4,200. Accounts and Bills' CARDS list only Platinum card, and `cover-dark-accounts.png` shows a different "Credit card" instead (`money-plan-clear-cover.png`, `money-plan-clear-unfolded.png`, `money/cover-dark-bills-tidy.png`, `cover-dark-accounts-layout.png`).
11. **Accounts headers say the same thing twice, or speak about accounts that don't exist** (med): with balances missing the card says "Add each account's balance to see one total" and then "Partial · 2 accounts have no balance yet" (`money/cover-dark-accounts-layout.png`, `-list`, `-more`, `-data-sheet`). With nothing imported it still says "Your money / Add each account's balance to see one total" when there are no accounts (`money/cover-dark-empty-accounts.png`).
12. **Edit category sheet still cut** (med): the MONTHLY CAP field is half hidden behind the Cancel / Save bar; its placeholder is cut through ("Cap it a month, e.g. £60"). When scrolled, a sliver of the name field ("Treats") shows under the sticky header. Colour circles are ~34 dp and icon tiles ~40 dp (`money/cover-dark-own-category.png`, `cover-dark-own-category-cap.png`, `cover-dark-new-category.png`).
13. **Small tap targets** (med): Trading's 1D / 1W / 1M chips are ~24 dp tall (`cover-dark-trading-equity.png`). The card sheet's "Charge card (paid in full)" / "Not a card" pills are ~23 dp (`cover-dark-card-sheet.png`). No-spend day circles are ~23 dp, folded and open (`money-plan-goals-cover.png`, `money-plan-goals-unfolded.png`). Fun money's £40/£60/£80 circles are ~37 dp. Clear my cards' strategy pills are ~31 dp (`money-plan-clear-cover.png`). Trading's "Remove" is bare faint text (`cover-dark-trading-add-address.png`).
14. **Lone words and early wraps** (med): "Debt-free Dec 2027 · 14 / mo" (`money-plan-clear-cover.png`); "Groceries · Current / account" (`money-layout-foot-cover.png`); "Income · Current / account" and "2 payments without a / category" (`cover-dark-needs-wants.png`); "3 payments without a / category" (`cover-dark-sorter.png`); "within a minute / or two" (`cover-dark-trading-add-address.png`); "updated / just now" (`cover-dark-trading-kestrel.png`); "Card · Imported / Wed 21 Oct" (`cover-dark-accounts.png`); "· imported / Wed 21 Oct" (`cover-dark-account-sheet.png`). The forecast text "A forecast needs two whole months / of statements" breaks ~150 px early (`money-plan-goals-cover.png`, `money-plan-goal-sheet-cover.png`). In the card sheet: "(for Clear my cards / on Plan)", "(if it / has one)", "· not / financial advice" (`cover-dark-card-sheet.png`).
15. **Said twice** (low): "£2,400 by Oct 2027 / You put in £2,400" (`money-plan-save-cover.png`); "£1,200 by Apr 2027 / You put in £1,200" (`money-plan-goals-unfolded.png`). In the goal sheet, "Follows Savings · up to date with its balance on Mon 12 Oct" and then "“Holiday 2027” follows Savings · it moves with its balance" (`money-plan-goal-sheet-cover.png`). Obi's answer ends "not financial advice" directly above the footer's "Not financial advice." and also reads "puts by £6,600 by Apr 2027" (`cover-dark-ask-obi-answer.png`). Kestrel's "BTC +$0.80" repeats "After fees and funding +$0.80" when there is only one asset.
16. **The same £142 shows as paid and to come** (low): Spending's "Bills to come £142" sits above Oakfield Council TAX −£142 "Thu 1 Oct" in LATEST PAYMENTS. It's the 1 Nov instalment, but the screen doesn't say so (`money-layout-review-cover.png`, `money-layout-foot-cover.png`, `money-layout-spending-unfolded.png`).
17. **Dates disagree between shots** (low; fixture): Spending says "£133 over usual by the 5th" (payments to Sat 3 Oct, Came in £0). The goal sheet says "up to date … on Mon 12 Oct". Bills and Accounts put today at Wed 21 Oct. Holiday 2027 is "£0 of £3,000" on Plan but "£800 of £3,000 · 26%" in its sheet, and 800/3,000 rounds to 27%, not 26% (`money-plan-goals-*.png` vs `money-plan-goal-sheet-cover.png`).
18. **Ask Obi's question bubble changes shape** (low): it's right-aligned and compact in `cover-dark-ask-obi-ai.png`, but full-width and left-aligned in `cover-dark-ask-obi-answer.png`.
19. **Weak selected states** (low): in the Alerts sheet the selected option (10%, 20%, 5×, 1%) differs only by gold text, with the same fill (`cover-dark-trading-alerts-sheet.png`). In the goal sheet, "Savings ✓" has the same fill as "Current account" (`money-plan-goal-sheet-cover.png`).
20. **"Not for me" stranded** (low): on Plan it sits alone on its own row under Set a cap / Remind me (`cover-dark-plan-layout.png`, `cover-dark-plan.png`). In the Idea sheet it's grey bare text in the pill row (`cover-dark-plan-idea.png`).
21. **Odd or unfinished wording** (low): the mic state says "Listening · on this phone, no signal", which reads like a connection error (`cover-dark-ask-obi-mic.png`). The What if placeholder "Extra a month (side hustle, trading): £200" reads like an entered value (`cover-dark-plan-whatif.png`). Add an address uses one field for "Public address 0x… and a name", with only Done to submit (`cover-dark-trading-add-address.png`). The Ask Obi hint is cut at "“What" with no ellipsis on every cover shot.
22. **Fun money shows an empty progress track under "Not set"** (low; A19 still): `money-plan-goals-cover.png`, `money-plan-goals-unfolded.png`.
23. **Open Fold Plan is lopsided** (low; B): the left column is empty beside Clear my cards (`money-plan-clear-unfolded.png`), and the left column ends halfway in `money-plan-goals-unfolded.png`. No-spend circles wrap 5 + 2 inside a wide tile.

#### Last week's items in this area
- A2 Open position P&L impossible: fixed (entry $60,000, now $59,900, −$10.00; liquidation 17% checks out).
- A3 September review clips £2,125.91: fixed (`money-layout-review-cover.png`).
- A10 Accounts' headline: fixed ("Counted so far £42,000 / Your 2 accounts aren't in it yet", Outside these accounts: Pension). The doubled "add balances" wording is a new item (11).
- A11 Forecast "OUT /" cut: fixed ("OUT / MONTH £280", `cover-dark-plan-whatif.png`). Trading chips with no chart: fixed on the cover (the line draws).
- A13 No-spend circles: still there (~23 dp, `money-plan-goals-cover.png`, `-unfolded.png`).
- A14 Money's lone words: "21st" fixed; "Dec / 2027" became "14 / mo" (still there); "minute or / two" became "minute / or two" (still there); "for the / month" fixed.
- A17 Edit category sheet: icon grid off the edge fixed; a sliver still shows under the header when scrolled (`cover-dark-own-category-cap.png`).
- A19: Holiday "£800 of £3,000" three times fixed; Ask Obi re-offering the question fixed; "All 10 ›" baseline fixed; Bills to come's label size fixed; Trading equity figure repeated fixed; empty progress tracks with nothing set still there (Fun money); "no ideas just now" not visible.
- A20 money many = one-column, trading equity = tiles: not visible in these shots. New identical-ish trio instead (item 8).
- A21 Obi's "spare" sum: fixed (LEFT OVER £2,220; £1,100 leaves £1,120; 6/12-month totals £13,320 / £26,640 check out).
- A23 bills tidy "£0 · no bills found yet": fixed. Trading layout on the open Fold: not visible.
- A25 Cover tab row hides the open tab: fixed (Accounts fully visible).
- A26: Edit category order fixed; cap field cut still there; colour circles still under 48 dp (~34 dp); Idea sheet fixed (rows aligned, amounts end at the figure's edge, "and 6 more"); money data sheet fixed (sits lower, Delete in red); Add a £ goal's two buttons not visible.
- A27 Plan coffee card's Not for me: left edge fixed, now stranded on its own row (item 20).
- A28: Trading "5× ·" fixed; card sheet "due", "0% / ends", "one)" fixed, "on Plan)" still there; Challenges "for the / month" and "fun / money" fixed; "24 months" alone fixed; Ask Obi "6 / months?" and "Not / financial advice." fixed.
- A29: "Nothing imported yet" twice fixed; "your month shows here" twice fixed.
- A31 Money light contrast: not visible (no light shots).
- A32: Spending Left vs Bills to come fixed (now "After bills" = in − spent − bills); Kestrel £2,140 fixed (£2,120); card sheet pills now drawn but ~23 dp; What if slider fixed; date-header totals fixed; "Save £ a month", Apr bar label and OUT £280 vs Bills £295 not visible; uncategorised count vs sorter still there in a new form (item 6); Coffee £57.60 vs Spending still disagrees (Needs vs wants shows Coffee £3.40).

### g1-moneyb
Read 40 of 40 shots. (None unread. md5 check: no byte-identical pairs in this group.)

#### Findings (ranked, most severe first)
1. **Plan's Groceries idea clips its figure and orphans "month"** (high; real-looking): the card title wraps to "Groceries over usual this / month" with "month" alone on line 2, and the amount on the right is cut at the card's edge: "£131.50 over" loses the end of the "r" against the padding (`money/inner-dark-columns-plan.png`).
2. **Groceries "over usual" disagrees between tabs** (high; real-looking, same £191.75): Spending says "Groceries … £191.75 · £151 over usual", but Plan's idea says "Groceries £131.50 over your 2-month average. £191.75 so far in October" (`money/inner-dark-columns-spending.png`, `inner-light-many.png`, `cover-light-many.png` vs `inner-dark-columns-plan.png`).
3. **Bills counted as spent and as still to come** (high; real if confirmed): Spending lists "Bills 24% £142 · as usual" inside "Spent so far £583" and also has a "Bills to come £142" tile, with "After bills £1,775" = 2,500 − 583 − 142, so the £142 is taken off twice. Bills says only "£11 of £153 already paid since payday" and that the £142 Council tax is still to go out on Nov 1 (`money/inner-dark-columns-spending.png`, `inner-light-many.png`, `cover-light-many.png` vs `inner-dark-bills-layout.png`, `inner-dark-columns-bills.png`).
4. **Forecast's OUT / MONTH changes between sibling shots** (med; fixture): it is £230 (LEFT OVER £2,270, "about £1,145") in `inner-dark-columns-plan.png`, £280 (£2,220, "about £1,120") in `inner-dark-plan-layout.png`, and £270 (£2,230, "about £1,105") in the `plan-*` shots. Also, "at your pace" £270 a month sits beside October's "Spent so far £583" by the 21st.
5. **"2026 so far" gives three different years on Accounts** (med; fixture): "Spent £425.17 · in £5,000" (`cover-dark-worth-other-row.png`), "Spent £578.16 · in £5,000" (`inner-dark-accounts-layout.png`), "Spent £1,050.95 · in £7,500" (`inner-dark-columns-accounts.png`, `inner-light-accounts.png`). "in £5,000" for a whole year beside "Came in £2,500" a month reads as nonsense. The accounts also switch between "Platinum card" and "Credit card", and the bill between "Streamly.com" and "Netflix.com" (`inner-dark-bills-layout.png` vs `inner-dark-columns-bills.png`).
6. **Statement date disagrees** (med; fixture): Challenges says "Your statements reach Thu 15 Oct, so that's as far as these count", while Accounts says "Imported Wed 21 Oct" and Spending says "by the 21st" (`money/plan-abroad-tiles-cover.png`, `plan-challenges-tiles-cover.png` vs the accounts shots).
7. **Obi's money check disagrees with itself across shots** (med; fixture): "Nothing to look at just now" (`plan-forecast-tiles-unfolded.png`) vs "£350 a year from 1 idea" (`inner-dark-plan-layout.png`) vs a near-empty card saying only "from 2 ideas" with no figure (`inner-dark-columns-plan.png`), all against the same Groceries/Fuel overspend.
8. **Trading's 1M line starts on Wed 7 Oct** (med; real-looking): with 1M selected, the axis runs "Wed 7 Oct … Now", which is two weeks, not a month. "+$470.00 (7.8%) over the month" also isn't explained by "Closed profit +$42.00" and "−$10.00 not yet taken" (deposits?) (`money/inner-dark-trading-equity.png`).
9. **1D/1W/1M chips with no line** (med, still): "The line draws once two days have been read" sits above live 1D/1W/1M chips (`money/cover-dark-trading-risk.png`, `inner-dark-trading-risk.png`).
10. **Open Fold columns leave one side empty or out of line** (med): on trading-layout the whole left half is black while the right column scrolls on (`money/inner-dark-trading-layout.png`); on plan-abroad and challenges the right column ends after "Spending plans" with half a screen empty (`plan-abroad-tiles-unfolded.png`). On Bills, the right bill-list card has no section heading and its top sits ~40 px above the left card's top (`inner-dark-bills-layout.png`, `inner-dark-columns-bills.png`).
11. **Ask Obi sheet on the open Fold runs off the bottom** (med): the centred "Obi · money" sheet continues past the screen edge under "Done" with no bottom margin, and the content behind shows through at the right ("£142", "See all 16 ›") (`money/inner-dark-ask-obi.png`).
12. **Said twice** (med): Accounts says "Add each account's balance to see one total" then "Partial · 2 accounts have no balance yet" (`inner-dark-accounts-layout.png`). AI says "On · $2.05 of $20 this month" then "This month by feature · $2.05 in all" (`ai/cover-*-one.png`, `inner-dark-one.png`). Trading has "1 open position · −$10.00 not yet taken" over "−$10.00" (`cover-dark-trading-risk.png`, `inner-dark-trading-*`). "£1,105" appears in both the What if line and Obi's line under it (`plan-challenges-tiles-unfolded.png`). Plan says "£350 a year" / "~£350/yr" / "saves about £350 a year" (`inner-dark-plan-layout.png`). AI empty has "Ask works once this device is connected" then "Until this device is connected" (`ai/cover-dark-empty.png`). Mail has "No emails waiting on you ✓" over "Nothing needs you from 60 days of mail ✓" (`mail/cover-dark-by-hand.png`).
13. **Outside these accounts sheet's hint looks like data** (med): the field placeholder "Pension £42,000 · Car £8,500 · Car loan £3,200" repeats the real £42,000 pension, so it reads as if a car and loan were entered. The row's sub-line "You have · typed today" is garbled. The headline row "Outside these accounts / Pension" shows no amount (`money/cover-dark-worth-other-sheet.png`, `cover-dark-worth-other-row.png`).
14. **AI usage bars use one stub width for every small figure** (low/med): 31¢, 22¢, 14¢, 9¢, 1¢ and "under 1¢" all draw the same ~36 px bar. On the open Fold, labels and figures sit ~1,800 px apart (`ai/cover-dark-many.png`, `inner-dark-many.png`). Also "Not split by feature $15.48" is 85% of "$18.30 in all".
15. **Text wrapping early** (low/med): AI's fallback list wraps ~100 px short of the card edge ("still answer / on this device", "in Navilife's / own words", "no replies / are drafted", "the AI / is back") (`ai/cover-dark-empty.png`, `cover-dark-many.png`). Obi on calls has a lone "your tap" (`obi-calls/*`), Challenges "Cap a category / for the month" and "Pick a week's / fun money" (`plan-challenges-tiles-cover.png`), Plan "about £350 / a year." (`inner-dark-plan-layout.png`), and Accounts "side / hustle to start" (`cover-dark-worth-other-row.png`).
16. **Forecast's "+£13.4k" label touches the May bar** (low): the Apr label overlaps the next bar's top (`plan-forecast-tiles-cover.png`, `plan-forecast-tiles-unfolded.png`, `plan-challenges-tiles-unfolded.png`).
17. **Small targets** (low/med): No-spend day circles are ~22–23 dp (`plan-*-tiles-*`). Obi on calls' cap pills £10/£20/£30/£50 are ~31 dp tall (`obi-calls/*`). Bare text links "Remove", "Hide", "How it works" and "‹ ›" month arrows (`cover-dark-trading-layout.png`, `mail/cover-dark-by-hand.png`, Spending shots).
18. **Fun money "£60 of £60" with a full bar** (low): reads as all spent, beside "0 this week" no-spend days, and conflicts with "Not set" in the unfolded sibling (`plan-abroad-tiles-cover.png` vs `plan-abroad-tiles-unfolded.png`). Paying abroad lists both payments as "Other · Thu 15 Oct" / "Other · Wed 14 Oct" with no merchant.
19. **Light theme edges** (low): white cards on the near-white page have almost no edge (`money/inner-light-accounts.png`, `ai/cover-light-one.png`, `obi-calls/cover-light.png`). Grey percentages "33%", "24%" are faint (`inner-light-many.png`).
20. **Smaller ones** (low): "Oakfield Council TAX" in caps (`inner-dark-bills-*`). Mail's Close sits top-left while Money's is top-right. Spending "one" uses a "Left" tile but "many" uses "After bills" for the same idea (`inner-dark-one.png` vs `inner-dark-columns-spending.png`). Day headers repeat the single row's amount ("Mon 5 Oct +£2,500" over "+£2,500") (`inner-dark-one.png`). Obi on calls shows an empty progress track at £0 (`obi-calls/*`). The cover Ask Obi field cuts its hint to "“What" (all cover Money shots).

#### Last week's items in this area
- A2 (P&L impossible): fixed. Entry $60,000, now $59,900, −$10.00 (`cover-dark-trading-risk.png`, `inner-dark-trading-*`).
- A10 (Accounts headline label repeated): mostly fixed. Now "Counted so far £42,000"; the "Outside these accounts / Pension" row still shows no amount (finding 13).
- A11 Forecast "OUT /" losing MONTH: fixed ("OUT / MONTH" on cover).
- A11 Mail Autopilot line twice: fixed (`mail/cover-dark-by-hand.png`).
- A11/A32 Trading 1D/1W/1M chips with no chart: still there (`cover-dark-trading-risk.png`, `inner-dark-trading-risk.png`).
- A13 No-spend circles small: still there, ~22–23 dp (`plan-*-tiles-*`).
- A14/A28 lines ending on "·" or a lone word: no trailing "·" seen; lone words still there ("month", "your tap", "a year.") (finding 15). "Cap a category for the / month" is no longer on a pill edge but still wraps on the cover.
- A19 AI card chevron pointing down while open: fixed (now "^").
- A19 "Nothing to look at just now": still on `plan-forecast-tiles-unfolded.png`.
- A19 empty progress tracks with nothing set: still there (Obi on calls £0, Fun money "Not set").
- A19 "All 10 ›" off the chips' baseline: fixed.
- A19 Trading equity figure repeated: not visible (equity shows once, with "≈ £5,038").
- A20 byte-identical pairs (inner many = columns-spending, trading equity = tiles, plan-forecast-six = plan-goals-empty): fixed for the shots here (no identical pairs; forecast-six shows the 6-month chart).
- A21 Obi's "spare" sum: fixed. £2,230 − £1,125 = "about £1,105" in both lines, and £2,220 − £1,100 = £1,120 (`plan-*-unfolded.png`, `inner-dark-plan-layout.png`).
- A23 trading layout on open Fold only the loading line: fixed (`inner-dark-trading-layout.png`, but see finding 10). Plan-abroad unfolded showing abroad tiles only at the bottom edge: still there.
- A26 Add a £ goal two identical gold buttons: fixed (Add goal + Cancel).
- A28 Trading alerts "5× ·": fixed ("a big order 5× · funding 1%").
- A29 Money empty's "your month shows here" twice: fixed on the open Fold (`inner-dark-empty.png`).
- A31 light contrast, Money's unselected tabs: fixed (outlined). Grey percentages: still faint.
- A32 Spending's Left = in − spent beside Bills to come: changed to "After bills £1,775", which now takes the £142 off twice (finding 3).
- A32 Kestrel "About £2,140": fixed (£2,120).
- A32 forecast OUT £270 vs £280: still there (finding 4); Coffee £57.60 vs Spending: not checkable here (Coffee is not in Spending's top 5); statements 13 vs 15 Oct: now 15 vs 21 Oct (finding 6).

### g2-needsa
Read 38 of 38 shots. (None unread. Byte-identical by md5: `cover-dark-mail-by-hand` = `cover-dark-mail`; `inner-dark-many` = `inner-dark-two-columns` = `inner-dark-two-columns-boxed`.)

#### Findings (ranked, most severe first)
1. **Header counts don't match what's shown** (high; real-looking): the default state's header says "2 to decide · 3 to reply · 2 FYI" with chip "Decide 2", but the All list shows no decide item at all, only MESSAGES and FYI. "3 to reply" also disagrees with the badges below: Sam "1 to reply" + Game testing "2 to reply" + Alex "1 to reply" = 4. On the open Fold the chip says "Mail 12", but the header leaves mail out and no mail appears on screen. (`cover-dark.png`, `cover-light.png`, `inner-dark.png`)
2. **Mail tidied numbers don't add up** (high; may be the fixture): tiles read "138 tidied", "33 newsletters", "71 promos", "33 junk & scams", and 33+71+33 = 137. The sibling group card says "41 tidied · 12 newsletters · 24 promo…" for the same "today". (`mail-cover-dark.png`, `groups-cover-dark.png`)
3. **"newslett / ers" broken mid-word** (high): the 33 tile's label wraps inside the word. (`mail-cover-dark.png`)
4. **Two different days both "in 7 days"** (high; real-looking): "Alex and Sam are off Mon 21 Dec · INSET day · in 7 days" and "Date night · Fri 18 Dec · In 7 days". With Dentist "Due Mon 14 Dec", 21 Dec is 7 days away, so Fri 18 Dec is 4 days away. (`decide-others-cover-dark.png`, `-cover-light.png`, `-inner-dark.png`, `decide-others-booked-open.png`)
5. **Messages group open with nothing under it** (high): "Messages 3" shows its up-chevron (open) and a square-bottomed header, but no rows follow; the FYI card comes straight after. (`groups-cover-dark.png`, `groups-cover-light.png`, `groups-cover-dark-fyi-open.png`, `groups-cover-light-fyi-open.png`)
6. **Shots that don't show their state** (med; tests): `cover-dark-mail-by-hand` is byte-identical to `cover-dark-mail`. The three "two-columns" shots are identical to `inner-dark-many` and have only one column of content; the right side is just "No FYI or tidied mail right now". `inner-dark-mail` is the only open-Fold shot with anything in the right column.
7. **The same thing said twice** (med): Sam's card shows "Did you see my missed call and voice note?" as the preview and again in full under it (`cover-dark.png`, `cover-light.png`, and in full both times on `inner-dark.png`). "6–7 of 7" appears in the FYI header and again in its pager (`groups-*-fyi-open.png`), and "1–5 of 7" the same way (`mail-box-*.png`). "Mail tidied today 41" sits over "41 tidied" (`groups-*.png`). "Renewals found in mail" sits over "RENEWALS FROM YOUR MAIL" (`cover-*-mail.png`, `inner-dark-mail.png`). Both FYI rows read "Says thanks for Saturday" (`groups-*-fyi-open.png`).
8. **Key facts cut off on the cover's decide cards** (med): "expires i…" (the expiry on a trade, which is the reason to act now), "Asked 09:1…", "You're in the offi…", "Book somewhe…", "Dentist · … · Las…" (it hides "Last went Sun 14 Jun. Booked the next one?"), "Put back fro…". The open Fold shows these in full. (`decide-approvals-cover-*.png`, `decide-others-cover-*.png`, `mail-cover-dark.png`)
9. **Open Fold's hint cut at the bottom edge** (med): the "Tap a heading…" line is sliced off at the bottom while the right column is empty. (`inner-dark-many.png`, `inner-light-many.png`, `inner-dark-two-columns*.png`)
10. **Placeholder fixture text in mail rows** (med; test fixture): "Subject 0" … "Subject 4" at times "00:12", "01:12", "02:12", "03:12", "04:12". (`mail-box-cover-*.png`)
11. **Chip row cut at the right edge** (med): "Mail" / "Mail 2" is cut against the screen edge, and on the default and groups states only an "M" shows. The chip row also sits ~18 px higher in those states than in the others. (`cover-dark.png`, `cover-light.png`, `groups-*.png`, `cover-*-mail.png`, `cover-*-many.png`, `cover-dark-empty.png`)
12. **Lines ending on "·", a lone word or bare glyphs** (med): "Task: Check the algorithm · / Today" (`decide-cover-*.png`). "Renews Tue 3 Nov · £10.99 a / month · Subscription" ends a line on a lone "a" (`cover-*-mail.png`, `inner-dark-mail.png`, which has a ~900 px-wide column). The hint ends a line on "‹ ›" then "for the next ·" on the cover, and "for the / next ·" on the open Fold. "Approvals, replies and decisions land / here." (`cover-dark-empty.png`). "Ten-minute inbox · one / at a time" (`cover-*-mail.png`, `mail-box-*.png`). "Navilife's AI works once this device is / connected" wraps ~250 px early on the open Fold (`inner-dark-mail.png`). "Alex, Sam, Jo + 4 …" has a space before the ellipsis (`groups-cover-*.png`).
13. **Overdue dates and example dates in the past** (med; likely fixture): "Overdue · was due Sun 18 Oct 17:15" on a Dec timeline. The When? field's example reads "Tue 20 Oct 9:30" for a dentist due Mon 14 Dec. Streamly "Renews Tue 3 Nov" sits under "to add". (`cover-*-many.png`, `inner-*-many.png`, `decide-others-booked-open.png`, `cover-*-mail.png`)
14. **Left edges drift** (med): page text at ~37 px, the hint and "To a day…"/"2 more waiting" at ~46–55, FYI names at ~46, the decide card's text at ~92 against group text at ~73 (`cover-dark-many.png`, `cover-dark.png`). In the mail box, Reply/Done/Task/More start at ~37 against row text at ~73 (`mail-box-cover-*-open.png`). "Call to book / Copy email" start ~22 px right of the buttons above (`decide-others-*.png`). In the unsubscribe card the title starts at ~127 against the tiles at ~106 (`mail-cover-dark.png`).
15. **Open Fold layouts waste the width** (med): the approval and decide cards stretch to ~1920 px with all content in the left ~40% and buttons hard left (`decide-approvals-inner-dark.png`, `decide-inner-dark.png`, `decide-others-inner-dark.png`). In the empty state, "No FYI or tidied mail right now" floats mid-screen out of line with the left column (`inner-dark-empty.png`). The "To decide" header card and its body card are drawn as separate boxes with a gap and a square-cornered header bottom (all group states).
16. **Small tap targets** (low): pager ‹ › circles are ~32 dp (`groups-*-fyi-open.png`, `mail-box-*.png`). Bare "✓" glyphs on FYI rows (`cover-dark.png`, `cover-light.png`, `inner-dark.png`). Text-only "No", "Decline", "Covered", "Not yet", "Keep" (Keep sits tight against Unsubscribe in `mail-cover-dark.png`).
17. **Mixed type sizes in the renewals card** (low): "Renews Tue 3 Nov · £10.99 a" is larger than the "Streamly" title above it and than the mail row subtitles. (`cover-*-mail.png`)
18. **Light theme edges** (low): white chips, group headers and action pills (Reply/Done/Task/More) sit on an off-white page with barely visible edges, and the pager circles are faint. (`cover-light*.png`, `groups-cover-light*.png`, `mail-box-cover-light-open.png`)
19. **Odd copy** (low): "Thanked you, regards to the footballers" and "Jokes the boys have eaten his money". The top-right action switches between "Close all" and "Clear FYI" between states. (`cover-dark.png`, `inner-dark.png`)

#### Last week's items in this area
- A11 "Draft replies to 2" over "nothing is drafted": fixed (no Draft replies line now; `cover-dark-mail.png`).
- A20 needs-you by-hand byte-identical: partly fixed. `cover-dark-by-hand` now differs from its siblings, but `cover-dark-mail-by-hand` = `cover-dark-mail` is still byte-identical, and there are new identical triples (`inner-dark-many` = `two-columns` = `two-columns-boxed`).
- A22 open Fold mail state "Nothing needs you" beside mail, and "2 mail" leaving out renewals: fixed. It now reads "Nothing to decide" and the header says "2 mail · 2 renewals". Its subtitle "Approvals, replies and decisions land here." still sits beside 2 mail to reply (`inner-dark-mail.png`).
- A27 mail rows ~28 px left of group text: fixed (rows now line up with the group header text). Close all mid-screen: fixed (now at the right edge). Mail chip cut on the cover: still there (`cover-light-mail.png`, `cover-dark.png`).
- A28 hint "for the next ·": changed but still awkward. The cover line now ends on "‹ ›", and the open Fold reads "for the / next ·". "£10.99 a month ·": still there in a new form, "£10.99 a / month" (`cover-*-mail.png`, `inner-dark-mail.png`).

### g2-needsb
Read 38 of 38 shots. (All read. No two shots in the list are byte-identical; checked with md5sum.)

#### Findings (ranked, most severe first)

1. **Routine says it is running now and also that it has never run** (high; real-looking): the tile reads "LAST RUN · Now" and the card reads "RUNNING NOW … Step 1 of 4", but LAST TIME at the bottom says "It hasn't run yet. What it did will show here after each run." (`routines/cover-dark-running.png`).
2. **The mail tiles don't add up** (high; real if the four tiles are meant to make up the whole): "138 tidied" next to "33 newsletters", "71 promos" and "33 junk & scams", which add up to 137. This is on both widths (`needs-you/mail-cover-light.png`, `needs-you/mail-inner-dark.png`).
3. **"Empty" state says there is nothing to watch over three helpers that are on** (high; real-looking): the header reads "Nothing to keep an eye on yet · add a renewal, a birthday, something you're waiting on…", while STANDING ERRANDS below lists Renewals watcher, Birthday keeper and Follow-up chaser, each with a "Turn off" button, so all three are on (`working-on/cover-dark-empty.png`).
4. **Retry calls back for the slot that was already full** (med; probably the test fixture): the not-booked card says "They're full at 7 on Saturday · they offered 21:00". After Try again, the new call card is for "Sat 24 Oct", WINDOW "19:00–20:00", the same full slot, and the 21:00 they offered is not used (`working-on/cover-dark-not-booked.png`, `cover-dark-tried-again.png`).
5. **The answer still isn't in the draft** (med; real-looking, carried over from A24): YOU SAID "Tuesday morning", but the draft still says "next week, ideally a morning". A line now explains the gap: "Obi is putting your answer into this draft · copy it once he has". The state still shows a stale draft with a Copy-later instruction (`working-on/cover-dark-answered.png`).
6. **Routine added by hand is paused as soon as it's saved** (med; probably the test fixture): the header reads "All paused", then "Added Sunday reset · Every Sunday at 19:00", and the only routine is greyed out with "Paused ·" and its toggle off. A routine that was just added starts out paused (`routines/cover-dark-by-hand.png`).
7. **Same routine offered again right after saving it** (med): you saved "Sunday evening", SUN 19:00, covering bills, plan next week, tidy inbox and bins. START WITH ONE still offers "Sunday reset", SUN 19:00, "Bills, plan the week, inbox, bins" with Add (`routines/cover-dark-new-list.png`).
8. **The same message is shown twice in one card** (med): the Game testing group row's preview line "Kim: Says she isn't in the group and asks you to add her" is repeated word for word as the second expanded line just under it. This happens on the cover (light and dark) and on the open Fold (`needs-you/open-cover-dark.png`, `open-cover-light.png`, `open-inner-dark.png`).
9. **Action chips cut off at the card edge on the cover** (med): the row Reply / Done / Later / Task runs past the card's padding, so the "Task" chip is sliced flat at its right edge. Nothing shows that the row scrolls (`needs-you/open-cover-dark.png`, `open-cover-light.png`).
10. **Word broken in the middle in a tile** (med): the newsletters tile wraps as "newslett / ers" (`needs-you/mail-cover-light.png`).
11. **Lines that wrap early or end on a lone word** (med):
    - Renewals card on the cover: "Home insurance renews / Thu 5 Nov · keep it, or look / around first?" breaks about 150 px before the card's edge. The Birthday card does the same with "Sam's birthday · Sun 25 Oct · in / 4 days" (a line ending on "in") and "Pick a gift · order / …" (`working-on/cover-dark-many.png`, `cover-light-many.png`).
    - Call card: "Obi found 01632 960111 / on oakfieldbarbers.example" leaves about 300 px of the first line empty (`working-on/cover-dark-found.png`, `cover-light-found.png`).
    - Lone word: needs-you hint "swipe right for / Done" (`needs-you/open-cover-*.png`).
    - Expanded Obi row: "Asks / before acting" (`team/cover-dark-unfolded.png`).
    - New routine hint: "Just show · Asks / you · Obi does it" (`routines/cover-dark-new.png`).
    - Team sheet: "Reminded you: Black bin / out tonight" with room left on the line (`team/cover-dark-sheet.png`).
    - Step titles wrap early beside their pills: "Show my bills / for the week", "Remind me / about bins" (`routines/cover-dark-new.png`); "Show the week ahead / of bills" (`routines/cover-dark-running.png`).
12. **Mail card text cut off on the cover** (med): the footer reads "Still in Gmail and Outlook · Put back fro…", truncated before "See all ›". On the open Fold it reads in full as "Put back from the list" (`needs-you/mail-cover-light.png`).
13. **Left edges that drift** (low/med):
    - Needs you: the expanded message lines start at about x=159, roughly 18 px left of the name and preview text (x≈177) (`needs-you/open-cover-*.png`).
    - Mail: the "Unsubscribe from Oakfield Kids Club?" card text starts about 20 px right of the tile text above it (`needs-you/mail-cover-light.png`, `mail-inner-dark.png`).
    - New routine: the WHEN / STARTS WITH tile text sits at x≈55 while the step cards below start their content at x≈73 (`routines/cover-dark-new.png`).
14. **Team sheet shows a sliver of the row behind it** (low/med): the tops of a line of text from the expanded Obi row ("Your assistant…") peek out just above the sheet's top edge (`team/cover-dark-sheet.png`).
15. **Small tap targets** (low/med):
    - The −/+ circles on the call card are about 40×32 dp (`working-on/cover-*-found.png`, `cover-dark-tried-again.png`, `cover-dark-used-found.png`).
    - Bare text targets: "► Play" on the not-booked card (`working-on/*-not-booked.png`), "Stop" at the right of the running card (`routines/cover-dark-running.png`), and "Keep" beside the Unsubscribe pill (`needs-you/mail-*.png`).
16. **Same two reminders listed in a different order** (low): both are at 17:15. The expanded row lists "Call the garage" then "Black bin out tonight"; the sheet lists them the other way round (`team/cover-dark-unfolded.png` vs `team/cover-dark-sheet.png`).
17. **Money row says "Not set up" over "Nothing yet today"** (low): a helper that isn't set up gets the same status line as the ones that are ready (`team/cover-dark-quiet.png`).
18. **Mixed time styles and capitals** (low):
    - "They're full at 7 on Saturday · they offered 21:00" (`working-on/*-not-booked.png`).
    - Title Case in the middle of a list: "Show my bills for the week, Plan next week, Tidy my inbox, Remind …" (`routines/cover-dark-new-list.png`).
    - "Chase Casey: The drill back" (`working-on/cover-dark-one.png`).
    - STARTS WITH "A nudge" (`routines/cover-dark-new.png`).
19. **Said twice, mildly** (low):
    - The Ask sheet repeats the page's intro paragraph almost word for word ("Ask Obi to look into something. He works on it step by step…"), and the dimmed copy is visible behind it (`working-on/cover-dark-ask-sheet.png`).
    - The running card's subtitle "Sunday reset" repeats the page title directly above it (`routines/cover-dark-running.png`).
20. **Large empty areas on the open Fold** (low; layout review B material): every row stretches to the full ~1900 px width with chevrons and toggles far right. The mail card has a tall empty band between its text and Keep / Unsubscribe. The not-booked card has an empty band between Play and Try again (`team/inner-dark-busy.png`, `routines/inner-dark-many.png`, `needs-you/mail-inner-dark.png`, `working-on/inner-dark-not-booked.png`).
21. **Light theme** (low):
    - On Team, the stat tiles and the helpers card are only slightly darker than the page, so their edges barely show (`team/cover-light-busy.png`).
    - On Routines, YOUR ROUTINES cards have an outline but the START WITH ONE card doesn't (`routines/cover-light-many.png`).
22. **Shot name doesn't match the state** (low; test): `team/cover-dark-unfolded.png` is the folded cover with the Obi row expanded, not the open Fold. If "unfolded" means "row expanded", the name is misleading.

#### Last week's items in this area
- A4 Team tiles vs cards: **fixed**. 2 working = Obi + Money, both "Working", and Money shows "1 heads-up" (`team/cover-dark-busy.png`, `inner-dark-busy.png`).
- A5 "nothing needs you" over CALL CARD · NEEDS YOUR TAP: **fixed**. It now reads "Holding 1 call card for you · 1 needs you" (`working-on/cover-light-found.png`).
- A11 Needs you's "Draft replies to 2" over "nothing is drafted": **not visible in these shots** (my mail shots show only the "Mail Navilife tidied today" card).
- A12 Routines' rows:
  - Tiles: **fixed**. They are all the same width and the titles line up.
  - Subtitles: **fixed**. They are no longer cut off.
  - Running: **partly fixed**. The bar is now partly filled, but LAST RUN "Now" contradicts LAST TIME "It hasn't run yet" (finding 1).
- A20 Identical pairs:
  - **Fixed**: routines new-list / empty / by-hand, working-on found / used-found and not-booked / tried-again are now all different.
  - **Not in these shots**: the by-hand shots of needs-you, team and working-on.
- A22 Headers that say nothing over a card:
  - Working-on not-booked: **fixed**. It reads "1 call finished · nothing needs you" over a finished call.
  - Team quiet: **fixed**. It reads "4 helpers on" with Money Not set up.
  - Team light busy: **fixed**. Money is Working.
  - Needs-you inner mail: **not visible**.
  - The same pattern appears on working-on empty (finding 3).
- A24 Answer doesn't reach the draft: **still there, now with an explanation** (finding 5).
- A27 Needs you's mail rows drift, Close all mid-screen, Mail chip cut: Close all and the Mail chip are **not visible**. A smaller drift is **still there** (the Unsubscribe card is inset about 20 px from the tiles; finding 13).
- A28 Lines ending on "·":
  - Needs you's "for the next ·" and "£10.99 a month ·": **not visible**.
  - "Obi found … · on": **fixed** as a dot ending, but the line still wraps early (finding 11).
  - Card wrapping ~140 px early: **still there** on the Renewals and Birthday cards (finding 11).
- A29 Said twice:
  - "Home insurance renews…" twice on `inner-dark-many`: **fixed** (WHAT I DID now says "Watching 1 renewal").
  - "Obi has your answer" twice and "never sends…" twice on answered: **fixed**.
  - NOT BOOKED / restaurant name twice: **fixed**.
  - Routines' Morning brief time on both the tile and the line: **fixed**.
- A30 Call card tiles:
  - Sizes and figures: **fixed**. WHEN, WINDOW and PEOPLE are now the same size with the same figure size.
  - "Party size and time" under OBI MAY SAY: **still there**, though it reads as a list of what Obi may say rather than a duplicate.
- A31 Routines light contrast: **fixed**. Time tiles are outlined and the paused tile is readable (`routines/cover-light-many.png`).
- A13 / A32 Small −/+ targets: **still there** on the call card (about 40×32 dp; finding 15).

### g3-todaya
Read 28 of 28 shots. (None unread.)

#### Findings (ranked, most severe first)

1. **Fasting card on the open Fold is crushed to a sliver under Up next** (high; real-looking): in `today/inner-dark-fasting.png` the fasting card is a full-width strip ~60 px tall; only the top of the ring's arc shows, with no FASTING label, time, "Ends in…" or End button. The Up next card is drawn over it. In `today/inner-dark-fasting-end.png` the same empty strip stays pinned at the top while the left column scrolls under it. The ring's gold arc overlaps the "Done" button, and Up next's title has gone, leaving only "Done / Tomorrow / Open". The cover (`cover-dark-fasting.png`) draws the card correctly: "13:30 · Switching to fat · Ends in 2:30 · 19:45 · End".

2. **"TASKS 0 · nothing planned" over a task** (high; real-looking, the tile's own count): in `today/cover-dark-one.png` and `inner-dark-one.png` the tile says "0 / nothing planned". The same screen shows UP NEXT "Book the MOT", Shut down the day "1 left", and the schedule line "17:15 · 1 anytime task". The fasting shots say the same over Book the MOT (`cover-dark-fasting.png`, `cover-light-fasting.png`, `inner-dark-fasting*.png`). The "one" state reads as empty in the header.

3. **Counts that disagree on the "many" screen** (high; real-looking): the tile says "1 of 5 done today" and the list says "TODAY · 4 LEFT", but Shut down the day says "5 left · move or drop them". UP NEXT is "Fix the gate latch", which is not one of the 4 rows listed (Pay the window cleaner, Order new bins, Book the MOT, Plan Saturday's football run). It looks like the one already done, still offered as next. Seen in `cover-dark-many.png`, `cover-light-many.png`, `inner-dark-many.png`, `inner-light-many.png` and `cover-dark-many-end.png`.

4. **"3H 15M" doesn't match the rows' minutes** (med; real if confirmed): the listed durations are 15 + 30 + 120 min = 2 h 45, and Order new bins shows none. The header says "4 LEFT · 3H 15M", so some 30 min is uncounted or hidden (`cover-dark-many-end.png`, `inner-dark-many-end.png`). The brief says "4 planned · about 3h 30m" for its own 4 tasks (`brief/cover-dark-many.png`).

5. **NEXT tile says "Free · nothing else today" while the schedule has more** (med; real-looking): in every non-schedule Today shot, the schedule right under the tile shows "17:30 Commute home · Home about 18:50", 15 min after now (17:15). The empty state's timeline row also reads "17:15 · Nothing else planned after 18:50", sitting above the 17:30 commute (`cover-dark-empty.png`, `inner-dark-empty.png`).

6. **Open-Fold "many-end": the list scrolls under the pinned habit chips** (high; real-looking): in `today/inner-dark-many-end.png` the tiles and habit-chip row stay fixed. The first task, "Pay the window cleaner", is sliced through its title under "Read 20 pages / Stretch", with no fade or edge. The TODAY header and progress bar have scrolled out of sight, and the right column doesn't move.

7. **Setup's step count changes between sibling shots** (med; fixture): "12 steps left" in `inner-dark-empty/one/many/fasting*.png` and `inner-light-many.png`, but "11 steps left" in `inner-dark-schedule.png` (A8's "12 steps left" is still there).

8. **Brief and Bedside disagree with Today about the same Wed 21 Oct** (med; mostly fixtures):
   - The Focus task is "Plan Saturday's football run with **Sam**" on the brief and bedside, but "…with **Alex**" on Today.
   - Quarterly planning is today at 10:00 on the brief, but Today's Plan tomorrow says "Thu 22 Oct · 1 event · first 10:00 Quarter…".
   - The brief lists Dentist 13:45, Call with Priya 17:00 and Swimming 18:30. Today's schedule shows none of them and says "Free · nothing else today".
   - The brief's tasks (football, bins, MOT, gate latch) leave out Today's "Pay the window cleaner".
   - The brief says "4 events · 4 tasks" in `brief/cover-dark-many.png` but "2 tasks · 2 planned · about 1h 30m" in `bedside/stand-dark-brief.png`.
   - The bedside's "Next: Plan Saturday's football run" doesn't match Today's Up next, "Fix the gate latch".
   - Commute home is "about 18:50" in most shots but "18:20" in `cover-dark-schedule.png` / `cover-light-schedule.png`.

9. **"Parents' evening" at 09:30** (med; fixture): `bedside/dream-inner-dark-evening.png` shows TOMORROW · THU 22 OCT "First thing 09:30 Parents' evening at Oakfield…", an evening event in the morning and inside "Work 09:00–15:30". Today says Thursday's first event is 10:00 Quarterly planning.

10. **Today's schedule cut off by the capture bar on the open Fold** (med; A1, partly still there):
    - `inner-dark-schedule.png` shows the timeline only down to "17:15", faded under the bar, so the screen named "schedule" never shows Commute or Training while the left column has ~350 px empty below "Plan today's tasks".
    - `inner-dark-one.png` has "Plan today's tasks · Day list ›" half-hidden and faded under the bar.
    - On the cover, `cover-dark-schedule.png` cuts after Training, with the next row's text just showing under the bar.

11. **Brief's AI note is garbled** (med; real): "Navilife's AI works once this device is connected · read from your day as it is, in Navilife's own words" doesn't read as a sentence (`brief/cover-dark-many.png`, `cover-light-many.png`, `inner-dark-many.png`, `bedside/stand-dark-brief.png`).

12. **Said twice** (low–med):
    - The schedule header "AT WORK UNTIL 17:30" is followed by the row "Work · Now · until 17:30" in all Today shots.
    - The brief's "4 events · 4 tasks · first at 10:00" sits over "4 planned · about 3h 30m", the task count said twice.
    - The brief's WAITING ON "Waiting on 1 thing · 1 to chase today" sits over "Casey · chase today".
    - The bedside's "UP NEXT" heading is followed by a line starting "Next:" (`dream-cover-light-day.png`, `stand-dark-day.png`).

13. **"1 left · move or drop them"** (low; real): plural "them" for one task (`cover-dark-one.png`, `cover-*-fasting.png`, `inner-dark-one.png`, `inner-dark-fasting*.png`).

14. **Schedule header wraps to a lone "2 EVENTS"** (low): "SCHEDULE · 1 ALL DAY · AT WORK UNTIL 17:30 · / 2 EVENTS", with the first line ending on "·" (`cover-dark-schedule.png`, `cover-light-schedule.png`).

15. **Lone words and truncation** (low):
    - The brief's Focus task wraps "…football run with / Sam" (`brief/cover-*-many.png`).
    - Headlines end on "fund", "charts" and "weekend" (`brief/cover-dark-many-foot.png`, `cover-dark-topics.png`).
    - "Plan tomorrow · Thu 22 Oct · 1 event · first 1…" is cut on the cover, and "nothing planne…" is cut when it would nearly fit.

16. **Uneven vertical rhythm on Today, open Fold** (low): in `inner-dark-many.png` / `inner-light-many.png` the habit chips sit ~14 px above the Up next card but ~96 px separates Up next from This evening. "Navilife health · 4 things need a look" floats mid-column with its baseline matching nothing on the left (`inner-dark-empty.png`, `inner-dark-one.png`). `cover-dark-many-end.png` has a ~150 px empty band between "+ Add for today · Enter for the next" and "1 done today ›".

17. **Brief's "Got it" button cut by the bottom edge, label left-aligned** (low): in `brief/cover-dark-many-foot.png` the full-width gold pill runs into the screen bottom, and "Got it" sits at the left rather than centred.

18. **Brief's Topics chips run off the edge** (low; B item, still there): Barça / AI / Top stories / World, then a sliver of a 5th chip at the right edge (`brief/cover-dark-topics.png`).

19. **Light theme contrast** (low): white stat tiles, habit chips and the Setup card on the off-white page have almost no edge. Card fills are mixed: the tiles, fasting card and Setup card are white, while Up next and This evening are grey (`today/cover-light-many.png`, `cover-light-fasting.png`, `inner-light-many.png`). The brief's Short / Full chips are faint (`brief/cover-light-many.png`).

20. **Bedside stand layout looks unfinished** (low):
    - In `stand-dark-day.png` the analog clock's rim nearly touches the "‹ Back" row (~8 px).
    - About 220 px of empty space sits between the clock and "Alarm…".
    - Two BBC tiles fill only the left ~70%, with a bare "BBC" text placeholder for an image.
    - `stand-dark-day-ring.png` has no hour labels on the 24-h ring, so the coloured blocks can't be read as times, and "3 h 30 free · 2 to do" has no explanation. The work day (09:00–17:30) isn't drawn on the ring.
    - The "45 min until Dentist" card is full-width with small left-aligned text.

21. **Small targets** (low): "Make it a task", "Later", "Topics"/"Done" and "1 earlier ›" are bare text links, visibly under 48 dp tall (`cover-dark-schedule.png`, `brief/cover-dark-many-foot.png`).

#### Last week's items in this area
- A1 Today under the capture bar: **partly fixed**. The cover's last Anytime row and the cover fasting Up next buttons are now clear. The open Fold "many" timeline is fully visible. Still there in `today/inner-dark-schedule.png` (timeline cut at 17:15) and `inner-dark-one.png` ("Plan today's tasks" under the bar). The inner fasting shots now have a worse overlap instead (finding 1).
- A8 counts between screens (Today/brief/bedside parts): **still there**. Setup is "12 steps left" (and 11 in one shot). The brief's "4 tasks" vs Today's 1 of 5 / 4 left / "5 left". The Focus task's name and fixtures differ across Brief/Today/Bedside (finding 8). Habits 1 of 2 vs Ask: not visible in these shots.
- A18 light contrast, fasting rings: **still there (faint)**. The remaining track of the ring in `cover-light-fasting.png` is pale grey on white. Bedside BBC tiles in light: not visible (no light bedside news shot; the dark tiles' "BBC" placeholder is dim).
- A19 bedside "No alarm set" at 07:40 vs 06:30: **fixed**. Day and evening both say "Alarm 06:30" with correct countdowns ("in 17 h 30" at 13:00, "in 10 h" at 20:30). "No alarm set" appears only in `stand-dark-empty.png`, where it is expected.
- B Brief's Topics running off the edge / half-empty open-Fold Today and Brief: still there (`brief/cover-dark-topics.png`, `brief/inner-dark-many.png`, `today/inner-dark-schedule.png`).

### g3-todayb
Read 27 of 27 shots. (All read. Checked with md5: talk `cover-dark-by-hand` = `cover-dark`; speak-up `cover-dark-by-hand` = `-empty` = `-many` = `-one`, all byte-identical.)

#### Findings (ranked, most severe first)
1. **Tomorrow's first thing differs between Bedside and Tomorrow** (high; likely fixture, but it's what a user sees): the Bedside evening/quiet shots say "TOMORROW · THU 22 OCT / First thing 09:30 Parents' evening at Oakfield…", while Tomorrow for the same Thu 22 Oct says "Tomorrow starts at 07:40 · Gym before work" and lists no Parents' evening. Week ahead's THU 22 OCT has only "Office 09:00–15:30", so it doesn't show it either. Separately, an event called "Parents' *evening*" sits at 09:30 inside "Work 09:00–15:30". (`bedside/stand-dark-evening.png`, `stand-dark-quiet.png`, `stand-light-evening.png`, `tomorrow/fold-*.png`, `week-ahead/cover-dark-one.png`)
2. **Bedside morning brief contradicts itself and Week ahead** (high; likely fixture): at 07:40 on Wed 21 Oct it says "4 events · 2 tasks · first at 10:00" right above "Work 09:00–17:30", so work starts before the "first" event. Week ahead's WED 21 OCT shows the office plus one event, "18:30 Swimming lesson", not 4 events with one at 10:00. (`bedside/stand-dark-morning.png`, `week-ahead/cover-dark-one.png`)
3. **Gym leaves when work leaves** (med, real-looking): Tomorrow puts "Gym before work · 07:40" alongside "Work 09:00–15:30 · leave 07:40 · home about 16:50". The gym and leaving for work are at the same minute, so "before work" can't happen. (`tomorrow/fold-*.png`; same "leave 07:40" on the Bedside evening shots)
4. **Speaking up: four states, one image** (high, tests): `cover-dark-empty`, `-one`, `-many` and `-by-hand` are byte-identical. Every rule shows "On" with "Turn off", so "empty" and "one" don't show their states. (`speak-up/cover-dark-*.png`)
5. **Talk by-hand = default** (med, tests): `talk/cover-dark-by-hand.png` is byte-identical to `talk/cover-dark.png`.
6. **Edit sheet names a different task** (med; A7 left over): the list row "Gym before work" opens a sheet titled "Gym, legs day", and the banner over the sheet now reads "first 07:40 Gym, legs day". The edit shot's banner also says "1 task", while the unmoved list it opens from says "2 tasks". (`tomorrow/fold-dark-edit.png`, `fold-light-edit.png` vs `fold-dark.png`)
7. **News: "6 sources" while the text names ten** (med): the explainer lists Google News, BBC Sport, Barca Universal, Football España, The Verge, TechCrunch, MIT Technology Review, OpenAI, Hacker News and BBC News, but the line under the chips says "6 sources · all on". The source chip is cut to "Football E" at the right edge. "Spanish sources Off / On" sits above "Off · English only", so the state is said twice. (`news/cover-dark-topics.png`)
8. **Tomorrow's header says things two or three times** (med): the title "Tomorrow", then "TOMORROW · THU 22 OCT", then "Tomorrow starts at 07:40 · Gym before work", then "2 tasks · first at 07:40". The banner above repeats all of it ("2 tasks · 1 bill due · first 07:40 Gym before work"). The four header lines use three different sizes with tight leading. On the edit sheet, "07:40" under the title repeats "When: Tomorrow · 07:40". (`tomorrow/fold-*.png`)
9. **Watch "many" shot carries an error state, in accent gold** (med): "That code doesn't match a watch waiting / to link · check the watch and type it again" sits under "1234 5678", while the header says "2 watches are linked". The error is in the link gold, not an error colour. It wraps ~160 px short of the field's right edge and sits ~16 px tighter to the first card than the cards sit to each other. (`watch/cover-*-many.png`)
10. **Bedside news never ages** (low; fixture): "BBC News · 40 min ago" and "1 h ago" show the same two headlines at 07:40, 13:00, 20:30 and 23:30. The tiles also fill only ~70% of the width and leave the right third empty. (`bedside/stand-*.png`)
11. **Lines ending on a lone word or number** (med): Tomorrow's banner breaks "· 2 tasks · 1 / bill due" (`tomorrow/fold-dark.png`, `fold-light.png`). Week ahead breaks "family 0 h / of 8 h" and "family 45 min / of 8 h". Talk has "Say nothing for 6 / seconds", "never / starts listening", "“Listening / for “Hey Obi””." and "never / in your pocket", all wrapping ~150 px early. Speaking up wraps "someone / on Always notify" ~140 px early. (`week-ahead/cover-dark-*.png`, `talk/cover-*.png`, `speak-up/*.png`)
12. **Broken quotes in Talk** (low): "While it listens the orb shows “Listening for “Hey Obi””." has nested double quotes with two closing marks together. (`talk/cover-*.png`)
13. **Week ahead's event block is oversized and oddly worded** (low): "Swimming lesson · Oakfield Leisure Centre · Before you're home · about 18:50" takes three lines of large gold type, bigger than the day headings and office rows. "Before you're home" next to an 18:30 start reads garbled. FAMILY SYNC then repeats the same event. (`week-ahead/cover-dark-one.png`)
14. **Small targets** (med): Tomorrow's Today / Day after / Edit pills are ~31 dp tall and its circles ~27 dp. Week ahead's time chips and On pill are ~32 dp. Watch's Unlink pills are ~33 dp. Talk's Turn on/off and Speaking up's Turn off pills are ~32–33 dp. Train queue's Done / Remove / Back and Watch's "Link" are bare words. (`tomorrow/fold-*.png`, `week-ahead/*`, `watch/*`, `talk/*`, `speak-up/*`, `train-queue/*`)
15. **Week ahead's empty shot isn't empty, and its chips leave a widow** (low): "empty" shows 3 office days, with "otherwise quiet" as its state. The 16:00–19:00 chips fill one row and "20:00" sits alone on a second. "On · Sundays at 18:00…" stands above a lone "On" pill with no Off, so On is said twice. (`week-ahead/cover-dark-empty.png`)
16. **Alignment drifts** (low): in Tomorrow, "Ring the school about the trip" sits ~23 px below its circle's centre, while Gym's title lines up with its circle. On the edit sheet, "Start from template" starts ~18 px right of every other left edge. "Close" sits at y≈341 in light but 360 in dark. (`tomorrow/fold-*.png`, `fold-light-edit.png` vs `fold-dark-edit.png`)
17. **Tomorrow banner has a big gap below it** (low): it now sits ~32 dp from the top, but ~70 dp of empty space separates it from "‹ Today" / "Close". (`tomorrow/fold-*.png`)
18. **Light contrast** (low): Bedside's BBC tiles are white cards on an off-white page with no visible edge. Train queue's and Watch's white cards, and Tomorrow's and Talk's pale pills, barely separate from the background. (`bedside/stand-light-*.png`, `train-queue/cover-light-many.png`, `watch/cover-light-many.png`, `tomorrow/fold-light*.png`, `talk/cover-light.png`, `speak-up/cover-light-many.png`)
19. **Train queue footer** (low): "On Wi-Fi the Fold keeps each waiting article's words (up to 30)" reads as 30 words. "1 min read" for "Why the trains run late" looks like a fixture. (`train-queue/cover-*-many.png`)

#### Last week's items in this area
- A7 Tomorrow's banner keeps "2 tasks" after a move: fixed (moved shots say "1 task"). "Gym before work" opening "Gym, legs day": still there (`tomorrow/fold-*-edit.png`).
- A8 Brief/Bedside/Tomorrow fixtures disagreeing: still there (findings 1–2).
- A13 Week ahead chips ~31 dp: still there (~32 dp, `week-ahead/cover-dark-empty.png`).
- A14 Week ahead "0 h of / 8 h": still there ("0 h / of 8 h", "45 min / of 8 h"). "leads", "Fri · 5 events" and "1 h 45" are not visible in these shots.
- A18 Bedside BBC tiles and meta in light: still there (`bedside/stand-light-*.png`).
- A19 Bedside "No alarm set" at 07:40: fixed ("Alarm 06:30 · in 22 h 50").
- A19 Tomorrow's banner against the top edge: fixed (~32 dp down), but see finding 17.
- From 2 Nov, Tomorrow's edit time and light Repeat row: fixed (time shown, light "Doesn't repeat" readable).

### g4-plana
Read 42 of 42 shots. (All read; byte-check: `calendar/week-by-hand.png` and `calendar/week-inner-dark-many.png` are identical.)

#### Findings (ranked, most severe first)
1. **The week's total is different folded and open** (high; real-looking): folded Board header says "Week 43 · 2h", while the open Board, with the same tasks, says "Week 43 · 2h 30m planned". The day tiles add up to 2h 30m (W 1h + F 1h 30m). So the folded figure is wrong, or it is "2h 30m" cut short with no ellipsis (`day-list/board-folded-*.png` vs `board-open-*.png`).
2. **"Fit times from 19:30" when the list already starts at 19:10** (med; real-looking): every Wed/Today shot schedules tasks at 19:10 / 19:25 / 19:40, but the chip under them offers "Fit times from 19:30" (`day-list/fold-*.png`, `fold-*-added.png`, `fold-dark-moved.png`, `board-folded-*.png` non-Friday). On Friday the chip says "Fit times from 09:00" for a task already fixed at 21:00.
3. **A timed task in the all-day row** (med; real-looking): "12:00 Pay the window cl…" sits in Thursday's "all day" strip instead of at 12:00 on the grid. In Agenda it is listed at 12:00 (`calendar/week-inner-*-many.png`, `week-by-hand.png`, `week-cover-dark-many.png`).
4. **An empty day says "nothing" three times, plus "0 of 0"** (med): an empty column shows the "Nothing yet" pill, then "0 of 0 done" under an empty progress bar, then "Nothing planned yet. Type a task…", and then "Drag a task's ≡ to another day" with no task to drag (`day-list/board-open-*`: Thu 22; `board-open-*-two.png`: Sat 24 and Sun 25).
5. **The undo snackbar covers the input** (med; real): "Moved "Call the school about Friday" down · Undo" sits on top of the New task field. The "New task" placeholder shows through under it and the + button is half hidden (`day-list/fold-dark-moved.png`).
6. **The third chip is cut at each column's edge** (med): under the Wed and Fri columns the row "Say them · Paste a list · F" ends on a clipped "F" (Fit times…). The Thu column has no third chip at all (`day-list/board-open-*.png`, `-drag`, `-sheet`).
7. **"by-hand" week shot is the many shot** (med; test): `calendar/week-by-hand.png` is byte-identical to `week-inner-dark-many.png`, so the by-hand state of Week is unread. The Agenda by-hand shows a different set (9 events, Parents' evening hidden, Fri "4 events"), so the two should differ.
8. **Fixture dates disagree between Study and Board/Calendar** (med; fixture, A8): Board and Calendar make today Wed 21 Oct. Study's Plan for chips read "Today · Tomorrow · Wed 28 Oct", which makes today Mon 26 Oct (`study/cover-dark-lesson-planned.png`, `-lesson-sheet.png`).
9. **Calendars vs Calendar** (med; fixture): Calendar's legend shows "Kids" and "Personal" with Kids events (Swimming lesson, Parents' evening, Inset day), but Calendars-many lists only Personal (Google, "3 events"), Outlook (expired) and Bank holidays, with no Kids calendar (`calendars/cover-*-many.png` vs `calendar/cover-*-many.png`).
10. **"Parents' evening" at 09:30–10:00** (low-med; fixture): an "evening" in the morning, flagged "Clashes with work until 15:30" (`calendar/cover-*-many.png`, week shots).
11. **Clash notes that don't follow the same rule** (low-med; real if confirmed): Call with Priya 17:00 gets "Clashes with work until 17:30", but Dentist 13:45–14:15 (also Personal) and Quarterly planning 10:00–11:30, both inside Work 09:00–17:30, get none (`calendar/*-many.png`). In Week every event has an orange dot and orange border, and orange is not in the Kids/Personal legend (`calendar/week-*.png`).
12. **"Booked this week 5 h" doesn't add up** (low-med; real if confirmed): the visible timed events are 1h30 + 30m + 45m + 45m + 30m = 4h (`calendar/week-inner-*-many.png`, cover "booked 5 h").
13. **The now line runs through Call with Priya** (low): the knob and line cross the event's title on Week ("Call with Priya", and on the cover "Call with P…" with its first letters hidden) (`calendar/week-*.png`). In Agenda the 17:15 now line sits after the 17:00–17:45 event rather than inside it.
14. **Lines ending on a lone word** (low, A14's rule): "Quarterly planning with the / design and engineering / leads" (cover Agenda); "Tap a task to move it to another day · hold its ≡ to / reorder" (`board-folded-*`); "Add Data analytics · 2 modules · 5 / lessons?" (`study/cover-dark-import.png`); placeholder "Add lessons · paste the course / outline" (`study/cover-dark-add-lessons.png`); "Off · the weather is home's / and work's" (`calendars/cover-dark-empty.png`). Chips left alone on a row: "No estimate" (`fold-*-sheet.png`), "21 Dec · 8 wks" (`study/cover-dark-course.png`), "Select several".
15. **Hints that name a ≡ the row doesn't have** (low): Friday's only task, "Study module 6", has no ≡ handle but the hint says "hold its ≡ to reorder" / "Drag a task's ≡ to another day". The done "Book the MOT" also has no handle, so its 15m pill sits ~115 px right of the others in the open Board (`board-folded-*-friday.png`, `board-open-*.png`).
16. **"1h 30m" fills the Friday day tile edge to edge** (low): in the folded strip the "1h 30m" under F 23 touches both sides of its tile (`board-folded-*.png`).
17. **Two-day view after a move** (low): the toast says the task moved "to Tomorrow" while the view shows Sat/Sun, so the result can't be seen. Its columns say "Saturday 24" where the 3-day view says "Thu 22" (`board-open-*-two.png`).
18. **Inner Agenda is the cover layout stretched** (low): on the open Fold the work/commute lines sit ~1,000 px right of the day names with nothing between. The week strip's selected pill is ~250 px wide. "Tomorrow Thursday 22 October" sits beside "Fri 23 Oct" (`calendar/inner-*.png`).
19. **Study's left edges** (low): "+ Add lesson" starts at ~92 px, "+ Add module" at ~55 px and "+ From a screenshot" at ~37 px. The status line "Planned "Pivot tables" for tomorrow" is squeezed between LESSONS and the first card. The editor ends "Mark done · Delete" as pills but "Done" as bare gold text (`study/cover-dark-add-lessons.png`, `-lesson-planned.png`, `-lesson-sheet.png`). "lesson-sheet" is an inline panel, not a sheet.
20. **Small targets** (low, A13): bare "˅"/"^" chevrons on Study module cards, Fasting's bare "›", Calendars' "Allow", "Reconnect" and "Stop editing" as plain text, and the dim disabled "‹" on the Board (`study/*`, `calendars/*`, `day-list/board-*`).
21. **Light theme edges** (low): the Board's white day tiles and progress track barely show on the off-white page. The sheet scrim hardly dims the light page, so the sheet's edge is faint (`board-folded-light*.png`, `board-open-light-sheet.png`, `fold-light-sheet.png`). The disabled "‹" is nearly invisible in light.
22. **Smaller ones** (low): "‹ Today" back link above a page titled "Today"; hint ""#work" work in the text"; the selected 45 min chip is filled gold, while selected chips on the Board sheet are outlined; "1 h" vs "1h" vs "1h 30m" formats across Study and Board; the Goals import card's Add (~125×108) and Cancel buttons differ in height; Calendars' Allow card has ~60 px of empty space under Allow.

#### Last week's items in this area
- A6 Board on Saturday: not visible in these shots. The A6 shots (`board/inner-*`) aren't in this group. The Sat/Sun board here (`board-open-*-two.png`) shows no "over" nudge or cut chips.
- A8 counts across screens (Brief/Calendar/Board dates): partly. Board and Calendar agree on Wed 21 Oct, but Study's chips put today at Mon 26 Oct (still there, `study/cover-dark-lesson-planned.png`).
- A9 Study plan's figures: not visible. No finish date is picked in any Study shot ("Pick a finish date to see how many sessions a week it takes.").
- A13 bare "^"/"˅" glyphs on Study: still there (`study/cover-dark-course.png`, `-add-lessons.png`).
- A14 Calendars "→": fixed (arrows now sit mid-line). Calendars "work's": still there ("home's / and work's", `calendars/cover-dark-empty.png`). Study's lone "1": not visible.
- A20 shots that don't show their state: a new identical pair here, `calendar/week-by-hand.png` = `week-inner-dark-many.png`.

### g4-planb
Read 40 of 40 shots. (None unread. No byte-identical pairs among the 40.)

#### Findings (ranked, most severe first)
1. **Wins count says one thing, Wins section the opposite** (high; real-looking): Review's reflect line reads "9 done · 1 of 2 habits met · **2 wins** · 2 still open", while the WINS section a little further down the same screen says "**Nothing yet.** A line or two on Friday is enough". The saved week has the same problem: "1 done · **1 win** · 1 still open" above "WINS · **No wins kept that week.** Add one now and it's dated that Friday." (`review/cover-dark-many-reflect.png` with `cover-dark-many-journal.png`; `review/cover-dark-one-reflect.png`, `cover-light-one-reflect.png`).
2. **Goals' habits and the Habits screen are different people's habits** (high; likely a test fixture): Goals lists Read 20 pages / Run / Stretch under HABITS with "1 habit to do today". Its "Open Habits ›" leads to a screen with 8 different habits (Brush teeth, Drink water, No biscuits, No shop coffee, Walk 20 minutes, Floss…) and "3 of 8 done today". None of the names match (`goals/*-many.png` vs `habits/*-full.png`). "Today" also differs between sibling areas: Habits is Thu 12, Fasting is Wed with Mon 19 → Tue 20 history, Study's plan sheet has Tomorrow then "Wed 28 Oct" (so today is Mon 26 Oct), and Review's last saved week is Sun 18 Oct.
3. **Course import disagrees with the course** (high; likely a fixture): the import card says "Add Data analytics · 2 modules · 5 lessons?", but the course pages show "Data analytics certificate", 3 modules and "3 of 8 lessons". The card's line also ends on a lone "5", with "lessons?" on the next line (`study/cover-light-import.png` vs `study/cover-*-course.png`, `*-plan.png`).
4. **"1 habit to do today" over two open habits** (med; real-looking): in the many state, Read 20 pages ("4 left · 5 days to go") and Run ("0 of 3 this week") both have empty circles, and Stretch is done. The header still says "1 habit to do today", the same as the one state, so the header doesn't show which of the two it means (`goals/cover-dark-many.png`, `cover-light-many.png`, `inner-dark-many.png`, `inner-light-many.png` vs `*-one.png`).
5. **"reflect" and "reflect-form" show the same state** (med; test): both shots show the open form with the same "What went well? / A line or two…" fields and the same suggestion chips. The form shot is only the same screen scrolled further, so no shot shows a closed or prompt-only Reflect card (`review/cover-dark-many-reflect.png`, `cover-dark-many-reflect-form.png`).
6. **Lesson plan sheet overlaps the list and its options float** (med; real-looking): the "2 lessons selected / Plan for…" sheet rises over the "3 · Putting it together 0 of 2" card and covers its bottom edge. Its options (Today, Tomorrow, Wed 28 Oct, Thu 29 Oct, Fri 30 Oct, Sat 31 Oct, Sun 1 Nov, Pick a day…, Not planned) are bare text with no chip or row background. They sit 3 in the first row and 2 in the others, and their left edges drift (Tomorrow ~281, Fri 30 Oct ~346, Sun 1 Nov ~339, Not planned ~355 px). The selected lessons' gold ticks look exactly like "done" ticks, while module 2 reads "0 of 3" (`study/cover-dark-lessons-selected.png`).
7. **Habits filter chip cut at the edge** (med; real): on the cover the chip row ends with a sliver of a 5th chip at the right edge (the inner shot shows it is "+ New list"). The row also appears in the empty state, where there is nothing to filter (`habits/cover-dark-empty.png`, `cover-dark-full.png`, `cover-dark-open.png`, `cover-light-full.png`; compare `inner-dark-full.png`).
8. **Study plan's sums don't quite fit** (med; real-looking): "about 4½ h left" in 5 lessons (Pivot tables alone is "1 h"), yet the plan says "2 sessions a week" at 45 min (1 h 30 a week) and "5 lessons suggested for your next 5 sessions". Five 45-minute sessions give 3¾ h, not 4½ h. In the this-week shot, "4 lessons suggested for your next 4 sessions · Mon, Wed, Mon 2 Nov, …" appears under "Pick a finish date to see how many sessions a week it takes." (no date picked) (`study/cover-dark-plan.png`, `cover-light-plan.png`, `cover-dark-this-week.png`).
9. **Study wraps that leave a lone figure or split a phrase** (med): "1 h 30 a week · 5 / lessons left" (lone "5"); "Next: Pivot tables · Module 2: / Core skills"; "Next: Welcome and / setup · Module 1: Foundations"; and in THIS WEEK, "Welcome and / setup · 30 min" with "Evening" jammed underneath and the row cut at the bottom of the shot (`study/cover-*-plan.png`, `cover-light-course.png`, `cover-dark-this-week.png`).
10. **Streak dots don't count today** (med; real-looking): Brush teeth and No shop coffee say "7 day streak" and are ticked today, but their dot rows show 6 green dots and a 7th grey ringed dot. Drink water has "1 day streak", is ticked, and shows no green dot at all. The day strip's mini-bars are the same length on Fri–Wed, even though two 7-day streaks run through those days (likely a fixture) (`habits/cover-dark-full.png`, `cover-light-full.png`, `inner-dark-full.png`).
11. **Habit detail's option rows have no labels** (med): below "Reminder · Off / Every day", two more chip rows, "Do / Avoid" and "Morning / All day / Night time", have no headings. "Done 7 of 7 days since you / added it" wraps early beside "Best run 7 days" (`habits/cover-dark-open.png`).
12. **Journal: an answer without its question, cramped labels** (med): the week of 5 Oct shows "What went well?" then "Shipped the release notes a day early" and "Three runs". The second answer has no "What to drop?" label. Each question label sits ~8 px under the previous answer, so the groups run together. The open list shows a tiny down "˅" glyph at the far right (x≈736) while open, closed shows "›" (`review/cover-dark-many-journal-open.png`, `cover-dark-many-journal.png`).
13. **Open Fold: Goals stretches edge to edge while its siblings are centred** (med): Goals' inner shots run text fields, the fasting card and a single-line "Add Gym" paragraph across the full ~1920 px. The fasting card's "›" sits ~1700 px from its text. Study, Habits and Fasting on the inner screen use a centred column of ~1370 px (`goals/inner-*.png` vs `study/inner-dark-course.png`, `habits/inner-dark-full.png`, `fasting/inner-*.png`).
14. **Fasting start time vs its own 16:8 schedule** (low; fixture): Goals says "16:8 · eating 12:00–20:00 · Next fast from 20:00", but the running fast "Started Wed 03:45". Separately, the many state (fasting) and Goals (not fasting) never agree on the same "now" (`fasting/*-many.png`, `goals/*.png`).
15. **Light contrast** (low): the Goals fasting card's empty ring is near-white on white. The goal ring "0" track around Learn Spanish and Stretch's 7 week dots (except today's) are almost invisible. Habits' unfilled streak dots (Drink water, Walk 20 minutes, Floss) are very faint. Review's "Remove" and the faint "What went well?" labels are low contrast in dark too (`study/cover-light-import.png`, `goals/cover-light-many.png`, `goals/inner-light-many.png`, `habits/cover-light-full.png`, `review/cover-dark-many-journal-open.png`).
16. **Fasting ring's three unlabelled dots** (low): every ring has dots at 3, 6 and 9 o'clock. On a 16 h goal those mark 4 h, 8 h and 12 h, but there is no stage boundary at 8 h (the stages are 0–4, 4–12, 12–16). They read as decoration or as wrong stage marks (`fasting/cover-dark-empty.png`, `inner-dark-empty.png`, `*-many.png`).
17. **Smaller ones** (low): fasting history's durations drift left on the short row ("9h 30m" starts ~17 px left of "16h 05m" because "6½h short" is wider than "on goal"). In light, today's in-progress week ring is dark brown while the done days are gold. Review's "Save" uses a larger font than "Skip this week" beside it. "Two minutes: what went well, what / to drop, …" breaks early. There is a ~130 px dead gap between "Edit" and "Comes on Sundays / at 18:00 · through Monday", and that line breaks mid-phrase. Study's module rows put "Done", a "˅" glyph and a "≡" handle within ~100 px, which makes three small targets. Goals' empty-state intro ("Add a habit and Navilife keeps count…") sits above FASTING, not under HABITS. The large ~110 px gap between HABITS and the New habit field is off-rhythm (`fasting/*-many-below.png`, `review/*.png`, `study/cover-light-course.png`, `goals/cover-*-empty.png`).

#### Last week's items in this area
- 9 Study plan's figures: partly fixed. The "Fits" sentence is now readable ("Fits: the 2 times you picked give 4 sessions a week; you need 2."), and 2 × 45 min now says "1 h 30 a week". Still there: the suggestion run "Mon, Wed, Mon 2 Nov, …" and the THIS WEEK title wrapping ("Welcome and / setup · 30 min") instead of ellipsising (`study/cover-dark-this-week.png`). The 5 sessions × 45 min vs 4½ h gap is new (finding 8).
- 13 Small targets, Study's bare "^"/"˅" glyphs: still there (`study/cover-light-course.png`, `cover-dark-lessons-selected.png`; module rows now also add a "≡" handle beside them).
- 14 Study's lone "1": the old one is gone, but a lone "5" now ends a line ("1 h 30 a week · 5 / lessons left", `study/cover-*-plan.png`).
- 18 Light contrast, Fasting's future rings/knob/stage dots: fixed on the Fasting screen (ring track, hollow knob, white stage dots and Thu/Fri week rings all visible in `fasting/cover-light-many.png`, `inner-light-*.png`). Still faint: the small fasting ring on Goals' light card.
- 19 habit line's em dash: not visible in these shots (no em dash on any habit line).
- 20 fasting one = many above the fold: not visible (no above-the-fold "one" shot in this set). The below-the-fold one and many shots now differ correctly (one: only Wed ring and empty history; many: 4 fasts and history).
- 8 habits "1 of 2" vs "0 of 1" (Today/Ask): not visible here. A new sibling mismatch exists between Goals' habits and the Habits screen (finding 2).
- From 2 Nov, Fasting's "6½h short": still correct (9h 30m against a 16 h goal).

### g5-aska
Read 45 of 45 shots. (All read. Byte-identical by md5: `lists/cover-dark-one` = `cover-dark-promises-by-hand`; `lists/cover-dark-shopping` = `cover-dark-shopping-by-hand`; `settings/cover-dark-appearance` = `cover-dark-connected` = `cover-dark-settings-by-hand`; `setup/cover-dark-one` = `cover-dark-by-hand`.)

#### Findings (ranked, most severe first)
1. **Ask's Daily and Settings by-hand shots are taken mid-crossfade** (high; test timing, but it is the only view of those tabs): the Life tiles ("Lists", "Goals and habits", "0 of 1 habit done today", "People", "Dinners", "Countdown…") show through behind the Daily list, and the list itself is dimmed (`ask/cover-dark-daily-by-hand.png`). On Settings the Daily rows ("Obi is working on / Watching 3 things", "Routines", "Train queue", "Reading", "Voice notes") show through, and the Settings card is cut off mid-line at "Share the shopping list with your partner", with a stray "Obi" heading floating below the card (`ask/cover-dark-settings-by-hand.png`).
2. **"Nothing set up yet" above tiles that have data** (high, real): Ask's "one" state says "Nothing set up yet · pick one to start; each takes a minute" over Lists "1 to buy" and Countdowns "Christmas in 65 days" (`ask/cover-dark-one.png`, `ask/inner-dark-one.png`). The empty state also shows Countdowns "Christmas in 65 days" under that same line (`ask/cover-dark-empty.png`, `inner-dark-empty.png`).
3. **Shots that claim one state and show another** (high, tests):
   - Four groups of byte-identical shots (see header). The three Settings shots show only the top of Settings, so neither an Appearance section nor a connected state is in view.
   - `settings/cover-dark-connected.png` and `cover-light-connected.png` say "Navilife's AI · Ask works once this device is connected", which is the not-connected state.
   - `setup/cover-dark-one.png` and `inner-dark-one.png` show "14 steps left · 4 done · 6 come later", which is not one of anything.
   - `setup/cover-dark-empty.png` shows "5 steps left · 3 come later · 17 couldn't be checked" with every row "Couldn't check". That is a failed check, not an empty setup.
4. **Counts that disagree across sibling shots** (med; mostly fixtures):
   - Ask's "one" shows Lists "1 to buy", but Lists' "one" shows "Nothing to buy" (`ask/cover-dark-one.png` vs `lists/cover-dark-one.png`).
   - Ask's "many" shows "3 to buy", but Lists' "many" shows "5 to buy" and Shopping 5 (`ask/cover-dark-many.png`, `inner-dark-many.png` vs `lists/cover-dark-many.png`).
   - Ask's by-hand shots show "7 to buy" and "0 of 1 habit done today", but Ask many shows "1 of 2 habits done today" (`ask/cover-dark-tiles-by-hand.png`, `cover-dark-field-by-hand.png`).
   - Health many shows Call assistant "Navilife isn't screening calls on this phone · see Spam calls", but Setup many shows Call screening "Navilife screens calls on this phone" and Call assistant "On" (`health/cover-dark-many.png` vs `setup/cover-dark-many.png`). Health working and not-connected also show Call assistant "Off".
5. **Settings' Navilife's AI block is unfinished** (med, real):
   - "Ask works once this device is connected" is followed by "Until this device is connected", which says the same thing twice.
   - The fallback list below runs as plain text with no dividers and lower-case sub-lines: "Ask and Talk / adding a task or shopping, your fast, money questions, help and Search still answer on this device", "Morning brief / read from your day as it is, in Navilife's own words", "Drafted replies / nothing is drafted · write your reply…".

   Shots: `settings/cover-dark.png`, and the bottom edge of the other cover settings shots.
6. **Settings' "You and your day" changes its row count** (med): `settings/cover-dark.png` shows 3 rows (About you, Work mode, Protected time). Every other Settings shot shows 6 (plus Calendars, Mail, Family).
7. **Renewals' summary tiles are uneven** (med): on the cover, "NEXT 30 / DAYS" and "TO / CANCEL" wrap to two lines, so those tiles are taller than "A YEAR £552". "Car / insurance" wraps although the title column has room (`lists/cover-dark-renewals.png`).
8. **Said twice** (med):
   - Boiler service's sub-line is "Boiler service" (`lists/cover-dark-renewals.png`, `inner-dark-renewals.png`).
   - Shopping's header "5 to buy" sits directly under the "5 to buy" tile (`lists/*shopping*.png`).
   - Settings' "Until this device is connected" (see item 5).
9. **Lines ending on a lone word or number** (med):
   - Setup's status line wraps to "…6 come later · 1 / couldn't be checked" and "…3 come later · 1 / couldn't be checked" (`setup/cover-*`).
   - Ask: "each takes a / minute" (`ask/cover-dark-empty.png`, `cover-dark-one.png`); "Christmas in 65 / days"; People "waiting for / 1 · +1".
   - Health: Instant updates ends "· last push 16:55 · sent", with a bare "sent" (all health shots).
   - Settings: Speaking up wraps early, "When Obi says something out loud / on his own", with room left for "on" (cover settings shots).
10. **Lists' title runs into its info icon** (low): the (i) circle touches the "s" of "Lists" on every Lists shot, cover and open (`lists/*.png`).
11. **Light theme: white cards on an off-white page with no border** (low): Setup's cards, Health's cards, and the Lists tab row (pill and selected tab barely visible) (`setup/cover-light-one.png`, `health/cover-light-many.png`, `lists/cover-light-many.png`, `lists/inner-light-many.png`). Ask's light tiles now have borders; these screens don't.
12. **Small targets** (low):
   - "Close" is bare text at the top left of Settings, Setup and Health.
   - Health and Settings use bare "›" chevrons.
   - Shopping's "Remove" is bare grey text.
   - Lists' cover tab pills are about 40 dp tall (`lists/cover-*`).
13. **Smaller ones** (low):
   - Health's "Outlook sign-in / expired · not updating / since Mon 19 Oct 17:15" wraps to three narrow lines on the cover beside Reconnect.
   - Spam calls' sub-line starts lower-case ("can ring · …").
   - The Lisbon refund row is the only waiting-for row without a "who" ("chase Wed 28 Oct").
   - The open Fold Lists and Settings lists are one column across 829 dp, with the chevrons ~1,800 px from their labels (`settings/inner-dark-connected.png`).
   - Ask's search page leaves a ~180 px gap between "See all 6 matches" and the tab row (`ask/cover-dark-field-by-hand.png`).
   - Lists' open Fold renewals stacks two segmented rows (tabs, then Coming up / All / Done).

#### Last week's items in this area
- A8 setup "12 steps left" vs "TO SET UP · 10": changed. Setup now says 14 left / 21 done, and Ask says "PLACES TO SET UP · 10/13". These may count different things; not comparable from these shots.
- A8 habits "1 of 2" vs "0 of 1": still there, within Ask's own shots (`ask/cover-dark-many.png` vs `cover-dark-tiles-by-hand.png`).
- A8 / A-tail Health's Call assistant Off vs Setup On: still there (`health/cover-dark-working.png`, `cover-dark-many.png` vs `setup/cover-dark-many.png`).
- A14 Ask People "waiting for / 1 · +1": still there (`ask/cover-*-many.png`, by-hand).
- A14 Setup "· 1 /": still there ("· 1 / couldn't be checked", `setup/cover-*`).
- A16 Lists' cover tab row (Decisions smaller, Shopping on the edge, counts dropped, rows inset): fixed. Counts show, fonts match, and rows line up with headings.
- A23 setup empty fully set up: changed but still not an empty state (now 17 "Couldn't check", `setup/cover-dark-empty.png`).
- A27 Ask's search results stepping right: fixed (`ask/cover-dark-field-by-hand.png`).
- A28 Health "can't see notifications ·": fixed (now "notifications · messages / aren't captured").
- A29 Navilife's number "takes messages for you" repeating its label: fixed (single "Takes messages for you" under "Navilife's number").
- A31 Ask's light tiles: fixed (bordered, `ask/cover-light-many.png`, `inner-light-many.png`).
- A32 Health "Checking now" beside "5 things need a look": fixed (now "Checked 17:15").
- A32 Setup "All set for now" with "1 couldn't be checked": fixed (now "No steps left · 21 done · 3 come later · 1 couldn't be checked").
- A32 bare "›" glyphs on Health: still there.
- A32 Face On on Voice vs off on Health: not visible in these shots (Health shows "Face · off"; Voice is not in this group).

### g5-askb
Read 46 of 46 shots. (None unread.)

#### Findings (ranked, most severe first)

1. **"What Navilife may do" header contradicts its own rows** (high; real-looking): the empty state says "Navilife asks before everything it does for you" while "Add what you ask for" has a lit gold dot and says "are added at once", and Inbox autopilot has a lit dot and "Tidies new mail as it arrives… each with Undo". The counts don't match the dots either: the one/by-hand shots say "Navilife does 1 thing on its own" over two lit rows (Add what you ask for, Add a task); many says "3 things" over three lit list rows plus Inbox autopilot below. Also, "Pause everything Navilife does on its own" is offered when the header says nothing runs on its own. (`autonomy/cover-dark-empty.png`, `cover-dark-one.png`, `cover-dark-by-hand.png`, `cover-dark-many.png`, `cover-light-many.png`)
2. **Byte-identical state pairs** (high; test fixture): `autonomy/cover-dark-by-hand.png` = `autonomy/cover-dark-one.png`, and `work/inner-dark-by-hand.png` = `work/inner-dark-empty.png` (same md5). So the by-hand states were never captured.
3. **Work empty is fully set up** (high; test fixture): `work/cover-dark-empty.png` and `inner-dark-empty.png` show "At work until 17:30", "Stop work now", Mon–Fri lit, Thu "09:00–15:30 · own hours", commute "About 1 h 20 each way" with a chip lit. Apart from the commute line it matches `cover-dark-one.png`.
4. **Recovery "many" shows no "many"** (med; test fixture): it is a single "Used Mon 19 Oct to connect the Mac · make a new one…" state, no different in kind from "one" (`recovery/cover-dark-many.png`, `cover-light-many.png`).
5. **Recovery not-connected gives two different reasons** (med; real-looking): the header says "Can't check right now · Navilife's server didn't answer", but the gold line under the button says "Connect this device to your server first." ("Navilife's server" vs "your server"). Meanwhile "Make a recovery key" is drawn as enabled (`recovery/cover-dark-not-connected.png`).
6. **Activity: "Undo lasts 30" with no unit** (med; real-looking): "this shows the last 30 days and Undo lasts 30 · Export saves the / 1 entry", with no "days" after 30 and "1 entry" alone on its line (`activity/cover-dark-one.png`).
7. **Notifications by-hand: quiet-hours text says things wait "for the next digest" with digests off** (med; real-looking): Midday and Evening are both Off and the header says "No digests", yet the quiet-hours line reads "Heads-ups that fall in quiet hours wait for the next digest." (`notifications/cover-dark-by-hand.png`; the same line also shows under "No digests" in the empty shot's header copy).
8. **Lines ending on a lone word or "·", or wrapping early** (med): Activity "This week: 1 / reminder (1 digest) · 3 changes · 2 / calls stopped." (`activity/cover-*-many.png`, `cover-dark-by-hand.png`); Activity empty "calendar edits you make / in Navilife" and "and can / be undone." break with room left (`activity/cover-dark-empty.png`); Voice "where you left / off · 3 notes" and "off · 1 note" (`voice/cover-dark-many.png`, `cover-dark-one.png`, `cover-light-one.png`); Voice intro "The choice follows you / to every device." breaks early (all voice cover shots); Voice notes "Sam about the trip money / before Monday." (`voice-notes/cover-*-many.png`) and "hold the 🎙 / in Capture" (`voice-notes/cover-dark-empty.png`); Recovery "keep it / at home…", "keep them / in notes." and "connect the / Mac ·" break with room left (all recovery shots).
9. **Activity chips: "Calls · 2" wraps alone onto a second row** on the cover, with a large ~64 dp gap between the subtitle and the chips (`activity/cover-dark-many.png`, `cover-dark-by-hand.png`, `cover-light-many.png`). The same oversized gap sits above TODAY in `cover-dark-one.png`.
10. **About you by-hand starts mid-scroll with clipped fields** (med): the top field "OFC Oakfield Town, HRFC" is cut by the top edge with no title, and "Official numbers" is cut mid-digit, "bank 0306 9…" (`about-you/cover-dark-about-you-by-hand.png`).
11. **Said twice** (low–med): "In your data" under every one of 5–8 fields (`about-you/*`); Voice notes by-hand "Kept · Navilife won't delete it" next to a "Kept" button (`voice-notes/cover-dark-by-hand.png`); in Voice many, the Kept garden note has no such line, so the two states are worded inconsistently.
12. **About you's pronouns disagree** (low; real-looking): "Watch for requests · Navilife flags what they ask you" next to "Handle her requests for me · what she asks for" for the same partner (`about-you/cover-*-many.png`). That row's subtitle also runs right up to its On pill ("with" ends ~5 px from the pill).
13. **Voice notes by-hand: stray "Put back"** (low): a small grey "Put back" line sits under "1 note · 1:23 recorded" with no context about what it would restore (`voice-notes/cover-dark-by-hand.png`).
14. **Voice: Face explanation only in the empty shot** (low): "Shows once the face's files are on Navilife's server; the orb until then" appears in `voice/cover-dark-empty.png` but not in one/many/light-one, with the same Face Off setting.
15. **Small tap targets** (low–med): On/Off pills, Pause, Ask first, filter chips and day chips are ~32 dp tall (`notifications/*`, `autonomy/*`, `activity/*`, `voice/*`, `about-you/*`); bare −/+ stepper glyphs (`notifications/*`, `work/*`); bare "›" chevrons on Work's day rows; text-only Keep/Delete, Forget all, Remove, Not now.
16. **Dark-theme faint "Off" pills** (low): unselected Off pills in Notifications draw dark-grey text on a dark-grey fill and are barely legible (`notifications/cover-dark-by-hand.png`, `cover-dark-empty.png`). Autonomy's off-state dots are near-invisible in both themes (`autonomy/cover-dark-*.png`, `cover-light-many.png`).
17. **Light contrast** (low): Activity's unselected chips and cards, Notifications' Allow/Send buttons and Recovery's "Make a new one" are off-white on off-white, with no edge (`activity/cover-light-many.png`, `notifications/cover-light-many.png`, `recovery/cover-light-many.png`).
18. **"Allow notifications" shown with Everything selected in every state** (low; maybe fixture): the permission button stays even when digests are on and counting "3 things so far" (`notifications/*`).
19. **Work's open-Fold layout uses a centred column (left edge ~316 px)** while every other inner screen here starts at ~39 px (`work/inner-*.png` vs `activity/`, `notifications/`, `voice/`, `voice-notes/inner-*.png`) (low; may be intended).

#### Last week's items in this area
- A14 Voice "left / off · 3 notes": still there (`voice/cover-dark-many.png`, `cover-dark-one.png`)
- A13 small targets, On pills ~31 dp and About you's Watch for requests ~32 dp: still there (`notifications/*`, `about-you/cover-*-many.png`)
- A19 Work mode's "Each day" in sentence case: fixed (now "EACH DAY")
- A20 notifications by-hand = empty: fixed (by-hand now shows quiet hours 22:00–07:15)
- A20 by-hand shots of about-you, activity, voice (×2), voice-notes: fixed for activity, voice-notes and about-you (all now differ). Voice has no by-hand shot in this set. New identical pairs: autonomy by-hand = one, work inner by-hand = inner empty
- A23 work empty fully set up: still there (`work/cover-dark-empty.png`, `inner-dark-empty.png`)
- A23 notifications one/many "Next digest 18:00 · nothing yet": fixed ("1 thing so far" / "3 things so far")
- A23 voice inner-one shows Obi remembers nothing: fixed (shows "1 note · Likes the 07:42 train")
- A27 Voice notes' Keep / Delete ~18 px right of the note text: fixed (aligned at the text's left edge)
- A27 −/+ steppers end short of the On pills' right edge: fixed (`notifications/*-many.png`, `work/cover-dark-one.png`)
- A28 Activity "1 / reminder", "stopped.", lone "log.": partly still there ("This week: 1 / reminder… 2 / calls stopped."; "log." now fits on the cover); new lone "1 entry" in `activity/cover-dark-one.png`
- A29 Notifications' "On · 21:30–06:45" beside an On pill: fixed (row now reads "21:30–06:45")
- A31 light contrast, Work's off-day circles and commute chips: fixed (outlined); Voice notes' cards and Keep / Delete: fixed (cards outlined)
- A32 Work's commute "About 1 h 20 each way" with no chip lit: fixed in many (1 h 15 lit, "1 h 15 there · 1 h 20 home"); the empty shot still says "About 1 h 20 each way" over a cut-off lit chip
- A32 bare −/+ and "›" glyphs ~24–30 dp (Work, Notifications): still there
- A32 Face On on Voice vs off on Health: Voice now shows Face Off everywhere (Health not in this set)

### g6-lifea
Read 31 of 31 shots. (None unread; no byte-identical pairs in the group, checked by md5.)

#### Findings (ranked, most severe first)
1. **Reading goal shows a count of "1" when there is no goal** (high; real-looking): the card header reads "Reading goal 1" over "No goal for the year / Tap to set one, e.g. 12 books". It is in the empty state too, where every other count is 0. (`reading/cover-dark-empty.png`, `inner-dark-empty.png`, `cover-dark-one.png`, `cover-dark-reading-by-hand.png`)
2. **The same trip has different dates on Trips and Trip lists** (high; likely fixture, still A8):
   - Seville: Trips says "Next: Seville · Wed 28 Oct · in 7 days", but its Trip lists page says "2 days to go" for the same Wed 28 – Sat 31 Oct.
   - Rome: Trips by-hand has "Fri 20 – Sun 22 Nov · 3 days · in 30 days", but Trip lists has "Rome trip / Sun 20 – Wed 23 Dec · 4 days / 55 days to go".
   (`trips/cover-dark-many.png`, `cover-dark-trips-by-hand.png`, `trip-lists/cover-dark-pack.png`, `cover-dark-start-from.png`, `cover-dark-start-template.png`)
3. **Reading goal line cut off on the cover** (med; real): "9 behind pace · 12 to go · about 4 a m…". The open Fold shows the full "about 4 a month". (`reading/cover-dark-many.png`, `cover-light-many.png`)
4. **Casey's promises change between People states** (med; fixture): many shows "Casey / You promised 2 · Waiting for 1". After adding a promise to Jordan, by-hand shows "Casey / You promised 1 · Waiting for 1", and one shows "Casey / You promised 1". On many, the "1 due or to chase" tile also leaves out Casey's 2 promises with no explanation. (`people/cover-dark-many.png`, `cover-dark-people-by-hand.png`, `cover-dark-one.png`)
5. **School: "1 day off to cover" for a 5-day half term, and an ambiguous "5 days"** (med; real-looking):
   - The tile says "1 / day off / to cover", but the item is half term, Mon 26 – Fri 30 Oct (five weekdays).
   - The Half term row's right-hand "5 days" could mean "in 5 days" or "lasts 5 days", since both are true. It is the only row without its date, which is replaced by "Who's covering?".
   (`school/cover-dark-many.png`, `cover-light-many.png`, `inner-dark-many.png`)
6. **Reading header says the same thing twice** (med; real): "1 on the go · 3 up next" is followed by "October · 1 still going · next up: Dune". "On the go" and "still going" are the same count, "next up" repeats "3 up next", and the bare "October" label is unexplained. The one and by-hand states have the same lines: "1 on the go / October · 1 still going" and "2 on the go … / 2 still going". (`reading/cover-dark-many.png`, `cover-dark-one.png`, `cover-dark-reading-by-hand.png`, `inner-dark-many.png`, `cover-light-many.png`)
7. **Line ending on "·"** (med; real, A14 Reading still): the add confirmation wraps as "Added book Project Hail Mary by Andy Weir · / up next". (`reading/cover-dark-reading-by-hand.png`)
8. **"Holidays from the council 0" repeats Days off** (med; real): the card's only line is "Type the holidays with + Add → A day off", the same action as Days off' "+ Add → A day off". In many it stays at 0 while Half term, a council holiday, is listed under Days off. (`school/*`, all 7 shots)
9. **Small tap targets** (med; real; A13 still on Trip lists):
   - Trip lists: the bare "^" beside "Documents 1 of 3", and "⋯" in ~32 dp circles.
   - Trip lists: the Everyone / Me / Kids pills and the suggestion chips are ~31 dp tall.
   - Every page: the ⓘ next to the title is ~24 dp and sits almost touching the last letter ("Trips", "School", "Reading", "People").
   - School sheet: "Remove" is a small grey text-only target.
   (`trip-lists/cover-*-pack.png`, `cover-dark-suggest.png`, all titles, `school/cover-dark-school-by-hand.png`)
10. **School date sheet's left edge drifts** (low; real): "Remove" starts ~18 px right of "Trip payment", "School date" and "To a day…". The sheet also has large empty bands. "To a day…" doesn't say what it does. (`school/cover-dark-school-by-hand.png`)
11. **Mixed units for countdowns on one screen** (low; real):
    - Trips: the tile says "3 wks to Barcelona" while the header and row say "in 24 days" / "In 24 days".
    - School: "12 days" and "3 wks" / "4 wks" / "6 wks" sit in one list.
    (`trips/cover-dark-one.png`, `school/cover-dark-many.png`, `cover-dark-school-by-hand.png`)
12. **Trips' Travel documents line wraps early** (low; real): "None in the Vault · add passports / in Settings → Vault" breaks with ~120 px of room left, while the longer "Nothing booked yet · + Add → A booking" fits on one line above it. (`trips/cover-dark-many.png`, `cover-dark-one.png`, `cover-dark-trips-by-hand.png`, `cover-light-many.png`)
13. **Trip lists put travel insurance under "To pack"** (low; real-looking): the suggestion "For 4 days, worth packing:" includes "Travel insurance" (and Passports / GHIC cards for Rome). Accepting it gives "Added Travel insurance to To pack" and Packed goes to "1 of 10".
    - Related, needs checking: Seville's lists total Bought 0 of 2 + Packed 1 of 9 = 11, but Rome's card offers "Start from Seville trip / Copies its 12 things". Before we leave may hold the 12th item.
    (`trip-lists/cover-dark-suggest.png`, `cover-dark-start-from.png`, `cover-dark-start-template.png`)
14. **Empty states said twice, plus "None documents"** (low; real):
    - Trips empty has "No trips yet." in the subtitle and "No trips yet · + Add" in the card, plus a "No trips coming up" tile.
    - The third tile reads "None / documents in the Vault", which is ungrammatical. (all trips shots)
15. **The Add button is crowded against its field's right edge** (low; real): the gold Add ends ~8 px inside "Add something to pack…", while the placeholder text starts ~36 px in. (`trip-lists/cover-*`, `inner-dark-pack.png`)
16. **Light theme edges are faint** (low; real): white cards on the off-white page have almost no visible edge: Your trips, Booked, Travel documents, the Bought/Packed tiles and the segmented tab row. Outlined tiles are fine. (`trips/cover-light-many.png`, `trip-lists/cover-light-pack.png`, `school/cover-light-many.png`, `reading/cover-light-many.png`)
17. **People by-hand confirmation drops the header and has no Undo** (low; real): "Added your promise to Jordan: Lend the ladder" replaces "N people · 1 to chase". Reading and Trip lists offer Undo on their add confirmations. (`people/cover-dark-people-by-hand.png`)
18. **Taylor's lone "due"** (low; real): the Coming up row ends in a bare gold "due" with no date, where the other rows show "Sat" and "3 Nov". (`people/cover-dark-many.png`, `cover-dark-people-by-hand.png`)

#### Last week's items in this area
- A8 People "1 to chase" vs two "Waiting for 1": fixed. Taylor now reads "Waiting for 1, 1 to chase", which matches the header and tile. Casey's counts now differ between states instead (finding 4).
- A8 same trip's dates across Trips / Trip lists: still there (`trip-lists/cover-dark-pack.png` vs `trips/cover-dark-many.png`; Rome too, finding 2).
- A13 bare "^" / "⋯" glyphs on Trip lists: still there (`trip-lists/cover-dark-pack.png`, `cover-light-pack.png`, `cover-dark-suggest.png`).
- A14 Trips "4 days ·": fixed (dates and "· 4 days" sit on one line).
- A14 School "Oct" alone: fixed ("Next day off: Half term · Mon 26 – Fri 30 Oct" fits on one line).
- A14 Reading lone word / "·": still there ("…Andy Weir · / up next", `reading/cover-dark-reading-by-hand.png`).
- A19 Trip cards' bottom padding: looks fixed (the Your trips card's padding is even top and bottom).
- A20 People by-hand identical to another state: fixed (it shows the Jordan add and 5 people).
- A32 Trips and Countdowns both "Seville in 7 days": not visible in these shots (no Countdowns shot). Trips still says "in 7 days".
- B Trip lists' open Who's going editor and missing ⋯: the editor is now a single link, "Add who's going and when you leave", and ⋯ is present on Documents.
- B half-empty open-Fold pages (Trips, People, School): still half-empty on Trips and School (`trips/inner-dark-*.png`, `school/inner-dark-empty.png`). People has no inner shot here.

### g6-lifeb
Read 31 of 31 shots. (All read; none refused.)

#### Findings (ranked, most severe first)
1. **Facts' cover has no + Add button** (high; real-looking): on the folded phone the title "What Navilife knows" takes the whole width, so the gold "+ Add" pill every sibling screen has (People, Leave, Kids, Gifts, and Facts on the open Fold) is missing. The empty state still says "Nothing yet · + Add puts the car's reg, the GP, sizes and the utilities here", pointing to a button that isn't on screen. The ⓘ is squeezed into a narrow upright oval against the "s" and the right edge. (`facts/cover-dark-empty.png`, `cover-dark-one.png`, `cover-dark-many.png`, `cover-dark-facts-by-hand.png`, `cover-light-many.png`; compare `facts/inner-dark-empty.png`, where + Add is shown.)
2. **Leave's Booked rows don't add up to the headline, and next year's leave sits in this year's list** (high; real-looking): the header says "13 of 25 days booked · 12 days left" and "Leave year Jan – Dec 2026 · 13 to come", but the rows add up to 4 + 1 + 9 + 2 + 1 = 17 days. The 13 only works if Spain counts 8 of its 9 days (the 2026 part) and "Fri 5 – Mon 8 Feb · 2 days" ("3 mths") and "Fri 2 – Sat 3 Jul · 1 day" ("8 mths") belong to the 2027 leave year. None of these rows shows a year or says that its days count elsewhere, and Spain shows "9 days" while only 8 are counted. (`leave/cover-dark-many.png`, `cover-light-many.png`, `inner-dark-many.png`; same pattern in `cover-dark-leave-by-hand.png`, where "7 of 25" leaves the Feb row out.)
3. **Kids' pocket money: "£6 a week" but "£7 to pay"** (med; likely a test fixture): Sam's row says "2 chores · £6 a week" with "£7 to pay" on the right, and the tile says "£7 to pay this week". The many shot is consistent ("1 chore · £5 a week" / "£5 to pay"). (`kids/cover-dark-kids-by-hand.png`)
4. **Facts truncates the boiler's value with room to spare** (med; real-looking): "Oakfield Combi 30, serviced…" is cut with an ellipsis even on the open Fold, where about three-quarters of the row width is empty. This looks like a fixed character cap, not the width. (`facts/inner-dark-many.png`, `cover-dark-many.png`, `cover-light-many.png`, `cover-dark-facts-by-hand.png`)
5. **People and Gifts disagree on whose birthdays exist** (med; test fixture, last week's A8 still there): People's empty state says its birthdays come from Gifts ("No birthdays in Gifts yet"). Yet People many lists Priya (Sat 24 Oct) and Sam, with no Alex or Grandma Rose. Gifts many lists Alex (31 Oct), Sam, Grandma Rose and the anniversary, with no Priya. (`people/cover-light-many.png`, `inner-dark-many.png` vs `gifts/cover-dark-many.png`, `inner-dark-many.png`)
6. **Leave's by-hand Christmas row is cut and its status has no visible basis** (med): "Mon 21 Dec – Fri 1 Jan · 7 work …" is cut by the "Part booked" label. Nothing shown is booked in Dec/Jan: the header's 7 days = 1 + 4 + 1 + 1, all Oct/Nov. Booked shows 6 with "See all ›" but only 5 rows are visible. (`leave/cover-dark-leave-by-hand.png`)
7. **Test strings and debug text in the Facts by-hand shot** (med; test fixture): "Added Test kept to Other · kept from ai" (lowercase "ai") under the header, and rows "Test fact / by hand" and "Test kept / by hand". The value line shows the source instead of a value. (`facts/cover-dark-facts-by-hand.png`)
8. **Kids empty says the same instruction twice** (med): the header says "Add the children in Ask → Settings → About you first; each gets a card here for their sizes." and the Children card under it repeats "Add the children in Ask → Settings → About you first". On the cover the card's copy wraps to a lone "you first". A "+ Add" button also sits beside text saying children can't be added here. (`kids/cover-dark-empty.png`, `inner-dark-empty.png`)
9. **Line ending on "·"** (med, A14's rule): the Leave footer wraps as "…the brief says “Annual leave ·" / "no work”." on the cover. (`leave/cover-dark-empty.png`, `cover-dark-one.png`; also at the bottom edge of `cover-dark-many.png`, `cover-light-many.png`.) Gifts empty's "Nothing in the next two months · + Add a" / "birthday…" breaks inside "+ Add a birthday". (`gifts/cover-dark-empty.png`)
10. **Gifts counts "2 gift ideas noted" but no idea appears anywhere** (low-med): no row or section on the screen shows an idea. Also "2 gifts to sort soon" sits over "Coming up 3" with no hint which one is left out (presumably Grandma Rose at 16 days). (`gifts/cover-dark-many.png`, `cover-light-many.png`, `inner-dark-many.png`)
11. **Garbled empty-state tiles** (low): Kids "No fees / club fees kept", "None / needed this week", "None / sizes kept yet"; Leave "None / leave to come", "None / holidays to book"; Facts "None / for Obi yet". These read as broken sentences. (`kids/*-empty.png`, `leave/*-empty.png`, `facts/*-empty.png`)
12. **Count pills on single-item and empty sections** (low): "Allowance 1" on every Leave shot, and "0" pills beside "Nothing yet" text (Coming up 0, Everyone 0, Booked 0, Children 0, Your facts 0, Club fees 0). (all `*-empty.png`, all leave shots)
13. **Kids' "1 of 1 sizes kept"** (low): Alex has only "Shoes 13" and the tile says "1 of 1"; the many shot drops this tile. "Kids" footer explains an "All still fit" control that appears on no Kids shot. (`kids/cover-dark-one.png`, all kids shots)
14. **People's wording mixes "," and "·" and calls a chase "due"** (low): "Waiting for 1, 1 to chase" vs Casey's "You promised 2 · Waiting for 1". Taylor's right-hand label is "due" though the item is a chase. Coming up's date column mixes "Sat" and "3 Nov". (`people/cover-light-many.png`, `inner-dark-many.png`)
15. **Facts "1 fact · Navilife answers from them"** (low): plural "them" for one fact. The header says "Navilife answers from 5" while the tile calls the same thing "Obi can use", two names for one figure. (`facts/cover-dark-one.png`, many shots)
16. **The ⓘ touches the title on every screen** (low): no gap between the title's last letter and the ⓘ circle ("People", "Annual leave", "Kids", "Gifts"). (all shots)
17. **Kids' This week row has extra bottom padding on the open Fold** (low): the one-line "No school · Half term · until Fri 30 Oct" row leaves about 40 px of empty space under it before the card edge, more than the other cards. (`kids/inner-dark-many.png`)

#### Last week's items in this area
- A8 People "1 to chase" vs two "Waiting for 1": fixed as a sum. Only Taylor's says "1 to chase" and Casey's is waiting only, which matches "1 due or to chase". The wording is still muddled (see 14).
- A8 People/Gifts birthday fixtures agree: still there (people/*-many vs gifts/*-many; finding 5). Sam's 3 Nov does agree.
- A8 Emergency/Facts: not visible in these shots (no Emergency shot). Facts' GP is "Dr Patel, 01632 960123".
- A13 Kids' chore circles ~31 dp: not visible. The by-hand shot no longer shows chore circles, only a "Sam · 2 chores" summary row.
- A14 Leave line ending "·": still there (footer "“Annual leave ·", leave/cover-dark-empty.png, cover-dark-one.png).
- A14 Kids "months"/"next": fixed. "checked every 3 months" now fits on one line; no lone "next".
- A14 Facts "from AI": fixed. The footer now wraps "…never one kept / from AI. Passwords…".
- A15 Kids' size fields / "All still fit" with 2 of 6 kept: not visible. The many shot shows sizes as summary lines with no fields (the footer still mentions "All still fit").
- A19 Leave's − buttons not aligned: not visible (no − buttons in these shots).
- A19 21 Dec–1 Jan counted as 10 work days: fixed ("7 work days").
- A19 Kids' half term as two days: fixed. It is now one row, "No school · Half term · until Fri 30 Oct".
- A20 People by-hand identical to another state: not visible (no people by-hand shot in this set).

### g7-life2
Read 49 of 49 shots. (Errands, Emergency, Dinners, Date night, Car, Bins, Appointments; cover dark/light and inner dark. All read; md5 used to confirm the identical pairs in finding 3.)

#### Findings (ranked, most severe first)
1. **Emergency card's + Add is cut to "+ A" and sits on the (i) icon** (high; real). "Emergency card" plus its (i) fill the row on the cover, so the gold pill is pushed right and clipped at the screen edge, reading "+ A", with its left end overlapping the (i) circle. This is on every cover shot: `emergency/cover-dark-empty.png`, `cover-dark-one.png`, `cover-dark-many.png`, `cover-dark-emergency-by-hand.png`, `cover-light-many.png`. The inner shots are fine. Appointments almost hits the same problem: there is only ~20 px between "Appointments(i)" and + Add (`appointments/cover-*`).
2. **Appointments by-hand: the tile says "None due to book" over a To book card holding Dentist** (high; real-looking). Tiles read "None / due to book" while the card below is "To book 1 · Dentist · Every 6 months · Add a date", and the subtitle says "1 needs a date". The subtitle also runs to a second line, "Physio · Next due Jan 2027", which reads as stray text (`appointments/cover-dark-appointments-by-hand.png`).
3. **Shots that don't show their state** (med; tests). These pairs are byte-identical: `errands/cover-dark-errands-by-hand.png` = `cover-dark-many.png`, `dinners/cover-dark-dinners-by-hand.png` = `cover-dark-many.png`, `date-night/cover-dark-date-night-by-hand.png` = `cover-dark-empty.png`. The emergency by-hand shot shows its added test lines ("Test Who to call / by hand", "Test Where things are", "Test Key accounts" and a banner "Added Test Key accounts to Key accounts"). It also dropped "Alex's school office", which the many shot has, so its "9 lines" isn't many + 3 (`emergency/cover-dark-emergency-by-hand.png`).
4. **Counts that disagree with what's listed** (med; real). Each card's count chip counts something different from its rows:
   - Errands "Waiting 3" (places) under a tile "6 errands waiting" (`errands/*-many`, `cover-dark-errands-by-hand`).
   - Dinners "Shopping 1" over one row saying "Add 12 ingredients".
   - Dinners "This week 7" over 5 rows, even on the open Fold with room to spare (`dinners/inner-dark-many.png`).
   - Dinners one: "Nothing planned this week" and "None dinners planned" beside "This week 7" (`dinners/cover-dark-one.png`).
   - Settings rows get a count: "Near-me reminders 1" (errands, every shot), "The evening 1" (date-night many), "Reminder and dates 2" in the empty Bins (`bins/cover-dark-empty.png`, `inner-dark-empty.png`).
   - Car shows "Fuel near you 0" and "Car Bluetooth 0" for permissions that aren't granted (`car/*`).
5. **Empty-state lines that read as garbled templates** (med; real). The "None · + Add → <label>" pattern stitches a placeholder onto a hint. Examples:
   - "Not parked · + Add → Where I parked when you leave the car" (`car/cover-dark-empty.png`, `inner-dark-empty.png`).
   - "Off · + Add → The evening to keep one clear every two weeks" (`date-night/*-empty`).
   - "Nothing waiting · + Add → Things to do at a place" (`errands/*-empty`).
   - "Your own days · + Add → The council's dates t…" (`bins/*`).
   - "None yet · + Add → A key account, by name" (`emergency/*-empty`, `cover-dark-one`).
6. **Said twice** (med). Appointments empty says "No appointments yet" in both the subtitle and the card. Dinners empty has "No favourite dinners yet", "None favourites" and "No favourites yet" on one screen. Bins empty has tiles "No bins added yet" beside "0 bins on your list", and the reminder row reads "Reminder / Reminder the evening before at 19:00 …" next to "19:00". Date night many says "Every other Friday from 19:00" three times: subtitle, the "19:00 every other Friday" tile, and The evening row. Car parked rows put "19:05" on the right beside "Parked 19:05". Errands has "Near-me reminders" as both the card title and its only row (`errands/*`).
7. **Lines ending on "·" or a lone word** (med):
   - Car tiles "to the MOT · / The Golf" and "paid until · / The Golf" (`car/cover-dark-many.png`, `cover-light-many.png`).
   - Car fuel sheet "…about 1 km is / sent", the lone "sent" still there (`car/cover-dark-by-hand.png`).
   - Emergency subtitle "…on their family / page" (`emergency/cover-*-many`, `cover-dark-one`, by-hand).
   - Dinners empty "+ Add one with its / ingredients".
   - Dinners favourite "Chicken and vegetable stir fry with / noodles" (`dinners/cover-*-many`).
   - Bins reminder truncated to "at 19:00 · …" (`bins/cover-*`).
8. **Text cut where there's room, or with no way to see it** (med). Car's two parked rows end "±…" and "±1…", and MOT ends "Every year from the dat…" (`car/cover-*-many`). Emergency "the grey box file in the loft…" is cut even on the open Fold, with ~1,400 px spare (`emergency/inner-dark-many.png`). Bins' Council's dates row is cut to "The council's dates t…" on the cover. Errands rows end "return the par…" and "a spare key…" (`errands/cover-*-many`).
9. **Car tile promises "Fuel / prices below" with no prices below** (med; real). The Fuel near you card says "0" and "Allow location…" (`car/*`, every state including empty).
10. **Fixture dates disagree across these screens** (low; test fixtures). Dinners, Date night, Bins and Appointments put today at Wed 21 Oct ("Tonight", "Fri 23 Oct", "13 days to Hygienist" on Tue 3 Nov). Car says "3 wks to the MOT" for Mon 7 Dec, which puts today around mid-November. Dinners also plans "Fish pie" for Fri 23 Oct, the same evening Date night keeps clear and marks "To book".
11. **Left edges and rhythm** (low). On the cover, Car's "Stop timer" starts ~18 px right of the +15 min chip and the card text, with a large empty band above it (`car/cover-*-many`, `cover-dark-one`). Cards with a right-side link (This week / See all, Dates / All dates, Fuel near you / See all) have ~70 px top padding against ~43 px on cards without one, so headers sit at different heights (`dinners/*`, `car/*`). The link text ("See all ›", "All dates ›", "Find my car ›") ends ~18 px short of the right edge of the row values below it. The (i) icon touches the title with no gap on every screen ("Errands(i)", "Car(i)", "Bins(i)").
12. **Bins' right column mixes days and dates** (low). "Fri", "Tue", "Tue", then "3 Nov" for Recycling, so you can't tell which Tuesday is this week's (`bins/*-many`).
13. **Light theme** (low). Car's "Stop timer" is pale grey on white and looks disabled (`car/cover-light-many.png`). The tile borders and the other light shots read fine.
14. **Small or bare targets** (low). Car's "Stop timer" and the errands "Off" value are bare text with no visible control. Errands' Near-me "Off" shows no switch (`errands/*`).

#### Last week's items in this area
- A8 Appointments "all in hand" over a card asking when he last went: fixed (subtitle is now "5 appointments · 1 due"), but see finding 2 for the by-hand tile "None due to book" over Dentist.
- A8 Emergency/Facts date fixtures: not visible in these shots (no Facts shot); the in-area fixture spread is in finding 10.
- A14 Emergency "key" / "house" lone words: fixed on the rows ("Neighbour with the spare key" and "Wills and the deeds to the house" fit), though the subtitle now leaves "page" alone (finding 7).
- A14 Car "sent": still there (`car/cover-dark-by-hand.png`, fuel sheet "…1 km is / sent").
- A19 Car "Walk there in Maps" vs Find my car: not visible (only "Find my car ›" is shown; no walk/Maps button in these shots).
- A19 errand buttons right-aligned: not visible (no errand detail or buttons shown, only list rows).
- B half-empty open-Fold pages (Bins, Emergency): still there (`bins/inner-dark-empty.png`, `emergency/inner-dark-empty.png`, and Date night, Errands and Appointments inner empty are the same, more than half the page blank).
