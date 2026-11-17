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
    fun aPhraseThatReadsAsOneGoesDownWhole() {
        // A15 part 3: Needs you's hint ended a line on "‹ ›" over "for the next ·"; the inbox lead read "one / at a time".
        val hint = LoneWordGlue.measured(NeedsYouGroupRules.HINT)
        assertTrue(hint.contains("‹${nb}›${nb}for${nb}more${nb}·${nb}"))
        assertTrue(LoneWordGlue.measured(NeedsYouGroupRules.HINT_MAC).contains("‹${nb}›${nb}for${nb}more${nb}·${nb}"))
        assertTrue(LoneWordGlue.kept(NeedsYouGroupRules.MAIL_LEAD).contains("one${nb}at${nb}a${nb}time"))
        // A sentence's last word never ends alone: "decisions land / here." goes down as "land here.".
        assertTrue(LoneWordGlue.measured(NeedsYouStackRules.EMPTY_CAPTION).endsWith("land${nb}here."))
        // Elsewhere the words are untouched.
        assertEquals("one at", LoneWordGlue.phrasesHeld("one at"))
    }

    @Test
    fun setupsCountAndAsksTilesKeepTheirLastWords() {
        // A15 part 5: Setup's head read "… 3 come later · 1 / couldn't be checked"; the count goes down with its phrase.
        val setup = LoneWordGlue.kept(SetupRules.summaryLine(done = 20, left = 0, later = 3, unknown = 1))
        assertTrue(setup.endsWith("1${nb}couldn't${nb}be${nb}checked"), setup)
        // Ask's welcome "each takes a / minute", Countdowns' "Christmas in 65 / days", People's "waiting for / 1 · +1".
        assertTrue(LifeTileRules.shown(LifeTileRules.WELCOME).endsWith("takes${nb}a${nb}minute"))
        assertTrue(LifeTileRules.shown("Christmas in 65 days").endsWith("in${nb}65${nb}days"))
        assertTrue(LifeTileRules.shown("Alex · waiting for 1" + LifeTileRules.more(1)).endsWith("for${nb}1${nb}·${nb}+1"))
        // The words themselves never change.
        assertEquals("Christmas in 65 days", DateGlue.plain(LifeTileRules.shown("Christmas in 65 days")))
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

    // A15 part 4: Obi on calls read "Obi can ring businesses after / your tap"; a pair led by "your" takes the word before.
    @Test
    fun aPairLedByYourTakesTheWordBefore() {
        val sub = LoneWordGlue.kept(CallsSettingsRules.BOOKING_SUB)
        assertTrue(sub.endsWith("after${nb}your${nb}tap"), sub)
        assertEquals(CallsSettingsRules.BOOKING_SUB, DateGlue.plain(sub))
        assertTrue(LoneWordGlue.kept("Cap a category for the month").endsWith("for${nb}the${nb}month"))
        // Not when the held run would be too long.
        assertFalse(LoneWordGlue.kept("Ask before extraordinarily the questions").contains("extraordinarily$nb"))
        // No determiner: the last two words only, as before.
        assertFalse(LoneWordGlue.kept("Pick something nice today").contains("something$nb"))
    }

    // Walk-through 2026-11-15, g7 7: Dinners' empty line ("+ Add one with its / ingredients"), Emergency's summary ("on
    // their family / page") and the fuel sheet's line ("about 1 km is / sent") each go down with the words before them.
    @Test
    fun lifePagesLastWordsGoDownTogether() {
        assertTrue(LoneWordGlue.kept(MealsPageRules.EMPTY_SUMMARY).endsWith("with${nb}its${nb}ingredients"))
        assertTrue(LoneWordGlue.kept("2 lines · your partner sees it read-only on their family page").endsWith("family${nb}page"))
        assertTrue(LoneWordGlue.measured(FuelRules.NO_LOCATION_LINE).endsWith("km${nb}is${nb}sent"))
        assertEquals(FuelRules.NO_LOCATION_LINE, DateGlue.plain(LoneWordGlue.measured(FuelRules.NO_LOCATION_LINE)))
    }

    // A15 part 7 (reviewers g5b 8, g6b 9, g4a 14): an article or "+" never ends a line mid-sentence ("+ Add a / birthday",
    // "hold the / 🎙", "hold its / ≡"); "her", "this" and "that" stay free, and a word after a dot stays for the dot.
    @Test
    fun anArticleOrAPlusLeadsTheWordAfterIt() {
        val gifts = LoneWordGlue.kept(GiftsPageRules.COMING_EMPTY)
        assertTrue(gifts.contains("+${nb}Add a${nb}birthday"), gifts)
        assertTrue(LoneWordGlue.kept(VoiceNoteRules.EMPTY).contains("the${nb}🎙"))
        assertTrue(LoneWordGlue.kept(DayListBoardRules.HINT).contains("its${nb}≡"))
        assertTrue(LoneWordGlue.kept("Ask her about the plan for next week").contains("her about"))
        assertTrue(LoneWordGlue.kept("Off · the weather is home's and work's").startsWith("Off${nb}·${nb}the weather"))
        assertEquals(GiftsPageRules.COMING_EMPTY, DateGlue.plain(gifts))
    }

    // A15 part 4: the card sheet's APR hint ended on "on Plan)" alone on the cover; it now ends on Clear my cards.
    @Test
    fun theAprHintEndsOnClearMyCards() {
        val hint = LoneWordGlue.measured("APR, e.g. 24.9% (for Clear my cards)")
        assertTrue(hint.endsWith("my${nb}cards)"), hint)
        assertFalse(hint.contains("Plan"))
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
