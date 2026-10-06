package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FastingTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private val hourMs = 3_600_000L
    private var n = 0
    private fun ids(): String = "F${n++}"
    private fun fasting(d: Device) = Fasting(d.replica, ::ids, { world.clock.nowMs })

    private val d = world.device("android")
    private val f = fasting(d)

    init {
        // Start every test on a Monday at 20:00 UTC.
        val today = world.clock.nowMs.floorDiv(dayMs)
        val toMonday = (8 - CivilDate.isoDayOfWeek(today)) % 7
        world.clock.nowMs = (today + toMonday) * dayMs + 20 * hourMs
    }

    private fun today() = world.clock.nowMs.floorDiv(dayMs)
    private fun hours(h: Int) = world.clock.advance(h * hourMs)
    private fun window(day: Long = today()) = DayWindow(day * dayMs, (day + 1) * dayMs)

    @Test
    fun theDefaultPlanIsSixteenEightWithLunchToEightPm() {
        val v = f.view()
        assertEquals(FastingRules.DEFAULT_PLAN, v.plan)
        assertEquals("16:8 · eating 12:00–20:00", v.plan.line)
        assertFalse(v.isFasting)
        assertEquals("Eating window closed at 20:00", v.windowLine)
        assertNull(v.weekLine)
        assertEquals(7, v.week.size)
        assertTrue(v.week.last().isToday)
        assertEquals("Mon", v.week.last().label)
    }

    @Test
    fun aFastRunsToItsGoalAndEndsIntoHistory() {
        val id = f.start()
        var cur = assertNotNull(f.view().current)
        assertEquals(id, cur.id)
        assertEquals(16, cur.targetHours)
        assertEquals("Started 20:00", cur.startedLine)
        assertEquals("Goal 16 h · at 12:00 tomorrow", cur.goalLine)
        assertFalse(cur.reachedGoal)
        assertFailsWith<ValidationException> { f.start() }

        hours(17) // Tuesday 13:00
        cur = assertNotNull(f.view().current)
        assertTrue(cur.reachedGoal)
        assertEquals("Goal reached at 12:00", cur.goalLine)
        assertEquals("Started 20:00 yesterday", cur.startedLine)

        f.end()
        val v = f.view()
        assertNull(v.current)
        val last = assertNotNull(v.last)
        assertEquals("Last fast 17 h 00 m · goal reached", last.line)
        assertTrue(last.canResume)
        assertEquals(17.0, v.week.last().hours)
        assertTrue(v.week.last().reachedGoal)
        assertEquals("1 fast in 7 days · average 17 h 00 m · 1 reached the goal", v.weekLine)
        assertEquals("Eating window open until 20:00", v.windowLine)
    }

    @Test
    fun aMistakenEndCanBeUndoneForAFewMinutes() {
        val id = f.start()
        hours(2)
        f.end()
        f.resume(id)
        assertEquals(id, f.view().current?.id)
        f.end()
        world.clock.advance(FastingRules.RESUME_WINDOW_MS + 1)
        assertFalse(f.view().last!!.canResume)
        assertFailsWith<ValidationException> { f.resume(id) }
    }

    @Test
    fun startCanBeBackdatedAndNudgedButNeverIntoTheFutureOrTheLastFast() {
        f.start(startedMinutesAgo = 60)
        assertEquals("Started 19:00", f.view().current!!.startedLine)
        f.moveStart(-30)
        assertEquals("Started 18:30", f.view().current!!.startedLine)
        assertFailsWith<ValidationException> { f.moveStart(120) }
        f.setTarget(18)
        assertEquals("Goal 18 h · at 12:30 tomorrow", f.view().current!!.goalLine)
        hours(1)
        f.end() // 21:00
        assertFailsWith<ValidationException> { f.start(startedMinutesAgo = 30) }
        f.start()
        assertFailsWith<ValidationException> { f.moveStart(-30) }
        assertFailsWith<ValidationException> { f.start(startedMinutesAgo = 0) }
    }

    @Test
    fun discardLeavesNoHistory() {
        f.start()
        hours(1)
        f.discard()
        val v = f.view()
        assertNull(v.current)
        assertNull(v.last)
        assertFailsWith<ValidationException> { f.end() }
    }

    @Test
    fun choosingAPlanSetsTheGoalAndWindowAndTheNextFastUsesIt() {
        f.choosePlan(2) // 18:6
        assertEquals(FastingPlan(18, 13 * 60, 19 * 60), f.view().plan)
        assertEquals("18:6", FastingRules.planLabel(f.view().plan))
        f.start()
        assertEquals(18, f.view().current!!.targetHours)
        assertFailsWith<ValidationException> { f.setPlan(16, 12 * 60, 12 * 60) }
        assertFailsWith<ValidationException> { f.setTarget(0) }
    }

    @Test
    fun windowLinesCoverBeforeDuringAfterAndAcrossMidnight() {
        val p = FastingRules.DEFAULT_PLAN
        assertEquals("Eating window opens at 12:00", FastingRules.windowLine(9 * 60, p))
        assertEquals("Eating window open until 20:00", FastingRules.windowLine(12 * 60, p))
        assertEquals("Eating window closed at 20:00", FastingRules.windowLine(20 * 60, p))
        val late = FastingPlan(16, 22 * 60, 2 * 60)
        assertTrue(FastingRules.inWindow(23 * 60, late.eatingStartMin, late.eatingEndMin))
        assertTrue(FastingRules.inWindow(60, late.eatingStartMin, late.eatingEndMin))
        assertEquals("Eating window opens at 22:00", FastingRules.windowLine(3 * 60, late))
    }

    @Test
    fun timerTextAndProgress() {
        assertEquals("14:05:09", FastingRules.clock(14 * hourMs + 5 * 60_000 + 9_000))
        assertEquals("26:00:00", FastingRules.clock(26 * hourMs))
        assertEquals("45 m", FastingRules.duration(45 * 60_000L))
        assertEquals("16 h 05 m", FastingRules.duration(16 * hourMs + 5 * 60_000))
        assertEquals(0.5f, FastingRules.progress(0, 16, 8 * hourMs))
        assertEquals(1f, FastingRules.progress(0, 16, 20 * hourMs))
        assertEquals("1 h ago", FastingRules.startedAgoLabel(60))
        assertEquals("−30 min", FastingRules.moveLabel(-30))
    }

    @Test
    fun twoDevicesStartingOfflineCountOnceAndEndingEndsBoth() {
        val mac = world.device("mac")
        val m = fasting(mac)
        f.start(startedMinutesAgo = 30)
        m.start()
        d.sync(); mac.sync(); d.sync()
        // The earlier start counts on both devices.
        assertEquals("Started 19:30", f.view().current!!.startedLine)
        assertEquals("Started 19:30", m.view().current!!.startedLine)
        hours(16)
        m.end()
        d.sync(); mac.sync(); d.sync()
        assertNull(f.view().current)
        val v = f.view()
        assertEquals("1 fast in 7 days · average 16 h 30 m · 1 reached the goal", v.weekLine)
    }

    @Test
    fun historyCountsTheLastSevenDaysOnTheDayEachEnded() {
        // Eight fasts, 20:00 to 12:00, ending on eight days in a row: the oldest falls outside the week.
        repeat(8) { f.start(); hours(16); f.end(); hours(8) }
        val v = f.view()
        assertEquals(7, v.week.size)
        assertTrue(v.week.all { it.hours == 16.0 && it.reachedGoal })
        assertEquals("7 fasts in 7 days · average 16 h 00 m · 7 reached the goal", v.weekLine)
    }

    @Test
    fun thePlannerKeepsMealsFreeAndPlansAroundThem() {
        // Tuesday 08:00, fasting since Monday 20:00: the goal (12:00) lands today.
        f.start()
        hours(12)
        val day = window()
        val meals = f.plannerMeals(day)
        assertEquals(listOf("Break your fast", "Last meal before your fast"), meals.map { it.title })
        assertEquals(day.startMs + 12 * hourMs, meals[0].startMs)
        assertEquals(day.startMs + 20 * hourMs, meals[1].endMs)

        val tasks = (0 until 13).map { i ->
            Task(
                id = "t$i", title = "Task $i", notes = null, lifecycle = Lifecycle.ACTIVE, priority = 0, dueAtMs = null,
                scheduledAtMs = null, estimateMinutes = 30, goalId = null, somedayKind = null, createdAtMs = i.toLong(),
                completedAtMs = null, hasConflict = false,
            )
        }
        val plan = DayPlanner.plan(tasks, emptyList(), world.clock.nowMs, day, meals = meals)
        assertEquals(meals, plan.meals)
        plan.placements.forEach { p ->
            meals.forEach { m -> assertTrue(p.endMs <= m.startMs || p.startMs >= m.endMs, "${p.task.title} overlaps ${m.title}") }
        }

        // Not fasting, window still ahead: only the last meal; after it closes, nothing.
        f.end()
        assertEquals(listOf("Last meal before your fast"), f.plannerMeals(day).map { it.title })
        hours(12) // 20:00
        assertTrue(f.plannerMeals(day).isEmpty())
    }
}
