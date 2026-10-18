package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyChallengesTest {
    private val today = CivilDate.toEpochDay(2026, 10, 17) // Saturday

    private fun l(m: Int, d: Int, pence: Long, c: MoneyCategory) = MoneyChallengeLine(CivilDate.toEpochDay(2026, m, d), pence, c)

    @Test
    fun nothingSetShowsHowToStartAndNoStatementsShowsNothing() {
        val v = MoneyChallenges.view(listOf(l(10, 1, -500, MoneyCategory.COFFEE)), MoneyChallengeSetting(), today)!!
        assertEquals(MoneyChallenges.INTRO, v.intro)
        assertNull(v.noSpend)
        assertNull(MoneyChallenges.view(emptyList(), MoneyChallengeSetting(noSpend = true), today))
    }

    @Test
    fun noSpendDaysCountOnlyEverydaySpendingAndTheStreakEndsWhereStatementsDo() {
        val lines = listOf(
            l(9, 28, -1_000, MoneyCategory.GROCERIES),
            l(10, 2, -500, MoneyCategory.COFFEE),
            l(10, 3, -8_000, MoneyCategory.BILLS), // a bill doesn't break one
            l(10, 5, 250_000, MoneyCategory.INCOME),
            l(10, 6, -2_000, MoneyCategory.TRANSFERS),
            l(10, 9, -1_200, MoneyCategory.TAKEAWAY),
            l(10, 15, -100, MoneyCategory.SUBSCRIPTIONS),
        )
        val v = MoneyChallenges.view(lines, MoneyChallengeSetting(noSpend = true), today)!!
        val ns = v.noSpend!!
        assertEquals(15, ns.days.size)
        assertEquals("13 no-spend days in October · of 15 days so far", ns.line)
        assertEquals("6 days in a row · your best this month", ns.streakLine)
        assertTrue(ns.lit)
        assertFalse(ns.days[1]) // the 2nd
        assertTrue(ns.days[2])  // the 3rd: just a bill
        assertEquals("Your statements reach Thu 15 Oct, so that's as far as these count", v.throughLine)
    }

    @Test
    fun funMoneyIsTheWeekAndCountsGoodWeeksBefore() {
        val lines = listOf(
            l(9, 30, -3_000, MoneyCategory.EATING_OUT), // week of 28 Sep: £30
            l(10, 7, -2_000, MoneyCategory.COFFEE),     // week of 5 Oct: £20
            l(10, 13, -2_500, MoneyCategory.TAKEAWAY),  // this week
            l(10, 14, -1_000, MoneyCategory.FUN),
            l(10, 14, -9_000, MoneyCategory.GROCERIES), // not fun money
            l(10, 17, 500, MoneyCategory.SHOPPING),     // a refund nets off
        )
        val f = MoneyChallenges.view(lines, MoneyChallengeSetting(funPence = 5_000), today)!!.funMoney!!
        assertEquals("£30 of £50", f.amount)
        assertEquals("£20 left · 1 day to go · within it 2 weeks in a row", f.line)
        assertFalse(f.lit)
        val over = MoneyChallenges.view(lines, MoneyChallengeSetting(funPence = 2_500), today)!!.funMoney!!
        assertEquals("£5 over · 1 day to go · within it last week", over.line)
        assertTrue(over.lit)
    }

    @Test
    fun capsShowTheMonthAndTheMonthsWithinIt() {
        val lines = listOf(
            l(8, 10, -20_000, MoneyCategory.EATING_OUT),
            l(9, 10, -9_000, MoneyCategory.EATING_OUT),
            l(10, 4, -14_000, MoneyCategory.EATING_OUT),
            l(10, 16, -500, MoneyCategory.COFFEE),
        )
        val v = MoneyChallenges.view(lines, MoneyChallengeSetting(caps = mapOf(MoneyCategory.EATING_OUT to 15_000, MoneyCategory.COFFEE to 4_000)), today)!!
        val eat = v.caps.first()
        assertEquals("Eating out this month", eat.title)
        assertEquals("£140 of £150", eat.amount)
        assertEquals("£10 left · within it last month", eat.line)
        assertTrue(eat.lit)
        assertEquals("Coffee this month", v.caps[1].title)
        assertFalse(v.caps[1].lit)
    }

    @Test
    fun parsing() {
        assertEquals(MoneyCategory.EATING_OUT to 15_000L, MoneyChallenges.parseCap("Eating out £150"))
        assertEquals(MoneyCategory.TAKEAWAY to 6_000L, MoneyChallenges.parseCap("takeaways 60 a month"))
        assertEquals(MoneyCategory.COFFEE to 4_000L, MoneyChallenges.parseCap("£40 coffee"))
        assertEquals(MoneyCategory.CLOTHES to 10_000L, MoneyChallenges.parseCap("cap clothes at £100"))
        assertNull(MoneyChallenges.parseCap("£150"))
        assertNull(MoneyChallenges.parseCap("salary £150"))
        assertEquals(5_000L, MoneyChallenges.parseFun("£50"))
        assertEquals(4_615L, MoneyChallenges.parseFun("£200 a month"))
        assertNull(MoneyChallenges.parseFun("lots"))
        val s = MoneyChallenges.setting(MoneyChallenges.capFields(MoneyCategory.FUEL, 8_000, 1L) + MoneyChallenges.noSpendFields(true, 1L))
        assertTrue(s.noSpend)
        assertEquals(mapOf(MoneyCategory.FUEL to 8_000L), s.caps)
    }

    @Test
    fun goalsStreaksNameTheRunWithNoAmountsAndStayAwayWithNoneSet() {
        val lines = listOf(
            l(8, 20, -10_000, MoneyCategory.EATING_OUT), // August over a £60 cap
            l(9, 10, -4_000, MoneyCategory.EATING_OUT),  // September within
            l(9, 30, -3_000, MoneyCategory.COFFEE),      // week of 28 Sep: £30 fun
            l(10, 7, -2_000, MoneyCategory.COFFEE),      // week of 5 Oct: £20
            l(10, 9, -1_200, MoneyCategory.TAKEAWAY),
            l(10, 13, -2_500, MoneyCategory.TAKEAWAY),   // this week
            l(10, 14, -1_000, MoneyCategory.FUN),
            l(10, 15, -100, MoneyCategory.SUBSCRIPTIONS),
        )
        assertNull(MoneyChallenges.streaks(lines, MoneyChallengeSetting(), today))
        val s = MoneyChallenges.streaks(
            lines, MoneyChallengeSetting(noSpend = true, funPence = 5_000, caps = mapOf(MoneyCategory.EATING_OUT to 6_000)), today,
        )!!
        assertEquals(MoneyChallenges.STREAKS_HEAD, s.head)
        assertEquals(listOf("No-spend days", "Fun money", "Eating out cap"), s.rows.map { it.title })
        assertEquals("1 day in a row · 11 this month", s.rowAt(0).line)
        assertFalse(s.rowAt(0).lit)
        assertEquals("Within it 7 weeks in a row · on track this week", s.rowAt(1).line)
        assertTrue(s.rowAt(1).lit)
        assertEquals("Within it last month · on track this month", s.rowAt(2).line)
        assertFalse(s.rowAt(2).lit)
        assertEquals("Counted to Thu 15 Oct, where your statements reach", s.note)
        assertTrue(s.rows.none { '£' in it.line || '£' in it.spoken })
        val over = MoneyChallenges.streaks(lines, MoneyChallengeSetting(funPence = 2_000), today)!!
        assertEquals("Over this week", over.rowAt(0).line)
        assertFalse(over.rowAt(0).lit)
    }
}
