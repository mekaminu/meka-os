package os.meka.core.facade

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PullResponse
import os.meka.core.sync.PushRequest
import os.meka.core.sync.PushResponse
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.WireCodec
import kotlin.coroutines.cancellation.CancellationException

/**
 * HTTPS transport to the sync service. M0 authenticates with a per-device bearer secret issued at enrolment
 * (stored in Keystore/Keychain; the server keeps only its SHA-256). Ed25519 request signing replaces it before any
 * real personal data is synced (ADR-005).
 */
class HttpSyncTransport(
    private val client: HttpClient,
    private val baseUrl: String,
    private val deviceSecret: () -> String,
) : SyncTransport {

    override suspend fun push(request: PushRequest): PushResponse =
        WireCodec.decodePushResponse(post("/v1/sync/push", WireCodec.encodePushRequest(request)))

    override suspend fun pull(request: PullRequest): PullResponse =
        WireCodec.decodePullResponse(post("/v1/sync/pull", WireCodec.encodePullRequest(request)))

    private suspend fun post(path: String, body: String): String {
        val resp = try {
            client.post(baseUrl.trimEnd('/') + path) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer ${deviceSecret()}")
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TransportException("network error: ${e.message}", e)
        }
        if (!resp.status.isSuccess()) throw TransportException("HTTP ${resp.status.value} from $path")
        return resp.bodyAsText()
    }
}
