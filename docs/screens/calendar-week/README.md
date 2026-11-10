# Calendar → Week: the design beside the app

The approved Option A mockups (`docs/design/calendar-week/WeekOpen.dc.html`, `WeekFolded.dc.html`) rendered with
`tools/design-render/render.mjs`, beside the Fold's shots from CI run 1701 (c69cb72; Robolectric, invented events,
Wednesday 21 Oct). These are the shots *before* the fixes below; the fixed ones land on the `screens` branch
(`fold/calendar/week-*.png`) with the next green run.

| Design | App |
| --- | --- |
| WeekOpen, 884 wide | `beside-open-before.png` (open Fold, stacked) |
| WeekFolded, 390 wide | `beside-folded-before.png` (folded) |

## Checked item by item (2026-11-10)

1. Header (month, week, Week · Board · Agenda, 5 · 7 days, Today, ‹ ›), the key and "Booked this week": **matches**.
2. Square blocks, 2 dp calendar edge, 11 sp regular titles, the amber dot and outline on a work clash: **matches**.
3. Short blocks: the mockup puts title and time on one line when two won't fit. The app drew two lines in a 30 min
   (18 dp) and a 45 min (28 dp) block and lost both lines to the clip ("Dentist", "Call with Priya"). **Fixed**: one
   line whenever the block is under 33 dp at that grid's hour height (core `WeekBlock.oneLine(hourDp)`), with tighter
   13/12 sp lines so an hour block holds both; Fold and Mac.
4. Hour labels: the mockups centre each on its line and the folded one reads "09", not "09:00". The app sat them under
   the line and the folded 40 dp column overflowed "08:00". **Fixed** (core `hourLabelTopDp`, `foldedHours`).
5. The all-day label: right-aligned "all day" open, blank folded in the mockup; the app showed a cut "all" folded and a
   left-aligned label open. **Fixed** (core `allDayLabel`).
6. Work band, the gold now line, today's gold date, task chips with the teal edge: **matches**.
7. Not in the app yet: the folded week strip's small load bar under each day (the mockup's bars). Left for the Week
   board's leftovers, as it is the same strip.
