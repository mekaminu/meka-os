package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.HealthCodec
import os.meka.core.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `POST /v1/health/household` (Reliability first, item 3): what only the server knows, for a keyed device. */
class HealthRouteTest {
    private val devices = InMemoryDeviceRegistry()
    private val foldSecret = devices.enrol("hh", "fold")
    private val macSecret = devices.enrol("hh", "mac")
    private val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
    private val macKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "mac"), it.publicB64) }
    private val path = "/v1/health/household"

    @Test
    fun saysWhetherThisDeviceGetsWakeUpsAndWhatIsSetUp() = testApplication {
        val store = InMemoryPushTokenStore()
        val push = Push(store, { SendResult.SENT }, schedule = { _, _ -> })
        application { mekaSync(InMemoryServerOpStore(), devices, push = push) }
        val token = WireCodec.encodePushToken(WireCodec.PushToken("fcm", "fold-token:APA91b" + "x".repeat(40)))
        assertEquals(HttpStatusCode.OK, client.post("/v1/push/token") { with(foldKey) { signed(foldSecret, "/v1/push/token", token) } }.status)

        val body = HealthCodec.encodeRequest()
        val fold = HealthCodec.decodeResponse(client.post(path) { with(foldKey) { signed(foldSecret, path, body) } }.bodyAsText())
        assertEquals(HealthCodec.Response.PUSH_ON, fold.push)
        // The phone service and Polly aren't configured on this test server.
        assertFalse(fold.calls)
        assertFalse(fold.speech)
        assertTrue(kotlin.math.abs(fold.atMs - System.currentTimeMillis()) < 60_000)
        // The Mac has no address of its own.
        val mac = HealthCodec.decodeResponse(client.post(path) { with(macKey) { signed(macSecret, path, body) } }.bodyAsText())
        assertEquals(HealthCodec.Response.PUSH_MISSING, mac.push)
        // Setup's "Mac" step: the household has one Mac connected (only the count is said).
        assertEquals(1, fold.macs)
        devices.revoke("mac")
        assertEquals(0, HealthCodec.decodeResponse(client.post(path) { with(foldKey) { signed(foldSecret, path, body) } }.bodyAsText()).macs)
    }

    @Test
    fun withoutPushItIsOffAndUnsignedCallersAreRefused() = testApplication {
        application { mekaSync(InMemoryServerOpStore(), devices) }
        val body = HealthCodec.encodeRequest()
        val r = HealthCodec.decodeResponse(client.post(path) { with(foldKey) { signed(foldSecret, path, body) } }.bodyAsText())
        assertEquals(HealthCodec.Response.PUSH_OFF, r.push)
        assertEquals(HttpStatusCode.Unauthorized, client.post(path) { header("Authorization", "Bearer $foldSecret"); setBody(body) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post(path) { setBody(body) }.status)
    }
}
