# Whole-app walk-through, 2026-10-30 (quality gate (6), the first one)

**How:** the 562 screenshots CI published after run 1483 (`screens` branch, `docs/screens/fold/<area>/`, invented data)
were read by five fresh reviewers who did not build the screens, each taking a group of areas: Today and its
companions; Calendar, Board, Study, Goals, Fasting and Review; Money, AI, Autonomy and Activity; Ask, Settings and
the system pages; and the small "places" under Ask (Life admin, People, Errands, Kids, School, Car…). Each looked at
the folded (cover) and open-Fold (inner) shots, dark and light, empty / one / many, against the approved mockups in
`docs/design/` where one exists and against the layout rules (build plan, Money's rules (a)–(f), now app-wide).
The build run spot-checked the two most surprising findings in the images (voice notes' clipped first letters, the AI
spend total) and both are real. **Not covered:** the Mac (the macos job's UI screenshots aren't downloadable from the
build environment; the Mac pass waits on the Mac review item and his screenshots).

Findings fall in two groups. **A** are faults (wrong or contradictory numbers, clipped or covered text, an address
shown as a name, a state that lies): fixed by build runs without a mockup, as the "Found and fixing" item on the board.
**B** are breaks of the layout rules that change how a page looks: they feed the whole-app layout review (the
"Navilife look" mockup first, then area mockups for Meka's yes), and nothing changes on screen for them before his yes.

## A · Found and fixing (no mockup needed), ranked

1. **Voice notes clip the first letter of lines** (high). "Today" reads "ιoday", also "Yesterday", "Thu", "answer",
   "the shed", "Nothing to add": the first glyph of a card's line is cut at the card's inner edge
   (`voice-notes/cover-dark-many.png`, inner and light the same). Lines starting "Mon" are whole.
2. **An email address shown as an account's name** (high; rule (6)/(i) of the Week board item, still open here).
   Settings → Calendars cards ("Google · alex@example.com", "Access expired for alex.work@…"), Setup ("Connected ·
   alex@example.com") and Health (calendar rows). Show the provider and the calendar's or account's own name, or his
   nickname for it.
3. **Today's capture field covers the last Anytime row** (high; `today/cover-dark-many.png`, light, inner): pad the
   list so nothing sits under it. Same screen: the timeline says "5 anytime tasks" while ANYTIME TODAY says 4; the empty
   day's now-row ends in a stray dash.
4. **Money's numbers that contradict** (high): Plan's What if I save says £1,100 a month leaves about £1,120 spare
   while Ask Obi says £1,150 leaves about £1,130 (saving more leaves more); the What-if sheet says "+£13,340 by Apr
   2027 · 6 months" over a card saying £6,600 for the same 6 months; the idea sheet and the What-if sheet open on top of
   each other (`money/cover-dark-plan-whatif.png`, `cover-dark-ask-obi-answer.png`). Accounts: with no balance on either
   account the head reads "Across 2 accounts £42,000" (only the typed pension), "Outside £42,000" twice, "Partial" with
   nothing counted; card rows read "Due ? · tap to add" with a literal "?" (Accounts and Bills). Trading: the old
   Dollars | Pounds row and its rate paragraph still show in one state, "Read-only" up to three times, "Reading it from
   Hyperliquid…" twice, the empty "Add your Hyperliquid address" subtitle cut ("pu…").
5. **AI's spend heading doesn't add up** (med): "This month by feature · $18.30 in all" over rows worth about $2.82
   (`ai/cover-dark-many.png`). The heading uses the month's whole spend; the rows only what is split by feature. Add the
   rest as its own row ("Not split by feature · $15.48") or head with the rows' sum.
6. **The open Fold's Board with a full week** (high; `board/inner-dark-many.png`, light): Sat/Sun wrap under Mon/Tue
   and their cards are clipped at the bottom ("Renew the car insurance" cut mid-word), black space under Wed–Fri, titles
   wrapping to 3 lines. Day pills read "planned – / 2h", "– / 12h" and "done none"; Fri's pill sits higher than Wed/Thu's
   (`inner-dark-dense.png`).
7. **Health says things that aren't so** (med): "Call assistant · Off" with a green dot; "5 things need a look" in both
   the connected and not-connected states though the rows differ; the Battery row's Keep Navilife awake button squeezes
   its text into 3 narrow lines.
8. **Setup's counts disagree** (med): "4 steps left · 0 of 4 done" over 9+ rows; elsewhere "14 steps left · 4 of 18
   done" and "21 of 21 done · 3 come later".
9. **Study's plan figures** (med; `study/cover-dark-plan.png`): "about 1 h 4 · 5 lessons left" (a cut-off length);
   "Fits: you picked 4 slots a week" with 2 chips lit.
10. **Clipped or covered on screen** (med): Tomorrow's edit sheet: the Repeat row under the Done/Tomorrow/Someday row
    and "Someday" cut at the right (`tomorrow/fold-light-edit.png`); the bedside clock: "UP NEXT" cut at the left, the
    same headline twice, headline cards bleeding off both edges (`bedside/stand-*-day.png`); Date night's time chips
    and Appointments' "Common ones" chips cut at the right edge; an appointment's title truncated by its Went/Remove
    column; Activity's 4th filter chip cut; Mail's last card cut flat at the bottom; the Week ahead's balance line
    leaving "h" alone on a line.
11. **States that look wrong** (low): Life admin and Date night "Off" with a time still lit; Watch's "That code doesn't
    match" under an empty field; Voice settings' On/Off rows in the opposite order for Face and Live (and 20–23 dp tall);
    the obi-face light shot is black (check whether intended); the calendar's agenda shot caught mid-swipe (reset the
    swipe in the test).
12. **Invented data that contradicts itself** (low, tests only, so the shots can be trusted): Car parked 14:44, "1 h 35
    ago", "Paid until 19:15 · 2 h left"; Kids "Nothing in the next 7 days" during half term; an INSET day inside half
    term; Reading's 45% field over a 37% bar; a child's shoe size 12 in Facts and 13 in Kids; the partner named like a
    child; the same trip on different dates in Trips and Trip lists; a "Done today" list all at 19:10.

## B · For the whole-app layout review (mockup and Meka's yes first)

1. **Open input fields on the page** on about 20 pages: Today's capture, Goals (up to 3 fields empty), Train queue,
   Calendars' weather town, Review, About you (the whole page is fields, 1900 px wide on the open Fold), Lists, Vault,
   Watch, Ask's search with help under it, Errands, Trips, Gifts, Bins, Reading, Leave, Emergency, Facts,
   Appointments, Kids (6 per child), School, Car, Dinners, Trip lists. Rule (e): a row or Add button opening a sheet.
2. **Open chip rows**: Work's 16 commute chips, Protected time's three groups, Study's plan card, Review's "Count as",
   Notifications (with −/+ steppers), Mail's meeting prep, Brief's length, Shutdown (Tomorrow/Someday under every
   leftover, mood, a cut-off length row), the Week ahead's heads-up, Life admin, Date night, Bins, Activity's filter,
   Money Plan's months and interest. One row with its value, choices in a sheet.
3. **Paragraphs of explanation**: Talk, Voice, Speaking up, AI (8 lines of "until it answers again"), Protected,
   Train queue, Goals, Calendars, Fasting (a 4-line note and the GP line mid-page), Recovery, Your data, Mail,
   Notifications (three), Voice notes, Activity, Autonomy, Brief, Needs you's mail note, and nearly every small place
   (2–3 each). Rule (c): ⓘ, one foot line.
4. **Sections not in cards / no small-caps label**: Today's timeline and Anytime (the approved calmer Today has them in
   cards and no "Keep Navilife awake" line under the greeting), Protected, Work, Goals, Review, the Calendar agenda,
   the Board's inner lists (big bold headings), Settings (13+ cards, no group labels), News, Lists, People, Family,
   Activity (each event its own card), Autonomy's lists.
5. **The open Fold is a stretched phone column** almost everywhere outside Money, Ask's grid and the Board: buttons
   ~1,600 px from their labels (Errands, Appointments, Kids, Autonomy, Notifications), News' source tile 1,100 px from
   its headline, Your data's full-width Export, Review's 900 px tiles, Shutdown, Brief, Week ahead, Fasting, Work,
   Life admin (a narrow centred column instead). One shared wide layout: two columns or a grid.
6. **Lists not capped at 5 + See all**: Dinners (7 day rows, empty ones boxed), Leave, Life admin (11), Vault, AI's
   8 cost rows, Fasting's stages and history, Activity.
7. **One skeleton for the small places**: header "Close" vs "‹ Trips", bold card titles only in Appointments, labels in
   different styles (Kids, Errands), five different add patterns, actions beside vs under the content.
8. **Type scale**: every page title very large, Protected's subtitle ~22 sp, Needs you's card title oversized, Goals'
   habits bold, Trading's risk line at tile size, Today's habit title bigger than its pane.
9. **Empty states**: the Board's four "Nothing …" headings, Review's metrics with no values, a "0" in Goals' ring,
   Bins/Reading/Emergency showing their inputs over a void, News' single grey line, Money's "nothing imported" twice.

**Looked fine:** light theme everywhere (no unthemed widgets); one sans typeface, no monospace or serif seen; the
folded Board close to its mockup; Spending close to its mockup on both sizes; Trading's tiles and alerts row; Ask's
grid on the open Fold; the recovery words grid; Gifts' and Trips' cards (bar the field and foot paragraph).
