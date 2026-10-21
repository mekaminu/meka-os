package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week board and roll-over, slice 1: unfinished tasks from earlier days lead Today under "From yesterday". */
class RollOverTest {
    private val dayMs = CivilDate.DAY_MS
    private val today = DayWindow(startMs = 20 * dayMs, endMs = 21 * dayMs)
    private val now = today.startMs + 10 * 3_600_000L
    private val dayOf: (Long) -> Long = { it.floorDiv(dayMs) }

    private fun task(
        id: String, lifecycle: Lifecycle = Lifecycle.ACTIVE, scheduled: Long? = null, due: Long? = null,
        repeat: String? = null, asked: Long? = null, deferred: Long? = null, created: Long = 0,
    ) = Task(
        id, id, null, lifecycle, due, scheduled, null, 0, null, null, created, null, false,
        recurrenceRule = repeat, deferredToDay = deferred, carryAskedDay = asked,
    )

    private fun daysAgo(n: Int, hour: Int = 9) = today.startMs - n * dayMs + hour * 3_600_000L

    @Test
    fun openTasksPlannedOnEarlierDaysCarryOldestFirstWithHowLongTheyHaveCarried() {
        val carried = RollOverRules.carried(
            listOf(task("yesterday", scheduled = daysAgo(1)), task("old", scheduled = daysAgo(4)), task("today", scheduled = now + 3_600_000)),
            emptySet(), today, dayOf,
        )
        assertEquals(listOf("old", "yesterday"), carried.map { it.task.id })
        assertEquals(listOf(4L, 1L), carried.map { it.days })
        assertEquals(listOf("Carried 4 days", "Carried from yesterday"), carried.map { it.line })
    }

    @Test
    fun doneDroppedSomedayWaitingSnoozedAndUnplannedTasksNeverCarry() {
        val carried = RollOverRules.carried(
            listOf(
                task("done", Lifecycle.DONE, scheduled = daysAgo(1)),
                task("dropped", Lifecycle.CANCELLED, scheduled = daysAgo(1)),
                task("someday", Lifecycle.SOMEDAY, scheduled = daysAgo(1)),
                task("waiting", Lifecycle.WAITING, scheduled = daysAgo(1)),
                task("snoozed", scheduled = daysAgo(1), deferred = today.epochDay + 1),
                task("anytime"),
                task("inbox", Lifecycle.INBOX, scheduled = daysAgo(2)),
            ),
            emptySet(), today, dayOf,
        )
        assertEquals(listOf("inbox"), carried.map { it.task.id })
    }

    @Test
    fun theThreeCarryPromptAsksOnceAndNeverForARepeatingTask() {
        val carried = RollOverRules.carried(
            listOf(
                task("two", scheduled = daysAgo(2)),
                task("three", scheduled = daysAgo(3)),
                task("asked", scheduled = daysAgo(5), asked = today.epochDay - 1),
                task("routine", scheduled = daysAgo(6), repeat = "FREQ=DAILY"),
            ),
            emptySet(), today, dayOf,
        ).associateBy { it.task.id }
        assertFalse(carried.getValue("two").asks)
        assertTrue(carried.getValue("three").asks)
        assertFalse(carried.getValue("asked").asks)
        assertFalse(carried.getValue("routine").asks)
        assertTrue(carried.getValue("routine").line.startsWith("Carried 6 days · ↻ "))
    }

    @Test
    fun todayLeadsWithFromYesterdayAndKeepsItOutOfNeedsYouUpNextAndTheTimeline() {
        val t = TodayProjection.project(
            listOf(
                task("carried", scheduled = daysAgo(1)),
                task("overdue", scheduled = daysAgo(2), due = now - 1_000),
                task("planned", scheduled = now + 3_600_000),
            ),
            now, today,
        )
        assertEquals(listOf("carried"), t.fromYesterday.map { it.task.id })
        assertEquals(listOf("overdue"), t.needsYou.map { it.task.id })
        assertEquals("planned", t.upNext?.id)
        assertTrue(t.yourDay.none { it.id == "carried" })
        assertFalse(t.isClear)
        assertNull(t.clearLine)
    }

    @Test
    fun aDayWithOnlyCarriedTasksIsNotClear() {
        val t = TodayProjection.project(listOf(task("carried", scheduled = daysAgo(3))), now, today)
        assertFalse(t.isClear)
        assertTrue(t.fromYesterday.single().asks)
    }

    @Test
    fun theUndoLineNamesTheTaskForSomedayAndDropOnly() {
        assertEquals("Moved “Book MOT” to Someday", RollOverRules.undoLine(CarryChoice.SOMEDAY, "book MOT"))
        assertEquals("Dropped “Book MOT”", RollOverRules.undoLine(CarryChoice.DROP, "Book MOT"))
        assertNull(RollOverRules.undoLine(CarryChoice.KEEP, "Book MOT"))
        assertNull(RollOverRules.undoLine(CarryChoice.BREAK_DOWN, "Book MOT"))
    }

    @Test
    fun answeringThePromptIsKeptOnTheTaskSyncsAndUndoes() {
        val world = SyncWorld()
        val d = world.device("android")
        val id = d.tasks.create(NewTask("Book MOT", scheduledAtMs = world.clock.nowMs - 4 * dayMs))
        val before = d.tasks.answerCarry(id, CarryChoice.SOMEDAY)
        val after = d.tasks.get(id)!!
        assertEquals(Lifecycle.SOMEDAY, after.lifecycle)
        assertTrue(after.carryAskedDay != null)
        d.tasks.undoCarry(before)
        val back = d.tasks.get(id)!!
        assertEquals(Lifecycle.ACTIVE, back.lifecycle)
        assertNull(back.carryAskedDay)

        d.tasks.answerCarry(id, CarryChoice.DROP)
        assertEquals(Lifecycle.CANCELLED, d.tasks.get(id)!!.lifecycle)
        d.tasks.undoCarry(before)
        assertEquals(Lifecycle.ACTIVE, d.tasks.get(id)!!.lifecycle)

        d.tasks.answerCarry(id, CarryChoice.KEEP)
        val kept = d.tasks.get(id)!!
        assertEquals(Lifecycle.ACTIVE, kept.lifecycle)
        assertTrue(kept.carryAskedDay != null)
        val mac = world.device("mac")
        d.sync(); mac.sync()
        assertEquals(kept.carryAskedDay, mac.tasks.get(id)!!.carryAskedDay)
    }
}
