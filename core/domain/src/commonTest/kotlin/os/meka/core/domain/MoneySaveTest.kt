package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Money redesign, Plan: What if I save… — plain monthly arithmetic, chips, bars, Obi's line and the goal it makes. */
class MoneySaveTest {
    /** October 2026 (year × 12 + month − 1). */
    private val oct2026 = 2026 * 12 + 9
    private fun base(left: Long?) = MoneySaveBase(left, oct2026, MoneySave.start(left))

    @Test
    fun withNoInterestItIsTheAmountTimesTheMonthsReachedThatManyMonthsOn() {
        val v = MoneySave.view(30_000, 6, 0, base(95_000))
        assertEquals("£300", v.amount)
        assertEquals(180_000, v.totalPence)
        assertEquals("£1,800", v.total)
        assertEquals("by Apr 2027", v.end)
        assertEquals(6, v.bars.size)
        assertTrue(v.bars.last().last && !v.bars.first().last)
        assertEquals(1.0, v.bars.last().share)
        assertEquals("Nov 2026: £300", v.bars.first().label)
        assertTrue(v.split.startsWith("You put in £1,800"))
        assertFalse("interest" in v.split)
        assertEquals("Save £1,800 by Apr 2027", v.goalTitle)
        assertEquals("£1,800", v.goalTarget)
    }

    @Test
    fun interestCompoundsMonthly() {
        // £100 a month at 4% for 12 months: 100 × ((1 + 0.04/12)^12 − 1) / (0.04/12) ≈ £1,222.
        val b = MoneySave.balances(10_000, 12, 400)
        assertEquals(10_000, b.first())
        assertTrue(b.last() in 122_100..122_300, "got ${b.last()}")
        val v = MoneySave.view(10_000, 12, 400, base(null))
        assertTrue("interest about £20" in v.split, v.split)
        assertTrue(v.rates.single { it.on }.label == "At 4% a year")
    }

    @Test
    fun theSliderSnapsToTwentyFivePoundStepsWithinItsRange() {
        assertEquals(30_000, MoneySave.snap(31_000))
        assertEquals(MoneySave.MIN_PENCE, MoneySave.snap(0))
        assertEquals(MoneySave.MAX_PENCE, MoneySave.snap(9_999_999))
        // Unknown months or rates fall back to six months and no interest.
        val v = MoneySave.view(20_000, 5, 123, base(null))
        assertEquals(6, v.months.single { it.on }.value)
        assertEquals(0, v.rates.single { it.on }.value)
        assertEquals(listOf("3 months", "6 months", "12 months", "24 months"), v.months.map { it.label })
    }

    @Test
    fun theSliderStartsAtAboutHalfWhatIsLeftOver() {
        assertEquals(47_500, MoneySave.start(95_000))
        assertEquals(MoneySave.DEFAULT_PENCE, MoneySave.start(null))
        assertEquals(MoneySave.DEFAULT_PENCE, MoneySave.start(-20_000))
    }

    @Test
    fun obiSaysWhetherItFitsTheUsualMonth() {
        val fits = MoneySave.view(30_000, 6, 0, base(95_000))
        assertTrue(fits.obi.startsWith("£300 a month still leaves about £650 spare"), fits.obi)
        assertFalse(fits.obiLit)
        assertTrue("left each month after usual spending: about £650" in fits.split)
        val doable = MoneySave.view(70_000, 6, 0, base(95_000))
        assertTrue(doable.obi.startsWith("Doable"), doable.obi)
        val tight = MoneySave.view(90_000, 6, 0, base(95_000))
        assertTrue(tight.obi.startsWith("Tight") && tight.obiLit, tight.obi)
        val over = MoneySave.view(120_000, 6, 0, base(95_000))
        assertTrue(over.obi.startsWith("That's more than you usually have left (£950 a month)") && over.obiLit, over.obi)
        val short = MoneySave.view(10_000, 6, 0, base(-30_000))
        assertTrue(short.obi.startsWith("About £300 more goes out than comes in") && short.obiLit, short.obi)
        val unknown = MoneySave.view(10_000, 6, 0, base(null))
        assertTrue(unknown.obi.startsWith("Once two whole months") && !unknown.obiLit)
        assertFalse("left each month" in unknown.split)
    }

    @Test
    fun theGoalIsDueOnTheLastDayOfTheMonthItIsReached() {
        val due = MoneySave.goalDueDay(6, base(null))
        assertEquals(CivilDate.Ymd(2027, 4, 30), CivilDate.fromEpochDay(due))
        assertEquals(GoalHorizon.QUARTER, MoneySave.horizon(3))
        assertEquals(GoalHorizon.YEAR, MoneySave.horizon(24))
        // Goals reads the total as a £ number.
        assertEquals(180_000L to "£", GoalMetricRules.parseTarget(MoneySave.view(30_000, 6, 0, base(null)).goalTarget))
    }

    @Test
    fun planShowsWhatIfISaveAfterTheTipsWhenThisMonthIsIn() {
        val m = MoneyMonth(0, "October so far", "Spent £1,234", null, false, emptyList(), emptyList(), 0)
        val v = MoneyView(false, "", null, m, emptyList(), earlier = false, later = false, save = base(95_000))
        assertEquals(listOf(MoneySection.SAVE), MoneyTabs.sections(MoneyTab.PLAN, v))
        assertEquals(MoneyTab.PLAN, MoneyTabs.tabOf(MoneySection.SAVE))
        assertFalse(MoneySection.SAVE in MoneyTabs.sections(MoneyTab.PLAN, v.copy(save = null)))
    }
}
