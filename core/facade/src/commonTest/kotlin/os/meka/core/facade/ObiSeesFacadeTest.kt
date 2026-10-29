package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskItemKind
import os.meka.core.domain.GoalHorizon
import os.meka.core.domain.ObiArea
import os.meka.core.domain.ObiSees
import os.meka.core.domain.SomedayKind
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Obi sees everything, slice 1 (Meka 2026-10-28: "I asked Obi about my study and he said he had no view of that" …
 * "I need the orb to be the same"): typed Ask carries his Study plan, and Live's Navilife look-up on the orb returns,
 * for every area, the same lines Ask's <today> block carries.
 */
class ObiSeesFacadeTest {
    private var now = 1_793_181_600_000L // Wed 28 Oct 2026, 10:00 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val asked = mutableListOf<Pair<String, AskContext>>()
        override suspend fun aiStatus(): AiStatusReply? = AiStatusReply("on", null, 120, 2000, "ok")
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply {
            asked += question to context
            return AskReply.Answered("Ok.", emptyList())
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun askCarriesHisStudyAndOneLineWhenTheQuestionIsAboutSomethingElse() = runTest {
        val c = core()
        val id = c.addStudyCourse("Data analytics\nModule 1: Foundations\nWelcome 15 min\nPivot tables 1 h").courseId!!
        val (welcome, _) = c.studyView.value.course(id)!!.modules.single().lessons
        c.planStudyLesson(welcome.id, c.todayEpochDay())
        c.tick()
        c.askMeka("How's my study going?")
        val study = server.asked.last().second.items.filter { it.kind == AskItemKind.STUDY }.map { it.line }
        assertEquals("Study · 1 course · 0 of 2 lessons done", study.first())
        assertTrue(study.any { it.startsWith("Course “Data analytics” next lessons: “Welcome”") }, study.joinToString("\n"))
        assertTrue("Study planned today · 1 lesson · “Welcome” (Data analytics)" in study, study.joinToString("\n"))
        c.askMeka("Why is the sky blue?")
        val brief = server.asked.last().second.items.filter { it.kind == AskItemKind.STUDY }.map { it.line }
        assertEquals(1, brief.size)
        assertTrue(brief.single().startsWith("Study · 1 course · “Data analytics” 0 of 2"), brief.single())
    }

    @Test
    fun theOrbsLookUpReturnsTheSameLinesAsAskForEveryArea() = runTest {
        val c = core()
        c.addStudyCourse("Data analytics\nModule 1: Foundations\nWelcome 15 min\nPivot tables 1 h")
        c.addGoal("Run 5k", null, GoalHorizon.MONTH)
        c.addShopping("milk, bread")
        c.addFact("Car reg: AB12 CDE")
        c.addErrand("When I'm at Tesco: batteries")
        c.addWin("Shipped the new report")
        // Slice 3: Someday, a decision and the reading list.
        c.addSomeday("Learn the guitar", SomedayKind.IDEA)
        c.recordDecision("Keep the old car another year", "cheaper", 90)
        c.addReading("Dune by Frank Herbert")
        c.tick()
        val q = "How's my study going?"
        // An area that sends one counts line unless asked about is compared on a question about it.
        val about = mapOf(ObiArea.BOARD to "What's on Someday?", ObiArea.DECISIONS to "What did I decide?", ObiArea.READING to "Which book should I take on holiday?", ObiArea.KIDS to "What do the kids need?")
        c.askMeka(q)
        val context = server.asked.last().second
        ObiArea.entries.forEach { area ->
            val question = about[area] ?: q
            val ctx = if (question == q) context else {
                val before = server.asked.size
                c.askMeka(question)
                assertEquals(before + 1, server.asked.size, "${area.key}: answered on the device, not asked")
                server.asked.last().second
            }
            val asked = ctx.items.filter { it.kind == area.kind }.map { "${area.kind.wire}: ${it.line}" }
            val looked = c.liveNavilife(area.key, question)
            if (asked.isEmpty()) assertTrue(looked.startsWith("Navilife has nothing in ${area.label}"), "${area.key}: $looked")
            else assertEquals(asked.joinToString("\n"), looked, area.key)
        }
        // The ones with data are really there, not just equally empty.
        listOf(ObiArea.STUDY, ObiArea.GOALS, ObiArea.SHOPPING, ObiArea.FACTS, ObiArea.ERRANDS, ObiArea.WINS, ObiArea.BOARD, ObiArea.DECISIONS, ObiArea.READING)
            .forEach { a -> assertTrue(context.items.any { it.kind == a.kind }, a.key) }
        // Not asked about, each of those sends just its counts line.
        assertEquals(listOf("Week board · 0 with no day yet · 1 on Someday"), context.items.filter { it.kind == AskItemKind.BOARD }.map { it.line })
        assertEquals(listOf("Decisions · 1 active"), context.items.filter { it.kind == AskItemKind.DECISIONS }.map { it.line })
        assertEquals(1, context.items.count { it.kind == AskItemKind.READING })
        // The server takes every kind the device sends (an unknown one would refuse the whole question).
        AskItemKind.entries.forEach { assertTrue(it.wire in os.meka.core.wire.AskCodec.KINDS, it.wire) }
        assertEquals(ObiSees.NO_AREA, c.liveNavilife("money", ""))
    }
}
