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
import os.meka.core.sync.TransportException
import os.meka.core.wire.MessageRequestCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Requests from people Meka watches through the facade (V1, slice 2): one message out, checked cards back, nothing done. */
class MessageRequestFacadeTest {
    private val now = 1_791_550_980_000L // Fri 9 Oct 2026, 14:03 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val sent = mutableListOf<MessageRequestCodec.Request>()
        var reply = MessageRequestCodec.Response("answered")
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun messageRequest(request: MessageRequestCodec.Request): MessageRequestCodec.Response {
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

    private val lists = PeopleLists(family = setOf("Mum"))
    private val watching = setOf("Wife")
    private fun msg(who: String, text: String, at: Long = now - 60_000, group: String? = null) =
        CapturedItem("wa-$who-$at", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, text, group, at)

    @Test
    fun aWatchedMessageGoesOutAloneAndComesBackAsACheckedCard() = runTest {
        val c = core()
        server.reply = MessageRequestCodec.Response(
            "answered",
            listOf(
                MessageRequestCodec.Proposal("task", "pick up the dry cleaning", date = "2027-12-01", words = "tomorrow"),
                MessageRequestCodec.Proposal("send_money", "£500"),
            ),
        )
        val read = assertIs<RequestRead.Read>(c.readRequest(msg("Wife", "can you pick up the dry cleaning tomorrow?"), lists, watching))
        val sent = server.sent.single()
        assertEquals("Wife", sent.sender)
        assertEquals("14:02", sent.sentAt)
        assertEquals("2026-10-09", sent.date)
        assertTrue(sent.now.endsWith("2026 · 14:03"), sent.now)
        assertEquals("can you pick up the dry cleaning tomorrow?", sent.text)
        // The message's own words win over the model's date; an unknown kind is dropped.
        val card = read.cards.single()
        assertEquals("Add task: Pick up the dry cleaning · Tomorrow", card.action)
        assertEquals("From Wife · 14:02", card.from)
        assertEquals(listOf(card), c.requests.value)
        // Nothing was added until Meka taps Add.
        assertTrue(c.today.value.yourDay.isEmpty() && c.today.value.upNext == null)

        // WhatsApp re-posts the unread message: asked again, no second card.
        c.readRequest(msg("Wife", "can you pick up the dry cleaning tomorrow?"), lists, watching)
        assertEquals(1, c.requests.value.size)

        assertTrue(c.declineRequest(card.id))
        assertTrue(c.requests.value.isEmpty())
    }

    @Test
    fun othersGroupsAndPhotosNeverReachTheServerAndAVoiceNoteNeedsNoAi() = runTest {
        val c = core()
        assertIs<RequestRead.Skipped>(c.readRequest(msg("Tunde", "are you coming Saturday?"), lists, watching))
        assertIs<RequestRead.Skipped>(c.readRequest(msg("Wife", "milk please", group = "Family"), lists, watching))
        assertIs<RequestRead.Skipped>(c.readRequest(msg("Wife", "📷 Photo"), lists, watching))
        val voice = assertIs<RequestRead.Read>(c.readRequest(msg("Mum", "🎤 Voice message (0:12)"), lists, watching))
        assertEquals("Remind me: Listen to Mum's voice note", voice.cards.single().action)
        assertTrue(server.sent.isEmpty())
    }

    @Test
    fun offlineOrNoServerSaysSoAndKeepsNothing() = runTest {
        server.down = true
        val c = core()
        assertIs<RequestRead.Unavailable>(c.readRequest(msg("Wife", "get bread"), lists, watching))
        assertTrue(c.requests.value.isEmpty())
        assertIs<RequestRead.Unavailable>(core(transport = null).readRequest(msg("Wife", "get bread"), lists, watching))
        server.down = false
        server.reply = MessageRequestCodec.Response("off")
        assertIs<RequestRead.Unavailable>(c.readRequest(msg("Wife", "get bread"), lists, watching))
    }

    @Test
    fun addMakesThePlannedTaskClearsTheCardAndUndoTakesTheTaskBack() = runTest {
        val c = core()
        server.reply = MessageRequestCodec.Response(
            "answered", listOf(MessageRequestCodec.Proposal("task", "pick up the dry cleaning", words = "tomorrow at 6pm")),
        )
        val card = assertIs<RequestRead.Read>(c.readRequest(msg("Wife", "can you pick up the dry cleaning tomorrow at 6pm?"), lists, watching)).cards.single()
        val done = assertNotNull(c.acceptRequest(card.id))
        assertEquals("Added “Pick up the dry cleaning” · Tomorrow · 18:00", done.line)
        assertTrue(c.requests.value.isEmpty())
        assertNull(c.acceptRequest(card.id)) // answered already (the Mac got there first)
        val tomorrow = c.calendarView.value.sections.first { it.title == "Tomorrow" }
        assertEquals(listOf("Pick up the dry cleaning"), tomorrow.rows.map { it.title })
        assertTrue(c.undoRequest(done))
        assertTrue(c.calendarView.value.sections.first { it.title == "Tomorrow" }.rows.isEmpty())
    }

    @Test
    fun anEventWithNoCalendarToWriteToBecomesATaskAndChangeOpensATask() = runTest {
        val c = core()
        server.reply = MessageRequestCodec.Response(
            "answered", listOf(MessageRequestCodec.Proposal("event", "parents' evening", words = "Tue at 6pm")),
        )
        val card = assertIs<RequestRead.Read>(c.readRequest(msg("Wife", "parents' evening is Tue at 6pm"), lists, watching)).cards.single()
        val changed = assertNotNull(c.changeRequest(card.id))
        assertNotNull(changed.taskId)
        assertNull(changed.editId)
        assertEquals("Added “Parents' evening” as a task · Tue 13 Oct · 18:00", changed.line)
        assertTrue(c.requests.value.isEmpty())
    }

    @Test
    fun workFromHomeMarksTheDayOnBothAppsAndHasNoChange() = runTest {
        val c = core()
        server.reply = MessageRequestCodec.Response(
            "answered", listOf(MessageRequestCodec.Proposal("work_from_home", null, words = "Thursday")),
        )
        val card = assertIs<RequestRead.Read>(c.readRequest(msg("Wife", "can you work from home Thursday?"), lists, watching)).cards.single()
        assertNull(card.changeLabel)
        assertNull(c.changeRequest(card.id))
        val done = assertNotNull(c.acceptRequest(card.id))
        assertEquals("Thu 15 Oct: work from home", done.line)
        val thu = c.calendarView.value.sections.first { it.title == "Thu 15 Oct" }
        assertEquals("Work from home 09:00–17:30", thu.workLine)
        assertTrue(c.undoRequest(done))
        assertEquals("Work 09:00–17:30", c.calendarView.value.sections.first { it.title == "Thu 15 Oct" }.workLine)
    }
}
