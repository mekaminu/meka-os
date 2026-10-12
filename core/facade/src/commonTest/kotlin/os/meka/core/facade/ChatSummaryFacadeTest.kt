package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem
import os.meka.core.domain.ChatSummaryRules
import os.meka.core.domain.TalkTurn
import os.meka.core.domain.TriageChatRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.ChatSummaryCodec
import os.meka.core.wire.MessageTriageCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A busy chat's summary through the facade (messages grouped per chat, slice 3c): one batched call, checked, synced without the messages. */
class ChatSummaryFacadeTest {
    private var now = 1_791_550_980_000L // Fri 9 Oct 2026, 14:03 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val sent = mutableListOf<ChatSummaryCodec.Request>()
        var triage = MessageTriageCodec.Response("answered", lane = "fyi", summary = "Lineup talk")
        var reply = ChatSummaryCodec.Response("answered")
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun messageTriage(request: MessageTriageCodec.Request) = triage
        override suspend fun chatSummary(request: ChatSummaryCodec.Request): ChatSummaryCodec.Response {
            sent += request
            if (down) throw TransportException("offline")
            return reply
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))
    private fun core(device: String = "android") = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun msg(who: String, text: String, i: Int) =
        CapturedItem("wa-$who-$i", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, who, text, "Barça lads", now - (10 - i) * 60_000L)

    private suspend fun busyChat(c: MekaCore) =
        repeat(4) { i -> assertIs<TriageRead.Read>(c.triageMessage(msg(listOf("Femi", "Obi")[i % 2], "@Meka lineup $i", i))) }

    @Test
    fun aBusyChatGoesOutOnceWithGistsOnlyAndTheMacShowsTheSummary() = runTest {
        val fold = core()
        busyChat(fold)
        server.reply = ChatSummaryCodec.Response("answered", listOf(ChatSummaryCodec.ChatAnswer("Barça lads", "Saturday's lineup, nothing for you yet")))
        val read = assertIs<ChatSummaryRead.Read>(fold.summariseChats())
        assertEquals(1, read.chats.size)
        val sent = server.sent.single().chats.single()
        assertEquals("Barça lads", sent.name)
        assertTrue(sent.group)
        // MEKA's gists go, never the messages' own words.
        assertEquals(listOf("Lineup talk"), sent.lines.map { it.text }.distinct())
        assertEquals("2026-10-09", server.sent.single().date)

        // Up to date: nothing more is sent.
        assertIs<ChatSummaryRead.NotDue>(fold.summariseChats())
        assertEquals(1, server.sent.size)

        val chats = ChatSummaryRules.withSummaries(TriageChatRules.chats(fold.triage.value), fold.chatSummaries.value)
        assertEquals("Saturday's lineup, nothing for you yet", chats.single().summary)

        fold.syncNow()
        val mac = core("mac")
        mac.syncNow()
        val onMac = ChatSummaryRules.withSummaries(TriageChatRules.chats(mac.triage.value), mac.chatSummaries.value)
        assertEquals("Saturday's lineup, nothing for you yet", onMac.single().summary)
    }

    @Test
    fun offlineOrAiOffWritesNothingAndAQuietChatIsNeverSent() = runTest {
        val fold = core()
        repeat(3) { i -> fold.triageMessage(msg("Femi", "@Meka lineup $i", i)) }
        assertIs<ChatSummaryRead.NotDue>(fold.summariseChats())
        fold.triageMessage(msg("Obi", "@Meka lineup 3", 3))
        server.down = true
        assertIs<ChatSummaryRead.Unavailable>(fold.summariseChats())
        server.down = false
        server.reply = ChatSummaryCodec.Response("off", reason = "MEKA's AI is off")
        assertIs<ChatSummaryRead.Unavailable>(fold.summariseChats())
        assertTrue(fold.chatSummaries.value.isEmpty())
        assertNull(ChatSummaryRules.withSummaries(TriageChatRules.chats(fold.triage.value), fold.chatSummaries.value).single().summary)
    }
}
