package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem
import os.meka.core.domain.GroupMode
import os.meka.core.domain.TalkTurn
import os.meka.core.domain.TriageCard
import os.meka.core.domain.TriageLane
import os.meka.core.domain.TriageSettings
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.MessageRequestCodec
import os.meka.core.wire.MessageTriageCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The messages assistant through the facade (V1, slice 2): routed first, one message out, checked cards back, nothing sent. */
class MessageTriageFacadeTest {
    private val now = 1_791_550_980_000L // Fri 9 Oct 2026, 14:03 in London

    private class Server(service: SyncService) : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val sent = mutableListOf<MessageTriageCodec.Request>()
        var reply = MessageTriageCodec.Response("answered", lane = "fyi")
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun messageTriage(request: MessageTriageCodec.Request): MessageTriageCodec.Response {
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

    private fun msg(who: String, text: String, group: String? = null, at: Long = now - 60_000, kind: CaptureKind = CaptureKind.MESSAGE) =
        CapturedItem("wa-$who-$at-${text.length}", CaptureApp.WHATSAPP, kind, who, text, group, at)

    @Test
    fun aOneToOneMessageGoesOutAloneAndComesBackAsAReplyCardWithACheckedDraft() = runTest {
        val c = core()
        server.reply = MessageTriageCodec.Response("answered", lane = "needs_reply", summary = "Asks if you're coming Saturday", draft = "Yes, I'll be there — what time?")
        val item = msg("Tunde", "are you coming Saturday?")
        val read = assertIs<TriageRead.Read>(c.triageMessage(item))
        val sent = server.sent.single()
        assertEquals("Tunde", sent.sender)
        assertEquals("", sent.group)
        assertEquals("14:02", sent.sentAt)
        assertEquals("2026-10-09", sent.date)
        assertEquals("are you coming Saturday?", sent.text)
        val card = assertNotNull(read.card)
        assertEquals(TriageLane.NEEDS_REPLY, card.lane)
        assertEquals("Tunde · 14:02", card.from)
        assertEquals("Yes, I'll be there — what time?", card.draft)
        assertEquals(listOf(card), c.triage.value)
        // A re-post isn't asked again.
        assertIs<TriageRead.Skipped>(c.triageMessage(item))
        assertEquals(1, server.sent.size)
        assertTrue(c.dismissTriage(item.id))
        assertTrue(c.triage.value.isEmpty())
    }

    @Test
    fun aDraftCarryingALinkIsDroppedAndAGroupMentionSendsTheGroupsName() = runTest {
        val c = core()
        server.reply = MessageTriageCodec.Response("answered", lane = "needs_reply", summary = "Tickets", draft = "Pay here: www.evil.example.com")
        val read = assertIs<TriageRead.Read>(c.triageMessage(msg("Femi", "@Meka tickets are out, you in?", group = "Barça lads")))
        assertEquals("Barça lads", server.sent.single().group)
        assertNull(read.card!!.draft)
        assertEquals("Femi in Barça lads · 14:02", read.card!!.from)
    }

    @Test
    fun groupChatterStaysOnThePhoneWithNoAi() = runTest {
        val c = core()
        assertEquals(TriageRead.Digest("barça lads"), c.triageMessage(msg("Femi", "lineup for Getafe?", group = "Barça lads")))
        val quiet = TriageSettings(groupModes = mapOf("Barça lads" to GroupMode.IGNORE))
        assertIs<TriageRead.Skipped>(c.triageMessage(msg("Femi", "@Meka lineup?", group = "Barça lads"), quiet))
        assertTrue(server.sent.isEmpty())
        assertTrue(c.triage.value.isEmpty())
    }

    @Test
    fun anActionBecomesARequestCardQuotingTheGistNotTheMessage() = runTest {
        val c = core()
        server.reply = MessageTriageCodec.Response(
            "answered", lane = "action", summary = "Asks you to get milk",
            proposals = listOf(MessageRequestCodec.Proposal("task", "get milk", date = "2026-10-10", words = "tomorrow")),
        )
        val read = assertIs<TriageRead.Read>(c.triageMessage(msg("Ada", "can you get milk tomorrow? code 4471")))
        assertNull(read.card)
        val request = read.requests.single()
        assertEquals("Add task: Get milk · Tomorrow", request.action)
        assertEquals("“Asks you to get milk”", request.quote)
        assertEquals(listOf(request), c.requests.value)
        assertTrue(c.triage.value.isEmpty())
    }

    @Test
    fun voiceNotesAndPrivatePeopleNeverReachTheAi() = runTest {
        val c = core()
        val voice = assertIs<TriageRead.Read>(c.triageMessage(msg("Tunde", "🎤 Voice message (0:12)", kind = CaptureKind.VOICE_MESSAGE)))
        assertEquals("Listen to Tunde's voice note", voice.requests.single().proposal.title)
        val private = TriageSettings(neverToAi = setOf("Mum"))
        val mum = assertIs<TriageRead.Read>(c.triageMessage(msg("Mum", "call me about the doctor"), private))
        assertEquals(TriageCard.LOCAL_LINE, mum.card!!.gist)
        assertTrue(server.sent.isEmpty())
    }

    @Test
    fun offlineNothingIsStoredSoTheNextRepostTriesAgain() = runTest {
        val c = core()
        server.down = true
        val item = msg("Tunde", "are you coming Saturday?")
        assertIs<TriageRead.Unavailable>(c.triageMessage(item))
        server.down = false
        server.reply = MessageTriageCodec.Response("answered", lane = "fyi", summary = "Saturday plans")
        assertEquals("Saturday plans", assertIs<TriageRead.Read>(c.triageMessage(item)).card!!.gist)
        assertIs<TriageRead.Unavailable>(core(transport = null).triageMessage(item))
    }
}
