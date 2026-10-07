package os.meka.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.PushRequest
import os.meka.core.wire.WireCodec
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushTest {
    private val devices = InMemoryDeviceRegistry()
    private val foldSecret = devices.enrol("hh", "fold")
    private val macSecret = devices.enrol("hh", "mac")
    private val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
    private val macKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "mac"), it.publicB64) }
    private val fold = DeviceIdentity("hh", "fold")
    private val mac = DeviceIdentity("hh", "mac")
    private val foldToken = "fold-token:APA91b" + "x".repeat(40)
    private val macToken = "mac-token:APA91b" + "y".repeat(40)

    /** Records wake-ups; scheduled tasks run when the test says so. */
    private class Harness(var nowMs: Long = 1_000_000) {
        val sent = mutableListOf<PushAddress>()
        val queued = mutableListOf<Pair<Long, () -> Unit>>()
        var result = SendResult.SENT
        val store = InMemoryPushTokenStore()
        val push = Push(store, { a -> sent += a; result }, now = { nowMs }, minGapMs = 20_000, schedule = { d, t -> queued += d to t })
        fun runQueued() { val q = queued.toList(); queued.clear(); q.forEach { it.second() } }
    }

    private fun op(id: String, dev: String) = Op(id, "hh", "task", "t-$id", "title", FieldValue.Text("x"), Hlc(1, 0, dev), emptyList(), dev)

    @Test
    fun anEditOnTheMacWakesTheFoldAndNotTheMac() = testApplication {
        val h = Harness()
        application { mekaSync(InMemoryServerOpStore(), devices, push = h.push) }
        for ((key, secret, token) in listOf(Triple(foldKey, foldSecret, foldToken), Triple(macKey, macSecret, macToken))) {
            val body = WireCodec.encodePushToken(WireCodec.PushToken("fcm", token))
            val r = client.post("/v1/push/token") { with(key) { signed(secret, "/v1/push/token", body) } }
            assertEquals(HttpStatusCode.OK, r.status)
        }
        val body = WireCodec.encodePushRequest(PushRequest("hh", "mac", listOf(op("o1", "mac"))))
        assertEquals(HttpStatusCode.OK, client.post("/v1/sync/push") { with(macKey) { signed(macSecret, "/v1/sync/push", body) } }.status)
        h.runQueued()
        assertEquals(listOf(foldToken), h.sent.map { it.token })
    }

    @Test
    fun registeringNeedsASignedKeyedDeviceAndAnEmptyTokenRemovesIt() = testApplication {
        val h = Harness()
        application { mekaSync(InMemoryServerOpStore(), devices, push = h.push) }
        val body = WireCodec.encodePushToken(WireCodec.PushToken("fcm", foldToken))
        // Bearer alone isn't enough once the device has a key; a bad token shape is refused.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/push/token") { header("Authorization", "Bearer $foldSecret"); setBody(body) }.status)
        val bad = """{"w":1,"service":"fcm","token":"<script>"}"""
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/push/token") { with(foldKey) { signed(foldSecret, "/v1/push/token", bad) } }.status)
        assertEquals(HttpStatusCode.OK, client.post("/v1/push/token") { with(foldKey) { signed(foldSecret, "/v1/push/token", body) } }.status)
        assertEquals(listOf(PushAddress("fold", "fcm", foldToken)), h.store.household("hh"))
        val off = WireCodec.encodePushToken(WireCodec.PushToken("fcm", ""))
        assertEquals(HttpStatusCode.OK, client.post("/v1/push/token") { with(foldKey) { signed(foldSecret, "/v1/push/token", off) } }.status)
        assertEquals(emptyList(), h.store.household("hh"))
    }

    @Test
    fun aBurstOfEditsIsOneWakeNowAndOneAtTheEndOfTheGap() {
        val h = Harness()
        h.store.put(fold, "fcm", foldToken)
        h.push.changed(mac)
        assertEquals(listOf(0L), h.queued.map { it.first })
        h.push.changed(mac) // already on its way: nothing more queued
        assertEquals(1, h.queued.size)
        h.runQueued()
        assertEquals(1, h.sent.size)
        h.nowMs += 5_000
        h.push.changed(mac)
        h.push.changed(mac)
        assertEquals(listOf(15_000L), h.queued.map { it.first }) // one trailing wake when the gap ends
        h.nowMs += 15_000
        h.runQueued()
        assertEquals(2, h.sent.size)
        h.nowMs += 60_000
        h.push.changed(mac)
        assertEquals(listOf(0L), h.queued.map { it.first })
    }

    @Test
    fun aServerSideChangeWakesEveryDeviceOfTheHousehold() {
        val h = Harness()
        h.store.put(fold, "fcm", foldToken)
        h.store.put(mac, "fcm", macToken)
        h.store.put(DeviceIdentity("other", "phone"), "fcm", "other-token:" + "z".repeat(40))
        h.push.serverChanged("hh")
        h.runQueued()
        assertEquals(setOf(foldToken, macToken), h.sent.map { it.token }.toSet())
    }

    @Test
    fun aGoneTokenIsForgottenAndOtherHouseholdsAreNeverWoken() {
        val h = Harness()
        h.store.put(fold, "fcm", foldToken)
        h.store.put(DeviceIdentity("other", "phone"), "fcm", "other-token:" + "z".repeat(40))
        h.result = SendResult.GONE
        h.push.changed(mac)
        h.runQueued()
        assertEquals(listOf(foldToken), h.sent.map { it.token })
        assertEquals(emptyList(), h.store.household("hh"))
        assertEquals(1, h.store.household("other").size)
        // Push off (no service account yet): the token is kept for when it's set up.
        h.result = SendResult.OFF
        h.store.put(fold, "fcm", foldToken)
        h.nowMs += 60_000
        h.push.changed(mac)
        h.runQueued()
        assertEquals(1, h.store.household("hh").size)
    }

    // ---- FCM HTTP v1 ----

    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun keyFile(extra: String = "") = """{
        "type": "service_account", "project_id": "meka-os-5ae41", "private_key_id": "abc",
        "private_key": "-----BEGIN PRIVATE KEY-----\n${Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(rsa.private.encoded).replace("\n", "\\n")}\n-----END PRIVATE KEY-----\n",
        "client_email": "firebase-adminsdk-x@meka-os-5ae41.iam.gserviceaccount.com",
        "token_uri": "https://oauth2.googleapis.com/token"$extra
    }"""

    @Test
    fun theServiceAccountIsReadOnlyWhenItIsOne() {
        assertNull(FcmServiceAccount.parse("{}")) // the CDK placeholder: push off
        assertNull(FcmServiceAccount.parse(null))
        assertNull(FcmServiceAccount.parse("not json"))
        assertNull(FcmServiceAccount.parse(keyFile().replace("service_account", "authorized_user")))
        assertNull(FcmServiceAccount.parse(keyFile().replace("https://oauth2.googleapis.com/token", "https://evil.example/token")))
        val sa = assertNotNull(FcmServiceAccount.parse(keyFile()))
        assertEquals("meka-os-5ae41", sa.projectId)
    }

    @Test
    fun theAssertionIsASignedJwtForTheMessagingScope() {
        val sa = FcmServiceAccount.parse(keyFile())!!
        val jwt = GoogleJwt.assertion(sa, 1_800_000_000)
        val (h, c, s) = jwt.split(".")
        val dec = Base64.getUrlDecoder()
        val header = Json.parseToJsonElement(String(dec.decode(h))).jsonObject
        val claims = Json.parseToJsonElement(String(dec.decode(c))).jsonObject
        assertEquals("RS256", header["alg"]!!.jsonPrimitive.content)
        assertEquals(sa.clientEmail, claims["iss"]!!.jsonPrimitive.content)
        assertEquals(GoogleJwt.FCM_SCOPE, claims["scope"]!!.jsonPrimitive.content)
        assertEquals("https://oauth2.googleapis.com/token", claims["aud"]!!.jsonPrimitive.content)
        assertEquals("1800003600", claims["exp"]!!.jsonPrimitive.content)
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(rsa.public as RSAPublicKey); update("$h.$c".toByteArray()); verify(dec.decode(s))
        }
        assertTrue(ok)
    }

    @Test
    fun theSenderGetsATokenOnceAndSendsADataOnlyMessage() {
        val calls = mutableListOf<Triple<String, Map<String, String>, String>>()
        var sendStatus = 200 to "{}"
        val http = HttpPost { url, headers, body ->
            calls += Triple(url, headers, body)
            if (url == FcmServiceAccount.TOKEN_URI) 200 to """{"access_token":"ya29.abc","expires_in":3599}""" else sendStatus
        }
        var now = 1_000_000L
        val sender = FcmSender({ FcmServiceAccount.parse(keyFile()) }, http, { now })
        val fold = PushAddress("fold", "fcm", foldToken)
        assertEquals(SendResult.SENT, sender.wake(fold))
        assertEquals(SendResult.SENT, sender.wake(fold))
        assertEquals(1, calls.count { it.first == FcmServiceAccount.TOKEN_URI }) // the access token is reused
        val send = calls.last()
        assertEquals("https://fcm.googleapis.com/v1/projects/meka-os-5ae41/messages:send", send.first)
        assertEquals("Bearer ya29.abc", send.second["Authorization"])
        val msg = Json.parseToJsonElement(send.third).jsonObject["message"]!!.jsonObject
        assertEquals(foldToken, msg["token"]!!.jsonPrimitive.content)
        assertEquals("""{"t":"sync"}""", msg["data"].toString()) // nothing about Meka's data
        assertFalse("notification" in msg)
        assertEquals("normal", msg["android"]!!.jsonObject["priority"]!!.jsonPrimitive.content)
        assertTrue(calls.first().third.startsWith("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="))

        sendStatus = 404 to """{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}"""
        assertEquals(SendResult.GONE, sender.wake(fold))
        sendStatus = 500 to "oops"
        assertEquals(SendResult.FAILED, sender.wake(fold))
        // Near expiry the token is fetched again.
        now += 3_400_000
        sendStatus = 200 to "{}"
        assertEquals(SendResult.SENT, sender.wake(fold))
        assertEquals(2, calls.count { it.first == FcmServiceAccount.TOKEN_URI })
        // No service account: off, and nothing is sent.
        val before = calls.size
        assertEquals(SendResult.OFF, FcmSender({ null }, http).wake(fold))
        assertEquals(before, calls.size)
    }

    /** The store on Postgres (CI's service container); skipped when MEKA_TEST_DB_URL is unset. */
    @Test
    fun postgresStoreKeepsOneAddressPerDeviceAndSkipsRevokedDevices() {
        val url = System.getenv("MEKA_TEST_DB_URL")?.takeIf { it.isNotBlank() } ?: return
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
            password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 2
        }).use { ds ->
            Migrations.apply(ds)
            val reg = PostgresDeviceRegistry(ds)
            reg.enrol("push-hh", "fold", "Fold")
            reg.enrol("push-hh", "mac", "Mac")
            ds.connection.use { c ->
                c.createStatement().execute("DELETE FROM push_token WHERE household_id = 'push-hh'")
                c.createStatement().execute("UPDATE device SET revoked_at = NULL WHERE household_id = 'push-hh'")
            }
            val store = PostgresPushTokenStore(ds)
            store.put(DeviceIdentity("push-hh", "fold"), "fcm", "a".repeat(30))
            store.put(DeviceIdentity("push-hh", "fold"), "fcm", foldToken) // replaces
            store.put(DeviceIdentity("push-hh", "mac"), "fcm", macToken)
            assertEquals(listOf(PushAddress("fold", "fcm", foldToken), PushAddress("mac", "fcm", macToken)), store.household("push-hh"))
            store.forget("push-hh", macToken)
            assertEquals(listOf("fold"), store.household("push-hh").map { it.deviceId })
            ds.connection.use { c -> c.createStatement().execute("UPDATE device SET revoked_at = now() WHERE household_id = 'push-hh' AND id = 'fold'") }
            assertEquals(emptyList(), store.household("push-hh"))
            store.remove(DeviceIdentity("push-hh", "fold"))
            ds.connection.use { c -> c.createStatement().execute("UPDATE device SET revoked_at = NULL WHERE household_id = 'push-hh'") }
            assertEquals(emptyList(), store.household("push-hh"))
        }
    }
}
