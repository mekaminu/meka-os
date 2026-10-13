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
}

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
    override fun inbox(accessToken: String): MailboxCheck {
        val label = http.call("GET", "$API/labels/INBOX", accessToken).body ?: error("Gmail answered without a body")
        return MailboxCheck(label["messagesTotal"].long(), label["messagesUnread"].long())
    }

    companion object {
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
