package os.meka.backend.integrations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import os.meka.core.domain.MailProviders

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
}

/**
 * A message's headers as the server keeps them (slice 1c): who it's from, the subject, when, read or not, whether it
 * is in the inbox, the provider's labels and the list headers the sorting and unsubscribe slices read. Never a body.
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
        val headers = listOf("From", "Subject", "List-Unsubscribe", "List-Unsubscribe-Post", "List-Id")
        val url = "$API/messages/${Http.enc(id)}?format=metadata&" + headers.joinToString("&") { "metadataHeaders=" + Http.enc(it) }
        val a = http.call("GET", url, accessToken)
        if (a.status == 404) return null
        return GmailHistory.header(a.body ?: error("Gmail answered without a body"))
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
            "\$select=" + Http.enc("from,subject,receivedDateTime,isRead,conversationId") +
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
            labels = labels,
            listUnsubscribe = hs["list-unsubscribe"]?.take(MailAddress.MAX_HEADER),
            oneClickUnsubscribe = hs["list-unsubscribe-post"]?.replace(" ", "")?.equals("List-Unsubscribe=One-Click", ignoreCase = true) == true,
            listId = hs["list-id"]?.take(MailAddress.MAX_HEADER),
        )
    }
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
            )
        }
        return Page(headers, removed)
    }
}

/** `From:` headers read into a name and a lower-case address. */
internal object MailAddress {
    const val MAX_SUBJECT = 300
    const val MAX_HEADER = 1000

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
