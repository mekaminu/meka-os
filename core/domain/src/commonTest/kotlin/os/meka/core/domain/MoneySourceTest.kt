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

    /** The last places (Paying abroad, Challenges, Ask): bank lines are never called "your statements". */
    @Test
    fun travelChallengesAndAskSayWhereTheLinesCameFrom() {
        val today = day(2026, 10, 17)
        assertEquals("your statements", MoneySource.FILES.held)
        assertEquals("Your bank payments", MoneySource.BANK.heldCap)
        assertEquals(MoneyAskRules.NOT_ADVICE, MoneySource.FILES.notAdvice)
        assertEquals(" From your banks through Lunch Flow · not financial advice.", MoneySource.BANK.notAdvice)

        // Paying abroad: nothing paid abroad, the foot and Ask's answer.
        val names = mapOf("a-1" to "Current account")
        val home = listOf(MoneyTravelLine("a-1", day(2026, 9, 1), -4_500, "TESCO STORES 2041", MoneyCategory.GROCERIES))
        val files = assertNotNull(MoneyTravel.view(home, names, today, abroadNow = true))
        assertEquals(MoneyTravel.NOTHING, files.tip)
        assertEquals(MoneyTravel.FOOT, files.foot)
        val bank = assertNotNull(MoneyTravel.view(home, names, today, abroadNow = true, source = MoneySource.BANK))
        assertTrue(bank.tip!!.startsWith("Nothing paid abroad in your bank payments in the last year"), bank.tip)
        assertTrue(bank.banner!!.endsWith("nothing paid abroad in your bank payments yet"), bank.banner)
        assertTrue(bank.foot.startsWith("From your banks through Lunch Flow over the last year"), bank.foot)
        assertTrue(!MoneyTravel.answer(null, MoneySource.BANK).contains("statements"))

        // Challenges: where the lines reach, and an empty month.
        val ch = listOf(MoneyChallengeLine(day(2026, 9, 30), -1_000, MoneyCategory.GROCERIES))
        val on = MoneyChallengeSetting(noSpend = true)
        val v = assertNotNull(MoneyChallenges.view(ch, on, today, source = MoneySource.BANK))
        assertEquals("Your bank payments reach Wed 30 Sep, so that's as far as these count", v.throughLine)
        assertEquals("Nothing from October in your bank payments yet", v.noSpend!!.line)
        val oct = listOf(MoneyChallengeLine(day(2026, 10, 2), -1_000, MoneyCategory.GROCERIES))
        val streaks = assertNotNull(MoneyChallenges.streaks(oct, on, day(2026, 10, 3), source = MoneySource.BOTH))
        assertEquals("Counted to Fri 2 Oct, where your payments reach", streaks.note)

        // Ask: how far the lines reach, and the end of an answer.
        val lines = listOf(MoneyLine(day(2026, 9, 10), -2_000, MoneyCategoryRules.merchantKey("TESCO STORES 2041"), MoneyCategory.GROCERIES))
        val d = MoneyAskData(lines, today, emptyList(), null, source = MoneySource.BANK)
        val a = MoneyAskRules.answerSpend("what did I spend on groceries this week?", d)
        assertTrue(a.contains("Your bank payments reach Thu 10 Sep."), a)
        assertTrue(!a.contains("statements"), a)
        assertTrue(!MoneyAskRules.LOCKED.contains("statements"))
    }
}
