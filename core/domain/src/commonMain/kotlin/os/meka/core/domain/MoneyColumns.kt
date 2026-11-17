package os.meka.core.domain

/** Where a part of a Money tab sits once the screen is wide enough for two columns. */
enum class MoneyColumn { FULL, START, END }

/**
 * Money on the open Fold (Money redesign, daily plan Wed 28 Oct: "tabs, Spending, Bills, Accounts at all three sizes";
 * approved mockup `docs/design/money/`, Fold-*.dc.html): on a screen at least [WIDE_DP] wide the tab's body sits in two
 * columns, as the mockup draws it at 884 px, instead of one long narrow list. The title, the summary, the tab pills, the
 * Ask Obi bar, the empty line, the said line and each tab's foot stay full width ([FULL]) above and below the columns.
 *
 * The columns follow the mockup:
 *  - Spending: the month switcher, the ring and its tiles, the review and Worth a look on the left; WHERE IT WENT (the
 *    category rows, "Sort them") and the latest payments on the right.
 *  - Bills: the still-to-go-out card, Until payday and the cards on the left; the month's bills by date, refunds and the
 *    regular payments on the right; balanced like Plan ([flow]).
 *  - Plan: Obi's money check, the forecast, challenges and paying abroad on the left; What if I save…, Clear my cards
 *    and the goals on the right; a part goes under the other column when its own is already much longer ([flow]).
 *  - Trading: equity, the month's tiles, Obi's risk line and the P&L line on the left; open positions and Kestrel on the
 *    right; then the trades, the month in review, the addresses, alerts and the tax export each go under whichever
 *    column is shorter so far ([tradingFlow], walk-through 2026-11-15 A17: the right ran on alone below an empty left).
 *  - Accounts: his banks, net worth and the accounts on the left; the year, his own categories and MORE on the right.
 *
 * A part is named by the key the Fold's list gives it (a section's head and its card share a stem: "save-head", "save");
 * an unknown part goes on the left, so a new section is never lost. Layout only: nothing is stored, nothing moves tab.
 */
object MoneyColumns {
    /** The narrowest pane that gets two columns (the open Fold is ~840 dp; the closed one ~344–412 dp). */
    const val WIDE_DP = 600

    /**
     * The small caps label over the tabs where they run down the side instead of pills (the Mac's wider Money window,
     * Mac-*.dc.html; the Mac draws by the same keys, so both apps split each tab alike).
     */
    const val SIDE_HEAD = "MONEY"

    /** The key of the one list item that holds both columns (what a jump to a part inside them scrolls to). */
    const val COLUMNS_KEY = "money-columns"

    /** Whether a pane [widthDp] wide shows two columns. */
    fun wide(widthDp: Int): Boolean = widthDp >= WIDE_DP

    private val FULL_PARTS = setOf(
        "close", "title", "summary", "opening", "unlock", "tabs", "ask-obi", "said", "foot",
        "spend-foot", "bills-foot", "plan-foot", "trading-foot",
    )

    private val END_STEMS = listOf(
        // Spending
        "cats", "cat", "spend-card", "unsorted", "latest", "txns", "txn",
        // Bills
        "bills-list", "refunds", "regular",
        // Plan
        "save", "clear", "goals", "spend-plans",
        // Trading
        "trading-positions", "trading-kestrel", "trading-trades", "trading-addresses", "trading-alerts", "trading-export",
        // Accounts
        "year", "own", "empty", "more",
    )

    /** The column the part keyed [key] sits in on a wide screen. */
    fun of(key: String): MoneyColumn = when {
        key in FULL_PARTS || key.startsWith("tab-empty-") -> MoneyColumn.FULL
        END_STEMS.any { key == it || key.startsWith("$it-") } -> MoneyColumn.END
        else -> MoneyColumn.START
    }

    /**
     * The list item to scroll to for part [key]: itself when the list is one column (or the part is full width), else
     * the item holding the columns, whose top is where both columns start.
     */
    fun scrollKey(key: String, wide: Boolean): String = if (!wide || of(key) == MoneyColumn.FULL) key else COLUMNS_KEY

    /** Trading's lower parts, in the list's order, that sit under whichever column is shorter (a head goes with its card). */
    val TRADING_FLOW = listOf("trading-trades", "trading-review", "trading-addresses", "trading-alerts", "trading-export")

    /**
     * Where Trading's lower parts go on a wide screen (walk-through 2026-11-15 A17, the open Fold's trading-layout shot:
     * equity, the tiles and Obi's line ended a third of the way down while positions, Kestrel, the trades, the addresses,
     * alerts and the export ran on down the right beside an empty left half). Each part of [TRADING_FLOW] that shows goes
     * under the column that is shorter so far, a tie to the right as the mockup; heights are rough line counts worked out
     * from [t] (and whether Kestrel's card shows, [kestrel]), the same on both apps, so the split never jumps on a redraw.
     * Layout only.
     */
    fun tradingFlow(t: TradingView, kestrel: Boolean): Map<String, MoneyColumn> {
        var left = 0
        var right = 0
        if (MoneyTradingLayout.showHead(t)) left += 1
        left += if (t.equity != null) 12 else 4 + t.pnl.size
        t.tiles?.let { left += 1 + 5 }
        t.risk?.let { left += 4 }
        t.curve?.takeIf { MoneyTradingLayout.showCurveCard(t) }?.let { left += 1 + 8 }
        if (MoneyTradingLayout.hasPositions(t)) right += 1 + 3 * t.positions.size.coerceIn(1, 5) + (if (t.positions.size > 5) 1 else 0)
        if (kestrel) right += 1 + 6
        val heights = linkedMapOf<String, Int>()
        TradingTiles.trades(t)?.let { tr ->
            heights["trading-trades"] = 1 + 2 + tr.lines.size + (if (tr.summary != null) 1 else 0) + listOfNotNull(tr.averageLine, tr.bestLine, tr.moreLine, tr.empty).size +
                2 * tr.coins.size
        }
        t.review?.let { r -> heights["trading-review"] = 1 + 3 + 2 * r.sentences.size }
        heights["trading-addresses"] = 1 + 2 + 2 * t.wallets.size
        t.alerts?.let { heights["trading-alerts"] = 1 + 2 }
        t.export?.let { heights["trading-export"] = 1 + 3 }
        return heights.mapValues { (_, h) ->
            if (left < right) { left += h; MoneyColumn.START } else { right += h; MoneyColumn.END }
        }
    }

    /**
     * The column the part keyed [key] sits in, once a tab's movable parts are placed by [flow] ([flow], [tradingFlow]):
     * a part's head and its rows go with it ("regular-head", "regular-sky" follow "regular").
     */
    fun of(key: String, flow: Map<String, MoneyColumn>): MoneyColumn =
        flow[key] ?: flow[key.removeSuffix("-head")] ?: flow.entries.firstOrNull { key.startsWith("${it.key}-") }?.value ?: of(key)

    /** A part of a tab that may change column: its key's stem, the column the mockup gives it, and its rough height in lines. */
    data class FlowPart(val stem: String, val home: MoneyColumn, val lines: Int)

    /**
     * How far (in rough lines) a column may run on past the other before the next part goes to the other side. Below
     * it every part keeps the mockup's column.
     */
    const val SLACK_LINES = 4

    /**
     * Places [parts] (in the list's order) in their mockup column unless that column is already more than [SLACK_LINES]
     * longer than the other, when the part goes under the other instead (and when none of the left's own parts show, the
     * first part starts the left) (walk-through 2026-11-15 A17: Plan's left
     * column empty beside Clear my cards and the goals, Bills' right run on alone). The same on every redraw and both apps.
     */
    fun balance(parts: List<FlowPart>): Map<String, MoneyColumn> {
        var left = 0
        var right = 0
        val out = linkedMapOf<String, MoneyColumn>()
        // Nothing of the left's own shows (Bills with only refunds and regular payments): the first part starts it.
        val allRight = parts.isNotEmpty() && parts.all { it.home == MoneyColumn.END }
        parts.forEachIndexed { i, p ->
            val homeLeft = p.home != MoneyColumn.END || (allRight && i == 0)
            val (home, other) = if (homeLeft) left to right else right to left
            val toLeft = if (home - other > SLACK_LINES) !homeLeft else homeLeft
            if (toLeft) left += p.lines else right += p.lines
            out[p.stem] = if (toLeft) MoneyColumn.START else MoneyColumn.END
        }
        return out
    }

    /**
     * Where the movable parts of [tab] go on a wide screen, from what [v] shows (Kestrel's card counts on Trading when
     * [kestrel]): Trading by [tradingFlow]; Plan's and Bills' parts by [balance] with rough heights; nothing moves on
     * the other tabs. Layout only.
     */
    fun flow(tab: MoneyTab, v: MoneyView, kestrel: Boolean = false): Map<String, MoneyColumn> {
        if (v.locked || v.skeleton) return emptyMap()
        fun has(s: MoneySection) = MoneyTabs.shows(tab, s, v)
        val L = MoneyColumn.START
        val R = MoneyColumn.END
        return when (tab) {
            MoneyTab.TRADING -> if (has(MoneySection.TRADING)) v.trading?.let { tradingFlow(it, kestrel) } ?: emptyMap() else emptyMap()
            MoneyTab.PLAN -> balance(listOfNotNull(
                v.check?.takeIf { has(MoneySection.TIPS) }?.let { c ->
                    FlowPart("check", L, 4 + if (c.ideas.isEmpty()) 1 else 6 + (c.ideas.size - 1).coerceIn(0, 3))
                },
                v.save?.takeIf { has(MoneySection.SAVE) }?.let { FlowPart("save", R, 1 + 9) },
                v.clear?.takeIf { has(MoneySection.CLEAR) }?.let { b -> FlowPart("clear", R, 1 + 6 + 2 * b.cards.size) },
                v.goals?.takeIf { has(MoneySection.GOALS) }?.let { g -> FlowPart("goals", R, 1 + maxOf(4, 3 * g.rows.size)) },
                v.forecast?.takeIf { has(MoneySection.FORECAST) }?.let { f -> FlowPart("forecast", L, 1 + if (f.notReady != null) 2 else 10) },
                v.challenges?.takeIf { has(MoneySection.CHALLENGES) }?.let { ch -> FlowPart("challenges", L, 1 + 6 + 2 * ch.caps.size) },
                v.travel?.takeIf { has(MoneySection.TRAVEL) }?.let { t -> FlowPart("travel", L, 1 + if (t.rows.isEmpty()) 3 else 4 + 2 * t.rows.size) },
                FlowPart("spend-plans", R, 2),
            ))
            MoneyTab.BILLS -> balance(listOfNotNull(
                v.bills?.takeIf { has(MoneySection.BILLS) }?.let { FlowPart("bills-card", L, 5) },
                v.bills?.takeIf { has(MoneySection.BILLS) && it.rows.isNotEmpty() }?.let { b ->
                    FlowPart("bills-list", R, 2 * minOf(b.rows.size, MoneyBills.FOLDED) + (if (b.rows.size > MoneyBills.FOLDED) 1 else 0))
                },
                v.payday?.takeIf { has(MoneySection.PAYDAY) }?.let { FlowPart("payday", L, 1 + 2) },
                v.cards?.takeIf { has(MoneySection.CARDS) }?.let { c -> FlowPart("cards", L, 1 + 2 * (c.rows.size + c.offers.size).coerceAtLeast(1)) },
                v.refunds?.takeIf { has(MoneySection.REFUNDS) }?.let { r ->
                    FlowPart("refunds", R, 1 + 2 * minOf(r.rows.size, MoneyRefunds.SHOWN) + (if (r.rows.size > MoneyRefunds.SHOWN) 1 else 0))
                },
                v.regular?.takeIf { has(MoneySection.REGULAR) }?.let { r -> FlowPart("regular", R, 2 + 2 * r.rows.size) },
            ))
            else -> emptyMap()
        }
    }

    /**
     * Whether the bills list (Bills' first part on the right, no label of its own as the mockup) needs the card's top gap so
     * the two cards start level (walk-through 2026-11-15 C g2: its top sat ~40 px above the left card's).
     */
    fun billsListLevel(wide: Boolean, flow: Map<String, MoneyColumn>): Boolean = wide && of("bills-list", flow) == MoneyColumn.END

    /** Whether the full-width part [key] comes after the columns (the said line and the tab's foot) rather than above. */
    fun below(key: String): Boolean = key == "said" || key == "foot" || key.endsWith("-foot")
}
