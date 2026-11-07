# Routines: the design beside the app

`beside-cover-list.png`: the approved mockup (`docs/design/routines/routines.dc.html`, rendered at 390 by
`tools/design-render/render.mjs`, left) beside the `routines/cover-dark-many` shot from before this check (the closed
Fold at 344 dp, right), both at 2 px per dp with a faint line every 8 dp and the 16 dp gutter in cyan
(`tools/design-render/beside.py`). The data differs (the mockup has a run going); the layout is what's compared.

## Checked item by item (2026-11-07)

1. ‹ / Close, the title and + New beside it: **matches**.
2. The line under the title: the mockup says what Routines is, the app how many are on ("2 on · 1 paused"): kept
   (numbers, not sentences). The rule ("never sends, pays, books or trades") sat open under it in three lines: **moved
   to the foot**, said once.
3. RUNNING NOW: label, name, bar, the step waiting on you, Carry on · Skip this step: **matches** (the app adds Stop).
4. YOUR ROUTINES rows: the mockup's are one compact row (tile · name · steps on one line · a switch). The app's were
   tall cards with the line wrapping, "Next: …", Remove and an On pill: **fixed** on the Fold and the Mac: the name
   semibold on one line, the steps on one line (cut with "…"), a sliding switch on the Fold (the Mac's own
   switch), the next run and Remove on the routine's page (both were there already).
5. The day tile, "SUN" over "19:00": **matches** (the app's day in brass while on).
6. IDEAS FROM YOUR WEEK, dashed tiles two to a row: **matches**.
7. START WITH ONE (starters not added yet) isn't on the mockup, which shows all four added: kept as cards with Add.

The Fold's shots after this change are in the `screens` branch (`docs/screens/fold/routines/`) after CI.
