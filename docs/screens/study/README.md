# Study: the design beside the app

`beside-cover.png`: the approved mockup (`docs/design/study/study-plan.dc.html`, rendered at 390 by
`tools/design-render/render.mjs`, left) beside CI run 1454's `study/cover-dark-plan` (the closed Fold at 344 dp, right),
both at 2 px per dp with a faint line every 8 dp and the 16 dp gutter in cyan (`tools/design-render/beside.py`).
The data differs (the mockup's course, the shots' invented one); the layout is what's compared.

## Checked item by item (2026-10-30)

1. The ring, the title, the lessons line and "On track for …": **matches** (the app adds "Next: <lesson>").
2. YOUR PLAN 16 dp inside: **matches** since this check (was 24).
3. Finish by, two dates to a row: **matches** since this check (on the cover a date drops its weekday, "23 Nov · 4 wks").
4. Each session and When you can study chips, "N sessions a week" and the fit line: **matches**; the chips keep a
   48 dp touch target, so their rows sit a little further apart than the mockup's.
5. LESSONS: the modules, then a dashed "+ Add lessons · paste the course outline" row that opens the field:
   **matches** since this check (the field was always open at the top); see `study/cover-dark-add-lessons` with it open.
6. THIS WEEK stays a card (the app's layout rules), its rows and Start as the mockup.
