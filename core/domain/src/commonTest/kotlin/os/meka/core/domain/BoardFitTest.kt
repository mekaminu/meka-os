package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Week board by hand, slice 8: a narrow lane fits its words (the quality gate's first findings). */
class BoardFitTest {
    private fun free(start: Int, end: Int) = BoardFreeTime(start * 60_000L, end * 60_000L, start, end)

    @Test
    fun theUnfoldedFoldsLanesAreCompactAndAWideOneIsNot() {
        assertTrue(BoardFitRules.compact(134f))
        assertTrue(BoardFitRules.compact(BoardTidyRules.MIN_LANE_DP))
        assertFalse(BoardFitRules.compact(BoardTidyRules.MAX_LANE_DP))
        assertFalse(BoardFitRules.compact(BoardFitRules.COMPACT_LANE_DP))
        // The card lifted from a 134 dp lane is 118 dp wide and keeps the compact look; one from a 250 dp lane doesn't.
        assertTrue(BoardFitRules.compactCard(118f))
        assertFalse(BoardFitRules.compactCard(234f))
    }

    @Test
    fun aCompactLaneGivesTheTitleTheWidth() {
        assertEquals(28, BoardFitRules.tickColumn(true))
        assertEquals(BoardHandRules.CHECK_TARGET, BoardFitRules.tickColumn(false))
        assertEquals(3, BoardFitRules.titleLines(true))
        assertEquals(2, BoardFitRules.titleLines(false))
        assertTrue(BoardFitRules.tagBelow(true))
        assertFalse(BoardFitRules.tagBelow(false))
    }

    @Test
    fun aTitleCutToAWordOrTwoIsNotReadable() {
        // The quality gate's shot: "Fix the gate l…" and "Plan / Satur…".
        assertFalse(BoardFitRules.titleReadable("Fix the gate latch", cut = true, wholeLines = 0))
        assertFalse(BoardFitRules.titleReadable("Plan Saturday's football run with Alex", cut = true, wholeLines = "Plan ".length))
        // Whole, or a long one cut after a couple of full lines.
        assertTrue(BoardFitRules.titleReadable("Fix the gate latch", cut = false, wholeLines = 0))
        assertTrue(BoardFitRules.titleReadable("Plan Saturday's football run with Alex", cut = true, wholeLines = "Plan Saturday's ".length))
        // A short title is never cut.
        assertFalse(BoardFitRules.titleReadable("Renew the car insurance", cut = true, wholeLines = 20))
    }

    @Test
    fun theCapacityLineIsShortOnACompactLane() {
        val c = BoardCapacity(plannedMin = 90, freeMin = 120, unsized = 0)
        assertEquals("1h 30m planned · 2h free", BoardFitRules.capacityLine(c, false))
        assertEquals("1h 30m of 2h", BoardFitRules.capacityLine(c, true))
        assertEquals("5h · 1h over", BoardCapacity(300, 240, 0).shortLine)
        assertEquals("7h free", BoardCapacity(0, 420, 2).shortLine)
        // Short enough for a ~110 dp line of micro text (about 16 characters).
        listOf(BoardCapacity(15, 240, 0), BoardCapacity(135, 165, 0), BoardCapacity(600, 540, 0)).forEach {
            assertTrue(it.shortLine.length <= 16, it.shortLine)
        }
    }

    @Test
    fun freeTimeOnACompactLaneSaysOnlyWhen() {
        val stretches = listOf(free(10 * 60, 11 * 60), free(19 * 60, 21 * 60))
        assertEquals("19:00–21:00 · 2h free · +1 more", BoardFitRules.freeLine(stretches, false)!!.label)
        val compact = BoardFitRules.freeLine(stretches, true)!!
        assertEquals("19:00–21:00 free · +1 more", compact.label)
        assertEquals(BoardLookRules.freeLine(stretches)!!.spoken, compact.spoken)
        assertEquals("17:00–21:00 free", BoardFitRules.freeLine(listOf(free(17 * 60, 21 * 60)), true)!!.label)
        assertNull(BoardFitRules.freeLine(emptyList(), true))
        assertEquals(2, BoardFitRules.freeLines(true))
        assertEquals(1, BoardFitRules.freeLines(false))
    }

    private fun day(today: Boolean = false, place: String? = null) = BoardDay(
        epochDay = 20_000, weekday = "Sat", number = "24", isToday = today, isPast = false, place = null, placeLabel = place,
        chips = emptyList(), moreChips = 0, cards = emptyList(), countLine = "", accessibilityLabel = "",
    )

    @Test
    fun aCompactLaneKeepsTheTagLineSoEveryLanesFirstCardStartsLevel() {
        // Slice 9, the re-shot: Sat (no tag) started its cards higher than Thu ("Office").
        assertEquals("Office", BoardFitRules.tagLine(day(place = "Office"), compact = true))
        assertEquals("Today · Office", BoardFitRules.tagLine(day(today = true, place = "Office"), compact = true))
        assertEquals("", BoardFitRules.tagLine(day(), compact = true))
        // A roomy lane's tag sits beside the number: no line of its own.
        assertNull(BoardFitRules.tagLine(day(place = "Office"), compact = false))
        assertNull(BoardFitRules.tagLine(day(), compact = false))
        // The underline's slot is on every lane; only today's is lit.
        assertTrue(BoardFitRules.underlineLit(day(today = true)))
        assertFalse(BoardFitRules.underlineLit(day()))
    }
}
