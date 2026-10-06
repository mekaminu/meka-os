package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalsTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private fun ids(): String = "G${n++}"
    private fun goals(d: Device) = Goals(d.replica, ::ids, { world.clock.nowMs })

    private val d = world.device("android")
    private val g = goals(d)

    init {
        // Start every test on a Monday at 08:00 UTC.
        val today = world.clock.nowMs.floorDiv(dayMs)
        val toMonday = (8 - CivilDate.isoDayOfWeek(today)) % 7
        world.clock.nowMs = (today + toMonday) * dayMs + 8 * 3_600_000L
    }

    private fun today() = world.clock.nowMs.floorDiv(dayMs)
    private fun nextDay() = world.clock.advance(dayMs)
    private fun habit(id: String) = g.habits().single { it.id == id }

    // ---- Pace rules ----

    @Test
    fun aDailyHabitIsDueEachDayAndBehindOnceADayIsMissed() {
        val h = g.addHabit("Stretch", perWeek = 7)
        assertEquals(HabitPace.DUE, habit(h).pace)
        assertEquals("To do today", habit(h).meta)
        g.setHabitDone(h, true)
        assertEquals(HabitPace.DONE_TODAY, habit(h).pace)
        nextDay()
        assertEquals(HabitPace.DUE, habit(h).pace)
        nextDay() // Tuesday missed
        assertEquals(HabitPace.BEHIND, habit(h).pace)
        assertEquals("Behind · 1 of 7 this week", habit(h).meta)
    }

    @Test
    fun threeAWeekIsDueMondayOnTrackTuesdayAndBehindWhenItSlips() {
        val target = 3
        val monday = today()
        assertEquals(HabitPace.DUE, GoalRules.pace(target, 0, false, monday, monday))
        assertEquals(HabitPace.ON_TRACK, GoalRules.pace(target, 1, false, monday + 1, monday))
        assertEquals(HabitPace.DUE, GoalRules.pace(target, 1, false, monday + 2, monday))
        assertEquals(HabitPace.BEHIND, GoalRules.pace(target, 0, false, monday + 2, monday))
        assertEquals(HabitPace.WEEK_MET, GoalRules.pace(target, 3, false, monday + 4, monday))
    }

    @Test
    fun aHabitAddedMidweekCountsThisWeekProRata() {
        nextDay(); nextDay(); nextDay() // Thursday
        val h = g.addHabit("Run", perWeek = 7)
        val item = habit(h)
        assertEquals(4, item.weekTarget) // Thu–Sun
        assertEquals(HabitPace.DUE, item.pace) // not behind for Mon–Wed before it existed
        assertEquals(2, GoalRules.weekTarget(3, today(), today())) // 3 a week from Thursday: ceil(3 × 4/7)
    }

    // ---- Streaks ----

    @Test
    fun dailyStreakCountsDaysInARowAndSurvivesUntilTodayIsMissed() {
        val h = g.addHabit("Read", perWeek = 7)
        repeat(4) { g.setHabitDone(h, true); nextDay() }
        // Today (5th day) still open: the streak of 4 stands.
        assertEquals(4, habit(h).streak)
        assertEquals("4-day streak", habit(h).streakLine)
        nextDay()
        assertEquals(0, habit(h).streak)
        assertNull(habit(h).streakLine)
    }

    @Test
    fun weeklyStreakCountsWeeksThatMetTheTarget() {
        val h = g.addHabit("Swim", perWeek = 2)
        // Week 1: Mon + Wed. Week 2: Tue + Thu. Week 3 (now): Mon only so far.
        g.setHabitDone(h, true); nextDay(); nextDay(); g.setHabitDone(h, true)
        repeat(6) { nextDay() } // Tuesday of week 2
        g.setHabitDone(h, true); nextDay(); nextDay(); g.setHabitDone(h, true)
        repeat(4) { nextDay() } // Monday of week 3
        g.setHabitDone(h, true)
        val item = habit(h)
        assertEquals(2, item.streak)
        assertEquals("week", item.streakUnit)
        assertEquals(listOf(true, false, false, false, false, false, false), item.week)
        g.setHabitDone(h, true, today() - 1) // a late tick for yesterday (Sunday) counts in week 2
        assertEquals(2, habit(h).streak)
    }

    @Test
    fun untickAndFutureDays() {
        val h = g.addHabit("Floss")
        g.setHabitDone(h, true)
        g.setHabitDone(h, false)
        assertFalse(habit(h).doneToday)
        assertFailsWith<ValidationException> { g.setHabitDone(h, true, today() + 1) }
        assertFailsWith<ValidationException> { g.addHabit("Too often", perWeek = 8) }
        assertFailsWith<ValidationException> { g.addHabit("   ") }
    }

    // ---- Sync ----

    @Test
    fun tickingTheSameDayOnTwoOfflineDevicesEndsWithOneTick() {
        val mac = world.device("mac")
        val mg = goals(mac)
        val h = g.addHabit("Meditate")
        d.sync(); mac.sync()
        d.goOffline(); mac.goOffline()
        g.setHabitDone(h, true)
        mg.setHabitDone(h, true)
        d.goOnline(); mac.goOnline()
        d.syncWithRetry(); mac.syncWithRetry(); d.syncWithRetry()
        assertEquals(1, habit(h).doneThisWeek)
        assertEquals(1, mg.habits().single().doneThisWeek)
        assertEquals(1, d.replica.entities(EntityTypes.HABIT_COMPLETION).size)
    }

    // ---- Goals ----

    @Test
    fun goalProgressIsSetByHandUntilSomethingIsLinked() {
        val goal = g.addGoal("Half marathon", target = "Under 2 hours", horizon = GoalHorizon.MEDIUM)
        g.setGoalProgress(goal, 40)
        var item = g.view(d.tasks.all()).goals.single()
        assertEquals(40, item.progressPct)
        assertFalse(item.counted)
        assertEquals("Months · set by hand", item.meta)

        val run = g.addHabit("Run", perWeek = 2, goalId = goal)
        val t1 = d.tasks.create(NewTask("Buy shoes"))
        val t2 = d.tasks.create(NewTask("Book race"))
        g.setTaskGoal(t1, goal); g.setTaskGoal(t2, goal)
        d.tasks.complete(t1)
        g.setHabitDone(run, true)
        item = g.view(d.tasks.all()).goals.single()
        assertTrue(item.counted)
        // Tasks 1/2 done (1.0 + 0.0), habit 1 of 2 this week (0.5): (1 + 0 + 0.5) / 3 = 50 %.
        assertEquals(50, item.progressPct)
        assertEquals("Months · 1 of 2 tasks · 1 habit", item.meta)
    }

    @Test
    fun finishedGoalsLeaveTheListAndDeletingAGoalUnlinksItsHabits() {
        val a = g.addGoal("Learn Spanish", horizon = GoalHorizon.LONG)
        val b = g.addGoal("Clear the loft", horizon = GoalHorizon.SHORT)
        assertEquals(listOf(b, a), g.view(d.tasks.all()).goals.map { it.id })
        g.finishGoal(b)
        val v = g.view(d.tasks.all())
        assertEquals(listOf(a), v.goals.map { it.id })
        assertEquals(1, v.finishedGoals)
        val h = g.addHabit("Duolingo", goalId = a)
        g.deleteGoal(a)
        assertNull(habit(h).goalId)
        assertTrue(g.view(d.tasks.all()).goals.isEmpty())
    }

    @Test
    fun viewOrdersBehindFirstAndSaysSoInOneLine() {
        val ok = g.addHabit("Walk", perWeek = 7)
        g.setHabitDone(ok, true)
        val late = g.addHabit("Journal", perWeek = 7)
        nextDay()
        g.setHabitDone(ok, true)
        val v = g.view(d.tasks.all())
        assertEquals(listOf(late, ok), v.habits.map { it.id })
        assertEquals("1 habit behind", v.paceLine)
        assertEquals(listOf(PlannerHabit(late, "Journal", 15, HabitTiming.ANYTIME, behind = true)), g.plannerHabits())
    }

    // ---- Planner makes room ----

    @Test
    fun plannerMakesRoomForHabitsInTheirPartOfTheDayBeforeTasks() {
        val hour = 3_600_000L
        val day = DayWindow(0, 24 * hour)
        val task = Task("t", "Report", null, Lifecycle.ACTIVE, null, null, 60, 0, null, null, 0, null, false)
        val plan = DayPlanner.plan(
            tasks = listOf(task), events = emptyList(), nowMs = 8 * hour, day = day,
            habits = listOf(
                PlannerHabit("gym", "Gym", 45, HabitTiming.EVENING, behind = false),
                PlannerHabit("read", "Read", 20, HabitTiming.ANYTIME, behind = true),
            ),
        )
        // Behind first: Read at 09:00 (20 min → 09:30 aligned). Gym in the evening window: 17:00. Report after Read.
        assertEquals(listOf("read" to 9 * hour, "gym" to 17 * hour), plan.habits.map { it.habitId to it.startMs })
        assertEquals(9 * hour + 30 * 60_000L, plan.placements.single().startMs)
        assertFalse(plan.isBlank)
    }

    @Test
    fun aHabitWithNoRoomIsReportedNotSqueezed() {
        val hour = 3_600_000L
        val plan = DayPlanner.plan(
            emptyList(), emptyList(), nowMs = 20 * hour + 50 * 60_000L, day = DayWindow(0, 24 * hour),
            habits = listOf(PlannerHabit("gym", "Gym", 30, HabitTiming.MORNING, behind = true)),
        )
        assertTrue(plan.habits.isEmpty())
        assertEquals(listOf("gym"), plan.habitsUnplaced.map { it.id })
    }
}
