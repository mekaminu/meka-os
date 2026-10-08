package os.meka.core.facade

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
) {
    val needsReconnect: Boolean get() = status == "needs_reconnect"
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
    suspend fun ask(question: String, context: os.meka.core.domain.AskContext): AskReply
    /** Whether MEKA's AI is on and the month's spend; null when the server has no AI layer. */
    suspend fun aiStatus(): AiStatusReply? = null
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
) : SyncTransport, AccountsApi, ReleasesApi, PushApi, NewsImagesApi, AiApi {
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

    override suspend fun ask(question: String, context: os.meka.core.domain.AskContext): AskReply {
        val body = AskCodec.encodeRequest(
            AskCodec.Request(question, context.dateIso, context.nowLine, context.items.map { AskCodec.Item(it.ref, it.kind.wire, it.line) }),
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
