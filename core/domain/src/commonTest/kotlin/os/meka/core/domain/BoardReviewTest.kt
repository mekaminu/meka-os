package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week board item (8): the Friday review — planned against done on the Board, the best day, what kept slipping. */
class BoardReviewTest {
    private val dayMs = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    private val mon = CivilDate.toEpochDay(2026, 10, 19)
    private val tue = mon + 1
    private val wed = mon + 2
    private val fri = mon + 4

    private fun at(day: Long, hour: Int) = day * dayMs + hour * 3_600_000L

    private fun task(
        id: String, title: String = id, scheduled: Long? = null, completed: Long? = null, week: Long? = null,
        lifecycle: Lifecycle = Lifecycle.ACTIVE, recurrence: String? = null, asked: Long? = null, created: Long = 0,
    ) = Task(
        id, title, null, lifecycle, null, scheduled, null, 0, null, null, created, completed, false,
        recurrenceRule = recurrence, carryAskedDay = asked, boardWeek = week,
    )

    private fun done(id: String, day: Long, scheduled: Long? = at(day, 9)) =
        task(id, scheduled = scheduled, completed = at(day, 12), lifecycle = Lifecycle.DONE)

    @Test
    fun plannedCountsDoneAndOpenOnTheWeeksDaysAndLaneAndNamesTheBestDay() {
        val tasks = listOf(
            done("a", mon), done("b", tue), done("c", tue), done("d", tue, scheduled = null),
            task("open", scheduled = at(fri, 10)),
            task("lane", week = mon),
            task("floating"),
            task("someday", lifecycle = Lifecycle.SOMEDAY, scheduled = at(tue, 9)),
            task("waiting", lifecycle = Lifecycle.WAITING, scheduled = at(tue, 9)),
            task("nextWeek", scheduled = at(mon + 8, 9)),
            done("lastWeek", mon - 3),
        )
        val r = BoardReviewRules.review(tasks, mon, fri, cal)
        assertEquals(6, r.planned)
        assertEquals(4, r.done)
        assertEquals("4 of 6 planned done · best day Tue, 3 done", r.line)
        assertTrue(r.slipping.isEmpty())
        assertNull(r.slippingLine)
    }

    @Test
    fun whatKeptSlippingIsLongestFirstAndLeavesOutRepeats() {
        val tasks = listOf(
            task("mot", "Book MOT", scheduled = at(mon - 2, 9)),
            task("tax", "Tax return", scheduled = at(tue, 9)),
            task("old", "Sort photos", week = mon - 7),
            task("new", "Clear desk", scheduled = at(wed + 1, 9)),
            task("asked", "Call bank", scheduled = at(wed + 1, 9), asked = wed + 1),
            task("rpt", "Stretch", scheduled = at(mon, 8), recurrence = "FREQ=DAILY"),
        )
        val r = BoardReviewRules.review(tasks, mon, fri, cal)
        // Carried into the week, carried within it and parked from last week all count as planned this week.
        assertEquals(6, r.planned)
        assertEquals(listOf("Sort photos", "Book MOT", "Tax return"), r.slipping.map { it.title })
        assertEquals("Parked since last week", r.slipping[0].why)
        assertEquals("Carried 6 days", r.slipping[1].why)
        assertEquals("Kept slipping: Sort photos (parked since last week), Book MOT (carried 6 days), Tax return (carried 3 days)", r.slippingLine)
        assertEquals("0 of 6 planned done", r.line)
    }

    @Test
    fun aPastWeekCountsOnlyItsOwnDaysToItsEnd() {
        val last = mon - 7
        val tasks = listOf(
            done("a", last + 1), task("left", "Book MOT", scheduled = at(last + 5, 9)),
            task("carriedIn", scheduled = at(last - 3, 9)),
        )
        val r = BoardReviewRules.review(tasks, last, wed, cal)
        assertEquals(2, r.planned)
        assertEquals("1 of 2 planned done", r.line)
        assertEquals(listOf("Carried 2 days"), r.slipping.map { it.why })
    }

    @Test
    fun anEmptyWeekSaysSo() {
        val r = BoardReviewRules.review(listOf(task("floating")), mon, fri, cal)
        assertEquals(0, r.planned)
        assertEquals(BoardReviewRules.NOTHING_PLANNED, r.line)
    }

    @Test
    fun theReviewIsOfferedFromFridayAndItsOneThingCarriesIntoNextWeeksPlan() {
        assertFalse(BoardReviewRules.offersReview(mon + 3))
        assertTrue(BoardReviewRules.offersReview(fri))
        assertTrue(BoardReviewRules.offersReview(mon + 6))
        val r = Reflection(mon, "Ran twice", "", "Run 5k.\nSleep earlier", false, 1)
        assertEquals("From your review · Run 5k", BoardReviewRules.carryLine(listOf(r), mon + 7))
        assertNull(BoardReviewRules.carryLine(listOf(r), mon))
        assertNull(BoardReviewRules.carryLine(listOf(r.copy(skipped = true)), mon + 7))
    }
}
