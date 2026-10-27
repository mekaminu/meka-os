package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money redesign (B3): the Bills tab's card and the month's bills by date. Invented payers, shops and amounts only. */
class MoneyBillsTest {
    private val today = CivilDate.toEpochDay(2026, 10, 16) // a Friday
    private val names = mapOf("a-current" to "Current account", "a-card" to "Credit card")
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)

    private fun line(day: Long, pence: Long, text: String, account: String = "a-current") = MoneyPayLine(
        account, day, pence, MoneyCategoryRules.merchantKey(text), MoneyCategoryRules.categoryOf(text, pence),
    )

    private val pay = listOf(
        line(d(7, 30), 250_000, "ACME PAYROLL"), line(d(8, 28), 250_000, "ACME PAYROLL"), line(d(9, 30), 250_000, "ACME PAYROLL"),
    )
    /** Streamly on the 20th (still to come), Netflux on the 3rd (already paid this month). */
    private val regulars = listOf(
        line(d(7, 20), -1_099, "STREAMLY.COM"), line(d(8, 20), -1_099, "STREAMLY.COM"), line(d(9, 20), -1_099, "STREAMLY.COM"),
        line(d(7, 3), -3_000, "NETFLUX.COM"), line(d(8, 3), -3_000, "NETFLUX.COM"), line(d(9, 3), -3_000, "NETFLUX.COM"),
        line(d(10, 3), -3_000, "NETFLUX.COM"),
        line(d(10, 2), -12_000, "TESCO STORES 2041"),
    )
    private val water = MoneyBill("Water bill", d(10, 25), 4_000)
    private val card = MoneyBill("Credit card", d(10, 22), 20_000)

    private fun payday(ls: List<MoneyPayLine> = pay + regulars) = MoneyPayday.view(
        ls, today, names, emptyMap(), listOf(water, card), HolidayCalendar(), cardTitles = setOf("Credit card"),
    )

    @Test
    fun theCardSaysWhatsStillToGoOutBeforePaydayAndWhatsPaid() {
        val b = payday().bills!!
        assertEquals("Still to go out before payday · Fri 30 Oct", b.head)
        // Streamly £10.99 + the card's £200 + water £40 = £250.99 → £251, the same as the Spending tab's tile.
        assertEquals("£251", b.amount)
        assertEquals(25_099L, payday().toGoPence)
        assertEquals("3 bills", b.count)
        assertEquals("£30 of £281 this month already paid", b.paidLine)
        assertTrue(kotlin.math.abs(3_000f / 28_099f - b.paidShare) < 0.001f)
    }

    @Test
    fun billsToComeByDateThenThePaidOnesGreyed() {
        val rows = payday().bills!!.rows
        assertEquals(listOf("Streamly.com", "Credit card", "Water bill", "Netflux.com"), rows.map { it.title })
        assertEquals(listOf("OCT" to "20", "OCT" to "22", "OCT" to "25", "OCT" to "3"), rows.map { it.month to it.day })
        assertEquals(listOf("Monthly", "Card statement", "From Lists → Renewals", "Paid · Sat 3 Oct"), rows.map { it.sub })
        assertEquals(listOf(false, false, false, true), rows.map { it.paid })
        assertEquals(listOf("£10.99", "£200", "£40", "£30"), rows.map { it.amount })
        assertEquals("Netflux.com, Sat 3 Oct, £30, Paid · Sat 3 Oct", rows.last().spoken)
    }

    @Test
    fun aBillDueTodayOrTomorrowIsLitAndAPriceRiseSaysSo() {
        val tomorrow = MoneyBills.Due("r-x", "Broadband", today + 1, 3_500, MoneyBillKind.REGULAR, rise = 500)
        val later = MoneyBills.Due("r-y", "Phone", today + 5, 1_200, MoneyBillKind.REGULAR)
        val rows = MoneyBills.view(listOf(later, tomorrow), emptyList(), "Fri 30 Oct", today)!!.rows
        assertEquals("Due tomorrow · Monthly · went up £5", rows[0].sub)
        assertTrue(rows[0].subLit)
        assertFalse(rows[1].subLit)
        assertEquals("Due today · Card statement", MoneyBills.sub(MoneyBills.Due("c", "Card", today, 1, MoneyBillKind.CARD), today))
    }

    @Test
    fun nothingLeftStillShowsWhatWasPaidAndNothingAtAllShowsNoCard() {
        val paid = MoneyBills.Due("p-a", "Gym", today - 3, 2_500, MoneyBillKind.REGULAR, paid = true)
        val v = MoneyBills.view(emptyList(), listOf(paid), "Fri 30 Oct", today)!!
        assertEquals("£0", v.amount)
        assertEquals(MoneyBills.NOTHING, v.count)
        assertEquals("£25 of £25 this month already paid", v.paidLine)
        assertEquals(1f, v.paidShare)
        assertNull(MoneyBills.view(emptyList(), emptyList(), null, today))
        // Nothing paid yet: no bar and no line.
        assertNull(MoneyBills.view(listOf(paid.copy(paid = false, day = today + 2)), emptyList(), null, today)!!.paidLine)
    }

    @Test
    fun withoutAPaydayTheMonthsBillsOnEveryAccountUntilItEnds() {
        val lines = regulars.map { MoneyLine(it.day, it.pence, it.merchant, it.category) }
        val v = MoneyBills.forMonth(MoneyTips.regular(lines, today), listOf(water, card, MoneyBill("Insurance", d(11, 2), 9_000)),
            setOf("Credit card"), today)!!
        assertEquals("Still to go out before the month ends", v.head)
        // Insurance on 2 Nov is next month's.
        assertEquals(listOf("Streamly.com", "Credit card", "Water bill", "Netflux.com"), v.rows.map { it.title })
        // And the payday view without pay says not ready, so Money falls back to this.
        assertNull(payday(regulars).bills)
    }

    @Test
    fun theBillsCardShowsFiveThenSeeAll() {
        // Layout rule (d): five bills, then "See all N ›".
        val dues = (1..7).map { MoneyBills.Due("r-$it", "Bill $it", today + it, 1_000L * it, MoneyBillKind.REGULAR) }
        val v = MoneyBills.view(dues, emptyList(), "Fri 30 Oct", today)!!
        assertEquals(5, MoneyBills.shownRows(v, open = false).size)
        assertEquals(v.rows.size, MoneyBills.shownRows(v, open = true).size)
        assertEquals("See all ${v.rows.size} ›", MoneyBills.allLabel(v.rows.size, open = false))
        assertEquals(MoneyBills.FEWER, MoneyBills.allLabel(v.rows.size, open = true))
        assertEquals(null, MoneyBills.allLabel(5, open = false))
    }
}
