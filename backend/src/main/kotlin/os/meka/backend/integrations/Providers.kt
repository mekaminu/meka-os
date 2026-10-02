package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** OAuth app credentials for one provider, pasted by the owner into Secrets Manager. */
data class OAuthClient(val clientId: String, val clientSecret: String)

data class TokenSet(val accessToken: String, val refreshToken: String?, val expiresInSec: Long)

/** One event occurrence as the provider reports it. [id] is unique within the account. */
data class RemoteEvent(
    val id: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val location: String?,
    val calendarName: String?,
)

/** The refresh token was revoked or expired: the owner has to connect the account again. */
class ReconnectRequired(message: String) : RuntimeException(message)

/** A calendar provider (ADR-008). Read-only in M1: no scope that can change the owner's calendars is requested. */
interface CalendarProvider {
    val id: String
    fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String): String
    fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String): TokenSet
    fun refresh(client: OAuthClient, refreshToken: String): TokenSet
    fun accountEmail(accessToken: String): String
    fun events(accessToken: String, fromMs: Long, toMs: Long): List<RemoteEvent>
}

/** Minimal HTTPS helper on the JDK client: no extra dependencies, explicit timeouts, never logs bodies. */
internal class Http(private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getJson(url: String, bearer: String, headers: Map<String, String> = emptyMap()): JsonObject {
        val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer $bearer").GET()
        headers.forEach { (k, v) -> b.header(k, v) }
        return send(b.build())
    }

    fun postForm(url: String, form: Map<String, String>): JsonObject {
        val body = form.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        return send(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        )
    }

    private fun send(req: HttpRequest): JsonObject {
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        val parsed = runCatching { json.parseToJsonElement(resp.body()).jsonObject }.getOrNull()
        if (resp.statusCode() in 200..299 && parsed != null) return parsed
        val err = when (val e = parsed?.get("error")) {
            is JsonObject -> e["code"].str()
            else -> e.str()
        }
        // invalid_grant = refresh token revoked/expired; 401 on a fresh access token means the same in practice.
        if (err == "invalid_grant") throw ReconnectRequired("provider says invalid_grant")
        throw IllegalStateException("provider HTTP ${resp.statusCode()}${err?.let { " ($it)" } ?: ""}")
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)
        fun query(params: Map<String, String>) = params.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
    }
}

private fun JsonElement?.str(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
private fun JsonObject.tokens(previousRefresh: String? = null) = TokenSet(
    accessToken = this["access_token"].str() ?: error("no access_token"),
    refreshToken = this["refresh_token"].str() ?: previousRefresh,
    expiresInSec = this["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600,
)
private fun rfc3339(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))
private fun utcMidnight(date: String): Long = LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

class GoogleCalendar internal constructor(private val http: Http) : CalendarProvider {
    constructor() : this(Http())
    override val id = "google"
    private val scopes = "openid email https://www.googleapis.com/auth/calendar.readonly"

    override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String) =
        "https://accounts.google.com/o/oauth2/v2/auth?" + Http.query(mapOf(
            "client_id" to client.clientId, "redirect_uri" to redirectUri, "response_type" to "code",
            "scope" to scopes, "access_type" to "offline", "prompt" to "consent select_account",
            "state" to state, "code_challenge" to codeChallenge, "code_challenge_method" to "S256",
        ))

    override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String) =
        http.postForm("https://oauth2.googleapis.com/token", mapOf(
            "code" to code, "client_id" to client.clientId, "client_secret" to client.clientSecret,
            "redirect_uri" to redirectUri, "grant_type" to "authorization_code", "code_verifier" to verifier,
        )).tokens()

    override fun refresh(client: OAuthClient, refreshToken: String) =
        http.postForm("https://oauth2.googleapis.com/token", mapOf(
            "client_id" to client.clientId, "client_secret" to client.clientSecret,
            "refresh_token" to refreshToken, "grant_type" to "refresh_token",
        )).tokens(previousRefresh = refreshToken)

    override fun accountEmail(accessToken: String): String =
        http.getJson("https://openidconnect.googleapis.com/v1/userinfo", accessToken)["email"].str() ?: error("no email")

    override fun events(accessToken: String, fromMs: Long, toMs: Long): List<RemoteEvent> {
        val calendars = http.getJson(
            "https://www.googleapis.com/calendar/v3/users/me/calendarList?minAccessRole=reader&maxResults=250", accessToken,
        )["items"]?.jsonArray.orEmpty().map { it.jsonObject }
            // The calendars the owner has ticked in Google Calendar, plus the primary one.
            .filter { it["selected"]?.jsonPrimitive?.booleanOrNull == true || it["primary"]?.jsonPrimitive?.booleanOrNull == true }
        return calendars.flatMap { cal ->
            val calId = cal["id"].str() ?: return@flatMap emptyList()
            val calName = cal["summaryOverride"].str() ?: cal["summary"].str()
            buildList {
                var page: String? = null
                do {
                    val q = mutableMapOf(
                        "timeMin" to rfc3339(fromMs), "timeMax" to rfc3339(toMs), "singleEvents" to "true",
                        "maxResults" to "2500", "showDeleted" to "false",
                    )
                    page?.let { q["pageToken"] = it }
                    val resp = http.getJson("https://www.googleapis.com/calendar/v3/calendars/${Http.enc(calId)}/events?" + Http.query(q), accessToken)
                    resp["items"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { e ->
                        if (e["status"].str() == "cancelled") return@forEach
                        val start = e["start"]?.jsonObject ?: return@forEach
                        val end = e["end"]?.jsonObject ?: start
                        val allDay = start["date"] != null
                        val startMs = if (allDay) utcMidnight(start["date"].str()!!) else OffsetDateTime.parse(start["dateTime"].str()).toInstant().toEpochMilli()
                        val endMs = if (allDay) utcMidnight(end["date"].str() ?: start["date"].str()!!)
                        else end["dateTime"].str()?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() } ?: startMs
                        add(RemoteEvent("$calId/${e["id"].str()}", e["summary"].str() ?: "", startMs, endMs, allDay, e["location"].str(), calName))
                    }
                    page = resp["nextPageToken"].str()
                } while (page != null)
            }
        }
    }
}

class MicrosoftCalendar internal constructor(private val http: Http) : CalendarProvider {
    constructor() : this(Http())
    override val id = "microsoft"
    // "consumers": personal Microsoft accounts (outlook.com/hotmail/live), which is what the owner uses.
    private val authority = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private val scopes = "offline_access openid email User.Read Calendars.Read"

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
        )).tokens()

    // Microsoft rotates refresh tokens: the caller stores the new one every time.
    override fun refresh(client: OAuthClient, refreshToken: String) =
        http.postForm("$authority/token", mapOf(
            "client_id" to client.clientId, "client_secret" to client.clientSecret, "scope" to scopes,
            "refresh_token" to refreshToken, "grant_type" to "refresh_token",
        )).tokens(previousRefresh = refreshToken)

    override fun accountEmail(accessToken: String): String {
        val me = http.getJson("https://graph.microsoft.com/v1.0/me?\$select=mail,userPrincipalName", accessToken)
        return me["mail"].str() ?: me["userPrincipalName"].str() ?: error("no email")
    }

    override fun events(accessToken: String, fromMs: Long, toMs: Long): List<RemoteEvent> {
        val utc = mapOf("Prefer" to "outlook.timezone=\"UTC\"")
        val calendars = http.getJson("https://graph.microsoft.com/v1.0/me/calendars?\$select=id,name&\$top=50", accessToken)["value"]
            ?.jsonArray.orEmpty().map { it.jsonObject }
        return calendars.flatMap { cal ->
            val calId = cal["id"].str() ?: return@flatMap emptyList()
            val calName = cal["name"].str()
            buildList {
                var url: String? = "https://graph.microsoft.com/v1.0/me/calendars/${Http.enc(calId)}/calendarView?" + Http.query(mapOf(
                    "startDateTime" to rfc3339(fromMs), "endDateTime" to rfc3339(toMs), "\$top" to "500",
                    "\$select" to "id,subject,start,end,isAllDay,location,isCancelled",
                ))
                while (url != null) {
                    val resp = http.getJson(url, accessToken, utc)
                    resp["value"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { e ->
                        if (e["isCancelled"]?.jsonPrimitive?.booleanOrNull == true) return@forEach
                        val allDay = e["isAllDay"]?.jsonPrimitive?.booleanOrNull == true
                        val start = e["start"]?.jsonObject?.get("dateTime").str() ?: return@forEach
                        val end = e["end"]?.jsonObject?.get("dateTime").str() ?: start
                        // With the UTC preference, timed values are UTC wall times; all-day values are calendar dates.
                        fun parse(s: String) = if (allDay) utcMidnight(s.take(10))
                        else LocalDateTime.parse(s.substringBefore('.')).toInstant(ZoneOffset.UTC).toEpochMilli()
                        val location = e["location"]?.jsonObject?.get("displayName").str()?.takeIf { it.isNotBlank() }
                        add(RemoteEvent("$calId/${e["id"].str()}", e["subject"].str() ?: "", parse(start), parse(end), allDay, location, calName))
                    }
                    url = resp["@odata.nextLink"].str()
                }
            }
        }
    }
}
