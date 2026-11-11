# Today: the design beside the app

`beside-cover.png`: the approved calmer mockup (`docs/design/today/today-calmer.dc.html`, rendered at 390 by
`tools/design-render/render.mjs`, left) beside CI run 1447's `today/cover-dark-many` (the closed Fold at 344 dp, right),
both at 2 px per dp with a faint line every 8 dp and the 16 dp gutter in cyan (`tools/design-render/beside.py`).
The data differs (the mockup's Sunday, the shots' invented Wednesday with a work block); the layout is what's compared.

## Checked item by item (2026-10-29)

1. Greeting, date, Search · Plan my day, the slim update banner: **matches**.
2. Up next: label and why on one row, a 17 sp title, 36 dp pills (Done filled, the rest a step lighter than the card):
   **matches** since this check (the quiet pills were black on the grey card).
3. Section labels in 12 sp spaced caps, "ANYTIME TODAY · 4" with the count: **matches** since this check.
4. Task rows: a 22 dp tick in a 44 dp target, 16 sp titles, a 48 dp row with a faint rule between rows: **matches** since
   this check (rows were 60 dp plus the list's gap, no rule; the app's pitch is now 56 dp, the list's 8 dp gap kept).
5. A row's line ("15 min") in the 13 sp caption: the mockup shows none; the app keeps it, now at the calmer scale.
6. "2 done today ›" and the capture bar: **matches**.

## The Schedule card (Today regression 3, 2026-11-11)

`beside-schedule-folded.png` (the closed Fold) and `beside-schedule-open.png` (the open Fold, stacked): the approved
`docs/design/day-list/TodayNew.dc.html` at 390 beside CI run 1754's `today/cover-dark-schedule` and
`inner-dark-schedule` (a Wednesday at 17:15: all day, one earlier, Work, the now line, the commute home, Training).

1. One card, rows parted by a hairline: **matches** since this check. Run 1752's shots showed black stripes between
   the rows (each row is a list item, 8 dp from the next); the rows now bridge the gap with the card's surface.
2. No oval: Work is one dim line; "Make it a task" on an all-day to-do lost its pill outline in the card: **matches**.
3. Time column, "All day" in the first row's time, "1 earlier ›", the gold now time: **matches**.
4. The label: the mockup says "SCHEDULE"; the app adds its summary (two lines folded). Kept.
5. The calendar colour: the mockup's 7 dp dot at the row's end; the app's 2 dp edge after the time (Meka asked for it).
