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
