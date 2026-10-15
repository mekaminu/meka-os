package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailDraft
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
    fun theChaseIsWrittenFromWhatTheRowKnowsAndNothingIsSent() = runTest {
        val sentDay = os.meka.core.domain.CivilDate.toEpochDay(2026, 10, 12)
        val fu = os.meka.core.domain.MailFollowUpRules.read(
            os.meka.core.domain.MailSentFacts("Sam Taylor", "sam@example.com", 1, "Quote", "Could you send the quote?", now - 5 * 86_400_000L),
            setOf("alex@example.com"), false,
        )!!
        val fid = os.meka.core.domain.MailFollowUpRules.findId("acc1", "s1")
        os.meka.core.domain.MailFollowUpRules.serverFields(fu, "Business", "Quote for the website", sentDay).forEach { (f, v) ->
            serverOps.append(Op("srvmd${k++}", "hh", EntityTypes.MAIL_FIND, fid, f, v, clock.now(), emptyList(), "server"))
        }
        val fold = core()
        assertTrue(fold.syncNow())
        fold.setAboutYou(os.meka.core.domain.AboutField.SHOWN_NAME, "Alex")
        fold.addMailFind(fid)
        val row = fold.mailChases.value!!.rows.single()
        assertTrue(os.meka.core.domain.MailChaseDraftRules.autoDraft(row))
        server.reply = MailDraftCodec.Response("answered", "Hi Sam,\n\nJust checking in on the website quote — any news?\n\nThanks,\nAlex")
        assertTrue(fold.syncNow())
        val opsBefore = serverOps.after("hh", 0, 100_000).size

        val d = fold.draftMailChase(fid)
        assertEquals("Hi Sam,\n\nJust checking in on the website quote — any news?\n\nThanks,\nAlex", d.text)
        assertEquals(os.meka.core.domain.MailChaseDraftRules.DRAFTED_LINE, d.line)
        val asked = server.sent.single()
        assertEquals(MailDraftCodec.CHASE_FOLLOW_UP, asked.chase)
        assertEquals("Sam Taylor", asked.from)
        assertEquals("Quote for the website", asked.subject)
        assertEquals("Business", asked.account)
        assertEquals(row.business, asked.business)
        assertEquals(row.title, asked.what)
        assertEquals("Mon 12 Oct", asked.since)
        assertEquals("Alex", asked.sign)
        assertNull(asked.preview)
        // Nothing written or sent by drafting: the row is still there and the server has no new op.
        assertTrue(fold.syncNow())
        assertEquals(opsBefore, serverOps.after("hh", 0, 100_000).size)
        assertEquals(listOf(fid), fold.mailChases.value!!.rows.map { it.id })

        // An email he received, on Waiting for and due: chased in reply to their email, with its preview.
        val a = email("m1", "Jo Bloggs", "Signed contract")
        assertTrue(fold.syncNow())
        fold.waitingFromMailItem(a)
        now += 4 * 24 * hour
        assertTrue(fold.syncNow())
        fold.tick()
        assertTrue(fold.mailChases.value!!.rows.any { it.id == a })
        fold.draftMailChase(a)
        val waiting = server.sent.last()
        assertEquals(MailDraftCodec.CHASE_WAITING, waiting.chase)
        assertEquals("Jo Bloggs", waiting.from)
        assertEquals("Signed contract", waiting.subject)
        assertEquals("Are you still OK to drive on Saturday?", waiting.preview)
        assertEquals("Sat 17 Oct", waiting.since)

        // A usable answer only: a link drops it whole and the plain chase stays; AI off and offline say why.
        server.reply = MailDraftCodec.Response("answered", "Hi Sam, see https://example.com")
        fold.draftMailChase(fid).let { assertNull(it.text); assertEquals(os.meka.core.domain.MailChaseDraftRules.UNUSABLE_LINE, it.line) }
        server.reply = MailDraftCodec.Response("off", reason = "Navilife's AI is off")
        assertNull(fold.draftMailChase(fid).text)
        server.down = true
        fold.draftMailChase(fid).let { assertNull(it.text); assertEquals(AskRules.OFFLINE_LINE, it.line) }
        assertEquals(os.meka.core.domain.MailChaseRules.UNKNOWN, fold.draftMailChase("nothing").line)
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

    @Test
    fun sendAllSendsEachFromItsOwnAccountAfterTheHoldAndOneUndoStopsThemAll() = runTest {
        val a = email("m1", "Sam Taylor", "Saturday's game", "Business")
        val b = email("m2", "Alex Reed", "Dinner")
        val c = email("m3", "Pat Lee", "Kit")
        val fold = core()
        assertTrue(fold.syncNow())
        val card = fold.mailNeeds.value!!
        val all = os.meka.core.domain.MailSendAllRules.sendAll(card.rows, mapOf(a to "Yes, I'll drive.", b to "Sounds good, 7 it is."))!!
        assertEquals(os.meka.core.domain.MailSendAllRules.sendingLine(2), fold.replyToMailItems(all.ids, all.texts))
        // Both leave the card, the third stays; each carries its own words and is held for Undo's window.
        assertEquals(listOf(c), fold.mailNeeds.value?.rows?.map { it.id })
        assertTrue(fold.syncNow())
        val mac = MekaCore(
            householdId = "hh", deviceId = "mac", store = InMemoryReplicaStore(), transport = server,
            secureRandom = Random(2), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        assertTrue(mac.syncNow())
        assertEquals(listOf(c), mac.mailNeeds.value!!.rows.map { it.id })
        // One Undo brings both back.
        now += 1_000
        fold.undoMailItem()
        assertEquals(setOf(a, b, c), fold.mailNeeds.value?.rows?.map { it.id }?.toSet())
        // A blank reply is never sent; with nothing sendable, nothing is written.
        assertEquals(MailNeedsRules.REPLY_EMPTY, fold.replyToMailItems(listOf(a), listOf("  ")))
        assertEquals(MailNeedsRules.UNKNOWN, fold.replyToMailItems(listOf("nope"), listOf("Hi")))
        assertEquals(3, fold.mailNeeds.value?.rows?.size)
        now += 1_000
        // One that can go and one that can't: the good one goes, with the one-reply line from its own account.
        assertEquals(MailNeedsRules.replySendingLine("Business"), fold.replyToMailItems(listOf(a, b), listOf("Yes.", "")))
        assertEquals(setOf(b, c), fold.mailNeeds.value?.rows?.map { it.id }?.toSet())
        assertTrue(server.sent.isEmpty())
    }

    private data class MailDraft(val text: String?, val line: String)
}
