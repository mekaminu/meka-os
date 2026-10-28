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
 *    regular payments on the right.
 *  - Plan: Obi's money check, the forecast, challenges and paying abroad on the left; What if I save…, Clear my cards
 *    and the goals on the right.
 *  - Trading: equity, Obi's risk line, the P&L line and the month on the left; open positions, Kestrel, the trades, the
 *    addresses, alerts and the tax export on the right.
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
        "save", "clear", "goals",
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

    /** Whether the full-width part [key] comes after the columns (the said line and the tab's foot) rather than above. */
    fun below(key: String): Boolean = key == "said" || key == "foot" || key.endsWith("-foot")
}
