package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.DeviceLinkCodec
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Linking a watch (Galaxy Watch, slice 1): the watch's code, Meka's approval, the secret handed over once, unlink. */
class DeviceLinkTest {
    private var nowMs = 1_791_540_000_000L
    private val devices = InMemoryDeviceRegistry()
    private val verifier = RequestVerifier { nowMs }
    private val link = DeviceLink(devices, verifier) { nowMs }
    private val fold = DeviceIdentity("home", "android")
    private val watchId = "watch0123456789abcdef"

    init {
        devices.enrol("home", "android")
    }

    /** (time, nonce, signature) by [key] over a POST of [body] to [path], as the watch signs before it has a secret. */
    private fun signed(key: TestDeviceKey, path: String, body: String): Triple<String, String, String> {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val msg = listOf("MEKA1", "POST", path, nowMs.toString(), nonce, Secrets.sha256Hex(body)).joinToString("\n")
        return Triple(nowMs.toString(), nonce, key.sign(msg))
    }

    private fun start(key: TestDeviceKey, id: String = watchId): LinkReply {
        val body = DeviceLinkCodec.encodeStart(DeviceLinkCodec.Start(id, "Galaxy Watch", key.publicB64))
        val (t, n, s) = signed(key, "/v1/link/start", body)
        return link.start("/v1/link/start", body, t, n, s)
    }

    private fun status(key: TestDeviceKey, linkId: String): DeviceLinkCodec.Status {
        val body = DeviceLinkCodec.encodeStatusRequest(linkId)
        val (t, n, s) = signed(key, "/v1/link/status", body)
        val r = link.status("/v1/link/status", body, t, n, s)
        assertEquals(200, r.status)
        return DeviceLinkCodec.decodeStatus(r.body)
    }

    @Test
    fun theWatchShowsACodeMekaTypesItAndTheWatchGetsItsSecretOnce() {
        val watchKey = TestDeviceKey { nowMs }
        val started = DeviceLinkCodec.decodeStarted(start(watchKey).body)
        assertTrue(DeviceLinkCodec.isCode(started.code))
        assertEquals(nowMs + DeviceLink.CODE_MS, started.expiresAtMs)
        assertEquals(DeviceLinkCodec.WAITING, status(watchKey, started.linkId).state)

        // Meka types the code on the Fold.
        val approved = link.approve(fold, DeviceLinkCodec.encodeApprove(started.code))
        assertEquals(200, approved.status)
        assertEquals(DeviceLinkCodec.Linked(watchId, "Galaxy Watch"), DeviceLinkCodec.decodeLinked(approved.body))
        // The code can't be used twice.
        assertEquals(404, link.approve(fold, DeviceLinkCodec.encodeApprove(started.code)).status)

        // The watch picks up its secret, signed with its key; the server already holds that key for it.
        val got = status(watchKey, started.linkId)
        assertEquals(DeviceLinkCodec.LINKED, got.state)
        assertEquals("home", got.householdId)
        assertEquals(watchId, got.deviceId)
        assertEquals(DeviceIdentity("home", watchId), devices.authenticate(got.secret!!))
        assertEquals(watchKey.publicB64, devices.publicKey(DeviceIdentity("home", watchId)))
        // Only once.
        assertEquals(DeviceLinkCodec.EXPIRED, status(watchKey, started.linkId).state)
        assertEquals(0, link.waiting())

        // Meka sees it, then unlinks it: its secret stops working at once.
        val listed = DeviceLinkCodec.decodeWatches(link.watches(fold).body)
        assertEquals(listOf(watchId), listed.map { it.id })
        assertEquals("Galaxy Watch", listed[0].name)
        assertEquals(200, link.unlink(fold, DeviceLinkCodec.encodeUnlink(watchId)).status)
        assertEquals(null, devices.authenticate(got.secret!!))
        assertTrue(DeviceLinkCodec.decodeWatches(link.watches(fold).body).isEmpty())
        assertEquals(404, link.unlink(fold, DeviceLinkCodec.encodeUnlink(watchId)).status)

        // An unlinked watch id can't come back by linking again (a reset watch makes a new id).
        val again = DeviceLinkCodec.decodeStarted(start(watchKey).body)
        val refused = link.approve(fold, DeviceLinkCodec.encodeApprove(again.code))
        assertEquals(409, refused.status)
        assertEquals(DeviceLinkCodec.ERR_REVOKED, DeviceLinkCodec.decodeError(refused.body))
    }

    @Test
    fun onlyTheKeyThatAskedCanReadTheLinkAndCodesRunOut() {
        val watchKey = TestDeviceKey { nowMs }
        val other = TestDeviceKey { nowMs }
        // A start signed by a different key than the one it names is refused.
        val body = DeviceLinkCodec.encodeStart(DeviceLinkCodec.Start(watchId, "Galaxy Watch", watchKey.publicB64))
        val (t, n, s) = signed(other, "/v1/link/start", body)
        assertEquals(401, link.start("/v1/link/start", body, t, n, s).status)

        val started = DeviceLinkCodec.decodeStarted(start(watchKey).body)
        link.approve(fold, DeviceLinkCodec.encodeApprove(started.code))
        // Someone else's key can't collect the secret.
        val sb = DeviceLinkCodec.encodeStatusRequest(started.linkId)
        val (t2, n2, s2) = signed(other, "/v1/link/status", sb)
        assertEquals(401, link.status("/v1/link/status", sb, t2, n2, s2).status)

        // A code nobody types runs out after ten minutes.
        val second = TestDeviceKey { nowMs }
        val late = DeviceLinkCodec.decodeStarted(start(second, "watchaaaaaaaabbbbbbbb").body)
        nowMs += DeviceLink.CODE_MS
        assertEquals(404, link.approve(fold, DeviceLinkCodec.encodeApprove(late.code)).status)
        assertEquals(DeviceLinkCodec.EXPIRED, status(second, late.linkId).state)
        // And a secret nobody collected is gone too.
        assertEquals(DeviceLinkCodec.EXPIRED, status(watchKey, started.linkId).state)
    }

    @Test
    fun wrongCodesAreLimitedAndWaitingLinksAreCapped() {
        repeat(DeviceLink.MAX_WRONG) { assertEquals(404, link.approve(fold, DeviceLinkCodec.encodeApprove("00000000")).status) }
        val k = TestDeviceKey { nowMs }
        val started = DeviceLinkCodec.decodeStarted(start(k).body)
        // Even the right code waits now.
        val wait = link.approve(fold, DeviceLinkCodec.encodeApprove(started.code))
        assertEquals(429, wait.status)
        assertEquals(DeviceLinkCodec.ERR_WAIT, DeviceLinkCodec.decodeError(wait.body))
        nowMs += DeviceLink.CODE_MS - 1
        assertEquals(DeviceLinkCodec.ERR_WAIT, DeviceLinkCodec.decodeError(link.approve(fold, DeviceLinkCodec.encodeApprove(started.code)).body))

        // Asking again replaces the watch's last code; at most MAX_PENDING watches wait at once.
        nowMs += 2
        val fresh = TestDeviceKey { nowMs }
        val a = DeviceLinkCodec.decodeStarted(start(fresh, "watchaaaaaaaa00000000").body)
        val b = DeviceLinkCodec.decodeStarted(start(fresh, "watchaaaaaaaa00000000").body)
        assertTrue(a.linkId != b.linkId)
        assertEquals(1, link.waiting())
        repeat(DeviceLink.MAX_PENDING - 1) { i -> assertEquals(200, start(TestDeviceKey { nowMs }, "watchbbbbbbbb0000000$i").status) }
        val busy = start(TestDeviceKey { nowMs }, "watchcccccccc00000000")
        assertEquals(429, busy.status)
        assertEquals(DeviceLinkCodec.ERR_BUSY, DeviceLinkCodec.decodeError(busy.body))
        // A Fold or Mac id can't join this way.
        assertEquals(400, start(TestDeviceKey { nowMs }, "android").status)
    }

    @Test
    fun throughKtorTheFoldMustBeKeyedAndTheWatchNeedsNoBearer() = testApplication {
        val ops = InMemoryServerOpStore()
        val reg = InMemoryDeviceRegistry()
        val foldSecret = reg.enrol("home", "android")
        val foldKey = TestDeviceKey().also { reg.registerKey(DeviceIdentity("home", "android"), it.publicB64) }
        val v = RequestVerifier()
        application { mekaSync(ops, reg, verifier = v) }

        val watchKey = TestDeviceKey()
        val startBody = DeviceLinkCodec.encodeStart(DeviceLinkCodec.Start(watchId, "Galaxy Watch", watchKey.publicB64))
        val now = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val started = client.post("/v1/link/start") {
            header("X-Meka-Time", now.toString()); header("X-Meka-Nonce", nonce)
            header("X-Meka-Signature", watchKey.sign(listOf("MEKA1", "POST", "/v1/link/start", now.toString(), nonce, Secrets.sha256Hex(startBody)).joinToString("\n")))
            setBody(startBody)
        }
        assertEquals(HttpStatusCode.OK, started.status)
        assertEquals("no-store", started.headers["Cache-Control"])
        val code = DeviceLinkCodec.decodeStarted(started.bodyAsText()).code

        // Without the Fold's signature nothing is linked.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/devices/link") { setBody(DeviceLinkCodec.encodeApprove(code)) }.status)
        val approved = client.post("/v1/devices/link") { with(foldKey) { signed(foldSecret, "/v1/devices/link", DeviceLinkCodec.encodeApprove(code)) } }
        assertEquals(HttpStatusCode.OK, approved.status)
        assertEquals(watchId, DeviceLinkCodec.decodeLinked(approved.bodyAsText()).deviceId)
        val listed = client.post("/v1/devices/watches") { with(foldKey) { signed(foldSecret, "/v1/devices/watches", "{}") } }
        assertNotNull(DeviceLinkCodec.decodeWatches(listed.bodyAsText()).singleOrNull { it.id == watchId })
    }
}
