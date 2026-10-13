package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailDraftRules
import os.meka.core.domain.MailNeedsRules
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.MailDraftCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox autopilot, slice 6b-2b: Draft it through the facade — one call, his tone, checked, nothing written or sent. */
class MailDraftFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val serverOps = InMemoryServerOpStore()

    private inner class Server : SyncTransport, AiApi {
        private val sync = os.meka.core.testing.FaultyTransport(SyncService(serverOps))
        val sent = mutableListOf<MailDraftCodec.Request>()
        var reply = MailDraftCodec.Response("answered", "Hi Sam,\n\nYes, I can drive on Saturday.\n\nM")
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun mailDraft(request: MailDraftCodec.Request): MailDraftCodec.Response {
            sent += request
            if (down) throw TransportException("offline")
            return reply
        }
    }

    private val server = Server()
    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0
    private fun serverWrite(id: String, fields: Map<String, FieldValue>) = fields.forEach { (f, v) ->
        serverOps.append(Op("srvmd${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
    }

    private fun email(message: String, from: String, subject: String, account: String = "Personal"): String {
        val id = MailNeedsRules.itemId("acc1", message)
        serverWrite(id, MailNeedsRules.serverFields(account, from, "sam@example.com", subject, "Are you still OK to drive on Saturday?", now - hour, 1))
        return id
    }

    @Test
    fun draftItSendsTheCardsWordsAndHisEarlierRepliesAndWritesNothing() = runTest {
        val old = email("m0", "Alex Reed", "Dinner")
        val a = email("m1", "Sam Taylor", "Saturday's game", "Business")
        val fold = core()
        assertTrue(fold.syncNow())
        // An earlier reply he sent becomes a tone example once the server says it went.
        fold.replyToMailItem(old, "Sounds good, see you at 7.")
        assertTrue(fold.syncNow())
        now += 60_000
        serverWrite(old, MailNeedsRules.replySentFields(now))
        assertTrue(fold.syncNow())

        val draft = fold.draftMailReply(a)
        assertEquals("Hi Sam,\n\nYes, I can drive on Saturday.\n\nM", draft.text)
        assertEquals(MailDraftRules.DRAFTED_LINE, draft.line)
        val sent = server.sent.single()
        assertEquals("Sam Taylor", sent.from)
        assertEquals("Saturday's game", sent.subject)
        assertEquals("Business", sent.account)
        assertTrue(sent.business)
        assertEquals("Are you still OK to drive on Saturday?", sent.preview)
        assertEquals(listOf("Sounds good, see you at 7."), sent.examples)
        assertEquals("2026-10-17", sent.date)
        // Never the sender's address.
        assertTrue("sam@example.com" !in MailDraftCodec.encodeRequest(sent))
        // Nothing written, nothing sent: the card is still there and the phone wrote nothing on that email.
        assertTrue(fold.syncNow())
        assertTrue(serverOps.after("hh", 0, 100_000).map { it.op }.none { it.entityId == a && it.deviceId != "server" })
        assertEquals(listOf(a), fold.mailNeeds.value?.rows?.map { it.id })
    }

    @Test
    fun anUnsafeDraftOfflineAndAiOffGiveNoText() = runTest {
        val a = email("m1", "Sam Taylor", "Saturday's game")
        val fold = core()
        assertTrue(fold.syncNow())
        server.reply = MailDraftCodec.Response("answered", "Pay here: https://example.com/pay")
        assertEquals(MailDraft(null, MailDraftRules.UNUSABLE_LINE), fold.draftMailReply(a).let { MailDraft(it.text, it.line) })
        server.reply = MailDraftCodec.Response("off", reason = "Navilife's AI is off")
        val off = fold.draftMailReply(a)
        assertNull(off.text)
        assertEquals("Navilife's AI is off", off.line)
        server.down = true
        assertEquals(AskRules.OFFLINE_LINE, fold.draftMailReply(a).line)
        assertEquals(MailNeedsRules.UNKNOWN, fold.draftMailReply("nope").line)
        assertEquals(3, server.sent.size)
    }

    private data class MailDraft(val text: String?, val line: String)
}
