package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyMonthReviewTest {
    private fun day(y: Int, m: Int, d: Int) = CivilDate.toEpochDay(y, m, d)
    private fun l(y: Int, m: Int, d: Int, pence: Long, text: String, c: MoneyCategory) =
        MoneyYearLine(day(y, m, d), pence, MoneyCategoryRules.merchantKey(text), c)
    private fun month(y: Int, m: Int) = MoneyRules.monthOf(day(y, m, 1))

    /** Feb–Aug 2026 the same each month; September eats out more, shops less and pays somewhere new; October so far. */
    private fun lines(): List<MoneyYearLine> {
        val out = mutableListOf<MoneyYearLine>()
        for (m in 2..8) {
            out += l(2026, m, 1, 250_000, "ACME LTD SALARY", MoneyCategory.INCOME)
            out += l(2026, m, 1, -90_000, "OAK LETTINGS RENT", MoneyCategory.BILLS)
            out += l(2026, m, 3, -40_000, "TESCO STORES 2041", MoneyCategory.GROCERIES)
            out += l(2026, m, 10, -8_000, "PIZZA PLACE", MoneyCategory.EATING_OUT)
            out += l(2026, m, 12, -50_000, "TO SAVINGS", MoneyCategory.TRANSFERS)
        }
        out += l(2026, 9, 1, 250_000, "ACME LTD SALARY", MoneyCategory.INCOME)
        out += l(2026, 9, 1, -90_000, "OAK LETTINGS RENT", MoneyCategory.BILLS)
        out += l(2026, 9, 3, -30_000, "TESCO STORES 2041", MoneyCategory.GROCERIES)
        out += l(2026, 9, 10, -10_000, "PIZZA PLACE", MoneyCategory.EATING_OUT)
        out += l(2026, 9, 20, -10_000, "PIZZA PLACE", MoneyCategory.EATING_OUT)
        out += l(2026, 9, 15, -6_000, "GREENLEAF GARDEN CENTRE", MoneyCategory.HOME)
        out += l(2026, 9, 12, -50_000, "TO SAVINGS", MoneyCategory.TRANSFERS)
        out += l(2026, 10, 2, -2_000, "TESCO STORES 2041", MoneyCategory.GROCERIES)
        return out
    }

    @Test
    fun aFinishedMonthReadsInPlainSentences() {
        val v = assertNotNull(MoneyMonthReview.view(lines(), month(2026, 9), day(2026, 10, 17)))
        assertEquals("SEPTEMBER IN REVIEW", v.head)
        assertFalse(v.firstLit)
        val s = v.sentences
        assertEquals("In September you spent £1,460 and £2,500 came in, so about £1,040 was left over.", s[0])
        assertEquals("That's £80 more than your 6-month average.", s[1])
        assertEquals("Most went on Bills (£900), then Groceries (£300) and Eating out (£200).", s[2])
        assertEquals("Eating out was £120 over its usual; Groceries £100 under.", s[3])
        assertTrue(s[4].startsWith("New this month: £60 at "), s[4])
        assertTrue(s[5].startsWith("The biggest single payment was £900 to "), s[5])
        assertEquals("26 days of 30 with no everyday spending.", s[6])
        assertTrue(v.foot.startsWith("From your statements, all of September."))
        assertTrue(v.spoken.startsWith("September in review. In September"))
        // Transfers never count as spending.
        assertTrue(s.none { it.contains("£500") })
    }

    @Test
    fun moreOutThanInIsLitAndAShortHistorySaysSo() {
        val ls = lines().filter { it.day >= day(2026, 8, 1) } + l(2026, 9, 25, -200_000, "SKYLINE AIRWAYS", MoneyCategory.TRAVEL)
        val v = assertNotNull(MoneyMonthReview.view(ls, month(2026, 9), day(2026, 10, 17)))
        assertTrue(v.firstLit)
        assertTrue(v.sentences[0].endsWith("so £960 more went out than came in."), v.sentences[0])
        assertTrue(v.sentences[1].contains("1-month average"), v.sentences[1])
        // No new-place sentence without six months of statements before it.
        assertTrue(v.sentences.none { it.startsWith("New this month") })
    }

    @Test
    fun nothingForThisMonthOrAThinMonth() {
        assertNull(MoneyMonthReview.view(lines(), month(2026, 10), day(2026, 10, 17)))
        assertNull(MoneyMonthReview.view(lines().take(3), month(2026, 2), day(2026, 10, 17)))
        assertNull(MoneyMonthReview.view(emptyList(), month(2026, 9), day(2026, 10, 17)))
    }

    @Test
    fun lastMonthLeadsThisMonthForItsFirstDays() {
        val early = assertNotNull(MoneyMonthReview.forScreen(lines(), month(2026, 10), day(2026, 10, 5)))
        assertEquals(month(2026, 9), early.month)
        assertTrue(early.lastMonth)
        assertNull(MoneyMonthReview.forScreen(lines(), month(2026, 10), day(2026, 10, 17)))
        val paged = assertNotNull(MoneyMonthReview.forScreen(lines(), month(2026, 8), day(2026, 10, 17)))
        assertEquals("AUGUST IN REVIEW", paged.head)
        assertFalse(paged.lastMonth)
    }

    @Test
    fun theHeadsUpComesOnTheSecondWithNoAmounts() {
        val cal = LocalCalendar.UTC
        val days = lines().map { it.day }
        assertEquals(month(2026, 9), MoneyMonthReview.readyMonth(days, day(2026, 10, 5)))
        assertNull(MoneyMonthReview.readyMonth(days, day(2026, 10, 17)))
        // Statements that stop before the month's end: not ready yet.
        assertNull(MoneyMonthReview.readyMonth(days.filter { it <= day(2026, 9, 20) }, day(2026, 10, 5)))
        // January: the year in review's heads-up says it.
        assertNull(MoneyMonthReview.readyMonth(days + day(2027, 1, 1), day(2027, 1, 3)))
        val m = MoneyMonthReview.readyMonth(days, day(2026, 10, 2))
        val n = MoneyMonthReview.notices(m, cal.toEpochMs(day(2026, 10, 2), 8 * 60), cal).single()
        assertEquals(NoticeSource.MONEY, n.source)
        assertEquals(NoticeTarget.MONEY, n.target)
        assertEquals(cal.toEpochMs(day(2026, 10, 2), 9 * 60), n.atMs)
        assertEquals("Your September money review is ready", n.title)
        assertFalse(n.text.contains("£"))
        assertTrue(MoneyMonthReview.notices(m, cal.toEpochMs(day(2026, 10, 11), 12 * 60), cal).isEmpty())
        assertTrue(MoneyMonthReview.notices(null, cal.toEpochMs(day(2026, 10, 2), 12 * 60), cal).isEmpty())
    }
}
