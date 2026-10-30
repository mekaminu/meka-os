package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money's mockup check, item 7: Accounts' total card — "Across N accounts and trading", the total, one breakdown line. */
class MoneyTotalTest {
    private val today = CivilDate.toEpochDay(2026, 10, 28)

    private fun wallet(id: String, equity: Long, rate: Long) = TradingWallet(
        id, "0x" + "cd34".repeat(10), "Main", TradingTracker.OK, 1L, 1L, equity, null, null, null, null, null, emptyList(),
        gbpPerUsdMicros = rate, fxDay = today,
    )

    @Test
    fun theCardSaysAcrossHowManyAndBreaksTheTotalDownAsTheMockup() {
        // The mockup's figures: cash 1,204 + 4,100 + 620; two cards owing 4,200 and 1,800 (+ 686 on a third);
        // trading $8,140 at £0.80 = £6,512.
        val accounts = listOf(
            MoneyAccount("a-cur", "Current account", "bank", 1L, MoneyBalance(120_400, today, typed = false)),
            MoneyAccount("a-sav", "Savings", "bank", 1L, MoneyBalance(410_000, today, typed = false)),
            MoneyAccount("a-biz", "Business", "bank", 1L, MoneyBalance(62_000, today, typed = false)),
            MoneyAccount("a-c1", "Card 09", "bank", 1L),
            MoneyAccount("a-c2", "Card 01", "bank", 1L),
            MoneyAccount("a-c3", "Card 77", "bank", 1L),
        )
        val cards = listOf(
            MoneyCard("a-c1", "Card 09", MoneyCards.CREDIT, statementPence = 420_000),
            MoneyCard("a-c2", "Card 01", MoneyCards.CREDIT, statementPence = 180_000),
            MoneyCard("a-c3", "Card 77", MoneyCards.CREDIT, statementPence = 68_600),
        )
        val w = MoneyWorth.view(accounts, cards, emptyList(), emptyList(), today, listOf(wallet("w1", 814_000, 800_000)))
        val c = MoneyTotal.card(w)!!
        assertEquals("Across 6 accounts and trading", c.head)
        assertEquals("£5,750", c.total)
        assertEquals("Cash £5,924 · trading £6,512 · owed on cards £6,686", c.breakdown)
        assertNull(c.empty)
        assertTrue(c.spoken.startsWith("Total £5,750, across 6 accounts and trading."), c.spoken)
    }

    @Test
    fun whatHeTypedIsInTheBreakdownAndPartsWithNothingStayOut() {
        val accounts = listOf(MoneyAccount("a-cur", "Current account", "csv", 1L, MoneyBalance(-5_000, today, typed = true)))
        val items = listOf(
            MoneyWorthItem("n-pension", "Pension", false, 4_200_000, today),
            MoneyWorthItem("n-car-loan", "Car loan", true, 320_000, today),
        )
        val c = MoneyTotal.card(MoneyWorth.view(accounts, emptyList(), items, emptyList(), today))!!
        assertEquals("Across 1 account", c.head)
        assertEquals("£38,750", c.total)
        assertEquals("Cash −£50 · outside £38,800", c.breakdown)
    }

    @Test
    fun beforeAnythingIsCountedItSaysWhatToDoInOneLine() {
        val c = MoneyTotal.card(MoneyWorth.view(listOf(MoneyAccount("a", "Current account", "csv", 1L)), emptyList(), emptyList(), emptyList(), today))!!
        assertNull(c.total)
        assertNull(c.breakdown)
        assertEquals(MoneyTotal.EMPTY, c.empty)
        assertEquals("Across 1 account", c.head)
        assertNull(MoneyTotal.card(null))
        assertEquals("Across trading", MoneyTotal.head(0, 1))
        assertEquals(MoneyTotal.NONE, MoneyTotal.head(0, 0))
    }

    @Test
    fun withNoBalanceInOnlyWhatHeTypedOutsideLeadsAndNothingSaysPartial() {
        // Walk-through A4: two accounts with no balance and a typed pension read "Across 2 accounts £42,000",
        // "Outside £42,000" twice and "Partial" with nothing counted.
        val accounts = listOf(
            MoneyAccount("a-cur", "Current account", "csv", 1L),
            MoneyAccount("a-sav", "Savings", "csv", 1L),
        )
        val items = listOf(MoneyWorthItem("n-pension", "Pension", false, 4_200_000, today))
        val w = MoneyWorth.view(accounts, emptyList(), items, emptyList(), today)
        val c = MoneyTotal.card(w)!!
        assertEquals(MoneyTotal.OUTSIDE_ONLY, c.head)
        assertEquals("£42,000", c.total)
        assertNull(c.breakdown)
        assertEquals("Not counted yet · 2 accounts have no balance", w.missingLine)
        // One balance in: the head counts only it, the breakdown has two parts, and Partial is true again.
        val one = MoneyWorth.view(listOf(accounts[0].copy(balance = MoneyBalance(100_000, today, typed = true)), accounts[1]),
            emptyList(), items, emptyList(), today)
        val c1 = MoneyTotal.card(one)!!
        assertEquals("Across 1 account", c1.head)
        assertEquals("Cash £1,000 · outside £42,000", c1.breakdown)
        assertEquals("Partial · 1 account has no balance yet", one.missingLine)
        // Cash alone: no breakdown repeating the total.
        val cash = MoneyWorth.view(listOf(accounts[0].copy(balance = MoneyBalance(100_000, today, typed = true))),
            emptyList(), emptyList(), emptyList(), today)
        assertNull(MoneyTotal.card(cash)!!.breakdown)
    }
}
