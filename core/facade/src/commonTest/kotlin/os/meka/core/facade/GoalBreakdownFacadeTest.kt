package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.GoalBreakdownRules
import os.meka.core.domain.GoalHorizon
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.GoalBreakdownCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Goals, properly, slice 4: Break it down through the facade — one goal out, checked proposals back, nothing added until Keep. */
class GoalBreakdownFacadeTest {
    private val now = 1_791_668_400_000L // Sat 10 Oct 2026, 22:40 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val sent = mutableListOf<GoalBreakdownCodec.Request>()
        var reply = GoalBreakdownCodec.Response("answered")
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun goalBreakdown(request: GoalBreakdownCodec.Request): GoalBreakdownCodec.Response {
            sent += request
            if (down) throw TransportException("offline")
            return reply
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))
    private fun core(transport: SyncTransport? = server) = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theGoalGoesOutAloneAndNothingIsAddedUntilKeepThenUndoTakesItBack() = runTest {
        val c = core()
        val other = c.addGoal("Save £2,000", null, GoalHorizon.YEAR)
        c.addGoalStep(other, "Open a savings pot")
        val g = c.addGoal("Run a half marathon", "Under 2 hours", GoalHorizon.YEAR)
        c.setGoalWhy(g, "To feel fit again")
        c.addGoalMilestone(g, "Run 5k")
        c.addGoalStep(g, "Buy running shoes")
        c.addTask("Call the dentist")
        server.reply = GoalBreakdownCodec.Response(
            "answered",
            listOf("Run 5k", "Run 10k", "Run 15k."),
            listOf("buy running shoes", "Book a gait analysis"),
        )
        val out = assertIs<BreakdownOutcome.Proposals>(c.breakDownGoal(g))
        val sent = server.sent.single()
        assertEquals("Run a half marathon", sent.title)
        assertEquals("This year", sent.horizon)
        assertEquals("Under 2 hours", sent.done)
        assertEquals("To feel fit again", sent.why)
        assertEquals(listOf("Run 5k"), sent.milestones)
        // Only this goal's open steps: never another goal's or an unlinked task.
        assertEquals(listOf("Buy running shoes"), sent.steps)
        assertEquals("2026-10-10", sent.date)
        // What it already has isn't offered again.
        assertEquals(listOf("Run 10k", "Run 15k"), out.breakdown.milestones)
        assertEquals(listOf("Book a gait analysis"), out.breakdown.steps)
        assertEquals("2 milestones and 1 first step to choose from", out.line)
        // Nothing added yet.
        assertEquals(1, c.goalsView.value.goals.first { it.id == g }.milestones.size)

        val kept = c.keepBreakdown(g, listOf("Run 10k"), listOf("Book a gait analysis"))
        assertEquals("Added 1 milestone and 1 step", kept.line)
        val goal = c.goalsView.value.goals.first { it.id == g }
        assertEquals(listOf("Run 5k", "Run 10k"), goal.milestones.map { it.title })
        assertEquals(2, goal.tasksTotal)

        c.undoBreakdown(kept)
        val back = c.goalsView.value.goals.first { it.id == g }
        assertEquals(listOf("Run 5k"), back.milestones.map { it.title })
        assertEquals(1, back.tasksTotal)
    }

    @Test
    fun offlineOffAndNothingNewSaySo() = runTest {
        val c = core()
        val g = c.addGoal("Read 12 books", null, GoalHorizon.YEAR)
        server.reply = GoalBreakdownCodec.Response("answered", listOf("read 12 books"))
        // A proposal that repeats the goal's own milestone isn't new.
        c.addGoalMilestone(g, "Read 12 books")
        assertEquals(GoalBreakdownRules.NOTHING_LINE, assertIs<BreakdownOutcome.NothingNew>(c.breakDownGoal(g)).line)
        server.reply = GoalBreakdownCodec.Response("off", reason = "MEKA's AI is off")
        assertIs<BreakdownOutcome.Unavailable>(c.breakDownGoal(g))
        server.down = true
        assertIs<BreakdownOutcome.Unavailable>(c.breakDownGoal(g))
        assertEquals(GoalBreakdownRules.NOT_CONNECTED_LINE, assertIs<BreakdownOutcome.Unavailable>(core(null).let { n ->
            val id = n.addGoal("Learn Spanish", null, GoalHorizon.YEAR); n.breakDownGoal(id)
        }).line)
        assertTrue(server.sent.size == 3)
    }
}
