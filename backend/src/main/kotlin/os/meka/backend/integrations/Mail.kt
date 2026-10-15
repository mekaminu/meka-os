package os.meka.backend.integrations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import os.meka.core.domain.MailProviders
import os.meka.core.domain.MailSortRules
import os.meka.core.domain.MailTidy
import os.meka.core.domain.UnsubscribeMailto

/**
 * A mail provider (ADR-009's `EmailProvider`; Inbox autopilot, slice 1). Meka decided MEKA may read, organise and send
 * only on his tap (2026-10-12 21:01), so the sign-in asks for exactly that: Google `gmail.modify` + `gmail.send`,
 * Microsoft `Mail.ReadWrite` + `Mail.Send`. A mail account is its own account on the server (provider [id]), with its
 * own refresh token, separate from a calendar account on the same address; it signs in with the [oauth] provider's
 * OAuth client and comes back through that provider's callback, so no new redirect address has to be registered.
 *
 * Slice 1 only connects and checks the sign-in ([inbox]); reading (listChanges/getMessage), labelling, archiving and
 * sending come in the next slices, each with an idempotency key on every change.
 */
interface MailProvider {
    val id: String
    /** The OAuth client and callback it shares: "google" or "microsoft". */
    val oauth: String
    /** Every scope the account needs; a sign-in that left one unticked isn't connected. */
    val requiredScopes: List<String>
    fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String): String
    fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String): TokenSet
    fun refresh(client: OAuthClient, refreshToken: String): TokenSet
    fun accountEmail(accessToken: String): String
    /** A read that proves the mail permission works: the inbox's message count where the provider says it. */
    fun inbox(accessToken: String): MailboxCheck
    /**
     * Disconnect (slice 1b): asks the provider to withdraw MEKA's permission with this refresh token. Best effort:
     * true when the provider confirmed it; Microsoft has no such call for personal accounts (Meka removes it there).
     */
    fun revoke(refreshToken: String): Boolean = false

    /**
     * Incremental sync (slice 1c, ADR-009's `listChanges`): what changed since [cursor] (null: start again, listing the
     * mail received since [sinceMs]). Headers only; a message's body is never read. Throws [MailCursorExpired] when
     * the provider no longer knows the cursor (Gmail's history runs out after about a week; Graph answers 410).
     */
    fun changes(accessToken: String, cursor: String?, sinceMs: Long): MailChanges =
        MailChanges(cursor = cursor ?: "")

    /** One message's headers (Gmail: the ids [changes] hands back in [MailChanges.fetch]); null when it's gone. */
    fun header(accessToken: String, id: String): MailHeader? = null

    /**
     * Slice 3b: the threads Meka has written in since [sinceMs] (his sent mail's thread or conversation ids), so
     * Autopilot never tidies a conversation he's part of. Throws when it can't tell (then nothing is tidied).
     */
    fun repliedThreads(accessToken: String, sinceMs: Long): Set<String> = emptySet()

    /**
     * Slice 3b: takes each email out of the inbox (archived), with its [MailTidy.label] where it has one; slice 5b:
     * [MailTidy.TRASH] goes to the provider's Trash / Deleted Items instead (recoverable, never deleted for good). Returns, for
     * each email it moved, the id it has now (Gmail keeps the id; Outlook's move gives a new one). One that has gone
     * meanwhile is left out. Default: moves nothing.
     */
    fun tidy(accessToken: String, items: Map<String, MailTidy>): Map<String, String> = emptyMap()

    /** Undo of [tidy]: each email (by the id [tidy] returned) back in the inbox, its label taken off; returns its id now. */
    fun untidy(accessToken: String, items: Map<String, MailTidy>): Map<String, String> = emptyMap()

    /**
     * Slice 5a: one email's `List-Unsubscribe` and whether it offers one-click (Outlook, whose change feed doesn't carry
     * them); null when it has gone. Gmail's are read with the headers already.
     */
    fun listHeaders(accessToken: String, id: String): Pair<String?, Boolean>? = null

    /**
     * Inbox fixes, slice 1: one email's list and bulk headers (Outlook, whose change feed carries none), so an email that
     * only looked like a person's can be told apart from bulk mail; null when it has gone or this provider has no such read.
     */
    fun bulkHeaders(accessToken: String, id: String): MailBulkHeaders? = null

    /**
     * Inbox fixes, slice 1: marks these emails (by the id they have now) read, or unread again for an Undo. Best effort:
     * a failure never undoes the move it follows. Default: does nothing.
     */
    fun markRead(accessToken: String, ids: Collection<String>, read: Boolean) {}

    /**
     * Slice 5a: the list's unsubscribe email, sent from this account only because Meka tapped Unsubscribe. True when
     * the provider accepted it.
     */
    fun sendUnsubscribe(accessToken: String, mail: UnsubscribeMailto): Boolean = false

    /**
     * Slice 6b-2a: Meka's reply to the email [id], sent from this account in its thread, only because he tapped Send.
     * [MailReplyResult.GONE] when the email no longer exists. Never retried by itself: a failure goes back to him.
     */
    fun reply(accessToken: String, id: String, text: String): MailReplyResult = MailReplyResult.FAILED

    /**
     * Slice 8a, the backlog clean-up: one page of the mail still in the inbox that arrived before [beforeMs], continuing
     * from [page] (null: the first). Gmail hands back ids ([MailBacklogPage.ids], headers read later with [header]);
     * Outlook whole headers. Null when this provider can't list it (nothing is planned).
     */
    fun backlog(accessToken: String, beforeMs: Long, page: String?): MailBacklogPage? = null

    /**
     * Slice 3c, the daily Junk review: the headers of what sits in the Junk folder (Gmail's Spam) received since
     * [sinceMs], at most [limit]; never a body. Null when this provider can't list it (nothing is reviewed).
     */
    fun junk(accessToken: String, sinceMs: Long, limit: Int): List<MailHeader>? = null

    /**
     * Follow-up radar, slice 1: what Meka sent since [sinceMs], newest first, at most [limit] — the first recipient,
     * how many, the subject, the provider's preview and the time; never a body. Null when this provider can't list it
     * (nothing is offered).
     */
    fun sent(accessToken: String, sinceMs: Long, limit: Int): List<MailSent>? = null

    /**
     * Follow-up radar, slice 2: Meka's chase of the email [id] that he sent, from this account, in its conversation, to
     * the people it went to (To and Cc as they were), only because he tapped Send. [MailReplyResult.GONE] when the email
     * no longer exists. Never retried by itself: a failure goes back to him.
     */
    fun chaseSent(accessToken: String, id: String, text: String): MailReplyResult = MailReplyResult.FAILED
}

/** Inbox fixes, slice 1: what an email's own headers say about how it was sent (never its body). */
data class MailBulkHeaders(val listUnsubscribe: String?, val oneClick: Boolean, val listId: String?, val bulk: Boolean)

/** Follow-up radar: one email Meka sent, as [MailProvider.sent] reads it. */
data class MailSent(
    val id: String,
    val threadId: String?,
    val toAddress: String?,
    val toName: String?,
    /** To and Cc together. */
    val recipients: Int,
    val subject: String?,
    val sentAtMs: Long,
    val preview: String?,
)

/** Slice 8a: one page of the inbox's older mail; [next] null once the listing is done. */
data class MailBacklogPage(val ids: List<String> = emptyList(), val headers: List<MailHeader> = emptyList(), val next: String?)

enum class MailReplyResult { SENT, GONE, FAILED }

/**
 * Slice 6b-2a: a reply as RFC 822 text (Gmail's messages.send): to the original's Reply-To or From, "Re: " + its
 * subject, In-Reply-To and References so it threads, plain UTF-8 text in base64. Header values come from the
 * original email, so line breaks in them are dropped (no header injection).
 */
internal object MailReplyMessage {
    fun subject(original: String?): String {
        val s = clean(original).ifEmpty { return "Re:" }
        return if (s.length >= 3 && s.substring(0, 3).equals("re:", ignoreCase = true)) s else "Re: $s"
    }

    fun rfc822(to: String, subject: String, messageId: String?, references: String?, text: String, cc: String? = null): String {
        val subj = clean(subject)
        val encodedSubject = if (subj.all { it.code in 0x20..0x7e }) subj
        else "=?UTF-8?B?" + java.util.Base64.getEncoder().encodeToString(subj.toByteArray()) + "?="
        val id = clean(messageId).takeIf { it.isNotEmpty() }
        val refs = listOfNotNull(clean(references).takeIf { it.isNotEmpty() }, id).joinToString(" ").takeIf { it.isNotEmpty() }
        val body = java.util.Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(text.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray())
        return buildString {
            append("To: ").append(clean(to)).append("\r\n")
            clean(cc).takeIf { it.isNotEmpty() }?.let { append("Cc: ").append(it).append("\r\n") }
            append("Subject: ").append(encodedSubject).append("\r\n")
            id?.let { append("In-Reply-To: ").append(it).append("\r\n") }
            refs?.let { append("References: ").append(it).append("\r\n") }
            append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
            append(body).append("\r\n")
        }
    }

    /**
     * Follow-up radar, slice 2: who a chase of his sent email goes to — its To and Cc addresses (names dropped), each a
     * [validAddress]; null when To has none.
     */
    fun chaseRecipients(to: String?, cc: String?): Pair<String, String?>? {
        fun list(raw: String?) = MailAddress.parseList(raw).map { it.second.trim() }.filter(::validAddress).distinct()
        val t = list(to).takeIf { it.isNotEmpty() } ?: return null
        val c = list(cc).filter { it !in t }
        return t.joinToString(", ") to c.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /** One plain address (who the reply goes to): no spaces, separators or brackets, one @ with something either side. */
    fun validAddress(s: String): Boolean =
        s.length in 3..254 && s.count { it == '@' } == 1 && !s.startsWith('@') && !s.endsWith('@') &&
            s.none { it.isWhitespace() || it in ",;<>()\"\\" || it.code < 0x20 }

    private fun clean(s: String?): String = s.orEmpty().replace('\r', ' ').replace('\n', ' ').trim()
}

/** The unsubscribe email as RFC 822 text (plain text; a non-ASCII subject in RFC 2047's encoded form). */
internal object UnsubscribeMessage {
    fun rfc822(mail: UnsubscribeMailto): String {
        val subject = if (mail.subject.all { it.code in 0x20..0x7e }) mail.subject
        else "=?UTF-8?B?" + java.util.Base64.getEncoder().encodeToString(mail.subject.toByteArray()) + "?="
        return "To: ${mail.to}\r\nSubject: $subject\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n${mail.body}\r\n"
    }
}

/**
 * A message's headers as the server keeps them (slice 1c): who it's from, the subject, when, read or not, whether it
 * is in the inbox, the provider's labels and the list headers the sorting and unsubscribe slices read, and (slice 6a)
 * the provider's short preview. Never the body.
 */
data class MailHeader(
    val id: String,
    val threadId: String?,
    val fromAddress: String?,
    val fromName: String?,
    val subject: String?,
    val receivedAtMs: Long,
    val unread: Boolean,
    val inInbox: Boolean,
    val labels: List<String> = emptyList(),
    /** `List-Unsubscribe` as sent (`<mailto:…>, <https://…>`), for slice 5. */
    val listUnsubscribe: String? = null,
    /** `List-Unsubscribe-Post: List-Unsubscribe=One-Click` was present (RFC 8058). */
    val oneClickUnsubscribe: Boolean = false,
    val listId: String? = null,
    /**
     * Slice 6a: the provider's own short preview of the text (Gmail's snippet, Outlook's bodyPreview), for the card of an
     * email that needs Meka. Never the whole body, never logged.
     */
    val preview: String? = null,
)

/**
 * One round of [MailProvider.changes]: whole [headers] (Graph's delta carries them), ids whose headers still have to
 * be read ([fetch], Gmail), ids that left ([removed]), and the [cursor] to continue from. [more]: the cursor continues
 * a listing that was cut short (read again at the next check).
 */
data class MailChanges(
    val headers: List<MailHeader> = emptyList(),
    val fetch: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val cursor: String,
    val more: Boolean = false,
)

/** The provider has forgotten the sync cursor: the account's mail is listed again from the start. */
class MailCursorExpired(message: String) : Exception(message)

/**
 * Inbox autopilot, completeness ("API failures and offline: nothing half-applied"): a [MailProvider.tidy] or
 * [MailProvider.untidy] that failed part-way (a 429, a 5xx, the network) after moving some emails. [done] is what
 * did move (old id → id now), so the caller records those (with Undo) and the rest are simply tried at the next check.
 */
class MailMovePartial(val done: Map<String, String>, cause: Throwable) : RuntimeException("moved ${done.size} before a failure", cause)

/** Runs a provider's moves; a failure after some moved becomes [MailMovePartial] with them, one before any is rethrown. */
internal inline fun movesKept(block: (MutableMap<String, String>) -> Unit): Map<String, String> {
    val done = LinkedHashMap<String, String>()
    try {
        block(done)
    } catch (e: Exception) {
        if (done.isEmpty()) throw e
        throw MailMovePartial(done, e)
    }
    return done
}

/** What moved, whether or not the provider failed part-way (the rest wait for the next check). */
internal fun MailProvider.tidyKept(accessToken: String, items: Map<String, MailTidy>): Pair<Map<String, String>, Boolean> =
    try { tidy(accessToken, items) to false } catch (p: MailMovePartial) { p.done to true }

/** As [tidyKept], for an Undo. */
internal fun MailProvider.untidyKept(accessToken: String, items: Map<String, MailTidy>): Pair<Map<String, String>, Boolean> =
    try { untidy(accessToken, items) to false } catch (p: MailMovePartial) { p.done to true }

/** What the sign-in check saw (counts only; no message is read). */
data class MailboxCheck(val messages: Long?, val unread: Long?)

class GmailMail internal constructor(private val http: Http) : MailProvider {
    constructor() : this(Http())
    override val id = MailProviders.GMAIL
    override val oauth = "google"
    override val requiredScopes = listOf(MODIFY, SEND)
    private val scopes = "openid email $MODIFY $SEND"

    override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String) =
        "https://accounts.google.com/o/oauth2/v2/auth?" + Http.query(mapOf(
            "client_id" to client.clientId, "redirect_uri" to redirectUri, "response_type" to "code",
            "scope" to scopes, "access_type" to "offline", "prompt" to "consent select_account",
            "state" to state, "code_challenge" to codeChallenge, "code_challenge_method" to "S256",
        ))

    override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String) =
        GoogleCalendar(http).exchangeCode(client, redirectUri, code, verifier, false)

    override fun refresh(client: OAuthClient, refreshToken: String) = GoogleCalendar(http).refresh(client, refreshToken, false)

    // OpenID's userinfo (the "email" scope), so connecting works even before the Gmail API is enabled.
    override fun accountEmail(accessToken: String): String = GoogleCalendar(http).accountEmail(accessToken)

    // 403 here (the Gmail API not enabled in the Cloud project) is "couldn't reach it", not an ended sign-in.
    // Google's token revocation (RFC 7009): 200 when withdrawn, 400 when the token was already no good.
    override fun revoke(refreshToken: String): Boolean = http.postFormStatus("https://oauth2.googleapis.com/revoke", mapOf("token" to refreshToken)) == 200

    override fun inbox(accessToken: String): MailboxCheck {
        val label = http.call("GET", "$API/labels/INBOX", accessToken).body ?: error("Gmail answered without a body")
        return MailboxCheck(label["messagesTotal"].long(), label["messagesUnread"].long())
    }

    /**
     * Gmail: the cursor is a history id. Starting again takes the mailbox's history id first (so nothing that arrives
     * meanwhile is missed), then lists the ids received in the window (sent, drafts and chats left out); an existing
     * cursor reads the history since it (added, deleted and relabelled messages). Every id comes back in [MailChanges.fetch]:
     * its headers are read one call each, a few hundred per check.
     */
    override fun changes(accessToken: String, cursor: String?, sinceMs: Long): MailChanges {
        if (cursor == null) {
            val profile = http.call("GET", "$API/profile", accessToken).body ?: error("Gmail answered without a body")
            val history = profile["historyId"].str() ?: error("Gmail's profile has no historyId")
            val days = ((System.currentTimeMillis() - sinceMs) / DAY_MS).coerceIn(1, 3650)
            val ids = mutableListOf<String>()
            var page: String? = null
            var pages = 0
            do {
                val q = mutableMapOf("q" to "newer_than:${days}d -in:sent -in:drafts -in:chats", "maxResults" to "500")
                page?.let { q["pageToken"] = it }
                val body = http.call("GET", "$API/messages?" + Http.query(q), accessToken).body ?: error("Gmail answered without a body")
                (body["messages"] as? kotlinx.serialization.json.JsonArray)?.forEach { m -> (m as? JsonObject)?.get("id").str()?.let(ids::add) }
                page = body["nextPageToken"].str()
            } while (page != null && ++pages < MAX_LIST_PAGES)
            return MailChanges(fetch = ids.distinct(), cursor = history)
        }
        val touched = LinkedHashSet<String>()
        val removed = LinkedHashSet<String>()
        var latest: String = cursor
        var page: String? = null
        do {
            val q = mutableListOf("startHistoryId" to cursor, "maxResults" to "500") +
                listOf("messageAdded", "messageDeleted", "labelAdded", "labelRemoved").map { "historyTypes" to it }
            val url = "$API/history?" + (q + listOfNotNull(page?.let { "pageToken" to it })).joinToString("&") { (k, v) -> Http.enc(k) + "=" + Http.enc(v) }
            val a = http.call("GET", url, accessToken)
            if (a.status == 404) throw MailCursorExpired("Gmail's history no longer has $cursor")
            val body = a.body ?: error("Gmail answered without a body")
            val read = GmailHistory.read(body)
            touched += read.touched; removed += read.removed
            body["historyId"].str()?.let { latest = it }
            page = body["nextPageToken"].str()
        } while (page != null)
        touched.removeAll(removed)
        return MailChanges(fetch = touched.toList(), removed = removed.toList(), cursor = latest)
    }

    override fun header(accessToken: String, id: String): MailHeader? {
        val headers = listOf("From", "Subject", "List-Unsubscribe", "List-Unsubscribe-Post", "List-Id", "Precedence", "Auto-Submitted")
        val url = "$API/messages/${Http.enc(id)}?format=metadata&" + headers.joinToString("&") { "metadataHeaders=" + Http.enc(it) }
        val a = http.call("GET", url, accessToken)
        if (a.status == 404) return null
        return GmailHistory.header(a.body ?: error("Gmail answered without a body"))
    }

    /** Slice 3b: the thread ids of what Meka sent in the window (one listing; ids and thread ids only). */
    override fun repliedThreads(accessToken: String, sinceMs: Long): Set<String> {
        val days = ((System.currentTimeMillis() - sinceMs) / DAY_MS + 1).coerceIn(1, 3650)
        val threads = HashSet<String>()
        var page: String? = null
        var pages = 0
        do {
            val q = mutableMapOf("q" to "in:sent newer_than:${days}d", "maxResults" to "500")
            page?.let { q["pageToken"] = it }
            val body = http.call("GET", "$API/messages?" + Http.query(q), accessToken).body ?: error("Gmail answered without a body")
            (body["messages"] as? JsonArray)?.forEach { m -> (m as? JsonObject)?.get("threadId").str()?.let(threads::add) }
            page = body["nextPageToken"].str()
        } while (page != null && ++pages < MAX_LIST_PAGES)
        return threads
    }

    /** The id of the label [name], made (shown in Gmail's list) the first time it's needed. */
    internal fun labelId(accessToken: String, name: String): String {
        fun find(): String? = ((http.call("GET", "$API/labels", accessToken).body ?: error("Gmail answered without a body"))["labels"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }?.firstOrNull { it["name"].str() == name }?.get("id").str()
        find()?.let { return it }
        val made = http.call("POST", "$API/labels", accessToken, buildJsonObject {
            put("name", name); put("labelListVisibility", "labelShow"); put("messageListVisibility", "show")
        })
        made.body?.get("id").str()?.takeIf { made.status in 200..299 }?.let { return it }
        // 409: made meanwhile (another check); read it again.
        return find() ?: error("Gmail wouldn't make the label")
    }

    /** One batchModify per label, a thousand ids at a time: archived (INBOX off) and labelled. Ids stay the same. */
    override fun tidy(accessToken: String, items: Map<String, MailTidy>): Map<String, String> = movesKept { done ->
        for ((t, ids) in items.entries.groupBy({ it.value }, { it.key })) {
            if (t == MailTidy.KEEP) continue
            if (t == MailTidy.TRASH || t == MailTidy.JUNK_TRASH) {
                // Slice 5b: messages.trash (Gmail keeps it 30 days; never a permanent delete).
                ids.forEach { id -> if (http.call("POST", "$API/messages/${Http.enc(id)}/trash", accessToken).status in 200..299) done[id] = id }
                continue
            }
            val label = t.label?.let { listOf(labelId(accessToken, it)) }.orEmpty()
            // Slice 3c: a rescued email leaves Spam for the inbox, starred and labelled; the rest leave the inbox.
            val rescue = t == MailTidy.RESCUE
            val adding = if (rescue) label + listOf("INBOX", "STARRED") else label
            for (chunk in ids.chunked(1000)) {
                val a = http.call("POST", "$API/messages/batchModify", accessToken, buildJsonObject {
                    putJsonArray("ids") { chunk.forEach { add(it) } }
                    putJsonArray("addLabelIds") { adding.forEach { add(it) } }
                    putJsonArray("removeLabelIds") { add(if (rescue) "SPAM" else "INBOX") }
                })
                if (a.status in 200..299) chunk.forEach { done[it] = it }
            }
        }
    }

    /** One modify per email (an Undo is at most a check's worth): back in the inbox, its label off; a deleted one is skipped. */
    override fun untidy(accessToken: String, items: Map<String, MailTidy>): Map<String, String> = movesKept { done ->
        val labels = HashMap<String, String?>()
        for ((id, t) in items) {
            if (t == MailTidy.TRASH || t == MailTidy.JUNK_TRASH) {
                // Out of Trash with the labels it had (INBOX, or slice 3c's SPAM: trashing never took them off).
                if (http.call("POST", "$API/messages/${Http.enc(id)}/untrash", accessToken).status in 200..299) done[id] = id
                continue
            }
            val label = t.label?.let { name -> labels.getOrPut(name) { runCatching { labelId(accessToken, name) }.getOrNull() } }
            // Slice 3c: a rescued email goes back to Spam, out of the inbox, unstarred.
            val rescue = t == MailTidy.RESCUE
            val a = http.call("POST", "$API/messages/${Http.enc(id)}/modify", accessToken, buildJsonObject {
                putJsonArray("addLabelIds") { add(if (rescue) "SPAM" else "INBOX") }
                putJsonArray("removeLabelIds") { label?.let { add(it) }; if (rescue) { add("INBOX"); add("STARRED") } }
            })
            if (a.status in 200..299) done[id] = id
        }
    }

    /** Inbox fixes, slice 1: UNREAD off (or back on for an Undo), a thousand ids a batchModify. */
    override fun markRead(accessToken: String, ids: Collection<String>, read: Boolean) {
        for (chunk in ids.distinct().chunked(1000)) {
            http.call("POST", "$API/messages/batchModify", accessToken, buildJsonObject {
                putJsonArray("ids") { chunk.forEach { add(it) } }
                putJsonArray(if (read) "removeLabelIds" else "addLabelIds") { add("UNREAD") }
            })
        }
    }

    /** Slice 5a: messages.send with the RFC 822 text; Gmail fills in From with this account. */
    override fun sendUnsubscribe(accessToken: String, mail: UnsubscribeMailto): Boolean {
        val raw = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(UnsubscribeMessage.rfc822(mail).toByteArray())
        return http.call("POST", "$API/messages/send", accessToken, buildJsonObject { put("raw", raw) }).status in 200..299
    }

    /** Slice 6b-2a: the original's headers read, the reply built and sent in its thread (Gmail fills in From). */
    override fun reply(accessToken: String, id: String, text: String): MailReplyResult {
        val headers = listOf("From", "Reply-To", "Subject", "Message-ID", "References")
        val url = "$API/messages/${Http.enc(id)}?format=metadata&" + headers.joinToString("&") { "metadataHeaders=" + Http.enc(it) }
        val a = http.call("GET", url, accessToken)
        if (a.status == 404) return MailReplyResult.GONE
        val m = a.body?.takeIf { a.status in 200..299 } ?: return MailReplyResult.FAILED
        val hs = ((m["payload"] as? JsonObject)?.get("headers") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { h -> h["name"].str()?.let { n -> n.lowercase() to (h["value"].str() ?: "") } }.toMap()
        val to = MailAddress.parse(hs["reply-to"]).second ?: MailAddress.parse(hs["from"]).second ?: return MailReplyResult.FAILED
        if (!MailReplyMessage.validAddress(to)) return MailReplyResult.FAILED
        val raw = MailReplyMessage.rfc822(to, MailReplyMessage.subject(hs["subject"]), hs["message-id"], hs["references"], text)
        val sent = http.call("POST", "$API/messages/send", accessToken, buildJsonObject {
            put("raw", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray()))
            m["threadId"].str()?.let { put("threadId", it) }
        })
        return if (sent.status in 200..299) MailReplyResult.SENT else MailReplyResult.FAILED
    }

    /** Follow-up radar, slice 2: his sent email's To, Cc and threading headers read, the chase sent in its thread. */
    override fun chaseSent(accessToken: String, id: String, text: String): MailReplyResult {
        val headers = listOf("To", "Cc", "Subject", "Message-ID", "References")
        val url = "$API/messages/${Http.enc(id)}?format=metadata&" + headers.joinToString("&") { "metadataHeaders=" + Http.enc(it) }
        val a = http.call("GET", url, accessToken)
        if (a.status == 404) return MailReplyResult.GONE
        val m = a.body?.takeIf { a.status in 200..299 } ?: return MailReplyResult.FAILED
        val hs = ((m["payload"] as? JsonObject)?.get("headers") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { h -> h["name"].str()?.let { n -> n.lowercase() to (h["value"].str() ?: "") } }.toMap()
        val (to, cc) = MailReplyMessage.chaseRecipients(hs["to"], hs["cc"]) ?: return MailReplyResult.FAILED
        val raw = MailReplyMessage.rfc822(to, MailReplyMessage.subject(hs["subject"]), hs["message-id"], hs["references"], text, cc)
        val sent = http.call("POST", "$API/messages/send", accessToken, buildJsonObject {
            put("raw", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray()))
            m["threadId"].str()?.let { put("threadId", it) }
        })
        return if (sent.status in 200..299) MailReplyResult.SENT else MailReplyResult.FAILED
    }

    /** Slice 8a: `in:inbox before:<seconds>`, 500 ids a page (Gmail's own search; nothing but ids comes back). */
    override fun backlog(accessToken: String, beforeMs: Long, page: String?): MailBacklogPage {
        val q = mutableMapOf("q" to "in:inbox before:${beforeMs / 1000}", "maxResults" to "500")
        page?.let { q["pageToken"] = it }
        val body = http.call("GET", "$API/messages?" + Http.query(q), accessToken).body ?: error("Gmail answered without a body")
        val ids = (body["messages"] as? JsonArray)?.mapNotNull { m -> (m as? JsonObject)?.get("id").str() }.orEmpty()
        return MailBacklogPage(ids = ids, next = body["nextPageToken"].str())
    }

    /** Slice 3c: `in:spam after:<seconds>` (one page of ids), then each one's headers. */
    override fun junk(accessToken: String, sinceMs: Long, limit: Int): List<MailHeader> {
        val q = mapOf("q" to "in:spam after:${sinceMs / 1000}", "maxResults" to limit.coerceIn(1, 500).toString())
        val body = http.call("GET", "$API/messages?" + Http.query(q), accessToken).body ?: error("Gmail answered without a body")
        val ids = (body["messages"] as? JsonArray)?.mapNotNull { m -> (m as? JsonObject)?.get("id").str() }.orEmpty()
        return ids.mapNotNull { header(accessToken, it) }.filter { "SPAM" in it.labels }
    }

    /** Follow-up radar: one listing of Sent, then each message's To, Cc and Subject (metadata only, with its snippet). */
    override fun sent(accessToken: String, sinceMs: Long, limit: Int): List<MailSent> {
        val q = mapOf("q" to "in:sent after:${sinceMs / 1000}", "maxResults" to limit.coerceIn(1, 500).toString())
        val body = http.call("GET", "$API/messages?" + Http.query(q), accessToken).body ?: error("Gmail answered without a body")
        val ids = (body["messages"] as? JsonArray)?.mapNotNull { m -> (m as? JsonObject)?.get("id").str() }.orEmpty()
        val headers = listOf("To", "Cc", "Subject")
        return ids.mapNotNull { id ->
            val url = "$API/messages/${Http.enc(id)}?format=metadata&" + headers.joinToString("&") { "metadataHeaders=" + Http.enc(it) }
            val a = http.call("GET", url, accessToken)
            if (a.status == 404) null else GmailHistory.sent(a.body ?: error("Gmail answered without a body"))
        }
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        /** 500 ids a page: up to 10,000 messages in the first listing. */
        private const val MAX_LIST_PAGES = 20
        const val MODIFY = "https://www.googleapis.com/auth/gmail.modify"
        const val SEND = "https://www.googleapis.com/auth/gmail.send"
        private const val API = "https://gmail.googleapis.com/gmail/v1/users/me"
    }
}

class OutlookMail internal constructor(private val http: Http) : MailProvider {
    constructor() : this(Http())
    override val id = MailProviders.OUTLOOK
    override val oauth = "microsoft"
    override val requiredScopes = listOf("Mail.ReadWrite", "Mail.Send")
    // "consumers": personal Microsoft accounts (outlook.com/hotmail/live), as for the calendar.
    private val authority = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private val scopes = "offline_access openid email User.Read Mail.ReadWrite Mail.Send"

    override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String) =
        "$authority/authorize?" + Http.query(mapOf(
            "client_id" to client.clientId, "response_type" to "code", "redirect_uri" to redirectUri,
            "response_mode" to "query", "scope" to scopes, "state" to state, "prompt" to "select_account",
            "code_challenge" to codeChallenge, "code_challenge_method" to "S256",
        ))

    override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String) =
        http.postForm("$authority/token", mapOf(
            "client_id" to client.clientId, "client_secret" to client.clientSecret, "scope" to scopes, "code" to code,
            "redirect_uri" to redirectUri, "grant_type" to "authorization_code", "code_verifier" to verifier,
        )).tokenSet()

    // Microsoft rotates refresh tokens: the caller stores the new one every time.
    override fun refresh(client: OAuthClient, refreshToken: String) =
        http.postForm("$authority/token", mapOf(
            "client_id" to client.clientId, "client_secret" to client.clientSecret, "scope" to scopes,
            "refresh_token" to refreshToken, "grant_type" to "refresh_token",
        )).tokenSet(refreshToken)

    override fun accountEmail(accessToken: String): String = MicrosoftCalendar(http).accountEmail(accessToken)

    override fun inbox(accessToken: String): MailboxCheck {
        val f = http.call("GET", "https://graph.microsoft.com/v1.0/me/mailFolders/inbox?\$select=totalItemCount,unreadItemCount", accessToken).body
            ?: error("Outlook answered without a body")
        return MailboxCheck(f["totalItemCount"].long(), f["unreadItemCount"].long())
    }

    /**
     * Outlook: Graph's delta on the inbox, the window as its filter; the cursor is the delta link (or, when a long first
     * listing was cut short after [MAX_PAGES] pages, the next link, continued at the next check). A message that
     * leaves the inbox (archived, moved, deleted) comes back as removed; its whole headers come with the delta.
     */
    override fun changes(accessToken: String, cursor: String?, sinceMs: Long): MailChanges {
        var url = cursor ?: ("$GRAPH/me/mailFolders/inbox/messages/delta?" +
            "\$select=" + Http.enc("from,subject,receivedDateTime,isRead,conversationId,bodyPreview") +
            "&\$filter=" + Http.enc("receivedDateTime ge " + graphTime(sinceMs)))
        val headers = mutableListOf<MailHeader>()
        val removed = mutableListOf<String>()
        var pages = 0
        while (true) {
            val a = http.call("GET", url, accessToken, headers = mapOf("Prefer" to "odata.maxpagesize=100"))
            if (a.status == 410 || a.status == 404) throw MailCursorExpired("Graph no longer knows the delta link")
            val body = a.body ?: error("Outlook answered without a body")
            val page = GraphDelta.read(body)
            headers += page.headers; removed += page.removed
            val delta = body["@odata.deltaLink"].str()
            val next = body["@odata.nextLink"].str()
            if (delta != null) return MailChanges(headers = headers, removed = removed, cursor = delta)
            next ?: error("Graph's delta had neither a next nor a delta link")
            url = next
            if (++pages >= MAX_PAGES) return MailChanges(headers = headers, removed = removed, cursor = next, more = true)
        }
    }

    private fun graphTime(ms: Long) = java.time.Instant.ofEpochMilli(ms / 1000 * 1000).toString()

    /** Slice 3b: the conversation ids of what Meka sent in the window (Sent Items, conversation ids only). */
    override fun repliedThreads(accessToken: String, sinceMs: Long): Set<String> {
        var url = "$GRAPH/me/mailFolders/sentitems/messages?\$select=conversationId&\$top=100&\$filter=" +
            Http.enc("sentDateTime ge " + graphTime(sinceMs))
        val out = HashSet<String>()
        var pages = 0
        while (true) {
            val body = http.call("GET", url, accessToken).body ?: error("Outlook answered without a body")
            (body["value"] as? JsonArray)?.forEach { v -> (v as? JsonObject)?.get("conversationId").str()?.let(out::add) }
            url = body["@odata.nextLink"].str() ?: return out
            if (++pages >= MAX_PAGES) return out
        }
    }

    private fun categories(accessToken: String, id: String): List<String>? {
        val a = http.call("GET", "$GRAPH/me/messages/${Http.enc(id)}?\$select=categories", accessToken)
        if (a.status == 404) return null
        return (a.body?.get("categories") as? JsonArray)?.mapNotNull { it.str() }.orEmpty()
    }

    private fun setCategories(accessToken: String, id: String, cats: List<String>) =
        http.call("PATCH", "$GRAPH/me/messages/${Http.enc(id)}", accessToken, buildJsonObject { putJsonArray("categories") { cats.forEach { add(it) } } })

    /** Slice 3c: Outlook's follow-up flag on or off. */
    private fun setFlag(accessToken: String, id: String, on: Boolean) =
        http.call("PATCH", "$GRAPH/me/messages/${Http.enc(id)}", accessToken, buildJsonObject {
            put("flag", buildJsonObject { put("flagStatus", if (on) "flagged" else "notFlagged") })
        })

    /** Moves [id] to a well-known folder; the message's id there, or null when it has gone. */
    private fun move(accessToken: String, id: String, folder: String): String? {
        val a = http.call("POST", "$GRAPH/me/messages/${Http.enc(id)}/move", accessToken, buildJsonObject { put("destinationId", folder) })
        return if (a.status in 200..299) a.body?.get("id").str() ?: id else null
    }

    /** Each email: its category added (Outlook's label), then moved to Archive (a new id). */
    override fun tidy(accessToken: String, items: Map<String, MailTidy>): Map<String, String> = movesKept { done ->
        for ((id, t) in items) {
            if (t == MailTidy.KEEP) continue
            t.label?.let { label ->
                val cats = categories(accessToken, id) ?: return@let
                if (label !in cats) setCategories(accessToken, id, cats + label)
            }
            // Slice 5b: an unsubscribed sender's later mail to Deleted Items (recoverable; never a permanent delete); slice 3c:
            // junk the same, a rescued email to the Inbox, flagged.
            val folder = when (t) {
                MailTidy.TRASH, MailTidy.JUNK_TRASH -> "deleteditems"
                MailTidy.RESCUE -> "inbox"
                else -> "archive"
            }
            val moved = move(accessToken, id, folder) ?: continue
            if (t == MailTidy.RESCUE) runCatching { setFlag(accessToken, moved, true) }
            done[id] = moved
        }
    }

    /** Each email back to the Inbox (another new id), its category taken off. */
    override fun untidy(accessToken: String, items: Map<String, MailTidy>): Map<String, String> = movesKept { done ->
        for ((id, t) in items) {
            // Slice 3c: the Junk review's moves go back to Junk.
            val junk = t == MailTidy.JUNK_TRASH || t == MailTidy.RESCUE
            val back = move(accessToken, id, if (junk) "junkemail" else "inbox") ?: continue
            if (t == MailTidy.RESCUE) runCatching { setFlag(accessToken, back, false) }
            t.label?.let { label -> categories(accessToken, back)?.takeIf { label in it }?.let { setCategories(accessToken, back, it - label) } }
            done[id] = back
        }
    }

    /** Slice 5a: the message's internet headers (on demand, one message per sender). */
    override fun listHeaders(accessToken: String, id: String): Pair<String?, Boolean>? =
        bulkHeaders(accessToken, id)?.let { it.listUnsubscribe to it.oneClick }

    /** Inbox fixes, slice 1: the message's internet headers read once (list and bulk headers only kept). */
    override fun bulkHeaders(accessToken: String, id: String): MailBulkHeaders? {
        val a = http.call("GET", "$GRAPH/me/messages/${Http.enc(id)}?\$select=internetMessageHeaders", accessToken)
        if (a.status == 404) return null
        val hs = (a.body?.get("internetMessageHeaders") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { h -> h["name"].str()?.let { n -> n.lowercase() to (h["value"].str() ?: "") } }.toMap()
        val unsub = hs["list-unsubscribe"]?.take(MailAddress.MAX_HEADER)
        val oneClick = hs["list-unsubscribe-post"]?.replace(" ", "")?.equals("List-Unsubscribe=One-Click", ignoreCase = true) == true
        return MailBulkHeaders(unsub, oneClick, hs["list-id"]?.take(MailAddress.MAX_HEADER), MailSortRules.bulk(hs["precedence"], hs["auto-submitted"]))
    }

    /** Inbox fixes, slice 1: isRead on each email (Outlook has no batch for it here: one PATCH each). */
    override fun markRead(accessToken: String, ids: Collection<String>, read: Boolean) {
        for (id in ids.distinct()) {
            http.call("PATCH", "$GRAPH/me/messages/${Http.enc(id)}", accessToken, buildJsonObject { put("isRead", read) })
        }
    }

    /** Slice 5a: sendMail from this account (kept in Sent Items, so he can see it went). */
    override fun sendUnsubscribe(accessToken: String, mail: UnsubscribeMailto): Boolean =
        http.call("POST", "$GRAPH/me/sendMail", accessToken, buildJsonObject {
            put("message", buildJsonObject {
                put("subject", mail.subject)
                put("body", buildJsonObject { put("contentType", "Text"); put("content", mail.body) })
                putJsonArray("toRecipients") { add(buildJsonObject { put("emailAddress", buildJsonObject { put("address", mail.to) }) }) }
            })
            put("saveToSentItems", true)
        }).status in 200..299

    /**
     * Slice 6b-2a: Graph's reply on the message (Outlook threads it, answers the sender and keeps it in Sent Items),
     * his words as the plain-text body.
     */
    override fun reply(accessToken: String, id: String, text: String): MailReplyResult {
        val a = http.call("POST", "$GRAPH/me/messages/${Http.enc(id)}/reply", accessToken, buildJsonObject {
            put("message", buildJsonObject { put("body", buildJsonObject { put("contentType", "Text"); put("content", text) }) })
        })
        return when {
            a.status in 200..299 -> MailReplyResult.SENT
            a.status == 404 -> MailReplyResult.GONE
            else -> MailReplyResult.FAILED
        }
    }

    /**
     * Follow-up radar, slice 2: Graph's reply on his sent email would go back to him, so the reply names the people it
     * went to (To and Cc as they were); Outlook threads it and keeps it in Sent Items.
     */
    override fun chaseSent(accessToken: String, id: String, text: String): MailReplyResult {
        val r = http.call("GET", "$GRAPH/me/messages/${Http.enc(id)}?\$select=" + Http.enc("toRecipients,ccRecipients"), accessToken)
        if (r.status == 404) return MailReplyResult.GONE
        val m = r.body?.takeIf { r.status in 200..299 } ?: return MailReplyResult.FAILED
        fun people(key: String) = (m[key] as? JsonArray).orEmpty().mapNotNull { e ->
            ((e as? JsonObject)?.get("emailAddress") as? JsonObject)?.get("address").str()?.trim()?.takeIf(MailReplyMessage::validAddress)
        }.distinct()
        val to = people("toRecipients").takeIf { it.isNotEmpty() } ?: return MailReplyResult.FAILED
        val cc = people("ccRecipients").filter { it !in to }
        fun recipients(list: List<String>) = kotlinx.serialization.json.buildJsonArray {
            list.forEach { addr -> add(buildJsonObject { put("emailAddress", buildJsonObject { put("address", addr) }) }) }
        }
        val a = http.call("POST", "$GRAPH/me/messages/${Http.enc(id)}/reply", accessToken, buildJsonObject {
            put("message", buildJsonObject {
                put("toRecipients", recipients(to))
                if (cc.isNotEmpty()) put("ccRecipients", recipients(cc))
                put("body", buildJsonObject { put("contentType", "Text"); put("content", text) })
            })
        })
        return when {
            a.status in 200..299 -> MailReplyResult.SENT
            a.status == 404 -> MailReplyResult.GONE
            else -> MailReplyResult.FAILED
        }
    }

    /** Slice 8a: the Inbox's messages received before [beforeMs], 100 whole headers a page (oldest last). */
    override fun backlog(accessToken: String, beforeMs: Long, page: String?): MailBacklogPage {
        val url = page ?: ("$GRAPH/me/mailFolders/inbox/messages?\$top=100" +
            "&\$select=" + Http.enc("from,subject,receivedDateTime,isRead,conversationId") +
            "&\$filter=" + Http.enc("receivedDateTime lt " + graphTime(beforeMs)) +
            "&\$orderby=" + Http.enc("receivedDateTime desc"))
        val body = http.call("GET", url, accessToken).body ?: error("Outlook answered without a body")
        return MailBacklogPage(headers = GraphDelta.read(body).headers, next = body["@odata.nextLink"].str())
    }

    /** Slice 3c: the Junk Email folder's messages received since [sinceMs], whole headers, newest first. */
    override fun junk(accessToken: String, sinceMs: Long, limit: Int): List<MailHeader> {
        val url = "$GRAPH/me/mailFolders/junkemail/messages?\$top=${limit.coerceIn(1, 100)}" +
            "&\$select=" + Http.enc("from,subject,receivedDateTime,isRead,conversationId") +
            "&\$filter=" + Http.enc("receivedDateTime ge " + graphTime(sinceMs)) +
            "&\$orderby=" + Http.enc("receivedDateTime desc")
        val body = http.call("GET", url, accessToken).body ?: error("Outlook answered without a body")
        return GraphDelta.read(body).headers.map { it.copy(inInbox = false) }
    }

    /** Follow-up radar: Sent Items since [sinceMs], newest first, recipients, subject and preview only (one listing). */
    override fun sent(accessToken: String, sinceMs: Long, limit: Int): List<MailSent> {
        val url = "$GRAPH/me/mailFolders/sentitems/messages?\$top=${limit.coerceIn(1, 100)}" +
            "&\$select=" + Http.enc("toRecipients,ccRecipients,subject,sentDateTime,conversationId,bodyPreview") +
            "&\$filter=" + Http.enc("sentDateTime ge " + graphTime(sinceMs)) +
            "&\$orderby=" + Http.enc("sentDateTime desc")
        val body = http.call("GET", url, accessToken).body ?: error("Outlook answered without a body")
        return GraphDelta.sent(body)
    }

    private companion object {
        const val GRAPH = "https://graph.microsoft.com/v1.0"
        const val MAX_PAGES = 20
    }
}

/** Gmail's answers read into headers and changes (pure, so tests feed it the API's JSON). */
internal object GmailHistory {
    data class Read(val touched: List<String>, val removed: List<String>)

    fun read(body: JsonObject): Read {
        val touched = mutableListOf<String>()
        val removed = mutableListOf<String>()
        (body["history"] as? kotlinx.serialization.json.JsonArray)?.forEach { h ->
            val o = h as? JsonObject ?: return@forEach
            fun ids(key: String) = (o[key] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { ((it as? JsonObject)?.get("message") as? JsonObject)?.get("id").str() }
            touched += ids("messagesAdded") + ids("labelsAdded") + ids("labelsRemoved")
            removed += ids("messagesDeleted")
        }
        return Read(touched.distinct(), removed.distinct())
    }

    /** A `format=metadata` message. Null for what MEKA leaves alone: sent mail, drafts and chats. */
    fun header(m: JsonObject): MailHeader? {
        val labels = (m["labelIds"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it.str() }
        if (labels.any { it == "SENT" || it == "DRAFT" || it == "CHAT" } && "INBOX" !in labels) return null
        val hs = ((m["payload"] as? JsonObject)?.get("headers") as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { h -> h["name"].str()?.let { n -> n.lowercase() to (h["value"].str() ?: "") } }
            .toMap()
        val (name, address) = MailAddress.parse(hs["from"])
        return MailHeader(
            id = m["id"].str() ?: return null,
            threadId = m["threadId"].str(),
            fromAddress = address, fromName = name,
            subject = hs["subject"]?.take(MailAddress.MAX_SUBJECT),
            receivedAtMs = m["internalDate"].str()?.toLongOrNull() ?: 0L,
            unread = "UNREAD" in labels,
            inInbox = "INBOX" in labels,
            // Inbox fixes, slice 1: bulk mail by its own headers gets the server's label, so the rules can tell.
            labels = if (MailSortRules.bulk(hs["precedence"], hs["auto-submitted"])) labels + MailSortRules.BULK_LABEL else labels,
            listUnsubscribe = hs["list-unsubscribe"]?.take(MailAddress.MAX_HEADER),
            oneClickUnsubscribe = hs["list-unsubscribe-post"]?.replace(" ", "")?.equals("List-Unsubscribe=One-Click", ignoreCase = true) == true,
            listId = hs["list-id"]?.take(MailAddress.MAX_HEADER),
            preview = m["snippet"].str()?.take(MailAddress.MAX_PREVIEW)?.takeIf { it.isNotBlank() },
        )
    }
}

/** Follow-up radar: Gmail's `format=metadata` sent message read (pure, for tests). Null for one that isn't in Sent. */
internal fun GmailHistory.sent(m: JsonObject): MailSent? {
    val labels = (m["labelIds"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it.str() }
    if ("SENT" !in labels || "DRAFT" in labels) return null
    val hs = ((m["payload"] as? JsonObject)?.get("headers") as? kotlinx.serialization.json.JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .mapNotNull { h -> h["name"].str()?.let { n -> n.lowercase() to (h["value"].str() ?: "") } }
        .toMap()
    val to = MailAddress.parseList(hs["to"])
    val cc = MailAddress.parseList(hs["cc"])
    val first = to.firstOrNull()
    return MailSent(
        id = m["id"].str() ?: return null,
        threadId = m["threadId"].str(),
        toAddress = first?.second, toName = first?.first,
        recipients = to.size + cc.size,
        subject = hs["subject"]?.take(MailAddress.MAX_SUBJECT),
        sentAtMs = m["internalDate"].str()?.toLongOrNull() ?: return null,
        preview = m["snippet"].str()?.take(MailAddress.MAX_PREVIEW)?.takeIf { it.isNotBlank() },
    )
}

/** Follow-up radar: Graph's Sent Items page read (pure, for tests). */
internal fun GraphDelta.sent(body: JsonObject): List<MailSent> =
    (body["value"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { v ->
        val m = v as? JsonObject ?: return@mapNotNull null
        fun people(key: String) = (m[key] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { r ->
            val e = (r as? JsonObject)?.get("emailAddress") as? JsonObject ?: return@mapNotNull null
            val address = e["address"].str()?.lowercase()?.takeIf { '@' in it } ?: return@mapNotNull null
            e["name"].str()?.takeIf { it.isNotBlank() } to address
        }
        val to = people("toRecipients")
        val first = to.firstOrNull()
        MailSent(
            id = m["id"].str() ?: return@mapNotNull null,
            threadId = m["conversationId"].str(),
            toAddress = first?.second, toName = first?.first,
            recipients = to.size + people("ccRecipients").size,
            subject = m["subject"].str()?.take(MailAddress.MAX_SUBJECT),
            sentAtMs = m["sentDateTime"].str()?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return@mapNotNull null,
            preview = m["bodyPreview"].str()?.take(MailAddress.MAX_PREVIEW)?.takeIf { it.isNotBlank() },
        )
    }

/** Graph's delta page read into headers and removals (pure, for tests). */
internal object GraphDelta {
    data class Page(val headers: List<MailHeader>, val removed: List<String>)

    fun read(body: JsonObject): Page {
        val headers = mutableListOf<MailHeader>()
        val removed = mutableListOf<String>()
        (body["value"] as? kotlinx.serialization.json.JsonArray)?.forEach { v ->
            val m = v as? JsonObject ?: return@forEach
            val id = m["id"].str() ?: return@forEach
            if (m["@removed"] != null) { removed += id; return@forEach }
            val from = (m["from"] as? JsonObject)?.get("emailAddress") as? JsonObject
            headers += MailHeader(
                id = id,
                threadId = m["conversationId"].str(),
                fromAddress = from?.get("address").str()?.lowercase(),
                fromName = from?.get("name").str()?.takeIf { it.isNotBlank() },
                subject = m["subject"].str()?.take(MailAddress.MAX_SUBJECT),
                receivedAtMs = m["receivedDateTime"].str()?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
                unread = m["isRead"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == "false" } ?: false,
                inInbox = true,
                preview = m["bodyPreview"].str()?.take(MailAddress.MAX_PREVIEW)?.takeIf { it.isNotBlank() },
            )
        }
        return Page(headers, removed)
    }
}

/** `From:` headers read into a name and a lower-case address. */
internal object MailAddress {
    const val MAX_SUBJECT = 300
    const val MAX_HEADER = 1000
    /** Slice 6a: the provider's preview as kept (Gmail's snippet and Outlook's bodyPreview are ~200 characters already). */
    const val MAX_PREVIEW = 300

    /**
     * Follow-up radar: a `To:`/`Cc:` header's addresses, in order (commas inside quotes or angle brackets don't split).
     * Entries without an address are dropped.
     */
    fun parseList(raw: String?): List<Pair<String?, String>> {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var angled = false
        for (c in s) {
            when {
                c == '"' -> { quoted = !quoted; cur.append(c) }
                c == '<' && !quoted -> { angled = true; cur.append(c) }
                c == '>' && !quoted -> { angled = false; cur.append(c) }
                c == ',' && !quoted && !angled -> { parts += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        parts += cur.toString()
        return parts.mapNotNull { p -> parse(p).let { (n, a) -> a?.let { n to it } } }
    }

    fun parse(raw: String?): Pair<String?, String?> {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
        val lt = s.lastIndexOf('<')
        val gt = s.lastIndexOf('>')
        if (lt >= 0 && gt > lt) {
            val address = s.substring(lt + 1, gt).trim().lowercase().takeIf { '@' in it }
            val name = s.substring(0, lt).trim().removeSurrounding("\"").trim().takeIf { it.isNotEmpty() }
            return name to address
        }
        return null to s.lowercase().takeIf { '@' in it && ' ' !in it }
    }
}

private fun kotlinx.serialization.json.JsonElement?.str(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
private fun kotlinx.serialization.json.JsonElement?.long(): Long? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.let { it.longOrNull ?: it.intOrNull?.toLong() }

private fun JsonObject.tokenSet(previousRefresh: String? = null) = TokenSet(
    accessToken = this["access_token"].str() ?: error("no access_token"),
    refreshToken = this["refresh_token"].str() ?: previousRefresh,
    expiresInSec = this["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600,
    scope = this["scope"].str(),
)
