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
        assertEquals("1 left · move or drop them", c.steps[1].line)
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
        val h = EveningCardRules.head(listOf(task("a", 10), task("b")), listOf(task("c", 20)))
        assertEquals("Today · 2 left · 40m", h.label)
        assertEquals(20f / 60f, h.progress)
        assertEquals("1 of 3 done", h.doneLine)
        assertEquals("Today · all done", EveningCardRules.head(emptyList(), listOf(task("c"))).label)
        val none = EveningCardRules.head(emptyList(), emptyList())
        assertEquals("Today", none.label)
        assertEquals(0f, none.progress)
        assertNull(none.doneLine)
        assertEquals("Today · 1 left · 1h 30m", EveningCardRules.head(listOf(task("a", 90)), emptyList()).label)
    }

    @Test
    fun theTimelineIsTheSchedule() {
        assertEquals("Schedule · Work 09:00–17:30 · 2 events", EveningCardRules.scheduleLabel("Today · Work 09:00–17:30 · 2 events"))
        assertEquals("Schedule", EveningCardRules.scheduleLabel("Today"))
    }
}
