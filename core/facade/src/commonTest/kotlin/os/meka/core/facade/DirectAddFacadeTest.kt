package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.AskProposal
import os.meka.core.domain.DirectAddRules
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** "The mic should just add it", slice 1: his own "add …" is done at once, no AI, no card, with Undo. */
class DirectAddFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        var asked = 0
        override suspend fun aiStatus(): AiStatusReply? = AiStatusReply("on", null, 120, 2000, "ok")
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply {
            asked++
            return AskReply.Answered("Nothing yet.", emptyList())
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun bookAsTheVerbIsATaskNotABook() = runTest {
        // Quality gate slice 28: Activity showed "book The boiler service on friday", added to Reading.
        val c = core()
        val did = assertIs<AskOutcome.Answered>(c.askMeka("add book the boiler service on friday")).answer.did.single()
        // Thu 8 Oct: Friday is tomorrow.
        assertEquals(AskProposal.AddTask("Book the boiler service", c.todayEpochDay() + 1, null), did.proposal)
        assertTrue(c.readingView.value.isEmpty, "${c.readingView.value}")
        assertEquals(0, server.asked)
    }

    @Test
    fun anAddIsDoneAtOnceWithoutTheAiAndUndoTakesItBack() = runTest {
        val c = core()
        val answer = assertIs<AskOutcome.Answered>(c.talk("add call the garage tomorrow", emptyList())).answer
        assertEquals(0, server.asked)
        assertTrue(answer.cards.isEmpty())
        val did = answer.did.single()
        assertEquals("Added “Call the garage” · Tomorrow", did.line)
        assertEquals(AskProposal.AddTask("Call the garage", c.todayEpochDay() + 1, null), did.proposal)
        val row = c.activityView.value.days.flatMap { it.rows }.single { it.id == did.activityId }
        assertTrue(row.canUndo)
        // An Undo takes it off and leaves earned trust alone.
        assertEquals("Undone", c.undoAutoAsk(did.activityId))
        // Shopping through Ask's typed field.
        val shop = assertIs<AskOutcome.Answered>(c.askMeka("add milk and eggs to the shopping")).answer
        assertEquals("Added milk and eggs to shopping", shop.did.single().line)
        assertEquals(0, server.asked)
        // Anything else still goes to the AI.
        c.askMeka("what's on tomorrow?")
        assertEquals(1, server.asked)
    }

    @Test
    fun askFirstOrPausedMakesItACard() = runTest {
        val c = core()
        assertEquals(DirectAddRules.OFF_SAID, c.resetTrust(DirectAddRules.ENTITY_ID))
        val row = c.autonomyView.value.sections.flatMap { it.rows }.single { it.key == DirectAddRules.ENTITY_ID }
        assertEquals(DirectAddRules.JUST_ADD, row.action)
        val card = assertIs<AskOutcome.Answered>(c.askMeka("remind me to ring Sam")).answer
        assertEquals(listOf("Add “Ring Sam”"), card.cards.map { it.line })
        assertTrue(card.did.isEmpty())
        assertEquals(DirectAddRules.ON_SAID, c.resetTrust(DirectAddRules.ENTITY_ID))
        c.setAutonomyPaused(true)
        val paused = assertIs<AskOutcome.Answered>(c.askMeka("remind me to ring Sam")).answer
        assertEquals(1, paused.cards.size)
        assertTrue(paused.did.isEmpty())
        assertEquals(0, server.asked)
    }
}
