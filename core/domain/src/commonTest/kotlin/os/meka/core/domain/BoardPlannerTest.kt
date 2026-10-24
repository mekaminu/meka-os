package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Week board redesign, Option B, slice 1: planner cards and the day's budget pill. */
class BoardPlannerTest {
    private fun task(id: String, estimate: Int? = null, done: Boolean = false, source: String? = null) = Task(
        id, id, null, if (done) Lifecycle.DONE else Lifecycle.ACTIVE, null, null, estimate, 0, null, null, 0, if (done) 1L else null, false,
        source = source,
    )

    private fun card(
        id: String, time: String? = null, line: String? = null, done: Boolean = false, carried: Boolean = false,
        estimate: Int? = null, goal: String? = null, source: String? = null,
    ) = BoardCard(task(id, estimate, done, source), "Title $id", time, line, done, carried, goalTitle = goal)

    @Test
    fun durationsReadLikeTheMockup() {
        assertEquals("0:15", BoardPlannerRules.duration(15))
        assertEquals("0:45", BoardPlannerRules.duration(45))
        assertEquals("1:00", BoardPlannerRules.duration(60))
        assertEquals("2:30", BoardPlannerRules.duration(150))
        assertEquals("0:00", BoardPlannerRules.duration(-5))
    }

    @Test
    fun hoursNeverSayZeroHours() {
        assertEquals("45m", BoardPlannerRules.hours(45))
        assertEquals("1h", BoardPlannerRules.hours(60))
        assertEquals("4h 05", BoardPlannerRules.hours(245))
        assertEquals("1h 20", BoardPlannerRules.hours(80))
        assertEquals("12h", BoardPlannerRules.hours(720))
    }

    @Test
    fun aCardsTopLineHasItsTimeAndDuration() {
        val both = BoardPlannerRules.top(card("a", time = "12:30", estimate = 45))!!
        assertEquals("12:30", both.time)
        assertEquals("0:45", both.duration)
        val timeOnly = BoardPlannerRules.top(card("b", time = "09:00"))!!
        assertNull(timeOnly.duration)
        val durationOnly = BoardPlannerRules.top(card("c", estimate = 120))!!
        assertNull(durationOnly.time)
        assertEquals("2:00", durationOnly.duration)
        // Neither: no top line, never a dash or "0:00".
        assertNull(BoardPlannerRules.top(card("d")))
        assertNull(BoardPlannerRules.top(card("e", estimate = 0)))
        // A done card keeps its top line (it fades with the rest).
        assertEquals("0:30", BoardPlannerRules.durationLabel(card("f", estimate = 30, done = true)))
        assertEquals("12:30", BoardPlannerRules.timeChip(card("a", time = "12:30", estimate = 45)))
    }

    @Test
    fun theFootCarriesTheOtherChips() {
        val c = card("a", time = "14:30", line = "Carried 2 days", carried = true, estimate = 30, goal = "Run 5k")
        assertEquals(listOf(CardChipKind.CARRIED, CardChipKind.GOAL), BoardPlannerRules.footChips(c).map { it.kind })
        assertTrue(BoardPlannerRules.footChips(card("p", time = "09:00", estimate = 15)).isEmpty())
        assertEquals("Mail", BoardPlannerRules.sourceLabel(CardSource.MAIL))
        assertEquals("Needs you", BoardPlannerRules.sourceLabel(CardSource.NEEDS))
        assertEquals("Discord", BoardPlannerRules.sourceLabel(CardSource.DISCORD))
        assertEquals("School", BoardPlannerRules.sourceLabel(CardSource.SCHOOL))
    }

    @Test
    fun aDoneCardFadesAndIsNeverStruckThrough() {
        assertEquals(0.55f, BoardPlannerRules.alpha(card("d", done = true)))
        assertEquals(1f, BoardPlannerRules.alpha(card("o")))
        assertFalse(BoardPlannerRules.STRIKE_DONE)
    }

    @Test
    fun theBudgetPillSaysPlannedAgainstFree() {
        val under = BoardPlannerRules.budget(BoardCapacity(80, 180, 0))
        assertEquals("planned", under.label)
        assertEquals("1h 20 / 3h", under.value)
        assertFalse(under.over)
        val over = BoardPlannerRules.budget(BoardCapacity(245, 180, 1))
        assertEquals("4h 05 / 3h", over.value)
        assertTrue(over.over)
        assertEquals(1f, over.fill)
        val small = BoardPlannerRules.budget(BoardCapacity(45, 30, 0))
        assertEquals("45m / 30m", small.value)
        assertTrue(small.over)
        // Nothing planned: how much is free. No free time either: says so, nothing on the right.
        val free = BoardPlannerRules.budget(BoardCapacity(0, 420, 2))
        assertEquals("free", free.label)
        assertEquals("7h", free.value)
        val none = BoardPlannerRules.budget(BoardCapacity(0, 0, 0))
        assertEquals("no free time", none.label)
        assertEquals("", none.value)
        val packed = BoardPlannerRules.budget(BoardCapacity(60, 0, 0))
        assertEquals("1h · no free time", packed.value)
        assertTrue(packed.over)
        // A compact lane draws it as one line.
        assertEquals("1h 20 / 3h", BoardPlannerRules.compactLine(under))
        assertEquals("7h free", BoardPlannerRules.compactLine(free))
        assertEquals("no free time", BoardPlannerRules.compactLine(none))
        assertEquals("1h · no free time", BoardPlannerRules.compactLine(packed))
        // The quality gate's nonsense check finds nothing in any of them.
        listOf(under, over, small, free, none, packed).forEach {
            assertTrue(ScreenSenseRules.problemsIn("${it.label} ${it.value}").isEmpty(), "${it.label} ${it.value}")
        }
    }
}
