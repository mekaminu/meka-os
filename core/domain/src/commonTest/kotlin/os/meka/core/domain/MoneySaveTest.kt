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
        assertEquals("saved by Apr 2027", v.end)
        assertEquals(6, v.bars.size)
        assertTrue(v.bars.last().last && !v.bars.first().last)
        assertEquals(1.0, v.bars.last().share)
        assertEquals("Nov 2026: £300", v.bars.first().label)
        // A16: with no interest "You put in £1,800" under "£1,800 by Apr 2027" said it twice; week 5 A15: what's left is
        // Obi's line alone, so with no interest there is no split line.
        assertEquals("", v.split)
        assertTrue("leaves about £650 spare" in v.obi, v.obi)
        assertEquals("", MoneySave.view(30_000, 6, 0, base(null)).split)
        assertFalse(". ." in MoneySave.view(30_000, 6, 0, base(null)).spoken)
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
        assertTrue(v.split.startsWith("You put in £1,200"), v.split)
        assertEquals("At 4%", v.rates.single { it.on }.label)
        assertEquals("At 4% a year, selected", v.rates.single { it.on }.spoken)
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
        assertEquals(listOf(MoneySection.SAVE, MoneySection.PLAN_FOOT), MoneyTabs.sections(MoneyTab.PLAN, v))
        assertEquals(MoneyTab.PLAN, MoneyTabs.tabOf(MoneySection.SAVE))
        assertFalse(MoneySection.SAVE in MoneyTabs.sections(MoneyTab.PLAN, v.copy(save = null)))
    }

    /** Walk-through A4: saving more never leaves more spare, in the slider's line and Obi's alike. */
    @Test
    fun savingMoreNeverLeavesMoreSpare() {
        val left = 222_000L
        val spare = Regex("""about £([\d,]+)""")
        fun said(amount: Long): Long = spare.find(MoneySave.obi(amount, left).first)!!.groupValues[1].replace(",", "").toLong()
        var last = Long.MAX_VALUE
        for (a in listOf(25_000L, 50_000L, 110_000L, 115_000L, 150_000L, 200_000L)) {
            val now = said(a)
            assertTrue(now <= last, "£${a / 100} a month leaves £$now, more than a smaller amount's £$last")
            last = now
        }
        // Obi's line names the forecast's month less the saving.
        val v = MoneySave.view(110_000, 6, 0, base(left))
        assertTrue("about £1,120" in v.obi, v.obi)
    }

    @Test
    fun whatIsLeftIsTheForecastsMonthLessTheSaving() {
        // Walk-through 2026-11-02 A2: the forecast says "About £2,220 a month left over"; saving £1,100 leaves £1,120.
        val v = MoneySave.view(110_000, 6, 0, base(221_730))
        assertTrue("leaves about £1,120 spare" in v.obi, v.obi)
    }

    /** Walk-through A21: "About £2,230 left over" less £1,125 is £1,105 in the split, in Obi's line and in Ask Obi's answer. */
    @Test
    fun obisSpareIsTheCardsSumNotARoundingOfIt() {
        val left = 223_450L // the forecast says about £2,230
        val v = MoneySave.view(112_500, 6, 0, base(left))
        assertTrue(v.obi.startsWith("£1,125 a month still leaves about £1,105 spare"), v.obi)
        assertEquals(110_500L, MoneySave.spareAfter(112_500, left))
        for (a in listOf(2_500L, 37_500L, 110_000L, 112_500L, 187_500L)) {
            val said = MoneySave.view(a, 6, 0, base(left))
            val obi = Regex("""about (£[\d,]+)""").find(said.obi)?.groupValues?.get(1)
            if (obi != null) assertEquals(MoneyRules.fmt(MoneySave.spareAfter(a, left)), obi, "£${a / 100}: " + said.obi)
        }
    }

    /** Found and fixing week 5, A15 (Plan open): what's spare after the saving is said once, in Obi's line. */
    @Test
    fun whatIsSpareIsSaidOnce() {
        for (rate in MoneySave.RATES_BP) for (a in listOf(2_500L, 30_000L, 112_500L, 200_000L)) {
            val v = MoneySave.view(a, 12, rate, base(223_450))
            assertFalse("after saving this" in v.split || "spare" in v.split, v.split)
            val shown = listOf(v.total + " " + v.end, v.split, v.obi, v.basis)
            for (m in Regex("""£[\d,]+""").findAll(v.obi).map { it.value }) {
                assertEquals(1, shown.count { m in it }, "$m is said on more than one line: $shown")
            }
        }
    }
}
