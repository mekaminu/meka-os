package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import os.meka.backend.integrations.GmailMail
import os.meka.backend.integrations.GmailMessageBody
import os.meka.backend.integrations.GraphMessageBody
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.InMemoryMailStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.MailChanges
import os.meka.backend.integrations.MailHeader
import os.meka.backend.integrations.MailMoved
import os.meka.backend.integrations.MailProvider
import os.meka.backend.integrations.MailboxCheck
import os.meka.backend.integrations.OAuthClient
import os.meka.backend.integrations.ReconnectRequired
import os.meka.backend.integrations.TokenCipher
import os.meka.backend.integrations.TokenSet
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MailAttachmentFacts
import os.meka.core.domain.MailMessageAnswer
import os.meka.core.domain.MailMessageFacts
import os.meka.core.domain.MailNeedsRules
import os.meka.core.domain.MailPerson
import os.meka.core.domain.MailTidiedRules
import os.meka.core.domain.MailTidy
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.MailMessageCodec
import java.net.URI
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Read the full email inside Navilife, slice 1: the providers' answers read, and the handle → email round trip. */
class MailReaderTest {
    private var now = 1_790_985_600_000L
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    @Test
    fun gmailsMimeTreeGivesTheHtmlTheTextAndTheFiles() {
        val f = GmailMessageBody.read(json("""
            {"id":"m1","internalDate":"1790985600000","payload":{"mimeType":"multipart/mixed","headers":[
              {"name":"From","value":"\"Oakfield School\" <Office@Oakfield.example>"},
              {"name":"To","value":"Alex <alex@example.com>, \"Sam, Jr\" <sam@example.com>"},
              {"name":"Cc","value":"jordan@example.com"},{"name":"Subject","value":"Trip on Friday"}],
             "parts":[
              {"mimeType":"multipart/alternative","parts":[
                {"mimeType":"text/plain","headers":[{"name":"Content-Type","value":"text/plain; charset=UTF-8"}],"body":{"size":5,"data":"${b64("Hi £5")}"}},
                {"mimeType":"text/html","headers":[{"name":"Content-Type","value":"text/html; charset=\"utf-8\""}],"body":{"size":9,"data":"${b64("<p>Hi £5</p>")}"}}]},
              {"mimeType":"image/png","filename":"","headers":[{"name":"Content-Disposition","value":"inline"},{"name":"Content-ID","value":"<logo>"}],"body":{"attachmentId":"x","size":900}},
              {"mimeType":"application/pdf","filename":"consent.pdf","headers":[{"name":"Content-Disposition","value":"attachment; filename=consent.pdf"}],"body":{"attachmentId":"y","size":240400}}]}}
        """))
        assertEquals(MailPerson("Oakfield School", "office@oakfield.example"), f.from)
        assertEquals(listOf("alex@example.com", "sam@example.com"), f.to.map { it.address })
        assertEquals("Sam, Jr", f.to[1].name)
        assertEquals(listOf("jordan@example.com"), f.cc.map { it.address })
        assertEquals("Trip on Friday", f.subject)
        assertEquals(1_790_985_600_000L, f.atMs)
        assertEquals("<p>Hi £5</p>", f.html)
        assertEquals("Hi £5", f.text)
        assertEquals(listOf(MailAttachmentFacts("consent.pdf", 240_400, "application/pdf")), f.attachments)
    }

    @Test
    fun outlooksMessageGivesItsBodyAndFilesButNotInlinePictures() {
        val f = GraphMessageBody.read(json("""
            {"subject":"Your order","receivedDateTime":"2026-10-03T08:00:00Z",
             "from":{"emailAddress":{"name":"Shop","address":"Orders@Shop.example"}},
             "toRecipients":[{"emailAddress":{"name":"Alex","address":"alex@example.com"}}],"ccRecipients":[],
             "body":{"contentType":"html","content":"<p>Thanks</p>"},
             "attachments":[{"name":"invoice.pdf","size":1200,"contentType":"application/pdf","isInline":false},
                            {"name":"logo.png","size":300,"contentType":"image/png","isInline":true}]}
        """))
        assertEquals("orders@shop.example", f.from.address)
        assertEquals("<p>Thanks</p>", f.html)
        assertNull(f.text)
        assertEquals(listOf("invoice.pdf"), f.attachments.map { it.name })
        assertEquals(java.time.Instant.parse("2026-10-03T08:00:00Z").toEpochMilli(), f.atMs)
        assertEquals("Plain words", GraphMessageBody.read(json("""{"body":{"contentType":"text","content":"Plain words"}}""")).text)
    }

    /** A Gmail-like mailbox that also reads whole emails. */
    private class Mailbox : MailProvider {
        override val id = "gmail"
        override val oauth = "google"
        override val requiredScopes = listOf(GmailMail.MODIFY, GmailMail.SEND)
        val headers = linkedMapOf<String, MailHeader>()
        val whole = mutableMapOf<String, MailMessageFacts>()
        val read = mutableListOf<String>()
        var fails: Exception? = null
        override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String) = "https://a.example/?state=$state"
        override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String) =
            TokenSet("access", "refresh", 3600, "openid email ${GmailMail.MODIFY} ${GmailMail.SEND}")
        override fun refresh(client: OAuthClient, refreshToken: String) = TokenSet("access", null, 3600)
        override fun accountEmail(accessToken: String) = "alex@example.com"
        override fun inbox(accessToken: String) = MailboxCheck(headers.size.toLong(), null)
        override fun changes(accessToken: String, cursor: String?, sinceMs: Long) = MailChanges(fetch = headers.keys.toList(), cursor = "h1")
        override fun header(accessToken: String, id: String) = headers[id]
        override fun message(accessToken: String, id: String): MailMessageFacts? {
            fails?.let { throw it }
            read += id
            return whole[id]
        }
    }

    private class Cipher : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain.reversedArray()
        override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher.reversedArray()
    }

    private fun world(box: Mailbox, ms: InMemoryMailStore, ops: InMemoryServerOpStore = InMemoryServerOpStore()) = Integrations(
        InMemoryIntegrationStore(), ops, emptyMap(), { p -> if (p == "google") OAuthClient("cid", "s") else null },
        Cipher(), "https://meka.example", { now }, mail = mapOf("gmail" to box), mailStore = ms,
    )

    private fun connect(i: Integrations): String {
        val url = assertIs<Integrations.StartResult.Url>(i.start("home", "gmail")).url
        val state = URI(url).query.split("&").first { it.startsWith("state=") }.removePrefix("state=")
        return assertIs<Integrations.CallbackResult.Connected>(i.callback("google", state, "code", null)).accountId
    }

    private val letter = MailMessageFacts(
        from = MailPerson("Oakfield School", "office@oakfield.sch.uk"), to = listOf(MailPerson("Alex", "alex@example.com")), cc = emptyList(),
        atMs = 1_790_985_000_000L, subject = "Trip on Friday",
        html = "<p onclick=\"x()\">Please sign</p><script>steal()</script><img src=\"https://track.example/p.gif\">", text = null,
        attachments = listOf(MailAttachmentFacts("consent.pdf", 240_400, "application/pdf")),
    )

    @Test
    fun aCardsHandleReadsTheWholeEmailMadeSafeAndOtherHandlesReadNothing() {
        val box = Mailbox()
        val ms = InMemoryMailStore()
        val ops = InMemoryServerOpStore()
        val i = world(box, ms, ops)
        box.headers["p0"] = MailHeader("p0", "t-p0", "office@oakfield.sch.uk", "Oakfield School", "Trip on Friday", now - 60_000, unread = true, inInbox = true)
        box.whole["p0"] = letter
        val acc = connect(i)
        i.syncAccount(acc)
        val item = MailNeedsRules.itemId(acc, "p0")
        assertTrue(item in os.meka.backend.integrations.EntityReader.scanning(ops).entities("home", EntityTypes.MAIL_ITEM).keys, "the card was published")

        val r = i.readMailMessage("home", MailMessageCodec.Request(MailMessageCodec.Request.ITEM, item))
        assertEquals(MailMessageAnswer.OK, r.state)
        assertEquals(listOf("p0"), box.read)
        assertEquals("office@oakfield.sch.uk", r.from.address)
        assertEquals("Trip on Friday", r.subject)
        assertEquals(listOf("consent.pdf"), r.attachments.map { it.name })
        val html = r.html!!
        assertFalse(html.contains("script") || html.contains("onclick") || html.contains("track.example"), html)
        assertTrue(html.contains("Please sign"))
        assertEquals(1, r.blockedImages)
        assertEquals("Please sign", r.text)
        // Show images: the picture comes back.
        assertTrue(i.readMailMessage("home", MailMessageCodec.Request(MailMessageCodec.Request.ITEM, item, images = true)).html!!.contains("track.example"))
        // Another household, or a made-up handle: nothing is read.
        val before = box.read.size
        assertEquals(MailMessageAnswer.UNKNOWN, i.readMailMessage("other", MailMessageCodec.Request(MailMessageCodec.Request.ITEM, item)).state)
        assertEquals(MailMessageAnswer.UNKNOWN, i.readMailMessage("home", MailMessageCodec.Request(MailMessageCodec.Request.ITEM, "mi0000000000")).state)
        assertEquals(before, box.read.size)
        // Gone from the mailbox; a sign-in that ended; the provider down.
        box.whole.remove("p0")
        assertEquals(MailMessageAnswer.GONE, i.readMailMessage("home", MailMessageCodec.Request(MailMessageCodec.Request.ITEM, item)).state)
        box.fails = ReconnectRequired("refused")
        assertEquals(MailMessageAnswer.RECONNECT, i.readMailMessage("home", MailMessageCodec.Request(MailMessageCodec.Request.ITEM, item)).state)
        box.fails = IllegalStateException("provider HTTP 503")
        assertEquals(MailMessageAnswer.FAILED, i.readMailMessage("home", MailMessageCodec.Request(MailMessageCodec.Request.ITEM, item)).state)
    }

    @Test
    fun aTidiedEmailIsReadWhereTheMovePutIt() {
        // Archived, junk or trashed: the id the move gave it first (Outlook gives a new one), else its first id.
        val box = Mailbox()
        val ms = InMemoryMailStore()
        val i = world(box, ms)
        val acc = connect(i)
        ms.recordTidy(acc, "mtbatch1", listOf(MailMoved("old1", "new1", MailTidy.TRASH), MailMoved("g2", "g2", MailTidy.JUNK_TRASH)))
        box.whole["new1"] = letter
        box.whole["g2"] = letter.copy(subject = "Prize draw")
        val k1 = MailTidiedRules.key(acc, "old1")
        val r = i.readMailMessage("home", MailMessageCodec.Request(MailMessageCodec.Request.TIDIED, k1, "mtbatch1"))
        assertEquals(MailMessageAnswer.OK, r.state)
        assertEquals(listOf("new1"), box.read)
        assertEquals("Prize draw", i.readMailMessage("home", MailMessageCodec.Request(MailMessageCodec.Request.TIDIED, MailTidiedRules.key(acc, "g2"), "mtbatch1")).subject)
        // Moved away from where Navilife put it: the first id is tried too, then it's gone.
        box.whole.remove("new1")
        box.whole["old1"] = letter.copy(subject = "Back")
        assertEquals("Back", i.readMailMessage("home", MailMessageCodec.Request(MailMessageCodec.Request.TIDIED, k1, "mtbatch1")).subject)
        box.whole.remove("old1")
        assertEquals(MailMessageAnswer.GONE, i.readMailMessage("home", MailMessageCodec.Request(MailMessageCodec.Request.TIDIED, k1, "mtbatch1")).state)
        // A key from another tidy isn't found in this one.
        assertEquals(MailMessageAnswer.UNKNOWN, i.readMailMessage("home", MailMessageCodec.Request(MailMessageCodec.Request.TIDIED, k1, "mtother")).state)
    }
}
