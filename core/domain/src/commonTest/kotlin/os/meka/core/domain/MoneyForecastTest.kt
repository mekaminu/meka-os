package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money manager, slice 4: the forecast at his pace and its what-ifs. Invented statements only. */
class MoneyForecastTest {
    private val oct16 = CivilDate.toEpochDay(2026, 10, 16)
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private fun line(day: Long, pence: Long, merchant: String, c: MoneyCategory) = MoneyLine(day, pence, merchant, c)

    /** Jul–Sep: £2,500 pay on the 1st, £1,600 bills, £300 eating out, £150 groceries, a card repayment; Oct so far too. */
    private fun months(vararg ms: Int) = ms.flatMap { m ->
        listOf(
            line(d(m, 1), 250_000, "SALARY OAKFIELD", MoneyCategory.INCOME),
            line(d(m, 2), -160_000, "RENT", MoneyCategory.BILLS),
            line(d(m, 10), -30_000, "NANDOS", MoneyCategory.EATING_OUT),
            line(d(m, 12), -15_000, "TESCO STORES", MoneyCategory.GROCERIES),
            line(d(m, 20), -50_000, "CARD REPAYMENT", MoneyCategory.TRANSFERS),
            line(d(m, 28), -1_000, "COSTA COFFEE", MoneyCategory.COFFEE),
        )
    }

    @Test
    fun averageMonthAndWhereItLeavesHim() {
        val f = MoneyForecast.view(months(7, 8, 9, 10), oct16)!!
        assertNull(f.notReady)
        assertEquals("In about £2,500 a month · out about £2,060", f.pace)
        assertEquals("About £440 a month left over", f.left)
        assertFalse(f.leftLit)
        assertEquals(listOf("By Apr 2027 · 6 months", "By Oct 2027 · 12 months"), f.rows.map { it.label })
        assertEquals(listOf("+£2,640", "+£5,280"), f.rows.map { it.amount })
        assertEquals("all that's left over, at your pace", f.rows[0].line)
        assertEquals("From Jul – Sep 2026 (3 whole months) · your average month, not a promise · not financial advice", f.basis)
        // What-ifs: the biggest everyday categories, never bills; coffee is under £20 a month.
        assertEquals(listOf("Halve Eating out", "Halve Groceries"), f.cuts.map { it.label })
        assertEquals("+£150 a month", f.cuts[0].line)
    }

    @Test
    fun whatIfsAddToTheHorizons() {
        val f = MoneyForecast.view(months(7, 8, 9), oct16, MoneyWhatIf(cuts = setOf("eating_out"), extraPence = 20_000))!!
        assertTrue(f.cuts.first { it.key == "eating_out" }.on)
        // £150 + £200 = £350 a month more: £2,100 in 6 months, £4,200 in 12.
        assertEquals("all that's left over, at your pace · +£2,100 with your what-ifs", f.rows[0].line)
        assertEquals("all that's left over, at your pace · +£4,200 with your what-ifs", f.rows[1].line)
        assertEquals("+£200 a month coming in · your own figure", f.extraLine)
    }

    @Test
    fun moreOutThanInIsLit() {
        val lines = months(8, 9) + listOf(line(d(8, 15), -80_000, "CURRYS", MoneyCategory.SHOPPING), line(d(9, 15), -80_000, "CURRYS", MoneyCategory.SHOPPING))
        val f = MoneyForecast.view(lines, oct16)!!
        assertEquals("About £360 a month more going out than coming in", f.left)
        assertTrue(f.leftLit)
        assertEquals("−£2,160", f.rows[0].amount)
        assertEquals("more out than in at your pace", f.rows[0].line)
        assertTrue(f.rows[0].lit)
    }

    @Test
    fun needsTwoWholeMonths() {
        // Statements from 10 Sep: September isn't whole, October is this month.
        val f = MoneyForecast.view(listOf(line(d(9, 10), -5_000, "TESCO", MoneyCategory.GROCERIES), line(d(10, 2), -5_000, "TESCO", MoneyCategory.GROCERIES)), oct16)!!
        assertEquals(MoneyForecast.NOT_READY, f.notReady)
        assertTrue(f.rows.isEmpty())
        assertNull(MoneyForecast.view(emptyList(), oct16))
        // A latest month that stops on the 12th doesn't count either.
        val ms = months(7, 8) + line(d(9, 12), -1_000, "TESCO", MoneyCategory.GROCERIES)
        assertEquals(listOf(2026 * 12 + 6, 2026 * 12 + 7), MoneyForecast.wholeMonths(ms, oct16))
    }

    @Test
    fun onlyACardStatementSaysSo() {
        val lines = listOf(8, 9).flatMap { m -> listOf(line(d(m, 1), -10_000, "TESCO", MoneyCategory.GROCERIES), line(d(m, 28), -5_000, "NEXT", MoneyCategory.CLOTHES)) }
        val f = MoneyForecast.view(lines, oct16)!!
        assertEquals("Out about £150 a month · no money in on these statements", f.pace)
        assertEquals("Import the account your pay goes into to see what's left over", f.left)
        assertEquals("−£900", f.rows[0].amount)
        assertEquals("spent at this pace", f.rows[0].line)
    }

    @Test
    fun extraAmountsReadAsAMonth() {
        assertEquals(20_000L, MoneyForecast.parseExtra("£200"))
        assertEquals(20_000L, MoneyForecast.parseExtra("200 a month"))
        assertEquals(21_666L, MoneyForecast.parseExtra("£50 a week"))
        assertEquals(10_000L, MoneyForecast.parseExtra("£1,200 a year"))
        assertEquals(0L, MoneyForecast.parseExtra(" "))
        assertNull(MoneyForecast.parseExtra("lots"))
        assertNull(MoneyForecast.parseExtra("£200,000"))
        assertEquals("What if: £200 a month more coming in", MoneyForecast.extraSaid(20_000))
    }

    @Test
    fun theViewCarriesItOnThisMonthOnly() {
        val accounts = listOf(MoneyAccount("a-current", "Current account", "csv", null))
        val txns = listOf(7, 8, 9, 10).flatMap { m ->
            listOf(
                MoneyTxn("p$m", "a-current", d(m, 1), 250_000, "SALARY OAKFIELD LTD"),
                MoneyTxn("t$m", "a-current", d(m, 12), -15_000, "TESCO STORES 2041"),
            )
        }
        val cal = LocalCalendar.UTC
        val now = MoneyRules.view(accounts, txns, emptyMap(), oct16, cal, 0)
        assertEquals("About £2,350 a month left over", now.forecast?.left)
        assertNull(MoneyRules.view(accounts, txns, emptyMap(), oct16, cal, 1).forecast)
    }

    // ---- Trading tracker, slice 11: his actual trading result as a what-if (invented accounts and figures) ----

    private val nowMs = LocalCalendar.UTC.toEpochMs(oct16, 10 * 60)
    private val eth = TradingCoinStats("ETH", 4, 3, 1, 30_000, 2_000, -1_000, 40_000, -10_000, 18_000, -10_000)
    private val sol = TradingCoinStats("SOL", 2, 0, 2, -8_000, 500, 0, 0, -8_000, -3_000, -5_000)

    private fun wallet(
        n: Int, month: String? = "2026-09", review: List<TradingCoinStats> = listOf(eth, sol),
        prev: List<TradingCoinStats>? = listOf(eth.copy(realisedCents = 10_000)), rate: Long? = 750_000,
        trades: List<TradingCoinStats> = emptyList(), tradesAt: Long? = null,
    ): TradingWallet {
        val address = "0x" + n.toString().repeat(40)
        return TradingWallet(TradingTracker.walletId(address), address, "Account $n", TradingTracker.OK, nowMs, nowMs, 1_000_000, null, null, null, null, 0,
            emptyList(), trades = trades, tradesAtMs = tradesAt, gbpPerUsdMicros = rate, reviewMonth = month, review = review, reviewPrev = prev)
    }

    @Test
    fun tradingPaceAveragesTheLastTwoMonthsInPounds() {
        // September +$185, August +$70: about $127.50 a month, £95.62 at £0.75.
        val p = MoneyForecast.tradingPace(listOf(wallet(1)), nowMs, LocalCalendar.UTC)!!
        assertEquals(9_562, p.pencePerMonth)
        assertEquals("September and August", p.from)
        // Only September read so far for the month before: September alone.
        assertEquals(MoneyTradingPace(13_875, "September"), MoneyForecast.tradingPace(listOf(wallet(1, prev = null)), nowMs, LocalCalendar.UTC))
        // Last month not added up yet: the last 30 days.
        val recent = MoneyForecast.tradingPace(listOf(wallet(1, month = "2026-08", trades = listOf(sol), tradesAt = nowMs)), nowMs, LocalCalendar.UTC)!!
        assertEquals(MoneyTradingPace(-6_375, "the last 30 days"), recent)
        // Nothing to go on: no accounts, an account without the dollar rate, or no trades at all.
        assertNull(MoneyForecast.tradingPace(emptyList(), nowMs, LocalCalendar.UTC))
        assertNull(MoneyForecast.tradingPace(listOf(wallet(1), wallet(2, rate = null)), nowMs, LocalCalendar.UTC))
        assertNull(MoneyForecast.tradingPace(listOf(wallet(1, review = emptyList(), prev = emptyList())), nowMs, LocalCalendar.UTC))
    }

    @Test
    fun tradingIsAWhatIfOnHisTap() {
        val pace = MoneyTradingPace(9_562, "September and August")
        val off = MoneyForecast.view(months(7, 8, 9), oct16, MoneyWhatIf(), pace)!!
        assertEquals("Trading as it's gone", off.trading?.label)
        assertEquals("+£100 a month", off.trading?.line)
        assertFalse(off.trading!!.on)
        assertEquals("all that's left over, at your pace", off.rows[0].line)
        assertTrue(off.tradingLine!!.startsWith("Trading: your Hyperliquid result a month from September and August, after fees and funding"))
        // Two cut chips only: trading has its own.
        assertEquals(listOf("eating_out", "groceries"), off.cuts.map { it.key })
        val on = MoneyForecast.view(months(7, 8, 9), oct16, MoneyWhatIf(cuts = setOf(MoneyForecast.TRADING_KEY)), pace)!!
        assertTrue(on.trading!!.on)
        assertEquals("all that's left over, at your pace · +£570 with your what-ifs", on.rows[0].line)
        assertEquals("all that's left over, at your pace · +£1,150 with your what-ifs", on.rows[1].line)
        // A losing pace counts against him, said plainly.
        val losing = MoneyForecast.view(months(7, 8, 9), oct16, MoneyWhatIf(cuts = setOf("trading")), MoneyTradingPace(-6_375, "the last 30 days"))!!
        assertEquals("−£60 a month", losing.trading?.line)
        assertEquals("all that's left over, at your pace · −£380 with your what-ifs", losing.rows[0].line)
        // Without a pace the "trading" key does nothing and there's no chip.
        val none = MoneyForecast.view(months(7, 8, 9), oct16, MoneyWhatIf(cuts = setOf("trading")))!!
        assertNull(none.trading)
        assertEquals("all that's left over, at your pace", none.rows[0].line)
    }

    @Test
    fun theMoneyScreenCarriesTheTradingPace() {
        val accounts = listOf(MoneyAccount("a-current", "Current account", "csv", null))
        val txns = listOf(7, 8, 9, 10).flatMap { m ->
            listOf(MoneyTxn("p$m", "a-current", d(m, 1), 250_000, "SALARY OAKFIELD LTD"), MoneyTxn("t$m", "a-current", d(m, 12), -15_000, "TESCO STORES 2041"))
        }
        val v = MoneyRules.view(accounts, txns, emptyMap(), oct16, LocalCalendar.UTC, 0, nowMs = nowMs, trading = listOf(wallet(1)))
        assertEquals("+£100 a month", v.forecast?.trading?.line)
        assertNull(MoneyRules.view(accounts, txns, emptyMap(), oct16, LocalCalendar.UTC, 0, nowMs = nowMs).forecast?.trading)
    }
}
