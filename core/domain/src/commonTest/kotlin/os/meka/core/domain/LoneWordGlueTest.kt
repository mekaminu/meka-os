package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoneWordGlueTest {
    private val nb = LifeTileRules.GLUE

    @Test
    fun theLastTwoWordsGoDownTogether() {
        assertEquals("When Obi says something out loud on${nb}his${nb}own", LoneWordGlue.kept(SpeakUpRules.ROW_LINE))
        assertEquals("Gmail and Outlook${nb}·${nb}read, tidied, sent only on${nb}your${nb}tap", LoneWordGlue.kept(MailAccountRules.ROW_LINE))
    }

    @Test
    fun aShortLastPairTakesTheWordBeforeIt() {
        // Walk-through 2026-11-15, A15: "within a minute / or two" and "updated / just now".
        val reading = LoneWordGlue.kept(TradingTracker.WAITING)
        assertTrue(reading.endsWith("minute${nb}or${nb}two"), reading)
        val updated = LoneWordGlue.kept("0x12…cd34 · \$5,038.20 · updated just now")
        assertTrue(updated.endsWith("updated${nb}just${nb}now"), updated)
        // A joiner or a figure before the pair stays as it was.
        assertTrue(LoneWordGlue.kept("Holiday plan · £185 to go").endsWith("£185 to${nb}go") || LoneWordGlue.kept("Holiday plan · £185 to go").endsWith("to${nb}go"))
    }

    @Test
    fun aDateEndingTheLineGoesDownWithTheWordBeforeIt() {
        // A15: "Card · Imported / Wed 21 Oct" on Accounts.
        val l = LoneWordGlue.kept("Card · Imported Wed 21 Oct")
        assertTrue(l.endsWith("Imported${nb}Wed${nb}21${nb}Oct"), l)
        val sheet = LoneWordGlue.kept("Current account · 18 payments · imported Wed 21 Oct")
        assertTrue(sheet.endsWith("imported${nb}Wed${nb}21${nb}Oct"), sheet)
        // Too long a run to hold with the date: the last two words still go down together as before.
        val long = LoneWordGlue.kept("Statement from Mortgageaccountholders Wed 21 Oct")
        assertTrue(long.endsWith("21${nb}Oct") && !long.contains("Mortgageaccountholders${nb}"), long)
    }

    @Test
    fun aFigureNeverEndsALine() {
        // Walk-through 2026-11-15, A15 part 2: the cover screen left a figure at a line's end.
        val sched = LoneWordGlue.kept("SCHEDULE · 1 ALL DAY · AT WORK UNTIL 17:30 · 2 EVENTS")
        assertTrue(sched.endsWith("17:30${nb}·${nb}2${nb}EVENTS"), sched)
        assertTrue(LoneWordGlue.kept("Thu 22 Oct · 2 tasks · 1 bill due").endsWith("1${nb}bill${nb}due"))
        assertTrue(LoneWordGlue.measured("1 h 30 a week · 5 lessons left").contains("5${nb}lessons"))
        assertTrue(LoneWordGlue.kept("Say nothing for 6 seconds and it stops").contains("6${nb}seconds"))
        val act = LoneWordGlue.kept("This week: 1 reminder (1 digest) · 3 changes · 2 calls stopped.")
        assertTrue(act.contains("1${nb}reminder") && act.contains("2${nb}calls"), act)
        // A price and its period go down together.
        val renews = LoneWordGlue.kept("Renews Tue 3 Nov · £10.99 a month · Subscription")
        assertTrue(renews.contains("a${nb}month") && renews.endsWith("month${nb}·${nb}Subscription"), renews)
        // A figure before a joiner stays free.
        assertEquals("Spent 12 · then more", LoneWordGlue.dotsHeld(LoneWordGlue.figuresHeld("Spent 12 · then more")).replace(nb, " "))
        assertFalse(LoneWordGlue.figuresHeld("Spent 12 · then").contains("12$nb·"))
    }

    @Test
    fun aShareOfATimeGoesDownWhole() {
        // A15 part 2: Week ahead's "family 0 h / of 8 h" and "45 min / of 8 h".
        assertTrue(LoneWordGlue.measured("Balance · family 0 h of 8 h").endsWith("0${nb}h${nb}of${nb}8${nb}h"))
        assertTrue(LoneWordGlue.measured("Balance · family 45 min of 8 h").contains("45${nb}min${nb}of${nb}8${nb}h"))
    }

    @Test
    fun monthsAsMoStayWithTheirNumber() {
        // A15: Clear my cards' Debt-free tile "Dec 2027 · 14 / mo".
        assertEquals("Dec${nb}2027 · 14${nb}mo", PhraseGlue.kept("Dec 2027 · 14 mo"))
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
        // A15: the date takes the word before it down with it.
        assertEquals("Next one due${nb}Fri${nb}13${nb}Nov", LoneWordGlue.kept("Next one due Fri 13 Nov"))
        assertEquals("Ring Sam on${nb}07700${nb}900123", LoneWordGlue.kept("Ring Sam on 07700 900123"))
    }

    @Test
    fun aloneIsOneWordOnItsLine() {
        assertTrue(LoneWordGlue.alone("own"))
        assertFalse(LoneWordGlue.alone("his${nb}own"))
        assertFalse(LoneWordGlue.alone("your tap"))
    }

    // Walk-through 2026-11-08, A14: Calendars' "→" alone at a line's start, and "work's" alone under the weather line.
    @Test
    fun anArrowStaysWithTheWordBeforeIt() {
        val s = LoneWordGlue.kept(PhoneCalendarRules.DENIED_LINE)
        assertFalse(s.split(' ').any { it == "→" || it.startsWith("→") }, "an arrow can start a line in \"$s\"")
        assertEquals(PhoneCalendarRules.DENIED_LINE, DateGlue.plain(s))
        assertTrue(LoneWordGlue.dotStartsLine("→ Permissions."))
    }

    @Test
    fun thePossessivePairGoesDownTogether() {
        assertTrue(LoneWordGlue.kept("Off · the weather is home's and work's").endsWith("and${nb}work's"))
    }

    // Walk-through 2026-11-08, A28: Health's "can't see notifications ·" and the call card's "Obi found … · on".
    @Test
    fun aDotTakesTheWordAfterALongWord() {
        val s = LoneWordGlue.kept("Navilife can't see notifications · messages aren't captured")
        assertTrue(s.contains("notifications${nb}·${nb}messages"), s)
        assertEquals("Navilife can't see notifications · messages aren't captured", DateGlue.plain(s))
    }

    @Test
    fun aSmallWordLeadsIntoTheNext() {
        val s = LoneWordGlue.kept("Obi found 01134 960123 on rosaskitchen.example · check it's theirs")
        assertTrue(s.contains("on${nb}rosaskitchen.example"), s)
        assertTrue(LoneWordGlue.kept("Reminder set on both devices").contains("on${nb}both"))
        // Just after a dot the small word is left for the dot, so a chain of dots still has somewhere to break.
        val chain = LoneWordGlue.kept("Sam's birthday · Sun 25 Oct · in 4 days · no gift ideas yet")
        assertTrue(chain.contains("·${nb}in"), chain)
        assertFalse(chain.contains("birthday${nb}·${nb}Sun"), chain)
        assertEquals("Turn it on", DateGlue.plain(LoneWordGlue.kept("Turn it on")))
        assertFalse(LoneWordGlue.leadsHeld("on").contains(nb))
    }

    @Test
    fun aPercentageTakesTheWordAfterIt() {
        val foot = LoneWordGlue.measured(MoneyCards.FOOT)
        assertTrue(foot.contains("0%${nb}ends"), foot)
        assertEquals(MoneyCards.FOOT, DateGlue.plain(foot))
        assertEquals("a drop 10% · a big order", LoneWordGlue.percentsHeld("a drop 10% · a big order"))
        assertTrue(LoneWordGlue.measured("Pitch · 10% higher").endsWith("10%${nb}higher"))
    }

    // A28 slice 5: Ask Obi's foot ended a line on "Not" over "financial advice.".
    @Test
    fun aShortLastSentenceGoesDownWhole() {
        val foot = LoneWordGlue.measured(MoneyChat.FOOT)
        assertTrue(foot.endsWith("Not${nb}financial${nb}advice."), foot)
        assertEquals(MoneyChat.FOOT, DateGlue.plain(foot))
        // A last sentence too long to hold keeps only its last two words together.
        val long = LoneWordGlue.kept("All done. Remember everything tomorrow.")
        assertFalse(long.contains("Remember$nb"), long)
        // No sentence end before: as before.
        assertFalse(LoneWordGlue.kept("Read the not financial advice").contains("not$nb"))
    }

    @Test
    fun noChipIsLeftAloneOnItsRow() {
        // Three of four fit: two and two, not three and one.
        assertEquals(listOf(2, 2), EvenRows.sizes(listOf(80, 80, 90, 90), 8, 280))
        assertEquals(listOf(4), EvenRows.sizes(listOf(60, 60, 60, 60), 8, 280))
        assertEquals(listOf(2, 2), EvenRows.sizes(listOf(100, 100, 100, 100), 8, 220))
        // Five where four fit: three and two.
        assertEquals(listOf(3, 2), EvenRows.sizes(listOf(50, 50, 50, 50, 50), 8, 230))
        // A wide chip that can't share a row keeps plain wrapping.
        assertEquals(listOf(1, 1), EvenRows.sizes(listOf(200, 200), 8, 250))
        assertEquals(emptyList(), EvenRows.sizes(emptyList(), 8, 100))
    }
}
