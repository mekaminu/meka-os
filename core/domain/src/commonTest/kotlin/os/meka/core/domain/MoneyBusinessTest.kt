package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyBusinessTest {
    private val today = CivilDate.toEpochDay(2026, 10, 17)
    private val accounts = listOf(
        MoneyAccount("a-current", "Current account", "csv", null),
        MoneyAccount("a-shop", "Shop account", "csv", null),
    )

    private fun line(account: String, y: Int, m: Int, d: Int, pence: Long, merchant: String, cat: MoneyCategory = MoneyCategory.OTHER) =
        MoneyBusinessLine(account, CivilDate.toEpochDay(y, m, d), pence, merchant, cat)

    @Test
    fun nothingMarkedShowsHowToStart() {
        val v = MoneyBusiness.view(accounts, listOf(line("a-shop", 2026, 5, 1, 50_000, "stripe")), emptySet(), emptyMap(), 20, today)!!
        assertEquals(MoneyBusiness.INTRO, v.intro)
        assertNull(v.profitLine)
        assertEquals(2, v.accounts.size)
        assertFalse(v.accounts.any { it.on })
        assertNull(MoneyBusiness.view(emptyList(), emptyList(), emptySet(), emptyMap(), 20, today))
    }

    @Test
    fun aBusinessAccountCountsThisTaxYearLessTransfersAndSetsAside() {
        val lines = listOf(
            line("a-shop", 2026, 5, 1, 150_000, "stripe", MoneyCategory.INCOME),
            line("a-shop", 2026, 6, 2, -20_000, "canva", MoneyCategory.SUBSCRIPTIONS),
            line("a-shop", 2026, 6, 3, -30_000, "transfer to current", MoneyCategory.TRANSFERS),
            line("a-shop", 2026, 4, 5, 99_900, "stripe", MoneyCategory.INCOME), // last tax year
            line("a-current", 2026, 7, 1, -5_000, "tesco", MoneyCategory.GROCERIES), // personal
        )
        val v = MoneyBusiness.view(accounts, lines, setOf("a-shop"), emptyMap(), 25, today)!!
        assertNull(v.intro)
        assertEquals("2026–27 so far · since Mon 6 Apr", v.yearLine)
        assertEquals("In £1,500 · 1 payment", v.inLine)
        assertEquals("Out £200 · 1 cost", v.outLine)
        assertEquals("Profit £1,300", v.profitLine)
        assertTrue(v.profitUp)
        assertEquals("At 25%, put aside about £325 · your share, not a tax bill", v.setAsideLine)
        assertTrue(v.chips.single { it.on }.key == "25")
        assertEquals("Subscriptions", v.costs.single().label)
        assertEquals("2025–26 · in £999 · out £0 · profit £999", v.lastYearLine)
        assertTrue(v.allowanceLine!!.startsWith("Over HMRC's £1,000 trading allowance"))
        assertTrue(v.accounts.single { it.id == "a-shop" }.on)
    }

    @Test
    fun aMarkedPayerCountsOnAnyAccountAndCanBeKeptOut() {
        val lines = listOf(
            line("a-current", 2026, 8, 1, 40_000, "etsy", MoneyCategory.INCOME),
            line("a-shop", 2026, 8, 2, -10_000, "amazon", MoneyCategory.SHOPPING),
        )
        val v = MoneyBusiness.view(accounts, lines, setOf("a-shop"), mapOf("etsy" to true, "amazon" to false), 20, today)!!
        assertEquals("Profit £400", v.profitLine)
        assertEquals("Out £0 · 0 costs", v.outLine)
        assertTrue(v.allowanceLine!!.startsWith("Under HMRC's £1,000"))
        assertEquals("Marked as business: Etsy", v.merchantsLine)
        val loss = MoneyBusiness.view(accounts, listOf(line("a-shop", 2026, 8, 2, -10_000, "canva")), setOf("a-shop"), emptyMap(), 20, today)!!
        assertEquals("Loss £100", loss.profitLine)
        assertEquals("Nothing to put aside while there's no profit", loss.setAsideLine)
        assertNull(loss.allowanceLine)
    }

    @Test
    fun selfAssessmentDatesAreTheNextThreeLitWithinAMonth() {
        val d = MoneyBusiness.dates(today)
        assertEquals(listOf(CivilDate.toEpochDay(2027, 1, 31), CivilDate.toEpochDay(2027, 7, 31), CivilDate.toEpochDay(2027, 10, 5)), d.map { it.day })
        assertTrue(d[0].title.startsWith("File your 2025–26 return online"))
        assertTrue(d[1].title.contains("2026–27"))
        assertFalse(d[0].lit)
        val jan = MoneyBusiness.dates(CivilDate.toEpochDay(2027, 1, 10))
        assertTrue(jan[0].lit)
        assertEquals("Sun 31 Jan 2027 · in 21 days", jan[0].line)
        assertEquals(CivilDate.toEpochDay(2027, 1, 31), MoneyBusiness.dates(CivilDate.toEpochDay(2027, 1, 31))[0].day)
    }

    @Test
    fun taxYearAndChoices() {
        assertEquals(2026, MoneyBusiness.taxYearOf(CivilDate.toEpochDay(2026, 4, 6)))
        assertEquals(2025, MoneyBusiness.taxYearOf(CivilDate.toEpochDay(2026, 4, 5)))
        assertEquals(30, MoneyBusiness.parseChoice("30%"))
        assertNull(MoneyBusiness.parseChoice("33"))
        assertEquals(MoneyBusiness.DEFAULT_PCT, MoneyBusiness.setAside(null))
        assertEquals(40, MoneyBusiness.setAside(MoneyBusiness.setAsideFields(40, 1L)))
        assertNotNull(MoneyBusiness.view(accounts, emptyList(), setOf("a-gone"), emptyMap(), 20, today)?.intro)
    }
}
