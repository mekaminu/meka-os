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
        assertEquals("Gmail and Outlook${nb}·${nb}read, tidied, sent only on your${nb}tap", LoneWordGlue.kept(MailAccountRules.ROW_LINE))
    }

    @Test
    fun noLineStartsWithADot() {
        assertEquals("Waiting for${nb}·${nb}Someday${nb}· Decisions${nb}·${nb}Renewals", LoneWordGlue.kept("Waiting for · Someday · Decisions · Renewals"))
    }

    @Test
    fun aDotStaysWithTheWordsEitherSide() {
        assertEquals("Instant updates${nb}·${nb}last push 16:55 ago${nb}today", LoneWordGlue.kept("Instant updates · last push 16:55 ago today"))
        assertEquals("jordan@example.com${nb}· sign-in expired${nb}·${nb}sign in${nb}again", LoneWordGlue.kept("jordan@example.com · sign-in expired · sign in again"))
        assertEquals("Fri${nb}13${nb}Nov${nb}·${nb}09:00", LoneWordGlue.kept("Fri 13 Nov · 09:00"))
        assertEquals("a·b stays", LoneWordGlue.kept("a·b stays"))
    }

    @Test
    fun aLongLineKeepsSomewhereToBreak() {
        // CI run 1110: holding both dots and the last words made Health's whole row one run, broken mid-time ("0" alone).
        assertEquals("Google${nb}· jordan@example.com${nb}· synced${nb}17:10", LoneWordGlue.kept("Google · jordan@example.com · synced 17:10"))
        assertEquals("Connected${nb}·${nb}synced${nb}17:10", LoneWordGlue.kept("Connected · synced 17:10"))
        val long = "Sam rings from bbbbbbbbbbbbbbbbbbbbbb${nb}·${nb}cc dd"
        assertEquals(long, LoneWordGlue.kept(long))
    }

    @Test
    fun aDotStartingALineIsSeen() {
        assertTrue(LoneWordGlue.dotStartsLine("· last push 16:55"))
        assertTrue(LoneWordGlue.dotStartsLine("${nb}· last push"))
        assertFalse(LoneWordGlue.dotStartsLine("Outlook sign-in expired ·"))
        assertFalse(LoneWordGlue.dotStartsLine("expired${nb}·${nb}sign in"))
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
