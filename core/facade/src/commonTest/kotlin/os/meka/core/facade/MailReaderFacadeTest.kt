package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailNeedsRules
import os.meka.core.domain.MailReaderRules
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
import os.meka.core.wire.MailMessageCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Read the full email inside Navilife, slice 1: the card's handle asked for, the answer shown, the preview kept offline. */
class MailReaderFacadeTest {
    private var now = 1_792_225_800_000L // Sat 17 Oct 2026, 09:30 in London
    private val hour = 3_600_000L
    private val serverOps = InMemoryServerOpStore()

    private inner class Server : SyncTransport, AiApi, AccountsApi {
        override suspend fun startConnect(provider: String, editing: Boolean): ConnectStart = ConnectStart.NotSetUp
        override suspend fun accounts(): List<ConnectedAccount> = emptyList()
        override suspend fun stopEditing(provider: String, email: String): List<ConnectedAccount> = emptyList()
        private val sync = os.meka.core.testing.FaultyTransport(SyncService(serverOps))
        val asked = mutableListOf<MailMessageCodec.Request>()
        var down = false
        var html = "<p>Are you still OK to drive?</p>"
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun mailMessage(request: MailMessageCodec.Request): MailMessageCodec.Response {
            asked += request
            if (down) throw TransportException("offline")
            return MailMessageCodec.Response(
                "ok", MailMessageCodec.Person("Sam Taylor", "sam@example.com"), listOf(MailMessageCodec.Person("Alex", "alex@example.com")),
                atMs = now - hour, subject = "Saturday", html = html,
                attachments = listOf(
                    MailMessageCodec.Attachment("route.pdf", 52_000, "application/pdf"),
                    MailMessageCodec.Attachment("menu.docx", 9_000, null),
                    MailMessageCodec.Attachment("setup.exe", 9_000, null),
                ),
                blockedImages = if (request.images) 0 else 1,
            )
        }
        var accountsAsked = 0
        override suspend fun mailAccounts(): List<os.meka.core.domain.MailAccountFacts> {
            accountsAsked++
            return listOf(os.meka.core.domain.MailAccountFacts(
                os.meka.core.domain.MailProviders.GMAIL, "alex@example.com", "ok", null, os.meka.core.domain.MailLabel.PERSONAL.name,
            ))
        }
        val files = mutableListOf<MailMessageCodec.AttachmentRequest>()
        override suspend fun mailAttachment(request: MailMessageCodec.AttachmentRequest): MailMessageCodec.AttachmentFetch {
            files += request
            if (down) throw TransportException("offline")
            return MailMessageCodec.AttachmentFetch("ok", if (request.index == 0) "%PDF-1.4 x".encodeToByteArray() else "PK\u0003\u0004".encodeToByteArray())
        }
    }

    private val server = Server()
    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val clock = HlcClock("server", { now })
    private var k = 0

    @Test
    fun aCardOpensTheWholeEmailAndShowsThePreviewWhenOffline() = runTest {
        val id = MailNeedsRules.itemId("acc1", "m1")
        MailNeedsRules.serverFields("Personal", "Sam Taylor", "sam@example.com", "Saturday", "Are you still OK…", now - hour, 1).forEach { (f, v) ->
            serverOps.append(Op("srvmr${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
        }
        val fold = core()
        assertTrue(fold.syncNow())
        val before = serverOps.after("hh", 0, 100_000).size

        val v = fold.readMailItem(id)
        assertEquals(MailMessageCodec.Request(MailMessageCodec.Request.ITEM, id), server.asked.single())
        assertEquals("Sam Taylor", v.from)
        assertEquals("sam@example.com", v.fromAddress)
        assertEquals("To Alex <alex@example.com>", v.toLine)
        assertEquals("Are you still OK to drive?", v.text)
        assertNotNull(v.page)
        assertEquals("1 picture hidden to stop tracking", v.imagesLine)
        assertEquals(listOf("route.pdf", "menu.docx", "setup.exe"), v.attachments.map { it.name })
        assertNull(v.problem)
        // Show images asks again with pictures on.
        assertNull(fold.readMailItem(id, images = true).imagesLine)
        assertTrue(server.asked.last().images)
        // Reading writes nothing (it isn't marked read or done).
        assertTrue(fold.syncNow())
        assertEquals(before, serverOps.after("hh", 0, 100_000).size)

        server.down = true
        val off = fold.readMailItem(id)
        assertEquals(MailReaderRules.OFFLINE, off.problem)
        assertEquals("Are you still OK…", off.text)
        assertEquals("Personal", off.account)
        // A tidied email's handle is its tidy and key.
        server.down = false
        fold.readArchivedMail("mtb1", "te1")
        assertEquals(MailMessageCodec.Request(MailMessageCodec.Request.TIDIED, "te1", "mtb1"), server.asked.last())
        // Links: only real web and email links, each with where it really goes.
        assertEquals("Open example.org?", fold.mailLink("https://example.org/x")!!.title)
        assertNull(fold.mailLink("javascript:x()"))
    }

    @Test
    fun anAttachmentIsFetchedByTheEmailsHandleAndOpensWhereItShould() = runTest {
        val id = MailNeedsRules.itemId("acc1", "m2")
        val fold = core()
        val v = fold.readMailItem(id)
        val pdf = fold.openMailAttachment(v, 0)
        assertEquals(MailMessageCodec.AttachmentRequest(MailMessageCodec.Request.ITEM, id, null, 0, "route.pdf"), server.files.single())
        assertEquals(os.meka.core.domain.MailAttachmentRules.VIEW, pdf.state)
        assertTrue(pdf.viewer!!.isPdf)
        val doc = fold.openMailAttachment(v, 1)
        assertEquals(os.meka.core.domain.MailAttachmentRules.SYSTEM, doc.state)
        assertNotNull(doc.base64)
        // A file that could run something is never fetched.
        val exe = fold.openMailAttachment(v, 2)
        assertEquals(os.meka.core.domain.MailAttachmentRules.REFUSED_LINE, exe.line)
        assertEquals(2, server.files.size)
        // Offline: a line, nothing opened.
        server.down = true
        assertEquals(os.meka.core.domain.MailAttachmentRules.OFFLINE_LINE, fold.openMailAttachment(v, 0).line)
        // An email that couldn't be read has no attachments to open.
        val off = fold.readMailItem(id)
        assertNull(off.source)
        assertEquals(os.meka.core.domain.MailAttachmentRules.GONE_LINE, fold.openMailAttachment(off, 0).line)
    }

    @Test
    fun aCardsReaderOpensInGmailAndSaysWhetherOpeningMarkedItRead() = runTest {
        val id = MailNeedsRules.itemId("acc1", "m3")
        MailNeedsRules.serverFields("Personal", "Sam Taylor", "sam@example.com", "Saturday", "Are you still OK…", now - hour, 1).forEach { (f, v) ->
            serverOps.append(Op("srvmo${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server"))
        }
        val fold = core()
        assertTrue(fold.syncNow())
        val v = fold.readMailItem(id)
        val l = assertNotNull(v.openLink)
        assertEquals("Open in Gmail", l.label)
        assertTrue(l.url.contains("authuser=alex%40example.com"), l.url)
        assertTrue(l.url.contains("from%3Asam%40example.com"), l.url)
        assertTrue(v.note.endsWith(MailReaderRules.STAYS_UNREAD_NOTE))
        // The account list is asked for once, then kept for this run.
        fold.readMailItem(id)
        assertEquals(1, server.accountsAsked)
        // A tidied email has none of its own (Archived for you passes its own link).
        assertNull(fold.readArchivedMail("mtb1", "te1").openLink)
        // Turned on in Mail: the reader says opening it marked it read (the server does the marking).
        assertEquals(os.meka.core.domain.MailAutopilotPanelRules.readerReadSwitchedLine(true), fold.setMailReaderMarksRead(true).line)
        assertTrue(fold.readMailItem(id).note.endsWith(MailReaderRules.MARKS_READ_NOTE))
    }

    @Test
    fun aRenewalsCostComesFromTheWholeEmailWhenThePreviewHadNone() = runTest {
        // Renewals keep their email, slice 3: the preview named no price; the email does.
        val received = os.meka.core.domain.CivilDate.toEpochDay(2026, 10, 16)
        val r = os.meka.core.domain.MailRenewalRules.read("Streamly", null, "Your membership renews soon", "It renews on 3 Nov", received)!!
        assertNull(r.pence)
        val find = os.meka.core.domain.MailRenewalRules.findId("acc1", "m1", r)
        os.meka.core.domain.MailRenewalRules.serverFields(r, "Personal", "Your membership renews soon", received, "Streamly").forEach { (f, v) ->
            serverOps.append(Op("srvmr${k++}", "hh", EntityTypes.MAIL_FIND, find, f, v, clock.now(), emptyList(), "server"))
        }
        server.html = "<p>Hi Alex, save £5 on our shop this week.</p><p>Your plan renews on 3 Nov for £24.00 a year.</p>"
        val fold = core()
        assertTrue(fold.syncNow())
        fold.addMailFind(find)
        val item = os.meka.core.domain.MailRenewalRules.obligationId(find)
        fun cost() = fold.listsView.value.renewals.let { it.attention + it.upcoming + it.later }.single { it.id == item }.costPence
        // After the tap: the email is peeked at (never marked read) and its price becomes Cost.
        fold.fillCostAfterAdd(find, item)
        assertTrue(server.asked.any { it.kind == MailMessageCodec.Request.FIND && it.id == find && it.peek })
        assertEquals(2400L, cost())
        // Opening the email later (his own open, not a peek) never writes over a Cost already there.
        server.html = "<p>Your plan renews on 3 Nov for £30.00 a year.</p>"
        assertNull(fold.readRenewalEmail(item).problem)
        assertTrue(server.asked.any { it.id == find && !it.peek })
        assertEquals(2400L, cost())
    }
}
