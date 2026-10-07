package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

data class TokenSet(val accessToken: String, val refreshToken: String?, val expiresInSec: Long, val scope: String? = null)

/** One event occurrence as the provider reports it. [id] is unique within the account. */
data class RemoteEvent(
    val id: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val location: String?,
    val calendarName: String?,
    /** The event's notes as plain text (HTML stripped by [plainText]). */
    val description: String? = null,
    /** The provider's own video-call link (Google Meet, Teams); https only. */
    val joinUrl: String? = null,
    /** The event's own page in the provider's web app (Google `htmlLink`, Outlook `webLink`); https only. */
    val webUrl: String? = null,
)

/** The refresh token was revoked or expired: the owner has to connect the account again. */
class ReconnectRequired(message: String) : RuntimeException(message)

/** A calendar provider (ADR-008). Read-only in M1: no scope that can change the owner's calendars is requested. */
interface CalendarProvider {
    val id: String
    /** The scope without which the account is useless (checked against what the owner actually granted). */
    val requiredScope: String
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
        return send(b.build(), apiCall = true)
    }

    fun postForm(url: String, form: Map<String, String>): JsonObject {
        val body = form.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        return send(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        )
    }

    private fun send(req: HttpRequest, apiCall: Boolean = false): JsonObject {
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        // A freshly refreshed token refused by the API means access was withdrawn or never granted.
        if (apiCall && (resp.statusCode() == 401 || resp.statusCode() == 403)) throw ReconnectRequired("provider API refused access")
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
    scope = this["scope"].str(),
)
/** An https link or null: the only kind of link mirrored as a Join button. */
internal fun httpsOrNull(url: String?): String? = url?.trim()?.takeIf { it.startsWith("https://") && it.none(Char::isWhitespace) }

private val BREAK = Regex("(?i)<br\\s*/?>")
private val BLOCK = Regex("(?i)</?(p|div|ul|ol|li|tr|table|h[1-6])\\b[^>]*>")
private val BLOCK_RUN = Regex("\u0000(\\s*\u0000)*")
private val LINK = Regex("(?is)<a\\s[^>]*?href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a\\s*>")
private val TAG = Regex("(?s)<[^>]*>")
private val NUMERIC_ENTITY = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")

/**
 * Calendar notes as plain text. Google sends HTML ("Hello<br><a href=...>link</a>"), Microsoft a plain preview. Tags go,
 * line breaks stay, a link keeps its address beside its text, entities are decoded. The apps show the result as text
 * only (ADR-006): nothing in it is ever rendered or followed.
 */
internal fun plainText(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    var s = raw.replace("\r\n", "\n")
    s = LINK.replace(s) { m ->
        val href = m.groupValues[1].trim()
        val text = TAG.replace(m.groupValues[2], "").trim()
        when {
            text.isEmpty() -> href
            href.isEmpty() || href == text || !href.startsWith("http") -> text
            else -> "$text ($href)"
        }
    }
    // <br> is a line break as written; block tags (paragraphs, list items) start a new line, however many meet.
    s = BREAK.replace(s, "\n")
    s = BLOCK.replace(s, "\u0000")
    s = TAG.replace(s, "")
    s = BLOCK_RUN.replace(s, "\n")
    s = NUMERIC_ENTITY.replace(s) { m ->
        val v = m.groupValues[1]
        val code = if (v.startsWith("x")) v.substring(1).toIntOrNull(16) else v.toIntOrNull()
        if (code != null && Character.isValidCodePoint(code) && code >= 32) String(Character.toChars(code)) else ""
    }
    s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
    val lines = s.lines().map { it.trimEnd() }
    val out = StringBuilder()
    var blank = 0
    for (l in lines) {
        if (l.isBlank()) { blank++; continue }
        if (out.isNotEmpty()) out.append(if (blank > 0) "\n\n" else "\n")
        out.append(l)
        blank = 0
    }
    return out.toString().trim().ifEmpty { null }
}

private fun rfc3339(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))
private const val DAY_MS = 86_400_000L
private fun utcMidnight(date: String): Long = LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

class GoogleCalendar internal constructor(private val http: Http) : CalendarProvider {
    constructor() : this(Http())
    override val id = "google"
    override val requiredScope = "https://www.googleapis.com/auth/calendar.readonly"
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
                        // Meet links arrive as hangoutLink, other conferencing as a "video" entry point.
                        val video = ((e["conferenceData"] as? JsonObject)?.get("entryPoints") as? JsonArray).orEmpty()
                            .filterIsInstance<JsonObject>().firstOrNull { it["entryPointType"].str() == "video" }?.get("uri").str()
                        add(RemoteEvent(
                            "$calId/${e["id"].str()}", e["summary"].str() ?: "", startMs, endMs, allDay, e["location"].str(), calName,
                            description = plainText(e["description"].str()),
                            joinUrl = httpsOrNull(e["hangoutLink"].str()) ?: httpsOrNull(video),
                            webUrl = httpsOrNull(e["htmlLink"].str()),
                        ))
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
    override val requiredScope = "Calendars.Read"
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
                    "\$select" to "id,subject,start,end,isAllDay,location,isCancelled,bodyPreview,onlineMeeting,webLink",
                ))
                while (url != null) {
                    val resp = http.getJson(url, accessToken, utc)
                    resp["value"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { e ->
                        if (e["isCancelled"]?.jsonPrimitive?.booleanOrNull == true) return@forEach
                        val allDay = e["isAllDay"]?.jsonPrimitive?.booleanOrNull == true
                        val start = e["start"]?.jsonObject?.get("dateTime").str() ?: return@forEach
                        val end = e["end"]?.jsonObject?.get("dateTime").str() ?: start
                        // With the UTC preference, timed values are UTC wall times; all-day values are calendar dates.
                        fun parse(s: String): Long {
                            val utc = LocalDateTime.parse(s.substringBefore('.')).toInstant(ZoneOffset.UTC).toEpochMilli()
                            // All-day boundaries may arrive shifted by the organiser's offset (e.g. 23:00 the day
                            // before): round to the nearest UTC midnight so the calendar date is right either way.
                            return if (allDay) Math.floorDiv(utc + DAY_MS / 2, DAY_MS) * DAY_MS else utc
                        }
                        val location = e["location"]?.jsonObject?.get("displayName").str()?.takeIf { it.isNotBlank() }
                        val join = (e["onlineMeeting"] as? JsonObject)?.get("joinUrl").str()
                        add(RemoteEvent(
                            "$calId/${e["id"].str()}", e["subject"].str() ?: "", parse(start), parse(end), allDay, location, calName,
                            description = plainText(e["bodyPreview"].str()),
                            joinUrl = httpsOrNull(join),
                            webUrl = httpsOrNull(e["webLink"].str()),
                        ))
                    }
                    url = resp["@odata.nextLink"].str()
                }
            }
        }
    }
}
