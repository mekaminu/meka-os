package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Obi sees everything, slice 5: "how's my trading going?" answered from the followed accounts' read figures. */
class TradingAskTest {
    private val now = 1_792_225_800_000L

    private fun wallet(
        label: String = "Main", equity: Long? = 1_234_567, positions: List<TradingPosition> = emptyList(),
        trades: List<TradingCoinStats> = emptyList(), tradesAt: Long? = null, drawdown: Long? = null, base: Long? = null,
    ) = TradingWallet(
        "w$label", "0x" + "ab12".repeat(10), label, TradingTracker.OK, now - 12 * 60_000L, now, equity,
        12_050, -4_000, 30_000, 99_000, 500, positions, trades = trades, tradesAtMs = tradesAt,
        drawdownCents = drawdown, drawdownBaseCents = base,
    )

    @Test
    fun tradingQuestionsAreKnownAndOthersAreNot() {
        listOf("how's my trading going?", "what positions are open?", "am I up on Hyperliquid?", "how far is my BTC long from liquidation?",
            "what's my P&L this week", "how much funding paid this month").forEach { assertTrue(TradingAskRules.isAbout(it), it) }
        listOf("how do I add my trading address?", "call trading standards", "what's for dinner?", "how much did I spend on takeaways?", "")
            .forEach { assertFalse(TradingAskRules.isAbout(it), it) }
    }

    @Test
    fun nothingFollowedOrNotReadSaysSo() {
        assertEquals(TradingAskRules.EMPTY, TradingAskRules.answer(emptyList(), TradingCcy.USD, now))
        assertEquals(TradingAskRules.NOT_READ, TradingAskRules.answer(listOf(wallet(equity = null)), TradingCcy.USD, now))
    }

    @Test
    fun theSummarySaysWhatHeHoldsPnlPositionsAndTrades() {
        val btc = TradingPosition("BTC", "0.5", "60000", "62000", "51460", -4_000, 5)
        val eth = TradingPosition("ETH", "-2", "3000", "2900", "3190", 20_000, 10)
        val stats = listOf(TradingCoinStats("BTC", 23, 14, 9, 50_000, 3_000, -1_200, 80_000, -30_000, 20_000, -9_000))
        val a = TradingAskRules.answer(listOf(wallet(positions = listOf(btc, eth), trades = stats, tradesAt = now, drawdown = 15_000, base = 1_000_000)), TradingCcy.USD, now)
        assertTrue(a.startsWith("On Hyperliquid (read 12 min ago) you hold $12,345.67."), a)
        assertTrue("P&L: +$120.50 today, −$40.00 this week, +$300.00 this month." in a, a)
        // Nearest liquidation first: ETH is 10% away, BTC 17%.
        assertTrue("2 positions are open: ETH short 10×, 10% from liquidation, +$200.00 unrealised; BTC long 5×, 17% from liquidation, −$40.00 unrealised." in a, a)
        assertTrue("The account is 2% below this month's high." in a, a)
        assertTrue("Last ${TradingTracker.TRADES_DAYS} days: 14 of 23 trades won, closed profit +$500.00, funding paid −$12.00, fees −$30.00." in a, a)
        assertTrue(a.endsWith(TradingAskRules.NOT_ADVICE), a)
    }

    @Test
    fun noPositionsAndTwoAccountsAreSaid() {
        val a = TradingAskRules.answer(listOf(wallet("Main"), wallet("Spare", equity = 100)), TradingCcy.USD, now)
        assertTrue("you hold $12,346.67 across 2 accounts." in a, a)
        assertTrue("No positions are open." in a, a)
    }

    @Test
    fun theMoneyLookUpNamesTrading() {
        assertTrue("for money and trading ${LiveToolRules.MONEY_TOOL}" in ObiSees.NO_AREA)
    }
}
