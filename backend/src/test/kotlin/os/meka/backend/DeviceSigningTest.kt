package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceSigningTest {
    private val devices = InMemoryDeviceRegistry()
    private val secret = devices.enrol("home", "fold")
    private val key = TestDeviceKey()
    private val pull = WireCodec.encodePullRequest(PullRequest("home", "fold", 0))

    @Test
    fun onceAKeyIsRegisteredOnlySignedFreshUntamperedRequestsPass() = testApplication {
        application { mekaSync(InMemoryServerOpStore(), devices) }
        // Before registration, the bearer secret alone still works (existing installs keep syncing).
        assertEquals(HttpStatusCode.OK, client.post("/v1/sync/pull") { header("Authorization", "Bearer $secret"); setBody(pull) }.status)

        // Registration must be signed by the key being registered.
        val reg = WireCodec.encodeDeviceKey(key.publicB64)
        val other = TestDeviceKey()
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/devices/key") { with(other) { signed(secret, "/v1/devices/key", reg) } }.status)
        assertEquals(HttpStatusCode.OK, client.post("/v1/devices/key") { with(key) { signed(secret, "/v1/devices/key", reg) } }.status)
        // Idempotent for the same key; a different key cannot replace it.
        assertEquals(HttpStatusCode.OK, client.post("/v1/devices/key") { with(key) { signed(secret, "/v1/devices/key", reg) } }.status)
        val reg2 = WireCodec.encodeDeviceKey(other.publicB64)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/devices/key") { with(other) { signed(secret, "/v1/devices/key", reg2) } }.status)

        // Now: a stolen bearer secret alone is useless.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/sync/pull") { header("Authorization", "Bearer $secret"); setBody(pull) }.status)
        val nonce = "n".repeat(20)
        assertEquals(HttpStatusCode.OK, client.post("/v1/sync/pull") { with(key) { signed(secret, "/v1/sync/pull", pull, nonce) } }.status)
        // Replay of the same nonce, a body swapped after signing, a stale clock, or the wrong key: all refused.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/sync/pull") { with(key) { signed(secret, "/v1/sync/pull", pull, nonce) } }.status)
        val tampered = client.post("/v1/sync/pull") {
            with(key) { signed(secret, "/v1/sync/pull", pull) }
            setBody(WireCodec.encodePullRequest(PullRequest("home", "fold", 5)))
        }
        assertEquals(HttpStatusCode.Unauthorized, tampered.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/sync/pull") {
            with(key) { signed(secret, "/v1/sync/pull", pull, time = System.currentTimeMillis() - 10 * 60_000) }
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/sync/pull") { with(other) { signed(secret, "/v1/sync/pull", pull) } }.status)
    }

    @Test
    fun reEnrollingClearsTheKey() {
        val who = DeviceIdentity("home", "fold")
        devices.registerKey(who, key.publicB64)
        devices.enrol("home", "fold")
        assertEquals(null, devices.publicKey(who))
    }
}
