package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LifeTilesTest {
    @Test
    fun todaysThingsAreLitAndBeatTheSummary() {
        val l = LifeTileRules.lines(
            LifeTileInputs(
                binsTonight = "Black bin out tonight", binsSummary = "Next: Black bin · Tue 13 Oct",
                dinnerTonight = "Dinner tonight: Chilli", dinnerTitle = "Chilli", mealsSummary = "2 of 7 dinners planned",
                dateNightToday = "Date night tonight from 19:00", dateNightSummary = "Every other Friday from 19:00 · next Fri 16 Oct",
                schoolToday = "Rex: PE today", schoolSummary = "Next day off: INSET day · Mon 27 Oct",
                listsDue = "2 to chase", toBuy = 4,
            ),
        )
        assertEquals(LifeTileLine("Black bin out tonight", true), l.of(LifeTile.BINS))
        assertEquals(LifeTileLine("Dinner tonight: Chilli", true), l.of(LifeTile.DINNERS))
        assertEquals(LifeTileLine("Date night tonight from 19:00", true), l.of(LifeTile.DATE_NIGHT))
        assertEquals(LifeTileLine("Rex: PE today", true), l.of(LifeTile.SCHOOL))
        assertEquals(LifeTileLine("2 to chase", true), l.of(LifeTile.LISTS))
    }

    @Test
    fun otherwiseTheSummaryMadeShort() {
        val l = LifeTileRules.lines(
            LifeTileInputs(
                binsSummary = "Next: Black bin and food caddy · Tue 13 Oct",
                dinnerTitle = "Chilli", mealsSummary = "2 of 7 dinners planned · tonight: Chilli",
                dateNightSummary = "Every other Friday from 19:00 · next Fri 16 Oct",
                schoolSummary = "Next day off: INSET day · Mon 27 Oct",
                appointmentsSummary = "4 appointments · 1 due", appointmentsDue = 1,
                toBuy = 6, habits = 3, habitsDone = 1,
            ),
        )
        assertEquals(LifeTileLine("Black bin and food caddy · Tue 13 Oct", false), l.of(LifeTile.BINS))
        assertEquals(LifeTileLine("Chilli tonight", false), l.of(LifeTile.DINNERS))
        assertEquals(LifeTileLine("Next Fri 16 Oct", false), l.of(LifeTile.DATE_NIGHT))
        assertEquals("Next day off: INSET day · Mon 27 Oct", l.of(LifeTile.SCHOOL)?.text)
        // An appointment due wants a look.
        assertTrue(l.of(LifeTile.APPOINTMENTS)!!.lit)
        assertEquals(LifeTileLine("6 to buy", false), l.of(LifeTile.LISTS))
        assertEquals("1 of 3 habits done today", l.of(LifeTile.GOALS)?.text)
        assertEquals("All 1 habit done today", LifeTileRules.lines(LifeTileInputs(habits = 1, habitsDone = 1)).of(LifeTile.GOALS)?.text)
    }

    @Test
    fun nothingEnteredGivesAShortPromptNeverTheDescription() {
        val l = LifeTileRules.lines(LifeTileInputs())
        LifeTile.entries.forEach {
            assertEquals(LifeTileLine(LifeTileRules.prompt(it), false), l.of(it), it.name)
            assertTrue(LifeTileRules.prompt(it).length <= 34, it.name)
        }
        assertEquals("Set your bin days", l.of(LifeTile.BINS)?.text)
        assertEquals("Not filled in yet", l.of(LifeTile.EMERGENCY)?.text)
        // A blank summary is the prompt too.
        assertEquals(LifeTileRules.prompt(LifeTile.PEOPLE), LifeTileRules.lines(LifeTileInputs(peopleSummary = "  ")).of(LifeTile.PEOPLE)?.text)
        // Favourites but nothing planned this week: the prompt, not the pane's instructions.
        assertEquals("Plan this week", LifeTileRules.lines(LifeTileInputs(mealsSummary = MealRules.NOTHING_PLANNED)).of(LifeTile.DINNERS)?.text)
    }

    @Test
    fun linesSayWhatIsComing() {
        val l = LifeTileRules.lines(
            LifeTileInputs(
                leaveSummary = "14 of 25 days booked · 11 days left",
                schoolSummary = "Next day off: Half term · Mon 26–Fri 30 Oct", schoolNextOff = "Half term", schoolNextOffIn = 14,
                appointmentsSummary = "4 appointments · 2 due", appointmentsDue = 2, appointmentDueTitle = "Dentist",
                giftsSummary = "Next: Alex's birthday · In 24 days",
                personName = "Alex", personLine = "Last message Tue 13:02 · WhatsApp", peopleCount = 1,
                peopleSummary = "1 person",
            ),
        )
        assertEquals(LifeTileLine("11 days left", false), l.of(LifeTile.LEAVE))
        assertEquals(LifeTileLine("Half term in 14 days", false), l.of(LifeTile.SCHOOL))
        assertEquals(LifeTileLine("Dentist due · +1", true), l.of(LifeTile.APPOINTMENTS))
        assertEquals("Alex's birthday · In 24 days", l.of(LifeTile.GIFTS)?.text)
        assertEquals("Alex · last message Tue 13:02 · WhatsApp", l.of(LifeTile.PEOPLE)?.text)
        assertEquals("Half term tomorrow", LifeTileRules.schoolLine("Half term", 1))
        assertEquals("INSET day starts today", LifeTileRules.schoolLine("INSET day", 0))
        // Over the allowance keeps the whole line.
        assertEquals("27 days booked · 2 over your 25", LifeTileRules.lines(LifeTileInputs(leaveSummary = "27 days booked · 2 over your 25")).of(LifeTile.LEAVE)?.text)
        // Two people: the first one named, the rest counted; an acronym keeps its capitals.
        assertEquals("Alex · you promised 2 · +2",
            LifeTileRules.lines(LifeTileInputs(personName = "Alex", personLine = "You promised 2", peopleCount = 3)).of(LifeTile.PEOPLE)?.text)
    }

    @Test
    fun todaysTilesFloatToTheTopInAStableOrder() {
        val l = LifeTileRules.lines(LifeTileInputs(binsTonight = "Black bin out tonight", listsDue = "2 to chase", toBuy = 3, dinnerTitle = "Chilli"))
        val o = LifeTileRules.ordered(l)
        assertEquals(listOf(LifeTile.LISTS, LifeTile.BINS), o.take(2))
        assertEquals(LifeTileRules.ORDER - setOf(LifeTile.LISTS, LifeTile.BINS), o.drop(2))
        assertEquals(LifeTileRules.ORDER, LifeTileRules.ordered(LifeTileRules.lines(LifeTileInputs())))
    }

    @Test
    fun tilesArriveARowAtATime() {
        assertEquals(listOf(3, 3, 4, 4, 5), (0..4).map { LifeTileRules.step(3, it) })
        assertEquals(15, LifeTileRules.ORDER.size) // Car and parking, slice 1: the Car tile
        assertEquals(LifeTile.LISTS, LifeTileRules.ORDER.first())
    }

    // Ask polish, slice 1: set-up tiles sink under "To set up" and rise once they have something live.
    @Test
    fun setUpTilesSinkAndRiseWhenLive() {
        val l = LifeTileRules.lines(LifeTileInputs(binsSummary = "Next: black bin Tue", toBuy = 2))
        assertTrue(LifeTile.BINS !in LifeTileRules.toSetUp(l))
        assertTrue(LifeTile.DATE_NIGHT in LifeTileRules.toSetUp(l))
        assertTrue(LifeTile.SCHOOL in LifeTileRules.toSetUp(l))
        assertTrue(LifeTile.EMERGENCY in LifeTileRules.toSetUp(l))
        // Lists, People, Trips and Errands stay in the grid even when empty.
        val live = LifeTileRules.live(l)
        assertTrue(live.containsAll(listOf(LifeTile.LISTS, LifeTile.PEOPLE, LifeTile.TRIPS, LifeTile.ERRANDS, LifeTile.BINS)))
        assertTrue(live.none { it in LifeTileRules.toSetUp(l) })
        assertEquals(LifeTileRules.ORDER.size, live.size + LifeTileRules.toSetUp(l).size)
        assertEquals("To set up · 10", LifeTileRules.setUpLabel(LifeTileRules.toSetUp(l).size))
        // Dinners with favourites but nothing planned is a nudge, not set-up.
        val d = LifeTileRules.lines(LifeTileInputs(mealsSummary = MealRules.NOTHING_PLANNED))
        assertTrue(LifeTile.DINNERS !in LifeTileRules.toSetUp(d))
        // A lit tile still floats to the top of the live grid.
        val lit = LifeTileRules.lines(LifeTileInputs(binsTonight = "Black bin out tonight"))
        assertEquals(LifeTile.BINS, LifeTileRules.live(lit).first())
    }

    @Test
    fun firstRunOpensTheSetUpGroupWithAWelcome() {
        val empty = LifeTileRules.lines(LifeTileInputs())
        assertEquals(LifeTileRules.WELCOME, LifeTileRules.welcome(empty))
        assertTrue(LifeTileRules.setUpOpen(null, empty))
        val some = LifeTileRules.lines(LifeTileInputs(habits = 1))
        assertEquals(null, LifeTileRules.welcome(some))
        assertEquals(false, LifeTileRules.setUpOpen(null, some))
        // What he chose on this device wins.
        assertEquals(true, LifeTileRules.setUpOpen(true, some))
        assertEquals(false, LifeTileRules.setUpOpen(false, empty))
    }

    @Test
    fun habitsGrammarAndFirstNames() {
        assertEquals("0 of 1 habit done today", LifeTileRules.lines(LifeTileInputs(habits = 1)).of(LifeTile.GOALS)?.text)
        assertEquals("0 of 2 habits done today", LifeTileRules.lines(LifeTileInputs(habits = 2)).of(LifeTile.GOALS)?.text)
        assertEquals("Sam · you promised 2",
            LifeTileRules.lines(LifeTileInputs(personName = "Sam Taylor", personLine = "You promised 2", peopleCount = 1)).of(LifeTile.PEOPLE)?.text)
        assertEquals("07700 900123", LifeTileRules.firstName("07700 900123"))
        assertEquals("Mum", LifeTileRules.firstName(" Mum "))
    }

    @Test
    fun threeColumnsOnAWidePane() {
        assertEquals(2, LifeTileRules.columns(411f))
        assertEquals(3, LifeTileRules.columns(700f))
        assertEquals(listOf(4, 4, 4, 5, 5, 5), (0..5).map { LifeTileRules.step(4, it, 3) })
    }
}
