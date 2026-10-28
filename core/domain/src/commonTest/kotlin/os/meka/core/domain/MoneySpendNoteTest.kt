package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneySpendNoteTest {
    private fun note(
        c: MoneyCategory = MoneyCategory.GROCERIES, pounds: Long, avgPounds: Long?, partial: Boolean = false, day: Int = 30,
        usual: String = "usual", own: Boolean = false, n: Int = 3,
    ) = MoneySpendNote.of(c, own, pounds * 100, n, avgPounds?.let { it * 100 }, usual, partial, day, 30)

    @Test
    fun aFinishedMonthComparesWithTheWholeUsualMonth() {
        assertEquals(MoneySpendNote.Note("£20 under usual", MoneyTone.GOOD), note(pounds = 402, avgPounds = 422))
        assertEquals(MoneySpendNote.Note("£110 over usual", MoneyTone.OVER), note(MoneyCategory.TRANSPORT, pounds = 270, avgPounds = 160))
        assertEquals(MoneySpendNote.Note("as usual", MoneyTone.QUIET), note(pounds = 410, avgPounds = 400))
        // £25 over a £400 usual is under the share that counts as over.
        assertEquals("as usual", note(pounds = 425, avgPounds = 400).text)
    }

    @Test
    fun oneMonthBeforeIsNamed() {
        assertEquals("£20 under September", note(pounds = 402, avgPounds = 422, usual = "September").text)
        assertEquals("about the same as September", note(pounds = 400, avgPounds = 400, usual = "September").text)
        assertEquals("none in September", note(pounds = 40, avgPounds = 0, usual = "September").text)
        assertEquals("new this month", note(pounds = 40, avgPounds = 0).text)
    }

    @Test
    fun noMonthBeforeKeepsTheCountAndOtherSaysWhatItIs() {
        assertEquals("3 payments", note(pounds = 402, avgPounds = null).text)
        assertEquals("1 payment", note(pounds = 4, avgPounds = null, n = 1).text)
        assertEquals("14 without a category", note(MoneyCategory.OTHER, pounds = 424, avgPounds = 300, n = 14).text)
        // One of his own categories (Other underneath) compares like any other.
        assertEquals("£50 over usual", note(MoneyCategory.OTHER, pounds = 150, avgPounds = 100, own = true).text)
    }

    @Test
    fun thisMonthComparesWithTheUsualPaceByToday() {
        // Day 15 of 30: half of a £400 usual is £200.
        assertEquals("£50 under usual", note(pounds = 150, avgPounds = 400, partial = true, day = 15).text)
        assertEquals("£100 over usual", note(pounds = 300, avgPounds = 400, partial = true, day = 15).text)
        // Too early in the month to say.
        assertEquals("3 payments", note(pounds = 300, avgPounds = 400, partial = true, day = 3).text)
    }

    @Test
    fun billsGoOutInLumpsSoThisMonthOnlySaysOverPastTheWholeMonth() {
        assertEquals("as usual", note(MoneyCategory.BILLS, pounds = 388, avgPounds = 390, partial = true, day = 10).text)
        assertEquals("as usual", note(MoneyCategory.BILLS, pounds = 100, avgPounds = 390, partial = true, day = 10).text)
        assertEquals(MoneyTone.OVER, note(MoneyCategory.BILLS, pounds = 500, avgPounds = 390, partial = true, day = 10).tone)
        // A finished month is under like anything else.
        assertEquals("£290 under usual", note(MoneyCategory.BILLS, pounds = 100, avgPounds = 390).text)
    }
}
