package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Health Connect, slice 3: last night's sleep and the lighter plan after a short night. */
class SleepTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val dayStart = 10 * 24 * hour
    private val day = DayWindow(dayStart, dayStart + 24 * hour)

    private fun s(fromH: Double, toH: Double) = SleepSession(dayStart + (fromH * hour).toLong(), dayStart + (toH * hour).toLong())

    private fun task(id: String, est: Int, due: Long? = null) =
        Task(id, id, null, Lifecycle.ACTIVE, due, null, est, 0, null, null, 0, null, false)

    @Test
    fun lastNightCountsOverlapsOnceAndIgnoresOtherNights() {
        // 23:30 → 06:10 from the watch, 23:45 → 05:50 from the phone, a nap at 15:00 yesterday, a session ending at 15:00 today.
        val sessions = listOf(s(-0.5, 6 + 10.0 / 60), s(-0.25, 5 + 50.0 / 60), s(-9.0, -8.0), s(14.0, 15.0))
        assertEquals(6 * 60 + 40, SleepRules.lastNight(sessions, dayStart))
        // Two pieces with a wake in between: 23:00–02:00 and 03:00–06:00 is six hours.
        assertEquals(6 * 60, SleepRules.lastNight(listOf(s(-1.0, 2.0), s(3.0, 6.0)), dayStart))
        assertNull(SleepRules.lastNight(listOf(s(-9.0, -8.0)), dayStart))
        assertNull(SleepRules.lastNight(emptyList(), dayStart))
    }

    @Test
    fun aShortNightCapsTheTasksAndSaysSo() {
        assertNull(SleepRules.taskCap(null))
        assertNull(SleepRules.taskCap(6 * 60))
        assertEquals(180, SleepRules.taskCap(5 * 60 + 10))
        assertEquals(120, SleepRules.taskCap(4 * 60 + 59))
        assertNull(SleepRules.planNote(7 * 60))
        assertEquals(
            "☾ Short night · 5 h 10 m asleep: a lighter plan, up to 3 hours of tasks. Anything due today still goes in.",
            SleepRules.planNote(5 * 60 + 10),
        )
        assertEquals("On · last night 5 h 10 m · a lighter plan today. Read on this phone only.", SleepRules.settingLine(WorkoutRules.Access.ON, 310))
        assertEquals("On · last night 7 h 05 m. Read on this phone only.", SleepRules.settingLine(WorkoutRules.Access.ON, 425))
        assertTrue(SleepRules.settingLine(WorkoutRules.Access.ON, null).startsWith("On · no sleep logged"))
    }

    @Test
    fun theLighterPlanKeepsDueTasksAndLeavesTheRestForALighterDay() {
        val tasks = listOf(
            task("a", 60), task("b", 60), task("c", 60), task("d", 60),
            task("overdue", 90, due = dayStart - hour), task("today", 60, due = dayStart + 17 * hour),
        )
        val usual = DayPlanner.plan(tasks, emptyList(), dayStart + 8 * hour, day)
        assertEquals(6, usual.placements.size)
        assertNull(usual.keptLine)

        val note = SleepRules.planNote(310)
        val light = DayPlanner.plan(tasks, emptyList(), dayStart + 8 * hour, day, taskCapMin = SleepRules.taskCap(310), restNote = note)
        // Due ones first (150 min), then 30 min of room under 3 hours: no 60-min task fits, so all four wait.
        assertEquals(listOf("overdue", "today"), light.placements.map { it.task.id })
        assertEquals(listOf("a", "b", "c", "d"), light.restedOut.map { it.id })
        assertEquals("Left for a lighter day: a, b, c, d", light.unplacedLine)
        assertEquals(note, light.keptLine)

        // No due tasks: three of the hour-long ones fill the three hours.
        val plain = DayPlanner.plan(tasks.take(4), emptyList(), dayStart + 8 * hour, day, taskCapMin = 180, restNote = note)
        assertEquals(listOf("a", "b", "c"), plain.placements.map { it.task.id })
        assertEquals(listOf("d"), plain.restedOut.map { it.id })
        // Nothing left out still says why the plan is light.
        assertEquals(note, DayPlanner.plan(tasks.take(1), emptyList(), dayStart + 8 * hour, day, taskCapMin = 180, restNote = note).keptLine)
    }
}
