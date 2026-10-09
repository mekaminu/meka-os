package os.meka.core.facade

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import os.meka.core.sync.AuthRejectedException
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PullResponse
import os.meka.core.sync.PushRequest
import os.meka.core.sync.PushResponse
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.AskCodec
import os.meka.core.wire.SpeechCodec
import os.meka.core.wire.WireCodec
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Connected calendar/email accounts as the apps show them. Tokens never leave the server. */
data class ConnectedAccount(
    val provider: String, val email: String, val status: String, val lastSyncAtMs: Long?,
    /** MEKA may add and change events here (calendar editing; the owner allowed it). */
    val canEdit: Boolean = false,
    /**
     * The row's title (Meka's screenshot 2026-10-09 09:01): a feed's name, or a signed-in account's main calendar name
     * ("Personal", "Hotmail", or Meka's own); [MekaCore.connectedAccounts] fills Meka's names in.
     */
    val title: String = os.meka.core.domain.CalendarAccountRules.title(provider, email),
    /**
     * "No events in the next 30 days" / "12 events in the next 30 days" for a signed-in account (Meka's 10:48
     * screenshots: is Hotmail coming through?); [MekaCore.connectedAccounts] fills it in from the mirrored events.
     */
    val eventsLine: String? = null,
) {
    val needsReconnect: Boolean get() = status == "needs_reconnect"
    /** "Google · meka@gmail.com · synced 08:29" · "Headlines · synced 08:29"; [syncedAt] as the device writes the time. */
    fun statusLine(syncedAt: String?): String = os.meka.core.domain.CalendarAccountRules.statusLine(provider, email, status, syncedAt)
    /** The line about editing under the account, or null (feeds, or the reconnect line says enough). */
    val editingLine: String? get() = os.meka.core.domain.CalendarAccessRules.line(provider, canEdit, needsReconnect)
    /** Allow editing · Stop editing · none. */
    val editingAction: os.meka.core.domain.CalendarAccessAction? get() = os.meka.core.domain.CalendarAccessRules.action(provider, canEdit, needsReconnect)
}

sealed class ConnectStart {
    /** Open this in the system browser; the provider sends the owner back to the server, not the app. */
    data class OpenBrowser(val url: String) : ConnectStart()
    /** The provider's credentials have not been set up on the server yet. */
    object NotSetUp : ConnectStart()
    data class Failed(val reason: String) : ConnectStart()
}

/** The server can't take a push address right now (no push route, or this device's key isn't registered yet). */
class PushUnavailableException : Exception("push isn't available on this server yet")

/** Push addresses (build plan M1: push via Firebase), available once the device is connected. */
interface PushApi {
    /** Registers this device's address for [service] (`fcm`), or removes it with an empty [token]. */
    suspend fun registerPushToken(service: String, token: String)
}

/**
 * News pictures (news, images slice): the server fetched each story's picture from its feed, made a small JPEG and
 * keeps it under a key; devices fetch it from their own server only, never from the publisher.
 */
interface NewsImagesApi {
    /** The picture's bytes, or null when the server has none under [key] (made later, or tidied away). */
    suspend fun newsImage(key: String): ByteArray?
}

/** What MEKA's server said to a question (Ask MEKA, V1 AI layer): words and proposed actions, or why there are none. */
sealed class AskReply {
    data class Answered(val text: String, val actions: List<os.meka.core.domain.AskRawAction>) : AskReply()
    /** [state]: off · over (the month's budget) · failed, with the server's [reason]. */
    data class Unavailable(val state: String, val reason: String?) : AskReply()
}

/** What the server says about MEKA's AI (`POST /v1/ai/status`): never the key. Budget fields are null on an older server. */
data class AiStatusReply(val state: String, val reason: String?, val spentCents: Long?, val budgetCents: Long?, val level: String?)

/** Ask MEKA (V1 AI layer, slice 3), available once the device is connected. */
interface AiApi {
    /** [history]: the conversation so far (Talk to MEKA), oldest first; [voice]: the answer will be spoken. */
    suspend fun ask(
        question: String,
        context: os.meka.core.domain.AskContext,
        history: List<os.meka.core.domain.TalkTurn> = emptyList(),
        voice: Boolean = false,
    ): AskReply
    /** Whether MEKA's AI is on and the month's spend; null when the server has no AI layer. */
    suspend fun aiStatus(): AiStatusReply? = null

    /**
     * Requests from people Meka watches (`POST /v1/ai/message-request`): one message's text, its sender's label and
     * time; the proposals come back unchecked ([os.meka.core.domain.MessageRequestRules.check] reads them). A server
     * without the route answers "off".
     */
    suspend fun messageRequest(request: os.meka.core.wire.MessageRequestCodec.Request): os.meka.core.wire.MessageRequestCodec.Response =
        os.meka.core.wire.MessageRequestCodec.Response(AskCodec.Response.OFF)

    /**
     * The messages assistant (`POST /v1/ai/message-triage`): one message's text, its sender's label, the group's name when
     * it named Meka, and its time; the lane, gist, draft and proposals come back unchecked
     * ([os.meka.core.domain.MessageTriageRules.check] reads them). A server without the route answers "off".
     */
    suspend fun messageTriage(request: os.meka.core.wire.MessageTriageCodec.Request): os.meka.core.wire.MessageTriageCodec.Response =
        os.meka.core.wire.MessageTriageCodec.Response(AskCodec.Response.OFF)
}

/** What [MekaCore.triageMessage] did with one captured message. */
sealed class TriageRead {
    /** Nothing to triage: a missed call, an empty message, an ignored group, a Normal group's chatter, already triaged. */
    data object Skipped : TriageRead()
    /** A Digest group's chatter: the Fold keeps it (sealed, on the phone) for the digest; no AI, nothing synced. */
    data class Digest(val groupKey: String) : TriageRead()
    /**
     * Triaged: the card Needs you shows (Needs a reply or FYI; null for an Action, or a message with nothing worth a card)
     * and the request cards an Action or a voice note made.
     */
    data class Read(
        val card: os.meka.core.domain.TriageCard?,
        val requests: List<os.meka.core.domain.RequestCard> = emptyList(),
    ) : TriageRead()
    /** Couldn't ask: offline, AI off, the month's budget spent; [line] says which. Not stored, so it is tried again. */
    data class Unavailable(val line: String) : TriageRead()
}

/** What [MekaCore.readRequest] did with one captured message. */
sealed class RequestRead {
    /** Not a message MEKA may read (not watched, a group, a missed call, a bare photo). */
    data object Skipped : RequestRead()
    /** Read: the new Needs you cards (none when the message asked for nothing, or only repeated an open card). */
    data class Read(val cards: List<os.meka.core.domain.RequestCard>) : RequestRead()
    /** Couldn't ask: offline, AI off, the month's budget spent; [line] says which. */
    data class Unavailable(val line: String) : RequestRead()
}

/**
 * What Add or Change on a request card did ([MekaCore.acceptRequest], [MekaCore.changeRequest]): [line] for the undo
 * bar, and what [MekaCore.undoRequest] takes back — the task made, the calendar edit (inside its five seconds), or the
 * work-from-home day (-1 when none).
 */
data class RequestDone(val line: String, val taskId: String?, val editId: String?, val homeDay: Long = -1)

/**
 * MEKA's voice (Weather and a voice, item 3): the server says a piece of MEKA's own words with Amazon Polly. Only
 * MEKA's words are sent, never Meka's. Available once the device is connected.
 */
interface SpeechApi {
    /** [voice]: a Polly name, or null for the server's default. A server without a voice answers "off". */
    suspend fun speak(text: String, voice: String?): os.meka.core.wire.SpeechCodec.Response
    /** The voices the server can speak with and the month's characters. */
    suspend fun speechVoices(): os.meka.core.wire.SpeechCodec.Voices
}

/** Account management calls, available once the device is connected. */
interface AccountsApi {
    /** [editing]: ask the provider for permission to change events too (calendar editing). */
    suspend fun startConnect(provider: String, editing: Boolean): ConnectStart
    suspend fun accounts(): List<ConnectedAccount>
    /** Gives up editing on one account; returns the accounts as they are now. */
    suspend fun stopEditing(provider: String, email: String): List<ConnectedAccount>
}

/**
 * HTTPS transport to the sync service (ADR-005). Every request carries the device's bearer secret and, when the
 * device has a hardware key, an ECDSA signature over method, path, time, nonce and body hash. Once the server has
 * the device's public key it refuses unsigned requests from that device, so a copied secret alone is useless.
 */
@OptIn(ExperimentalTime::class)
class HttpSyncTransport(
    private val client: HttpClient,
    private val baseUrl: String,
    private val deviceKey: DeviceKey? = null,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val deviceSecret: () -> String,
) : SyncTransport, AccountsApi, ReleasesApi, PushApi, NewsImagesApi, AiApi, SpeechApi {
    private var keyRegistered = false

    override suspend fun prepare() {
        val key = deviceKey ?: return
        if (keyRegistered) return
        // Best effort: if registration fails (offline, older server) sync still runs and this retries next round.
        val resp = runCatching { send("/v1/devices/key", WireCodec.encodeDeviceKey(key.publicKeyDerBase64)) }.getOrNull()
        when {
            resp == null -> Unit
            resp.status.isSuccess() -> keyRegistered = true
            // Another key is registered for this device id (or the device was revoked): only re-enrolling fixes it.
            resp.status.value == 401 || resp.status.value == 403 ->
                throw AuthRejectedException("The server no longer recognises this device's key")
        }
    }

    override suspend fun push(request: PushRequest): PushResponse =
        WireCodec.decodePushResponse(post("/v1/sync/push", WireCodec.encodePushRequest(request)))

    override suspend fun pull(request: PullRequest): PullResponse =
        WireCodec.decodePullResponse(post("/v1/sync/pull", WireCodec.encodePullRequest(request)))

    override suspend fun awaitChanges(request: PullRequest): Boolean =
        WireCodec.decodePullResponse(post("/v1/sync/wait", WireCodec.encodePullRequest(request))).ops.isNotEmpty()

    override suspend fun startConnect(provider: String, editing: Boolean): ConnectStart {
        prepare() // connecting accounts requires the device's signing key on the server
        val resp = try {
            send("/v1/integrations/$provider/connect", WireCodec.encodeConnectRequest(editing))
        } catch (e: TransportException) {
            return ConnectStart.Failed("Couldn't reach the server")
        }
        return when {
            resp.status.value == 409 -> ConnectStart.NotSetUp
            !resp.status.isSuccess() -> ConnectStart.Failed("Server returned ${resp.status.value}")
            else -> ConnectStart.OpenBrowser(WireCodec.decodeConnectUrl(resp.bodyAsText()))
        }
    }

    override suspend fun accounts(): List<ConnectedAccount> =
        WireCodec.decodeAccounts(post("/v1/integrations/list", "")).map(::account)

    override suspend fun stopEditing(provider: String, email: String): List<ConnectedAccount> =
        WireCodec.decodeAccounts(post("/v1/integrations/$provider/editing", WireCodec.encodeEditingChange(WireCodec.EditingChange(email, editing = false))))
            .map(::account)

    private fun account(a: WireCodec.IntegrationAccount) = ConnectedAccount(a.provider, a.email, a.status, a.lastSyncAtMs, a.canEdit)

    override suspend fun latestRelease(platform: String): AppRelease? {
        prepare() // release routes require the device's signing key on the server
        return WireCodec.decodeRelease(post("/v1/releases/latest", WireCodec.encodePlatform(platform)))?.let {
            AppRelease(it.platform, it.versionCode, it.versionName, it.sha256, it.sizeBytes, it.chunkCount)
        }
    }

    override suspend fun releaseChunk(platform: String, versionCode: Long, index: Int): ByteArray {
        val body = post("/v1/releases/chunk", WireCodec.encodeChunkRef(WireCodec.ChunkRef(platform, versionCode, index)))
        return try {
            Base64.decode(WireCodec.decodeChunkData(body))
        } catch (e: IllegalArgumentException) {
            throw TransportException("malformed chunk", e)
        }
    }

    override suspend fun uploadReleaseChunk(release: AppRelease, index: Int, bytes: ByteArray): Boolean {
        prepare()
        val r = WireCodec.AppRelease(release.platform, release.versionCode, release.versionName, release.sha256, release.sizeBytes, release.chunkCount)
        val resp = send("/v1/releases/upload", WireCodec.encodeReleaseChunk(WireCodec.ReleaseChunk(r, index, Base64.encode(bytes))))
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/releases/upload")
            resp.status.value == 403 -> throw PublishRefusedException("this device's signing key isn't registered with the server yet")
            resp.status.value == 409 || resp.status.value == 400 -> throw PublishRefusedException(resp.bodyAsText().take(200))
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/releases/upload")
            else -> WireCodec.decodeUploadAck(resp.bodyAsText()).complete
        }
    }

    override suspend fun newsImage(key: String): ByteArray? {
        prepare() // the picture route requires the device's signing key on the server
        val resp = send("/v1/news/image", WireCodec.encodeNewsImageRef(key))
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/news/image")
            // 404: no picture under this key (or an older server without the route).
            resp.status.value == 404 -> null
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/news/image")
            else -> try {
                Base64.decode(WireCodec.decodeChunkData(resp.bodyAsText()))
            } catch (e: IllegalArgumentException) {
                throw TransportException("malformed picture", e)
            }
        }
    }

    override suspend fun registerPushToken(service: String, token: String) {
        prepare() // the push route requires the device's signing key on the server
        val resp = send("/v1/push/token", WireCodec.encodePushToken(WireCodec.PushToken(service, token)))
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/push/token")
            // 404: a server without push (older, or not configured); 403: no signing key registered yet.
            resp.status.value == 404 || resp.status.value == 403 -> throw PushUnavailableException()
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/push/token")
        }
    }

    override suspend fun ask(
        question: String,
        context: os.meka.core.domain.AskContext,
        history: List<os.meka.core.domain.TalkTurn>,
        voice: Boolean,
    ): AskReply {
        val body = AskCodec.encodeRequest(
            AskCodec.Request(
                question, context.dateIso, context.nowLine, context.items.map { AskCodec.Item(it.ref, it.kind.wire, it.line) },
                history = history.map { AskCodec.Turn(it.question, it.answer, it.done) },
                voice = voice,
            ),
        )
        prepare() // the ask route requires the device's signing key on the server
        val resp = send("/v1/ai/ask", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/ai/ask")
            // A server without the AI layer (older, or no key secret configured).
            resp.status.value == 404 -> return AskReply.Unavailable(AskCodec.Response.OFF, null)
            resp.status.value == 403 -> return AskReply.Unavailable(AskCodec.Response.FAILED, "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/ai/ask")
        }
        val r = AskCodec.decodeResponse(resp.bodyAsText())
        return if (r.state == AskCodec.Response.ANSWERED) {
            AskReply.Answered(r.answer.orEmpty(), r.actions.map { os.meka.core.domain.AskRawAction(it.kind, it.ref, it.title, it.date, it.time, it.hours, it.minutes) })
        } else {
            AskReply.Unavailable(r.state, r.reason)
        }
    }

    override suspend fun aiStatus(): AiStatusReply? {
        prepare() // the status route requires the device's signing key on the server too
        val resp = send("/v1/ai/status", "{}")
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/ai/status")
            // A server without the AI layer (older, or no key secret configured): AI is off.
            resp.status.value == 404 -> return AiStatusReply(AskCodec.Response.OFF, null, null, null, null)
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/ai/status")
        }
        val st = AskCodec.decodeStatus(resp.bodyAsText())
        return AiStatusReply(st.state, st.reason, st.spentCents, st.budgetCents, st.level)
    }

    override suspend fun messageRequest(request: os.meka.core.wire.MessageRequestCodec.Request): os.meka.core.wire.MessageRequestCodec.Response {
        val codec = os.meka.core.wire.MessageRequestCodec
        val body = codec.encodeRequest(request)
        prepare() // the AI routes require the device's signing key on the server
        val resp = send("/v1/ai/message-request", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/ai/message-request")
            // A server without the route (older, or no AI key): nothing is read.
            resp.status.value == 404 -> return os.meka.core.wire.MessageRequestCodec.Response(AskCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.MessageRequestCodec.Response(AskCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/ai/message-request")
        }
        return codec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun messageTriage(request: os.meka.core.wire.MessageTriageCodec.Request): os.meka.core.wire.MessageTriageCodec.Response {
        val codec = os.meka.core.wire.MessageTriageCodec
        val body = codec.encodeRequest(request)
        prepare() // the AI routes require the device's signing key on the server
        val resp = send("/v1/ai/message-triage", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/ai/message-triage")
            // A server without the route (older, or no AI key): nothing is triaged.
            resp.status.value == 404 -> return os.meka.core.wire.MessageTriageCodec.Response(AskCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.MessageTriageCodec.Response(AskCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/ai/message-triage")
        }
        return codec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun speak(text: String, voice: String?): os.meka.core.wire.SpeechCodec.Response {
        // Raw MP3 back (a quarter smaller than base64 JSON, nothing to parse); an older server answers JSON as before.
        val body = os.meka.core.wire.SpeechCodec.encodeRequest(os.meka.core.wire.SpeechCodec.Request(text, voice, binary = true))
        prepare() // the speech routes require the device's signing key on the server
        val resp = send("/v1/speech/speak", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/speech/speak")
            // A server without MEKA's voice (older, or not configured): the device's own voice speaks.
            resp.status.value == 404 -> return os.meka.core.wire.SpeechCodec.Response(os.meka.core.wire.SpeechCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.SpeechCodec.Response(os.meka.core.wire.SpeechCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/speech/speak")
        }
        if (resp.contentType()?.match(AUDIO_MPEG) == true) {
            val unusable = SpeechCodec.Response(SpeechCodec.Response.FAILED, reason = "MEKA's voice sent an unusable clip")
            val size = resp.contentLength()
            if (size != null && size > SpeechCodec.MAX_AUDIO_BYTES) return unusable
            val bytes = resp.readRawBytes()
            if (bytes.size > SpeechCodec.MAX_AUDIO_BYTES) return unusable
            return SpeechCodec.binaryResponse(Base64.encode(bytes), resp.headers[SpeechCodec.HEADER_VOICE], resp.headers[SpeechCodec.HEADER_ENGINE])
        }
        return SpeechCodec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun speechVoices(): os.meka.core.wire.SpeechCodec.Voices {
        prepare()
        val resp = send("/v1/speech/voices", os.meka.core.wire.SpeechCodec.encodeVoicesRequest())
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/speech/voices")
            resp.status.value == 404 -> return os.meka.core.wire.SpeechCodec.Voices(os.meka.core.wire.SpeechCodec.Voices.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.SpeechCodec.Voices(os.meka.core.wire.SpeechCodec.Voices.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/speech/voices")
        }
        return os.meka.core.wire.SpeechCodec.decodeVoices(resp.bodyAsText())
    }

    private companion object {
        val AUDIO_MPEG = ContentType.parse(SpeechCodec.AUDIO_TYPE)
    }

    private suspend fun post(path: String, body: String): String {
        val resp = send(path, body)
        if (resp.status.value == 401) throw AuthRejectedException("HTTP 401 from $path")
        if (!resp.status.isSuccess()) throw TransportException("HTTP ${resp.status.value} from $path")
        return resp.bodyAsText()
    }

    private suspend fun send(path: String, body: String): HttpResponse = try {
        val time = nowMs()
        val nonce = Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val signature = deviceKey?.signBase64(RequestSigning.canonical("POST", path, time, nonce, body))
        client.post(baseUrl.trimEnd('/') + path) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${deviceSecret()}")
            if (signature != null) {
                header(RequestSigning.HEADER_TIME, time.toString())
                header(RequestSigning.HEADER_NONCE, nonce)
                header(RequestSigning.HEADER_SIGNATURE, signature)
            }
            setBody(body)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw TransportException("network error: ${e.message}", e)
    }
}
