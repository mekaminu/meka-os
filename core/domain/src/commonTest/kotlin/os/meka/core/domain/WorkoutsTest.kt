package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Health Connect, slice 1: which habits today's workouts tick. */
class WorkoutsTest {
    private val day = 86_400_000L
    private val today = 20_000L
    private val now = today * day + 19 * 3_600_000L
    private fun dayOf(ms: Long) = ms / day
    private fun w(id: String, kind: WorkoutKind, startH: Double, minutes: Int, onDay: Long = today): Workout {
        val start = onDay * day + (startH * 3_600_000).toLong()
        return Workout(id, kind, start, start + minutes * 60_000L)
    }
    private fun h(id: String, title: String, minutes: Int = 60, booked: Boolean = false, answered: Boolean = false) =
        WorkoutHabit(id, title, minutes, booked, answered)

    @Test
    fun fitNamesTheKindTheBookedGymAndGenericHabits() {
        assertEquals(2, WorkoutRules.fit(WorkoutKind.GYM, "Gym", booked = false))
        assertEquals(2, WorkoutRules.fit(WorkoutKind.RUN, "Morning running", booked = false))
        assertEquals(2, WorkoutRules.fit(WorkoutKind.OTHER, "Training", booked = true))
        assertEquals(1, WorkoutRules.fit(WorkoutKind.RUN, "Exercise", booked = false))
        assertNull(WorkoutRules.fit(WorkoutKind.WALK, "Exercise", booked = false))
        assertNull(WorkoutRules.fit(WorkoutKind.RUN, "Gym", booked = false))
        assertNull(WorkoutRules.fit(WorkoutKind.GYM, "Read 20 pages", booked = false))
        assertNull(WorkoutRules.fit(WorkoutKind.SWIM, "Gym", booked = true))
    }

    @Test
    fun aLongEnoughWorkoutEndedTodayTicksTheBestHabitOnce() {
        val habits = listOf(h("ex", "Exercise", 30), h("gym", "Gym", 60, booked = true), h("read", "Read"))
        val ticks = WorkoutRules.ticks(listOf(w("a", WorkoutKind.GYM, 17.75, 52)), habits, today, now, ::dayOf)
        assertEquals(listOf("gym"), ticks.map { it.habitId })
        assertEquals("Ticked Gym · 52-min workout", WorkoutRules.summary(ticks.single()))
        assertEquals("52-min workout · from Health Connect", WorkoutRules.note(ticks.single()))
        // A second workout goes to the next habit it fits.
        val two = WorkoutRules.ticks(listOf(w("a", WorkoutKind.GYM, 7.0, 52), w("b", WorkoutKind.RUN, 12.0, 25)), habits, today, now, ::dayOf)
        assertEquals(listOf("gym" to "a", "ex" to "b"), two.map { it.habitId to it.workoutId })
    }

    @Test
    fun shortYesterdaysUnfinishedAndAnsweredDaysTickNothing() {
        val gym = h("gym", "Gym", 60)
        assertEquals(30, WorkoutRules.enough(60)); assertEquals(15, WorkoutRules.enough(20)); assertEquals(30, WorkoutRules.enough(120))
        assertTrue(WorkoutRules.ticks(listOf(w("a", WorkoutKind.GYM, 17.0, 20)), listOf(gym), today, now, ::dayOf).isEmpty())
        assertTrue(WorkoutRules.ticks(listOf(w("a", WorkoutKind.GYM, 17.0, 50, onDay = today - 1)), listOf(gym), today, now, ::dayOf).isEmpty())
        assertTrue(WorkoutRules.ticks(listOf(w("a", WorkoutKind.GYM, 18.5, 60)), listOf(gym), today, now, ::dayOf).isEmpty())
        assertTrue(WorkoutRules.ticks(listOf(w("a", WorkoutKind.GYM, 17.0, 50)), listOf(gym.copy(answeredToday = true)), today, now, ::dayOf).isEmpty())
    }

    @Test
    fun settingLines() {
        assertEquals(listOf("Gym", "Run"), WorkoutRules.tickable(listOf(h("g", "Gym"), h("r", "Run"), h("x", "Read"))))
        assertEquals("On · workouts tick Gym or Run. Read on this phone only.", WorkoutRules.settingLine(WorkoutRules.Access.ON, listOf("Gym", "Run")))
        assertEquals("Allow MEKA to read exercise in Health Connect", WorkoutRules.settingLine(WorkoutRules.Access.NEEDS_PERMISSION, emptyList()))
    }

    @Test
    fun backgroundReadingLines() {
        assertEquals(
            "On · workouts tick Gym, even while MEKA is closed. Read on this phone only.",
            WorkoutRules.settingLine(WorkoutRules.Access.ON, listOf("Gym"), background = true),
        )
        assertEquals(
            "Read while MEKA is open · allow in the background and a workout ticks it before “Did you go?”",
            WorkoutRules.backgroundLine(WorkoutRules.Access.ON, background = false, offered = true),
        )
        assertEquals(
            "Read while MEKA is open · background reading wasn't allowed (change it in Health Connect → MEKA)",
            WorkoutRules.backgroundLine(WorkoutRules.Access.ON, background = false, offered = false),
        )
        assertEquals(null, WorkoutRules.backgroundLine(WorkoutRules.Access.ON, background = true, offered = true))
        assertEquals(null, WorkoutRules.backgroundLine(WorkoutRules.Access.OFF, background = false, offered = true))
    }
}
