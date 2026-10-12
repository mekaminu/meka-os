package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun nothingEnteredGivesNoLine() {
        val l = LifeTileRules.lines(LifeTileInputs())
        LifeTile.entries.forEach { assertNull(l.of(it), it.name) }
        // A blank summary is no line either.
        assertNull(LifeTileRules.lines(LifeTileInputs(peopleSummary = "  ")).of(LifeTile.PEOPLE))
        assertFalse(LifeTileRules.lines(LifeTileInputs(leaveSummary = "14 of 25 days booked · 11 left")).of(LifeTile.LEAVE)!!.lit)
    }

    @Test
    fun tilesArriveARowAtATime() {
        assertEquals(listOf(3, 3, 4, 4, 5), (0..4).map { LifeTileRules.step(3, it) })
        assertEquals(14, LifeTileRules.ORDER.size)
        assertEquals(LifeTile.LISTS, LifeTileRules.ORDER.first())
    }
}
