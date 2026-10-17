package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneySharedTest {
    private val today = CivilDate.toEpochDay(2026, 10, 17)

    private fun line(id: String, m: Int, d: Int, pence: Long, merchant: String, cat: MoneyCategory = MoneyCategory.GROCERIES) =
        MoneySharedLine(id, CivilDate.toEpochDay(2026, m, d), pence, merchant, cat)

    @Test
    fun nothingSharedShowsHowToStartWithTheirName() {
        val v = MoneyShared.view(true, listOf(line("t1", 10, 1, -5_000, "tesco")), emptyMap(), emptyMap(), emptyList(), MoneySharedSetting(), "Alex Green", today)!!
        assertEquals("SHARED WITH ALEX", v.head)
        assertTrue(v.intro!!.contains("what Alex paid"))
        assertNull(v.balanceLine)
        assertEquals("What Alex paid, e.g. Tesco £42.50", v.hint)
        val anon = MoneyShared.view(true, emptyList(), emptyMap(), emptyMap(), emptyList(), MoneySharedSetting(), null, today)!!
        assertEquals("SHARED COSTS", anon.head)
        assertNull(MoneyShared.view(false, emptyList(), emptyMap(), emptyMap(), emptyList(), MoneySharedSetting(), null, today))
    }

    @Test
    fun alwaysSharedPayersAndOnePaymentAddUpAgainstWhatTheyPaid() {
        val lines = listOf(
            line("t1", 10, 2, -6_000, "tesco"),
            line("t2", 10, 9, -4_000, "tesco"),
            line("t3", 10, 10, -2_000, "cinema", MoneyCategory.FUN),
            line("t4", 10, 11, -1_000, "tesco"), // kept out on its own
            line("t5", 10, 5, 250_000, "salary", MoneyCategory.INCOME),
        )
        val entries = listOf(MoneySharedEntry("s1", "Big shop", 3_000, CivilDate.toEpochDay(2026, 10, 12), 1L))
        val v = MoneyShared.view(true, lines, mapOf("t3" to true, "t4" to false, "t5" to true), mapOf("tesco" to true), entries,
            MoneySharedSetting(), "Alex", today)!!
        assertNull(v.intro)
        assertEquals("Since Fri 2 Oct", v.sinceLine)
        assertEquals("You paid £120 · 3 payments", v.youLine)
        assertEquals("Alex paid £30 · 1 payment", v.partnerLine)
        assertEquals("Split half each · £75 each", v.splitLine)
        assertEquals("Alex owes you £45", v.balanceLine)
        assertTrue(v.balanceLit)
        assertEquals(MoneyShared.SETTLE, v.settle)
        assertEquals("October so far · £150 shared · £120 by you, £30 by Alex", v.monthLine)
        assertEquals("Always shared: Tesco", v.merchantsLine)
        assertEquals("Big shop", v.rows.single().name)
        val more = MoneyShared.view(true, lines, mapOf("t3" to true), mapOf("tesco" to true), entries,
            MoneySharedSetting(myPct = 70), "Alex", today)!!
        assertEquals("Split 70/30 · your share £112", more.splitLine)
        assertEquals("Alex owes you £18", more.balanceLine)
    }

    @Test
    fun settlingUpCountsOnlyWhatCameAfter() {
        val settled = CivilDate.toEpochDay(2026, 10, 10)
        val lines = listOf(line("t1", 10, 2, -6_000, "tesco"), line("t2", 10, 14, -4_000, "tesco"))
        val entries = listOf(
            MoneySharedEntry("s1", "Old", 9_000, CivilDate.toEpochDay(2026, 10, 3), 1L),
            MoneySharedEntry("s2", "Takeaway", 4_000, settled, 500L), // typed on the day, after settling
        )
        val v = MoneyShared.view(true, lines, emptyMap(), mapOf("tesco" to true), entries,
            MoneySharedSetting(50, settled, 400L), "Alex", today)!!
        assertEquals("Since you settled up · Sat 10 Oct", v.sinceLine)
        assertEquals("You paid £40 · 1 payment", v.youLine)
        assertEquals("Alex paid £40 · 1 payment", v.partnerLine)
        assertEquals("All square", v.balanceLine)
        assertFalse(v.balanceLit)
        assertNull(v.settle)
    }

    @Test
    fun parsingAndChoices() {
        assertEquals("Tesco" to 4_250L, MoneyShared.parse("Tesco £42.50"))
        assertEquals("Cinema" to 1_800L, MoneyShared.parse("£18 cinema"))
        assertNull(MoneyShared.parse("tesco"))
        assertEquals(60, MoneyShared.parseChoice("60%"))
        assertNull(MoneyShared.parseChoice("55"))
        assertEquals("your partner", MoneyShared.partnerName("  "))
        assertEquals(MoneyShared.DEFAULT_PCT, MoneyShared.setting(null).myPct)
        assertFalse(MoneyShared.counts(MoneySharedLine("x", today, -100, "tesco", MoneyCategory.TRANSFERS), mapOf("x" to true), emptyMap()))
    }
}
