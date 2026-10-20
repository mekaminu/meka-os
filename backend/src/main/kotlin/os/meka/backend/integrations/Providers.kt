package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import os.meka.core.domain.EventDraft
import os.meka.core.domain.EventFields
import os.meka.core.domain.EventEditChange
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
    /** Meeting prep, slice 1: the other people on it, names only ([EventGuests]); never the owner, rooms or a decline. */
    val guests: List<String> = emptyList(),
    /**
     * Meeting prep, slice 4: each guest's address (lower case) by the name in [guests], for looking up their newest
     * email in Meka's own mailboxes. Kept on the server only: never synced, stored or logged.
     */
    val guestAddresses: Map<String, String> = emptyMap(),
    /**
     * Edit your calendars, completeness slice 1: the repeating series it is one of, in [id]'s `<calendar>/<event>`
     * form (Google `recurringEventId`, Outlook `seriesMasterId`); null for a one-off. Synced only as a hash.
     */
    val seriesId: String? = null,
    /**
     * Edit your calendars, completeness slice 3: the event's calendar is shared with the owner to see, not change
     * (Google `accessRole` reader / freeBusyReader, Outlook `canEdit` false), mirrored as event.readOnly.
     */
    val readOnly: Boolean = false,
    /**
     * Edit your calendars, completeness slice 6: the IANA zone the event was set in ([EventZones.iana]); null for
     * all-day events and zones that don't parse. Mirrored as event.timeZone.
     */
    val timeZone: String? = null,
)

/**
 * Completeness slice 6: an event's own time zone as an IANA id. Google gives IANA ids already; Outlook's
 * `originalStartTimeZone` is usually a Windows name, so the common ones are mapped and anything else that isn't an
 * IANA id is dropped (the apps then simply show no extra line).
 */
object EventZones {
    private val WINDOWS = mapOf(
        "GMT Standard Time" to "Europe/London",
        "Greenwich Standard Time" to "Atlantic/Reykjavik",
        "W. Europe Standard Time" to "Europe/Berlin",
        "Romance Standard Time" to "Europe/Paris",
        "Central Europe Standard Time" to "Europe/Budapest",
        "Central European Standard Time" to "Europe/Warsaw",
        "GTB Standard Time" to "Europe/Bucharest",
        "FLE Standard Time" to "Europe/Kiev",
        "E. Europe Standard Time" to "Europe/Chisinau",
        "Russian Standard Time" to "Europe/Moscow",
        "Turkey Standard Time" to "Europe/Istanbul",
        "Israel Standard Time" to "Asia/Jerusalem",
        "Egypt Standard Time" to "Africa/Cairo",
        "South Africa Standard Time" to "Africa/Johannesburg",
        "W. Central Africa Standard Time" to "Africa/Lagos",
        "E. Africa Standard Time" to "Africa/Nairobi",
        "Morocco Standard Time" to "Africa/Casablanca",
        "Arabian Standard Time" to "Asia/Dubai",
        "Arab Standard Time" to "Asia/Riyadh",
        "India Standard Time" to "Asia/Kolkata",
        "Pakistan Standard Time" to "Asia/Karachi",
        "China Standard Time" to "Asia/Shanghai",
        "Singapore Standard Time" to "Asia/Singapore",
        "Tokyo Standard Time" to "Asia/Tokyo",
        "Korea Standard Time" to "Asia/Seoul",
        "AUS Eastern Standard Time" to "Australia/Sydney",
        "New Zealand Standard Time" to "Pacific/Auckland",
        "Eastern Standard Time" to "America/New_York",
        "Central Standard Time" to "America/Chicago",
        "Mountain Standard Time" to "America/Denver",
        "US Mountain Standard Time" to "America/Phoenix",
        "Pacific Standard Time" to "America/Los_Angeles",
        "Atlantic Standard Time" to "America/Halifax",
        "E. South America Standard Time" to "America/Sao_Paulo",
        "Canada Central Standard Time" to "America/Regina",
        "Hawaiian Standard Time" to "Pacific/Honolulu",
        "UTC" to "UTC",
        "Coordinated Universal Time" to "UTC",
    )

    /** [raw] as an IANA id the JVM knows, or null ("tzone://Microsoft/Utc" and "Customized Time Zone" included). */
    fun iana(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val id = WINDOWS[s] ?: s
        if (id.length > 64 || '/' !in id && id != "UTC") return null
        return runCatching { java.time.ZoneId.of(id).id }.getOrNull()
    }
}

/**
 * Meeting prep, slice 1: the other people on an event, as names only (an address is never synced: a guest with no
 * display name is named from the part before the @, "sam.taylor" → "Sam Taylor"). Leaves out the account's owner,
 * rooms and resources, and anyone who declined; at most [EventFields.MAX_GUESTS], in the provider's order.
 */
object EventGuests {
    /** Google's `attendees` (and the `organizer`): `self`, `resource`, `responseStatus`, `displayName`, `email`. */
    fun google(attendees: JsonArray?, organizer: JsonObject?): List<String> = googlePeople(attendees, organizer).map { it.first }

    /** Slice 4: [google]'s names, each with its address when the provider gave one. */
    fun googlePeople(attendees: JsonArray?, organizer: JsonObject?): List<Pair<String, String?>> {
        val people = attendees.orEmpty().filterIsInstance<JsonObject>().filter { a ->
            a["self"]?.jsonPrimitive?.booleanOrNull != true && a["resource"]?.jsonPrimitive?.booleanOrNull != true &&
                a["responseStatus"].str() != "declined"
        }.map { it["displayName"].str() to it["email"].str() }
        val org = organizer?.takeIf { it["self"]?.jsonPrimitive?.booleanOrNull != true }
            ?.let { listOf(it["displayName"].str() to it["email"].str()) }.orEmpty()
        return pairs(org + people)
    }

    /** Graph's `attendees` (`emailAddress{name,address}`, `type`, `status{response}`) and `organizer`, without [self]. */
    fun graph(attendees: JsonArray?, organizer: JsonObject?, self: String?): List<String> = graphPeople(attendees, organizer, self).map { it.first }

    /** Slice 4: [graph]'s names, each with its address when the provider gave one. */
    fun graphPeople(attendees: JsonArray?, organizer: JsonObject?, self: String?): List<Pair<String, String?>> {
        fun pair(o: JsonObject?): Pair<String?, String?>? {
            val e = o?.get("emailAddress") as? JsonObject ?: return null
            return e["name"].str() to e["address"].str()
        }
        val me = self?.lowercase()
        val people = attendees.orEmpty().filterIsInstance<JsonObject>().filter { a ->
            a["type"].str() != "resource" && (a["status"] as? JsonObject)?.get("response").str() != "declined"
        }.mapNotNull { pair(it) }
        val org = listOfNotNull(pair(organizer))
        return pairs((org + people).filter { (_, address) -> address == null || address.lowercase() != me })
    }

    fun names(people: List<Pair<String?, String?>>): List<String> = pairs(people).map { it.first }

    /** Slice 4: [names] with each kept name's address (lower case; null when there was none). */
    fun pairs(people: List<Pair<String?, String?>>): List<Pair<String, String?>> {
        val seenAddresses = HashSet<String>()
        val out = LinkedHashMap<String, Pair<String, String?>>()
        for ((name, address) in people) {
            if (address != null && !seenAddresses.add(address.lowercase())) continue
            val n = (name?.trim()?.takeIf { it.isNotEmpty() && '@' !in it } ?: address?.let(::fromAddress))
                ?.replace('\n', ' ')?.trim()?.take(60) ?: continue
            if (n.isEmpty()) continue
            out.putIfAbsent(n.lowercase(), n to address?.trim()?.lowercase()?.takeIf { '@' in it })
        }
        return out.values.take(EventFields.MAX_GUESTS)
    }

    /** Slice 4: name → address for the people that have one. */
    fun addresses(guests: List<Pair<String, String?>>): Map<String, String> =
        guests.mapNotNull { (n, a) -> a?.let { n to it } }.toMap()

    /** "sam.taylor@example.com" → "Sam Taylor"; null for a no-reply or room-like address. */
    fun fromAddress(address: String): String? {
        val local = address.substringBefore('@').substringBefore('+').trim()
        if (local.isEmpty() || local.lowercase().let { it.startsWith("noreply") || it.startsWith("no-reply") }) return null
        val words = local.split('.', '_', '-').filter { it.isNotEmpty() && it.any(Char::isLetter) }
        if (words.isEmpty()) return null
        return words.joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
    }
}

/** The refresh token was revoked or expired: the owner has to connect the account again. */
class ReconnectRequired(message: String) : RuntimeException(message)

/**
 * A calendar provider (ADR-008). Read-only unless the owner allows editing on the account (calendar editing): only
 * then is the scope that can change events ([writeScope]) asked for, and only when it was granted may it be used.
 */
interface CalendarProvider {
    val id: String
    /** The scope without which the account is useless (checked against what the owner actually granted). */
    val requiredScope: String
    /** The scope that lets MEKA add, change and delete events (asked for only with Allow editing). */
    val writeScope: String
    /** [editing]: ask for [writeScope] as well. */
    fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String, editing: Boolean = false): String
    /** [editing]: the sign-in asked for [writeScope] too (Microsoft wants the same scopes when redeeming the code). */
    fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String, editing: Boolean = false): TokenSet
    /** [editing]: the account may edit, so the new access token should carry [writeScope] (Microsoft asks per refresh). */
    fun refresh(client: OAuthClient, refreshToken: String, editing: Boolean = false): TokenSet
    fun accountEmail(accessToken: String): String
    fun events(accessToken: String, fromMs: Long, toMs: Long): List<RemoteEvent>

    // Writes (calendar editing, slice 2a). Only ever called for an account with `can_edit`, with an access token
    // refreshed for [writeScope], and only for an edit Meka made (backend/integrations/CalendarWriter.kt).

    /** The provider's current copy of one event ([RemoteEvent.id] form), or null when it's gone or cancelled. */
    fun event(accessToken: String, remoteId: String): RemoteCopy? = throw UnsupportedOperationException("read-only provider")

    /**
     * Adds [draft] to the account's main calendar. [idemKey] (the edit's id, `[0-9a-f]`) makes a retry add nothing
     * more. Returns the new event's id.
     */
    fun createEvent(accessToken: String, draft: EventDraft, idemKey: String): String = throw UnsupportedOperationException("read-only provider")

    /** Sets the [changes] fields of [remoteId] to [draft]'s; [notifyGuests] tells the guests (an organiser's change). */
    fun updateEvent(accessToken: String, remoteId: String, draft: EventDraft, changes: Set<EventEditChange>, notifyGuests: Boolean): Unit =
        throw UnsupportedOperationException("read-only provider")

    /** Deletes [remoteId]; [notifyGuests] sends them the cancellation. Returns false when it was already gone. */
    fun deleteEvent(accessToken: String, remoteId: String, notifyGuests: Boolean): Boolean = throw UnsupportedOperationException("read-only provider")

    /**
     * Completeness slice 1: sets the [changes] fields of the whole series [seriesId] from [draft] (one event of it as
     * Meka left it). TIME keeps each event's own day: the series keeps its first day and takes [draft]'s time of day
     * (in the series' own time zone) and length. Only for a timed series; the caller checks that.
     */
    fun updateSeries(accessToken: String, seriesId: String, draft: EventDraft, changes: Set<EventEditChange>, notifyGuests: Boolean): Unit =
        throw UnsupportedOperationException("read-only provider")

    /**
     * Completeness slice 2, "this and following": the series [seriesId] ends just before [fromStartMs] (the original
     * start of the event Meka edited) and, when [draft] is given, a new series starts there carrying the same repeat
     * rule with [changes] from [draft] ([idemKey] makes it once on a retry). [draft] null deletes this and the ones
     * after. Returns why it can't be done in words (nothing was changed then), or null once done.
     */
    fun splitSeries(
        accessToken: String, seriesId: String, fromStartMs: Long, draft: EventDraft?, changes: Set<EventEditChange>,
        notifyGuests: Boolean, idemKey: String,
    ): String? = throw UnsupportedOperationException("read-only provider")
}

/**
 * Completeness slice 2: a series' repeat lines (RFC 5545 `RRULE` / `EXDATE` / `RDATE`, as Google keeps them) cut at
 * one event. Non-AI, pure.
 */
internal object SeriesSplit {
    /** UNTIL for the earlier part: a second before [fromStartMs], in UTC (`20261019T165959Z`). */
    fun until(fromStartMs: Long): String =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(fromStartMs - 1_000))

    /** The earlier part's lines: its RRULE ends at [until] (COUNT and UNTIL dropped, as RFC 5545 allows only one); null without an RRULE. */
    fun endBefore(lines: List<String>, until: String): List<String>? {
        if (lines.none { isRule(it) }) return null
        return lines.map { line ->
            if (!isRule(line)) line
            else "RRULE:" + (parts(line).filterNot { it.startsWith("COUNT=", true) || it.startsWith("UNTIL=", true) } + "UNTIL=$until").joinToString(";")
        }
    }

    /** Why the later part can't carry on with the same rule, or null: a set number of times, or extra dates, are Google's to cut. */
    fun problem(lines: List<String>): String? = when {
        lines.none { isRule(it) } -> "It doesn't repeat by a rule in Google · change it there"
        lines.filter { isRule(it) }.any { l -> parts(l).any { it.startsWith("COUNT=", true) } } ->
            "It repeats a set number of times · change it in Google, or choose Every event"
        lines.any { it.startsWith("RDATE", true) } -> "It has extra dates · change it in Google, or choose Every event"
        else -> null
    }

    private fun isRule(l: String) = l.startsWith("RRULE:", ignoreCase = true)
    private fun parts(l: String) = l.substringAfter(':').split(';').filter { it.isNotBlank() }
}

/** The provider's copy of one event, as the clash check and the guards need it. */
data class RemoteCopy(
    val event: EventDraft,
    /** Meka organises it (or it has no organiser): only then may MEKA change or delete it. */
    val organisedByMe: Boolean,
    /** Other people invited (rooms and Meka himself not counted). */
    val guests: Int,
    /** Completeness slice 1: the series this event is one of ([RemoteEvent.seriesId] form), or null. */
    val seriesId: String? = null,
    /** Completeness slice 2: where the series first put this event (Google `originalStartTime`), or null. */
    val originalStartMs: Long? = null,
)

/** The provider said no to a write (HTTP 403: the write permission was taken away, or the calendar is read-only). */
class WriteRefused(message: String) : RuntimeException(message)

/** The provider's event id inside our `<calendar>/<event>` id. */
internal fun eventPart(remoteId: String): String = remoteId.substringAfter('/', "")
internal fun calendarPart(remoteId: String): String = remoteId.substringBefore('/')

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

    /** A form POST whose answer is only its status (token revocation); never throws for a 4xx. */
    fun postFormStatus(url: String, form: Map<String, String>): Int {
        val body = form.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        return client.send(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()
    }

    /** One API call's answer: the status and the body when it was JSON. */
    data class Answer(val status: Int, val body: JsonObject?)

    /**
     * A write or a single read (calendar editing): any method, an optional JSON body. Hands back the status for the
     * caller to judge (404/409/410 mean things there); 401 means the token is no longer good (reconnect); 5xx and
     * timeouts throw, so the edit is tried again later.
     */
    fun call(method: String, url: String, bearer: String, body: JsonObject? = null, headers: Map<String, String> = emptyMap()): Answer {
        val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer $bearer")
        headers.forEach { (k, v) -> b.header(k, v) }
        if (body != null) b.header("Content-Type", "application/json")
        b.method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body.toString()) else HttpRequest.BodyPublishers.noBody())
        val resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        val code = resp.statusCode()
        if (code == 401) throw ReconnectRequired("provider API refused the token")
        if (code == 403) throw WriteRefused("provider refused the change" + (refusedReason(resp.body())?.let { " [$it]" } ?: ""))
        if (code >= 500 || code == 429) throw IllegalStateException("provider HTTP $code")
        return Answer(code, runCatching { json.parseToJsonElement(resp.body()).jsonObject }.getOrNull())
    }

    /**
     * Read the full email, slice 2: a GET whose answer is raw bytes (an attachment's `$value`), read up to [max] bytes;
     * more throws [MailAttachmentTooBig]. Null body for a 404/400 (gone); 401 reconnect; anything else not 2xx throws.
     */
    fun bytes(url: String, bearer: String, max: Long): Pair<Int, ByteArray?> {
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer $bearer").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        resp.body().use { input ->
            val code = resp.statusCode()
            if (code == 401) throw ReconnectRequired("provider API refused the token")
            if (code == 404 || code == 400) return code to null
            if (code !in 200..299) throw IllegalStateException("provider HTTP $code")
            val declared = resp.headers().firstValueAsLong("Content-Length").orElse(-1)
            if (declared > max) throw MailAttachmentTooBig()
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) throw MailAttachmentTooBig()
            }
            return code to out.toByteArray()
        }
    }

    /**
     * Inbox fixes, slice 7: the reason word in a 403's error body (Google: `error.errors[].reason`, `error.details[].reason`
     * or `error.status`; Microsoft: `error.code`), kept only when it is one of [os.meka.core.domain.MailCheckRules.KNOWN_REASONS]
     * (so "the Gmail API is off" can be told from a rate limit); the provider's own message is never kept.
     */
    internal fun refusedReason(body: String?): String? {
        val err = runCatching { json.parseToJsonElement(body ?: return null).jsonObject["error"] as? JsonObject }.getOrNull() ?: return null
        val words = buildList {
            (err["errors"] as? JsonArray)?.forEach { e -> (e as? JsonObject)?.get("reason").str()?.let(::add) }
            (err["details"] as? JsonArray)?.forEach { d -> (d as? JsonObject)?.get("reason").str()?.let(::add) }
            err["status"].str()?.let(::add)
            err["code"].str()?.let(::add)
        }
        val known = os.meka.core.domain.MailCheckRules.KNOWN_REASONS
        return words.firstOrNull { it in os.meka.core.domain.MailCheckRules.API_OFF_REASONS }
            ?: words.firstOrNull { it in known }
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
/**
 * Whether a granted scope string ("openid https://www.googleapis.com/auth/calendar.events …") holds [wanted] exactly:
 * as a whole word, or a word ending in "/<wanted>" (Microsoft's "https://graph.microsoft.com/Calendars.ReadWrite").
 * `calendar.events.readonly` doesn't count as `calendar.events`.
 */
internal fun grants(scope: String?, wanted: String): Boolean {
    if (scope.isNullOrBlank()) return false
    val short = wanted.substringAfterLast('/')
    return scope.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }.any {
        it.equals(wanted, ignoreCase = true) || it.endsWith("/$short", ignoreCase = true) || it.equals(short, ignoreCase = true)
    }
}

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
private fun isoDate(ms: Long): String = LocalDate.ofEpochDay(Math.floorDiv(ms, DAY_MS)).toString()
private val ALL_CHANGES = EventEditChange.entries.toSet()
private const val DAY_MS = 86_400_000L
private fun utcMidnight(date: String): Long = LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

class GoogleCalendar internal constructor(private val http: Http) : CalendarProvider {
    constructor() : this(Http())
    override val id = "google"
    override val requiredScope = "https://www.googleapis.com/auth/calendar.readonly"
    // calendar.events changes events but can't list calendars, so editing keeps calendar.readonly as well.
    override val writeScope = "https://www.googleapis.com/auth/calendar.events"
    private val scopes = "openid email https://www.googleapis.com/auth/calendar.readonly"

    override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String, editing: Boolean) =
        "https://accounts.google.com/o/oauth2/v2/auth?" + Http.query(mapOf(
            "client_id" to client.clientId, "redirect_uri" to redirectUri, "response_type" to "code",
            "scope" to if (editing) "$scopes $writeScope" else scopes, "access_type" to "offline", "prompt" to "consent select_account",
            "state" to state, "code_challenge" to codeChallenge, "code_challenge_method" to "S256",
        ))

    override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String, editing: Boolean) =
        http.postForm("https://oauth2.googleapis.com/token", mapOf(
            "code" to code, "client_id" to client.clientId, "client_secret" to client.clientSecret,
            "redirect_uri" to redirectUri, "grant_type" to "authorization_code", "code_verifier" to verifier,
        )).tokens()

    // Google's refreshed token carries every scope the owner granted; nothing to ask for.
    override fun refresh(client: OAuthClient, refreshToken: String, editing: Boolean) =
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
            // Completeness slice 3: a calendar he may only see (a holidays calendar, someone else's shared one).
            val readOnly = cal["accessRole"].str()?.let { it != "owner" && it != "writer" } ?: false
            // Completeness slice 6: the calendar's own zone, for events whose start names none.
            val calZone = cal["timeZone"].str()
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
                        val guestPeople = EventGuests.googlePeople(e["attendees"] as? JsonArray, e["organizer"] as? JsonObject)
                        add(RemoteEvent(
                            "$calId/${e["id"].str()}", e["summary"].str() ?: "", startMs, endMs, allDay, e["location"].str(), calName,
                            description = plainText(e["description"].str()),
                            joinUrl = httpsOrNull(e["hangoutLink"].str()) ?: httpsOrNull(video),
                            webUrl = httpsOrNull(e["htmlLink"].str()),
                            guests = guestPeople.map { it.first },
                            guestAddresses = EventGuests.addresses(guestPeople),
                            seriesId = e["recurringEventId"].str()?.let { "$calId/$it" },
                            readOnly = readOnly,
                            timeZone = if (allDay) null else EventZones.iana(start["timeZone"].str() ?: calZone),
                        ))
                    }
                    page = resp["nextPageToken"].str()
                } while (page != null)
            }
        }
    }

    private val api = "https://www.googleapis.com/calendar/v3"
    private fun eventUrl(remoteId: String) = "$api/calendars/${Http.enc(calendarPart(remoteId))}/events/${Http.enc(eventPart(remoteId))}"
    private fun updates(notify: Boolean) = "sendUpdates=" + if (notify) "all" else "none"

    override fun event(accessToken: String, remoteId: String): RemoteCopy? {
        if (eventPart(remoteId).isEmpty()) return null
        val a = http.call("GET", eventUrl(remoteId), accessToken)
        if (a.status == 404 || a.status == 410) return null
        val e = a.body?.takeIf { a.status in 200..299 } ?: throw WriteRefused("Google HTTP ${a.status}")
        if (e["status"].str() == "cancelled") return null
        val start = e["start"] as? JsonObject ?: return null
        val end = e["end"] as? JsonObject ?: start
        val allDay = start["date"] != null
        val startMs = if (allDay) utcMidnight(start["date"].str()!!) else OffsetDateTime.parse(start["dateTime"].str()).toInstant().toEpochMilli()
        val endMs = if (allDay) utcMidnight(end["date"].str() ?: start["date"].str()!!)
        else end["dateTime"].str()?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() } ?: startMs
        val organiser = e["organizer"] as? JsonObject
        val mine = organiser == null || organiser["self"]?.jsonPrimitive?.booleanOrNull == true
        val guests = (e["attendees"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().count {
            it["self"]?.jsonPrimitive?.booleanOrNull != true && it["resource"]?.jsonPrimitive?.booleanOrNull != true
        }
        val draft = EventDraft(e["summary"].str() ?: "", startMs, endMs, allDay, e["location"].str(), plainText(e["description"].str()))
        val original = (e["originalStartTime"] as? JsonObject)?.let { o ->
            o["dateTime"].str()?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() } ?: o["date"].str()?.let(::utcMidnight)
        }
        return RemoteCopy(
            draft, mine, guests, seriesId = e["recurringEventId"].str()?.let { "${calendarPart(remoteId)}/$it" }, originalStartMs = original,
        )
    }

    override fun createEvent(accessToken: String, draft: EventDraft, idemKey: String): String {
        // Our own event id (base32hex: 0-9 and a-v), so a retry finds it already there (409) instead of adding twice.
        val id = "meka$idemKey"
        require(id.length in 5..1024 && id.all { it in '0'..'9' || it in 'a'..'v' }) { "edit id isn't base32hex" }
        val primary = http.call("GET", "$api/calendars/primary", accessToken)
        val cal = primary.body?.get("id").str()?.takeIf { primary.status in 200..299 } ?: throw WriteRefused("Google HTTP ${primary.status}")
        val a = http.call("POST", "$api/calendars/${Http.enc(cal)}/events?${updates(false)}", accessToken, googleBody(draft, ALL_CHANGES, id))
        if (a.status !in 200..299 && a.status != 409) throw WriteRefused("Google HTTP ${a.status}")
        return "$cal/$id"
    }

    override fun updateEvent(accessToken: String, remoteId: String, draft: EventDraft, changes: Set<EventEditChange>, notifyGuests: Boolean) {
        val a = http.call("PATCH", "${eventUrl(remoteId)}?${updates(notifyGuests)}", accessToken, googleBody(draft, changes, null))
        if (a.status !in 200..299) throw WriteRefused("Google HTTP ${a.status}")
    }

    override fun deleteEvent(accessToken: String, remoteId: String, notifyGuests: Boolean): Boolean {
        val a = http.call("DELETE", "${eventUrl(remoteId)}?${updates(notifyGuests)}", accessToken)
        if (a.status == 404 || a.status == 410) return false
        if (a.status !in 200..299) throw WriteRefused("Google HTTP ${a.status}")
        return true
    }

    override fun updateSeries(accessToken: String, seriesId: String, draft: EventDraft, changes: Set<EventEditChange>, notifyGuests: Boolean) {
        val other = changes - EventEditChange.TIME
        val body = buildJsonObject {
            googleBody(draft, other, null).forEach { (k, v) -> put(k, v) }
            if (EventEditChange.TIME in changes) {
                val a = http.call("GET", eventUrl(seriesId), accessToken)
                val master = a.body?.takeIf { a.status in 200..299 } ?: throw WriteRefused("Google HTTP ${a.status}")
                val start = master["start"] as? JsonObject ?: throw WriteRefused("Google series has no start")
                val (s, e) = seriesTimes(start["dateTime"].str() ?: throw WriteRefused("an all-day series"), start["timeZone"].str(), draft)
                put("start", s)
                put("end", e)
            }
        }
        val a = http.call("PATCH", "${eventUrl(seriesId)}?${updates(notifyGuests)}", accessToken, body)
        if (a.status !in 200..299) throw WriteRefused("Google HTTP ${a.status}")
    }

    override fun splitSeries(
        accessToken: String, seriesId: String, fromStartMs: Long, draft: EventDraft?, changes: Set<EventEditChange>,
        notifyGuests: Boolean, idemKey: String,
    ): String? {
        val g = http.call("GET", eventUrl(seriesId), accessToken)
        val master = g.body?.takeIf { g.status in 200..299 } ?: throw WriteRefused("Google HTTP ${g.status}")
        val lines = (master["recurrence"] as? JsonArray).orEmpty().mapNotNull { it.str() }
        val start = master["start"] as? JsonObject ?: throw WriteRefused("Google series has no start")
        val startText = start["dateTime"].str() ?: return "An all-day series · change it in Google, or choose This event"
        val earlier = SeriesSplit.endBefore(lines, SeriesSplit.until(fromStartMs)) ?: return SeriesSplit.problem(lines)
        if (draft != null) {
            SeriesSplit.problem(lines)?.let { return it }
            val zone = start["timeZone"].str()
            val z = zone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
            val from = java.time.Instant.ofEpochMilli(fromStartMs).atZone(z)
            val (s, e) = if (EventEditChange.TIME in changes) {
                seriesTimes(from.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), z.id, draft)
            } else {
                // The series' own time and length from this event's day.
                val masterEnd = (master["end"] as? JsonObject)?.get("dateTime").str()
                val length = masterEnd?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() - OffsetDateTime.parse(startText).toInstant().toEpochMilli() }
                    ?: (draft.endAtMs - draft.startAtMs)
                fun obj(t: java.time.ZonedDateTime) = buildJsonObject {
                    put("dateTime", t.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    put("timeZone", z.id)
                }
                obj(from) to obj(from.plusNanos(length * 1_000_000L))
            }
            val id = "meka$idemKey"
            require(id.length in 5..1024 && id.all { it in '0'..'9' || it in 'a'..'v' }) { "edit id isn't base32hex" }
            val body = buildJsonObject {
                put("id", id)
                // What the new part keeps of the series (never its guests: the caller sends none to a series with guests).
                for (k in listOf("summary", "location", "description", "colorId", "reminders", "transparency", "visibility")) master[k]?.let { put(k, it) }
                googleBody(draft, changes - EventEditChange.TIME, null).forEach { (k, v) -> put(k, v) }
                put("start", s)
                put("end", e)
                put("recurrence", JsonArray(lines.map { JsonPrimitive(it) }))
            }
            // Made first, so a retry after the series was cut never loses the later part (a retry's insert finds it: 409).
            val a = http.call("POST", "$api/calendars/${Http.enc(calendarPart(seriesId))}/events?${updates(false)}", accessToken, body)
            if (a.status !in 200..299 && a.status != 409) throw WriteRefused("Google HTTP ${a.status}")
        }
        val cut = buildJsonObject { put("recurrence", JsonArray(earlier.map { JsonPrimitive(it) })) }
        val p = http.call("PATCH", "${eventUrl(seriesId)}?${updates(notifyGuests)}", accessToken, cut)
        if (p.status !in 200..299) throw WriteRefused("Google HTTP ${p.status}")
        return null
    }

    /**
     * The series' new start and end: its own first day, [draft]'s time of day in the series' time zone ([zone], UTC
     * when it has none), and [draft]'s length.
     */
    internal fun seriesTimes(masterStart: String, zone: String?, draft: EventDraft): Pair<JsonObject, JsonObject> {
        val z = zone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
        val day = OffsetDateTime.parse(masterStart).atZoneSameInstant(z).toLocalDate()
        val time = java.time.Instant.ofEpochMilli(draft.startAtMs).atZone(z).toLocalTime()
        val newStart = java.time.ZonedDateTime.of(day, time, z)
        val newEnd = newStart.plusNanos((draft.endAtMs - draft.startAtMs) * 1_000_000L)
        fun obj(t: java.time.ZonedDateTime) = buildJsonObject {
            put("dateTime", t.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("timeZone", z.id)
        }
        return obj(newStart) to obj(newEnd)
    }

    /** The fields of [changes] as Google writes them. An insert ([id] set) leaves the unused time form out. */
    internal fun googleBody(d: EventDraft, changes: Set<EventEditChange>, id: String?) = buildJsonObject {
        id?.let { put("id", it) }
        if (EventEditChange.TITLE in changes) put("summary", d.title)
        if (EventEditChange.TIME in changes) {
            put("start", googleTime(d.startAtMs, d.allDay, patch = id == null))
            put("end", googleTime(d.endAtMs, d.allDay, patch = id == null))
        }
        if (EventEditChange.LOCATION in changes) put("location", d.location)
        if (EventEditChange.NOTES in changes) put("description", d.notes)
    }

    /** A patch clears the other form, so an event can turn all-day or timed. */
    private fun googleTime(ms: Long, allDay: Boolean, patch: Boolean) = buildJsonObject {
        if (allDay) put("date", isoDate(ms)) else put("dateTime", rfc3339(ms))
        if (patch) put(if (allDay) "dateTime" else "date", JsonNull)
    }
}

class MicrosoftCalendar internal constructor(private val http: Http) : CalendarProvider {
    constructor() : this(Http())
    override val id = "microsoft"
    override val requiredScope = "Calendars.Read"
    override val writeScope = "Calendars.ReadWrite"
    // "consumers": personal Microsoft accounts (outlook.com/hotmail/live), which is what the owner uses.
    private val authority = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private val scopes = "offline_access openid email User.Read Calendars.Read"
    private val editScopes = "offline_access openid email User.Read Calendars.ReadWrite"
    private fun scopes(editing: Boolean) = if (editing) editScopes else scopes

    override fun authorizeUrl(client: OAuthClient, redirectUri: String, state: String, codeChallenge: String, editing: Boolean) =
        "$authority/authorize?" + Http.query(mapOf(
            "client_id" to client.clientId, "response_type" to "code", "redirect_uri" to redirectUri,
            "response_mode" to "query", "scope" to scopes(editing), "state" to state, "prompt" to "select_account",
            "code_challenge" to codeChallenge, "code_challenge_method" to "S256",
        ))

    override fun exchangeCode(client: OAuthClient, redirectUri: String, code: String, verifier: String, editing: Boolean) =
        http.postForm("$authority/token", mapOf(
            "client_id" to client.clientId, "client_secret" to client.clientSecret, "scope" to scopes(editing), "code" to code,
            "redirect_uri" to redirectUri, "grant_type" to "authorization_code", "code_verifier" to verifier,
        )).tokens()

    // Microsoft rotates refresh tokens: the caller stores the new one every time.
    override fun refresh(client: OAuthClient, refreshToken: String, editing: Boolean) =
        http.postForm("$authority/token", mapOf(
            "client_id" to client.clientId, "client_secret" to client.clientSecret, "scope" to scopes(editing),
            "refresh_token" to refreshToken, "grant_type" to "refresh_token",
        )).tokens(previousRefresh = refreshToken)

    override fun accountEmail(accessToken: String): String {
        val me = http.getJson("https://graph.microsoft.com/v1.0/me?\$select=mail,userPrincipalName", accessToken)
        return me["mail"].str() ?: me["userPrincipalName"].str() ?: error("no email")
    }

    override fun events(accessToken: String, fromMs: Long, toMs: Long): List<RemoteEvent> {
        val utc = mapOf("Prefer" to "outlook.timezone=\"UTC\"")
        // Meeting prep, slice 1: the account's own address, so Meka isn't listed among his own meetings' guests.
        val self = runCatching { accountEmail(accessToken) }.getOrNull()
        val calendars = http.getJson("https://graph.microsoft.com/v1.0/me/calendars?\$select=id,name,canEdit&\$top=50", accessToken)["value"]
            ?.jsonArray.orEmpty().map { it.jsonObject }
        return calendars.flatMap { cal ->
            val calId = cal["id"].str() ?: return@flatMap emptyList()
            val calName = cal["name"].str()
            val readOnly = cal["canEdit"]?.jsonPrimitive?.booleanOrNull == false
            buildList {
                var url: String? = "https://graph.microsoft.com/v1.0/me/calendars/${Http.enc(calId)}/calendarView?" + Http.query(mapOf(
                    "startDateTime" to rfc3339(fromMs), "endDateTime" to rfc3339(toMs), "\$top" to "500",
                    "\$select" to "id,subject,start,end,isAllDay,location,isCancelled,bodyPreview,onlineMeeting,webLink,attendees,organizer,seriesMasterId,originalStartTimeZone",
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
                        val people = EventGuests.graphPeople(e["attendees"] as? JsonArray, e["organizer"] as? JsonObject, self)
                        add(RemoteEvent(
                            "$calId/${e["id"].str()}", e["subject"].str() ?: "", parse(start), parse(end), allDay, location, calName,
                            description = plainText(e["bodyPreview"].str()),
                            joinUrl = httpsOrNull(join),
                            webUrl = httpsOrNull(e["webLink"].str()),
                            guests = people.map { it.first },
                            guestAddresses = EventGuests.addresses(people),
                            seriesId = e["seriesMasterId"].str()?.let { "$calId/$it" },
                            readOnly = readOnly,
                            timeZone = if (allDay) null else EventZones.iana(e["originalStartTimeZone"].str()),
                        ))
                    }
                    url = resp["@odata.nextLink"].str()
                }
            }
        }
    }

    private val graph = "https://graph.microsoft.com/v1.0"
    private val utcPref = mapOf("Prefer" to "outlook.timezone=\"UTC\"")
    private fun eventUrl(remoteId: String) = "$graph/me/events/${Http.enc(eventPart(remoteId))}"

    override fun event(accessToken: String, remoteId: String): RemoteCopy? {
        if (eventPart(remoteId).isEmpty()) return null
        val a = http.call(
            "GET", eventUrl(remoteId) + "?\$select=subject,start,end,isAllDay,isCancelled,isOrganizer,attendees,location,bodyPreview,seriesMasterId",
            accessToken, headers = utcPref,
        )
        if (a.status == 404 || a.status == 410) return null
        val e = a.body?.takeIf { a.status in 200..299 } ?: throw WriteRefused("Outlook HTTP ${a.status}")
        if (e["isCancelled"]?.jsonPrimitive?.booleanOrNull == true) return null
        val allDay = e["isAllDay"]?.jsonPrimitive?.booleanOrNull == true
        val start = (e["start"] as? JsonObject)?.get("dateTime").str() ?: return null
        val end = (e["end"] as? JsonObject)?.get("dateTime").str() ?: start
        val location = (e["location"] as? JsonObject)?.get("displayName").str()?.takeIf { it.isNotBlank() }
        val guests = (e["attendees"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().count { it["type"].str() != "resource" }
        val draft = EventDraft(e["subject"].str() ?: "", graphMs(start, allDay), graphMs(end, allDay), allDay, location, plainText(e["bodyPreview"].str()))
        return RemoteCopy(
            draft, organisedByMe = e["isOrganizer"]?.jsonPrimitive?.booleanOrNull != false, guests = guests,
            seriesId = e["seriesMasterId"].str()?.let { "${calendarPart(remoteId)}/$it" },
        )
    }

    override fun createEvent(accessToken: String, draft: EventDraft, idemKey: String): String {
        // transactionId: Graph adds nothing more when a retry sends the same one.
        val body = JsonObject(graphBody(draft, ALL_CHANGES) + ("transactionId" to JsonPrimitive(idemKey)))
        val a = http.call("POST", "$graph/me/events", accessToken, body, utcPref)
        val id = a.body?.get("id").str()?.takeIf { a.status in 200..299 } ?: throw WriteRefused("Outlook HTTP ${a.status}")
        return id
    }

    // Graph tells the guests itself when their organiser changes or cancels a meeting.
    override fun updateEvent(accessToken: String, remoteId: String, draft: EventDraft, changes: Set<EventEditChange>, notifyGuests: Boolean) {
        val a = http.call("PATCH", eventUrl(remoteId), accessToken, graphBody(draft, changes), utcPref)
        if (a.status !in 200..299) throw WriteRefused("Outlook HTTP ${a.status}")
    }

    // Completeness slice 1: Outlook's series keep their time (its repeat rules live in its own time zone); the caller
    // never sends TIME here.
    override fun updateSeries(accessToken: String, seriesId: String, draft: EventDraft, changes: Set<EventEditChange>, notifyGuests: Boolean) {
        val a = http.call("PATCH", eventUrl(seriesId), accessToken, graphBody(draft, changes - EventEditChange.TIME), utcPref)
        if (a.status !in 200..299) throw WriteRefused("Outlook HTTP ${a.status}")
    }

    override fun deleteEvent(accessToken: String, remoteId: String, notifyGuests: Boolean): Boolean {
        val a = http.call("DELETE", eventUrl(remoteId), accessToken)
        if (a.status == 404 || a.status == 410) return false
        if (a.status !in 200..299) throw WriteRefused("Outlook HTTP ${a.status}")
        return true
    }

    /** The fields of [changes] as Graph writes them, times in UTC (an all-day event from UTC midnight to midnight). */
    internal fun graphBody(d: EventDraft, changes: Set<EventEditChange>) = buildJsonObject {
        if (EventEditChange.TITLE in changes) put("subject", d.title)
        if (EventEditChange.TIME in changes) {
            put("start", buildJsonObject { put("dateTime", graphTime(d.startAtMs)); put("timeZone", "UTC") })
            put("end", buildJsonObject { put("dateTime", graphTime(d.endAtMs)); put("timeZone", "UTC") })
            put("isAllDay", d.allDay)
        }
        if (EventEditChange.LOCATION in changes) put("location", buildJsonObject { put("displayName", d.location ?: "") })
        if (EventEditChange.NOTES in changes) put("body", buildJsonObject { put("contentType", "text"); put("content", d.notes ?: "") })
    }

    private fun graphTime(ms: Long): String = LocalDateTime.ofEpochSecond(ms / 1000, 0, ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    /** A Graph time (UTC with the preference) in epoch ms; all-day boundaries rounded to the nearest UTC midnight. */
    private fun graphMs(s: String, allDay: Boolean): Long {
        val utc = LocalDateTime.parse(s.substringBefore('.')).toInstant(ZoneOffset.UTC).toEpochMilli()
        return if (allDay) Math.floorDiv(utc + DAY_MS / 2, DAY_MS) * DAY_MS else utc
    }
}
