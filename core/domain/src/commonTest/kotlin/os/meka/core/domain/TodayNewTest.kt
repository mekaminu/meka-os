package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodayNewTest {
    private fun task(id: String, estimate: Int? = null) =
        Task(id, "Task $id", null, Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false).copy(estimateMinutes = estimate)

    private val peek = TomorrowPeek("Tomorrow · Mon 9 Nov · nothing planned yet", "")
    private val evening = ShutdownView.EMPTY.copy(evening = true, peek = peek)
    private val sunday = ReviewCard(offered = true, weekStart = 1, offset = 0, reviewed = false, title = "Review your week", line = "12 done · 2 still open")

    @Test
    fun beforeTheEveningThereIsNoCard() {
        assertNull(EveningCardRules.evening(ShutdownView.EMPTY, sunday))
        // Switched off in Ask → Daily: no card even in the evening.
        assertNull(EveningCardRules.evening(evening.copy(cardOff = true), sunday))
    }

    @Test
    fun aSundayEveningHasThreeStepsInOrder() {
        val left = listOf(ShutdownItem(task("a"), null, false, false, false))
        val c = EveningCardRules.evening(evening.copy(left = left), sunday)!!
        assertEquals(listOf(EveningStepKind.REVIEW, EveningStepKind.SHUT_DOWN, EveningStepKind.PLAN_TOMORROW), c.steps.map { it.kind })
        assertEquals("This evening · 0 of 3", c.label)
        assertEquals("12 done · 2 still open", c.steps[0].line)
        assertEquals("1 left · move or drop it", c.steps[1].line)
        assertEquals("Mon 9 Nov · nothing planned yet", c.steps[2].line)
        assertFalse(c.allDone)
    }

    @Test
    fun anOrdinaryEveningLeavesTheReviewOut() {
        val c = EveningCardRules.evening(evening, ReviewCard.NONE)!!
        assertEquals(listOf(EveningStepKind.SHUT_DOWN, EveningStepKind.PLAN_TOMORROW), c.steps.map { it.kind })
        assertEquals("This evening · 0 of 2", c.label)
        assertEquals("Nothing left · close the day", c.steps[0].line)
    }

    @Test
    fun stepsTickAsTheyAreDone() {
        val done = evening.copy(
            doneToday = true, doneLine = "Day shut down at 18:42",
            tomorrow = evening.tomorrow.copy(taskCount = 2),
        )
        val c = EveningCardRules.evening(done, sunday.copy(offered = false, reviewed = true))!!
        assertEquals("This evening · 3 of 3", c.label)
        assertTrue(c.allDone)
        assertEquals(EveningCardRules.REVIEW_DONE, c.steps[0].line)
        assertEquals("Day shut down at 18:42", c.steps[1].line)
    }

    @Test
    fun shownHidesTheCardWhileLaterHolds() {
        val c = EveningCardRules.evening(evening, ReviewCard.NONE)
        assertEquals(c, EveningCardRules.shown(c, laterDay = null, today = 100, minute = 18 * 60))
        assertNull(EveningCardRules.shown(c, laterDay = 100, today = 100, minute = 18 * 60))
        assertEquals(c, EveningCardRules.shown(c, laterDay = 100, today = 100, minute = 21 * 60))
        assertNull(EveningCardRules.shown(null, laterDay = null, today = 100, minute = 18 * 60))
    }

    @Test
    fun theReviewTileOnlyWithoutAnEveningCard() {
        val c = EveningCardRules.evening(evening, sunday)
        // Sunday evening: the review is the card's first step, not a tile.
        assertNull(EveningCardRules.reviewForTile(sunday, c))
        // Monday morning: no evening card, so "Review last week" is a tile.
        assertEquals(sunday, EveningCardRules.reviewForTile(sunday, null))
        assertNull(EveningCardRules.reviewForTile(null, null))
    }

    @Test
    fun laterPutsTheCardAwayUntilNine() {
        assertTrue(EveningCardRules.laterOffered(20 * 60 + 45))
        assertFalse(EveningCardRules.laterOffered(20 * 60 + 50))
        assertTrue(EveningCardRules.away(laterDay = 100, today = 100, minute = 20 * 60))
        assertFalse(EveningCardRules.away(laterDay = 100, today = 100, minute = 21 * 60))
        assertFalse(EveningCardRules.away(laterDay = 99, today = 100, minute = 18 * 60))
        assertFalse(EveningCardRules.away(laterDay = null, today = 100, minute = 18 * 60))
    }

    @Test
    fun todaysHeadCountsWhatIsLeftAndHowLong() {
        // The time is the rows' own lengths (b has none, so it adds nothing); the bar still counts b as 30 min.
        val h = EveningCardRules.head(listOf(task("a", 10), task("b")), listOf(task("c", 20)), null)
        assertEquals("Today · 2 left · 10m", h.label)
        assertEquals(20f / 60f, h.progress)
        assertEquals("1 of 3 done", h.doneLine)
        assertEquals("Today · all done", EveningCardRules.head(emptyList(), listOf(task("c")), null).label)
        val none = EveningCardRules.head(emptyList(), emptyList(), null)
        assertEquals("Today", none.label)
        assertEquals(0f, none.progress)
        assertNull(none.doneLine)
        assertEquals("Today · 1 left · 1h 30m", EveningCardRules.head(listOf(task("a", 90)), emptyList(), null).label)
        assertEquals("Today · 1 left", EveningCardRules.head(listOf(task("a")), emptyList(), null).label)
    }

    /**
     * Walk-through 2026-11-15, A5: "1 of 5 done today" and "TODAY · 4 LEFT · 3H 15M" beside Shut down's "5 left" and
     * Up next "Fix the gate latch" not among the rows, over 15 + 30 + 120 min of shown lengths.
     */
    @Test
    fun withUpNextAboveTheRowsAreTheMoreAfterIt() {
        val rows = listOf(task("bins"), task("mot", 30), task("cleaner", 15), task("football", 120))
        val h = EveningCardRules.head(rows, listOf(task("done", 15)), task("gate", 60))
        assertEquals("Today · 4 more · 2h 45m", h.label)
        assertEquals("1 of 6 done", h.doneLine)
        // Up next among the rows (it isn't drawn above them) is counted once, as a row.
        assertEquals("Today · 4 left · 2h 45m", EveningCardRules.head(rows, emptyList(), rows[1]).label)
        // Only Up next open: the head names the list, not "all done".
        assertEquals("Today", EveningCardRules.head(emptyList(), listOf(task("done")), task("gate")).label)
    }

    @Test
    fun theTimelineIsTheSchedule() {
        assertEquals("Schedule · Work 09:00–17:30 · 2 events", EveningCardRules.scheduleLabel("Today · Work 09:00–17:30 · 2 events"))
        assertEquals("Schedule", EveningCardRules.scheduleLabel("Today"))
    }
}
