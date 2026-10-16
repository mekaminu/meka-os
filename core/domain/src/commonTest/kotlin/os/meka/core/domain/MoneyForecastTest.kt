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
        assertEquals("put by at your pace", f.rows[0].line)
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
        assertEquals("put by at your pace · +£2,100 with your what-ifs", f.rows[0].line)
        assertEquals("put by at your pace · +£4,200 with your what-ifs", f.rows[1].line)
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
}
