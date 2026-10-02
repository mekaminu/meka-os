package os.meka.core.facade

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import os.meka.core.sync.PullRequest
import os.meka.core.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpSyncTransportTest {
    /** Records what it was asked to sign, so the test can recompute the canonical string independently. */
    private class RecordingKey : DeviceKey {
        val signed = mutableListOf<String>()
        override val publicKeyDerBase64 = "A".repeat(124)
        override fun signBase64(message: String): String { signed += message; return "sig" + signed.size }
    }

    private val requests = mutableListOf<HttpRequestData>()
    private val json = headersOf("Content-Type", "application/json")
    private fun client() = HttpClient(MockEngine { req ->
        requests += req
        when (req.url.encodedPath) {
            "/v1/sync/pull" -> respond(WireCodec.encodePullResponse(os.meka.core.sync.PullResponse(emptyList(), false)), HttpStatusCode.OK, json)
            "/v1/devices/key" -> respond(WireCodec.encodeDeviceKey("A".repeat(124)), HttpStatusCode.OK, json)
            "/v1/integrations/google/connect" -> respond("not configured", HttpStatusCode.Conflict)
            else -> respond("", HttpStatusCode.NotFound)
        }
    })

    @Test
    fun everyRequestIsSignedOverMethodPathTimeNonceAndBodyHash() = runTest {
        val key = RecordingKey()
        val t = HttpSyncTransport(client(), "https://meka.example/", key, nowMs = { 1_790_000_000_000L }) { "s".repeat(64) }
        t.pull(PullRequest("home", "fold", 7))
        val req = requests.single()
        val body = (req.body as TextContent).text
        val nonce = req.headers[RequestSigning.HEADER_NONCE]!!
        assertEquals("1790000000000", req.headers[RequestSigning.HEADER_TIME])
        assertEquals("sig1", req.headers[RequestSigning.HEADER_SIGNATURE])
        assertEquals("Bearer " + "s".repeat(64), req.headers["Authorization"])
        assertEquals(listOf("MEKA1", "POST", "/v1/sync/pull", "1790000000000", nonce, Sha256.hex(body.encodeToByteArray())).joinToString("\n"), key.signed.single())
    }

    @Test
    fun withoutAKeyRequestsCarryOnlyTheBearer() = runTest {
        HttpSyncTransport(client(), "https://meka.example", null) { "s".repeat(64) }.pull(PullRequest("home", "fold", 0))
        assertNull(requests.single().headers[RequestSigning.HEADER_SIGNATURE])
    }

    @Test
    fun theKeyIsRegisteredOnceAndConnectReportsAnUnconfiguredProvider() = runTest {
        val t = HttpSyncTransport(client(), "https://meka.example", RecordingKey()) { "s".repeat(64) }
        t.prepare(); t.prepare()
        assertEquals(1, requests.count { it.url.encodedPath == "/v1/devices/key" })
        assertEquals(ConnectStart.NotSetUp, t.startConnect("google"))
        assertTrue(requests.last().url.encodedPath.endsWith("/connect"))
    }
}
