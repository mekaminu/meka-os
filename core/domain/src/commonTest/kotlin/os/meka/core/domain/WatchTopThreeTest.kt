package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTopThreeTest {
    private val cal = LocalCalendar.UTC
    private val hour = 3_600_000L
    private val min = 60_000L

    /** Wednesday 21 October 2026, UTC. */
    private val wed = CivilDate.toEpochDay(2026, 10, 21) * CivilDate.DAY_MS
    private val day = DayWindow(wed, wed + 24 * hour, 0)
    private fun at(h: Int, m: Int = 0) = wed + h * hour + m * min

    private fun task(
        id: String, title: String, scheduled: Long? = null, estimate: Int? = null, rank: Long? = null,
        lifecycle: Lifecycle = Lifecycle.ACTIVE, completed: Long? = null,
    ) = Task(id, title, null, lifecycle, null, scheduled, estimate, 0, null, null, 0, completed, false).copy(boardRank = rank)

    private fun top(tasks: List<Task>, now: Long, shown: String? = null): WatchTopThree? {
        val today = TodayProjection.project(tasks, now, day, emptyList(), cal)
        val card = shown ?: CoverNowRules.now(today, now, cal).task?.id
        return WatchTopThreeRules.view(today, card, cal)
    }

    @Test
    fun carriedLeadThenTheBoardsOrderAndTheCardsTaskIsLeftOut() {
        val v = top(
            listOf(
                task("old", "renew passport", scheduled = at(10) - 2 * 24 * hour),
                task("b", "Book MOT", rank = 2),
                task("a", "Call the plumber", rank = 1, estimate = 30),
                task("c", "Buy milk", rank = 3),
                task("d", "Gym bag", rank = 4),
            ),
            at(9),
        )!!
        // Up next (the card) is the Board's first: Call the plumber, so the wrist's list starts after it.
        assertEquals(listOf("old", "b", "c"), v.rows.map { it.taskId })
        assertEquals("Renew passport", v.rows[0].title)
        assertEquals("Carried 2 days", v.rows[0].line)
        assertTrue(v.rows[0].carried)
        assertFalse(v.rows[1].carried)
        assertEquals("+1 more on your phone", v.moreLine)
        assertEquals("5 to go", v.countLine)
        assertEquals(WatchTopThreeRules.LABEL, v.label)
    }

    @Test
    fun rowsSayWhenAndHowLong() {
        val v = top(
            listOf(task("p", "Pick up parcel", scheduled = at(14), estimate = 90), task("e", "Email Sam", estimate = 60)),
            at(9), shown = "none",
        )!!
        assertEquals(listOf("At 14:00 · 1h 30m", "1h"), v.rows.map { it.line })
        assertNull(v.moreLine)
    }

    @Test
    fun doneTodayIsCountedAndNeverListed() {
        val v = top(
            listOf(
                task("x", "Water plants", lifecycle = Lifecycle.DONE, completed = at(8)),
                task("y", "Post the form", rank = 1),
                task("z", "Pay the window cleaner", rank = 2),
            ),
            at(9),
        )!!
        assertEquals(listOf("z"), v.rows.map { it.taskId })
        assertEquals("1 done · 2 to go", v.countLine)
        assertTrue(v.spoken.startsWith("Today, 2 to go. Next: Pay the window cleaner."))
    }

    @Test
    fun nothingElseOpenShowsNoList() {
        assertNull(top(listOf(task("only", "Only one")), at(9)))
        assertNull(top(emptyList(), at(9)))
    }

    @Test
    fun theWatchHomeCarriesTheList() {
        val tasks = listOf(task("a", "First", rank = 1), task("b", "Second", rank = 2))
        val today = TodayProjection.project(tasks, at(9), day, emptyList(), cal)
        val now = CoverNowRules.now(today, at(9), cal)
        val t = WatchTopThreeRules.view(today, now.task?.id, cal)
        val home = WatchHomeRules.view(now, FastingView.EMPTY, at(9), top = t)
        assertEquals("First", home.title)
        assertEquals(listOf("Second"), home.top!!.rows.map { it.title })
        assertEquals("Done · Second", WatchTopThreeRules.doneLine("second"))
    }
}
