package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import os.meka.core.domain.RecoveryKeyRules
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.RecoveryCodec
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The recovery key (build plan V1): made on the Fold, a new phone connects with the words once, then it is spent. */
class RecoveryTest {
    private var nowMs = 1_791_800_000_000L
    private val devices = InMemoryDeviceRegistry()
    private val verifier = RequestVerifier { nowMs }
    private val store = InMemoryRecoveryKeyStore()
    private val recovery = Recovery(store, devices, verifier) { nowMs }
    private val fold = DeviceIdentity("home", "android0123456789ab")
    private val entropy = ByteArray(32) { (it * 13 + 1).toByte() }

    init {
        devices.enrol("home", fold.deviceId, "Fold")
    }

    private fun signed(key: TestDeviceKey, path: String, body: String): Triple<String, String, String> {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val msg = listOf("MEKA1", "POST", path, nowMs.toString(), nonce, Secrets.sha256Hex(body)).joinToString("\n")
        return Triple(nowMs.toString(), nonce, key.sign(msg))
    }

    private fun enrol(key: TestDeviceKey, id: String, secret: String = RecoveryKeyRules.secret(entropy), name: String = "Galaxy Z Fold"): LinkReply {
        val body = RecoveryCodec.encodeEnrol(RecoveryCodec.Enrol(secret, id, name, key.publicB64))
        val (t, n, s) = signed(key, "/v1/recovery/enrol", body)
        return recovery.enrol("/v1/recovery/enrol", body, t, n, s)
    }

    private fun status(who: DeviceIdentity = fold) = RecoveryCodec.decodeStatus(recovery.status(who).body)

    @Test
    fun aNewPhoneConnectsWithTheWordsOnceAndTheKeyIsThenSpent() {
        assertEquals(RecoveryCodec.NONE, status().state)
        val made = recovery.set(fold, RecoveryCodec.encodeSet(RecoveryKeyRules.verifier(entropy)))
        assertEquals(200, made.status)
        assertEquals(RecoveryCodec.Status(RecoveryCodec.READY, nowMs, "Fold"), RecoveryCodec.decodeStatus(made.body))
        // Only the verifier is stored.
        assertEquals(RecoveryKeyRules.verifier(entropy), store.get("home")!!.verifier)

        val key = TestDeviceKey { nowMs }
        val r = enrol(key, "android00112233aabb")
        assertEquals(200, r.status)
        val got = RecoveryCodec.decodeEnrolled(r.body)
        assertEquals("home", got.householdId)
        assertEquals(DeviceIdentity("home", "android00112233aabb"), devices.authenticate(got.secret))
        assertEquals(key.publicB64, devices.publicKey(DeviceIdentity("home", "android00112233aabb")))

        assertEquals(RecoveryCodec.Status(RecoveryCodec.USED, nowMs, "Fold", nowMs, "Galaxy Z Fold"), status())
        // Spent: the same words connect nothing else.
        val again = enrol(TestDeviceKey { nowMs }, "mac00112233aabb")
        assertEquals(404, again.status)
        assertEquals(RecoveryCodec.ERR_KEY, RecoveryCodec.decodeError(again.body))
        // A new key from the recovered phone works again.
        val e2 = ByteArray(32) { 7 }
        assertEquals(200, recovery.set(DeviceIdentity("home", "android00112233aabb"), RecoveryCodec.encodeSet(RecoveryKeyRules.verifier(e2))).status)
        assertEquals(RecoveryCodec.READY, status().state)
        assertEquals(200, enrol(TestDeviceKey { nowMs }, "mac00112233aabb", RecoveryKeyRules.secret(e2), "Mac").status)
    }

    @Test
    fun aNewKeyReplacesTheOldOneAndWrongWordsAreRefused() {
        recovery.set(fold, RecoveryCodec.encodeSet(RecoveryKeyRules.verifier(entropy)))
        val newer = ByteArray(32) { 42 }
        recovery.set(fold, RecoveryCodec.encodeSet(RecoveryKeyRules.verifier(newer)))
        assertEquals(404, enrol(TestDeviceKey { nowMs }, "android00112233aabb").status)
        assertEquals(200, enrol(TestDeviceKey { nowMs }, "android00112233aabb", RecoveryKeyRules.secret(newer)).status)
    }

    @Test
    fun itNeverTakesOverAKnownDeviceAndNeedsTheKeyItNames() {
        recovery.set(fold, RecoveryCodec.encodeSet(RecoveryKeyRules.verifier(entropy)))
        // The Fold's own id is still connected: refused, and the key isn't spent.
        val taken = enrol(TestDeviceKey { nowMs }, fold.deviceId)
        assertEquals(409, taken.status)
        assertEquals(RecoveryCodec.ERR_TAKEN, RecoveryCodec.decodeError(taken.body))
        assertEquals(RecoveryCodec.READY, status().state)
        // A revoked id stays revoked, and the key isn't spent by trying it.
        devices.enrol("home", "mac0011223344ff", "Old Mac")
        devices.revoke("mac0011223344ff")
        assertEquals(RecoveryCodec.ERR_REVOKED, RecoveryCodec.decodeError(enrol(TestDeviceKey { nowMs }, "mac0011223344ff").body))
        assertEquals(RecoveryCodec.READY, status().state)
        // Signed by another key than the one in the body.
        val key = TestDeviceKey { nowMs }
        val other = TestDeviceKey { nowMs }
        val body = RecoveryCodec.encodeEnrol(RecoveryCodec.Enrol(RecoveryKeyRules.secret(entropy), "mac00112233aabb", "Mac", key.publicB64))
        val (t, n, s) = signed(other, "/v1/recovery/enrol", body)
        assertEquals(401, recovery.enrol("/v1/recovery/enrol", body, t, n, s).status)
        assertEquals(RecoveryCodec.READY, status().state)
        // Watches and odd ids can't make keys.
        devices.enrol("home", "watch0123456789abcdef", "Galaxy Watch")
        assertEquals(403, recovery.set(DeviceIdentity("home", "watch0123456789abcdef"), RecoveryCodec.encodeSet(RecoveryKeyRules.verifier(entropy))).status)
        assertEquals(400, recovery.set(fold, """{"verifier":"nope"}""").status)
    }

    @Test
    fun tooManyWrongTriesWaitAnHour() {
        recovery.set(fold, RecoveryCodec.encodeSet(RecoveryKeyRules.verifier(entropy)))
        repeat(Recovery.MAX_WRONG) { assertEquals(404, enrol(TestDeviceKey { nowMs }, "mac00112233aabb", "c".repeat(64)).status) }
        val waiting = enrol(TestDeviceKey { nowMs }, "mac00112233aabb")
        assertEquals(429, waiting.status)
        assertEquals(RecoveryCodec.ERR_WAIT, RecoveryCodec.decodeError(waiting.body))
        nowMs += Recovery.WINDOW_MS + 1
        assertEquals(200, enrol(TestDeviceKey { nowMs }, "mac00112233aabb").status)
    }

    @Test
    fun throughKtorMakingNeedsAKeyedDeviceAndRecoveringNeedsNoBearer() = testApplication {
        val reg = InMemoryDeviceRegistry()
        val secret = reg.enrol("home", fold.deviceId, "Fold")
        val foldKey = TestDeviceKey().also { reg.registerKey(fold, it.publicB64) }
        val v = RequestVerifier()
        application { mekaSync(InMemoryServerOpStore(), reg, verifier = v, recovery = Recovery(InMemoryRecoveryKeyStore(), reg, v)) }

        val setBody = RecoveryCodec.encodeSet(RecoveryKeyRules.verifier(entropy))
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/recovery/set") { setBody(setBody) }.status)
        val made = client.post("/v1/recovery/set") { with(foldKey) { signed(secret, "/v1/recovery/set", setBody) } }
        assertEquals(HttpStatusCode.OK, made.status)
        assertEquals(RecoveryCodec.READY, RecoveryCodec.decodeStatus(made.bodyAsText()).state)

        val newKey = TestDeviceKey()
        val body = RecoveryCodec.encodeEnrol(RecoveryCodec.Enrol(RecoveryKeyRules.secret(entropy), "mac00112233aabb", "Mac", newKey.publicB64))
        val now = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val r = client.post("/v1/recovery/enrol") {
            header("X-Meka-Time", now.toString()); header("X-Meka-Nonce", nonce)
            header("X-Meka-Signature", newKey.sign(listOf("MEKA1", "POST", "/v1/recovery/enrol", now.toString(), nonce, Secrets.sha256Hex(body)).joinToString("\n")))
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(DeviceIdentity("home", "mac00112233aabb"), reg.authenticate(RecoveryCodec.decodeEnrolled(r.bodyAsText()).secret))
        val st = client.post("/v1/recovery/status") { with(foldKey) { signed(secret, "/v1/recovery/status", "{}") } }
        assertEquals(RecoveryCodec.USED, RecoveryCodec.decodeStatus(st.bodyAsText()).state)
        assertNull(reg.name(DeviceIdentity("home", "mac-nope")))
    }
}
