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
import os.meka.core.sync.InMemoryServerOpStore
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
    }

    private class Cipher : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain.reversedArray()
        override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher.reversedArray()
    }

    private fun msg(id: String, ageDays: Long, from: String = "sam@example.com") =
        MailHeader(id, "t-$id", from, null, "Subject $id", now - ageDays * day, unread = true, inInbox = true)

    private fun world(mailStore: MailStore, gmail: FakeGmail = FakeGmail(), store: InMemoryIntegrationStore = InMemoryIntegrationStore()) =
        Triple(gmail, store, Integrations(
            store, InMemoryServerOpStore(), emptyMap(), { p -> if (p == "google") OAuthClient("cid", "s") else null },
            Cipher(), "https://meka.example", { now }, mail = mapOf("gmail" to gmail), mailStore = mailStore,
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

    // ---- On real Postgres (MEKA_TEST_DB_URL, CI); skipped when unset ----

    @Test
    fun thePostgresStoreKeepsHeadersPendingRowsAndTheCursor() {
        val url = System.getenv("MEKA_TEST_DB_URL") ?: return
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
            password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 4
        }).use { ds ->
            Migrations.apply(ds)
            ds.connection.use { c -> c.createStatement().execute("TRUNCATE op_log, device, household, integration_account, mail_message, mail_sync CASCADE") }
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
            ms.put("hh-m", acc, full.copy(inInbox = false))
            assertNull(ms.sortOf(acc, "o1"))
            ms.setSorts(acc, mapOf("o1" to MailSorted(MailSort.NEWSLETTER, MailSortReason.MAILING_LIST, true)))
            assertEquals(emptyMap(), ms.sortCounts(acc))
            ms.prune(acc, now)
            assertTrue(ms.messages(acc).isEmpty())
            ms.apply("hh-m", acc, MailChanges(fetch = listOf("x"), cursor = "h3"))
            ms.forget(acc)
            assertNull(ms.cursor(acc))
            assertEquals(0, ms.pendingCount(acc))
        }
    }
}
