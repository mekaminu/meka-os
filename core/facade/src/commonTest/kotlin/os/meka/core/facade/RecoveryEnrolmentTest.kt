package os.meka.core.facade

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import os.meka.core.domain.RecoveryKeyRules
import os.meka.core.wire.RecoveryCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The device's half of the recovery key (build plan V1 "Recovery key"): only the secret is sent, signed with the new key. */
class RecoveryEnrolmentTest {
    private class Key : DeviceKey {
        val signed = mutableListOf<String>()
        override val publicKeyDerBase64 = "A".repeat(124)
        override fun signBase64(message: String): String { signed += message; return "sig" }
    }

    private val json = headersOf("Content-Type", "application/json")
    private val requests = mutableListOf<HttpRequestData>()
    private var status = HttpStatusCode.OK
    private var reply = RecoveryCodec.encodeEnrolled(RecoveryCodec.Enrolled("home", "android00112233aabb", "ab".repeat(32)))
    private val entropy = ByteArray(32) { (it * 3).toByte() }
    private val words = RecoveryKeyRules.phrase(entropy).joinToString(" ")

    private fun client() = HttpClient(MockEngine { req ->
        requests += req
        when (req.url.encodedPath) {
            "/v1/recovery/enrol" -> respond(reply, status, json)
            "/v1/recovery/status" -> respond(RecoveryCodec.encodeStatus(RecoveryCodec.Status(RecoveryCodec.READY, 5L, "Fold")), HttpStatusCode.OK, json)
            "/v1/recovery/set" -> respond(RecoveryCodec.encodeStatus(RecoveryCodec.Status(RecoveryCodec.READY, 6L, "Fold")), HttpStatusCode.OK, json)
            else -> respond("", HttpStatusCode.NotFound)
        }
    })

    private fun body(r: HttpRequestData) = (r.body as TextContent).text

    @Test
    fun theWordsConnectANewDeviceAndOnlyTheirSecretIsSent() = runTest {
        val key = Key()
        val r = RecoveryEnrolment.enrol(client(), "https://meka.example", key, words, "android00112233aabb", "Galaxy Z Fold", 1_000)
        assertEquals(RecoveryEnrolment.Result.Enrolled("home", "android00112233aabb", "ab".repeat(32)), r)
        val sent = RecoveryCodec.decodeEnrol(body(requests.single()))
        assertEquals(RecoveryKeyRules.secret(entropy), sent.secret)
        assertTrue(words.split(' ').none { body(requests.single()).contains("\"$it\"") })
        assertEquals(null, requests.single().headers["Authorization"])
        assertEquals(RequestSigning.canonical("POST", "/v1/recovery/enrol", 1_000, requests.single().headers[RequestSigning.HEADER_NONCE]!!, body(requests.single())), key.signed.single())
    }

    @Test
    fun badWordsNeverLeaveAndRefusalsAreSaidInWords() = runTest {
        val r = RecoveryEnrolment.enrol(client(), "https://meka.example", Key(), words.substringBeforeLast(' '), "android00112233aabb", "Fold", 1)
        assertEquals(RecoveryEnrolment.Result.Unreadable("23 of 24 words"), r)
        assertTrue(requests.isEmpty())
        status = HttpStatusCode.NotFound
        reply = RecoveryCodec.encodeError(RecoveryCodec.ERR_KEY)
        val refused = RecoveryEnrolment.enrol(client(), "https://meka.example", Key(), words, "android00112233aabb", "Fold", 1)
        assertEquals(RecoveryCodec.ERR_KEY, (refused as RecoveryEnrolment.Result.Refused).reason)
        assertTrue(RecoveryEnrolment.enrol(client(), "http://meka.example", Key(), words, "android00112233aabb", "Fold", 1) is RecoveryEnrolment.Result.Failed)
    }

    @Test
    fun aKeyedDeviceReadsAndSetsTheKeyThroughItsTransport() = runTest {
        val t = HttpSyncTransport(client(), "https://meka.example", Key(), { 1L }) { "s".repeat(64) }
        assertEquals(RecoveryCodec.Status(RecoveryCodec.READY, 5L, "Fold"), t.recoveryStatus())
        assertEquals(6L, t.setRecoveryKey(RecoveryKeyRules.verifier(entropy)).createdAtMs)
        assertEquals(RecoveryKeyRules.verifier(entropy), RecoveryCodec.decodeSet(body(requests.last())))
    }

    @Test
    fun theMacGetsTheSecretOrOneLine() {
        val ok = RecoveryEnrolment.outcome(RecoveryEnrolment.Result.Enrolled("home", "mac0011", "ab".repeat(32)), "home", "mac0011", "Mac")
        assertEquals("ab".repeat(32), ok.secret)
        assertEquals(null, ok.problem)
        val other = RecoveryEnrolment.outcome(RecoveryEnrolment.Result.Enrolled("away", "mac0011", "ab".repeat(32)), "home", "mac0011", "Mac")
        assertEquals(null, other.secret)
        assertEquals("This Mac can't join that household · reinstall Navilife and try again", other.problem)
        assertEquals("23 of 24 words", RecoveryEnrolment.outcome(RecoveryEnrolment.Result.Unreadable("23 of 24 words"), "home", "m", "Mac").problem)
        assertEquals("Couldn't reach the server", RecoveryEnrolment.outcome(RecoveryEnrolment.Result.Failed("Couldn't reach the server"), "home", "m", "Mac").problem)
    }
}
