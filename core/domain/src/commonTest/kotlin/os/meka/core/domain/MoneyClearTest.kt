package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money redesign, Plan: Clear my cards — balances and rates, the two orders, the dates, the minimums and Obi's line. */
class MoneyClearTest {
    private val today = CivilDate.toEpochDay(2026, 10, 27)
    private val oct2026 = 2026 * 12 + 9

    private fun card(id: String, name: String, statement: Long?, apr: Int? = null, charge: Boolean = false, promo: Long? = null) =
        MoneyCard(id, name, if (charge) MoneyCards.CHARGE else MoneyCards.CREDIT, statementPence = statement, aprBp = apr, promoEndDay = promo)

    private fun twoCards(left: Long? = 95_000) = MoneyClear.base(
        listOf(card("a", "Card ••09", 420_000, 2_490), card("b", "Card ••01", 180_000, 2_290)), emptyMap(), emptyList(), left, today,
    )!!

    @Test
    fun theBaseTakesCreditCardsThatOweSomethingBiggestFirstAndLeavesChargeCardsOut() {
        val b = MoneyClear.base(
            listOf(
                card("s", "Small", 30_000, 2_000), card("big", "Big", null, 2_500), card("amex", "Amex Platinum", 90_000, charge = true),
                card("zero", "Paid off", 0, 2_000), card("none", "No balance", null),
            ),
            mapOf("big" to -250_000L), emptyList(), null, today,
        )!!
        assertEquals(listOf("Big", "Small"), b.cards.map { it.name })
        assertEquals(250_000, b.cards.first().owedPence)
        assertEquals(oct2026, b.thisMonth)
        assertTrue(b.minPence >= MoneyClear.MIN_PAY_PENCE && b.minPence % MoneyClear.STEP_PENCE == 0L)
        assertTrue(b.startPence in b.minPence..b.maxPence)
        assertNull(MoneyClear.base(listOf(card("amex", "Amex", 90_000, charge = true)), emptyMap(), emptyList(), null, today))
    }

    @Test
    fun aRateComesFromTheLastInterestChargeWhenNoneIsTyped() {
        val lines = listOf(
            MoneyCardLine("a", today - 40, -9_000, "INTEREST CHARGED"),
            MoneyCardLine("a", today - 10, -8_715, "Interest on purchases"),
            MoneyCardLine("a", today - 5, -2_000, "TESCO"),
        )
        // £87.15 a month on £4,200 → 24.9% a year.
        assertEquals(2_490, MoneyClear.estimateApr("a", 420_000, lines, today))
        val b = MoneyClear.base(listOf(card("a", "Card ••09", 420_000)), emptyMap(), lines, null, today)!!
        assertEquals(2_490, b.cards.single().aprBp)
        assertTrue(b.cards.single().estimated)
        val v = MoneyClear.view(60_000, MoneyClear.BY_RATE, b)
        assertTrue(v.rows.single().rate.startsWith("about 24.9% APR"), v.rows.single().rate)
        assertNull(MoneyClear.estimateApr("a", 420_000, lines.filter { it.pence == -2_000L }, today))
    }

    @Test
    fun anUnknownRateCountsAsNothingAndSaysSo() {
        val b = MoneyClear.base(listOf(card("a", "Card ••09", 120_000)), emptyMap(), emptyList(), 95_000, today)!!
        val v = MoneyClear.view(20_000, MoneyClear.BY_RATE, b)
        assertTrue(v.rows.single().rateLit)
        assertTrue(v.rows.single().rate.startsWith("APR ?"))
        assertEquals("£0", v.interest)
        // £1,200 at £200 a month with no interest: six months, Apr 2027.
        assertEquals("Apr 2027 · 6 mo", v.free)
        assertTrue("Type Card ••09's APR" in v.obi, v.obi)
        assertTrue(v.orders.isEmpty())
        assertEquals("Pay each month", v.payLabel)
    }

    @Test
    fun highestInterestFirstCostsLessThanSmallestFirst() {
        val b = twoCards()
        val rate = MoneyClear.run(b.cards, 60_000, MoneyClear.BY_RATE, b.thisMonth)
        val small = MoneyClear.run(b.cards, 60_000, MoneyClear.BY_SMALL, b.thisMonth)
        assertNotNull(rate.months)
        assertTrue(rate.interestPence < small.interestPence, "${rate.interestPence} vs ${small.interestPence}")
        // Smallest-first clears the small card sooner.
        assertTrue(small.clearMonth[1]!! < rate.clearMonth[1]!!)
        val v = MoneyClear.view(60_000, MoneyClear.BY_RATE, b)
        assertEquals("Pay each month, both cards", v.payLabel)
        assertEquals("£600", v.pay)
        assertEquals(listOf("Highest interest first", "Smallest balance first"), v.orders.map { it.label })
        assertTrue(v.orders.first().on)
        assertTrue(v.obi.startsWith("Clearing Card ••09 first saves about"), v.obi)
        assertTrue(v.rows.all { "clear by" in it.rate })
        assertTrue(v.goalTitle!!.startsWith("Clear my cards by "))
        val s = MoneyClear.view(60_000, MoneyClear.BY_SMALL, b)
        assertTrue(s.obi.startsWith("Smallest-first clears Card ••01 sooner, but costs about"), s.obi)
    }

    @Test
    fun theMinimumsTakeYearsAndCostMore() {
        val b = twoCards()
        val v = MoneyClear.view(60_000, MoneyClear.BY_RATE, b)
        assertTrue(v.vsMin.startsWith("Paying only the minimums: "), v.vsMin)
        val mins = MoneyClear.run(b.cards, 0, MoneyClear.BY_RATE, b.thisMonth, minimumsOnly = true)
        val plan = MoneyClear.run(b.cards, 60_000, MoneyClear.BY_RATE, b.thisMonth)
        assertTrue(mins.interestPence > plan.interestPence)
        assertTrue((mins.months ?: Int.MAX_VALUE) > plan.months!!)
    }

    @Test
    fun obiSaysWhetherTheAmountFitsTheUsualMonth() {
        val fits = MoneyClear.view(60_000, MoneyClear.BY_RATE, twoCards(95_000))
        assertTrue("£600 a month fits inside an average month, leaving about £350." in fits.obi, fits.obi)
        assertFalse(fits.obiLit)
        val over = MoneyClear.view(120_000, MoneyClear.BY_RATE, twoCards(95_000))
        assertTrue("That's more than you usually have left (£950 a month)" in over.obi, over.obi)
        assertTrue(over.obiLit)
        val unknown = MoneyClear.view(60_000, MoneyClear.BY_RATE, twoCards(null))
        assertTrue("Once two whole months" in unknown.obi)
    }

    @Test
    fun aZeroPercentPeriodCountsNoInterestUntilItEnds() {
        val promo = CivilDate.toEpochDay(2027, 6, 1)
        val withPromo = MoneyClear.base(listOf(card("a", "Card", 120_000, 2_490, promo = promo)), emptyMap(), emptyList(), null, today)!!
        val run = MoneyClear.run(withPromo.cards, 20_000, MoneyClear.BY_RATE, withPromo.thisMonth)
        assertEquals(0, run.interestPence)
        assertTrue("0% to Jun 2027" in MoneyClear.view(20_000, MoneyClear.BY_RATE, withPromo).rows.single().rate)
    }

    @Test
    fun tooLittleNeverClearsAndMakesNoGoal() {
        val b = MoneyClear.base(listOf(card("a", "Card", 1_000_000, 4_000)), emptyMap(), emptyList(), null, today)!!
        // The minimum alone (2.5% of £10,000) roughly matches the interest at 40%; the slider's floor.
        val v = MoneyClear.view(0, MoneyClear.BY_RATE, b)
        assertEquals(b.minPence, v.payPence)
        if (v.freeLit) {
            assertEquals("Not at this pace", v.free)
            assertNull(v.goalTitle)
            assertNull(MoneyClear.goalDueDay(0, MoneyClear.BY_RATE, b))
        }
        val high = MoneyClear.view(b.maxPence, MoneyClear.BY_RATE, b)
        assertFalse(high.freeLit)
        val due = MoneyClear.goalDueDay(b.maxPence, MoneyClear.BY_RATE, b)!!
        val ymd = CivilDate.fromEpochDay(due)
        assertEquals(CivilDate.lengthOfMonth(ymd.year, ymd.month), ymd.day)
    }

    @Test
    fun theCardSheetTakesAnApr() {
        assertEquals(2_490, MoneyCards.parseApr("24.9%"))
        assertEquals(2_290, MoneyCards.parseApr("22.9% APR"))
        assertEquals(2_200, MoneyCards.parseApr("APR 22"))
        assertEquals(1_995, MoneyCards.parseApr("19,95 %"))
        assertNull(MoneyCards.parseApr("lots"))
        assertNull(MoneyCards.parseApr("123%"))
        assertEquals("24.9%", MoneyCards.aprText(2_490))
        assertEquals("22%", MoneyCards.aprText(2_200))
        val row = MoneyCards.row(card("a", "Card", 10_000, 2_490), today, emptyList())
        assertEquals("24.9%", row.fields.single { it.key == MoneyCards.APR }.value)
        assertTrue(MoneyCards.row(card("c", "Amex", 10_000, charge = true), today, emptyList()).fields.none { it.key == MoneyCards.APR })
    }

    @Test
    fun planShowsClearMyCardsWhenACardOwesSomething() {
        val m = MoneyMonth(0, "October so far", "Spent £1,234", null, false, emptyList(), emptyList(), 0)
        val v = MoneyView(false, "", null, m, emptyList(), earlier = false, later = false, clear = twoCards())
        assertEquals(listOf(MoneySection.CLEAR, MoneySection.PLAN_FOOT), MoneyTabs.sections(MoneyTab.PLAN, v))
        assertEquals(MoneyTab.PLAN, MoneyTabs.tabOf(MoneySection.CLEAR))
        assertFalse(MoneySection.CLEAR in MoneyTabs.sections(MoneyTab.PLAN, v.copy(clear = null)))
    }
}
