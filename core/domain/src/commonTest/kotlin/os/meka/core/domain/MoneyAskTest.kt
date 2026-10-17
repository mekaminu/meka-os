package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Money manager (16), slice 1: Ask and Talk's money answers, from invented statements only. */
class MoneyAskTest {
    private val today = CivilDate.toEpochDay(2026, 10, 16) // a Friday
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private fun line(day: Long, pence: Long, text: String) =
        MoneyLine(day, pence, MoneyCategoryRules.merchantKey(text), MoneyCategoryRules.categoryOf(text, pence))

    private val lines = listOf(
        line(d(7, 30), 250_000, "ACME PAYROLL"), line(d(8, 28), 250_000, "ACME PAYROLL"), line(d(9, 30), 250_000, "ACME PAYROLL"),
        line(d(7, 20), -1_099, "STREAMLY.COM"), line(d(8, 20), -1_099, "STREAMLY.COM"), line(d(9, 20), -1_099, "STREAMLY.COM"),
        line(d(9, 4), -2_400, "DELIVEROO"), line(d(9, 18), -1_800, "DELIVEROO"), line(d(8, 12), -1_500, "DELIVEROO"),
        line(d(10, 2), -12_000, "TESCO STORES 2041"), line(d(10, 13), -4_000, "TESCO STORES 2041"), line(d(9, 9), -6_000, "TESCO STORES 2041"),
        line(d(10, 9), -320, "COSTA COFFEE"), line(d(10, 14), -350, "COSTA COFFEE"),
    )

    private fun payday(left: Long?) = MoneyPaydayView(
        "UNTIL PAYDAY", null, "Payday Fri 30 Oct · in 14 days", "Acme Payroll into Current account · about £2,500", null, null,
        emptyList(), 0, "a-current", "Current account", null, left != null,
        left?.let { "Safe to spend until payday · about £${it / 100} · about £${it / 100 / 14} a day" }, false, MoneyPayday.FOOT,
        paydayDay = d(10, 30), leftPence = left,
    )

    private fun data(left: Long? = 60_000) = MoneyAskData(
        lines, today,
        MoneyAskRules.upcoming(MoneyTips.regular(lines, today), listOf(MoneyBill("Water bill", d(10, 19), 4_000), MoneyBill("Streamly", d(10, 20), 1_099)), today),
        payday(left),
    )

    @Test
    fun whatCountsAsAMoneyQuestion() {
        listOf(
            "how much did I spend on takeaways last month?", "what have I spent at Tesco this week", "what's coming out this week?",
            "can I afford £300 on Saturday?", "when's payday?", "how much is safe to spend?", "any direct debits due tomorrow",
        ).forEach { assertTrue(MoneyAskRules.isAbout(it), it) }
        listOf(
            "how much time did I spend on email?", "can I afford to take Friday off?", "how do I import a statement?",
            "what size shoes is Alex?", "add milk to shopping", "how does safe to spend work?", "am I going out on Friday?",
            "what's coming out at the cinema this week?",
        ).forEach { assertFalse(MoneyAskRules.isAbout(it), it) }
        assertEquals(30_000L, MoneyAskRules.amount("can I afford £300 on Saturday"))
        assertEquals(150_000L, MoneyAskRules.amount("could I afford £1.5k"))
        assertEquals(125_050L, MoneyAskRules.amount("afford 1,250.50 pounds?"))
    }

    @Test
    fun spendingByCategoryShopAndEverything() {
        assertEquals(
            "You spent £42 on takeaways last month (September), 2 payments. In August it was £15.",
            MoneyAskRules.answer("how much did I spend on takeaways last month?", data()),
        )
        assertEquals("You spent £40 at Tesco Stores this week, 1 payment. Your statements reach Wed 14 Oct.", MoneyAskRules.answer("what did I spend at tesco this week", data()))
        assertEquals(
            "You spent £166.70 this month so far, 4 payments. Your 2-month average before that is £69 a month. Most went on groceries £160 and coffee £7. Your statements reach Wed 14 Oct.",
            MoneyAskRules.answer("how much have I spent?", data()),
        )
        assertEquals("Nothing on coffee yesterday. Your statements reach Wed 14 Oct.", MoneyAskRules.answer("how much did I spend on coffee yesterday", data()))
        assertEquals(
            "Your statements start 20 Jul 2026, so there's nothing that far back.",
            MoneyAskRules.answer("what did I spend in June", data()),
        )
    }

    @Test
    fun whatsComingOutAndPayday() {
        // Regular Streamly is due about the 20th; the radar's Streamly is the same payment, counted once.
        assertEquals(
            "About £51 coming out in the next 7 days, 2 payments: Water bill £40 on Mon 19 Oct and Streamly.com £10.99 on Tue 20 Oct. Regular payments' days are as they've been, so allow a day or two.",
            MoneyAskRules.answer("what's coming out of my account?", data()),
        )
        assertEquals(
            "Nothing regular is due to come out tomorrow, as far as your statements, Lists → Renewals and your cards show.",
            MoneyAskRules.answer("anything coming out tomorrow?", data()),
        )
        assertEquals("Payday Fri 30 Oct · in 14 days · Acme Payroll into Current account · about £2,500.", MoneyAskRules.answer("when's payday?", data()))
    }

    @Test
    fun canIAffordItFromSafeToSpend() {
        assertEquals(
            "Yes, by your numbers: after the bills before payday you'd have about £600; £300 on Saturday leaves about £300, about £23 a day until payday on Fri 30 Oct. From your own statements · not financial advice.",
            MoneyAskRules.answer("can I afford £300 on Saturday?", data()),
        )
        assertTrue(MoneyAskRules.answer("can I afford £550 tomorrow", data()).startsWith("Only just: "))
        assertTrue(MoneyAskRules.answer("can I afford £900", data()).startsWith("Not without going short: after the bills before payday you'd have about £600, so £900 would be about £300 more"))
        assertTrue(MoneyAskRules.answer("can I afford £300 on Saturday?", data(left = null)).startsWith("I'd need Current account's balance"))
    }
}
