package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.AskProposal
import os.meka.core.domain.AskRawAction
import os.meka.core.domain.AskRules
import os.meka.core.domain.AskUndo
import os.meka.core.domain.AiStatusView
import os.meka.core.domain.ValidationException
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Ask MEKA through the facade (build plan V1, AI layer slice 3): handles stay here, cards do nothing until tapped. */
class AskMekaFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London (16:15 UTC)

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val asked = mutableListOf<Pair<String, AskContext>>()
        var reply: AskReply = AskReply.Answered("Nothing yet.", emptyList())
        var down = false
        var status: AiStatusReply? = AiStatusReply("on", null, 120, 2000, "ok")
        override suspend fun aiStatus(): AiStatusReply? {
            if (down) throw TransportException("offline")
            return status
        }
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext): AskReply {
            asked += question to context
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
    fun aQuestionCarriesTodayByHandlesAndCardsComeBackChecked() = runTest {
        val c = core()
        val id = c.addTask("Book dentist")
        server.reply = AskReply.Answered(
            "Moved it to tomorrow at 9.",
            listOf(
                AskRawAction("move_task", ref = "t1", date = "2026-10-09", time = "09:00"),
                AskRawAction("complete_task", ref = "t9"),
                AskRawAction("send_email", title = "everything"),
            ),
        )
        val out = assertIs<AskOutcome.Answered>(c.askMeka("  Move the dentist to tomorrow morning "))
        val (q, ctx) = server.asked.single()
        assertEquals("Move the dentist to tomorrow morning", q)
        assertEquals("2026-10-08", ctx.dateIso)
        assertTrue(ctx.nowLine.endsWith("· 17:15"), ctx.nowLine)
        assertEquals("t1", ctx.items.single().ref)
        assertFalse(id in ctx.items.single().line)

        assertEquals("Moved it to tomorrow at 9.", out.answer.text)
        val card = out.answer.cards.single()
        assertEquals("Move “Book dentist” to Tomorrow · 09:00", card.line)
        // Nothing happened until the tap.
        assertEquals(id, c.today.value.upNext?.id)
        assertEquals("Moved “Book dentist” to Tomorrow · 09:00", c.doAsk(card).line)
        assertTrue((listOfNotNull(c.today.value.upNext) + c.today.value.yourDay).none { it.id == id })
    }

    @Test
    fun cardsAddTicksOffAndSetTimers() = runTest {
        val c = core()
        val id = c.addTask("Create CR")
        server.reply = AskReply.Answered("Here.", listOf(AskRawAction("add_task", title = "Milk"), AskRawAction("complete_task", ref = "t1"), AskRawAction("set_timer", minutes = 20)))
        val cards = assertIs<AskOutcome.Answered>(c.askMeka("do things")).answer.cards
        assertEquals(listOf("Add “Milk”", "Tick off “Create CR”", "Timer · 20 min"), cards.map { it.line })
        assertEquals("Added “Milk”", c.doAsk(cards[0]).line)
        assertTrue((listOfNotNull(c.today.value.upNext) + c.today.value.yourDay).any { it.title == "Milk" })
        assertEquals("Ticked off “Create CR”", c.doAsk(cards[1]).line)
        assertTrue(c.today.value.doneToday.any { it.id == id })
        // Ticked off already: the card can't do it twice.
        assertFailsWith<ValidationException> { c.doAsk(cards[1]) }
        assertEquals("Timer set · 20 min", c.doAsk(cards[2]).line)
        val fast = os.meka.core.domain.AskRules.cardOf(AskProposal.StartFast(36), 0)
        assertEquals("Started a 36 h fast", c.doAsk(fast).line)
        assertFailsWith<ValidationException> { c.doAsk(fast) }
    }

    @Test
    fun noAnswerSaysWhy() = runTest {
        val c = core()
        server.reply = AskReply.Unavailable("off", null)
        assertEquals(AskOutcome.Unavailable("MEKA's AI is off"), c.askMeka("hi"))
        server.reply = AskReply.Unavailable("over", null)
        assertEquals(AskOutcome.Unavailable("This month's AI budget is used up · back on the 1st"), c.askMeka("hi"))
        server.reply = AskReply.Unavailable("failed", "Couldn't reach Anthropic")
        assertEquals(AskOutcome.Unavailable("Couldn't ask: Couldn't reach Anthropic"), c.askMeka("hi"))
        server.down = true
        assertEquals(AskOutcome.Unavailable(AskRules.OFFLINE_LINE), c.askMeka("hi"))
        assertEquals(AskOutcome.Unavailable("Ask something first"), c.askMeka("   "))
        assertEquals(4, server.asked.size)
        assertEquals(AskOutcome.Unavailable(AskRules.NOT_CONNECTED_LINE), core(transport = null).askMeka("hi"))
    }

    @Test
    fun everyCardCanBeTakenBack() = runTest {
        val c = core()
        c.addTask("Book dentist")
        c.addTask("Create CR")
        server.reply = AskReply.Answered("Here.", listOf(
            AskRawAction("add_task", title = "Milk", date = "2026-10-09"),
            AskRawAction("complete_task", ref = "t1"),
            AskRawAction("move_task", ref = "t2", date = "2026-10-10", time = "09:00"),
        ))
        val cards = assertIs<AskOutcome.Answered>(c.askMeka("do things")).answer.cards
        assertEquals(3, cards.size)
        val dentist = assertIs<AskProposal.CompleteTask>(cards[1].proposal).taskId
        val cr = assertIs<AskProposal.MoveTask>(cards[2].proposal).taskId
        assertTrue(dentist != cr)
        fun open() = (listOfNotNull(c.today.value.upNext) + c.today.value.yourDay).map { it.id }

        val added = c.doAsk(cards[0])
        val milk = assertIs<AskUndo.RemoveTask>(added.undo).taskId
        assertTrue(c.undoAsk(added.undo!!))
        assertFalse(c.undoAsk(added.undo!!))
        assertFalse(milk in open())

        val ticked = c.doAsk(cards[1])
        assertTrue(c.today.value.doneToday.any { it.id == dentist })
        assertTrue(c.undoAsk(ticked.undo!!))
        assertTrue(dentist in open())
        // Already put back: nothing more to do.
        assertFalse(c.undoAsk(ticked.undo!!))

        val moved = c.doAsk(cards[2])
        assertFalse(cr in open())
        assertTrue(c.undoAsk(moved.undo!!))
        assertTrue(cr in open())

        // Changed since (moved again by hand): Undo leaves it.
        val again = c.doAsk(cards[2])
        c.setWhen(cr, AskRules.parseDay("2026-10-13")!!, null)
        assertFalse(c.undoAsk(again.undo!!))

        val fast = c.doAsk(AskRules.cardOf(AskProposal.StartFast(36), 0))
        assertTrue(c.undoAsk(fast.undo!!))
        assertFalse(c.undoAsk(fast.undo!!))
        // Thrown away, so a new fast can start.
        c.doAsk(AskRules.cardOf(AskProposal.StartFast(36), 0))

        val timer = c.doAsk(AskRules.cardOf(AskProposal.Timer(20), 0))
        assertIs<AskUndo.CancelAlarm>(timer.undo)
        assertTrue(c.undoAsk(timer.undo!!))
    }

    @Test
    fun theStatusLineComesFromTheServer() = runTest {
        val c = core()
        assertEquals(AiStatusView("On · $1.20 of $20 this month", lit = false, canAsk = true), c.aiStatus())
        server.status = AiStatusReply("on", null, 2000, 2000, "over")
        assertFalse(c.aiStatus().canAsk)
        server.status = null
        assertEquals(AskRules.STATUS_UNKNOWN, c.aiStatus())
        server.down = true
        assertEquals(AskRules.STATUS_UNKNOWN, c.aiStatus())
        assertEquals(AskRules.STATUS_NOT_CONNECTED, core(transport = null).aiStatus())
    }
}
