# Money: the design beside the app

The approved Money mockup (`docs/design/money/*.dc.html`) drawn headless by `tools/design-render/render.mjs`
(`node tools/design-render/render.mjs docs/design/money/Phone-Bills.dc.html out.png`; a stand-in for the design tool's
runtime, web fonts off so a system sans stands in for DM Sans). Phone = 390 px wide (the closed Fold), Fold = 884 px (the
open Fold), Mac = 1280 px. Phone Plan is `Main.dc.html` (its default tab).

The app's shots are on the `screens` branch under `docs/screens/fold/money/` (Robolectric, invented data, one screen's
height): cover `cover-dark-many` (Spending), `cover-dark-bills`, `cover-dark-plan`, `cover-dark-trading`,
`cover-dark-accounts`; inner `inner-dark-columns-spending`, `-bills`, `-plan`, `-accounts`, `inner-dark-trading-layout`;
the `*-layout` shots scroll further down each tab. The Mac's come with the nightly macOS run.

| Design | App (screens branch) |
| --- | --- |
| `design-phone-<tab>.png` | `cover-dark-<tab>.png` (Spending: `cover-dark-many.png`) |
| `design-fold-<tab>.png` | `inner-dark-columns-<tab>.png` |
| `design-mac-<tab>.png` | the Mac (nightly) |

## Checked item by item (2026-10-28, a fresh reviewer, then Claude)

Some differences are Meka's own later rules (2026-10-26 22:18) and stay: every section is a card under a small caps
label; lists paginate; "read-only" once at the foot; Until payday, Cards and Refunds on Bills and "2026 so far" on
Accounts are his features the mockup predates.

**Fixed in this slice (both apps):**
1. The shots of Bills, Plan, Trading and Accounts were taken before the tab switched (empty bodies under "Spending"); the
   shot now waits for the tab, and fails if the chosen pill isn't the chosen one.
2. Header: "Money" with an **Updated 06:10** pill on its right (a tap asks the banks for a sync, "Updating…" while it
   waits); the "2 accounts · 23 transactions" line is gone (it shows only when locked or nothing is in yet); Close sits on
   the title row instead of a row of its own.
3. Tab pills: the chosen one has the brass tint, every label semibold; on the closed Fold the row runs to the screen's
   edges (a pill slides off the edge, never cut inside the margin). Mac: the side tabs the same.
4. Spending: "‹ October 2026 ›" centred over the ring; Categories · Needs vs wants moved from over the ring to the row over
   the category rows, with "All N ›" (no WHERE IT WENT label when the chips are there); the tiles' labels are whole
   ("Bills to come", stepping down on the closed Fold, never "Bills to").

**Still to do (next Money slices):**
5. Trading: the mockup leads with the equity card (value, change, chart, 1D/1W/1M/3M), 2×2 tiles, Obi's line, positions
   and Kestrel; the app's shot with no address leads with the set-up card (Dollars/Pounds, addresses, a long alerts
   line). Check the shot with an address in, and fold the alerts line into a row with a sheet.
6. Plan: OBI'S MONEY CHECK as the mockup's brass-bordered card with its label inside, the yearly total large, and the
   first idea as its own card with its step, Remind me and Not for me; the What if I save slider as a thin track with a
   round knob, smaller chips.
7. Accounts: a total card on top ("Across N accounts and trading", the total, a breakdown line) instead of the net worth
   card's set-up words; an account with no balance shows "Add balance" quietly, owed amounts in amber, no cut words.
8. Section labels in the mockup's style (small, letter-spaced caps); Spending's category notes against usual ("£20 under
   usual") where the months allow it rather than "3 payments".
9. Bills: the grey line under the still-to-go-out card; refunds' middle column aligned.
