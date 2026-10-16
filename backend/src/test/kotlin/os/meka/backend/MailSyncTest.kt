package os.meka.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import os.meka.backend.integrations.MailBulkHeaders
import os.meka.backend.integrations.WriteRefused
import os.meka.backend.integrations.Http
import os.meka.backend.integrations.mailWhy
import os.meka.core.domain.MailSortRules
import os.meka.backend.integrations.GmailHistory
import os.meka.backend.integrations.GmailMail
import os.meka.backend.integrations.GraphDelta
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.InMemoryMailStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.MailAddress
import os.meka.backend.integrations.MailAiSorter
import os.meka.backend.integrations.MailDrafter
import os.meka.backend.integrations.MailChanges
import os.meka.backend.integrations.MailCursorExpired
import os.meka.backend.integrations.MailHeader
import os.meka.backend.integrations.MailProvider
import os.meka.backend.integrations.MailReplyMessage
import os.meka.backend.integrations.MailReplyResult
import os.meka.backend.integrations.MailSent
import os.meka.backend.integrations.sent
import os.meka.backend.integrations.MailStore
import os.meka.backend.integrations.MailboxCheck
import os.meka.backend.integrations.OAuthClient
import os.meka.backend.integrations.PostgresIntegrationStore
import os.meka.backend.integrations.PostgresMailStore
import os.meka.backend.integrations.TokenCipher
import os.meka.backend.integrations.TokenSet
import os.meka.core.domain.MailSort
import os.meka.core.domain.MailSortReason
import os.meka.core.domain.MailSorted
import os.meka.core.domain.AutonomyFields
import os.meka.core.domain.AboutYouFields
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.AutonomyRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailAutopilotFields
import os.meka.core.domain.MailAutopilotRules
import os.meka.core.domain.MailCheckRules
import os.meka.core.domain.MailTidy
import os.meka.core.domain.MailReturnRules
import os.meka.core.domain.MailBillRules
import os.meka.core.domain.MailRenewalRules
import os.meka.core.domain.ParcelFields
import os.meka.core.domain.ParcelRules
import os.meka.core.domain.ParcelState
import os.meka.core.domain.MailWarrantyRules
import os.meka.core.domain.MailWarrantyFields
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.ActionableFields
import os.meka.core.domain.ObligationFields
import os.meka.core.domain.MailAccountRules
import os.meka.core.domain.MailTidyBatchFields
import os.meka.backend.integrations.EntityReader
import os.meka.backend.integrations.MailMoved
import os.meka.backend.integrations.MailMovePartial
import os.meka.backend.integrations.MailBacklogPage
import os.meka.backend.integrations.BacklogScan
import os.meka.backend.integrations.BacklogItem
import os.meka.core.domain.MailBacklogKind
import os.meka.core.domain.MailBacklogRules
import os.meka.core.domain.MailBacklogFields
import os.meka.core.domain.MailJunkClearRules
import os.meka.backend.integrations.HttpOneClickUnsubscriber
import os.meka.backend.integrations.OneClickUnsubscriber
import os.meka.backend.integrations.UnsubscribeMessage
import os.meka.core.domain.MailSenderFields
import os.meka.core.domain.MailSenderRuleRules
import os.meka.core.domain.MailSenderRule
import os.meka.core.domain.MailItemFields
import os.meka.core.domain.MailNeedsRules
import os.meka.core.domain.MailChaseRules
import os.meka.core.domain.MailProviders
import os.meka.core.domain.MailUnsubscribeRules
import os.meka.core.domain.UnsubscribeMailto
import os.meka.core.domain.UnsubscribeMethod
import os.meka.core.domain.UnsubscribeState
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox autopilot, slice 1c: each mail account's headers kept current from the provider's change feed; slice 2a: sorted. */
class MailSyncTest {
    private var now = 1_790_985_600_000L
    private val day = 86_400_000L

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    // ---- Reading the providers' answers ----

    @Test
    fun gmailMetadataBecomesAHeaderAndSentMailIsLeftAlone() {
        val h = GmailHistory.header(json("""
            {"id":"m1","threadId":"t1","labelIds":["INBOX","UNREAD","CATEGORY_PROMOTIONS"],"internalDate":"1790985600000",
             "payload":{"headers":[
               {"name":"From","value":"\"Oakfield Shop\" <News@Oakfield.example>"},
               {"name":"Subject","value":"Autumn sale"},
               {"name":"List-Unsubscribe","value":"<mailto:u@oakfield.example>, <https://oakfield.example/u?x=1>"},
               {"name":"List-Unsubscribe-Post","value":"List-Unsubscribe=One-Click"},
               {"name":"List-ID","value":"<news.oakfield.example>"}]}}
        """))!!
        assertEquals("m1", h.id)
        assertEquals("t1", h.threadId)
        assertEquals("news@oakfield.example", h.fromAddress)
        assertEquals("Oakfield Shop", h.fromName)
        assertEquals("Autumn sale", h.subject)
        assertEquals(1_790_985_600_000L, h.receivedAtMs)
        assertTrue(h.unread && h.inInbox)
        assertTrue(h.oneClickUnsubscribe)
        assertEquals("<news.oakfield.example>", h.listId)
        assertTrue(h.listUnsubscribe!!.startsWith("<mailto:"))
        assertEquals(listOf("INBOX", "UNREAD", "CATEGORY_PROMOTIONS"), h.labels)
        // Sent mail and drafts aren't MEKA's to sort.
        assertNull(GmailHistory.header(json("""{"id":"m2","labelIds":["SENT"],"internalDate":"1","payload":{"headers":[]}}""")))
        assertNull(GmailHistory.header(json("""{"id":"m3","labelIds":["DRAFT"],"internalDate":"1","payload":{"headers":[]}}""")))
        // Archived mail is kept, out of the inbox and read.
        val archived = GmailHistory.header(json("""{"id":"m4","labelIds":["CATEGORY_UPDATES"],"internalDate":"5","payload":{"headers":[{"name":"from","value":"sam@example.com"}]}}"""))!!
        assertFalse(archived.inInbox || archived.unread || archived.oneClickUnsubscribe)
        assertEquals("sam@example.com", archived.fromAddress)
        assertNull(archived.fromName)
    }

    @Test
    fun gmailHistorySaysWhatToReadAgainAndWhatWent() {
        val r = GmailHistory.read(json("""
            {"history":[
              {"id":"10","messagesAdded":[{"message":{"id":"a"}}]},
              {"id":"11","labelsAdded":[{"message":{"id":"b"},"labelIds":["UNREAD"]}],"labelsRemoved":[{"message":{"id":"a"},"labelIds":["INBOX"]}]},
              {"id":"12","messagesDeleted":[{"message":{"id":"c"}}]}],
             "historyId":"12"}
        """))
        assertEquals(listOf("a", "b"), r.touched)
        assertEquals(listOf("c"), r.removed)
        assertEquals(GmailHistory.Read(emptyList(), emptyList()), GmailHistory.read(json("""{"historyId":"9"}""")))
    }

    @Test
    fun graphsDeltaGivesWholeHeadersAndRemovals() {
        val p = GraphDelta.read(json("""
            {"value":[
              {"id":"o1","conversationId":"c1","subject":"Your order","isRead":false,"receivedDateTime":"2026-10-03T08:00:00Z",
               "from":{"emailAddress":{"name":"Oakfield Store","address":"Orders@Oakfield.example"}}},
              {"id":"o2","isRead":true,"receivedDateTime":"2026-10-03T09:00:00Z","from":{"emailAddress":{"name":"","address":"sam@example.com"}}},
              {"id":"o3","@removed":{"reason":"deleted"}}]}
        """))
        assertEquals(listOf("o1", "o2"), p.headers.map { it.id })
        assertEquals(listOf("o3"), p.removed)
        val first = p.headers.first()
        assertEquals("orders@oakfield.example", first.fromAddress)
        assertEquals("Oakfield Store", first.fromName)
        assertEquals("c1", first.threadId)
        assertTrue(first.unread && first.inInbox)
        assertEquals(java.time.Instant.parse("2026-10-03T08:00:00Z").toEpochMilli(), first.receivedAtMs)
        assertFalse(p.headers[1].unread)
        assertNull(p.headers[1].fromName)
    }

    @Test
    fun sentMailReadsItsFirstRecipientAndHowMany() {
        val g = GmailHistory.sent(json("""
            {"id":"s1","threadId":"t1","labelIds":["SENT"],"internalDate":"1790985600000","snippet":"Could you send the quote?",
             "payload":{"headers":[{"name":"To","value":"\"Taylor, Sam\" <Sam@Example.com>, ann@example.com"},
               {"name":"Cc","value":"Bo <bo@example.com>"},{"name":"Subject","value":"Quote"}]}}
        """))!!
        assertEquals("sam@example.com", g.toAddress)
        assertEquals("Taylor, Sam", g.toName)
        assertEquals(3, g.recipients)
        assertEquals("t1", g.threadId)
        assertEquals(1_790_985_600_000L, g.sentAtMs)
        assertNull(GmailHistory.sent(json("""{"id":"d1","labelIds":["SENT","DRAFT"],"internalDate":"1","payload":{"headers":[]}}""")))
        assertNull(GmailHistory.sent(json("""{"id":"i1","labelIds":["INBOX"],"internalDate":"1","payload":{"headers":[]}}""")))
        val o = GraphDelta.sent(json("""
            {"value":[{"id":"o1","conversationId":"c1","subject":"Dates","sentDateTime":"2026-10-03T08:00:00Z","bodyPreview":"Does Friday work?",
              "toRecipients":[{"emailAddress":{"name":"Ann Lee","address":"Ann@Example.com"}}],"ccRecipients":[]}]}
        """)).single()
        assertEquals("ann@example.com", o.toAddress)
        assertEquals("Ann Lee", o.toName)
        assertEquals(1, o.recipients)
        assertEquals("c1", o.threadId)
        assertEquals(listOf(null to "a@example.com", "B" to "b@example.com"), MailAddress.parseList("a@example.com, B <b@example.com>, nobody"))
    }

    @Test
    fun fromHeadersParse() {
        assertEquals("Alex Smith" to "alex@example.com", MailAddress.parse("Alex Smith <Alex@Example.com>"))
        assertEquals("Smith, Alex" to "alex@example.com", MailAddress.parse("\"Smith, Alex\" <alex@example.com>"))
        assertEquals(null to "alex@example.com", MailAddress.parse("alex@example.com"))
        assertEquals(null to "alex@example.com", MailAddress.parse("<alex@example.com>"))
        assertEquals(null to null, MailAddress.parse(""))
        assertEquals(null to null, MailAddress.parse(null))
        assertEquals("Not an address" to null, MailAddress.parse("Not an address <nobody>"))
    }

    // ---- The server's sync ----

    /** A Gmail-like provider: ids from the feed, headers read one at a time. */
    private class FakeGmail(override val id: String = "gmail") : MailProvider {
        override val oauth = "google"
        override val requiredScopes = listOf(GmailMail.MODIFY, GmailMail.SEND)
        val mailbox = linkedMapOf<String, MailHeader>()
        var nextRound: (String?) -> MailChanges = { c -> MailChanges(fetch = mailbox.keys.toList(), cursor = "h1") }
        val cursorsAsked = mutableListOf<String?>()
        val headersRead = mutableListOf<String>()
        var sinceAsked = 0L
        override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String) = "https://a.example/?state=$state"
        override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String) =
            TokenSet("access", "refresh", 3600, "openid email ${GmailMail.MODIFY} ${GmailMail.SEND}")
        override fun refresh(client: OAuthClient, refreshToken: String) = TokenSet("access", null, 3600)
        override fun accountEmail(accessToken: String) = "alex@example.com"
        /** Inbox fixes, slice 7: the inbox's unread count the provider reports (null: none, so nothing is written). */
        var unreadInInbox: Long? = null
        override fun inbox(accessToken: String) = MailboxCheck(mailbox.size.toLong(), unreadInInbox)
        override fun changes(accessToken: String, cursor: String?, sinceMs: Long): MailChanges {
            cursorsAsked += cursor; sinceAsked = sinceMs
            return nextRound(cursor)
        }
        override fun header(accessToken: String, id: String): MailHeader? {
            headerFailsAfter?.let { if (headersRead.size >= it) throw WriteRefused("provider refused the change") }
            headersRead += id; return mailbox[id]
        }
        // Follow-up radar: what he sent.
        val sentMail = mutableListOf<MailSent>()
        var sentReads = 0
        override fun sent(accessToken: String, sinceMs: Long, limit: Int): List<MailSent> {
            sentReads++
            return sentMail.filter { it.sentAtMs >= sinceMs }.sortedByDescending { it.sentAtMs }.take(limit)
        }

        // Slice 3b: Autopilot's moves on the fake mailbox.
        val sentThreads = mutableSetOf<String>()
        var repliedFails = false
        val tidyCalls = mutableListOf<Map<String, MailTidy>>()
        override fun repliedThreads(accessToken: String, sinceMs: Long): Set<String> {
            if (repliedFails) error("Gmail 503")
            return sentThreads
        }
        // Completeness: the provider fails (a 429) after moving this many, as GmailMail reports it (MailMovePartial).
        var tidyFailsAfter: Int? = null
        var untidyFailsAfter: Int? = null
        private fun cut(items: Map<String, MailTidy>, after: Int?, move: (Map<String, MailTidy>) -> Map<String, String>): Map<String, String> {
            if (after == null) return move(items)
            val done = move(items.entries.take(after).associate { it.key to it.value })
            val cause = IllegalStateException("provider HTTP 429")
            if (done.isEmpty()) throw cause
            throw MailMovePartial(done, cause)
        }
        override fun tidy(accessToken: String, items: Map<String, MailTidy>): Map<String, String> {
            tidyCalls += items
            return cut(items, tidyFailsAfter, this::moveOut)
        }
        private fun moveOut(items: Map<String, MailTidy>): Map<String, String> {
            // Slice 3c: the Junk review's moves out of Junk.
            val fromJunk = items.filterKeys { it in junkBox }.mapValues { (id, t) ->
                val h = junkBox.remove(id)!!
                if (t == MailTidy.RESCUE) mailbox[id] = h.copy(inInbox = true, labels = h.labels - "SPAM" + "INBOX" + "STARRED")
                else trashBox[id] = h
                id
            }
            return fromJunk + items.filterKeys { it in mailbox && it !in fromJunk }.mapValues { (id, t) ->
                val h = mailbox.getValue(id)
                mailbox[id] = h.copy(inInbox = false, labels = h.labels - "INBOX" + listOfNotNull(t.label))
                id
            }
        }
        override fun untidy(accessToken: String, items: Map<String, MailTidy>): Map<String, String> = cut(items, untidyFailsAfter, this::moveBack)
        private fun moveBack(items: Map<String, MailTidy>): Map<String, String> =
            items.filter { (_, t) -> t == MailTidy.JUNK_TRASH || t == MailTidy.RESCUE }.mapNotNull { (id, _) ->
                val h = trashBox.remove(id) ?: mailbox.remove(id) ?: return@mapNotNull null
                junkBox[id] = h.copy(inInbox = false)
                id to id
            }.toMap() + items.filter { (id, t) -> id in mailbox && t != MailTidy.JUNK_TRASH && t != MailTidy.RESCUE }.mapValues { (id, t) ->
                val h = mailbox.getValue(id)
                mailbox[id] = h.copy(inInbox = true, labels = h.labels.filter { it != t.label } + "INBOX")
                id
            }

        // Slice 5a: unsubscribe emails sent, and (Outlook) the headers read on demand.
        val sent = mutableListOf<UnsubscribeMailto>()
        var sendFails = false
        val outlookHeaders = mutableMapOf<String, Pair<String?, Boolean>>()
        val headerReads = mutableListOf<String>()
        override fun sendUnsubscribe(accessToken: String, mail: UnsubscribeMailto): Boolean {
            if (sendFails) error("Gmail 503")
            sent += mail; return true
        }
        override fun listHeaders(accessToken: String, id: String): Pair<String?, Boolean>? { headerReads += id; return outlookHeaders[id] }

        // Inbox fixes, slice 1: Outlook's bulk headers read on demand, header reads that fail, and read marks.
        val bulk = mutableMapOf<String, MailBulkHeaders>()
        val bulkReads = mutableListOf<String>()
        var bulkFailsAfter: Int? = null
        override fun bulkHeaders(accessToken: String, id: String): MailBulkHeaders? {
            bulkFailsAfter?.let { if (bulkReads.size >= it) throw IllegalStateException("provider HTTP 429") }
            bulkReads += id; return bulk[id]
        }
        var headerFailsAfter: Int? = null
        val marked = mutableListOf<Pair<Set<String>, Boolean>>()
        override fun markRead(accessToken: String, ids: Collection<String>, read: Boolean) { marked += ids.toSet() to read }
        /** Inbox fixes, slice 8: "Mark N read" (the provider accepts all, or refuses past [markReadRefusedAfter]). */
        val markedDone = mutableListOf<Set<String>>()
        var markReadRefusedAfter: Int? = null
        override fun markReadDone(accessToken: String, ids: Collection<String>): Set<String> {
            val ok = markReadRefusedAfter?.let { ids.take(it) } ?: ids
            ok.forEach { id -> mailbox[id]?.let { mailbox[id] = it.copy(unread = false) } }
            return ok.toSet().also { markedDone += it }
        }

        // Slice 8a: the backlog's pages (by page token; null: can't list), and what was asked.
        var backlogPages: Map<String?, MailBacklogPage>? = null
        val backlogAsked = mutableListOf<Pair<Long, String?>>()
        override fun backlog(accessToken: String, beforeMs: Long, page: String?): MailBacklogPage? {
            val pages = backlogPages ?: return null
            backlogAsked += beforeMs to page
            return pages[page]
        }

        // Slice 3c: the Junk folder, what went to Trash, and the reviews asked.
        val junkBox = linkedMapOf<String, MailHeader>()
        val trashBox = linkedMapOf<String, MailHeader>()
        var junkAsked = 0
        override fun junk(accessToken: String, sinceMs: Long, limit: Int): List<MailHeader> {
            junkAsked++
            return junkBox.values.filter { it.receivedAtMs >= sinceMs }.take(limit)
        }
        // Inbox fixes, slice 3: "Empty Junk": the folder's count (null: can't say) and its older mail.
        var junkCountAnswer: Int? = null
        var junkCounts = 0
        override fun junkCount(accessToken: String): Int? { junkCounts++; return junkCountAnswer }
        val junkOlderAsked = mutableListOf<Pair<Long, Int>>()
        override fun junkOlder(accessToken: String, beforeMs: Long, limit: Int): List<MailHeader> {
            junkOlderAsked += beforeMs to limit
            return junkBox.values.filter { it.receivedAtMs < beforeMs }.sortedByDescending { it.receivedAtMs }.take(limit)
        }

        // Slice 6b-2a: replies sent.
        val replies = mutableListOf<Pair<String, String>>()
        var replyAnswer: () -> MailReplyResult = { MailReplyResult.SENT }
        override fun reply(accessToken: String, id: String, text: String): MailReplyResult {
            val r = replyAnswer()
            if (r == MailReplyResult.SENT) replies += id to text
            return r
        }

        // Follow-up radar, slice 2: chases of his sent emails.
        val chases = mutableListOf<Pair<String, String>>()
        var chaseAnswer: () -> MailReplyResult = { MailReplyResult.SENT }
        override fun chaseSent(accessToken: String, id: String, text: String): MailReplyResult {
            val r = chaseAnswer()
            if (r == MailReplyResult.SENT) chases += id to text
            return r
        }
    }

    private class Cipher : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain.reversedArray()
        override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher.reversedArray()
    }

    private fun msg(id: String, ageDays: Long, from: String = "sam@example.com") =
        MailHeader(id, "t-$id", from, null, "Subject $id", now - ageDays * day, unread = true, inInbox = true)

    private fun world(
        mailStore: MailStore, gmail: FakeGmail = FakeGmail(), store: InMemoryIntegrationStore = InMemoryIntegrationStore(),
        mailAi: MailAiSorter? = null, ops: InMemoryServerOpStore = InMemoryServerOpStore(),
        unsubscriber: OneClickUnsubscriber = OneClickUnsubscriber.NONE, mailDrafter: MailDrafter? = null,
    ) =
        Triple(gmail, store, Integrations(
            store, ops, emptyMap(), { p -> if (p == "google") OAuthClient("cid", "s") else null },
            Cipher(), "https://meka.example", { now }, mail = mapOf("gmail" to gmail), mailStore = mailStore, mailAi = mailAi,
            unsubscriber = unsubscriber, mailDrafter = mailDrafter,
        ))

    private fun connect(i: Integrations): String {
        val url = assertIs<Integrations.StartResult.Url>(i.start("home", "gmail")).url
        val state = URI(url).query.split("&").first { it.startsWith("state=") }.removePrefix("state=")
        return assertIs<Integrations.CallbackResult.Connected>(i.callback("google", state, "code", null)).accountId
    }

    @Test
    fun theFirstCheckListsSixtyDaysThenReadsHeadersAFewHundredAtATime() {
        val ms = InMemoryMailStore()
        val (gmail, _, i) = world(ms)
        repeat(Integrations.HEADERS_PER_CHECK + 20) { n -> gmail.mailbox["m$n"] = msg("m$n", 1) }
        val id = connect(i)
        i.syncAccount(id)
        assertEquals(listOf<String?>(null), gmail.cursorsAsked)
        assertEquals(now - Integrations.MAIL_WINDOW_MS, gmail.sinceAsked)
        assertEquals(Integrations.HEADERS_PER_CHECK, ms.messages(id).size)
        assertEquals(20, ms.pendingCount(id))
        assertEquals("h1", ms.cursor(id))
        // The next check continues from the cursor and reads the rest.
        gmail.nextRound = { c -> MailChanges(cursor = "h2") }
        now += 300_000
        i.syncAccount(id)
        assertEquals(listOf(null, "h1"), gmail.cursorsAsked)
        assertEquals(Integrations.HEADERS_PER_CHECK + 20, ms.messages(id).size)
        assertEquals(0, ms.pendingCount(id))
        assertEquals("h2", ms.cursor(id))
    }

    @Test
    fun changesUpdateRemoveAndAgeOut() {
        val ms = InMemoryMailStore()
        val (gmail, _, i) = world(ms)
        gmail.mailbox["a"] = msg("a", 1); gmail.mailbox["b"] = msg("b", 59); gmail.mailbox["c"] = msg("c", 2)
        val id = connect(i)
        i.syncAccount(id)
        assertEquals(listOf("a", "c", "b"), ms.messages(id).map { it.id })
        // a was archived and read; c deleted; d arrived; a message that vanished before its headers were read is dropped.
        gmail.mailbox["a"] = msg("a", 1).copy(inInbox = false, unread = false)
        gmail.mailbox.remove("c")
        gmail.mailbox["d"] = msg("d", 0)
        gmail.nextRound = { MailChanges(fetch = listOf("a", "d", "gone"), removed = listOf("c"), cursor = "h2") }
        now += 2 * day // b is now older than 60 days
        i.syncAccount(id)
        val kept = ms.messages(id)
        assertEquals(listOf("d", "a"), kept.map { it.id })
        assertFalse(kept.last().inInbox || kept.last().unread)
        assertEquals(0, ms.pendingCount(id))
    }

    @Test
    fun aForgottenCursorStartsTheAccountAgain() {
        val ms = InMemoryMailStore()
        val (gmail, _, i) = world(ms)
        gmail.mailbox["a"] = msg("a", 1)
        val id = connect(i)
        i.syncAccount(id)
        gmail.mailbox.clear(); gmail.mailbox["z"] = msg("z", 0)
        gmail.nextRound = { c -> if (c != null) throw MailCursorExpired("old") else MailChanges(fetch = listOf("z"), cursor = "h9") }
        i.syncAccount(id)
        assertEquals(listOf(null, "h1", null), gmail.cursorsAsked)
        assertEquals(listOf("z"), ms.messages(id).map { it.id }) // a's row went with the old listing
        assertEquals("h9", ms.cursor(id))
        assertEquals("ok", i.accounts("home").single().status)
    }

    @Test
    fun aFailedFeedLeavesTheAccountInErrorAndKeepsWhatItHad() {
        val ms = InMemoryMailStore()
        val (gmail, store, i) = world(ms)
        gmail.mailbox["a"] = msg("a", 1)
        val id = connect(i)
        i.syncAccount(id)
        gmail.nextRound = { throw IllegalStateException("provider HTTP 503") }
        runCatching { i.syncAccount(id) }
        assertEquals("error", store.account(id)!!.status)
        assertEquals(listOf("a"), ms.messages(id).map { it.id })
        assertEquals("h1", ms.cursor(id))
    }

    @Test
    fun aFailedCheckKeepsItsRealReasonForTheCardAndTheReport() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, store, i) = world(ms, ops = ops)
        gmail.mailbox["a"] = msg("a", 1)
        val id = connect(i)
        i.syncAccount(id)
        // Gmail says slow down: the card says busy, not "couldn't reach it".
        gmail.nextRound = { throw IllegalStateException("provider HTTP 429") }
        runCatching { i.syncAccount(id) }
        assertEquals("error", store.account(id)!!.status)
        assertEquals(MailCheckRules.BUSY, i.accounts("home").single().why)
        // A refusal (403) and a timeout are told apart too.
        gmail.nextRound = { throw WriteRefused("provider refused the change") }
        runCatching { i.syncAccount(id) }
        assertEquals(MailCheckRules.REFUSED, i.accounts("home").single().why)
        gmail.nextRound = { throw java.net.http.HttpTimeoutException("timed out") }
        runCatching { i.syncAccount(id) }
        assertEquals(MailCheckRules.NETWORK, i.accounts("home").single().why)
        // The report says it by label, never the address.
        val r = i.mailReport("home").single()
        assertEquals("Personal", r.label)
        assertEquals("error", r.status)
        assertEquals(MailCheckRules.NETWORK, r.why)
        assertEquals(now + MailAutopilotRules.DRY_RUN_MS, r.dryRunEndsAtMs)
        val line = MailCheckRules.reportLine(r, now) { "t$it" }
        assertFalse(line.contains("@"))
        // It works again: no reason is sent and the report counts the inbox.
        gmail.nextRound = { MailChanges(cursor = "h3") }
        i.syncAccount(id)
        assertEquals(null, i.accounts("home").single().why)
        val ok = i.mailReport("home").single()
        assertEquals("ok", ok.status)
        assertEquals(1L, ok.inboxTotal)
        assertEquals("not tidying", ok.autopilot) // Autopilot isn't on
    }

    @Test
    fun aFailingStageIsInTheReportWithoutFailingTheCheck() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        i.syncAccount(id)
        autopilotOn(ops)
        now += day * 2
        gmail.headerFailsAfter = 0
        gmail.mailbox["n1"] = msg("n1", 0, "editor@weekly.example").copy(listId = "<weekly.example>")
        gmail.nextRound = { MailChanges(fetch = listOf("n1"), cursor = "h2") }
        i.syncAccount(id)
        val r = i.mailReport("home").single()
        assertEquals("ok", r.status)
        assertEquals(MailCheckRules.REFUSED, r.stageFailures["headers"])
        assertTrue(MailCheckRules.reportLine(r, now) { "t" }.contains("failed: headers=refused"))
        // Once the headers come, Autopilot says what it did.
        gmail.headerFailsAfter = null
        gmail.nextRound = { MailChanges(cursor = "h3") }
        i.syncAccount(id)
        assertEquals("planned 1, moved 1", i.mailReport("home").single().autopilot)
        assertEquals(null, i.mailReport("home").single().stageFailures["headers"])
    }

    @Test
    fun mailWhySortsTheFailure() {
        assertEquals(MailCheckRules.BUSY, mailWhy(IllegalStateException("provider HTTP 429"), false))
        assertEquals(MailCheckRules.DOWN, mailWhy(RuntimeException("wrapped", IllegalStateException("provider HTTP 503 (backendError)")), false))
        assertEquals(MailCheckRules.REFUSED, mailWhy(WriteRefused("provider refused the change"), false))
        assertEquals(MailCheckRules.NETWORK, mailWhy(java.net.ConnectException("refused"), true))
        assertEquals(MailCheckRules.SIGN_IN, mailWhy(IllegalStateException("no access_token"), true))
        assertEquals(MailCheckRules.OTHER, mailWhy(IllegalStateException("Gmail answered without a body"), false))
        // Inbox fixes, slice 7: the reason word Http.call keeps from a 403's body.
        assertEquals(MailCheckRules.API_OFF, mailWhy(WriteRefused("provider refused the change [accessNotConfigured]"), false))
        assertEquals(MailCheckRules.NO_PERMISSION, mailWhy(WriteRefused("provider refused the change [insufficientPermissions]"), false))
        assertEquals(MailCheckRules.REFUSED, mailWhy(WriteRefused("provider refused the change [madeUp]"), false))
    }

    @Test
    fun aRefusalsReasonIsReadFromGoogleAndMicrosoftBodies() {
        val http = Http()
        val gmailOff = """{"error":{"code":403,"message":"Gmail API has not been used in project 123 before or it is disabled.","errors":[{"message":"…","domain":"usageLimits","reason":"accessNotConfigured"}],"status":"PERMISSION_DENIED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"SERVICE_DISABLED"}]}}"""
        assertEquals("accessNotConfigured", http.refusedReason(gmailOff))
        val scope = """{"error":{"code":403,"errors":[{"reason":"insufficientPermissions"}],"status":"PERMISSION_DENIED","details":[{"reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}"""
        assertEquals("insufficientPermissions", http.refusedReason(scope))
        assertEquals("ErrorAccessDenied", http.refusedReason("""{"error":{"code":"ErrorAccessDenied","message":"Access is denied."}}"""))
        // An unknown reason, or no JSON at all, keeps nothing.
        assertEquals(null, http.refusedReason("""{"error":{"code":403,"status":"PERMISSION_DENIED"}}"""))
        assertEquals(null, http.refusedReason("<html>Forbidden</html>"))
        assertEquals(null, http.refusedReason(null))
    }

    @Test
    fun disconnectForgetsTheHeaders() {
        val ms = InMemoryMailStore()
        val (gmail, _, i) = world(ms)
        gmail.mailbox["a"] = msg("a", 1)
        val id = connect(i)
        i.syncAccount(id)
        assertTrue(i.disconnectMail("home", "gmail", "alex@example.com"))
        assertTrue(ms.messages(id).isEmpty())
        assertNull(ms.cursor(id))
        // Signing in again lists the mail afresh.
        connect(i)
        i.syncAccount(id)
        assertEquals(listOf<String?>(null, null), gmail.cursorsAsked)
        assertEquals(listOf("a"), ms.messages(id).map { it.id })
    }

    // ---- Slice 2a: sorting ----

    @Test
    fun everyHeaderIsSortedAndTheAccountListCountsTheInbox() {
        val ms = InMemoryMailStore()
        val (gmail, _, i) = world(ms)
        gmail.mailbox["p"] = msg("p", 1) // a person
        gmail.mailbox["n"] = msg("n", 1, "editor@weekly.example").copy(listId = "<weekly.example>")
        gmail.mailbox["r"] = msg("r", 1, "auto-confirm@shop.example").copy(subject = "Your order has shipped")
        gmail.mailbox["s"] = msg("s", 2, "tax@service.gov.uk").copy(labels = listOf("INBOX", "CATEGORY_UPDATES"))
        gmail.mailbox["old"] = msg("old", 3, "noreply@x.example").copy(inInbox = false) // archived: not counted
        val id = connect(i)
        // Nothing listed yet: not "Inbox clear".
        assertNull(i.accounts("home").single().sorted)
        i.syncAccount(id)
        assertEquals(MailSort.NEEDS_YOU, ms.sortOf(id, "p")!!.sort)
        assertFalse(ms.sortOf(id, "p")!!.sure)
        assertEquals(MailSortReason.PUBLIC_BODY, ms.sortOf(id, "s")!!.reason)
        assertEquals(MailSort.NOTIFICATION, ms.sortOf(id, "old")!!.sort)
        assertEquals(mapOf("NEEDS_YOU" to 2, "NEWSLETTER" to 1, "RECEIPT" to 1), i.accounts("home").single().sorted)
        // A header read again is sorted again (it moved to Promotions).
        gmail.mailbox["n"] = gmail.mailbox.getValue("n").copy(labels = listOf("INBOX", "CATEGORY_PROMOTIONS"), listId = null)
        gmail.nextRound = { MailChanges(fetch = listOf("n"), cursor = "h2") }
        i.syncAccount(id)
        assertEquals(MailSort.PROMO, ms.sortOf(id, "n")!!.sort)
        assertEquals(mapOf("NEEDS_YOU" to 2, "PROMO" to 1, "RECEIPT" to 1), i.accounts("home").single().sorted)
    }

    @Test
    fun aBigFirstListingIsSortedInBatchesAndAnEmptyInboxIsClear() {
        val ms = InMemoryMailStore()
        val (gmail, _, i) = world(ms)
        gmail.nextRound = { MailChanges(headers = (0 until Integrations.SORT_BATCH + 7).map { n -> msg("m$n", 1) }, cursor = "h1") }
        val id = connect(i)
        i.syncAccount(id)
        assertEquals(mapOf("NEEDS_YOU" to Integrations.SORT_BATCH + 7), i.accounts("home").single().sorted)
        assertTrue(ms.unsorted(id, 10).isEmpty())
        // Everything left the inbox: listed and sorted, nothing waiting.
        gmail.nextRound = { MailChanges(removed = (0 until Integrations.SORT_BATCH + 7).map { n -> "m$n" }, cursor = "h2") }
        i.syncAccount(id)
        assertEquals(emptyMap(), i.accounts("home").single().sorted)
    }

    // ---- Slice 2b: the AI's look at the unsure ones ----

    private class FakeAi(var answer: ((List<MailHeader>) -> Map<String, String?>?)) : MailAiSorter {
        val asked = mutableListOf<List<String>>()
        override fun lanes(emails: List<MailHeader>): Map<String, String?>? { asked += emails.map { it.id }; return answer(emails) }
    }

    @Test
    fun mailFromPeopleHeHasWrittenToIsHisWithoutTheAisLookAndManyUnsureAreAskedInRounds() {
        val ms = InMemoryMailStore()
        val ai = FakeAi { e -> e.associate { it.id to "fyi" } }
        val (gmail, _, i) = world(ms, mailAi = ai)
        gmail.sentMail += MailSent("s1", "t-s1", "jo@example.com", "Jo", 2, "Plans", now - 30 * day, null,
            addresses = listOf("jo@example.com", "noreply@forms.example", "alex@example.com"))
        gmail.mailbox["j"] = msg("j", 1, "jo@example.com")
        (1..60).forEach { n -> gmail.mailbox["p$n"] = msg("p$n", 2, "stranger$n@example.com") }
        val id = connect(i)
        i.syncAccount(id)
        assertEquals(MailSorted(MailSort.NEEDS_YOU, MailSortReason.KNOWN_PERSON, true), ms.sortOf(id, "j"))
        assertTrue(ai.asked.none { "j" in it })
        // 60 strangers: three calls in one check (25 + 25 + 10), all looked at.
        assertEquals(listOf(25, 25, 10), ai.asked.map { it.size })
        assertTrue((1..60).all { n -> ms.sortOf(id, "p$n")!!.sure })
        // His own address and a no-reply he wrote to aren't people he knows; Jo is kept from every tidy.
        assertEquals(setOf("jo@example.com"), i.knownPeople("home"))
        assertTrue("jo@example.com" in i.keepAddresses("home"))
    }

    @Test
    fun anUnsureEmailSortedBeforeHisSentMailWasReadBecomesHisOnceItIs() {
        val ms = InMemoryMailStore()
        val ai = FakeAi { null } // off
        val (gmail, _, i) = world(ms, mailAi = ai)
        gmail.mailbox["j"] = msg("j", 1, "jo@example.com")
        val id = connect(i)
        i.syncAccount(id)
        assertFalse(ms.sortOf(id, "j")!!.sure)
        gmail.sentMail += MailSent("s1", "t-s1", "jo@example.com", "Jo", 1, "Plans", now - day, null, addresses = listOf("jo@example.com"))
        gmail.nextRound = { MailChanges(cursor = "h2") }
        now += Integrations.CORRESPONDENTS_EVERY_MS
        i.syncAccount(id)
        assertEquals(MailSortReason.KNOWN_PERSON, ms.sortOf(id, "j")!!.reason)
    }

    @Test
    fun theUnsureOnesGetOneLookAndOnlyNeedsYouOrFyiComesBack() {
        val ms = InMemoryMailStore()
        val ai = FakeAi { null } // AI off first
        val (gmail, _, i) = world(ms, mailAi = ai)
        gmail.mailbox["p"] = msg("p", 1) // a person: unsure
        gmail.mailbox["q"] = msg("q", 2, "jo@example.com")
        gmail.mailbox["r"] = msg("r", 3, "pat@example.com")
        gmail.mailbox["n"] = msg("n", 1, "editor@weekly.example").copy(listId = "<weekly.example>") // sure: never asked
        gmail.mailbox["a"] = msg("a", 1, "kim@example.com").copy(inInbox = false) // archived: not asked
        val id = connect(i)
        i.syncAccount(id)
        assertEquals(listOf(listOf("p", "q", "r")), ai.asked)
        // Off: nothing decided, still unsure.
        assertFalse(ms.sortOf(id, "p")!!.sure)
        gmail.nextRound = { MailChanges(cursor = "h2") } // nothing new in the mailbox from here
        ai.answer = { e -> mapOf(e[0].id to "fyi", e[1].id to "needs_you") } // r left out
        i.syncAccount(id)
        assertEquals(MailSorted(MailSort.FYI, MailSortReason.AI_FYI, true), ms.sortOf(id, "p"))
        assertEquals(MailSorted(MailSort.NEEDS_YOU, MailSortReason.AI_NEEDS_YOU, true), ms.sortOf(id, "q"))
        assertEquals(MailSorted(MailSort.NEEDS_YOU, MailSortReason.AI_UNCLEAR, true), ms.sortOf(id, "r"))
        assertEquals(mapOf("FYI" to 1, "NEEDS_YOU" to 2, "NEWSLETTER" to 1), i.accounts("home").single().sorted)
        // Nothing left unsure: no more calls.
        i.syncAccount(id)
        assertEquals(2, ai.asked.size)
        // A failing AI doesn't stop the sync.
        ai.answer = { throw IllegalStateException("boom") }
        gmail.nextRound = { MailChanges(headers = listOf(msg("s", 0, "lee@example.com")), cursor = "h9") }
        i.syncAccount(id)
        assertFalse(ms.sortOf(id, "s")!!.sure)
    }

    @Test
    fun theAiSeesAtMostItsRoundsOfBatchesACheckNewestFirst() {
        val ms = InMemoryMailStore()
        val ai = FakeAi { e -> e.associate { it.id to "needs_you" } }
        val (gmail, _, i) = world(ms, mailAi = ai)
        val most = Integrations.AI_BATCH * Integrations.AI_ROUNDS
        gmail.nextRound = { MailChanges(headers = (0 until most + 3).map { n -> msg("m$n", n.toLong() % 50) }, cursor = "h1") }
        val id = connect(i)
        i.syncAccount(id)
        assertEquals(List(Integrations.AI_ROUNDS) { Integrations.AI_BATCH }, ai.asked.map { it.size })
        val left = ms.unsure(id, 1000)
        assertEquals(3, left.size)
        val asked = ai.asked.flatten().toSet()
        assertTrue(left.none { it.id in asked })
        // Newest first: what waits is no newer than anything asked.
        val oldestAsked = ms.messages(id).filter { it.id in asked }.minOf { it.receivedAtMs }
        assertTrue(left.all { it.receivedAtMs <= oldestAsked })
    }

    // ---- Slice 3a: Autopilot's dry run ----

    @Test
    fun theDryRunCountsWhatWouldBeTidiedAndMovesNothing() {
        val ms = InMemoryMailStore()
        val (gmail, _, i) = world(ms)
        gmail.mailbox["p"] = msg("p", 1) // a person: kept
        gmail.mailbox["n"] = msg("n", 1, "editor@weekly.example").copy(listId = "<weekly.example>")
        gmail.mailbox["r"] = msg("r", 1, "auto-confirm@shop.example").copy(subject = "Your order has shipped")
        gmail.mailbox["u"] = msg("u", 1, "noreply@x.example")
        gmail.mailbox["b"] = msg("b", 1, "alerts@barclays.co.uk").copy(listId = "<alerts.barclays.co.uk>") // a bank: kept
        gmail.mailbox["old"] = msg("old", 3, "noreply@y.example").copy(inInbox = false) // archived already: not counted
        val id = connect(i)
        assertNull(i.accounts("home").single().wouldTidy)
        val before = gmail.mailbox.toMap()
        i.syncAccount(id)
        assertEquals(mapOf("KEEP" to 2, "NEWSLETTER" to 1, "RECEIPT" to 1, "ARCHIVE" to 1), i.accounts("home").single().wouldTidy)
        assertEquals(before, gmail.mailbox.toMap())
    }

    // ---- Slice 3b: Autopilot applies the plan, with Undo ----

    private var opN = 0
    private fun device(ops: InMemoryServerOpStore, type: String, id: String, field: String, v: FieldValue) {
        ops.append(Op("dev${opN++}", "home", type, id, field, v, Hlc(now, opN, "fold"), emptyList(), "fold"))
    }

    private fun autopilotOn(ops: InMemoryServerOpStore, on: Boolean = true) {
        device(ops, EntityTypes.CONTEXT_MODE, MailAutopilotRules.ENTITY_ID, MailAutopilotFields.ON, FieldValue.Bool(on))
        device(ops, EntityTypes.CONTEXT_MODE, MailAutopilotRules.ENTITY_ID, MailAutopilotFields.CHANGED_AT, FieldValue.Int64(now))
    }

    private fun tidies(ops: InMemoryServerOpStore) = EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_TIDY)

    @Test
    fun autopilotTidiesNewSureMailOnlyWhenOnAfterTheDryRunDay() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        gmail.mailbox["old"] = msg("old", 1, "editor@weekly.example").copy(listId = "<weekly.example>") // backlog: stays
        val id = connect(i)
        i.syncAccount(id)
        // Turned on during the dry-run day: nothing moves yet.
        autopilotOn(ops)
        now += day / 2
        gmail.mailbox["n1"] = msg("n1", 0, "editor@weekly.example").copy(listId = "<weekly.example>")
        gmail.nextRound = { MailChanges(fetch = listOf("n1"), cursor = "h2") }
        i.syncAccount(id)
        assertTrue(gmail.tidyCalls.isEmpty())
        // The day is over: mail that arrives now is tidied; the backlog and n1 (dry-run day) stay.
        now += day
        gmail.mailbox["n2"] = msg("n2", 0, "editor@weekly.example").copy(listId = "<weekly.example>")
        gmail.mailbox["r2"] = msg("r2", 0, "auto-confirm@shop.example").copy(subject = "Your order has shipped", preview = "Order total: £12.50 · arriving Friday")
        gmail.mailbox["u2"] = msg("u2", 0, "noreply@x.example")
        gmail.mailbox["p2"] = msg("p2", 0) // a person: Needs you, kept
        gmail.mailbox["b2"] = msg("b2", 0, "alerts@barclays.co.uk").copy(listId = "<alerts.barclays.co.uk>") // a bank: kept
        gmail.mailbox["t2"] = msg("t2", 0, "auto-confirm@shop2.example").copy(subject = "Your order has shipped", threadId = "sent-thread")
        gmail.sentThreads += "sent-thread" // he wrote in t2's thread: kept
        gmail.nextRound = { MailChanges(fetch = listOf("n2", "r2", "u2", "p2", "b2", "t2"), cursor = "h3") }
        i.syncAccount(id)
        assertEquals(mapOf("n2" to MailTidy.NEWSLETTER, "r2" to MailTidy.RECEIPT, "u2" to MailTidy.ARCHIVE), gmail.tidyCalls.single())
        assertFalse(gmail.mailbox.getValue("n2").inInbox)
        assertTrue(gmail.mailbox.getValue("old").inInbox && gmail.mailbox.getValue("n1").inInbox && gmail.mailbox.getValue("t2").inInbox)
        val (batchId, f) = tidies(ops).entries.single()
        assertEquals(FieldValue.Text("Tidied 3 emails · Personal"), f[MailTidyBatchFields.SUMMARY])
        assertEquals(FieldValue.Int64(3), f[MailTidyBatchFields.COUNT])
        // Inbox fixes, slice 5: the account's key (a hash, never the address) on the tidy.
        assertEquals(FieldValue.Text(MailAccountRules.key("gmail", "alex@example.com")), f[MailTidyBatchFields.ACCOUNT_KEY])
        // Slice 3d: the receipt's amount and who from, read from its subject and preview.
        assertEquals(FieldValue.Int64(1250), f[MailTidyBatchFields.RECEIPTS_PENCE])
        assertEquals(FieldValue.Text("Shop"), f[MailTidyBatchFields.MERCHANTS])
        assertTrue((f[MailTidyBatchFields.DETAIL] as FieldValue.Text).value.contains("→ Navilife/Receipts · £12.50 · Shop"))
        assertEquals(3, ms.tidied(id, batchId).size)
        // The next check plans nothing again.
        gmail.nextRound = { MailChanges(cursor = "h4") }
        i.syncAccount(id)
        assertEquals(1, gmail.tidyCalls.size)
    }

    @Test
    fun aReceiptWithAReturnWindowPutsOneReminderOnTheRenewalsRadar() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        i.syncAccount(id)
        autopilotOn(ops)
        now += day * 2
        gmail.mailbox["r1"] = msg("r1", 0, "auto-confirm@shop.example").copy(subject = "Your order has shipped", preview = "Order total: £40.00 · 30-day returns")
        gmail.mailbox["r2"] = msg("r2", 0, "orders@other.example").copy(subject = "Your order has shipped", preview = "Order total: £9.99")
        gmail.nextRound = { MailChanges(fetch = listOf("r1", "r2"), cursor = "h2") }
        i.syncAccount(id)
        val reminders = EntityReader.scanning(ops).entities("home", EntityTypes.OBLIGATION)
        val (rid, f) = reminders.entries.single()
        assertEquals(MailReturnRules.obligationId(id, "r1"), rid)
        assertEquals(FieldValue.Text("Last day to return · Shop order"), f[ActionableFields.TITLE])
        assertEquals(FieldValue.Int64(4000), f[ObligationFields.COST_MINOR])
        assertEquals(FieldValue.Text(MailReturnRules.SOURCE), f[ActionableFields.PROVENANCE_SOURCE])
        val (_, t) = tidies(ops).entries.single()
        assertTrue((t[MailTidyBatchFields.DETAIL] as FieldValue.Text).value.contains(" · 1 return date → Renewals"))
        // Written once: the next check (or a retried one) adds nothing.
        gmail.nextRound = { MailChanges(cursor = "h3") }
        i.syncAccount(id)
        assertEquals(1, EntityReader.scanning(ops).entities("home", EntityTypes.OBLIGATION).size)
    }

    @Test
    fun anOrderAboveHisThresholdPutsItsWarrantyEndOnTheRadarOnce() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        i.syncAccount(id)
        autopilotOn(ops)
        device(ops, EntityTypes.CONTEXT_MODE, MailWarrantyRules.ENTITY_ID, MailWarrantyFields.MIN_PENCE, FieldValue.Int64(25_000))
        now += day * 2
        gmail.mailbox["w1"] = msg("w1", 0, "auto-confirm@shop.example").copy(subject = "Your order has shipped", preview = "Order total: £450.00 · 2-year warranty")
        gmail.mailbox["w2"] = msg("w2", 0, "auto-confirm@other.example").copy(subject = "Your order has shipped", preview = "Order total: £120.00")
        gmail.nextRound = { MailChanges(fetch = listOf("w1", "w2"), cursor = "h2") }
        i.syncAccount(id)
        val reminders = EntityReader.scanning(ops).entities("home", EntityTypes.OBLIGATION)
        val (rid, f) = reminders.entries.single()
        assertEquals(MailWarrantyRules.obligationId(id, "w1"), rid)
        assertEquals(FieldValue.Text("Warranty ends · Shop order"), f[ActionableFields.TITLE])
        assertEquals(FieldValue.Text(ObligationKind.WARRANTY.name), f[ObligationFields.KIND])
        assertEquals(FieldValue.Int64(45_000), f[ObligationFields.COST_MINOR])
        val (_, t) = tidies(ops).entries.single()
        assertTrue((t[MailTidyBatchFields.DETAIL] as FieldValue.Text).value.contains(" · 1 warranty → Renewals"))
        gmail.nextRound = { MailChanges(cursor = "h3") }
        i.syncAccount(id)
        assertEquals(1, EntityReader.scanning(ops).entities("home", EntityTypes.OBLIGATION).size)
    }

    @Test
    fun shippingEmailsBecomeOneParcelThatADeliveryClears() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        i.syncAccount(id)
        // Autopilot isn't needed: parcels are read whether or not the emails were tidied.
        gmail.mailbox["p1"] = msg("p1", 0, "auto-confirm@shop.example").copy(
            subject = "Your order 112-4455 has shipped", preview = "Arriving tomorrow between 10:15 and 11:15",
        )
        gmail.mailbox["n1"] = msg("n1", 0, "sam@example.com").copy(subject = "Arriving tomorrow?", preview = "Are you arriving tomorrow")
        gmail.nextRound = { MailChanges(fetch = listOf("p1", "n1"), cursor = "h2") }
        i.syncAccount(id)
        val parcels = EntityReader.scanning(ops).entities("home", EntityTypes.PARCEL)
        val (pid, f) = parcels.entries.single()
        assertEquals(ParcelRules.entityId(id, "112-4455"), pid)
        assertEquals(FieldValue.Text(ParcelState.COMING.name), f[ParcelFields.STATE])
        assertEquals(FieldValue.Text("10:15–11:15"), f[ParcelFields.WINDOW])
        assertEquals(FieldValue.Text("Shop"), f[ParcelFields.MERCHANT])
        // The next day the delivery email clears it (the same entity, never a second one).
        now += day
        gmail.mailbox["p2"] = msg("p2", 0, "auto-confirm@shop.example").copy(subject = "Delivered: your order 112-4455", preview = "Left with a neighbour")
        gmail.nextRound = { MailChanges(fetch = listOf("p2"), cursor = "h3") }
        i.syncAccount(id)
        val after = EntityReader.scanning(ops).entities("home", EntityTypes.PARCEL)
        assertEquals(setOf(pid), after.keys)
        assertEquals(FieldValue.Text(ParcelState.DELIVERED.name), after.getValue(pid)[ParcelFields.STATE])
    }

    @Test
    fun theInboxsUnreadCountAndWhatCanBeTidiedReachTheBacklogEntity() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        gmail.unreadInInbox = 412
        gmail.mailbox["n1"] = msg("n1", 0, "news@weekly.example").copy(listId = "<weekly.example>", listUnsubscribe = "<mailto:off@weekly.example>")
        gmail.mailbox["n2"] = msg("n2", 0, "news@weekly.example").copy(listId = "<weekly.example>", listUnsubscribe = "<mailto:off@weekly.example>", unread = false)
        gmail.mailbox["p1"] = msg("p1", 0, "sam@example.com")
        i.syncAccount(id)
        val entityId = MailBacklogRules.entityId(id)
        // Read the counts straight off the entity (the backlog's own plan fields may not be written yet in this world).
        fun backlog() = MailBacklogRules.read(entityId, mapOf(MailBacklogFields.STARTED_AT to FieldValue.Int64(1L)) +
            EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_BACKLOG).getValue(entityId))!!
        val b = backlog()
        assertEquals(412, b.inboxUnread)
        // Only the unread newsletter counts (the read one and the person's email don't).
        assertEquals(1, b.tidyUnread)
        assertEquals(now, b.unreadCountedAtMs)
        // A small change soon after isn't written; a big one is.
        val at = now
        now += 60_000L
        gmail.unreadInInbox = 415
        i.syncAccount(id)
        assertEquals(412, backlog().inboxUnread)
        assertEquals(at, backlog().unreadCountedAtMs)
        gmail.unreadInInbox = 380
        i.syncAccount(id)
        assertEquals(380, backlog().inboxUnread)
    }

    @Test
    fun markNReadMarksOnlyTheUnreadTidyKindAfterHisTapAndFinishes() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        gmail.unreadInInbox = 40
        gmail.mailbox["n1"] = msg("n1", 0, "news@weekly.example").copy(listId = "<weekly.example>", listUnsubscribe = "<mailto:off@weekly.example>")
        gmail.mailbox["n2"] = msg("n2", 0, "deals@shop.example").copy(listId = "<shop.example>", listUnsubscribe = "<mailto:off@shop.example>")
        gmail.mailbox["p1"] = msg("p1", 0, "sam@example.com")
        i.syncAccount(id)
        val entityId = MailBacklogRules.entityId(id)
        fun backlog() = MailBacklogRules.read(entityId, mapOf(MailBacklogFields.STARTED_AT to FieldValue.Int64(1L)) +
            EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_BACKLOG).getValue(entityId))!!
        // Nothing is marked before his tap.
        assertTrue(gmail.markedDone.isEmpty())
        assertEquals(2, backlog().tidyUnread)
        now += 60_000L
        // The card (and the server) read the entity as a backlog plan, which this world never starts: stand one in.
        device(ops, EntityTypes.MAIL_BACKLOG, entityId, MailBacklogFields.STARTED_AT, FieldValue.Int64(1L))
        device(ops, EntityTypes.MAIL_BACKLOG, entityId, MailBacklogFields.MARK_READ_APPROVED_AT, FieldValue.Int64(now))
        gmail.markReadRefusedAfter = 1
        i.syncAccount(id)
        // The provider took one; only that one is counted, and the person's email is never in the list.
        assertEquals(1, gmail.markedDone.size)
        assertEquals(1, gmail.markedDone.single().size)
        assertTrue(gmail.markedDone.flatten().none { it == "p1" })
        assertEquals(1, backlog().markedRead)
        assertNull(backlog().markReadFinishedAtMs)
        // The next check marks the other, then one more finds none left and finishes the run.
        gmail.markReadRefusedAfter = null
        now += 60_000L
        i.syncAccount(id)
        assertEquals(setOf("n1", "n2"), gmail.markedDone.flatten().toSet())
        assertEquals(2, backlog().markedRead)
        now += 60_000L
        i.syncAccount(id)
        assertEquals(2, backlog().markedRead)
        assertEquals(now, backlog().markReadFinishedAtMs)
        assertTrue(ms.messages(id).filter { it.id != "p1" }.none { it.unread })
        assertTrue(ms.messages(id).single { it.id == "p1" }.unread)
    }

    @Test
    fun aBillEmailPutsOneBillOnTheRenewalsRadar() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        i.syncAccount(id)
        // Autopilot isn't needed: bills are read whether or not the emails were tidied.
        val due = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Europe/London")).toLocalDate().plusDays(10)
        val dueText = due.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        gmail.mailbox["b1"] = msg("b1", 0, "billing@energyco.example").copy(subject = "Your bill is ready", preview = "Your bill is £84.20 · payment due $dueText")
        gmail.mailbox["b2"] = msg("b2", 0, "billing@energyco.example").copy(subject = "Reminder: your bill", preview = "£84.20 due $dueText")
        gmail.mailbox["p1"] = msg("p1", 0, "billing@energyco.example").copy(subject = "Thanks for your payment", preview = "Payment received · bill due $dueText")
        gmail.nextRound = { MailChanges(fetch = listOf("b1", "b2", "p1"), cursor = "h2") }
        i.syncAccount(id)
        val bills = EntityReader.scanning(ops).entities("home", EntityTypes.OBLIGATION)
        val (_, f) = bills.entries.single()
        assertEquals(FieldValue.Text("Energyco bill"), f[ActionableFields.TITLE])
        assertEquals(FieldValue.Text(os.meka.core.domain.ObligationKind.BILL.name), f[ObligationFields.KIND])
        assertEquals(FieldValue.Int64(8420), f[ObligationFields.COST_MINOR])
        assertEquals(null, f[ObligationFields.PAY_LINK])
        assertEquals(FieldValue.Text(MailBillRules.SOURCE), f[ActionableFields.PROVENANCE_SOURCE])
        // Written once: the next check adds nothing.
        gmail.nextRound = { MailChanges(cursor = "h3") }
        i.syncAccount(id)
        assertEquals(1, EntityReader.scanning(ops).entities("home", EntityTypes.OBLIGATION).size)
    }

    @Test
    fun aRenewalEmailPublishesOneFindAndNothingOnTheRadar() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        i.syncAccount(id)
        val day = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Europe/London")).toLocalDate().plusDays(20)
        val dayText = day.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        gmail.mailbox["r1"] = msg("r1", 0, "info@streamco.example").copy(subject = "Your membership renews soon", preview = "It renews on $dayText for £10.99 a month")
        gmail.mailbox["r2"] = msg("r2", 0, "info@streamco.example").copy(subject = "Reminder: your plan renews on $dayText", preview = "£10.99 a month")
        gmail.mailbox["c1"] = msg("c1", 0, "info@otherco.example").copy(subject = "Your subscription was cancelled", preview = "It will not renew on $dayText")
        gmail.nextRound = { MailChanges(fetch = listOf("r1", "r2", "c1"), cursor = "h2") }
        i.syncAccount(id)
        val finds = EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_FIND)
        val (_, f) = finds.entries.single()
        assertEquals(FieldValue.Text(MailRenewalRules.KIND_RENEWAL), f[os.meka.core.domain.MailFindFields.KIND])
        assertEquals(FieldValue.Text("Streamco"), f[os.meka.core.domain.MailFindFields.MERCHANT])
        assertEquals(FieldValue.Int64(1099), f[os.meka.core.domain.MailFindFields.PENCE])
        // Nothing reaches the radar without his Add.
        assertEquals(0, EntityReader.scanning(ops).entities("home", EntityTypes.OBLIGATION).size)
        // Written once: the next check adds nothing.
        gmail.nextRound = { MailChanges(cursor = "h3") }
        i.syncAccount(id)
        assertEquals(1, EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_FIND).size)
    }

    @Test
    fun aPromiseEmailPublishesOneFindAndNothingOnWaitingFor() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        i.syncAccount(id)
        gmail.mailbox["p1"] = msg("p1", 0, "sam@example.com").copy(subject = "Re: contract", preview = "Thanks. I'll send you the signed contract by Friday.")
        gmail.mailbox["p2"] = msg("p2", 0, "noreply@shop.example").copy(subject = "Order update", preview = "We'll send the parcel tomorrow")
        gmail.mailbox["p3"] = msg("p3", 0, "ann@example.com").copy(subject = "Lunch?", preview = "Can you send me the menu by Friday?")
        gmail.nextRound = { MailChanges(fetch = listOf("p1", "p2", "p3"), cursor = "h2") }
        i.syncAccount(id)
        val finds = EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_FIND)
        val (_, f) = finds.entries.single()
        assertEquals(FieldValue.Text(os.meka.core.domain.MailPromiseRules.KIND_PROMISE), f[os.meka.core.domain.MailFindFields.KIND])
        assertEquals(FieldValue.Text("Signed contract"), f[os.meka.core.domain.MailFindFields.WHAT])
        assertEquals(FieldValue.Bool(true), f[os.meka.core.domain.MailFindFields.BY_STATED])
        // Nothing reaches Waiting for without his Add.
        assertEquals(0, EntityReader.scanning(ops).entities("home", EntityTypes.COMMITMENT).size)
        gmail.nextRound = { MailChanges(cursor = "h3") }
        i.syncAccount(id)
        assertEquals(1, EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_FIND).size)
    }

    @Test
    fun onlyTheSchoolsEmailsBecomeSchoolFinds() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        i.syncAccount(id)
        val day = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Europe/London")).toLocalDate().plusDays(10)
        val dayText = day.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        gmail.mailbox["s1"] = msg("s1", 0, "office@oakfield.sch.uk").copy(subject = "Trip payment", preview = "Please pay £12 for Alex's trip to the farm by $dayText.")
        gmail.mailbox["s2"] = msg("s2", 0, "office@oakfield.sch.uk").copy(subject = "Reminder: trip payment", preview = "Please pay £12 for Alex's trip by $dayText.")
        gmail.mailbox["s3"] = msg("s3", 0, "office@otherschool.example").copy(subject = "Trip payment", preview = "Please pay £15 by $dayText.")
        gmail.nextRound = { MailChanges(fetch = listOf("s1", "s2", "s3"), cursor = "h2") }
        // Nobody marked as the school yet: nothing read.
        i.syncAccount(id)
        assertEquals(0, EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_FIND).size)
        device(ops, EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, AboutYouFields.SCHOOL_WEBSITE, FieldValue.Text("https://www.oakfield.sch.uk"))
        device(ops, EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, AboutYouFields.CHILDREN, FieldValue.Text("Alex|7|Oakfield School\nSam|10|Oakfield School"))
        gmail.nextRound = { MailChanges(cursor = "h3") }
        i.syncAccount(id)
        val finds = EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_FIND)
        val (_, f) = finds.entries.single()
        assertEquals(FieldValue.Text(os.meka.core.domain.SchoolMailRules.KIND_SCHOOL), f[os.meka.core.domain.MailFindFields.KIND])
        assertEquals(FieldValue.Text("Trip payment"), f[os.meka.core.domain.MailFindFields.WHAT])
        assertEquals(FieldValue.Int64(1200), f[os.meka.core.domain.MailFindFields.PENCE])
        assertEquals(FieldValue.Text("Alex"), f[os.meka.core.domain.MailFindFields.WHO])
        // Nothing reaches School without his Add, and the next check adds nothing.
        assertEquals(0, EntityReader.scanning(ops).entities("home", EntityTypes.SCHOOL_ITEM).size)
        gmail.nextRound = { MailChanges(cursor = "h4") }
        i.syncAccount(id)
        assertEquals(1, EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_FIND).size)
    }

    // ---- Follow-up radar, slice 1 ----

    private fun sentMsg(id: String, ageDays: Long, preview: String, to: String = "sam@example.com", thread: String = "t-$id") =
        MailSent(id, thread, to, "Sam Taylor", 1, "Quote $id", now - ageDays * day, preview)

    @Test
    fun anUnansweredAskBecomesAFollowUpAndAReplyClearsIt() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        gmail.sentMail += sentMsg("s1", 4, "Could you send the quote?")
        gmail.sentMail += sentMsg("s2", 0, "Can you make Friday?") // too soon: sent today
        gmail.sentMail += sentMsg("s3", 5, "Thanks, all good.") // asks nothing
        gmail.sentMail += sentMsg("s4", 5, "Any update?", thread = "t-r4") // answered already
        gmail.sentMail += sentMsg("s5", 6, "Can you check?", to = "noreply@shop.example")
        gmail.mailbox["r4"] = msg("r4", 2, "sam@example.com").copy(threadId = "t-r4")
        i.syncAccount(id)
        val finds = EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_FIND)
        val (_, f) = finds.entries.single()
        assertEquals(FieldValue.Text(os.meka.core.domain.MailFollowUpRules.KIND_FOLLOW_UP), f[os.meka.core.domain.MailFindFields.KIND])
        assertEquals(FieldValue.Text("Reply about Quote s1"), f[os.meka.core.domain.MailFindFields.WHAT])
        assertEquals(FieldValue.Text("Sam Taylor"), f[os.meka.core.domain.MailFindFields.MERCHANT])
        // Nothing reaches Waiting for without his Add.
        assertEquals(0, EntityReader.scanning(ops).entities("home", EntityTypes.COMMITMENT).size)
        // The next check within six hours doesn't read Sent again.
        i.syncAccount(id)
        // (one read for the follow-ups, one for the people he has written to: inbox fixes, slice 4)
        assertEquals(2, gmail.sentReads)
        // Sam replies in the conversation; at the next read the find is marked answered.
        gmail.mailbox["r1"] = msg("r1", 0, "sam@example.com").copy(threadId = "t-s1")
        gmail.nextRound = { MailChanges(fetch = listOf("r1"), cursor = "h2") }
        now += Integrations.FOLLOW_UPS_EVERY_MS
        i.syncAccount(id)
        val after = EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_FIND).values.single()
        assertEquals(FieldValue.Text(os.meka.core.domain.MailFollowUpRules.ANSWERED_REPLIED), after[os.meka.core.domain.MailFindFields.ANSWERED_WHY])
        assertEquals(1, EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_FIND).size)
    }

    @Test
    fun hisChaseOfAnUnansweredEmailIsSentOnceInItsConversationAfterTheHold() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        gmail.sentMail += sentMsg("s1", 4, "Could you send the quote?")
        i.syncAccount(id)
        val (fid, _) = EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_FIND).entries.single()
        // Nothing is sent until he taps Send, and not inside Undo's hold.
        gmail.nextRound = { MailChanges(cursor = "h2") }
        i.syncAccount(id)
        assertTrue(gmail.chases.isEmpty())
        MailChaseRules.sendFields("Hi Sam, any news on the quote?", now).forEach { (f, v) -> device(ops, EntityTypes.MAIL_FIND, fid, f, v) }
        now += 5_000
        i.syncAccount(id)
        assertTrue(gmail.chases.isEmpty())
        now += MailNeedsRules.REPLY_HOLD_MS
        i.syncAccount(id)
        // Replying to the email he sent (its own id), once.
        assertEquals(listOf("s1" to "Hi Sam, any news on the quote?"), gmail.chases)
        assertTrue(gmail.replies.isEmpty())
        val sent = os.meka.core.domain.MailRenewalRules.read(fid, EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_FIND).getValue(fid))!!
        assertTrue(MailChaseRules.sent(sent.chase))
        i.syncAccount(id)
        assertEquals(1, gmail.chases.size)
        // Gmail can't find it next time: said so on the find, not retried.
        gmail.chaseAnswer = { MailReplyResult.GONE }
        now += 1_000
        MailChaseRules.sendFields("Any news?", now).forEach { (f, v) -> device(ops, EntityTypes.MAIL_FIND, fid, f, v) }
        now += MailNeedsRules.REPLY_HOLD_MS
        i.syncAccount(id)
        i.syncAccount(id)
        val failed = os.meka.core.domain.MailRenewalRules.read(fid, EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_FIND).getValue(fid))!!
        assertTrue(MailChaseRules.failed(failed.chase))
        assertEquals(MailChaseRules.WHY_GONE, failed.chase.failedWhy)
        assertEquals(1, gmail.chases.size)
        // After his own chase ("wrote again"), Sam's reply still marks it replied.
        gmail.sentMail += sentMsg("s2", 0, "Hi Sam, any news on the quote?", thread = "t-s1")
        now += Integrations.FOLLOW_UPS_EVERY_MS
        i.syncAccount(id)
        assertEquals(FieldValue.Text(os.meka.core.domain.MailFollowUpRules.ANSWERED_WROTE_AGAIN),
            EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_FIND).getValue(fid)[os.meka.core.domain.MailFindFields.ANSWERED_WHY])
        gmail.mailbox["r1"] = msg("r1", 0, "sam@example.com").copy(threadId = "t-s1", receivedAtMs = now)
        gmail.nextRound = { MailChanges(fetch = listOf("r1"), cursor = "h3") }
        now += Integrations.FOLLOW_UPS_EVERY_MS
        i.syncAccount(id)
        assertEquals(FieldValue.Text(os.meka.core.domain.MailFollowUpRules.ANSWERED_REPLIED),
            EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_FIND).getValue(fid)[os.meka.core.domain.MailFindFields.ANSWERED_WHY])
    }

    @Test
    fun aChaseGoesToWhoTheEmailWentTo() {
        assertEquals("sam@example.com, ann@example.com" to "jo@example.com",
            MailReplyMessage.chaseRecipients("\"Taylor, Sam\" <sam@example.com>, ann@example.com", "Jo <jo@example.com>, sam@example.com"))
        assertEquals("sam@example.com" to null, MailReplyMessage.chaseRecipients("Sam <sam@example.com>", null))
        assertNull(MailReplyMessage.chaseRecipients("undisclosed-recipients:;", null))
        val raw = MailReplyMessage.rfc822("sam@example.com", "Re: Quote", "<m1@x>", null, "Any news?", cc = "jo@example.com\r\nBcc: x@y")
        assertTrue("Cc: jo@example.com  Bcc: x@y\r\n" in raw, raw)
        assertFalse("\r\nBcc:" in raw)
    }

    // ---- Slice 3c: the daily Junk review ----

    private fun junkMsg(id: String, from: String, subject: String, thread: String = "t-$id") =
        MailHeader(id, thread, from, null, subject, now - 3_600_000L, unread = true, inInbox = false, labels = listOf("SPAM"))

    @Test
    fun junkIsReviewedOnceADayScamsToTrashRealMailRescuedWithUndo() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        gmail.junkBox["s0"] = junkMsg("s0", "prizes@win.example", "You have WON a prize")
        i.syncAccount(id)
        // The dry-run day: Junk isn't looked at.
        assertEquals(0, gmail.junkAsked)
        now += 2 * day
        gmail.mailbox["p1"] = msg("p1", 0, "coach@club.example") // someone who writes to him: Needs you
        gmail.nextRound = { MailChanges(fetch = listOf("p1"), cursor = "h2") }
        gmail.junkBox.clear()
        gmail.junkBox["s1"] = junkMsg("s1", "prizes@win.example", "Congratulations, you have WON")
        gmail.junkBox["k1"] = junkMsg("k1", "coach@club.example", "Training on Saturday")
        gmail.junkBox["r1"] = junkMsg("r1", "billing@supplier.example", "Re: the invoice", thread = "sent-thread")
        gmail.junkBox["b1"] = junkMsg("b1", "alerts@barclays.co.uk", "Your account has been suspended")
        gmail.junkBox["u1"] = junkMsg("u1", "hello@random.example", "Meeting notes")
        gmail.sentThreads += "sent-thread"
        i.syncAccount(id)
        assertEquals(mapOf("s1" to MailTidy.JUNK_TRASH, "k1" to MailTidy.RESCUE, "r1" to MailTidy.RESCUE), gmail.tidyCalls.single())
        assertEquals(setOf("b1", "u1"), gmail.junkBox.keys)
        assertTrue(gmail.mailbox.getValue("k1").inInbox && "STARRED" in gmail.mailbox.getValue("k1").labels)
        assertTrue("s1" in gmail.trashBox)
        val (batchId, f) = tidies(ops).entries.single()
        assertEquals(FieldValue.Text("Junk checked · 1 to Trash · 2 rescued · Personal"), f[MailTidyBatchFields.SUMMARY])
        assertEquals(FieldValue.Bool(true), f[MailTidyBatchFields.JUNK])
        assertEquals(FieldValue.Int64(2), f[MailTidyBatchFields.RESCUED])
        // A rescued email is never tidied by Autopilot after.
        assertTrue("k1" in ms.neverTidied(id))
        // The same day: not looked at again.
        gmail.nextRound = { MailChanges(cursor = "h3") }
        i.syncAccount(id)
        assertEquals(1, gmail.junkAsked)
        // Undo: everything back in Junk, and never reviewed again.
        now += 60_000
        device(ops, EntityTypes.MAIL_TIDY, batchId, MailTidyBatchFields.UNDO_ASKED_AT, FieldValue.Int64(now))
        i.syncAccount(id)
        assertEquals(setOf("b1", "u1", "s1", "k1", "r1"), gmail.junkBox.keys)
        assertEquals(FieldValue.Text("Put back 3 emails in Junk"), tidies(ops).getValue(batchId)[MailTidyBatchFields.UNDO_NOTE])
        now += day
        gmail.junkBox.replaceAll { _, h -> h.copy(receivedAtMs = now - 3_600_000L) }
        i.syncAccount(id)
        assertEquals(2, gmail.junkAsked)
        assertEquals(1, gmail.tidyCalls.size)
    }

    @Test
    fun theDailyReviewTrashesJunkOlderThanTwoDays() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        i.syncAccount(id)
        now += 2 * day
        gmail.nextRound = { MailChanges(cursor = "h2") }
        gmail.junkBox["old"] = junkMsg("old", "hello@random.example", "Meeting notes").copy(receivedAtMs = now - 3 * day)
        gmail.junkBox["new"] = junkMsg("new", "hello@random.example", "Meeting notes")
        gmail.junkBox["bank"] = junkMsg("bank", "alerts@barclays.co.uk", "Statement").copy(receivedAtMs = now - 3 * day)
        i.syncAccount(id)
        assertEquals(mapOf("old" to MailTidy.JUNK_TRASH), gmail.tidyCalls.single())
        assertEquals(setOf("new", "bank"), gmail.junkBox.keys)
    }

    @Test
    fun emptyJunkIsCountedForItsCardThenClearedOnlyAfterApprove() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        gmail.backlogPages = mapOf(null to MailBacklogPage(next = null))
        gmail.nextRound = { MailChanges(cursor = "h1") }
        gmail.junkCountAnswer = 4
        for (n in 1..3) gmail.junkBox["j$n"] = junkMsg("j$n", "deals$n@spam.example", "Hot offer $n").copy(receivedAtMs = now - (10 + n) * day)
        gmail.junkBox["fresh"] = junkMsg("fresh", "deals@spam.example", "Hot offer")
        gmail.junkBox["bank"] = junkMsg("bank", "alerts@barclays.co.uk", "Statement").copy(receivedAtMs = now - 20 * day)
        val id = connect(i)
        autopilotOn(ops, on = false) // the card needs no Autopilot: his Approve is the yes
        i.syncAccount(id)
        val entityId = MailBacklogRules.entityId(id)
        val counted = MailBacklogRules.read(entityId, backlogs(ops).getValue(entityId))!!
        assertEquals(4, counted.junkFolder)
        // Inbox fixes, slice 9: the count looked at the older junk: the bank's stays for him, nobody he knows.
        assertEquals(1, counted.junkKeep)
        assertEquals(0, counted.junkRescue)
        assertEquals(4, counted.junkLooked)
        assertTrue(counted.junkLookFull)
        val card = MailBacklogRules.cards(listOf(counted)).single()
        assertEquals("junk:$entityId", card.id)
        assertTrue(card.why.startsWith("3 emails your provider called spam → Trash"), card.why)
        assertTrue("keep 1 to check: banks, schools and public bodies" in card.why, card.why)
        assertTrue(gmail.tidyCalls.isEmpty())
        val looks = gmail.junkOlderAsked.size
        // Not approved: nothing listed or moved, and not counted again within hours.
        now += 300_000
        i.syncAccount(id)
        assertEquals(1, gmail.junkCounts)
        assertEquals(looks, gmail.junkOlderAsked.size)
        assertTrue(gmail.tidyCalls.isEmpty())
        // Approve: the older junk goes to Trash (the bank's stays for him, today's for the daily review).
        device(ops, EntityTypes.MAIL_BACKLOG, entityId, MailBacklogFields.JUNK_APPROVED_AT, FieldValue.Int64(now))
        now += 300_000
        i.syncAccount(id)
        assertEquals(mapOf("j1" to MailTidy.JUNK_TRASH, "j2" to MailTidy.JUNK_TRASH, "j3" to MailTidy.JUNK_TRASH), gmail.tidyCalls.single())
        assertEquals(setOf("fresh", "bank"), gmail.junkBox.keys)
        val batchId = MailJunkClearRules.batchId(id)
        assertEquals(FieldValue.Text("Junk emptied · 3 to Trash · Personal"), tidies(ops).getValue(batchId)[MailTidyBatchFields.SUMMARY])
        assertEquals(3, MailBacklogRules.read(entityId, backlogs(ops).getValue(entityId))!!.junkCleared)
        // Marked read as it went.
        assertTrue(gmail.marked.any { (ids, read) -> read && ids == setOf("j1", "j2", "j3") })
        // The next check finds nothing more it may move: finished.
        now += 300_000
        i.syncAccount(id)
        assertEquals(1, gmail.tidyCalls.size)
        val done = MailBacklogRules.read(entityId, backlogs(ops).getValue(entityId))!!
        assertNotNull(done.junkFinishedAtMs)
        assertTrue(MailBacklogRules.cards(listOf(done)).isEmpty())
        // Undo: back in Junk.
        device(ops, EntityTypes.MAIL_TIDY, batchId, MailTidyBatchFields.UNDO_ASKED_AT, FieldValue.Int64(now))
        now += 60_000
        i.syncAccount(id)
        assertEquals(setOf("fresh", "bank", "j1", "j2", "j3"), gmail.junkBox.keys)
    }

    @Test
    fun junkReviewWaitsWhenAutopilotIsOffOrHisThreadsCantBeRead() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        i.syncAccount(id)
        now += 2 * day
        autopilotOn(ops, on = false)
        gmail.junkBox["s1"] = junkMsg("s1", "prizes@win.example", "You have WON")
        i.syncAccount(id)
        assertEquals(0, gmail.junkAsked)
        autopilotOn(ops, on = true)
        gmail.repliedFails = true
        i.syncAccount(id)
        assertEquals(1, gmail.junkAsked)
        assertTrue(gmail.tidyCalls.isEmpty())
        // Next check (not marked reviewed): his threads read, the scam goes.
        gmail.repliedFails = false
        i.syncAccount(id)
        assertEquals(mapOf("s1" to MailTidy.JUNK_TRASH), gmail.tidyCalls.single())
    }

    @Test
    fun undoPutsATidyBackAndItIsNeverTidiedAgain() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        i.syncAccount(id)
        now += day + 1
        autopilotOn(ops)
        now += 1
        gmail.mailbox["n"] = msg("n", 0, "editor@weekly.example").copy(listId = "<weekly.example>")
        gmail.nextRound = { MailChanges(fetch = listOf("n"), cursor = "h2") }
        i.syncAccount(id)
        val batchId = tidies(ops).keys.single()
        assertFalse(gmail.mailbox.getValue("n").inInbox)
        // Undo on the Fold.
        now += 60_000
        device(ops, EntityTypes.MAIL_TIDY, batchId, MailTidyBatchFields.UNDO_ASKED_AT, FieldValue.Int64(now))
        gmail.nextRound = { MailChanges(cursor = "h3") }
        i.syncAccount(id)
        assertTrue(gmail.mailbox.getValue("n").inInbox)
        assertFalse("Navilife/Newsletters" in gmail.mailbox.getValue("n").labels)
        val f = tidies(ops).getValue(batchId)
        assertEquals(FieldValue.Text("Put back 1 email in the inbox"), f[MailTidyBatchFields.UNDO_NOTE])
        assertTrue(f[MailTidyBatchFields.UNDONE_AT] is FieldValue.Int64)
        // Back in the inbox (Gmail's history says so): never tidied again, and the Undo isn't done twice.
        gmail.nextRound = { MailChanges(fetch = listOf("n"), cursor = "h4") }
        i.syncAccount(id)
        assertEquals(1, gmail.tidyCalls.size)
        assertTrue(gmail.mailbox.getValue("n").inInbox)
    }

    @Test
    fun aProviderFailurePartWayRecordsWhatMovedAndTheRestFollowNextCheck() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        i.syncAccount(id)
        now += day + 1
        autopilotOn(ops)
        now += 1
        listOf("n1", "n2", "n3").forEach { gmail.mailbox[it] = msg(it, 0, "editor@weekly.example").copy(listId = "<weekly.example>") }
        gmail.nextRound = { MailChanges(fetch = listOf("n1", "n2", "n3"), cursor = "h2") }
        // Gmail answers 429 after the first move: that one is recorded (with Undo), the others stay in the inbox.
        gmail.tidyFailsAfter = 1
        i.syncAccount(id)
        assertEquals("ok", i.accounts("home").single().status)
        val first = tidies(ops).entries.single()
        assertEquals(FieldValue.Int64(1), first.value[MailTidyBatchFields.COUNT])
        val movedFirst = ms.tidied(id, first.key).single().id
        val rest = listOf("n1", "n2", "n3") - movedFirst
        assertFalse(gmail.mailbox.getValue(movedFirst).inInbox)
        assertTrue(rest.all { gmail.mailbox.getValue(it).inInbox })
        // The next check tidies the rest, once each.
        gmail.tidyFailsAfter = null
        now += 60_000
        gmail.nextRound = { MailChanges(cursor = "h3") }
        i.syncAccount(id)
        assertEquals(rest.associateWith { MailTidy.NEWSLETTER }, gmail.tidyCalls.last())
        assertEquals(2, tidies(ops).size)
        assertTrue(listOf("n1", "n2", "n3").none { gmail.mailbox.getValue(it).inInbox })
        // Undo of the second tidy cut short after one: not marked done, the one back is never tidied again; the next
        // check finishes it and says so.
        val second = tidies(ops).keys.single { it != first.key }
        now += 60_000
        device(ops, EntityTypes.MAIL_TIDY, second, MailTidyBatchFields.UNDO_ASKED_AT, FieldValue.Int64(now))
        gmail.untidyFailsAfter = 1
        gmail.nextRound = { MailChanges(cursor = "h4") }
        i.syncAccount(id)
        assertNull(tidies(ops).getValue(second)[MailTidyBatchFields.UNDONE_AT])
        val backFirst = rest.filter { gmail.mailbox.getValue(it).inInbox }
        assertEquals(1, backFirst.size)
        assertTrue(backFirst.single() in ms.neverTidied(id))
        gmail.untidyFailsAfter = null
        now += 60_000
        gmail.nextRound = { MailChanges(fetch = rest, cursor = "h5") }
        i.syncAccount(id)
        val f = tidies(ops).getValue(second)
        assertTrue(f[MailTidyBatchFields.UNDONE_AT] is FieldValue.Int64)
        assertEquals(FieldValue.Text("Put back 2 emails in the inbox"), f[MailTidyBatchFields.UNDO_NOTE])
        assertTrue(rest.all { gmail.mailbox.getValue(it).inInbox })
        // Back in the inbox: never tidied again.
        now += 60_000
        gmail.nextRound = { MailChanges(fetch = rest, cursor = "h6") }
        i.syncAccount(id)
        assertEquals(2, gmail.tidyCalls.size)
    }

    @Test
    fun offPausedOrUnsureOfHisThreadsMovesNothing() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        i.syncAccount(id)
        now += 2 * day
        fun arrive(n: String) {
            gmail.mailbox[n] = msg(n, 0, "editor@weekly.example").copy(listId = "<weekly.example>")
            gmail.nextRound = { MailChanges(fetch = listOf(n), cursor = "h$n") }
            now += 60_000
            i.syncAccount(id)
        }
        autopilotOn(ops, on = false); now += 1
        arrive("a") // turned off (slice 3b-2: absent reads as on)
        autopilotOn(ops); now += 1
        device(ops, EntityTypes.CONTEXT_MODE, AutonomyRules.ENTITY_ID, AutonomyFields.PAUSED, FieldValue.Bool(true))
        arrive("b") // MEKA paused
        device(ops, EntityTypes.CONTEXT_MODE, AutonomyRules.ENTITY_ID, AutonomyFields.PAUSED, FieldValue.Bool(false))
        gmail.repliedFails = true
        arrive("c") // can't tell his threads: nothing, and the check still succeeds
        assertTrue(gmail.tidyCalls.isEmpty())
        assertEquals("ok", i.accounts("home").single().status)
        gmail.repliedFails = false
        autopilotOn(ops, on = false); now += 1
        arrive("d") // turned off again
        assertTrue(gmail.tidyCalls.isEmpty())
        assertTrue(tidies(ops).isEmpty())
    }

    @Test
    fun mailFromThePartnersEmailInAboutYouIsNeverTidied() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        // Typed on a device as "Sam@Family.Example": the profile holds it, the autopilot reads it in any case.
        device(ops, EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, AboutYouFields.PARTNER_EMAIL, FieldValue.Text("Sam@Family.Example"))
        gmail.mailbox["s0"] = msg("s0", 1, "sam@family.example").copy(listId = "<club.example>") // in the dry run: kept
        val id = connect(i)
        i.syncAccount(id)
        assertEquals(mapOf("KEEP" to 1), i.accounts("home").single().wouldTidy)
        now += day + 1
        autopilotOn(ops)
        now += 1
        gmail.mailbox["s"] = msg("s", 0, "sam@family.example").copy(listId = "<club.example>") // a list she posts to
        gmail.mailbox["n"] = msg("n", 0, "editor@weekly.example").copy(listId = "<weekly.example>")
        gmail.nextRound = { MailChanges(fetch = listOf("s", "n"), cursor = "h2") }
        i.syncAccount(id)
        assertEquals(mapOf("n" to MailTidy.NEWSLETTER), gmail.tidyCalls.single())
        assertTrue(gmail.mailbox.getValue("s").inInbox)
    }

    @Test
    fun hisSenderRulesKeepOrAlwaysTidyAndCanBeTakenOff() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        gmail.mailbox["n0"] = msg("n0", 1, "Editor@Weekly.Example").copy(listId = "<weekly.example>")
        gmail.mailbox["p0"] = msg("p0", 1, "reports@tool.example") // sorted as needing him
        val id = connect(i)
        i.syncAccount(id)
        assertEquals(mapOf("NEWSLETTER" to 1, "KEEP" to 1), i.accounts("home").single().wouldTidy)
        // On a device: Keep in inbox on the newsletter, always tidy on the reports (only the rule field is written).
        val weekly = MailUnsubscribeRules.senderId(id, "editor@weekly.example")
        val reports = MailUnsubscribeRules.senderId(id, "reports@tool.example")
        device(ops, EntityTypes.MAIL_SENDER, weekly, MailSenderFields.RULE, FieldValue.Text(MailSenderRule.KEEP.name))
        device(ops, EntityTypes.MAIL_SENDER, reports, MailSenderFields.RULE, FieldValue.Text(MailSenderRule.TIDY.name))
        // The dry run follows them.
        assertEquals(mapOf("KEEP" to 1, "ARCHIVE" to 1), i.accounts("home").single().wouldTidy)
        now += day + 1
        autopilotOn(ops)
        now += 1
        gmail.mailbox["n1"] = msg("n1", 0, "editor@weekly.example").copy(listId = "<weekly.example>")
        gmail.mailbox["p1"] = msg("p1", 0, "reports@tool.example")
        gmail.mailbox["b1"] = msg("b1", 0, "alerts@barclays.co.uk")
        device(ops, EntityTypes.MAIL_SENDER, MailUnsubscribeRules.senderId(id, "alerts@barclays.co.uk"), MailSenderFields.RULE, FieldValue.Text(MailSenderRule.TIDY.name))
        gmail.nextRound = { MailChanges(fetch = listOf("n1", "p1", "b1"), cursor = "h2") }
        i.syncAccount(id)
        // Kept stays, always-tidy is archived, a bank is never tidied whatever the rule says.
        assertEquals(mapOf("p1" to MailTidy.ARCHIVE), gmail.tidyCalls.single())
        // Inbox fixes, slice 5: a sender the server writes carries the account's key, so Mail lists it under its own account.
        assertEquals(
            FieldValue.Text(MailAccountRules.key("gmail", "alex@example.com")),
            EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_SENDER)[weekly]?.get(MailSenderFields.ACCOUNT_KEY),
        )
        assertTrue(gmail.mailbox.getValue("n1").inInbox)
        assertTrue(gmail.mailbox.getValue("b1").inInbox)
        // Taken off ("Let Autopilot decide"): the next newsletter is tidied as usual.
        now += 60_000
        device(ops, EntityTypes.MAIL_SENDER, weekly, MailSenderFields.RULE, FieldValue.Text(""))
        gmail.mailbox["n2"] = msg("n2", 0, "editor@weekly.example").copy(listId = "<weekly.example>")
        gmail.nextRound = { MailChanges(fetch = listOf("n2"), cursor = "h3") }
        i.syncAccount(id)
        assertEquals(MailTidy.NEWSLETTER, gmail.tidyCalls.last()["n2"])
    }

    @Test
    fun alwaysTidyOnACardGivesTheSenderTheRuleAndUndoTakesItOff() {
        // Slice 10b.
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        gmail.mailbox["p0"] = msg("p0", 0, "Reports@Tool.Example").copy(fromName = "Tool Reports")
        gmail.mailbox["b0"] = msg("b0", 0, "office@oakfield.sch.uk").copy(fromName = "Oakfield School")
        val id = connect(i)
        i.syncAccount(id)
        val p0 = MailNeedsRules.itemId(id, "p0")
        val b0 = MailNeedsRules.itemId(id, "b0")
        assertEquals(setOf(p0, b0), items(ops).keys)
        fun senders() = EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_SENDER)
        val sid = MailUnsubscribeRules.senderId(id, "reports@tool.example")
        assertNull(senders()[sid])

        // Always tidy on both cards: each is archived; the person's sender is made with the rule, the school gets none.
        now += 60_000
        val asked = now
        for (c in listOf(p0, b0)) MailSenderRuleRules.cardFields(asked).forEach { (f, v) -> device(ops, EntityTypes.MAIL_ITEM, c, f, v) }
        gmail.nextRound = { MailChanges(cursor = "h2") }
        i.syncAccount(id)
        assertFalse(gmail.mailbox.getValue("p0").inInbox)
        assertFalse(gmail.mailbox.getValue("b0").inInbox)
        val made = assertNotNull(MailUnsubscribeRules.read(sid, senders().getValue(sid)))
        assertEquals("Tool Reports", made.name)
        assertEquals("Personal", made.account)
        assertEquals(MailSenderRule.TIDY, made.rule)
        assertFalse(senders().getValue(sid).values.any { it == FieldValue.Text("reports@tool.example") })
        assertNull(senders()[MailUnsubscribeRules.senderId(id, "office@oakfield.sch.uk")]?.let { MailSenderRuleRules.read(it) })
        assertFalse(MailSenderRuleRules.ruleDue(MailNeedsRules.read(p0, items(ops).getValue(p0))!!))
        // The Mail pane's "Your rules" would list it (the core reads the same entity).
        assertEquals(listOf("Tool Reports"), MailSenderRuleRules.rows(listOf(made), "Personal").map { it.name })

        // Autopilot follows it: their next email is archived as it arrives.
        now += day + 1
        autopilotOn(ops)
        now += 1
        gmail.mailbox["p1"] = msg("p1", 0, "reports@tool.example")
        gmail.nextRound = { MailChanges(fetch = listOf("p1"), cursor = "h3") }
        i.syncAccount(id)
        assertEquals(MailTidy.ARCHIVE, gmail.tidyCalls.last()["p1"])

        // Let Autopilot decide in Mail, then Always tidy on a later card from them: the rule is written again, even
        // though the server's own last write said the same.
        now += 60_000
        MailSenderRuleRules.fields(null, now).forEach { (f, v) -> device(ops, EntityTypes.MAIL_SENDER, sid, f, v) }
        now += 60_000
        gmail.mailbox["p2"] = msg("p2", 0, "reports@tool.example")
        gmail.nextRound = { MailChanges(fetch = listOf("p2"), cursor = "h4") }
        i.syncAccount(id)
        assertTrue(gmail.mailbox.getValue("p2").inInbox)
        val p2 = MailNeedsRules.itemId(id, "p2")
        assertNotNull(items(ops)[p2])
        now += 60_000
        val asked2 = now
        MailSenderRuleRules.cardFields(asked2).forEach { (f, v) -> device(ops, EntityTypes.MAIL_ITEM, p2, f, v) }
        gmail.nextRound = { MailChanges(cursor = "h5") }
        i.syncAccount(id)
        assertEquals(MailSenderRule.TIDY, MailSenderRuleRules.read(senders().getValue(sid)))

        // Undo on the first card: back in the inbox, but the rule set since (p2's) stands.
        now += 60_000
        device(ops, EntityTypes.MAIL_ITEM, p0, MailItemFields.UNDO_ASKED_AT, FieldValue.Int64(now))
        gmail.nextRound = { MailChanges(cursor = "h6") }
        i.syncAccount(id)
        assertTrue(gmail.mailbox.getValue("p0").inInbox)
        assertEquals(MailSenderRule.TIDY, MailSenderRuleRules.read(senders().getValue(sid)))
        assertFalse(MailSenderRuleRules.clearDue(MailNeedsRules.read(p0, items(ops).getValue(p0))!!))
        // Undo on p2's card: the rule comes off (once).
        now += 60_000
        device(ops, EntityTypes.MAIL_ITEM, p2, MailItemFields.UNDO_ASKED_AT, FieldValue.Int64(now))
        gmail.nextRound = { MailChanges(cursor = "h7") }
        i.syncAccount(id)
        assertTrue(gmail.mailbox.getValue("p2").inInbox)
        assertNull(MailSenderRuleRules.read(senders().getValue(sid)))
        assertFalse(MailSenderRuleRules.clearDue(MailNeedsRules.read(p2, items(ops).getValue(p2))!!))
    }

    // ---- Slice 5a: Unsubscribe for him ----

    // ---- Slice 6a: "From your mail" cards ----

    private fun items(ops: InMemoryServerOpStore) = EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_ITEM)

    @Test
    fun needsYouEmailsBecomeCardsWithDoneAndUndo() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        gmail.mailbox["p"] = msg("p", 0).copy(fromName = "Sam Taylor", subject = "Football on Saturday", preview = "Hi Alex, can you take&nbsp;the boys?")
        gmail.mailbox["q"] = msg("q", 1, "alex.reed@example.org").copy(threadId = "t-p") // same thread as p
        gmail.mailbox["n"] = msg("n", 0, "editor@weekly.example").copy(listId = "<weekly.example>") // a newsletter: no card
        gmail.mailbox["old"] = msg("old", 20) // outside the 14 days: no card
        val id = connect(i)
        i.syncAccount(id)
        val p = MailNeedsRules.itemId(id, "p")
        val q = MailNeedsRules.itemId(id, "q")
        assertEquals(setOf(p, q), items(ops).keys)
        val card = MailNeedsRules.read(p, items(ops).getValue(p))!!
        assertEquals("Sam Taylor", card.from)
        assertEquals("Personal", card.account)
        assertEquals("Football on Saturday", card.subject)
        assertEquals("Hi Alex, can you take the boys?", card.preview)
        assertEquals(2, card.threadCount)
        assertFalse(items(ops).values.flatMap { it.values }.any { it == FieldValue.Text("sam@example.com") })
        // Slice 6c: both carry the thread's hashed key (never the provider's thread id), so the apps link them.
        val qCard = MailNeedsRules.read(q, items(ops).getValue(q))!!
        assertNotNull(card.threadKey)
        assertEquals(card.threadKey, qCard.threadKey)
        assertFalse(items(ops).values.flatMap { it.values }.any { it == FieldValue.Text("t-p") })

        // Done on the Fold: archived at the next check, the card marked gone.
        now += 60_000
        device(ops, EntityTypes.MAIL_ITEM, p, MailItemFields.DONE_ASKED_AT, FieldValue.Int64(now))
        gmail.nextRound = { MailChanges(cursor = "h2") }
        i.syncAccount(id)
        assertEquals(mapOf("p" to MailTidy.ARCHIVE), gmail.tidyCalls.single())
        assertFalse(gmail.mailbox.getValue("p").inInbox)
        val done = MailNeedsRules.read(p, items(ops).getValue(p))!!
        assertTrue(done.doneAtMs != null && done.goneAtMs != null)
        // Not archived twice.
        i.syncAccount(id)
        assertEquals(1, gmail.tidyCalls.size)

        // Undo: back in the inbox, and the card shows again in the same check.
        now += 60_000
        device(ops, EntityTypes.MAIL_ITEM, p, MailItemFields.UNDO_ASKED_AT, FieldValue.Int64(now))
        i.syncAccount(id)
        assertTrue(gmail.mailbox.getValue("p").inInbox)
        val back = MailNeedsRules.read(p, items(ops).getValue(p))!!
        assertTrue(back.undoneAtMs != null)
        assertNull(back.goneAtMs)
        assertFalse(MailNeedsRules.putBackDue(back))
        assertTrue(MailNeedsRules.showing(back, now))

        // Archived in Gmail by hand: the card goes. Disconnect: every card of the account goes.
        gmail.mailbox["q"] = gmail.mailbox.getValue("q").copy(inInbox = false)
        gmail.nextRound = { MailChanges(fetch = listOf("q"), cursor = "h3") }
        i.syncAccount(id)
        assertTrue(MailNeedsRules.read(q, items(ops).getValue(q))!!.goneAtMs != null)
        assertTrue(i.disconnectMail("home", "gmail", "alex@example.com"))
        assertTrue(MailNeedsRules.read(p, items(ops).getValue(p))!!.goneAtMs != null)
    }

    // ---- Completeness: Delete mail data per account ----

    @Test
    fun deleteMailDataDropsWhatTheAccountPublishedAndReconnectingBringsItBack() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        gmail.mailbox["p"] = msg("p", 0).copy(fromName = "Sam Taylor", subject = "Football on Saturday")
        gmail.mailbox["n"] = newsletter("n", "editor@weekly.example", "Weekly", "<https://weekly.example/u>", oneClick = true)
        val id = connect(i)
        i.syncAccount(id)
        fun live(type: String) = EntityReader.scanning(ops).entities("home", type).filterValues { it["deleted"] != FieldValue.Bool(true) }
        val p = MailNeedsRules.itemId(id, "p")
        assertEquals(setOf(p), live(EntityTypes.MAIL_ITEM).keys)
        val senders = live(EntityTypes.MAIL_SENDER).keys
        assertTrue(senders.isNotEmpty())
        // A task he made from mail is his: never touched.
        device(ops, EntityTypes.TASK, "t1", "title", FieldValue.Text("Football kit"))

        assertTrue(i.disconnectMail("home", "gmail", "alex@example.com", deleteData = true))
        assertTrue(live(EntityTypes.MAIL_ITEM).isEmpty())
        assertTrue(live(EntityTypes.MAIL_SENDER).isEmpty())
        assertEquals(FieldValue.Bool(true), EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_ITEM).getValue(p)["deleted"])
        assertEquals(setOf("t1"), live(EntityTypes.TASK).keys)
        assertTrue(ms.messages(id).isEmpty())

        // Signing in again: the same cards and senders come back.
        connect(i)
        i.syncAccount(id)
        assertEquals(setOf(p), live(EntityTypes.MAIL_ITEM).keys)
        assertEquals(senders, live(EntityTypes.MAIL_SENDER).keys)
        assertNull(MailNeedsRules.read(p, live(EntityTypes.MAIL_ITEM).getValue(p))!!.goneAtMs)
    }

    @Test
    fun aPlainDisconnectKeepsThePublishedSenders() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        gmail.mailbox["n"] = newsletter("n", "editor@weekly.example", "Weekly", "<https://weekly.example/u>", oneClick = true)
        val id = connect(i)
        i.syncAccount(id)
        val before = EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_SENDER)
        assertTrue(before.isNotEmpty())
        assertTrue(i.disconnectMail("home", "gmail", "alex@example.com"))
        assertTrue(EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_SENDER).values.none { it["deleted"] == FieldValue.Bool(true) })
        assertTrue(ms.messages(id).isEmpty())
    }

    // ---- Drafted replies come to him ----

    @Test
    fun anEmailAskingHimSomethingGetsOneDraftOnItsCardAndNothingIsSent() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val asked = mutableListOf<os.meka.core.wire.MailDraftCodec.Request>()
        val answer = os.meka.core.wire.MailDraftCodec.Response(os.meka.core.wire.AskCodec.Response.ANSWERED, draft = "Yes, I can take them. See you at 9.")
        val (gmail, _, i) = world(ms, ops = ops, mailDrafter = MailDrafter { r -> asked += r; answer })
        gmail.mailbox["q"] = msg("q", 0).copy(fromName = "Sam Taylor", subject = "Saturday", preview = "Hi Alex, can you take the boys on Saturday? Sam")
        gmail.mailbox["f"] = msg("f", 0, from = "jo@example.com").copy(fromName = "Jo Park", subject = "Photos", preview = "Here are the photos from the weekend.")
        gmail.mailbox["u"] = msg("u", 0, from = "ali@example.com").copy(fromName = "Ali Khan", subject = "Quote", preview = "Could you send the quote?")
        val id = connect(i)
        i.syncAccount(id)
        gmail.nextRound = { MailChanges(cursor = "h2") }
        val q = MailNeedsRules.read(MailNeedsRules.itemId(id, "q"), items(ops).getValue(MailNeedsRules.itemId(id, "q")))!!
        val f = MailNeedsRules.read(MailNeedsRules.itemId(id, "f"), items(ops).getValue(MailNeedsRules.itemId(id, "f")))!!
        val u = MailNeedsRules.read(MailNeedsRules.itemId(id, "u"), items(ops).getValue(MailNeedsRules.itemId(id, "u")))!!
        assertTrue(q.replyNeeded)
        assertEquals("Can you take the boys on Saturday?", q.wants)
        assertEquals("Yes, I can take them. See you at 9.", q.autoDraft)
        assertTrue(u.replyNeeded)
        assertFalse(f.replyNeeded)
        assertNull(f.autoDraft)
        // Only the two that ask were drafted, with the card's words (never an address).
        assertEquals(setOf("Sam Taylor", "Ali Khan"), asked.map { it.from }.toSet())
        assertTrue(asked.none { it.from.contains("@") })
        // Nothing sent, nothing archived.
        assertTrue(gmail.replies.isEmpty())
        assertTrue(gmail.tidyCalls.isEmpty())
        // Later checks never ask again.
        now += 300_000
        i.syncAccount(id)
        assertEquals(2, asked.size)
        assertTrue(gmail.replies.isEmpty())
        // The draft goes only on his Send (the reply path's hold).
        MailNeedsRules.replyFields(q.autoDraft!!, now).forEach { (k, v) -> device(ops, EntityTypes.MAIL_ITEM, q.id, k, v) }
        now += MailNeedsRules.REPLY_HOLD_MS + 1
        i.syncAccount(id)
        assertEquals(listOf("q" to "Yes, I can take them. See you at 9."), gmail.replies)
    }

    @Test
    fun anUnusableOrRefusedDraftIsTriedOnceAndAiOffAsksAgainLater() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        var calls = 0
        var answer = os.meka.core.wire.MailDraftCodec.Response(os.meka.core.wire.AskCodec.Response.OFF)
        val (gmail, _, i) = world(ms, ops = ops, mailDrafter = MailDrafter { calls++; answer })
        gmail.mailbox["q"] = msg("q", 0).copy(fromName = "Sam Taylor", subject = "Saturday?", preview = "Are you free?")
        val id = connect(i)
        i.syncAccount(id)
        gmail.nextRound = { MailChanges(cursor = "h2") }
        val qid = MailNeedsRules.itemId(id, "q")
        assertEquals(1, calls)
        assertNull(MailNeedsRules.read(qid, items(ops).getValue(qid))!!.autoDraftTriedAtMs)
        // AI back: an answer with a link is dropped whole and recorded as tried.
        answer = os.meka.core.wire.MailDraftCodec.Response(os.meka.core.wire.AskCodec.Response.ANSWERED, draft = "Yes, see https://example.com")
        now += 300_000
        i.syncAccount(id)
        assertEquals(2, calls)
        val q = MailNeedsRules.read(qid, items(ops).getValue(qid))!!
        assertNull(q.autoDraft)
        assertNotNull(q.autoDraftTriedAtMs)
        now += 300_000
        i.syncAccount(id)
        assertEquals(2, calls)
    }

    // ---- Slice 6b-2a: Reply ----

    @Test
    fun aReplyIsSentOnceAfterTheHoldThenTheEmailIsArchived() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        gmail.mailbox["p"] = msg("p", 0).copy(fromName = "Sam Taylor", subject = "Football on Saturday")
        gmail.mailbox["r"] = msg("r", 0).copy(fromName = "Alex Reed", subject = "Quote")
        val id = connect(i)
        i.syncAccount(id)
        val p = MailNeedsRules.itemId(id, "p")
        val r = MailNeedsRules.itemId(id, "r")
        gmail.nextRound = { MailChanges(cursor = "h2") }

        // Send on the Fold: inside the hold nothing is sent or archived.
        now += 60_000
        MailNeedsRules.replyFields("Yes, I can take them.", now).forEach { (f, v) -> device(ops, EntityTypes.MAIL_ITEM, p, f, v) }
        now += 5_000
        i.syncAccount(id)
        assertTrue(gmail.replies.isEmpty())
        assertTrue(gmail.tidyCalls.isEmpty())
        // After it: sent once from this account, then archived.
        now += MailNeedsRules.REPLY_HOLD_MS
        i.syncAccount(id)
        assertEquals(listOf("p" to "Yes, I can take them."), gmail.replies)
        assertEquals(mapOf("p" to MailTidy.ARCHIVE), gmail.tidyCalls.single())
        val sent = MailNeedsRules.read(p, items(ops).getValue(p))!!
        assertTrue(MailNeedsRules.replySent(sent) && sent.doneAtMs != null)
        i.syncAccount(id)
        assertEquals(1, gmail.replies.size)

        // Undo inside the hold: never sent.
        MailNeedsRules.replyFields("Can't, sorry", now).forEach { (f, v) -> device(ops, EntityTypes.MAIL_ITEM, r, f, v) }
        now += 1_000
        device(ops, EntityTypes.MAIL_ITEM, r, MailItemFields.UNDO_ASKED_AT, FieldValue.Int64(now))
        now += MailNeedsRules.REPLY_HOLD_MS * 3
        i.syncAccount(id)
        assertEquals(1, gmail.replies.size)
        assertTrue(gmail.mailbox.getValue("r").inInbox)

        // Gmail refuses it: the card comes back with his words, not archived, not retried.
        now += 1_000
        MailNeedsRules.replyFields("Can't, sorry", now).forEach { (f, v) -> device(ops, EntityTypes.MAIL_ITEM, r, f, v) }
        gmail.replyAnswer = { MailReplyResult.FAILED }
        now += MailNeedsRules.REPLY_HOLD_MS
        i.syncAccount(id)
        val failed = MailNeedsRules.read(r, items(ops).getValue(r))!!
        assertTrue(MailNeedsRules.replyFailed(failed))
        assertEquals("Gmail didn't take it", failed.replyFailedWhy)
        assertTrue(MailNeedsRules.showing(failed, now))
        assertTrue(gmail.mailbox.getValue("r").inInbox)
        // Unreachable: the same, said so.
        gmail.replyAnswer = { error("Gmail 503") }
        now += 1_000
        MailNeedsRules.replyFields("Can't, sorry", now).forEach { (f, v) -> device(ops, EntityTypes.MAIL_ITEM, r, f, v) }
        now += MailNeedsRules.REPLY_HOLD_MS
        i.syncAccount(id)
        assertEquals("couldn't reach Gmail", MailNeedsRules.read(r, items(ops).getValue(r))!!.replyFailedWhy)
        assertEquals(1, gmail.replies.size)
        // The reply's words are never in the server's published fields beyond the device's own write.
        assertFalse(items(ops).getValue(p).filterKeys { it != MailItemFields.REPLY_TEXT }.values.any { it == FieldValue.Text("Yes, I can take them.") })
    }

    // ---- Slice 6b-3: Waiting for's chase ----

    @Test
    fun aChaseIsSentOnceInTheThreadAfterTheHoldAndTheEmailStaysArchived() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        gmail.mailbox["p"] = msg("p", 0).copy(fromName = "Sam Taylor", subject = "The quote")
        val id = connect(i)
        i.syncAccount(id)
        val p = MailNeedsRules.itemId(id, "p")
        gmail.nextRound = { MailChanges(cursor = "h2") }
        // Waiting for on the Fold: a Done, so the server archives it.
        now += 60_000
        MailNeedsRules.waitingFields(MailNeedsRules.waitingId(p), now).forEach { (f, v) -> device(ops, EntityTypes.MAIL_ITEM, p, f, v) }
        i.syncAccount(id)
        assertEquals(1, gmail.tidyCalls.size)
        // Three days on he sends the chase: nothing inside the hold, then once, in the thread; nothing else moves.
        now += 3 * 86_400_000L
        MailChaseRules.sendFields("Hi Sam, any news?", now).forEach { (f, v) -> device(ops, EntityTypes.MAIL_ITEM, p, f, v) }
        now += 5_000
        i.syncAccount(id)
        assertTrue(gmail.replies.isEmpty())
        now += MailNeedsRules.REPLY_HOLD_MS
        i.syncAccount(id)
        assertEquals(listOf("p" to "Hi Sam, any news?"), gmail.replies)
        assertEquals(1, gmail.tidyCalls.size)
        assertFalse(gmail.mailbox.getValue("p").inInbox)
        val sent = MailNeedsRules.read(p, items(ops).getValue(p))!!
        assertTrue(MailChaseRules.sent(sent))
        assertTrue(MailNeedsRules.done(sent))
        i.syncAccount(id)
        assertEquals(1, gmail.replies.size)
        // Gmail refuses the next one: said so, not retried.
        gmail.replyAnswer = { MailReplyResult.GONE }
        now += 1_000
        MailChaseRules.sendFields("Any news?", now).forEach { (f, v) -> device(ops, EntityTypes.MAIL_ITEM, p, f, v) }
        now += MailNeedsRules.REPLY_HOLD_MS
        i.syncAccount(id)
        i.syncAccount(id)
        val failed = MailNeedsRules.read(p, items(ops).getValue(p))!!
        assertTrue(MailChaseRules.failed(failed))
        assertEquals(MailChaseRules.WHY_GONE, failed.chaseFailedWhy)
        assertEquals(1, gmail.replies.size)
    }

    @Test
    fun theReplyThreadsAndKeepsHeadersClean() {
        assertEquals("Re: Football on Saturday", MailReplyMessage.subject("Football on Saturday"))
        assertEquals("RE: Quote", MailReplyMessage.subject("RE: Quote"))
        assertEquals("Re:", MailReplyMessage.subject(null))
        val raw = MailReplyMessage.rfc822("sam@example.com", "Re: Café\r\nX: y", "<a1@mail.example>", "<a0@mail.example>", "Yes,\nI can take them.")
        val head = raw.substringBefore("\r\n\r\n").split("\r\n")
        assertEquals("To: sam@example.com", head[0])
        assertTrue(MailReplyMessage.validAddress("sam@example.com"))
        assertFalse(MailReplyMessage.validAddress("sam@example.com\r\nBcc: x@evil.example"))
        assertFalse(MailReplyMessage.validAddress("a@b, c@d"))
        assertFalse(MailReplyMessage.validAddress("nobody"))
        assertTrue(head[1].startsWith("Subject: =?UTF-8?B?"))
        assertEquals("In-Reply-To: <a1@mail.example>", head[2])
        assertEquals("References: <a0@mail.example> <a1@mail.example>", head[3])
        assertTrue(head.none { it.startsWith("X:") })
        val body = String(java.util.Base64.getMimeDecoder().decode(raw.substringAfter("\r\n\r\n").trim()))
        assertEquals("Yes,\r\nI can take them.", body)
        // No Message-ID on the original: no threading headers, still a valid email.
        assertFalse("In-Reply-To" in MailReplyMessage.rfc822("sam@example.com", "Re: x", null, null, "ok"))
    }

    private fun senders(ops: InMemoryServerOpStore) = EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_SENDER)
    private fun newsletter(id: String, from: String, name: String?, unsub: String?, oneClick: Boolean = false, ageDays: Long = 0) =
        msg(id, ageDays, from).copy(fromName = name, listId = "<$from>", listUnsubscribe = unsub, oneClickUnsubscribe = oneClick)

    @Test
    fun newsletterSendersArePublishedAndUnsubscribeIsDoneOnlyWhenAsked() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val posted = mutableListOf<String>()
        val (gmail, _, i) = world(ms, ops = ops, unsubscriber = { url -> posted += url; true })
        gmail.mailbox["a1"] = newsletter("a1", "news@digest.example", "Weekly Digest", "<mailto:leave@digest.example>, <https://digest.example/u/1>", oneClick = true, ageDays = 2)
        gmail.mailbox["a2"] = newsletter("a2", "news@digest.example", "Weekly Digest", "<mailto:leave@digest.example>, <https://digest.example/u/2>", oneClick = true, ageDays = 1)
        gmail.mailbox["b1"] = newsletter("b1", "hello@club.example", null, "<mailto:off@club.example?subject=stop>")
        gmail.mailbox["c1"] = newsletter("c1", "info@page.example", "Page News", "<https://page.example/prefs>")
        gmail.mailbox["d1"] = newsletter("d1", "x@nowayout.example", "No Way Out", null)
        gmail.mailbox["p1"] = msg("p1", 0) // a person: never a sender here
        val id = connect(i)
        i.syncAccount(id)
        val pub = senders(ops)
        // No way out isn't published (nothing to offer); a person never is.
        assertEquals(3, pub.size)
        val digest = pub.getValue(MailUnsubscribeRules.senderId(id, "news@digest.example"))
        assertEquals(FieldValue.Text("Weekly Digest"), digest[MailSenderFields.NAME])
        assertEquals(FieldValue.Text("digest.example"), digest[MailSenderFields.DOMAIN])
        assertEquals(FieldValue.Text("Personal"), digest[MailSenderFields.ACCOUNT])
        assertEquals(FieldValue.Text(UnsubscribeMethod.ONE_CLICK.name), digest[MailSenderFields.METHOD])
        assertEquals(FieldValue.Int64(2), digest[MailSenderFields.COUNT])
        assertEquals(FieldValue.Int64(now - day), digest[MailSenderFields.LAST_AT])
        assertEquals(FieldValue.Text("club.example"), pub.getValue(MailUnsubscribeRules.senderId(id, "hello@club.example"))[MailSenderFields.NAME])
        // Nothing is done without a tap; no header or email id is in the synced data.
        assertTrue(posted.isEmpty() && gmail.sent.isEmpty())
        assertTrue(pub.values.flatMap { it.values }.none { v -> (v as? FieldValue.Text)?.value?.let { "u/2" in it || "a2" == it || "leave@" in it } == true })

        // He taps Unsubscribe on all three.
        now += 60_000
        for (from in listOf("news@digest.example", "hello@club.example", "info@page.example")) {
            device(ops, EntityTypes.MAIL_SENDER, MailUnsubscribeRules.senderId(id, from), MailSenderFields.ASKED_AT, FieldValue.Int64(now))
        }
        now += 60_000
        gmail.nextRound = { MailChanges(cursor = "h2") }
        i.syncAccount(id)
        // One-click to the newest email's link; the mailto from this account; the page handed back.
        assertEquals(listOf("https://digest.example/u/2"), posted)
        assertEquals(listOf(UnsubscribeMailto("off@club.example", "stop", "unsubscribe")), gmail.sent)
        val after = senders(ops)
        assertEquals(FieldValue.Text(UnsubscribeState.DONE.name), after.getValue(MailUnsubscribeRules.senderId(id, "news@digest.example"))[MailSenderFields.STATE])
        assertEquals(FieldValue.Text(UnsubscribeState.SENT.name), after.getValue(MailUnsubscribeRules.senderId(id, "hello@club.example"))[MailSenderFields.STATE])
        val page = after.getValue(MailUnsubscribeRules.senderId(id, "info@page.example"))
        assertEquals(FieldValue.Text(UnsubscribeState.OPEN_LINK.name), page[MailSenderFields.STATE])
        assertEquals(FieldValue.Text("https://page.example/prefs"), page[MailSenderFields.LINK])
        assertEquals(FieldValue.Int64(now), page[MailSenderFields.TRIED_AT])
        // Done once: the next check does nothing more.
        now += 60_000
        i.syncAccount(id)
        assertEquals(1, posted.size)
        assertEquals(1, gmail.sent.size)
    }

    // ---- Slice 5c: not opened in 30 days ----

    @Test
    fun eachSendersNotOpenedCountIsPublishedAndRefreshedOnlyWhenItChanges() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        // Three unread from the digest (one in capitals), two from the club with one read.
        gmail.mailbox["a1"] = newsletter("a1", "news@digest.example", "Weekly Digest", "<https://digest.example/u>", oneClick = true, ageDays = 3)
        gmail.mailbox["a2"] = newsletter("a2", "news@digest.example", "Weekly Digest", "<https://digest.example/u>", oneClick = true, ageDays = 2)
        gmail.mailbox["a3"] = newsletter("a3", "News@Digest.example", "Weekly Digest", "<https://digest.example/u>", oneClick = true, ageDays = 1)
        gmail.mailbox["b1"] = newsletter("b1", "hello@club.example", null, "<mailto:off@club.example>", ageDays = 2)
        gmail.mailbox["b2"] = newsletter("b2", "hello@club.example", null, "<mailto:off@club.example>", ageDays = 1).copy(unread = false)
        val id = connect(i)
        i.syncAccount(id)
        val digestId = MailUnsubscribeRules.senderId(id, "news@digest.example")
        val clubId = MailUnsubscribeRules.senderId(id, "hello@club.example")
        val pub = senders(ops)
        assertEquals(FieldValue.Int64(3), pub.getValue(digestId)[MailSenderFields.UNOPENED])
        assertEquals(FieldValue.Int64(now), pub.getValue(digestId)[MailSenderFields.UNOPENED_AT])
        assertEquals(FieldValue.Int64(0), pub.getValue(clubId)[MailSenderFields.UNOPENED])
        // A check an hour later with nothing new leaves the count (and its time) alone.
        val first = now
        now += 3_600_000
        gmail.nextRound = { MailChanges(cursor = "h2") }
        i.syncAccount(id)
        assertEquals(FieldValue.Int64(first), senders(ops).getValue(digestId)[MailSenderFields.UNOPENED_AT])
        // He opens one of the digest's: the next check publishes 0.
        now += 60_000
        gmail.mailbox["a3"] = gmail.mailbox.getValue("a3").copy(unread = false)
        gmail.nextRound = { MailChanges(fetch = listOf("a3"), cursor = "h3") }
        i.syncAccount(id)
        assertEquals(FieldValue.Int64(0), senders(ops).getValue(digestId)[MailSenderFields.UNOPENED])
        assertEquals(FieldValue.Int64(now), senders(ops).getValue(digestId)[MailSenderFields.UNOPENED_AT])
    }

    @Test
    fun anUnsubscribedSendersLaterNewslettersGoToTrashWithUndo() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops, unsubscriber = { true })
        gmail.mailbox["a1"] = newsletter("a1", "news@digest.example", "Weekly Digest", "<https://digest.example/u/1>", oneClick = true, ageDays = 1)
        val id = connect(i)
        i.syncAccount(id)
        now += day + 1
        autopilotOn(ops)
        val sender = MailUnsubscribeRules.senderId(id, "news@digest.example")
        // Not unsubscribed yet: its next newsletter is archived as usual.
        now += 60_000
        gmail.mailbox["a2"] = newsletter("a2", "news@digest.example", "Weekly Digest", "<https://digest.example/u/2>", oneClick = true)
        gmail.nextRound = { MailChanges(fetch = listOf("a2"), cursor = "h2") }
        i.syncAccount(id)
        assertEquals(mapOf("a2" to MailTidy.NEWSLETTER), gmail.tidyCalls.single())
        // He taps Unsubscribe; the one-click goes through.
        now += 60_000
        device(ops, EntityTypes.MAIL_SENDER, sender, MailSenderFields.ASKED_AT, FieldValue.Int64(now))
        now += 60_000
        gmail.nextRound = { MailChanges(cursor = "h3") }
        i.syncAccount(id)
        assertEquals(FieldValue.Text(UnsubscribeState.DONE.name), senders(ops).getValue(sender)[MailSenderFields.STATE])
        // Later: its newsletter goes to Trash; a receipt from the same address and another list are tidied as usual.
        now += day
        gmail.mailbox["a3"] = newsletter("a3", "news@digest.example", "Weekly Digest", "<https://digest.example/u/3>", oneClick = true)
        gmail.mailbox["r3"] = msg("r3", 0, "news@digest.example").copy(subject = "Your order has shipped", listId = "<news@digest.example>")
        gmail.mailbox["o3"] = newsletter("o3", "hello@other.example", "Other", "<https://other.example/u>")
        gmail.nextRound = { MailChanges(fetch = listOf("a3", "r3", "o3"), cursor = "h4") }
        i.syncAccount(id)
        assertEquals(mapOf("a3" to MailTidy.TRASH, "r3" to MailTidy.RECEIPT, "o3" to MailTidy.NEWSLETTER), gmail.tidyCalls.last())
        val batch = tidies(ops).entries.maxBy { (it.value[MailTidyBatchFields.AT] as FieldValue.Int64).value }
        assertEquals(FieldValue.Int64(1), batch.value[MailTidyBatchFields.TRASHED])
        assertTrue((batch.value[MailTidyBatchFields.DETAIL] as FieldValue.Text).value.contains("1 from an unsubscribed sender → Trash"))
        assertEquals(FieldValue.Int64(1), senders(ops).getValue(sender)[MailSenderFields.TRASHED])
        // Undo puts it back, and it is never moved again.
        now += 60_000
        device(ops, EntityTypes.MAIL_TIDY, batch.key, MailTidyBatchFields.UNDO_ASKED_AT, FieldValue.Int64(now))
        gmail.nextRound = { MailChanges(cursor = "h5") }
        i.syncAccount(id)
        assertTrue(gmail.mailbox.getValue("a3").inInbox)
        val calls = gmail.tidyCalls.size
        gmail.nextRound = { MailChanges(fetch = listOf("a3"), cursor = "h6") }
        i.syncAccount(id)
        assertEquals(calls, gmail.tidyCalls.size)
    }

    @Test
    fun aFailedOneClickFallsBackToTheMailtoAndAnUnreachableOneCanBeTriedAgain() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops, unsubscriber = { false })
        gmail.mailbox["a1"] = newsletter("a1", "news@digest.example", "Weekly Digest", "<mailto:leave@digest.example>, <https://digest.example/u/1>", oneClick = true)
        gmail.mailbox["b1"] = newsletter("b1", "hello@club.example", "Club", "<mailto:off@club.example>")
        val id = connect(i)
        i.syncAccount(id)
        val digest = MailUnsubscribeRules.senderId(id, "news@digest.example")
        val club = MailUnsubscribeRules.senderId(id, "hello@club.example")
        now += 60_000
        device(ops, EntityTypes.MAIL_SENDER, digest, MailSenderFields.ASKED_AT, FieldValue.Int64(now))
        device(ops, EntityTypes.MAIL_SENDER, club, MailSenderFields.ASKED_AT, FieldValue.Int64(now))
        // Sending fails for the club (the digest's one-click fails too, then its mailto fails the same way).
        gmail.sendFails = true
        now += 60_000
        gmail.nextRound = { MailChanges(cursor = "h2") }
        i.syncAccount(id)
        assertEquals(FieldValue.Text(UnsubscribeState.OPEN_LINK.name), senders(ops).getValue(digest)[MailSenderFields.STATE])
        assertEquals(FieldValue.Text(UnsubscribeState.RETRY.name), senders(ops).getValue(club)[MailSenderFields.STATE])
        // Asked again: tried again, and this time it goes.
        gmail.sendFails = false
        now += 60_000
        device(ops, EntityTypes.MAIL_SENDER, club, MailSenderFields.ASKED_AT, FieldValue.Int64(now))
        now += 60_000
        i.syncAccount(id)
        assertEquals(FieldValue.Text(UnsubscribeState.SENT.name), senders(ops).getValue(club)[MailSenderFields.STATE])
        assertEquals(listOf("off@club.example"), gmail.sent.map { it.to })
    }

    @Test
    fun outlooksListHeadersAreReadFromTheNewestEmail() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        // The fake signs in through Google's client; only its id makes it Outlook here.
        val outlook = FakeGmail(id = MailProviders.OUTLOOK)
        val store = InMemoryIntegrationStore()
        val i = Integrations(
            store, ops, emptyMap(), { p -> if (p == "google") OAuthClient("cid", "s") else null },
            Cipher(), "https://meka.example", { now }, mail = mapOf(MailProviders.OUTLOOK to outlook), mailStore = ms,
        )
        // Graph's feed has no list headers: sorted a newsletter by Gmail-free rules (a no-reply list sender with a List-Id here).
        outlook.mailbox["o1"] = msg("o1", 0, "news@shop.example").copy(fromName = "Shop", listId = "<shop.example>")
        outlook.outlookHeaders["o1"] = "<mailto:leave@shop.example>" to false
        val url = assertIs<Integrations.StartResult.Url>(i.start("home", MailProviders.OUTLOOK)).url
        val state = URI(url).query.split("&").first { it.startsWith("state=") }.removePrefix("state=")
        val id = assertIs<Integrations.CallbackResult.Connected>(i.callback("google", state, "code", null)).accountId
        i.syncAccount(id)
        assertEquals(listOf("o1"), outlook.headerReads)
        val f = senders(ops).getValue(MailUnsubscribeRules.senderId(id, "news@shop.example"))
        assertEquals(FieldValue.Text(UnsubscribeMethod.MAILTO.name), f[MailSenderFields.METHOD])
        // Read once.
        outlook.nextRound = { MailChanges(cursor = "h2") }
        i.syncAccount(id)
        assertEquals(1, outlook.headerReads.size)
    }

    @Test
    fun oneClickOnlyPostsToPublicHosts() {
        val private = HttpOneClickUnsubscriber(resolve = { listOf(java.net.InetAddress.getByName("10.1.2.3")) })
        assertFalse(private.post("https://lists.example/u"))
        assertFalse(HttpOneClickUnsubscriber(resolve = { emptyList() }).post("https://lists.example/u"))
        assertFalse(HttpOneClickUnsubscriber().post("http://lists.example/u"))
        for (a in listOf("127.0.0.1", "10.0.0.1", "172.16.5.4", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:10.0.0.1")) {
            assertFalse(HttpOneClickUnsubscriber.isPublic(java.net.InetAddress.getByName(a)), a)
        }
        for (a in listOf("93.184.216.34", "8.8.8.8", "2606:4700::1111")) assertTrue(HttpOneClickUnsubscriber.isPublic(java.net.InetAddress.getByName(a)), a)
    }

    @Test
    fun theUnsubscribeEmailIsPlainText() {
        assertEquals(
            "To: leave@digest.example\r\nSubject: unsubscribe\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nunsubscribe\r\n",
            UnsubscribeMessage.rfc822(UnsubscribeMailto("leave@digest.example", "unsubscribe", "unsubscribe")),
        )
        assertTrue(UnsubscribeMessage.rfc822(UnsubscribeMailto("a@b.example", "désabonner", "x")).contains("Subject: =?UTF-8?B?"))
    }

    // ---- Slice 8a: the backlog clean-up's plan ----

    private fun backlogs(ops: InMemoryServerOpStore) = EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_BACKLOG)

    @Test
    fun theBacklogIsPlannedAPageAtATimeAndNothingMoves() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        gmail.mailbox["n1"] = msg("n1", 45, "editor@weekly.example").copy(listId = "<weekly.example>")
        gmail.mailbox["r1"] = msg("r1", 40, "auto-confirm@shop.example").copy(subject = "Your order has shipped")
        gmail.mailbox["p1"] = msg("p1", 50) // a person: kept
        gmail.mailbox["n2"] = msg("n2", 5, "editor@weekly.example").copy(listId = "<weekly.example>") // under a week: kept
        gmail.mailbox["b1"] = msg("b1", 60, "alerts@barclays.co.uk").copy(listId = "<alerts.barclays.co.uk>") // a bank: kept
        gmail.mailbox["t1"] = msg("t1", 45, "editor@weekly.example").copy(listId = "<weekly.example>") // his thread: kept
        gmail.mailbox["a1"] = msg("a1", 45, "editor@weekly.example").copy(listId = "<weekly.example>", inInbox = false) // archived since
        gmail.sentThreads += "t-t1"
        gmail.backlogPages = mapOf(
            null to MailBacklogPage(ids = listOf("n1", "r1", "p1"), next = "pg2"),
            "pg2" to MailBacklogPage(ids = listOf("n2", "b1", "t1", "a1", "gone"), next = null),
        )
        gmail.nextRound = { MailChanges(cursor = "h1") }
        val id = connect(i)
        i.syncAccount(id)
        // The first check: one page listed (before now: the dry-run day hasn't ended) and its headers read.
        assertEquals(listOf(now to null as String?), gmail.backlogAsked)
        val entityId = MailBacklogRules.entityId(id)
        val first = MailBacklogRules.read(entityId, backlogs(ops).getValue(entityId))!!
        assertFalse(first.ready)
        assertEquals(3, first.scanned)
        assertEquals("Personal", first.account)
        // The second: the rest, then his threads taken out: the plan is ready.
        now += 300_000
        i.syncAccount(id)
        assertEquals(listOf(null, "pg2"), gmail.backlogAsked.map { it.second })
        val b = MailBacklogRules.read(entityId, backlogs(ops).getValue(entityId))!!
        assertTrue(b.ready)
        assertEquals(6, b.scanned)
        assertEquals(mapOf(MailBacklogKind.NEWSLETTER to 1, MailBacklogKind.RECEIPT to 1, MailBacklogKind.KEEP to 4), b.counts)
        assertEquals("shop.example 1 · weekly.example 1", b.top)
        assertEquals("Archive 2 · keep 4 that may need you", MailBacklogRules.row(b).line)
        // Nothing moved, and the next check lists nothing more.
        assertTrue(gmail.tidyCalls.isEmpty())
        assertTrue(gmail.mailbox.values.filter { it.id != "a1" }.all { it.inInbox })
        now += 300_000
        i.syncAccount(id)
        assertEquals(2, gmail.backlogAsked.size)
        // Disconnect forgets the scan.
        assertNotNull(ms.backlogScan(id))
        ms.forget(id)
        assertNull(ms.backlogScan(id))
    }

    @Test
    fun theBacklogWaitsWhenItCantTellWhichThreadsAreHis() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        gmail.mailbox["n1"] = msg("n1", 45, "editor@weekly.example").copy(listId = "<weekly.example>")
        gmail.backlogPages = mapOf(null to MailBacklogPage(ids = listOf("n1"), next = null))
        gmail.nextRound = { MailChanges(cursor = "h1") }
        gmail.repliedFails = true
        val id = connect(i)
        i.syncAccount(id)
        val entityId = MailBacklogRules.entityId(id)
        assertFalse(MailBacklogRules.read(entityId, backlogs(ops).getValue(entityId))!!.ready)
        gmail.repliedFails = false
        now += 300_000
        i.syncAccount(id)
        val b = MailBacklogRules.read(entityId, backlogs(ops).getValue(entityId))!!
        assertTrue(b.ready)
        assertEquals(mapOf(MailBacklogKind.NEWSLETTER to 1), b.counts)
        // A provider that can't list its backlog publishes nothing.
        val (other, _, j) = world(InMemoryMailStore(), ops = InMemoryServerOpStore())
        assertNull(other.backlogPages)
        j.syncAccount(connect(j))
    }

    @Test
    fun aPlanMadeByOlderRulesIsStartedAgainUnlessApproved() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, store, i) = world(ms, ops = ops)
        gmail.mailbox["n1"] = msg("n1", 10, "editor@weekly.example").copy(listId = "<weekly.example>")
        gmail.mailbox["p1"] = msg("p1", 50)
        gmail.backlogPages = mapOf(null to MailBacklogPage(ids = listOf("n1", "p1"), next = null))
        gmail.nextRound = { MailChanges(cursor = "h1") }
        val id = connect(i)
        val entityId = MailBacklogRules.entityId(id)
        fun plan() = MailBacklogRules.read(entityId, backlogs(ops).getValue(entityId))!!
        i.syncAccount(id); now += 300_000
        assertTrue(plan().ready)
        assertEquals(MailBacklogRules.PLAN_VERSION, plan().planVersion)
        // 10 days old: archived under the week rule.
        assertEquals(mapOf(MailBacklogKind.NEWSLETTER to 1, MailBacklogKind.KEEP to 1), plan().counts)
        i.syncAccount(id); now += 300_000
        assertEquals(1, gmail.backlogAsked.size)
        // A device writing a version changes nothing: the server knows what it wrote.
        device(ops, EntityTypes.MAIL_BACKLOG, entityId, MailBacklogFields.PLAN_VERSION, FieldValue.Int64(1))
        now += 1
        i.syncAccount(id); now += 300_000
        assertEquals(1, gmail.backlogAsked.size)
        // A plan the older rules wrote (no version, before this server): the next check starts again from the listing.
        fun forgetVersion() {
            val row = store.mirror("home", id).getValue(entityId)
            store.putMirror("home", row.copy(fieldOps = row.fieldOps - MailBacklogFields.PLAN_VERSION))
        }
        forgetVersion()
        now += 1
        i.syncAccount(id); now += 300_000
        assertEquals(2, gmail.backlogAsked.size)
        assertEquals(MailBacklogRules.PLAN_VERSION, plan().planVersion)
        assertTrue(plan().ready)
        assertEquals(mapOf(MailBacklogKind.NEWSLETTER to 1, MailBacklogKind.KEEP to 1), plan().counts)
        // Approved: an old version is left as it is.
        device(ops, EntityTypes.MAIL_BACKLOG, entityId, MailBacklogFields.APPROVED_AT, FieldValue.Int64(now))
        device(ops, EntityTypes.MAIL_BACKLOG, entityId, MailBacklogFields.PLAN_VERSION, FieldValue.Int64(1))
        forgetVersion()
        now += 1
        i.syncAccount(id); now += 300_000
        assertEquals(2, gmail.backlogAsked.size)
        assertEquals(listOf("n1"), gmail.tidyCalls.single().keys.toList())
    }

    @Test
    fun outlooksBacklogReadsEachSendersHeadersOnce() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val outlook = FakeGmail(id = MailProviders.OUTLOOK)
        val i = Integrations(
            InMemoryIntegrationStore(), ops, emptyMap(), { p -> if (p == "google") OAuthClient("cid", "s") else null },
            Cipher(), "https://meka.example", { now }, mail = mapOf(MailProviders.OUTLOOK to outlook), mailStore = ms,
        )
        val olds = listOf(
            msg("a1", 20, "hello@shop.example"), msg("a2", 30, "hello@shop.example"), msg("a3", 40, "hello@shop.example"),
            msg("s1", 20), // a person (no list headers): kept
        )
        olds.forEach { outlook.mailbox[it.id] = it }
        outlook.bulk["a1"] = MailBulkHeaders("<mailto:leave@shop.example>", false, null, false)
        outlook.backlogPages = mapOf(null to MailBacklogPage(headers = olds, next = null))
        outlook.nextRound = { MailChanges(cursor = "h1") }
        val url = assertIs<Integrations.StartResult.Url>(i.start("home", MailProviders.OUTLOOK)).url
        val state = URI(url).query.split("&").first { it.startsWith("state=") }.removePrefix("state=")
        val id = assertIs<Integrations.CallbackResult.Connected>(i.callback("google", state, "code", null)).accountId
        i.syncAccount(id)
        val entityId = MailBacklogRules.entityId(id)
        val b = MailBacklogRules.read(entityId, backlogs(ops).getValue(entityId))!!
        assertTrue(b.ready)
        // The shop's three emails are one sender: its headers read once (from the first), all three newsletters.
        assertEquals(mapOf(MailBacklogKind.NEWSLETTER to 3, MailBacklogKind.KEEP to 1), b.counts)
        assertEquals(2, outlook.bulkReads.size)
        assertTrue("a1" in outlook.bulkReads && "s1" in outlook.bulkReads)
    }

    // ---- Slice 8b: Approve, applied a few hundred a check, Undo ----

    @Test
    fun anApprovedBacklogIsAppliedInBatchesAsOneTidyAndUndoPutsItAllBack() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val ids = (1..600).map { "n$it" }
        ids.forEach { gmail.mailbox[it] = msg(it, 45, "editor@weekly.example").copy(listId = "<weekly.example>") }
        gmail.mailbox["p1"] = msg("p1", 50) // a person: kept
        gmail.backlogPages = mapOf(null to MailBacklogPage(ids = ids + "p1", next = null))
        gmail.nextRound = { MailChanges(cursor = "h1") }
        val id = connect(i)
        val entityId = MailBacklogRules.entityId(id)
        fun plan() = MailBacklogRules.read(entityId, backlogs(ops).getValue(entityId))!!
        repeat(3) { i.syncAccount(id); now += 300_000 }
        assertTrue(plan().ready)
        assertEquals(600, plan().toMove)
        // Not approved: nothing moves, however many checks.
        i.syncAccount(id); now += 300_000
        assertTrue(gmail.tidyCalls.isEmpty())
        // Approve (from the Fold): the next check moves 500, the one after the last 100.
        device(ops, EntityTypes.MAIL_BACKLOG, entityId, MailBacklogFields.APPROVED_AT, FieldValue.Int64(now))
        i.syncAccount(id); now += 300_000
        assertEquals(500, gmail.tidyCalls.single().size)
        assertEquals(500, plan().applied)
        assertNull(plan().finishedAtMs)
        val batchId = MailBacklogRules.tidyBatchId(id)
        val batch = MailAutopilotRules.readBatch(batchId, tidies(ops).getValue(batchId))!!
        assertTrue(batch.backlog)
        assertEquals(500, batch.count)
        i.syncAccount(id); now += 300_000
        assertEquals(listOf(500, 100), gmail.tidyCalls.map { it.size })
        assertEquals(600, plan().applied)
        assertNotNull(plan().finishedAtMs)
        assertEquals("Backlog clean-up · 600 older emails · Personal", MailAutopilotRules.readBatch(batchId, tidies(ops).getValue(batchId))!!.summary)
        assertTrue(ids.none { gmail.mailbox.getValue(it).inInbox })
        assertTrue(gmail.mailbox.getValue("p1").inInbox)
        // Finished: nothing more moves.
        i.syncAccount(id); now += 300_000
        assertEquals(2, gmail.tidyCalls.size)
        // Undo: 300 put back a check, then the tidy says so; never tidied again.
        device(ops, EntityTypes.MAIL_TIDY, batchId, MailTidyBatchFields.UNDO_ASKED_AT, FieldValue.Int64(now))
        i.syncAccount(id); now += 300_000
        assertEquals(300, ids.count { gmail.mailbox.getValue(it).inInbox })
        assertEquals(300, plan().putBack)
        assertNull(MailAutopilotRules.readBatch(batchId, tidies(ops).getValue(batchId))!!.undoneAtMs)
        i.syncAccount(id); now += 300_000
        assertTrue(ids.all { gmail.mailbox.getValue(it).inInbox })
        val undone = MailAutopilotRules.readBatch(batchId, tidies(ops).getValue(batchId))!!
        assertNotNull(undone.undoneAtMs)
        assertEquals("Put back 600 emails in the inbox", undone.undoNote)
        assertTrue(ms.neverTidied(id).containsAll(ids))
        i.syncAccount(id)
        assertEquals(2, gmail.tidyCalls.size)
    }

    @Test
    fun theKillSwitchHoldsAnApprovedCleanUp() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        gmail.mailbox["n1"] = msg("n1", 45, "editor@weekly.example").copy(listId = "<weekly.example>")
        gmail.backlogPages = mapOf(null to MailBacklogPage(ids = listOf("n1", "gone"), next = null))
        gmail.nextRound = { MailChanges(cursor = "h1") }
        val id = connect(i)
        val entityId = MailBacklogRules.entityId(id)
        repeat(2) { i.syncAccount(id); now += 300_000 }
        device(ops, EntityTypes.MAIL_BACKLOG, entityId, MailBacklogFields.APPROVED_AT, FieldValue.Int64(now))
        device(ops, EntityTypes.CONTEXT_MODE, AutonomyRules.ENTITY_ID, AutonomyFields.PAUSED, FieldValue.Bool(true))
        i.syncAccount(id); now += 300_000
        assertTrue(gmail.tidyCalls.isEmpty())
        device(ops, EntityTypes.CONTEXT_MODE, AutonomyRules.ENTITY_ID, AutonomyFields.PAUSED, FieldValue.Bool(false))
        i.syncAccount(id)
        assertEquals(mapOf("n1" to MailTidy.NEWSLETTER), gmail.tidyCalls.single())
        assertNotNull(MailBacklogRules.read(entityId, backlogs(ops).getValue(entityId))!!.finishedAtMs)
    }

    // ---- On real Postgres (MEKA_TEST_DB_URL, CI); skipped when unset ----

    @Test
    fun thePostgresStoreKeepsHeadersPendingRowsAndTheCursor() {
        val url = System.getenv("MEKA_TEST_DB_URL") ?: return
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
            password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 4
        }).use { ds ->
            Migrations.apply(ds)
            ds.connection.use { c -> c.createStatement().execute("TRUNCATE op_log, device, household, integration_account, mail_message, mail_sync, mail_tidy_item, mail_never_tidy, mail_sender, mail_backlog_scan, mail_backlog_item CASCADE") }
            PostgresDeviceRegistry(ds).enrol("hh-m", "android0123456789ab", "Fold")
            val ops = PostgresOpStore(ds)
            val accounts = PostgresIntegrationStore(ops)
            val acc = accounts.transaction { accounts.upsertAccount("hh-m", "gmail", "alex@example.com", byteArrayOf(1)) { "accmail1" } }
            val ms = PostgresMailStore(ops)
            assertNull(ms.cursor(acc))
            val full = msg("o1", 1).copy(labels = listOf("INBOX", "UNREAD"), listUnsubscribe = "<mailto:u@example.com>", oneClickUnsubscribe = true, listId = "<l.example>", fromName = "Sam")
            ms.apply("hh-m", acc, MailChanges(headers = listOf(full), fetch = listOf("p1", "p2"), cursor = "h1"))
            assertEquals("h1", ms.cursor(acc))
            assertEquals(listOf(full), ms.messages(acc))
            assertEquals(listOf("p1", "p2"), ms.pending(acc, 10))
            assertEquals(listOf("p1"), ms.pending(acc, 1))
            ms.put("hh-m", acc, msg("p1", 3))
            ms.remove(acc, "p2")
            assertEquals(0, ms.pendingCount(acc))
            assertEquals(listOf("o1", "p1"), ms.messages(acc).map { it.id })
            // Read again: back to pending until its headers come.
            ms.apply("hh-m", acc, MailChanges(fetch = listOf("o1"), removed = listOf("p1"), cursor = "h2"))
            assertEquals(listOf("o1"), ms.pending(acc, 10))
            ms.put("hh-m", acc, full.copy(unread = false))
            assertFalse(ms.messages(acc).single().unread)
            // Slice 6a: the provider's preview, kept when a later read has none.
            ms.put("hh-m", acc, full.copy(unread = false, preview = "Hi Alex, about Saturday"))
            ms.put("hh-m", acc, full.copy(unread = false))
            assertEquals(mapOf("o1" to "Hi Alex, about Saturday"), ms.previews(acc, listOf("o1", "zz")))
            assertEquals(emptyMap(), ms.previews(acc, emptyList()))
            // Meeting prep, slice 4: the newest email from each address asked, case folded, none after the cut-off.
            ms.put("hh-m", acc, msg("lf1", 5, from = "Robin@Example.com"))
            ms.put("hh-m", acc, msg("lf2", 2, from = "robin@example.com"))
            ms.put("hh-m", acc, msg("lf3", 0, from = "robin@example.com"))
            assertEquals(mapOf("robin@example.com" to "lf2"), ms.lastFrom(acc, listOf("ROBIN@example.com", "nobody@example.com"), now - day).mapValues { it.value.id })
            assertEquals(emptyMap(), ms.lastFrom(acc, emptyList(), now))
            listOf("lf1", "lf2", "lf3").forEach { ms.remove(acc, it) }
            // Slice 2a: sorting, counted only while in the inbox, cleared when the header is read again.
            assertEquals(listOf("o1"), ms.unsorted(acc, 10).map { it.id })
            ms.setSorts(acc, mapOf("o1" to MailSorted(MailSort.NEWSLETTER, MailSortReason.MAILING_LIST, true), "nope" to MailSorted(MailSort.JUNK, MailSortReason.SPAM, true)))
            assertTrue(ms.unsorted(acc, 10).isEmpty())
            assertEquals(MailSorted(MailSort.NEWSLETTER, MailSortReason.MAILING_LIST, true), ms.sortOf(acc, "o1"))
            assertEquals(mapOf("NEWSLETTER" to 1), ms.sortCounts(acc))
            // Slice 3a: the inbox's sorted emails with their sorts.
            assertEquals(listOf("o1" to MailSort.NEWSLETTER), ms.inboxSorted(acc).map { (h, s) -> h.id to s.sort })
            ms.put("hh-m", acc, full.copy(inInbox = false))
            assertNull(ms.sortOf(acc, "o1"))
            ms.setSorts(acc, mapOf("o1" to MailSorted(MailSort.NEWSLETTER, MailSortReason.MAILING_LIST, true)))
            assertEquals(emptyMap(), ms.sortCounts(acc))
            assertTrue(ms.inboxSorted(acc).isEmpty())
            // Slice 2b: only unsure Needs you in the inbox waits for the AI.
            ms.put("hh-m", acc, full)
            ms.setSorts(acc, mapOf("o1" to MailSorted(MailSort.NEEDS_YOU, MailSortReason.PERSON, false)))
            assertEquals(listOf("o1"), ms.unsure(acc, 10).map { it.id })
            ms.setSorts(acc, mapOf("o1" to MailSorted(MailSort.FYI, MailSortReason.AI_FYI, true)))
            assertTrue(ms.unsure(acc, 10).isEmpty())
            assertEquals(MailSorted(MailSort.FYI, MailSortReason.AI_FYI, true), ms.sortOf(acc, "o1"))
            // Slice 3b: what a tidy moved, and what an Undo put back for good.
            ms.recordTidy(acc, "mt1", listOf(MailMoved("o1", "o1x", MailTidy.NEWSLETTER)))
            ms.recordTidy(acc, "mt1", listOf(MailMoved("o1", "o1y", MailTidy.NEWSLETTER)))
            assertEquals(listOf(MailMoved("o1", "o1y", MailTidy.NEWSLETTER)), ms.tidied(acc, "mt1"))
            assertTrue(ms.tidied(acc, "mt2").isEmpty())
            ms.neverTidy(acc, listOf("o1", "o1y", "o1"))
            assertEquals(setOf("o1", "o1y"), ms.neverTidied(acc))
            // Slice 5a: senders, newest email's headers win, a re-read changes nothing, Outlook's headers set later.
            ms.noteSender("hh-m", acc, full.copy(id = "n1", receivedAtMs = now - 2 * day, fromName = "Old"))
            ms.noteSender("hh-m", acc, full.copy(id = "n2", receivedAtMs = now - day, fromName = null, listUnsubscribe = "<https://x.example/u>"))
            ms.noteSender("hh-m", acc, full.copy(id = "n2", receivedAtMs = now - day))
            ms.noteSender("hh-m", acc, full.copy(id = "n0", receivedAtMs = now - 3 * day))
            val rec = ms.senders(acc, 10).single()
            assertEquals("sam@example.com", rec.address)
            assertEquals("Old", rec.name)
            assertEquals("<https://x.example/u>", rec.listUnsubscribe)
            assertEquals("n2", rec.lastMessageId)
            assertEquals(2, rec.seen)
            assertTrue(rec.headersChecked)
            ms.setSenderHeaders(acc, "sam@example.com", "<mailto:a@x.example>", false)
            assertEquals("<mailto:a@x.example>", ms.senders(acc, 10).single().listUnsubscribe)
            ms.prune(acc, now)
            assertTrue(ms.messages(acc).isEmpty())
            ms.apply("hh-m", acc, MailChanges(fetch = listOf("x"), cursor = "h3"))
            // Slice 8a: the backlog scan's progress and its planned emails.
            assertNull(ms.backlogScan(acc))
            ms.setBacklogScan(acc, BacklogScan(now, now, "pg2", false, null))
            assertEquals(BacklogScan(now, now, "pg2", false, null), ms.backlogScan(acc))
            ms.setBacklogScan(acc, BacklogScan(now, now, null, true, now + 1))
            assertEquals(now + 1, ms.backlogScan(acc)?.readyMs)
            ms.addBacklogIds(acc, listOf("b1", "b2", "b1"))
            assertEquals(listOf("b1", "b2"), ms.backlogPending(acc, 10))
            val planned = BacklogItem("b1", "t1", "news@x.example", "X News", now - 40 * day, MailBacklogKind.NEWSLETTER)
            ms.putBacklog(acc, listOf(planned))
            ms.addBacklogIds(acc, listOf("b1"))
            assertEquals(listOf("b2"), ms.backlogPending(acc, 10))
            assertEquals(listOf(planned), ms.backlogItems(acc))
            ms.putBacklog(acc, listOf(planned.copy(kind = MailBacklogKind.KEEP)))
            assertEquals(MailBacklogKind.KEEP, ms.backlogItems(acc).single().kind)
            ms.removeBacklog(acc, "b2")
            assertTrue(ms.backlogPending(acc, 10).isEmpty())
            // Inbox fixes, slice 2: a re-plan forgets the scan and the planned emails.
            ms.resetBacklog(acc)
            assertNull(ms.backlogScan(acc))
            assertTrue(ms.backlogItems(acc).isEmpty())
            ms.setBacklogScan(acc, BacklogScan(now, now, null, true, now + 1))
            ms.putBacklog(acc, listOf(planned))
            ms.forget(acc)
            assertNull(ms.cursor(acc))
            assertNull(ms.backlogScan(acc))
            assertTrue(ms.backlogItems(acc).isEmpty())
            assertTrue(ms.tidied(acc, "mt1").isEmpty() && ms.neverTidied(acc).isEmpty() && ms.senders(acc, 10).isEmpty())
            assertEquals(0, ms.pendingCount(acc))
        }
    }

    // ---- Inbox fixes, slice 1 ----

    @Test
    fun aRefusedHeaderReadEndsTheReadingNotTheCheckAndNewMailIsReadFirst() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, store, i) = world(ms, ops = ops)
        repeat(5) { n -> gmail.mailbox["old$n"] = msg("old$n", 3) }
        gmail.headerFailsAfter = 2 // Gmail's rate limit after two reads
        val id = connect(i)
        i.syncAccount(id)
        assertEquals("ok", store.account(id)!!.status)
        assertEquals(2, ms.messages(id).size)
        assertEquals(3, ms.pendingCount(id))
        // After the dry-run day a promo arrives while three old ones still wait: it's read, sorted and tidied first.
        now += day + 1
        autopilotOn(ops)
        now += 1
        gmail.mailbox["promo"] = msg("promo", 0, "deals@shop.example").copy(labels = listOf("INBOX", "UNREAD", "CATEGORY_PROMOTIONS"))
        gmail.headersRead.clear()
        gmail.headerFailsAfter = 1
        gmail.nextRound = { MailChanges(fetch = listOf("promo"), cursor = "h2") }
        i.syncAccount(id)
        assertEquals(listOf("promo"), gmail.headersRead)
        assertEquals(mapOf("promo" to MailTidy.ARCHIVE), gmail.tidyCalls.single())
        assertEquals("ok", store.account(id)!!.status)
    }

    @Test
    fun gmailsBulkHeadersMakeItANotification() {
        val h = GmailHistory.header(json("""
            {"id":"m1","threadId":"t1","labelIds":["INBOX","UNREAD"],"internalDate":"1790985600000",
             "payload":{"headers":[{"name":"From","value":"Shop <hello@shop.example>"},{"name":"Subject","value":"Picked for you"},
               {"name":"Precedence","value":"bulk"}]}}
        """))!!
        assertTrue(MailSortRules.BULK_LABEL in h.labels)
        val plain = GmailHistory.header(json("""
            {"id":"m2","threadId":"t2","labelIds":["INBOX"],"internalDate":"1790985600000",
             "payload":{"headers":[{"name":"From","value":"Sam <sam@example.com>"},{"name":"Auto-Submitted","value":"no"}]}}
        """))!!
        assertFalse(MailSortRules.BULK_LABEL in plain.labels)
    }

    @Test
    fun tidiedMailIsMarkedReadAndUndoMarksItUnreadUnlessHeTurnedItOff() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val (gmail, _, i) = world(ms, ops = ops)
        val id = connect(i)
        i.syncAccount(id)
        now += day + 1
        autopilotOn(ops)
        now += 1
        gmail.mailbox["n"] = msg("n", 0, "editor@weekly.example").copy(listId = "<weekly.example>")
        gmail.mailbox["r"] = msg("r", 0, "editor@weekly.example").copy(listId = "<weekly.example>", unread = false)
        gmail.nextRound = { MailChanges(fetch = listOf("n", "r"), cursor = "h2") }
        i.syncAccount(id)
        // Only the unread one needs marking.
        assertEquals(listOf(setOf("n") to true), gmail.marked)
        val batchId = tidies(ops).keys.single()
        now += 60_000
        device(ops, EntityTypes.MAIL_TIDY, batchId, MailTidyBatchFields.UNDO_ASKED_AT, FieldValue.Int64(now))
        gmail.nextRound = { MailChanges(cursor = "h3") }
        i.syncAccount(id)
        assertEquals(setOf("n", "r") to false, gmail.marked.last())
        // Switched off: the next tidy leaves it unread.
        device(ops, EntityTypes.CONTEXT_MODE, MailAutopilotRules.ENTITY_ID, MailAutopilotFields.MARK_READ, FieldValue.Bool(false))
        gmail.marked.clear()
        gmail.mailbox["n2"] = msg("n2", 0, "editor@weekly.example").copy(listId = "<weekly.example>")
        gmail.nextRound = { MailChanges(fetch = listOf("n2"), cursor = "h4") }
        i.syncAccount(id)
        assertEquals(2, gmail.tidyCalls.size)
        assertTrue(gmail.marked.isEmpty())
    }

    @Test
    fun outlooksPersonLookingEmailsHaveTheirHeadersReadOnceAndBulkOnesAreTidied() {
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val outlook = FakeGmail(id = MailProviders.OUTLOOK)
        val i = Integrations(
            InMemoryIntegrationStore(), ops, emptyMap(), { p -> if (p == "google") OAuthClient("cid", "s") else null },
            Cipher(), "https://meka.example", { now }, mail = mapOf(MailProviders.OUTLOOK to outlook), mailStore = ms,
        )
        val url = assertIs<Integrations.StartResult.Url>(i.start("home", MailProviders.OUTLOOK)).url
        val state = URI(url).query.split("&").first { it.startsWith("state=") }.removePrefix("state=")
        val id = assertIs<Integrations.CallbackResult.Connected>(i.callback("google", state, "code", null)).accountId
        i.syncAccount(id)
        now += day + 1
        autopilotOn(ops)
        now += 1
        outlook.mailbox["news"] = msg("news", 0, "hello@shop.example")
        outlook.mailbox["bulk"] = msg("bulk", 0, "team@app.example")
        outlook.mailbox["sam"] = msg("sam", 0)
        outlook.bulk["news"] = MailBulkHeaders("<mailto:leave@shop.example>", false, null, false)
        outlook.bulk["bulk"] = MailBulkHeaders(null, false, null, true)
        outlook.nextRound = { MailChanges(fetch = listOf("news", "bulk", "sam"), cursor = "h2") }
        i.syncAccount(id)
        assertEquals(setOf("news", "bulk", "sam"), outlook.bulkReads.toSet())
        assertEquals(mapOf("news" to MailTidy.NEWSLETTER, "bulk" to MailTidy.ARCHIVE), outlook.tidyCalls.single())
        assertTrue(outlook.mailbox.getValue("sam").inInbox)
        // Each is read once.
        outlook.nextRound = { MailChanges(cursor = "h3") }
        i.syncAccount(id)
        assertEquals(3, outlook.bulkReads.size)
    }

    @Test
    fun outlooksHeaderReadsStopAtARefusalAndCarryOnNextCheck() {
        val ms = InMemoryMailStore()
        val outlook = FakeGmail(id = MailProviders.OUTLOOK)
        val store = InMemoryIntegrationStore()
        val i = Integrations(
            store, InMemoryServerOpStore(), emptyMap(), { p -> if (p == "google") OAuthClient("cid", "s") else null },
            Cipher(), "https://meka.example", { now }, mail = mapOf(MailProviders.OUTLOOK to outlook), mailStore = ms,
        )
        repeat(4) { n -> outlook.mailbox["p$n"] = msg("p$n", 1, "person$n@example.com") }
        outlook.bulkFailsAfter = 1
        val url = assertIs<Integrations.StartResult.Url>(i.start("home", MailProviders.OUTLOOK)).url
        val state = URI(url).query.split("&").first { it.startsWith("state=") }.removePrefix("state=")
        val id = assertIs<Integrations.CallbackResult.Connected>(i.callback("google", state, "code", null)).accountId
        i.syncAccount(id)
        assertEquals(1, outlook.bulkReads.size)
        assertEquals("ok", store.account(id)!!.status)
        outlook.bulkFailsAfter = null
        outlook.nextRound = { MailChanges(cursor = "h2") }
        i.syncAccount(id)
        assertEquals(4, outlook.bulkReads.toSet().size)
    }
}
