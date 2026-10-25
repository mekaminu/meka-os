package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Taps feel instant for habits: a tick on the dots, chips, Goals or Needs you shows in the next frame. */
class InstantHabitsTest {
    private val mon = CivilDate.toEpochDay(2026, 10, 26)
    private val wed = mon + 2

    private fun habit(id: String, target: Int, done: Int, today: Boolean, pace: HabitPace) = HabitItem(
        id = id, title = "Habit $id", targetPerWeek = target, timing = HabitTiming.ANYTIME, minutes = 15, goalId = null,
        doneThisWeek = done, weekTarget = target, doneToday = today, pace = pace, streak = 3, streakUnit = "day",
        week = List(7) { it < done }, meta = "", streakLine = "3-day streak", hasConflict = false,
    )

    private fun view(vararg h: HabitItem) = GoalsView(h.toList(), emptyList(), 0, today = wed)

    @Test
    fun nothingWaitingIsTheCoresViewItself() {
        val v = view(habit("a", 7, 2, false, HabitPace.DUE))
        assertTrue(v === InstantHabitRules.shown(v, emptyMap()))
        // A tap the core already shows changes nothing either.
        assertTrue(v === InstantHabitRules.shown(v, mapOf("a" to false)))
    }

    @Test
    fun aTickShowsDoneTodayWithTheCountTheStripAndTheLine() {
        val v = view(habit("a", 7, 2, false, HabitPace.DUE), habit("b", 3, 1, false, HabitPace.ON_TRACK))
        val shown = InstantHabitRules.shown(v, InstantHabitRules.tick(emptyMap(), "a", true))
        val a = shown.habits.first { it.id == "a" }
        assertTrue(a.doneToday)
        assertEquals(3, a.doneThisWeek)
        assertEquals(HabitPace.DONE_TODAY, a.pace)
        assertEquals("Done today", a.meta)
        assertTrue(a.week[2], "Wednesday's slot is filled")
        assertEquals(3, a.streak, "the streak is left to the core")
        assertEquals(v.habits[1], shown.habits[1], "the other habit is untouched")
        assertEquals(1, shown.doneToday)
        // The chips and the face's dots are built from the shown view, so they tick too.
        assertTrue(HabitChipRules.build(shown).first { it.id == "a" }.done)
    }

    @Test
    fun anUntickTakesTheGoBack() {
        val v = view(habit("b", 3, 2, true, HabitPace.DONE_TODAY))
        val b = InstantHabitRules.shown(v, mapOf("b" to false)).habits.single()
        assertFalse(b.doneToday)
        assertEquals(1, b.doneThisWeek)
        assertEquals(HabitPace.ON_TRACK, b.pace, "2 left with 5 days to go")
        assertEquals("1 of 3 this week", b.meta)
    }

    @Test
    fun aQuickTickThenUntickKeepsTheNewerTapUntilItsOwnCallReturns() {
        var taps = InstantHabitRules.tick(emptyMap(), "a", true)
        taps = InstantHabitRules.tick(taps, "a", false)
        // The first call (done = true) returns: the newer un-tick stays drawn.
        taps = InstantHabitRules.forget(taps, "a", true)
        assertEquals(mapOf("a" to false), taps)
        taps = InstantHabitRules.forget(taps, "a", false)
        assertTrue(taps.isEmpty())
    }

    @Test
    fun aViewWithoutItsDayLeavesTheStripAlone() {
        val h = habit("a", 7, 2, false, HabitPace.DUE)
        val shown = InstantHabitRules.apply(h, true, 0)
        assertEquals(h.week, shown.week)
        assertTrue(shown.doneToday)
    }
}
