package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Money on the open Fold: two columns as the mockup draws them at Fold size. */
class MoneyColumnsTest {
    private fun at(vararg keys: String) = keys.map { MoneyColumns.of(it) }

    @Test
    fun theOpenFoldIsWideAndTheCoverScreenIsNot() {
        assertTrue(MoneyColumns.wide(829))
        assertTrue(MoneyColumns.wide(600))
        assertFalse(MoneyColumns.wide(599))
        assertFalse(MoneyColumns.wide(344))
        assertFalse(MoneyColumns.wide(412))
    }

    @Test
    fun theHeaderTheSaidLineAndEachFootSpanBothColumns() {
        val full = listOf("close", "title", "summary", "opening", "unlock", "tabs", "ask-obi", "tab-empty-PLAN", "said",
            "foot", "spend-foot", "bills-foot", "plan-foot", "trading-foot")
        assertEquals(full.map { MoneyColumn.FULL }, at(*full.toTypedArray()))
        assertFalse(MoneyColumns.below("tabs"))
        assertFalse(MoneyColumns.below("ask-obi"))
        assertTrue(MoneyColumns.below("said"))
        assertTrue(MoneyColumns.below("foot"))
        assertTrue(MoneyColumns.below("plan-foot"))
    }

    @Test
    fun spendingHasTheRingOnTheLeftAndTheRowsAndPaymentsOnTheRight() {
        assertEquals(List(7) { MoneyColumn.START }, at("month", "needs-views", "ring", "tiles", "spent", "review-0", "alerts-head"))
        assertEquals(MoneyColumn.START, MoneyColumns.of("alert-x1"))
        assertEquals(List(9) { MoneyColumn.END },
            at("cats-head", "spend-card", "unsorted", "cat-groceries", "latest-head", "latest-card", "txns-head", "txn-t1", "txns-more"))
    }

    @Test
    fun billsPlanTradingAndAccountsFollowTheMockup() {
        assertEquals(List(5) { MoneyColumn.START }, at("bills-card", "payday-head", "payday", "cards-head", "cards"))
        assertEquals(List(4) { MoneyColumn.END }, at("bills-list", "refunds-head", "refunds", "regular-r1"))
        assertEquals(List(5) { MoneyColumn.START }, at("check-head", "check", "forecast", "challenges", "travel"))
        assertEquals(List(6) { MoneyColumn.END }, at("save-head", "save", "clear-head", "clear", "goals-head", "goals"))
        assertEquals(List(5) { MoneyColumn.START }, at("trading-head", "trading", "trading-risk", "trading-curve", "trading-review-head"))
        assertEquals(List(6) { MoneyColumn.END },
            at("trading-positions", "trading-kestrel-head", "trading-trades", "trading-addresses", "trading-alerts", "trading-export"))
        assertEquals(List(6) { MoneyColumn.START }, at("bank-head", "bank", "worth-head", "worth", "accounts-head", "accounts"))
        assertEquals(List(6) { MoneyColumn.END }, at("year-head", "year", "own-head", "own", "empty", "more"))
    }

    @Test
    fun anUnknownPartGoesLeftAndAJumpInsideTheColumnsScrollsToThem() {
        assertEquals(MoneyColumn.START, MoneyColumns.of("something-new"))
        assertEquals("clear-head", MoneyColumns.scrollKey("clear-head", wide = false))
        assertEquals(MoneyColumns.COLUMNS_KEY, MoneyColumns.scrollKey("clear-head", wide = true))
        assertEquals("tabs", MoneyColumns.scrollKey("tabs", wide = true))
    }

    @Test
    fun theMacsSideListHasItsLabelAndDrawsByTheSameKeys() {
        assertEquals("MONEY", MoneyColumns.SIDE_HEAD)
        // The Mac gates whole parts by these keys (a part's first key), so they must land as the Fold's do.
        assertEquals(List(4) { MoneyColumn.START }, at("travel-banner", "review", "alerts-head", "trading-review"))
        assertEquals(List(5) { MoneyColumn.END }, at("latest-head", "txns-head", "regular", "own", "trading-kestrel"))
    }

    private fun trading(positions: Int, wallets: Int) = TradingView(
        TradingTracker.HEAD, "$1,000.00", "Equity", emptyList(), "$positions open positions",
        (1..positions).map { TradingPositionRow("w:C$it", "C$it long", "", "+$1.00", true, null, false, "C$it") },
        (1..wallets).map { TradingWalletRow("w$it", "Main", "0xab…", false) }, null, TradingTracker.HINT, TradingTracker.FOOT, "spoken",
    )

    @Test
    fun tradingsLowerPartsGoUnderTheShorterColumn() {
        // Walk-through 2026-11-15 A17: five positions and Kestrel make the right the longer, so the addresses go left
        // instead of running on under them beside an empty left half.
        val busy = MoneyColumns.tradingFlow(trading(positions = 5, wallets = 1), kestrel = true)
        assertEquals(MoneyColumn.START, busy["trading-addresses"])
        assertEquals(MoneyColumn.START, MoneyColumns.of("trading-addresses-head", busy))
        assertEquals(MoneyColumn.START, MoneyColumns.of("trading-addresses", busy))
        // The mockup's parts stay where they were.
        assertEquals(MoneyColumn.END, MoneyColumns.of("trading-positions", busy))
        assertEquals(MoneyColumn.END, MoneyColumns.of("trading-kestrel-head", busy))
        assertEquals(MoneyColumn.START, MoneyColumns.of("trading", busy))
        assertEquals(MoneyColumn.FULL, MoneyColumns.of("trading-foot", busy))
        // Nothing on the right yet: the addresses start it, as before.
        val quiet = MoneyColumns.tradingFlow(trading(positions = 0, wallets = 1).copy(unrealisedLine = null), kestrel = false)
        assertEquals(MoneyColumn.END, quiet["trading-addresses"])
        // Parts that don't show aren't placed.
        assertEquals(setOf("trading-addresses"), quiet.keys)
    }

    @Test
    fun tradingsSplitIsTheSameOnEveryRedraw() {
        val t = trading(positions = 3, wallets = 2)
        assertEquals(MoneyColumns.tradingFlow(t, kestrel = true), MoneyColumns.tradingFlow(t, kestrel = true))
    }
}
