package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.Workout
import os.meka.core.domain.WorkoutKind
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Health Connect, slice 1, through the facade: a workout ticks Gym, Activity can undo it, and it never ticks again. */
class WorkoutTicksFacadeTest {
    private val start = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London
    private var now = start + 2 * 3_600_000L

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())),
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val gymWorkout = Workout("hc-1", WorkoutKind.GYM, start + 30 * 60_000L, start + 82 * 60_000L)

    @Test
    fun aWorkoutTicksGymWithItsNoteAndUndoKeepsItUnticked() = runTest {
        val c = core()
        val gym = c.addGym()
        val done = c.tickFromWorkouts(listOf(gymWorkout)).single()
        assertEquals(gym, done.habitId)
        assertEquals("Ticked Gym · 52-min workout", done.line)
        assertTrue(c.goalsView.value.habits.single { it.id == gym }.doneToday)
        val row = c.activityView.value.days.flatMap { it.rows }.single { it.id == done.activityId }
        assertTrue(row.canUndo)
        assertEquals("52-min workout · from Health Connect", row.detail)

        c.undoActivity(done.activityId)
        assertFalse(c.goalsView.value.habits.single { it.id == gym }.doneToday)
        // The same workout (or another today) never ticks it again once it was taken back.
        assertTrue(c.tickFromWorkouts(listOf(gymWorkout)).isEmpty())
        assertFalse(c.goalsView.value.habits.single { it.id == gym }.doneToday)
    }

    @Test
    fun aHandTickOrAPauseLeavesItAlone() = runTest {
        val c = core()
        val run = c.addHabit("Run", 3, os.meka.core.domain.HabitTiming.MORNING, 30, null)
        c.setAutonomyPaused(true)
        val runWorkout = Workout("hc-2", WorkoutKind.RUN, start - 3_600_000L, start - 30 * 60_000L)
        assertTrue(c.tickFromWorkouts(listOf(runWorkout)).isEmpty())
        c.setAutonomyPaused(false)
        assertEquals(listOf("Ticked Run · 30-min run"), c.tickFromWorkouts(listOf(runWorkout)).map { it.line })
        assertTrue(c.goalsView.value.habits.single { it.id == run }.doneToday)
        assertEquals(listOf("Run"), c.workoutHabits())
    }

    @Test
    fun readingInTheBackgroundHoldsDidYouGoUntilTheWatchHasHadItsChance() = runTest {
        now = start - 9 * 3_600_000L // Thursday 08:15: today's session is still ahead
        val c = core()
        c.addGym()
        fun ask(ns: List<os.meka.core.domain.Notice>) = ns.single { it.source == os.meka.core.domain.NoticeSource.SESSION_ASK }
        val end = ask(c.noticesNow()).atMs
        assertEquals(c.sessionsView.value.cards.first { it.endMs != null }.endMs, end)
        c.setWorkoutsReadInBackground(true)
        assertEquals(end + 30 * 60_000L, ask(c.noticesNow()).atMs)
        c.setWorkoutsReadInBackground(false)
        assertEquals(end, ask(c.noticesNow()).atMs)
    }

    @Test
    fun aShortNightPlansALighterDayOnThisDeviceOnly() = runTest {
        now = start - 9 * 3_600_000L // Thursday 08:15
        val c = core()
        repeat(8) { c.addTask("Task $it") } // 30 min each: four hours
        assertEquals(8, c.planDay().placements.size)
        val night = os.meka.core.domain.SleepSession(start - 18 * 3_600_000L, start - 18 * 3_600_000L + 310 * 60_000L) // 23:15 → 04:25
        assertEquals(310, c.setSleep(listOf(night)))
        val light = c.planDay()
        assertEquals(6, light.placements.size)
        assertEquals(2, light.restedOut.size)
        assertTrue(light.keptLine!!.startsWith("☾ Short night · 5 h 10 m asleep"))
        // Sleep switched off (nothing read): the usual plan.
        assertEquals(null, c.setSleep(emptyList()))
        assertEquals(8, c.planDay().placements.size)
    }
}
