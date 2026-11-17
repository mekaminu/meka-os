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

    private fun plan(check: Boolean, clearCards: Int, goals: Int) = MoneyView(
        locked = false, summary = "", empty = null,
        month = MoneyMonth(0, "October so far", "Spent £100", null, false, emptyList(), emptyList(), 0),
        accounts = emptyList(), earlier = false, later = false,
        check = if (check) MoneyCheckView(null, "from 0 ideas", emptyList(), "Nothing to look at just now", null) else null,
        save = MoneySaveBase(null, 10, 5_000),
        clear = if (clearCards > 0) MoneyClearBase((1..clearCards).map { MoneyClearCard("c$it", "Card $it", 10_000, 2_290, false) }, null, 10, 1_000, 50_000, 10_000) else null,
        goals = MoneyGoalsView("GOALS", (1..goals).map { MoneyGoalRow("g$it", "Goal $it", "£10 of £100", 10, null, emptyList(), "Goal $it") }, null, ""),
    )

    @Test
    fun plansPartsGoUnderTheOtherColumnWhenTheirsIsMuchLonger() {
        // Walk-through 2026-11-15 A17: no money check, so What if I save, Clear my cards and the goals all sat on the
        // right beside an empty left. With none of the left's own, What if I save starts the left, Clear my cards and
        // the goals keep the right, and Spending plans goes left once the right is well ahead.
        val bare = MoneyColumns.flow(MoneyTab.PLAN, plan(check = false, clearCards = 2, goals = 2))
        assertEquals(MoneyColumn.START, bare["save"])
        assertEquals(MoneyColumn.START, MoneyColumns.of("save-head", bare))
        assertEquals(MoneyColumn.END, bare["clear"])
        assertEquals(MoneyColumn.END, MoneyColumns.of("goals-head", bare))
        assertEquals(MoneyColumn.START, bare["spend-plans"])
        // Every part's head follows it, and the rest keep their mockup column while the two are close.
        val left = bare.filterValues { it == MoneyColumn.START }.keys
        val right = bare.filterValues { it == MoneyColumn.END }.keys
        assertTrue(left.isNotEmpty() && right.isNotEmpty())
        // The money check leads the left, and with it in place the mockup's split holds for save and clear.
        val full = MoneyColumns.flow(MoneyTab.PLAN, plan(check = true, clearCards = 1, goals = 0))
        assertEquals(MoneyColumn.START, full["check"])
        assertEquals(MoneyColumn.END, full["save"])
        // Same view, same split.
        assertEquals(full, MoneyColumns.flow(MoneyTab.PLAN, plan(check = true, clearCards = 1, goals = 0)))
        // Other tabs and a locked Money move nothing.
        assertEquals(emptyMap(), MoneyColumns.flow(MoneyTab.SPENDING, plan(check = true, clearCards = 1, goals = 0)))
        assertEquals(emptyMap(), MoneyColumns.flow(MoneyTab.PLAN, plan(true, 1, 0).copy(locked = true)))
    }

    @Test
    fun balanceKeepsTheMockupWhileTheColumnsAreClose() {
        val L = MoneyColumn.START
        val R = MoneyColumn.END
        fun p(stem: String, home: MoneyColumn, lines: Int) = MoneyColumns.FlowPart(stem, home, lines)
        assertEquals(mapOf("a" to L, "b" to R, "c" to L, "d" to R), MoneyColumns.balance(listOf(p("a", L, 5), p("b", R, 6), p("c", L, 5), p("d", R, 6))))
        // The right runs well ahead: the next right part goes left.
        assertEquals(L, MoneyColumns.balance(listOf(p("a", L, 2), p("b", R, 12), p("c", R, 4)))["c"])
        // Nothing of the left's own: the first part starts it, the rest keep the right.
        assertEquals(mapOf("x" to L, "y" to R), MoneyColumns.balance(listOf(p("x", R, 3), p("y", R, 10))))
        assertEquals(emptyMap(), MoneyColumns.balance(emptyList()))
    }

    @Test
    fun billsListStartsLevelWithTheCardOnlyAtTheTopOfTheRightColumn() {
        val split = mapOf("bills-card" to MoneyColumn.START, "bills-list" to MoneyColumn.END)
        assertTrue(MoneyColumns.billsListLevel(true, split))
        assertFalse(MoneyColumns.billsListLevel(false, split))
        assertFalse(MoneyColumns.billsListLevel(true, split + ("bills-list" to MoneyColumn.START)))
        // A regular payment's own row follows its part.
        assertEquals(MoneyColumn.START, MoneyColumns.of("regular-sky", mapOf("regular" to MoneyColumn.START)))
        assertEquals(MoneyColumn.FULL, MoneyColumns.of("bills-foot", split))
    }
}
