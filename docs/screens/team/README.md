# Your team: the design beside the app

`beside-cover.png`: the approved mockup (`docs/design/routines/team.dc.html`, rendered at 390, left) beside the
`team/cover-dark-busy` shot (the closed Fold at 344 dp, right), both at 2 px per dp, the 16 dp gutter in cyan. The
data differs; the layout is what's compared.

## Checked item by item (2026-11-07)

1. ‹ / Close, the title and the line under it: **matches**.
2. WORKING · NEED YOU · DONE TODAY tiles, NEED YOU lit only while something waits: **matches**.
3. A card per helper: the icon tile, name, role, the status dot and word on the right: **matches**; at 344 dp a long
   role wraps to two lines where the 390 mockup has room for one.
4. TODAY and WAITS ON YOU boxes side by side, WAITS ON YOU lit when something waits: **matches**.
5. Ask the team… at the foot and the helper's sheet: as the mockup.

Nothing to change.

## Your team, simpler, beside `docs/design/routines/team-simpler.dc.html` (2026-11-14)

Rendered with `tools/design-render/render.mjs` at 390 (and with Inbox unfolded) beside the ci run 1846 shots
`team/cover-dark-busy` and `team/cover-dark-unfolded` (`beside.py`, 2 px per dp). Gaps found and fixed on the Fold:

1. "Waiting on you" / "Helpers" titles and each helper's name were the 19 sp item title; the mockup is 15 semibold
   (now `metaStrong`). The status word beside the dot was 13 sp; mockup 11.5 (now `micro`, 12).
2. A waiting row's words were 17 sp body; mockup 14 (now `itemMeta`, 15).
3. An unfolded helper's Open · Pause · Whole day › wrapped onto two rows on the cover screen; the mockup has one row
   of small pills (now caption words with 8 dp sides, Whole day › bare; the test fails if they wrap).
4. The busy shot had nothing waiting, so the Waiting on you box was never drawn; it now has a request card waiting.

Kept on purpose: Ask the team… is the same bar as Money's (the orb at its left, a plain mic), so the two read alike.
The Mac's Team sheet already sits at the mockup's sizes on its own type scale (15 medium names, 11 status, 12 pills).

After the fixes (ci run 1848 at 029e8a6): `beside-simpler-busy.png` and `beside-simpler-unfolded.png` (mockup left, app
right, 1 px per dp). The sizes, the Waiting on you box and the one row of pills match; nothing left to change.
