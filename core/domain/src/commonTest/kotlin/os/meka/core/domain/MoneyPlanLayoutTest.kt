package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money's layout rules on Plan: tips as rows with "2 more", the what-ifs behind one row, and one foot with its ⓘ. */
class MoneyPlanLayoutTest {
    private fun tip(i: Int) = MoneyTip("t$i", "habit", "Coffee $i× · £${i * 10}", "Halving it would save about £${i * 100} a year", i == 1, "tip $i")

    private fun chip(key: String, on: Boolean) = MoneyCutChip(key, "Halve $key", "£40 a month", on, "Halve $key")

    private fun forecast(cuts: List<MoneyCutChip> = emptyList(), extra: String? = null, trading: MoneyCutChip? = null) = MoneyForecastView(
        MoneyForecast.HEAD, null, "In about £2,500 a month", "About £300 left a month", false, emptyList(), cuts, extra,
        "From Jul to Sep (3 whole months) · ${MoneyForecast.NOT_ADVICE}", trading = trading,
    )

    private fun month() = MoneyMonth(0, "October so far", "Spent £1,234 · in £2,500", null, false, emptyList(), emptyList(), 0)

    @Test
    fun threeTipsShowThenTheRestFoldBehindMore() {
        val tips = (1..5).map(::tip)
        assertEquals(listOf("t1", "t2", "t3"), MoneyPlanLayout.tipsShown(tips, open = false).map { it.id })
        assertEquals(5, MoneyPlanLayout.tipsShown(tips, open = true).size)
        assertEquals("2 more ›", MoneyPlanLayout.tipsMore(5, open = false))
        assertEquals(MoneyRefunds.FEWER, MoneyPlanLayout.tipsMore(5, open = true))
        assertNull(MoneyPlanLayout.tipsMore(3, open = false))
        assertEquals("t4", MoneyPlanLayout.tipOf(tips, "t4")?.id)
        assertNull(MoneyPlanLayout.tipOf(tips, "gone"))
        assertNull(MoneyPlanLayout.tipOf(tips, null))
    }

    @Test
    fun theWhatIfRowSaysWhichAreOn() {
        assertEquals(MoneyPlanLayout.WHAT_IF_TRY, MoneyPlanLayout.whatIfBrief(forecast(listOf(chip("Transport", false)))))
        assertFalse(MoneyPlanLayout.whatIfOn(forecast(listOf(chip("Transport", false)))))
        val one = forecast(listOf(chip("Transport", true), chip("Groceries", false)), extra = "+£200 a month coming in · your own figure")
        assertEquals("Halve Transport · +£200 a month coming in", MoneyPlanLayout.whatIfBrief(one))
        assertTrue(MoneyPlanLayout.whatIfOn(one))
        val many = forecast(listOf(chip("Transport", true), chip("Groceries", true)), extra = "+£200 a month coming in · your own figure",
            trading = chip("Trading", true))
        assertEquals("Halve Transport · Halve Groceries · +2 more", MoneyPlanLayout.whatIfBrief(many))
        assertTrue(MoneyPlanLayout.hasWhatIf(one))
        assertFalse(MoneyPlanLayout.hasWhatIf(one.copy(notReady = "Not enough months yet")))
    }

    @Test
    fun planEndsOnOneFootWhoseInfoSaysHowEachPartIsWorkedOut() {
        val bare = MoneyView(locked = false, summary = "Spent £1,234", empty = null, month = month(), accounts = emptyList(), earlier = false, later = false)
        assertTrue(MoneySection.PLAN_FOOT !in MoneyTabs.sections(MoneyTab.PLAN, bare))
        assertEquals(MoneyTabs.EMPTY_PLAN, MoneyTabs.empty(MoneyTab.PLAN, bare))

        val v = bare.copy(tips = listOf(tip(1)), forecast = forecast(), save = MoneySaveBase(30_000, 10, 10_000))
        val s = MoneyTabs.sections(MoneyTab.PLAN, v)
        assertEquals(MoneySection.PLAN_FOOT, s.last())
        assertEquals(MoneyTab.PLAN, MoneyTabs.tabOf(MoneySection.PLAN_FOOT))
        val info = MoneyTabs.info(MoneyTab.PLAN, v)!!
        assertEquals(MoneyTabs.PLAN_FROM, info.first())
        assertTrue(MoneySave.BASIS in info)
        assertTrue(MoneyClear.BASIS !in info, "Clear my cards isn't on this Plan")
        assertTrue(info.any { it.startsWith("From Jul to Sep") })
        assertEquals(MoneyRules.FOOT, info.last())
    }
}
