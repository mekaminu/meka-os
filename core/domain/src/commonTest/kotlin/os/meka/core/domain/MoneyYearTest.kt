package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyYearTest {
    private fun day(y: Int, m: Int, d: Int) = CivilDate.toEpochDay(y, m, d)
    private fun l(y: Int, m: Int, d: Int, pence: Long, text: String, c: MoneyCategory) =
        MoneyYearLine(day(y, m, d), pence, MoneyCategoryRules.merchantKey(text), c)

    /** Every month of 2025 and Jan–Oct 2026: pay in, groceries and eating out out, a transfer that never counts. */
    private fun twoYears(): List<MoneyYearLine> {
        val out = mutableListOf<MoneyYearLine>()
        for (y in 2025..2026) for (m in 1..12) {
            if (y == 2026 && m > 10) break
            out += l(y, m, 1, 250_000, "ACME LTD SALARY", MoneyCategory.INCOME)
            out += l(y, m, 3, if (y == 2026) -45_000 else -40_000, "TESCO STORES 2041", MoneyCategory.GROCERIES)
            out += l(y, m, 10, -8_000, "PIZZA PLACE", MoneyCategory.EATING_OUT)
            out += l(y, m, 12, -50_000, "TO SAVINGS", MoneyCategory.TRANSFERS)
        }
        out += l(2025, 12, 20, -90_000, "SKYLINE AIRWAYS", MoneyCategory.TRAVEL)
        out += l(2026, 10, 15, -2_000, "TESCO STORES 2041", MoneyCategory.GROCERIES)
        return out
    }

    @Test
    fun januaryReviewsLastYearOpenWithTheYearBefore() {
        val lines = twoYears() + l(2024, 1, 5, -1_000, "OLD SHOP", MoneyCategory.SHOPPING)
        // In January 2026 the year reviewed is 2025, statements reaching back into 2024 only from 5 Jan: no comparison.
        val v = assertNotNull(MoneyYear.view(lines.filter { it.day <= day(2026, 1, 20) }, day(2026, 1, 20)))
        assertEquals(2025, v.year)
        assertEquals("2025 IN REVIEW", v.head)
        assertTrue(v.unfolded)
        assertEquals("Spent £6,660 · in £30,000", v.title)
        assertEquals("£23,340 more came in than went out", v.leftLine)
        assertNull(v.vsLine)
        assertEquals(listOf("Groceries", "Eating out", "Travel"), v.categories.map { it.label })
        assertEquals("12 payments · about £400 a month", v.categoryAt(0).line)
        assertEquals("Busiest month December · £1,380 · quietest January · £480", v.monthsLine)
        assertTrue(v.biggest!!.startsWith("Biggest payment · £900 · "))
        assertTrue(v.foot.startsWith("From your statements, all of 2025."))
        assertFalse(v.categories.any { it.key == "transfers" || it.key == "income" })
    }

    @Test
    fun theRestOfTheYearIsThisYearSoFarAgainstTheSameDates() {
        val today = day(2026, 10, 17)
        val v = assertNotNull(MoneyYear.view(twoYears(), today))
        assertEquals("2026 SO FAR", v.head)
        assertFalse(v.unfolded)
        // 1 Jan – 15 Oct 2026 against 1 Jan – 15 Oct 2025: groceries £50 more a month for 10 months, plus £20.
        assertEquals("£520 more than the same dates in 2025", v.vsLine)
        assertTrue(v.vsLit)
        val g = v.categoryAt(0)
        assertEquals("Groceries", g.label)
        assertEquals("£4,520", g.amount)
        assertTrue(g.line.endsWith("up £520 on 2025"), g.line)
        assertTrue(g.lit)
        assertEquals("about the same as 2025", v.categoryAt(1).line.substringAfterLast(" · "))
        assertTrue(v.foot.startsWith("From your statements, 1 Jan – 15 Oct 2026 · 10 months."), v.foot)
        assertEquals("Your year so far · Spent £5,320 · in £25,000", v.folded)
    }

    @Test
    fun nothingWithoutEnoughStatements() {
        assertNull(MoneyYear.view(emptyList(), day(2026, 10, 17)))
        // One month of this year: too little for "so far".
        assertNull(MoneyYear.view(listOf(l(2026, 10, 2, -1_000, "TESCO", MoneyCategory.GROCERIES)), day(2026, 10, 17)))
        // January with no statements for last year.
        assertNull(MoneyYear.view(listOf(l(2026, 1, 2, -1_000, "TESCO", MoneyCategory.GROCERIES)), day(2026, 1, 10)))
        // More out than in lights the line.
        val v = assertNotNull(MoneyYear.view(listOf(
            l(2026, 3, 1, 10_000, "PAY", MoneyCategory.INCOME), l(2026, 3, 5, -20_000, "SHOP", MoneyCategory.SHOPPING),
            l(2026, 4, 5, -5_000, "SHOP", MoneyCategory.SHOPPING),
        ), day(2026, 10, 17)))
        assertEquals("£150 more went out than came in", v.leftLine)
        assertTrue(v.leftLit)
        assertNull(v.monthsLine) // only March is a whole month of statements
    }

    @Test
    fun theHeadsUpComesOnTheSecondOfJanuaryWithNoAmounts() {
        val cal = LocalCalendar.UTC
        val days = twoYears().map { it.day }
        assertNull(MoneyYear.readyYear(days, day(2026, 10, 17)))
        assertEquals(2026, MoneyYear.readyYear(days, day(2027, 1, 3)))
        assertNull(MoneyYear.readyYear(days, day(2028, 1, 3))) // no statements in 2027
        val y = MoneyYear.readyYear(days, day(2026, 1, 2))
        assertEquals(2025, y)
        val n = MoneyYear.notices(y, cal.toEpochMs(day(2026, 1, 2), 8 * 60), cal).single()
        assertEquals(NoticeSource.MONEY, n.source)
        assertEquals(cal.toEpochMs(day(2026, 1, 2), 9 * 60), n.atMs)
        assertEquals("Your 2025 money year in review is ready", n.title)
        assertFalse(n.text.contains("£"))
        assertTrue(MoneyYear.notices(y, cal.toEpochMs(day(2026, 1, 1), 12 * 60), cal).isEmpty())
        assertTrue(MoneyYear.notices(y, cal.toEpochMs(day(2026, 2, 1), 12 * 60), cal).isEmpty())
        assertTrue(MoneyYear.notices(null, cal.toEpochMs(day(2026, 1, 2), 12 * 60), cal).isEmpty())
    }
}
