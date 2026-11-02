# Weekly walk-through, 2026-11-02 (quality gate (5), the first weekly one; Sunday's, run Monday evening)

**How:** the 225 screenshots that changed since the first walk-through's set (`screens` branch, be86653 → 93fc4b9,
CI run 1483 → 1523; 576 shots in all), split in three groups and read by three fresh reviewers who did not build the
screens, folded (cover) and open (inner), dark and light, against `docs/design/` and the layout rules, each also
re-checking the 2026-10-30 section A items in their areas. The build run spot-checked two findings in the images: the
clipped "2" on Trip lists is real (A1 below); AI's "$18.30 in all" over $2.82 of rows is a **shot that stops too soon**
(the "Not split by feature" row is drawn below the image's bottom edge: `AiScreensTest` checks it in the tree), so the
fix there is the shot, not the screen. **Not covered:** the Mac (same reason as before), and the shots that didn't
change this week (read on 30 Oct).

**From 30 Oct, now fixed:** voice notes' first letters; Health's Off dot, Battery button and counts; Setup's "0 of N";
Watch's code error; Voice's On/Off order; Activity's 4th chip; Accounts' "Due ?"; Trading's repeated lines; Ask Obi
agreeing with Plan; date night and appointments chips; Life admin/Date night "Off" with a lit time; Reading's 37 %;
the bedside "UP NEXT" and doubled headline; Week ahead's orphan "h"; the Board's "–" and "done none" pills.

**From 30 Oct, still showing:** an email address as a calendar's name (`calendars/inner-dark-by-hand.png`, ON TODAY
rows); Today's "5 anytime tasks" over "ANYTIME TODAY · 4" and its last row under the capture field; the open Fold's
Board with a full week (Sat/Sun wrapped under Mon/Tue, clipped cards, black space under Wed–Fri); Accounts' "Outside
these accounts £42,000" twice; the Tomorrow edit sheet's Repeat row showing under the chips in light; the bedside's
first news card against the left edge; invented data that disagrees across screens.

## A · Found and fixing (no mockup needed), ranked

1. **Trip lists clips the first glyph** (high): "2 days to go · leave home 06:30 Wednesday" has its "2" cut at the
   left edge (`trip-lists/cover-dark-going.png`; whole in `cover-dark-pack.png`). Same family as the old voice notes.
2. **Money Plan's "left each month"** (high): the What-if card says "left each month after usual spending: about
   £1,120" while the forecast on the same page says "About £2,220 a month left over" — £1,120 is what's left *after*
   saving £1,100, so the label is wrong; and the forecast's totals don't match its monthly figure (£2,220 × 6 =
   £13,320, shown +£13,340; × 12 = £26,640, shown +£26,690) (`money/cover-dark-plan-whatif.png`,
   `inner-dark-plan-layout.png`).
3. **Study plan's figures** (high): "3 of 8 lessons · about 4½ h left" over "about 1 h · 5 lessons left"; "Fits: the
   2 times you picked hold 4 sessions a week" beside "2 sessions a week"; "Next: Pivot tables · 2 · Core skills" with a
   bare "2" (`study/cover-dark-plan.png`).
4. **Tomorrow's edit sheet** (high): the title reads "Gym, legs day 06:40" while When and the banner say 07:40; the
   Repeat row ghosted under the chips in light; the two shots are tiny (≈320×470) (`tomorrow/fold-*-edit.png`).
5. **Today, still** (high): "5 anytime tasks" vs "ANYTIME TODAY · 4", the last Anytime row under the capture field
   (`today/cover-*-many.png`); on the open Fold the "TODAY · AT WORK UNTIL 17:30" label's timeline and, while fasting,
   Up next's buttons sit under the capture bar (`today/inner-dark-one.png`, `inner-dark-many.png`, `inner-dark-fasting.png`).
6. **Board on the open Fold** (high, still): the full week as in 30 Oct's item 6 (`board/inner-dark-many.png`); on
   Saturday a lone "Not now" and "+ add" with nothing above, Obi's pill cut before it says where ("move … to …"),
   "Today is 4h over" while Saturday's column reads 1h / 12h (`inner-dark-unfolded-on-saturday.png`); the scrolled
   nudge squeezed to 3 lines with "Move to" cut (`inner-dark-scrolled.png`); "Send the quote" cut at the Today pane's
   bottom and its tag "Side h…" (`inner-dark-timeline*.png`).
7. **An email address as a calendar's name** (high, still): `calendars/inner-dark-by-hand.png`, ON TODAY.
8. **The Obi sheet's mic is a sliver** (med): about 17 dp wide, cut against the field's edge (`money/cover-dark-ask-obi*.png`,
   `inner-dark-ask-obi.png`).
9. **Small On pills and chips** (med): About you's "Watch for requests" On (~20 dp tall), Calendars' ON TODAY On pills
   (~21 dp), the Week ahead's Sunday heads-up On and 16:00–20:00 chips (~21–23 dp); Trip lists' "^" and "⋯" glyphs with
   no hit area (`about-you/cover-dark-many.png`, `calendars/inner-dark-by-hand.png`, `week-ahead/cover-dark-empty.png`,
   `trip-lists/cover-dark-pack.png`).
10. **Study on the Board** (med): Mon/Tue "Study · 1 lesson · about …" cut before the length; their pills read "0m /
    2h" with a session on the day (`study/inner-dark-board-study.png`).
11. **Setup** (med): the parts add to 25 in two states and 23 in the empty one; "On the Fold" shown on the Fold itself
    for Call screening, Family, WhatsApp and texts (`setup/cover-dark-empty.png`).
12. **Kids** (med): "From School, the club sessions … and the fees above" while CLUB FEES is below; "All still fit ·
    Sizes checked today" with 2 of 6 sizes filled and grey hints ("12" under Shoes 13) that read like sizes
    (`kids/cover-dark-many.png`).
13. **Trip lists** (med): "For 4 days with 2 people" gives "Pyjamas × 2" but "Underwear and socks × 4" (people not
    counted); the saved 06:30 leave time drawn in hint grey with "for example 06:30" under it (`trip-lists/cover-dark-going.png`).
14. **Smaller ones** (low): Accounts' £42,000 twice (still); Fasting's "9h 30m · 6h short" of 16 h (6 h 30 m) and
    light Thu/Fri rings near invisible (`fasting/inner-*-many-below.png`); Health "Checking… · Checked 17:15" at once
    (`health/cover-dark-not-connected.png`, `cover-dark-working.png`); Activity's "1 digest" vs the "Reminders · 1" chip;
    "Platinum Card" vs "Platinum card"; Money's tab row touching the cover's right edge; Week ahead's title breaking
    before "leads"; Emergency's add hint "GP: Dr Patel, 01632 123456" under the real Dr Patel card; Car's 25 min ago vs
    1 h 36 left a minute apart; voice notes' Keep/Delete look disabled; Trip lists' "Before we leave" against the pill
    edge, "Bought Nothing yet" run together; the bedside's first news card against the left edge.
15. **Shots that don't show what they claim** (med, tests): every `*-by-hand.png` in appointments, bins, car, date
    night, emergency, kids, reading, school and trips is byte-identical to another state; `trip-lists/cover-dark-suggest.png`
    = `cover-dark-pack.png`; Mail's one / by-hand / many are identical; AI's many stops above its last row. So those
    states went unreviewed.
16. **Invented data that disagrees** (low, tests): the user "Jordan" with an alex@ calendar and "Alex's shoe size 13";
    Trips' today (21 Oct) vs Trip lists' (26 Oct) and Seville missing from Trips; School's hint putting an INSET day in
    half term; Today's 1 habit vs Goals' 3; Today's "Nothing planned in the next 30 days" vs Calendar's 6 events; the
    bedside's dentist at 13:00 vs the calendar's 09:30.

## B · Layout taste (to the whole-app layout review, nothing changes before Meka's yes)

The open-Fold Today lopsided (left crowded, right empty); the Board's Sat/Sun on a second row and 3-line card titles at
the narrow column; the bedside news cards flush left with the right third empty; Car's lone "Drove off" and a bay field
still offered when the bay is set; School's two near-duplicate inputs beside an action-less council card; orphan chips on
a second line (Bins, Date night); Life admin's centred column on the open Fold unlike its neighbours; Family's cards
flush under the disclaimer; Mail's empty band above Turn off; Trading's near-empty left column on the open Fold; About
you's full-width fields on the open Fold; half-empty cover screens (Money's Bills tidy, Trading, Watch); Health rows
squeezed beside a button.
