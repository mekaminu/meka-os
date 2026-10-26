package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Money bugs (A4): each footer names where the lines really came from. Invented accounts and lines. */
class MoneySourceTest {
    private fun day(y: Int, m: Int, d: Int) = CivilDate.toEpochDay(y, m, d)
    private val file = MoneyAccount("a-1", "Current account", "csv", null)
    private val bank = MoneyAccount(MoneyBank.accountId("101"), "Oakbank ••1234", MoneyBank.SOURCE, null, bankStatus = MoneyBank.OK)

    @Test
    fun theSourceIsWhereTheLinesCameFrom() {
        assertEquals(MoneySource.FILES, MoneySource.of(listOf(file)))
        assertEquals(MoneySource.BANK, MoneySource.of(listOf(bank)))
        assertEquals(MoneySource.BOTH, MoneySource.of(listOf(file, bank)))
        assertEquals(MoneySource.FILES, MoneySource.of(emptyList()))
        // An account with no lines says nothing about where they came from.
        assertEquals(MoneySource.BANK, MoneySource.ofLines(listOf(file, bank), setOf(bank.id)))
        assertEquals("From your banks through Lunch Flow", MoneySource.BANK.from)
        assertEquals("From your banks through Lunch Flow and your statement files", MoneySource.BOTH.from)
        // "Nothing sent" only when every line came from a file he imported.
        assertTrue(MoneySource.FILES.worked.contains("nothing sent"))
        assertTrue(!MoneySource.BANK.worked.contains("nothing sent"))
    }

    private fun lines(): List<MoneyYearLine> {
        val out = mutableListOf<MoneyYearLine>()
        for (m in 3..9) {
            out += MoneyYearLine(day(2026, m, 1), 250_000, MoneyCategoryRules.merchantKey("ACME LTD SALARY"), MoneyCategory.INCOME)
            out += MoneyYearLine(day(2026, m, 3), -40_000, MoneyCategoryRules.merchantKey("TESCO STORES 2041"), MoneyCategory.GROCERIES)
            out += MoneyYearLine(day(2026, m, 10), -8_000, MoneyCategoryRules.merchantKey("PIZZA PLACE"), MoneyCategory.EATING_OUT)
            out += MoneyYearLine(day(2026, m, 20), -9_000, MoneyCategoryRules.merchantKey("PIZZA PLACE"), MoneyCategory.EATING_OUT)
            out += MoneyYearLine(day(2026, m, 28), -3_000, MoneyCategoryRules.merchantKey("COSTA COFFEE"), MoneyCategory.COFFEE)
        }
        return out
    }

    @Test
    fun theMonthInReviewSaysLunchFlowWhenTheLinesCameFromHisBanks() {
        val month = MoneyRules.monthOf(day(2026, 9, 1))
        val today = day(2026, 10, 5)
        val files = assertNotNull(MoneyMonthReview.view(lines(), month, today))
        assertEquals("From your statements, all of September. Worked out on this device, nothing sent. " +
            "Transfers between your accounts left out. Not financial advice.", files.foot)
        val bank = assertNotNull(MoneyMonthReview.view(lines(), month, today, source = MoneySource.BANK))
        assertEquals("From your banks through Lunch Flow, all of September. Worked out on this device. " +
            "Transfers between your accounts left out. Not financial advice.", bank.foot)
        assertTrue(!bank.foot.contains("statements"))
    }

    @Test
    fun theYearAndPaydaySayItToo() {
        val today = day(2026, 10, 5)
        val year = assertNotNull(MoneyYear.view(lines(), today, MoneySource.BOTH))
        assertTrue(year.foot.startsWith("From your banks through Lunch Flow and your statement files, "), year.foot)
        assertEquals(MoneyPayday.FOOT, MoneyPayday.foot(MoneySource.FILES))
        assertTrue(MoneyPayday.foot(MoneySource.BANK).startsWith("From your banks through Lunch Flow, Lists → Renewals"))
    }
}
