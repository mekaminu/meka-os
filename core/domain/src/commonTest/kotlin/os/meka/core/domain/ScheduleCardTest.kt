package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleCardTest {
    private fun row(id: String, kind: TimelineKind, time: String = "09:00") =
        TimelineRow(id, kind, time, id, null, null, null, false, 0)

    @Test
    fun theCardDropsTheFreeGapsAndKeepsEverythingElse() {
        val rows = listOf(
            row("w-540", TimelineKind.WORK), row("gap-e-1", TimelineKind.GAP), row("e-1", TimelineKind.EVENT),
            row("now", TimelineKind.NOW), row("c-home", TimelineKind.COMMUTE), row("t-1", TimelineKind.TASK), row("s-gym", TimelineKind.SESSION),
        )
        assertEquals(listOf("w-540", "e-1", "now", "c-home", "t-1", "s-gym"), ScheduleCardRules.rows(rows).map { it.id })
    }

    @Test
    fun theTimeColumnShowsTheStartOnly() {
        assertEquals("09:30", ScheduleCardRules.time("09:30–10:00"))
        assertEquals("–10:00", ScheduleCardRules.time("Until 10:00"))
        assertEquals("14:32", ScheduleCardRules.time("14:32"))
        assertEquals("until 10:00", ScheduleCardRules.until("09:30–10:00"))
        assertNull(ScheduleCardRules.until("Until 10:00"))
        assertNull(ScheduleCardRules.until("14:00"))
    }

    @Test
    fun allDaySitsInTheTimeColumnOfTheFirstRowOnly() {
        assertEquals("All day", ScheduleCardRules.allDayTime(0))
        assertNull(ScheduleCardRules.allDayTime(1))
    }

    @Test
    fun theRowsRoundTheCardsCorners() {
        assertEquals(CardEdge.ONLY, ScheduleCardRules.edge(0, 1))
        assertEquals(CardEdge.FIRST, ScheduleCardRules.edge(0, 3))
        assertEquals(CardEdge.MIDDLE, ScheduleCardRules.edge(1, 3))
        assertEquals(CardEdge.LAST, ScheduleCardRules.edge(2, 3))
        assertTrue(CardEdge.ONLY.roundsTop && CardEdge.ONLY.roundsBottom)
        assertFalse(CardEdge.MIDDLE.roundsTop || CardEdge.MIDDLE.roundsBottom)
        // Two all-day rows, "+2 more", "1 earlier" folded, three rows: seven in the card.
        assertEquals(7, ScheduleCardRules.count(allDay = 2, more = true, earlier = true, earlierRows = 0, rows = 3))
        assertEquals(9, ScheduleCardRules.count(allDay = 2, more = true, earlier = true, earlierRows = 2, rows = 3))
        assertEquals(3, ScheduleCardRules.count(allDay = 0, more = false, earlier = false, earlierRows = 5, rows = 3))
    }
}
