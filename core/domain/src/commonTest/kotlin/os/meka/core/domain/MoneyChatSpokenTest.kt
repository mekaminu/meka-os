package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ask Obi, slice 3: what he says to the mic, tidied into the question the money chat reads. Invented words only. */
class MoneyChatSpokenTest {
    @Test
    fun spokenAmountsBecomePounds() {
        assertEquals("What if I save £300 a month for 6 months?", MoneyChatSpoken.question("what if I save three hundred pounds a month for 6 months"))
        assertEquals("Can I afford a £1200 holiday in July?", MoneyChatSpoken.question("can I afford a twelve hundred pound holiday in July"))
        assertEquals("Can I afford £2,000 by March?", MoneyChatSpoken.question("can I afford two grand by March"))
        assertEquals("Save £1250 by June", MoneyChatSpoken.question("save one thousand two hundred and fifty quid by June"))
        assertEquals("Save £100 a month", MoneyChatSpoken.question("save a hundred pounds a month"))
        assertEquals("Can I afford £25 on Saturday?", MoneyChatSpoken.question("can I afford twenty five pounds on Saturday"))
        assertEquals("Can I afford £1,200 in July?", MoneyChatSpoken.question("can I afford 1,200 quid in July"))
        assertEquals("Can I afford £300 on Saturday?", MoneyChatSpoken.question("Can I afford £300 on Saturday?"))
    }

    @Test
    fun wordsThatArentMoneyStayWords() {
        assertEquals("Which one should I pay off first?", MoneyChatSpoken.question("which one should I pay off first"))
        assertEquals("What if I save £50 a month for six months?", MoneyChatSpoken.question("what if I save fifty pounds a month for six months"))
        assertEquals("Spent on coffee this year", MoneyChatSpoken.question("  spent on   coffee this year "))
        assertEquals("Five twenty", MoneyChatSpoken.question("five twenty"))
    }

    @Test
    fun theSpokenQuestionIsAnsweredLikeTheTypedOne() {
        val q = MoneyChatSpoken.question("what if I save three hundred pounds a month for six months")!!
        assertEquals(30_000L, MoneyAskRules.amount(q))
        assertEquals(6, MoneyChat.monthsIn(q))
        kotlin.test.assertTrue(MoneyChat.isAbout(q))
    }

    @Test
    fun nothingSaidIsNothingAsked() {
        assertNull(MoneyChatSpoken.question(""))
        assertNull(MoneyChatSpoken.question("   "))
    }
}
