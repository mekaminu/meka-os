package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Week board by hand, slice 1: quick add, the finger drag's landing place, edge scroll and the settling tick. */
class BoardHandsTest {
    @Test
    fun addRowAndEstimateChips() {
        assertEquals("Add a task for Thu 22", BoardHandRules.addLabel("Thu 22"))
        val chips = BoardHandRules.estimateChips(60)
        assertEquals(listOf("15m", "30m", "1h", "2h"), chips.map { it.label })
        assertEquals(listOf(false, false, true, false), chips.map { it.picked })
        assertTrue(BoardHandRules.estimateChips(null).none { it.picked })
        // A tap picks; tapping the lit one again clears it.
        assertEquals(30, BoardHandRules.toggleEstimate(null, 30))
        assertEquals(60, BoardHandRules.toggleEstimate(30, 60))
        assertNull(BoardHandRules.toggleEstimate(30, 30))
    }

    @Test
    fun aHeldCardLandsByTheMiddlesAboveTheFinger() {
        val centers = listOf(100f, 200f, 300f)
        assertEquals(0, BoardHandRules.insertIndex(centers, 50f))
        assertEquals(1, BoardHandRules.insertIndex(centers, 150f))
        assertEquals(3, BoardHandRules.insertIndex(centers, 999f))
        assertEquals(0, BoardHandRules.insertIndex(emptyList(), 10f))
    }

    @Test
    fun theGoldGapShowsWhereItLandsAndNotOnItsOwnPlace() {
        val ids = listOf("a", "h", "b", "c")
        // From this day: its own place is no gap.
        assertNull(BoardHandRules.gapBefore(ids, "h", 1))
        assertEquals(0, BoardHandRules.gapBefore(ids, "h", 0)) // to the top: before a
        assertEquals(3, BoardHandRules.gapBefore(ids, "h", 2)) // between b and c: before c
        assertEquals(4, BoardHandRules.gapBefore(ids, "h", 3)) // to the end
        // From another day: every place shows a gap.
        val other = listOf("a", "b")
        assertEquals(0, BoardHandRules.gapBefore(other, "x", 0))
        assertEquals(1, BoardHandRules.gapBefore(other, "x", 1))
        assertEquals(2, BoardHandRules.gapBefore(other, "x", 2))
        assertEquals(0, BoardHandRules.gapBefore(emptyList(), "x", 0))
        // Out of range is clamped.
        assertEquals(2, BoardHandRules.gapBefore(other, "x", 9))
    }

    @Test
    fun theBoardScrollsNearAnEdgeFasterTheCloser() {
        fun step(p: Float) = BoardHandRules.edgeStep(p, 0f, 1000f, 50f, 10f)
        assertEquals(0f, step(500f))
        assertEquals(0f, step(50f))
        assertEquals(-5f, step(25f))
        assertEquals(-10f, step(0f))
        assertEquals(-10f, step(-40f))
        assertEquals(5f, step(975f))
        assertEquals(10f, step(1000f))
        // Too small to have a middle: no scrolling.
        assertEquals(0f, BoardHandRules.edgeStep(10f, 0f, 80f, 50f, 10f))
    }

    @Test
    fun aJustTickedCardKeepsItsPlaceUntilItSettles() {
        // The core already put b (just ticked) after the open ones.
        val core = listOf("a", "c", "d", "b")
        assertEquals(listOf("a", "b", "c", "d"), BoardHandRules.settleOrder(core, mapOf("b" to 1)))
        assertEquals(core, BoardHandRules.settleOrder(core, emptyMap()))
        // A held card that left the day is ignored; an index past the end goes last.
        assertEquals(core, BoardHandRules.settleOrder(core, mapOf("z" to 0)))
        assertEquals(listOf("a", "c", "d", "b"), BoardHandRules.settleOrder(core, mapOf("b" to 9)))
        // Two ticked in a row keep both places.
        assertEquals(listOf("a", "b", "c", "d"), BoardHandRules.settleOrder(listOf("c", "d", "a", "b"), mapOf("a" to 0, "b" to 1)))
    }

    @Test
    fun theTickSaysWhatATapDoes() {
        assertEquals("Tick off Book MOT", BoardHandRules.tickLabel("Book MOT", false))
        assertEquals("Done: Book MOT. Tap to un-tick", BoardHandRules.tickLabel("Book MOT", true))
    }

    @Test
    fun aTaskTickedOffEarlyStaysInItsDayAndCarriedOnesLandOnTheDayDone() {
        val dayMs = CivilDate.DAY_MS
        val cal = LocalCalendar.UTC
        val today = CivilDate.toEpochDay(2026, 10, 21)
        val now = today * dayMs + 10 * 3_600_000L
        fun task(id: String, lifecycle: Lifecycle, scheduled: Long?, completed: Long?) =
            Task(id, id, null, lifecycle, null, scheduled, null, 0, null, null, 0, completed, false)
        val b = WeekBoardRules.build(
            listOf(
                // Planned for Friday, ticked off today: stays on Friday, faded.
                task("early", Lifecycle.DONE, (today + 2) * dayMs + 9 * 3_600_000L, now - 60_000L),
                // Carried from Monday and done today: today.
                task("carried", Lifecycle.DONE, (today - 2) * dayMs + 9 * 3_600_000L, now - 60_000L),
                task("open", Lifecycle.ACTIVE, (today + 2) * dayMs + 12 * 3_600_000L, null),
            ),
            emptyList(), now, cal,
        )
        assertEquals(listOf("open", "early"), b.day(today + 2)!!.cards.map { it.id })
        assertTrue(b.day(today + 2)!!.cards.last().done)
        assertEquals(listOf("carried"), b.day(today)!!.cards.map { it.id })
    }
}
