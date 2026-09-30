package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodayTest {
    private val day = DayWindow(startMs = 0, endMs = 24 * 3_600_000L)
    private val now = 10 * 3_600_000L // 10:00

    private fun task(
        id: String, lifecycle: Lifecycle = Lifecycle.ACTIVE, due: Long? = null, scheduled: Long? = null,
        conflict: Boolean = false, priority: Int = 0, completedAt: Long? = null,
    ) = Task(id, id, null, lifecycle, due, scheduled, null, priority, null, null, 0, completedAt, conflict)

    @Test
    fun emptyGraphIsClear() {
        assertTrue(TodayProjection.project(emptyList(), now, day).isClear)
    }

    @Test
    fun somedayAndDoneNeverAppearInTheDay() {
        val t = TodayProjection.project(
            listOf(task("idea", Lifecycle.SOMEDAY), task("done", Lifecycle.DONE, completedAt = now - 1)), now, day,
        )
        assertTrue(t.isClear)
        assertEquals(listOf("done"), t.doneToday.map { it.id })
    }

    @Test
    fun needsYouOrdersConflictsThenOverdueAndIsCapped() {
        val tasks = listOf(
            task("overdue-new", due = now - 1_000),
            task("overdue-old", due = now - 50_000),
            task("conflict", conflict = true),
        ) + (1..6).map { task("od$it", due = now - 100_000 - it) }
        val t = TodayProjection.project(tasks, now, day)
        assertEquals(Today.MAX_NEEDS_YOU, t.needsYou.size)
        assertEquals("conflict", t.needsYou.first().task.id)
        assertEquals(NeedsYouReason.OVERDUE, t.needsYou[1].reason)
    }

    @Test
    fun upNextIsNextScheduledThenHighestPriorityUnscheduled() {
        val t = TodayProjection.project(
            listOf(
                task("past", scheduled = now - 3_600_000),
                task("later", scheduled = now + 7_200_000),
                task("soon", scheduled = now + 600_000),
                task("p5", priority = 5),
            ),
            now, day,
        )
        assertEquals("soon", t.upNext!!.id)
        assertEquals(listOf("past", "later", "p5"), t.yourDay.map { it.id })
    }

    @Test
    fun tomorrowsWorkIsNotInToday() {
        val t = TodayProjection.project(listOf(task("tomorrow", due = day.endMs + 1)), now, day)
        assertNull(t.upNext)
        assertTrue(t.isClear)
    }
}
