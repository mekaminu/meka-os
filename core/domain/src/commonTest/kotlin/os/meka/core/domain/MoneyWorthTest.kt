package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money manager, slice 8: net worth — typed things, balances, cards and the month-by-month line. Invented figures only. */
class MoneyWorthTest {
    private val today = CivilDate.toEpochDay(2026, 10, 17)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)

    @Test
    fun typedLinesReadAsWhatYouHaveOrOwe() {
        assertEquals(MoneyWorthParse("Pension", false, 4_200_000), MoneyWorth.parse("Pension £42,000"))
        assertEquals(MoneyWorthParse("Savings elsewhere", false, 1_200_000), MoneyWorth.parse("savings elsewhere 12k"))
        assertEquals(MoneyWorthParse("Car", false, 850_000), MoneyWorth.parse("Car 8.5k"))
        assertEquals(MoneyWorthParse("Car loan", true, 320_000), MoneyWorth.parse("Car loan £3,200"))
        assertEquals(MoneyWorthParse("Mortgage", true, 18_000_000), MoneyWorth.parse("Mortgage −£180,000"))
        assertEquals(MoneyWorthParse("Dad", true, 50_000), MoneyWorth.parse("I owe Dad £500"))
        assertEquals(MoneyWorthParse("ISA", false, 520_000), MoneyWorth.parse("ISA: £5.2k"))
        assertEquals(MoneyWorthParse("Watch", true, 30_000), MoneyWorth.parse("Watch -300"))
        assertNull(MoneyWorth.parse("Pension"))
        assertNull(MoneyWorth.parse("£42,000"))
        assertNull(MoneyWorth.parse("Car £0"))
        assertEquals("n-car-loan", MoneyWorth.idFor("Car loan"))
    }

    @Test
    fun aBalanceIsWorkedBackThroughItsStatement() {
        // £1,000 now (17 Oct); £200 spent on 10 Oct, £2,000 paid in on 30 Sep, £150 spent on 20 Sep.
        val a = MoneyWorthAccount("a-current", 100_000, today, listOf(d(10, 10) to -20_000L, d(9, 30) to 200_000L, d(9, 20) to -15_000L))
        assertEquals(100_000, MoneyWorth.balanceOn(a, today))
        assertEquals(120_000, MoneyWorth.balanceOn(a, d(9, 30)))
        assertEquals(-65_000, MoneyWorth.balanceOn(a, d(8, 31)))
        val pts = MoneyWorth.points(listOf(a), flat = 500_000, today = today)
        assertEquals(listOf("Sep", "Oct"), pts.map { it.label })
        assertEquals(listOf(620_000L, 600_000L), pts.map { it.pence })
        assertEquals("End of Sep 2026 · £6,200", pts[0].said)
        assertEquals("Now · £6,000", pts[1].said)
    }

    @Test
    fun theLineStartsWhereEveryAccountIsKnownAndKeepsTwelveMonths() {
        val old = MoneyWorthAccount("a", 0, today, listOf(d(1, 5, 2025) to -100L))
        val newer = MoneyWorthAccount("b", 0, today, listOf(d(8, 3) to -100L))
        assertEquals(listOf("Aug", "Sep", "Oct"), MoneyWorth.points(listOf(old, newer), 0, today).map { it.label })
        assertEquals(12, MoneyWorth.points(listOf(old), 0, today).size)
        assertEquals("Nov", MoneyWorth.points(listOf(old), 0, today).first().label)
    }

    @Test
    fun theTotalCountsBalancesCardsAndTypedThingsAndSaysWhatIsMissing() {
        val accounts = listOf(
            MoneyAccount("a-current", "Current account", "csv", 1L, MoneyBalance(184_000, d(10, 16), typed = false)),
            MoneyAccount("a-credit", "Credit card", "csv", 1L),
            MoneyAccount("a-saver", "Saver", "csv", 1L),
        )
        val cards = listOf(MoneyCard("a-credit", "Credit card", MoneyCards.CREDIT, statementPence = 64_000))
        val items = listOf(
            MoneyWorthItem("n-pension", "Pension", false, 4_200_000, today),
            MoneyWorthItem("n-car-loan", "Car loan", true, 320_000, d(10, 1)),
        )
        val lines = listOf(Triple("a-current", d(9, 25), -6_000L), Triple("a-current", d(10, 12), -10_000L))
        val v = MoneyWorth.view(accounts, cards, items, lines, today)
        // 1,840 + 42,000 − 640 − 3,200
        assertEquals("£40,000", v.total)
        assertEquals("You have £43,840 · you owe £3,840", v.splitLine)
        assertEquals("Partial · 1 account has no balance yet", v.missingLine)
        assertEquals(listOf("Credit card", "Current account", "Saver", "Pension", "Car loan"), v.rows.map { it.name })
        assertEquals("−£640", v.rows[0].amount)
        assertTrue(v.rows[2].needsBalance)
        assertEquals("−£3,200", v.rows[4].amount)
        assertEquals("Owed · typed 1 Oct 2026", v.rows[4].line)
        assertTrue(v.rows[4].removable)
        // End of Sep: 1,840 + 100 spent since = 1,940.
        assertEquals(listOf(4_010_000L, 4_000_000L), v.points.map { it.pence })
        assertEquals("Down £100 since the end of Sep", v.changeLine)
    }

    @Test
    fun aCardsBalanceIsOwedNeverMoneyYouHave() {
        // Money's wrong numbers (2): a bank card that isn't marked as one still counts as owed, whichever sign the bank
        // gives; a marked card with nothing typed uses its balance; one with neither is unknown and the total partial.
        val accounts = listOf(
            MoneyAccount("a-current", "Oakbank current", "bank", 1L, MoneyBalance(500_000, today, typed = false)),
            MoneyAccount("a-premier", "Oakbank Premier Card", "bank", 1L, MoneyBalance(300_000, today, typed = false)),
            MoneyAccount("a-plat", "Amex Platinum", "bank", 1L, MoneyBalance(-120_000, today, typed = false)),
            MoneyAccount("a-gold", "Amex Gold", "bank", 1L),
        )
        val cards = listOf(MoneyCard("a-plat", "Amex Platinum", MoneyCards.CHARGE), MoneyCard("a-gold", "Amex Gold", MoneyCards.CREDIT))
        val v = MoneyWorth.view(accounts, cards, emptyList(), emptyList(), today)
        // 5,000 − 3,000 − 1,200
        assertEquals("£800", v.total)
        assertEquals("You have £5,000 · you owe £4,200", v.splitLine)
        val premier = v.rows.single { it.id == "a-premier" }
        assertEquals(MoneyWorth.CARD, premier.kind)
        assertEquals("−£3,000", premier.amount)
        assertEquals("Card · its balance, owed", premier.line)
        assertEquals("−£1,200", v.rows.single { it.id == "a-plat" }.amount)
        val gold = v.rows.single { it.id == "a-gold" }
        assertEquals(MoneyWorth.UNKNOWN_CARD, gold.line)
        assertEquals("", gold.amount)
        assertEquals("Partial · 1 account has no balance yet", v.missingLine)
        // An unmarked card with no balance can have one typed.
        val bare = MoneyWorth.view(listOf(MoneyAccount("a-premier", "Oakbank Premier Card", "bank", 1L)), emptyList(), emptyList(), emptyList(), today)
        assertTrue(bare.rows.single().needsBalance)
        assertEquals("Partial · 1 account has no balance yet", bare.missingLine)
    }

    @Test
    fun withNothingCountedItSaysWhatToDo() {
        val v = MoneyWorth.view(listOf(MoneyAccount("a", "Current account", "csv", 1L)), emptyList(), emptyList(), emptyList(), today)
        assertNull(v.total)
        assertEquals(MoneyWorth.EMPTY, v.empty)
        assertTrue(v.points.isEmpty())
    }

    private fun wallet(id: String, label: String, equity: Long?, rate: Long?, day: Long? = today) = TradingWallet(
        id, "0x" + "ab12".repeat(10), label, TradingTracker.OK, 1L, 1L, equity, null, null, null, null, null, emptyList(),
        gbpPerUsdMicros = rate, fxDay = day,
    )

    @Test
    fun hyperliquidAccountsCountInPoundsAtTheEcbRate() {
        assertEquals(79_123L, TradingTracker.pence(100_000, 791_234))
        assertEquals("£0.79 to the dollar", TradingTracker.rateLine(791_234))
        assertEquals("£1.00 to the dollar", TradingTracker.rateLine(999_999))
        assertTrue(!TradingTracker.rateOk(5_000_000))
        val items = listOf(MoneyWorthItem("n-pension", "Pension", false, 1_000_000, today))
        // $2,000.00 at £0.75 = £1,500; a second account not read with a rate yet isn't counted.
        val v = MoneyWorth.view(emptyList(), emptyList(), items, emptyList(), today,
            listOf(wallet("w1", "Main", 200_000, 750_000), wallet("w2", "Bot", 50_000, null), wallet("w3", "New", null, null)))
        assertEquals("£11,500", v.total)
        assertEquals("You have £11,500 · you owe £0", v.splitLine)
        val main = v.rows.single { it.id == "w1" }
        assertEquals(MoneyWorth.TRADING, main.kind)
        assertEquals("£1,500", main.amount)
        assertEquals("Hyperliquid · $2,000.00 at £0.75 to the dollar · today's rate", main.line)
        val bot = v.rows.single { it.id == "w2" }
        assertEquals("", bot.amount)
        assertEquals("$500.00 · counted once today's dollar rate is in", bot.line)
        assertTrue(v.rows.none { it.id == "w3" })
        assertEquals(MoneyWorth.FOOT_TRADING, v.foot)
        // Trading alone is enough for a total; an older rate says its day.
        val alone = MoneyWorth.view(emptyList(), emptyList(), emptyList(), emptyList(), today, listOf(wallet("w1", "Main", 200_000, 750_000, d(10, 16))))
        assertEquals("£1,500", alone.total)
        assertTrue(alone.rows.single().line.endsWith("the rate of Fri 16 Oct"), alone.rows.single().line)
    }

    @Test
    fun whatHeTypedIsOneRowOnTheCardAndAListInItsSheet() {
        // Money review, layout rule (e): the typed things and the field leave the card for a sheet.
        val none = MoneyWorth.view(emptyList(), emptyList(), emptyList(), emptyList(), today)
        val add = MoneyWorthOther.row(none)!!
        assertTrue(add.add)
        assertNull(add.amount)
        assertEquals(MoneyWorthOther.ADD_LINE, add.line)
        assertTrue(MoneyWorthOther.rows(none).isEmpty())
        assertNull(MoneyWorthOther.row(null))
        val items = listOf(
            MoneyWorthItem("n-pension", "Pension", false, 4_200_000, today),
            MoneyWorthItem("n-car", "Car", false, 850_000, today),
            MoneyWorthItem("n-car-loan", "Car loan", true, 320_000, today),
            MoneyWorthItem("n-boat", "Boat", false, 100_000, today),
            MoneyWorthItem("n-dad", "Dad", true, 50_000, today),
        )
        val w = MoneyWorth.view(emptyList(), emptyList(), items, emptyList(), today)
        val r = MoneyWorthOther.row(w)!!
        assertTrue(!r.add)
        assertEquals("£47,800", r.amount)
        assertEquals("Pension · Car · Boat + 2 more", r.line)
        assertTrue(r.spoken.startsWith("Outside these accounts, £47,800"))
        assertEquals(listOf("Pension", "Car", "Boat", "Car loan", "Dad"), MoneyWorthOther.rows(w).map { it.name })
        assertTrue(MoneyWorthOther.rows(w).all { it.removable })
        val owed = MoneyWorthOther.row(MoneyWorth.view(emptyList(), emptyList(), listOf(items[2]), emptyList(), today))!!
        assertEquals("−£3,200", owed.amount)
        assertTrue(owed.owes)
        assertEquals("Car loan", owed.line)
        assertTrue(owed.spoken.contains("owed £3,200"))
    }
}
