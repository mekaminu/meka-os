# Obi on calls: the design beside the app

`beside-cover.png`: the approved mockup (`docs/design/calls/settings.dc.html`, rendered at 390 by
`tools/design-render/render.mjs`, left) beside the `obi-calls/cover-dark` shot from before this check (the closed Fold
at 344 dp, right), both at 2 px per dp with a faint line every 8 dp and the 16 dp gutter in cyan
(`tools/design-render/beside.py`). The data differs (the mockup has a month's calls); the layout is what's compared.

## Checked item by item (2026-11-07)

1. ‹ back, the title and the line under it: the title was the smaller card-title size: **fixed**, now the screen
   title size the other pages use (Fold and Mac).
2. THIS MONTH: label and cap, SPENT · CALLS · BOOKED tiles, the bar, £10 · £20 · £30 · £50, the cap note:
   **matches**; the tiles' figures were regular weight where the mockup's are bold: **fixed** (semibold).
3. WHEN SOMEONE CALLS YOU: the mockup has each switch in its own card with a sliding switch; the app had one card with
   On pills and a spend line under Live conversation repeating THIS MONTH: **fixed** on the Fold and the Mac (a card
   each, the sliding switch Routines uses, now shared as `SlideSwitch`; Live's line shows only when it says something
   else: off, at the cap, the assistant off).
4. WHAT CALLERS MAY KNOW: the chips and "Never shared…" sit in a card on the mockup, loose on the app: **fixed**.
5. BOOKING CALLS limits as four tiles: **matches** (figures bold, as 2).
6. RECORDINGS: chips and the note in a card on the mockup: **fixed**. The app offers Don't keep · 7 days (no
   30 days: Meka dropped it on 2026-10-20, recordings are kept 7 days at most).
7. The footer "Obi always says he's your AI assistant": **matches**.
8. The selected cap chip is solid brass on the app, a tint on the mockup; the mockup's own recordings chip is solid,
   so the app keeps one style for both.

The Fold's shots after this change are in the `screens` branch (`docs/screens/fold/obi-calls/`) after CI.
