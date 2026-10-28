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

**Item 5, slice 1 (2026-10-28, both apps):** the equity card leads as the mockup — "Hyperliquid · Main 0x…" and
Read-only inside it, the value large with "≈ £…" beside it (a tap switches the currency), the change over 1D · 1W · 1M
(the month first) and its P&L line inside the card, "All time … · updated …" under the chips. The separate P&L card and
the Dollars · Pounds row left the page (what the currency means is behind the foot's ⓘ). No 3M chip: the server keeps a
month of day ends. App shots: `cover-dark-trading-equity`, `inner-dark-trading-equity`.

**Item 5, rest (2026-10-28, both apps):** the month's 2×2 tiles under the equity card (Closed profit, Funding paid or
received, Fees, Winning trades "14 of 23"), labelled LAST 30 DAYS rather than the mockup's "October so far" because the
server adds trades up over 30 days; YOUR TRADES no longer repeats them (it keeps After fees and funding, the averages and
each coin). ALERTS is one row ("All 4 alerts on" over each one's choice) opening a sheet (Mac: a popover) with the
choices. App shots: `cover-dark-trading-tiles`, `cover-dark-trading-alerts-sheet`, `inner-dark-trading-tiles`.

**Item 6 (2026-10-28, both apps):** OBI'S MONEY CHECK as the mockup's brass-bordered card (a warm accent tint, a thin
accent line) with its label inside, the yearly total in the greeting size over "from N ideas" and "Written by Obi · …"
with Ask Obi to look again; the biggest idea as its own card (title · figure, why, its step, Remind me, Not for me; a tap
on its title opens the payments behind it); the rest as one-line rows in a card with "N more ›" and "N put away". What
if I save's (and Clear my cards') slider is a thin 4 dp track with a round 20 dp knob (Mac: the small control size), and
the chips are drawn small inside a 48 dp target. App shot: `cover-dark-plan-layout`.

**Item 7 (2026-10-28, both apps):** Accounts opens on the mockup's total card — "Across 6 accounts and trading" inside it,
the total in the greeting size and one breakdown line ("Cash £5,924 · trading £6,512 · owed on cards £6,686", plus
"outside £…" for what he typed); the NET WORTH label, "You have … · you owe …" and the set-up sentence left (before
anything counts: "Add each account's balance to see one total"). The change since last month, the month-by-month line,
"Partial · …" and Outside these accounts stay in the card under it. In the list an owed amount is amber (the brass
accent, was blue), Add balance is quiet (secondary text, was the accent), and names and lines wrap instead of being cut.
App shot: `cover-dark-accounts-total`.

**Item 8 (2026-10-28, both apps):** every Money card label in the mockup's style: small, letter-spaced caps (12 sp,
0.14 em; Mac 11 pt) at regular weight in the secondary text colour (was a dim grey 13 sp caption), the money check's in
brass; a new `cardLabel` type token. Spending's category notes say how each compares with the usual month ("£20 under
usual" green, "£110 over usual" amber, "as usual"): this month against the usual pace by today from the 7th, bills,
subscriptions and fees against the whole usual month (over only once past it); one month before names it ("£20 under
September"); none counts the payments; Other says "14 without a category". Core MoneySpendNote.

**Item 9 (2026-10-28, both apps):** Bills' "£… of £… this month already paid" under the still-to-go-out bar is in the
head's secondary grey as the mockup (was the dim tertiary); each refund on the way puts its state under its name (as the
cards' rows), so every state starts on the same left edge instead of a middle column that moved with each name, and a
long name wraps to two lines. App shot: `cover-dark-bills-layout`.

Every item of this check has landed; each tab now waits for Meka's yes beside the mockup.
