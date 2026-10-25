package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Taps feel instant for the shutdown's and the brief's task moves: the row leaves in the next frame. */
class InstantTasksTest {
    private fun task(id: String) = Task(
        id = id, title = "Task $id", notes = null, lifecycle = Lifecycle.ACTIVE, dueAtMs = null, scheduledAtMs = null,
        estimateMinutes = null, priority = 0, goalId = null, somedayKind = null, createdAtMs = 0, completedAtMs = null,
        hasConflict = false,
    )

    private fun item(id: String) = ShutdownItem(task(id), null, overdue = false, canSkip = false, canSomeday = true)

    private fun view(left: List<String>, done: List<String> = emptyList()) = ShutdownView.EMPTY.copy(
        left = left.map(::item),
        leftLine = "${left.size} left from today",
        cardLine = "${left.size} left · tomorrow: 2 events",
        doneCount = done.size,
        doneCountLine = if (done.isEmpty()) ShutdownRules.NOTHING_DONE else "${done.size} done today",
        done = done.map { DoneRow(it, "Task $it", "09:00") },
    )

    @Test
    fun nothingWaitingIsTheCoresViewItself() {
        val v = view(listOf("a", "b"))
        assertTrue(v === InstantTaskRules.shutdown(v, emptyMap()))
        assertTrue(v === InstantTaskRules.shutdown(v, mapOf("gone" to TaskMove.DONE)), "a move the core already shows")
    }

    @Test
    fun aTickLeavesLeftAndJoinsDoneAtTheTop() {
        val v = view(listOf("a", "b", "c"), done = listOf("x"))
        val shown = InstantTaskRules.shutdown(v, InstantTaskRules.move(emptyMap(), "b", TaskMove.DONE))
        assertEquals(listOf("a", "c"), shown.left.map { it.task.id })
        assertEquals(listOf("b", "x"), shown.done.map { it.id })
        assertEquals(InstantTaskRules.JUST_NOW, shown.done.first().time)
        assertEquals(2, shown.doneCount)
        assertEquals("2 done today", shown.doneCountLine)
        assertEquals("2 left from today", shown.leftLine)
        assertEquals("2 left · tomorrow: 2 events", shown.cardLine)
    }

    @Test
    fun tomorrowSkipAndSomedayLeaveWithoutCountingAsDone() {
        val v = view(listOf("a", "b", "c"))
        var taps = InstantTaskRules.move(emptyMap(), "a", TaskMove.TOMORROW)
        taps = InstantTaskRules.move(taps, "b", TaskMove.SKIP)
        taps = InstantTaskRules.move(taps, "c", TaskMove.SOMEDAY)
        val shown = InstantTaskRules.shutdown(v, taps)
        assertTrue(shown.left.isEmpty())
        assertEquals("Nothing left from today", shown.leftLine)
        assertEquals("Nothing left · tomorrow: 2 events", shown.cardLine)
        assertEquals(0, shown.doneCount)
        assertEquals(ShutdownRules.NOTHING_DONE, shown.doneCountLine)
    }

    @Test
    fun moveTheRestToTomorrowTakesWhatIsLeftAndKeepsEarlierMoves() {
        val v = view(listOf("a", "b", "c"))
        val ticked = InstantTaskRules.move(emptyMap(), "a", TaskMove.DONE)
        val carried = InstantTaskRules.carried(ticked, v)
        assertEquals(listOf("b", "c"), carried)
        val taps = InstantTaskRules.carryAll(ticked, v)
        assertEquals(TaskMove.DONE, taps["a"])
        assertTrue(InstantTaskRules.shutdown(v, taps).left.isEmpty())
        // Its call returns: just the carried ids are forgotten.
        assertEquals(mapOf("a" to TaskMove.DONE), InstantTaskRules.forgetAll(taps, carried, TaskMove.TOMORROW))
    }

    @Test
    fun aFailedOrFinishedCallForgetsOnlyItsOwnMove() {
        val first = InstantTaskRules.move(emptyMap(), "a", TaskMove.DONE)
        val newer = InstantTaskRules.move(first, "a", TaskMove.TOMORROW)
        assertEquals(newer, InstantTaskRules.forget(newer, "a", TaskMove.DONE), "the tick's return can't drop the newer move")
        assertTrue(InstantTaskRules.forget(newer, "a", TaskMove.TOMORROW).isEmpty())
        // Forgotten after a failure: the core's view shows the task again.
        val v = view(listOf("a"))
        assertEquals(v, InstantTaskRules.shutdown(v, InstantTaskRules.forget(first, "a", TaskMove.DONE)))
    }

    @Test
    fun theDoneListKeepsItsLimitAndSaysHowManyMore() {
        val v = view(listOf("a"), done = (1..ShutdownRules.DONE_SHOWN).map { "d$it" })
        val shown = InstantTaskRules.shutdown(v, mapOf("a" to TaskMove.DONE))
        assertEquals(ShutdownRules.DONE_SHOWN, shown.done.size)
        assertEquals("a", shown.done.first().id)
        assertEquals("+ 1 more", shown.doneMore)
    }

    @Test
    fun theBriefsTickedTaskLeavesAndEventsStay() {
        val lines = listOf(
            BriefDayLine("e-1", null, "Standup", "09:30", false, "Event, Standup"),
            BriefDayLine("t-a", "a", "Task a", null, false, "Task, Task a"),
            BriefDayLine("t-b", "b", "Task b", null, false, "Task, Task b"),
        )
        assertTrue(lines === InstantTaskRules.lines(lines, emptyMap()))
        assertEquals(listOf("e-1", "t-b"), InstantTaskRules.lines(lines, mapOf("a" to TaskMove.DONE)).map { it.id })
    }

    @Test
    fun theBarSaysWhichMoveFailed() {
        assertEquals("Done didn't go through · it's back: offline", InstantTapRules.failedLine(InstantTaskRules.what(TaskMove.DONE), "offline"))
        assertEquals("Someday", InstantTaskRules.what(TaskMove.SOMEDAY))
    }
}
