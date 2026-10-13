package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.AskRawAction
import os.meka.core.domain.TalkTurn
import os.meka.core.domain.TrustRules
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Earned trust through the facade (build plan V1): five yeses in a row, the offer, then MEKA just does it, with Undo. */
class EarnedTrustFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val asked = mutableListOf<AskContext>()
        var reply: AskReply = AskReply.Answered("Nothing yet.", emptyList())
        override suspend fun aiStatus(): AiStatusReply? = AiStatusReply("on", null, 120, 2000, "ok")
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply {
            asked += context
            return reply
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun addTask(title: String) { server.reply = AskReply.Answered("Sure.", listOf(AskRawAction("add_task", title = title))) }

    private suspend fun MekaCore.titles() = (listOfNotNull(today.value.upNext) + today.value.yourDay).map { it.title }

    @Test
    fun fiveYesesBringTheOfferThenMekaJustDoesItWithUndo() = runTest {
        val c = core()
        repeat(TrustRules.STREAK) { i ->
            addTask("Task $i")
            val card = assertIs<AskOutcome.Answered>(c.askMeka("add task $i")).answer.cards.single()
            val done = c.doAsk(card)
            if (i < TrustRules.STREAK - 1) assertNull(done.offer) else {
                val offer = assertNotNull(done.offer)
                assertEquals("add_task", offer.kindKey)
                assertEquals("Navilife will add tasks without asking. Undo any one to go back to asking.", c.answerTrustOffer(offer.kindKey, yes = true))
            }
        }
        addTask("Milk")
        val answer = assertIs<AskOutcome.Answered>(c.askMeka("add milk")).answer
        assertTrue(answer.cards.isEmpty())
        val did = answer.did.single()
        assertEquals("Added “Milk” · done for you", did.line)
        assertTrue("Milk" in c.titles())
        val row = c.activityView.value.days.flatMap { it.rows }.single { it.id == did.activityId }
        assertTrue(row.canUndo)
        assertTrue(row.why.contains("just do it"), row.why)

        assertEquals("Undone. Navilife will ask before it adds tasks again.", c.undoAutoAsk(did.activityId))
        assertTrue("Milk" !in c.titles())
        // Back to asking first.
        addTask("Bread")
        val again = assertIs<AskOutcome.Answered>(c.askMeka("add bread")).answer
        assertEquals(listOf("Add “Bread”"), again.cards.map { it.line })
        assertTrue(again.did.isEmpty())
    }

    @Test
    fun aQuestionCarryingSomeoneElsesWordsStillAsksFirst() = runTest {
        val c = core()
        c.answerTrustOffer("add_task", yes = true)
        // A saved link's title is someone else's words: the question is untrusted, so the card waits for a tap.
        c.addToQueue("https://www.bbc.co.uk/news/uk-123", "Why trains run late")
        addTask("Milk")
        val answer = assertIs<AskOutcome.Answered>(c.askMeka("add milk")).answer
        assertTrue(server.asked.last().untrusted)
        assertEquals(1, answer.cards.size)
        assertTrue(answer.did.isEmpty())
    }

    @Test
    fun anUndoneYesStartsTheRunAgainAndKeepAskingNeverOffersAgain() = runTest {
        val c = core()
        repeat(TrustRules.STREAK - 1) { i ->
            addTask("Task $i")
            val d = c.doAsk(assertIs<AskOutcome.Answered>(c.askMeka("add")).answer.cards.single())
            if (i == TrustRules.STREAK - 2) c.undoAsk(assertNotNull(d.undo))
        }
        addTask("Fifth")
        assertNull(c.doAsk(assertIs<AskOutcome.Answered>(c.askMeka("add")).answer.cards.single()).offer)
        repeat(TrustRules.STREAK - 2) {
            addTask("More $it")
            c.doAsk(assertIs<AskOutcome.Answered>(c.askMeka("add")).answer.cards.single())
        }
        // Five in a row since the Undo: the offer; "Keep asking" and it never comes back.
        addTask("Last")
        val offer = c.doAsk(assertIs<AskOutcome.Answered>(c.askMeka("add")).answer.cards.single()).offer
        assertNotNull(offer)
        c.answerTrustOffer(offer.kindKey, yes = false)
        repeat(TrustRules.STREAK) {
            addTask("Again $it")
            assertNull(c.doAsk(assertIs<AskOutcome.Answered>(c.askMeka("add")).answer.cards.single()).offer)
        }
    }

    @Test
    fun cardsThatCanNeverRunOnTheirOwnNeverOffer() = runTest {
        val c = core()
        repeat(TrustRules.STREAK + 1) {
            server.reply = AskReply.Answered("Sure.", listOf(AskRawAction("set_timer", minutes = 5 + it)))
            assertNull(c.doAsk(assertIs<AskOutcome.Answered>(c.askMeka("timer")).answer.cards.single()).offer)
        }
    }
}
