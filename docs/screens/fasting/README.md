# Fasting: the design beside the app

`beside-cover.png`: the approved mockup (`docs/design/fasting/fasting.dc.html`, rendered at 390 by
`tools/design-render/render.mjs`, left) beside CI run 1458's `fasting/cover-dark-one` (the closed Fold at 344 dp,
right); `beside-cover-below.png`: the mockup's lower half beside `fasting/cover-dark-many-below`. Both at 2 px per dp
with a faint line every 8 dp and the 16 dp gutter in cyan (`tools/design-render/beside.py`). The data differs (the
mockup's week, the shots' invented one); the layout is what's compared. The Today card (`today-card.dc.html`) beside
`today/cover-dark-fasting` already matched.

## Checked item by item (2026-10-30)

1. Back link and the 16:8 chip: **match**.
2. The title at 28 sp: **matches** since this check (was the 34 sp greeting).
3. The time in the ring at 46 sp bold: **matches** since this check (was 34 sp).
4. Started and Goal tiles: **match**; the pencil sits beside the label so the value keeps the tile's width.
5. End fast and More options: **match** (the prompt reads "Started by mistake?" while fasting).
6. What's happening now: **matches**.
7. The stages, each row the 48 dp target 8 dp apart under their label, the note after them: **match** since this
   check (were 64 dp rows 16 dp apart).
8. This week: **matches**.
9. History, one line per fast (span · length · goal, "6h short"): **matches** since this check (the length was stacked
   over the goal word in a 64 dp row).
