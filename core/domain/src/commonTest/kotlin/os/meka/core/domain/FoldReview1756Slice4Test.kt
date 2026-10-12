package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fold review 2026-10-12 17:56, slice 4: Needs you's Coming up (item 3) and Break it down on Up next (item 5). */
class FoldReview1756Slice4Test {
    private val cal = LocalCalendar.UTC
    private val hour = 3_600_000L
    private val dayMs = 24 * hour
    private val wed = CivilDate.toEpochDay(2026, 10, 7) * CivilDate.DAY_MS
    private val wedDay = CivilDate.toEpochDay(2026, 10, 7)
    private val window = DayWindow(wed, wed + dayMs, 0)
    private fun at(h: Int) = wed + h * hour

    private fun task(
        id: String, title: String = id, created: Long = 0, remind: Long? = null,
        lifecycle: Lifecycle = Lifecycle.ACTIVE, repeat: String? = null, steps: List<ChecklistItem> = emptyList(),
    ) = Task(
        id, title, null, lifecycle, null, null, null, 0, null, null, created, null, false,
        checklist = steps, recurrenceRule = repeat, remindAtMs = remind,
    )

    // Coming up

    @Test
    fun comingUpIsTheNextTwoRemindersSoonestFirst() {
        val tasks = listOf(
            task("c", "call the garage", remind = at(24 + 9)),
            task("a", "renew passport", remind = at(18)),
            task("b", "Pay nursery", remind = at(48 + 13)),
        )
        val lines = NeedsYouMeanwhileRules.comingUp(tasks, at(10), cal)
        assertEquals(listOf("a", "c"), lines.map { it.id })
        assertEquals("Renew passport", lines[0].title)
        assertEquals("Reminder · Today · 18:00", lines[0].meta)
        assertEquals("Reminder · Tomorrow · 09:00", lines[1].meta)
    }

    @Test
    fun comingUpLeavesOutPastDoneSomedayAndFarOffReminders() {
        val tasks = listOf(
            task("past", remind = at(9)),
            task("done", remind = at(18), lifecycle = Lifecycle.DONE),
            task("someday", remind = at(18), lifecycle = Lifecycle.SOMEDAY),
            task("far", remind = at(10) + 8 * dayMs),
            task("none"),
        )
        assertTrue(NeedsYouMeanwhileRules.comingUp(tasks, at(10), cal).isEmpty())
    }

    @Test
    fun comingUpIsItsOwnSectionAfterHabits() {
        val line = MeanwhileLine("a", "Renew passport", "Reminder · Today · 18:00")
        val m = NeedsYouMeanwhileRules.build(null, null, listOf(line, line.copy(id = "b"), line.copy(id = "c")))
        assertFalse(m.isEmpty)
        assertEquals(listOf(NeedsYouMeanwhileRules.COMING_LABEL), m.sections)
        assertEquals(2, m.comingUp.size)
        assertTrue(NeedsYouMeanwhileRules.build(null, null).comingUp.isEmpty())
    }

    @Test
    fun theFacadeFieldStartsEmpty() {
        assertTrue(TodayProjection.project(emptyList(), at(10), window, emptyList(), cal).comingUp.isEmpty())
    }

    // Break it down

    private fun upNext(t: Task) =
        CoverNowRules.upNext(TodayProjection.project(listOf(t), at(10), window, emptyList(), cal), at(10), cal)!!

    @Test
    fun aTaskCarriedOverTwoDaysOffersBreakItDownSecond() {
        val v = upNext(task("inv", "Send the invoice", created = at(10) - 2 * dayMs))
        assertEquals(listOf(NowAction.DONE, NowAction.BREAK_DOWN, NowAction.TOMORROW), v.actions)
        assertEquals("Break it down", CoverNowRules.BREAK_DOWN_LABEL)
    }

    @Test
    fun yesterdaysTaskStepsAlreadyOrARoutineKeepOpen() {
        val plain = listOf(NowAction.DONE, NowAction.TOMORROW, NowAction.OPEN_TASK)
        assertEquals(plain, upNext(task("y", created = at(10) - dayMs)).actions)
        val step = ChecklistItem("s1", "z", "Find the form", false, 1)
        assertEquals(plain, upNext(task("z", created = at(10) - 3 * dayMs, steps = listOf(step))).actions)
        assertFalse(UpNextWhyRules.offersBreakDown(task("r", created = at(10) - 3 * dayMs, repeat = "FREQ=DAILY"), wedDay, cal::epochDayOf))
        assertTrue(UpNextWhyRules.offersBreakDown(task("o", created = at(10) - 3 * dayMs), wedDay, cal::epochDayOf))
        assertFalse(UpNextWhyRules.offersBreakDown(task("new", created = 0), wedDay, cal::epochDayOf))
    }

    @Test
    fun theCoverScreenOffersItToo() {
        val t = TodayProjection.project(listOf(task("inv", created = at(10) - 2 * dayMs)), at(10), window, emptyList(), cal)
        assertTrue(CoverNowRules.now(t, at(10), cal).offers(NowAction.BREAK_DOWN))
    }
}
