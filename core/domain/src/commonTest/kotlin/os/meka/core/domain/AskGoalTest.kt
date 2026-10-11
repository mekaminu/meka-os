package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Goals, properly, slice 8: Ask and Talk hear the goals, and "add a goal to run 5k by December" is a card. */
class AskGoalTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private val d = world.device("android")
    private val g = Goals(d.replica, { "G${n++}" }, { world.clock.nowMs })
    private val cal = LocalCalendar.UTC

    init {
        // Wednesday 14 October 2026, 08:00 UTC.
        world.clock.nowMs = CivilDate.toEpochDay(2026, 10, 14) * dayMs + 8 * 3_600_000L
    }

    private fun today() = world.clock.nowMs.floorDiv(dayMs)
    private fun day(y: Int, m: Int, dd: Int) = CivilDate.toEpochDay(y, m, dd)
    private fun card(title: String?, date: String? = null) =
        AskRules.card(AskRawAction("add_goal", title = title, date = date), AskContext.EMPTY, emptyMap(), world.clock.nowMs, cal,
            goals = g.goals(d.tasks.all()).first)

    @Test
    fun askHearsEachOpenGoalWithProgressAndTheNextStep() {
        assertEquals(emptyList(), GoalAskRules.askLines(g.view(d.tasks.all())))
        val year = g.addGoal("Run a marathon", horizon = GoalHorizon.YEAR)
        world.clock.nowMs += 1_000
        val month = g.addGoal("Run 5k", horizon = GoalHorizon.MONTH, parentId = year, dueDay = day(2026, 10, 31))
        g.addMilestone(month, "Run 2k")
        g.addMilestone(month, "Run 4k")
        g.setMilestoneDone(g.milestones(month).first().id, true)
        world.clock.nowMs += 1_000
        val old = g.addGoal("Old goal")
        g.finishGoal(old)

        val lines = GoalAskRules.askLines(g.view(d.tasks.all()))
        assertEquals("Goals · 2 open · 1 finished", lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("Goal “Run a marathon” · 50% · This year"), lines[1])
        assertEquals("Goal “Run 5k” · 50% · This month · by Sat 31 Oct · 1 of 2 milestones · under “Run a marathon” · Next milestone · Run 4k", lines[2])

        // They go with a question as `goals` lines, Meka's own words, so never untrusted.
        val todayView = TodayProjection.project(emptyList(), world.clock.nowMs, DayWindow(today() * dayMs, (today() + 1) * dayMs))
        val ctx = AskRules.context(todayView, world.clock.nowMs, cal, goals = lines)
        assertEquals(lines, ctx.items.filter { it.kind == AskItemKind.GOALS }.map { it.line })
        assertFalse(ctx.untrusted)
        assertTrue("add_goal" in AskRules.KINDS)
    }

    @Test
    fun addGoalIsACardWithTheHorizonItsDayFallsIn() {
        val t = today()
        assertEquals(GoalHorizon.MONTH, GoalAskRules.horizonFor(null, t))
        assertEquals(GoalHorizon.WEEK, GoalAskRules.horizonFor(day(2026, 10, 18), t))
        assertEquals(GoalHorizon.MONTH, GoalAskRules.horizonFor(day(2026, 10, 19), t))
        assertEquals(GoalHorizon.QUARTER, GoalAskRules.horizonFor(day(2026, 12, 31), t))
        assertEquals(GoalHorizon.SOMEDAY, GoalAskRules.horizonFor(day(2027, 6, 1), t))

        val c = card("  run   5k. ", "2026-12-31")!!
        assertEquals(AskProposal.AddGoal("run 5k", GoalHorizon.QUARTER, day(2026, 12, 31)), c.proposal)
        assertEquals("Add goal “run 5k” · This quarter · by Thu 31 Dec", c.line)
        assertEquals("Add", c.button)
        assertEquals("Added goal “run 5k”", AskRules.doneLine(c.proposal, t))
        assertEquals("add the goal run 5k, done by Thursday 31 December", TalkRules.phrase(c.proposal, t, past = false))

        val plain = card("Read 12 books")!!
        assertEquals("Add goal “Read 12 books” · This month", plain.line)
        assertEquals("added the goal Read 12 books for this month", TalkRules.phrase(plain.proposal, t, past = true))

        // No title, a bad or past day, or a goal already open by that name: no card.
        assertNull(card(" "))
        assertNull(card("Run 5k", "31 December"))
        assertNull(card("Run 5k", "2026-10-01"))
        g.addGoal("Run 5K")
        assertNull(card("run 5k"))
        // Never an earned-trust kind: Meka always sees a goal before it's added.
        assertNull(TrustRules.kindOf(c.proposal))
    }

    @Test
    fun undoOnlyTakesBackAGoalNothingHasBeenAddedTo() {
        val id = g.addGoal("Run 5k")
        assertTrue(GoalAskRules.untouched(g.goals(d.tasks.all()).first.single { it.id == id }))
        g.addMilestone(id, "Run 2k")
        assertFalse(GoalAskRules.untouched(g.goals(d.tasks.all()).first.single { it.id == id }))
    }
}
