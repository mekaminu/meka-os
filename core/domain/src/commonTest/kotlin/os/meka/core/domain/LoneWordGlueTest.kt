package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoneWordGlueTest {
    private val nb = LifeTileRules.GLUE

    @Test
    fun theLastTwoWordsGoDownTogether() {
        assertEquals("When Ada says something out loud on her${nb}own", LoneWordGlue.kept(SpeakUpRules.ROW_LINE))
        assertEquals("Gmail and Outlook · read, tidied, sent only on your${nb}tap", LoneWordGlue.kept(MailAccountRules.ROW_LINE))
    }

    @Test
    fun noLineStartsWithADot() {
        assertEquals("Waiting for · Someday · Decisions${nb}·${nb}Renewals", LoneWordGlue.kept("Waiting for · Someday · Decisions · Renewals"))
    }

    @Test
    fun shortLinesAndLongPairsAreLeftAlone() {
        assertEquals("Connected accounts", LoneWordGlue.kept("Connected accounts"))
        assertEquals("Talk", LoneWordGlue.kept("Talk"))
        assertEquals("See notifications-and-digests extraordinarily", LoneWordGlue.kept("See notifications-and-digests extraordinarily"))
    }

    @Test
    fun datesAndNumbersStayWholeToo() {
        assertEquals("Next one due Fri${nb}13${nb}Nov", LoneWordGlue.kept("Next one due Fri 13 Nov"))
        assertEquals("Ring Sam on 07700${nb}900123", LoneWordGlue.kept("Ring Sam on 07700 900123"))
    }

    @Test
    fun aloneIsOneWordOnItsLine() {
        assertTrue(LoneWordGlue.alone("own"))
        assertFalse(LoneWordGlue.alone("her${nb}own"))
        assertFalse(LoneWordGlue.alone("your tap"))
    }
}
