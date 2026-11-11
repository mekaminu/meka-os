package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.wire.MessageRequestCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * "Handle her requests for me" through the facade (Take first, 2026-11-11), slice 1: his partner's work-from-home
 * request is done at once (the home day, three prep steps for today, a reminder) with no card and nothing sent; Undo
 * takes everything back; with the switch off it is today's card.
 */
class HandleForMeFacadeTest {
    private val now = 1_791_550_980_000L // Fri 9 Oct 2026, 14:03 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        var reply = MessageRequestCodec.Response("answered")
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun messageRequest(request: MessageRequestCodec.Request): MessageRequestCodec.Response = reply
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))
    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val lists = PeopleLists(family = setOf("Alex"))
    private fun msg(who: String, text: String, at: Long = now - 60_000) =
        CapturedItem("wa-$who-$at", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, text, null, at)

    private suspend fun titles(c: MekaCore, ids: List<String>) = ids.mapNotNull { c.taskForDetail(it)?.title }

    @Test
    fun herWorkFromHomeRequestIsDoneAtOnceAndUndoTakesItAllBack() = runTest {
        val c = core()
        c.setAboutYouField("partnerName", "Alex")
        server.reply = MessageRequestCodec.Response(
            "answered", listOf(MessageRequestCodec.Proposal("work_from_home", null, words = "Monday")),
        )
        val read = assertIs<RequestRead.Read>(c.readRequest(msg("Alex", "can you work from home on Monday?"), lists, emptySet()))
        assertTrue(read.cards.isEmpty(), "no card to tap")
        assertTrue(c.requests.value.isEmpty())
        val row = read.handled.single()
        // Monday is three days away: the steps land on the working day before, today (Friday), reminding at 16:00.
        assertEquals("Work from home Mon 12 Oct · added to Monday, 3 steps for today, reminder 16:00", row.line)
        assertEquals("From Alex · 14:02", row.from)
        assertEquals(listOf(row), c.doneForYou.value)
        assertEquals(3, row.taskIds.size)
        assertEquals(listOf("Tell your manager", "Get approval", "Put the request in at work"), titles(c, row.taskIds))
        assertTrue(row.taskIds.all { c.taskForDetail(it)?.remindAtMs != null }, "each step reminds")
        assertTrue(c.calendarView.value.sections.first { it.title == "Mon 12 Oct" }.workLine.orEmpty().startsWith("Work from home"))

        assertTrue(c.undoDoneForYou(row.id))
        assertTrue(c.doneForYou.value.isEmpty())
        assertTrue(titles(c, row.taskIds).isEmpty())
        assertFalse(c.calendarView.value.sections.first { it.title == "Mon 12 Oct" }.workLine.orEmpty().startsWith("Work from home"))
        assertFalse(c.undoDoneForYou(row.id), "only once")
    }

    @Test
    fun switchOffUncertainOrSomeoneElseStaysACard() = runTest {
        val c = core()
        c.setAboutYouField("partnerName", "Alex")
        c.setAboutYouPartnerHandle(false)
        server.reply = MessageRequestCodec.Response("answered", listOf(MessageRequestCodec.Proposal("task", "get milk", words = "tomorrow")))
        val off = assertIs<RequestRead.Read>(c.readRequest(msg("Alex", "get milk tomorrow"), lists, emptySet()))
        assertEquals(1, off.cards.size)
        assertTrue(off.handled.isEmpty())

        c.setAboutYouPartnerHandle(true)
        server.reply = MessageRequestCodec.Response("answered", listOf(MessageRequestCodec.Proposal("task", "sort the garden")))
        val vague = assertIs<RequestRead.Read>(c.readRequest(msg("Alex", "we should sort the garden at some point", at = now - 30_000), lists, emptySet()))
        assertEquals(1, vague.cards.size, "no clear day: a card, not a guess")
        assertTrue(vague.handled.isEmpty())

        server.reply = MessageRequestCodec.Response("answered", listOf(MessageRequestCodec.Proposal("task", "call the garage", words = "tomorrow")))
        val other = assertIs<RequestRead.Read>(c.readRequest(msg("Sam", "call the garage tomorrow"), PeopleLists(family = setOf("Alex", "Sam")), emptySet()))
        assertEquals(1, other.cards.size)
        assertTrue(c.doneForYou.value.isEmpty())
    }

    @Test
    fun herTaskGetsItsDayAndAReminderAndClearKeepsIt() = runTest {
        val c = core()
        c.setAboutYouField("partnerName", "Alex")
        server.reply = MessageRequestCodec.Response("answered", listOf(MessageRequestCodec.Proposal("task", "pick up the dry cleaning", words = "tomorrow")))
        val row = assertIs<RequestRead.Read>(c.readRequest(msg("Alex", "can you pick up the dry cleaning tomorrow?"), lists, emptySet())).handled.single()
        assertEquals("“Pick up the dry cleaning” · Tomorrow · added to Tomorrow, reminder Tomorrow 09:00", row.line)
        assertEquals(listOf("Pick up the dry cleaning"), titles(c, row.taskIds))
        assertTrue(c.taskForDetail(row.taskIds.single())?.remindAtMs != null)
        assertTrue(c.clearDoneForYou(row.id))
        assertTrue(c.doneForYou.value.isEmpty())
        assertEquals(listOf("Pick up the dry cleaning"), titles(c, row.taskIds), "Clear keeps what it made")
    }
}
