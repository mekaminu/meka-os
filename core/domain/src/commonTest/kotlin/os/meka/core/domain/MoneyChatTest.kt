package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money redesign, Ask Obi (slice 1): the money chat on the device, from invented numbers only. */
class MoneyChatTest {
    private val today = CivilDate.toEpochDay(2026, 10, 28) // a Wednesday
    private val thisMonth = MoneyRules.monthOf(today)
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private fun line(day: Long, pence: Long, text: String) =
        MoneyLine(day, pence, MoneyCategoryRules.merchantKey(text), MoneyCategoryRules.categoryOf(text, pence))

    private val lines = listOf(
        line(d(8, 28), 250_000, "ACME PAYROLL"), line(d(9, 30), 250_000, "ACME PAYROLL"),
        line(d(2, 3), -350, "COSTA COFFEE"), line(d(5, 9), -320, "COSTA COFFEE"), line(d(10, 14), -350, "COSTA COFFEE"),
        line(d(3, 2), -500, "TESCO STORES 2041"), line(d(9, 18), -1_800, "DELIVEROO"),
    )
    private val cards = listOf(
        MoneyClearCard("c1", "Card A", 420_000, 2_490, estimated = false),
        MoneyClearCard("c2", "Card B", 180_000, 2_290, estimated = false),
    )
    private val clear = MoneyClearBase(cards, 95_000, thisMonth, 15_000, 150_000, 50_000)
    private fun data(left: Long? = 95_000, withCards: Boolean = true) = MoneyChatData(
        MoneyAskData(lines, today, emptyList(), null),
        MoneySave.base(left, today),
        if (withCards) clear.copy(leftPence = left) else null,
    )

    @Test
    fun whatTheChatAnswers() {
        listOf(
            "What if I save £300 a month for 6 months?", "Can I afford a £1,200 holiday in July?", "Which card should I pay off first?",
            "how do I clear my cards at £500 a month", "how much have I spent on coffee this year?", "save £1,200 by July",
        ).forEach { assertTrue(MoneyChat.isAbout(it), it) }
        listOf("where is the Plan tab?", "", "what's the weather?").forEach { assertFalse(MoneyChat.isAbout(it), it) }
    }

    @Test
    fun monthsAndMonthsAhead() {
        assertEquals(6, MoneyChat.monthsIn("save £300 a month for 6 months"))
        assertEquals(12, MoneyChat.monthsIn("for a year"))
        assertEquals(18, MoneyChat.monthsIn("an 18-month plan"))
        assertEquals(24, MoneyChat.monthsIn("for two years"))
        assertNull(MoneyChat.monthsIn("save £300 a month"))
        // July is next year from October; "in may" counts, a bare "may" doesn't.
        assertEquals(2027 * 12 + 6, MoneyChat.monthAhead("a holiday in July", thisMonth))
        assertEquals(2026 * 12 + 11, MoneyChat.monthAhead("by December", thisMonth))
        assertEquals(2027 * 12 + 9, MoneyChat.monthAhead("by October", thisMonth))
        assertNull(MoneyChat.monthAhead("may I save £50", thisMonth))
    }

    @Test
    fun whatIfISave() {
        val t = MoneyChat.answer("What if I save £300 a month for 6 months?", data())
        assertTrue(t.text.startsWith("Saving £300 a month for 6 months puts by £1,800 by Apr 2027."), t.text)
        assertTrue("leaves about" in t.text || "Doable" in t.text, t.text)
        assertTrue(t.text.endsWith(MoneyAskRules.NOT_ADVICE))
        assertEquals(listOf(MoneyChat.ACT_GOAL, MoneyChat.ACT_PLAN), t.actions.map { it.kind })
        assertEquals(30_000L, t.actions[0].amountPence)
        assertEquals(6, t.actions[0].months)
        // No months said: the slider's 6.
        assertEquals(6, MoneyChat.answer("what if I put aside £100 a month", data()).actions[0].months)
    }

    @Test
    fun saveAndClearTheCards() {
        val t = MoneyChat.answer("If I save £300 a month for 6 months, can I still clear my cards?", data())
        // £950 left over less £300 = £650 a month on the cards.
        assertTrue("With the other £650 a month on the cards, they'd clear by" in t.text, t.text)
        val tight = MoneyChat.answer("If I save £900 a month for 6 months, can I still clear my cards?", data())
        assertTrue("less than their minimums (£150)" in tight.text, tight.text)
        val early = MoneyChat.answer("If I save £300 a month for 6 months, can I still clear my cards?", data(left = null))
        assertTrue("Once two whole months are in" in early.text, early.text)
    }

    @Test
    fun affordSomethingLater() {
        val t = MoneyChat.answer("Can I afford a £1,200 holiday in July?", data())
        // Nov to Jul is 9 months: £1,200 / 9 = £133.33, rounded up to £135.
        assertTrue(t.text.startsWith("If you start now: put aside about £135 a month from November and you'd have £1,200 by July (9 months)."), t.text)
        assertEquals(13_500L, t.actions[0].amountPence)
        assertEquals(9, t.actions[0].months)
        val by = MoneyChat.answer("save £1,200 by July", data())
        assertTrue(by.text.startsWith("To have £1,200 by July, put aside about £135 a month from November (9 months)."), by.text)
        // Over what's usually left: said plainly.
        val big = MoneyChat.answer("can I afford £12,000 by December", data())
        assertTrue("more than you usually have left" in big.text, big.text)
    }

    @Test
    fun whichCardFirst() {
        val t = MoneyChat.answer("Which card should I pay off first?", data())
        assertTrue(t.text.startsWith("Card A first: it charges 24.9%, more than Card B's 22.9%."), t.text)
        assertTrue("At £500 a month they'd clear by" in t.text, t.text)
        assertEquals(MoneyChat.ACT_CLEAR, t.actions[0].kind)
        assertEquals(50_000L, t.actions[0].amountPence)
        assertEquals(MoneyClear.BY_RATE, t.actions[0].order)
        // An amount said is used (snapped to the slider).
        assertEquals(80_000L, MoneyChat.answer("clear my cards at £790 a month", data()).actions[0].amountPence)
        // Too little a month: no Use this plan.
        val slow = MoneyChat.answer("pay off my cards with £150 a month", data())
        assertTrue("interest keeps up" in slow.text || "clear by" in slow.text, slow.text)
        // No cards owe anything.
        val none = MoneyChat.answer("Which card should I pay off first?", data(withCards = false))
        assertTrue(none.text.startsWith("None of your cards owes anything"), none.text)
        assertTrue(none.actions.isEmpty())
    }

    @Test
    fun askRulesStillAnswerAndOtherwiseHello() {
        val t = MoneyChat.answer("how much have I spent on coffee this year?", data())
        assertTrue(t.text.startsWith("You spent £10.20 on coffee this year so far, 3 payments."), t.text)
        assertTrue(t.actions.isEmpty())
        assertEquals(MoneyChat.HELLO, MoneyChat.answer("tell me something", data()).text)
        val empty = MoneyChatData(MoneyAskData(emptyList(), today, emptyList(), null), null, null)
        assertTrue(MoneyChat.answer("what if I save £100 a month", empty).refused)
    }

    @Test
    fun suggestionsFromHisNumbers() {
        val s = MoneyChat.suggestions(data())
        assertEquals("What if I save £475 a month for 6 months?", s[0])
        assertEquals("Can I afford £300 on Saturday?", s[1])
        assertEquals("Which card should I pay off first?", s[2])
        // Takeaways (£18) beat coffee (£10.20) and groceries (£5) this year.
        assertEquals("How much have I spent on takeaways this year?", s[3])
        s.forEach { assertTrue(MoneyChat.isAbout(it), it) }
        assertEquals(3, MoneyChat.suggestions(data(withCards = false)).size)
    }

    @Test
    fun goalAndTurns() {
        val g = MoneyChat.goal(13_500, 9, thisMonth)
        assertEquals("Save £1,215 by Jul 2027", g.title)
        assertEquals("£1,215", g.target)
        assertEquals(CivilDate.toEpochDay(2027, 7, 31), g.dueDay)
        assertEquals(GoalHorizon.YEAR, g.horizon)
        var turns = emptyList<MoneyChatTurn>()
        repeat(8) { turns = MoneyChat.add(turns, MoneyChatTurn("q$it", "a")) }
        assertEquals(MoneyChat.KEEP, turns.size)
        assertEquals("q7", turns.last().question)
    }
}
