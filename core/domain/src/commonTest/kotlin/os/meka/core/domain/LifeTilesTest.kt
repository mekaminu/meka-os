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
                giftsSummary = "Next: Jeanette's birthday · In 24 days",
                personName = "Jeanette", personLine = "Last message Tue 13:02 · WhatsApp", peopleCount = 1,
                peopleSummary = "1 person",
            ),
        )
        assertEquals(LifeTileLine("11 days left", false), l.of(LifeTile.LEAVE))
        assertEquals(LifeTileLine("Half term in 14 days", false), l.of(LifeTile.SCHOOL))
        assertEquals(LifeTileLine("Dentist due · +1", true), l.of(LifeTile.APPOINTMENTS))
        assertEquals("Jeanette's birthday · In 24 days", l.of(LifeTile.GIFTS)?.text)
        assertEquals("Jeanette · last message Tue 13:02 · WhatsApp", l.of(LifeTile.PEOPLE)?.text)
        assertEquals("Half term tomorrow", LifeTileRules.schoolLine("Half term", 1))
        assertEquals("INSET day starts today", LifeTileRules.schoolLine("INSET day", 0))
        // Over the allowance keeps the whole line.
        assertEquals("27 days booked · 2 over your 25", LifeTileRules.lines(LifeTileInputs(leaveSummary = "27 days booked · 2 over your 25")).of(LifeTile.LEAVE)?.text)
        // Two people: the first one named, the rest counted; an acronym keeps its capitals.
        assertEquals("Jeanette · you promised 2 · +2",
            LifeTileRules.lines(LifeTileInputs(personName = "Jeanette", personLine = "You promised 2", peopleCount = 3)).of(LifeTile.PEOPLE)?.text)
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
        assertEquals(14, LifeTileRules.ORDER.size)
        assertEquals(LifeTile.LISTS, LifeTileRules.ORDER.first())
    }
}
