package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.HandleThisCodec
import os.meka.core.wire.MessageRequestCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** "Handle this" through the facade (V1, slice 1): one shared text out, checked "You shared" cards back, nothing done. */
class HandleThisFacadeTest {
    private val now = 1_791_668_400_000L // Sat 10 Oct 2026, 22:40 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val sent = mutableListOf<HandleThisCodec.Request>()
        var reply = MessageRequestCodec.Response("answered")
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun handleThis(request: HandleThisCodec.Request): MessageRequestCodec.Response {
            sent += request
            if (down) throw TransportException("offline")
            return reply
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))
    private fun core(transport: SyncTransport? = server, device: String = "android") = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val letter = "Dear parent, parents' evening is on Tuesday 20 October from 6pm. Please return the reply slip by Friday."

    @Test
    fun aShareGoesOutAloneAndComesBackAsCheckedCardsThatWaitInNeedsYou() = runTest {
        val c = core()
        server.reply = MessageRequestCodec.Response(
            "answered",
            listOf(
                MessageRequestCodec.Proposal("event", "parents' evening", "2027-01-01", "18:00", "Tuesday 20 October"),
                MessageRequestCodec.Proposal("work_from_home", null, "2026-10-20"),
                MessageRequestCodec.Proposal("task", "Return the reply slip", words = "by Friday"),
            ),
        )
        val out = assertIs<HandleOutcome.Cards>(c.handleThis(letter, "Year 4 parents' evening"))
        val sent = server.sent.single()
        assertEquals("2026-10-10", sent.date)
        assertTrue(sent.now.endsWith("2026 · 22:40"), sent.now)
        assertEquals("Year 4 parents' evening", sent.subject)
        assertEquals(letter, sent.text)
        // The text's own words win over the model's date; a work-from-home day is never made from a share.
        assertEquals(listOf("Add event: Parents' evening · Tue 20 Oct · 18:00"), out.cards.take(1).map { it.action })
        assertEquals(2, out.cards.size)
        assertEquals("2 things to add", out.line)
        assertTrue(out.cards.all { it.from == "You shared · 22:40" })
        assertEquals(out.cards, c.requests.value)
        // Nothing was added until Meka taps Add.
        assertTrue(c.today.value.yourDay.isEmpty() && c.today.value.upNext == null)

        // Shared again the same day: nothing goes out, the open cards come back.
        val again = assertIs<HandleOutcome.Cards>(c.handleThis(letter, "Year 4 parents' evening"))
        assertEquals(1, server.sent.size)
        assertEquals(out.cards.map { it.id }, again.cards.map { it.id })

        // Add on one, Not needed on the other: then sharing it again says it was handled.
        val task = out.cards.first { it.proposal.title == "Return the reply slip" }
        val done = assertNotNull(c.acceptRequest(task.id))
        assertEquals("Added “Return the reply slip” · Fri 16 Oct", done.line)
        assertTrue(c.declineRequest(out.cards.first { it.id != task.id }.id))
        assertTrue(c.requests.value.isEmpty())
        assertIs<HandleOutcome.NothingToDo>(c.handleThis(letter, "Year 4 parents' evening"))
        assertEquals(1, server.sent.size)
    }

    @Test
    fun aBareLinkNeverLeavesAndNothingToDoSaysSo() = runTest {
        val c = core()
        assertIs<HandleOutcome.Unavailable>(c.handleThis("https://example.com/some/article", null))
        assertTrue(server.sent.isEmpty())
        server.reply = MessageRequestCodec.Response("answered", emptyList())
        val none = assertIs<HandleOutcome.NothingToDo>(c.handleThis("Our autumn sale ends soon, 20% off everything!", "Sale"))
        assertEquals("Nothing in that for you to do · add it as a task instead", none.line)
        assertTrue(c.requests.value.isEmpty())
    }

    @Test
    fun offlineOffOrNotConnectedSaysSoAndKeepsNothing() = runTest {
        val c = core()
        server.down = true
        assertEquals("Couldn't reach MEKA · try again when you're online", assertIs<HandleOutcome.Unavailable>(c.handleThis(letter)).line)
        server.down = false
        server.reply = MessageRequestCodec.Response("off")
        assertIs<HandleOutcome.Unavailable>(c.handleThis(letter))
        assertIs<HandleOutcome.Unavailable>(core(transport = null).handleThis(letter))
        assertTrue(c.requests.value.isEmpty())
    }

    @Test
    fun aRenewalGoesOnTheRadarOnAddAndUndoTakesItOff() = runTest {
        val c = core()
        server.reply = MessageRequestCodec.Response(
            "answered",
            listOf(MessageRequestCodec.Proposal("renewal", "Car insurance", "2026-11-12", "09:00", "on 12 November", "insurance", "year", "412")),
        )
        val renewal = "Your car insurance with Acme renews on 12 November. Your new premium is £412 for the year."
        val card = assertIs<HandleOutcome.Cards>(c.handleThis(renewal, "Your renewal")).cards.single()
        assertEquals("Add to renewals: Car insurance · renews Thu 12 Nov", card.action)
        assertEquals("Insurance · every year · £412 a year", card.detail)
        assertEquals(null, card.changeLabel)
        assertEquals(null, c.changeRequest(card.id))
        assertTrue(c.listsView.value.renewals.all.isEmpty())

        val done = assertNotNull(c.acceptRequest(card.id))
        assertEquals("On the radar: “Car insurance” · renews Thu 12 Nov", done.line)
        val item = c.listsView.value.renewals.all.single()
        assertEquals("Car insurance", item.title)
        assertEquals(os.meka.core.domain.ObligationKind.INSURANCE, item.kind)
        assertEquals(os.meka.core.domain.RenewalRepeat.YEARLY, item.repeats)
        assertEquals(41_200L, item.costPence)
        assertTrue(c.requests.value.isEmpty())

        assertTrue(c.undoRequest(done))
        assertTrue(c.listsView.value.renewals.all.isEmpty())
    }

    @Test
    fun aRenewalFromAWatchedMessageIsNeverMade() {
        val raw = listOf(os.meka.core.domain.RawRequestProposal("renewal", "Netflix", "2026-11-01"))
        assertTrue(os.meka.core.domain.MessageRequestRules.check(raw, 20_736L, 600).isEmpty())
        assertEquals(1, os.meka.core.domain.MessageRequestRules.check(raw, 20_736L, 600, allowRenewal = true).size)
    }
}
