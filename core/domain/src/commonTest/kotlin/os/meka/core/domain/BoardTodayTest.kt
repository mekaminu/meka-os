package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Week board, slice 5: Today follows the Board's order, and the morning brief says how the day looks. */
class BoardTodayTest {
    private val dayMs = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    // Wed 21 Oct 2026, 08:00 UTC.
    private val today = CivilDate.toEpochDay(2026, 10, 21)
    private val now = today * dayMs + 8 * 3_600_000L
    private val window = DayWindow(today * dayMs, (today + 1) * dayMs)

    private fun task(
        id: String, title: String = id, scheduled: Long? = null, estimate: Int? = null, priority: Int = 0, created: Long = 0,
        rank: Long? = null, week: Long? = null, lifecycle: Lifecycle = Lifecycle.ACTIVE,
    ) = Task(
        id, title, null, lifecycle, null, scheduled, estimate, priority, null, null, created, null, false,
        boardRank = rank, boardWeek = week,
    )

    @Test
    fun todaysAnytimeFollowsTheBoardOrderThenPriority() {
        val tasks = listOf(
            task("urgent", priority = 2, created = 1),
            task("second", rank = 2_000, created = 2),
            task("first", rank = 1_000, created = 3),
            task("plain", created = 4),
        )
        val t = TodayProjection.project(tasks, now, window, calendar = cal)
        // The placed cards lead (and so the first one is Up next), then the rest by priority.
        assertEquals("first", t.upNext?.id)
        assertEquals(listOf("second", "urgent", "plain"), t.yourDay.map { it.id })
        assertEquals(listOf("second", "urgent", "plain"), t.timeline.anytime.map { it.id })
    }

    @Test
    fun withNothingPlacedTodayKeepsItsOldOrder() {
        val tasks = listOf(task("a", created = 1), task("b", priority = 1, created = 2), task("c", created = 3))
        val t = TodayProjection.project(tasks, now, window, calendar = cal)
        assertEquals(listOf("b", "a", "c"), listOfNotNull(t.upNext).map { it.id } + t.yourDay.map { it.id })
    }

    @Test
    fun theBriefsPlanCountsCarriedPlannedAndTheTime() {
        val tasks = listOf(
            task("old", scheduled = (today - 2) * dayMs + 9 * 3_600_000L, estimate = 60),
            task("older", scheduled = (today - 3) * dayMs + 9 * 3_600_000L, estimate = 30),
            task("meet", scheduled = today * dayMs + 14 * 3_600_000L, estimate = 120),
            task("call", estimate = 15),
            task("idea"),
            task("parked", week = today - 2),
        )
        val plan = BoardTodayRules.plan(ShutdownRules.left(tasks, window), tasks, window)
        assertEquals(DayPlan(2, 3, 240), plan)
        assertEquals("2 carried over · 3 planned · about 4h", plan.line)
        assertEquals("Two carried over from earlier days and three planned, about 4 hours in all.", plan.sentence)
    }

    @Test
    fun theTimeIsSaidOnlyWhenHalfTheTasksHaveOne() {
        val few = listOf(task("a", estimate = 30), task("b"), task("c"))
        val p = BoardTodayRules.plan(few, few, window)
        assertNull(p.minutes)
        // Just "3 planned" would repeat the day's count: nothing more is said.
        assertNull(p.line)
        assertNull(p.sentence)
        assertEquals("3 planned · about 1h 30m", DayPlan(0, 3, 90).line)
        assertEquals("Three tasks planned, about an hour and a half in all.", DayPlan(0, 3, 90).sentence)
        // Navilife's guess from a similar task counts as a time.
        val guessed = listOf(task("x", "Call the garage", estimate = 30), task("y", "Call the garage again"))
        val g = BoardTodayRules.plan(guessed, guessed, window)
        assertEquals(60, g.minutes)
        assertEquals("One task planned, about 30 minutes in all.", DayPlan(0, 1, 30).sentence)
        assertEquals("One task carried over from an earlier day.", DayPlan(1, 0, null).sentence)
        assertNull(DayPlan(0, 0, 90).line)
    }

    @Test
    fun roundingAndSpokenTimes() {
        assertEquals(15, BoardTodayRules.round(5))
        assertEquals(45, BoardTodayRules.round(40))
        assertEquals(90, BoardTodayRules.round(80))
        assertEquals(240, BoardTodayRules.round(235))
        assertEquals("45 minutes", BoardTodayRules.spokenMinutes(45))
        assertEquals("an hour", BoardTodayRules.spokenMinutes(60))
        assertEquals("an hour and a half", BoardTodayRules.spokenMinutes(90))
        assertEquals("2 and a half hours", BoardTodayRules.spokenMinutes(150))
    }
}
