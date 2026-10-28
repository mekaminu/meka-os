package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.AskProposal
import os.meka.core.domain.AskRawAction
import os.meka.core.domain.AskUndo
import os.meka.core.domain.TalkTurn
import os.meka.core.domain.ValidationException
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Obi sees everything, slice 2: typed Ask and the orb's Live offer the Study cards "tick a lesson" and "plan a lesson
 * (or a module) for a day"; a tap does what Study's own ring and Plan for… do, and Undo puts it back.
 */
class StudyAskFacadeTest {
    private var now = 1_793_181_600_000L // Wed 28 Oct 2026, 10:00 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        var reply: AskReply = AskReply.Answered("Ok.", emptyList())
        override suspend fun aiStatus(): AiStatusReply? = AiStatusReply("on", null, 120, 2000, "ok")
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply = reply
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private suspend fun lessons(c: MekaCore) = c.studyView.value.courses.single().modules.flatMap { it.lessons }

    @Test
    fun askTicksALessonWithATapAndUndoUnticksIt() = runTest {
        val c = core()
        c.addStudyCourse("Data analytics\nModule 1: Foundations\nWelcome 15 min\nPivot tables 1 h")
        c.tick()
        server.reply = AskReply.Answered("Nice work.", listOf(
            AskRawAction("study_done", title = "Pivot tables"),
            AskRawAction("study_done", title = "Calculus"), // not in Study: no card
        ))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("I've finished pivot tables"))
        assertEquals(listOf("Tick off “Pivot tables” · Data analytics"), out.answer.cards.map { it.line })
        c.tick()
        assertFalse(lessons(c).first { it.title == "Pivot tables" }.done) // nothing until tapped

        val done = c.doAsk(out.answer.cards.single())
        assertEquals("Ticked off “Pivot tables” in Study", done.line)
        c.tick()
        assertTrue(lessons(c).first { it.title == "Pivot tables" }.done)
        assertFailsWith<ValidationException> { c.doAsk(out.answer.cards.single()) }
        assertTrue(c.undoAsk(assertIs<AskUndo.UntickLesson>(done.undo)))
        c.tick()
        assertFalse(lessons(c).first { it.title == "Pivot tables" }.done)
        assertFalse(c.undoAsk(done.undo!!))
    }

    @Test
    fun askPlansAModuleForTomorrowAndUndoPutsEachDayBack() = runTest {
        val c = core()
        c.addStudyCourse("Data analytics\nModule 1: Foundations\nWelcome 15 min\nModule 4: Reporting\nDashboards 30 min\nCharts 30 min\nReview 20 min")
        c.tick()
        val today = c.todayEpochDay()
        val charts = lessons(c).first { it.title == "Charts" }
        c.planStudyLesson(charts.id, today + 3)
        server.reply = AskReply.Answered("Done.", listOf(AskRawAction("plan_lesson", title = "module 4", date = "2026-10-29")))
        val out = assertIs<AskOutcome.Answered>(c.askMeka("Plan module 4 for tomorrow"))
        assertEquals(listOf("Plan Module 4, Reporting · 3 lessons for tomorrow · Data analytics"), out.answer.cards.map { it.line })

        val done = c.doAsk(out.answer.cards.single())
        assertEquals("Planned Module 4, Reporting · 3 lessons for tomorrow", done.line)
        c.tick()
        val planned = lessons(c).filter { it.plannedDay == today + 1 }.map { it.title }
        assertEquals(listOf("Dashboards", "Charts", "Review"), planned)
        assertTrue(c.undoAsk(assertIs<AskUndo.PutBackLessonPlan>(done.undo)))
        c.tick()
        assertEquals(today + 3, lessons(c).first { it.title == "Charts" }.plannedDay)
        assertNull(lessons(c).first { it.title == "Dashboards" }.plannedDay)
    }

    @Test
    fun theOrbOffersTheSameStudyCards() = runTest {
        val c = core()
        c.addStudyCourse("Data analytics\nWelcome 15 min\nPivot tables 1 h")
        c.tick()
        val offer = c.livePropose(mapOf("kind" to "plan_lesson", "title" to "Pivot tables", "date" to "2026-10-30"))
        val card = assertNotNull(offer.card)
        assertIs<AskProposal.StudyPlan>(card.proposal)
        assertEquals("Plan “Pivot tables” for Fri 30 Oct · Data analytics", card.line)
        val tick = c.livePropose(mapOf("kind" to "study_done", "title" to "Welcome"))
        assertIs<AskProposal.StudyTick>(assertNotNull(tick.card).proposal)
    }
}
