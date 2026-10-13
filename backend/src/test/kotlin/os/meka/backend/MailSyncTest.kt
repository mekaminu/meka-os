package os.meka.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import os.meka.backend.integrations.GmailHistory
import os.meka.backend.integrations.GmailMail
import os.meka.backend.integrations.GraphDelta
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.InMemoryMailStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.MailAddress
import os.meka.backend.integrations.MailAiSorter
import os.meka.backend.integrations.MailChanges
import os.meka.backend.integrations.MailCursorExpired
import os.meka.backend.integrations.MailHeader
import os.meka.backend.integrations.MailProvider
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
import os.meka.core.domain.MailTidy
import os.meka.core.domain.MailTidyBatchFields
import os.meka.backend.integrations.EntityReader
import os.meka.backend.integrations.MailMoved
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    private class FakeGmail : MailProvider {
        override val id = "gmail"
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
        override fun inbox(accessToken: String) = MailboxCheck(mailbox.size.toLong(), 0)
        override fun changes(accessToken: String, cursor: String?, sinceMs: Long): MailChanges {
            cursorsAsked += cursor; sinceAsked = sinceMs
            return nextRound(cursor)
        }
        override fun header(accessToken: String, id: String): MailHeader? { headersRead += id; return mailbox[id] }

        // Slice 3b: Autopilot's moves on the fake mailbox.
        val sentThreads = mutableSetOf<String>()
        var repliedFails = false
        val tidyCalls = mutableListOf<Map<String, MailTidy>>()
        override fun repliedThreads(accessToken: String, sinceMs: Long): Set<String> {
            if (repliedFails) error("Gmail 503")
            return sentThreads
        }
        override fun tidy(accessToken: String, items: Map<String, MailTidy>): Map<String, String> {
            tidyCalls += items
            return items.filterKeys { it in mailbox }.mapValues { (id, t) ->
                val h = mailbox.getValue(id)
                mailbox[id] = h.copy(inInbox = false, labels = h.labels - "INBOX" + listOfNotNull(t.label))
                id
            }
        }
        override fun untidy(accessToken: String, items: Map<String, MailTidy>): Map<String, String> =
            items.filterKeys { it in mailbox }.mapValues { (id, t) ->
                val h = mailbox.getValue(id)
                mailbox[id] = h.copy(inInbox = true, labels = h.labels.filter { it != t.label } + "INBOX")
                id
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
    ) =
        Triple(gmail, store, Integrations(
            store, ops, emptyMap(), { p -> if (p == "google") OAuthClient("cid", "s") else null },
            Cipher(), "https://meka.example", { now }, mail = mapOf("gmail" to gmail), mailStore = mailStore, mailAi = mailAi,
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
    fun theAiSeesAtMostOneBatchACheckNewestFirst() {
        val ms = InMemoryMailStore()
        val ai = FakeAi { e -> e.associate { it.id to "needs_you" } }
        val (gmail, _, i) = world(ms, mailAi = ai)
        gmail.nextRound = { MailChanges(headers = (0 until Integrations.AI_BATCH + 3).map { n -> msg("m$n", n.toLong() % 50) }, cursor = "h1") }
        val id = connect(i)
        i.syncAccount(id)
        assertEquals(Integrations.AI_BATCH, ai.asked.single().size)
        assertEquals(3, ms.unsure(id, 100).size)
        assertTrue(ms.unsure(id, 100).all { h -> ai.asked.single().none { it == h.id } })
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
        gmail.mailbox["r2"] = msg("r2", 0, "auto-confirm@shop.example").copy(subject = "Your order has shipped")
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
        assertEquals(3, ms.tidied(id, batchId).size)
        // The next check plans nothing again.
        gmail.nextRound = { MailChanges(cursor = "h4") }
        i.syncAccount(id)
        assertEquals(1, gmail.tidyCalls.size)
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

    // ---- On real Postgres (MEKA_TEST_DB_URL, CI); skipped when unset ----

    @Test
    fun thePostgresStoreKeepsHeadersPendingRowsAndTheCursor() {
        val url = System.getenv("MEKA_TEST_DB_URL") ?: return
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
            password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 4
        }).use { ds ->
            Migrations.apply(ds)
            ds.connection.use { c -> c.createStatement().execute("TRUNCATE op_log, device, household, integration_account, mail_message, mail_sync, mail_tidy_item, mail_never_tidy CASCADE") }
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
            ms.prune(acc, now)
            assertTrue(ms.messages(acc).isEmpty())
            ms.apply("hh-m", acc, MailChanges(fetch = listOf("x"), cursor = "h3"))
            ms.forget(acc)
            assertNull(ms.cursor(acc))
            assertTrue(ms.tidied(acc, "mt1").isEmpty() && ms.neverTidied(acc).isEmpty())
            assertEquals(0, ms.pendingCount(acc))
        }
    }
}
