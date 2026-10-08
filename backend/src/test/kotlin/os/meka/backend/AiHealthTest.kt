package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.core.sync.InMemoryServerOpStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The AI layer's first slice (build plan V1): the server reads its key and checks it works, never sharing it. */
class AiHealthTest {
    private var now = 1_791_450_000_000L
    private val goodKey = "sk-ant-api03-" + "a".repeat(40)

    private class FakeGet(var status: Int = 200, var fail: Boolean = false) : HttpGet {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        override fun get(url: String, headers: Map<String, String>): Int {
            calls += url to headers
            if (fail) throw java.io.IOException("down")
            return status
        }
    }

    @Test
    fun theKeyIsReadFromItsSecretAndThePlaceholderMeansOff() {
        assertEquals(goodKey, AnthropicKey.parse("""{"api_key": "  $goodKey "}"""))
        assertNull(AnthropicKey.parse("{}"))
        assertNull(AnthropicKey.parse("""{"api_key": ""}"""))
        assertNull(AnthropicKey.parse("""{"api_key": "not-a-key"}"""))
        assertNull(AnthropicKey.parse("""{"api_key": "sk-ant-a b c d e f g h i j k"}"""))
        assertNull(AnthropicKey.parse("not json"))
        assertNull(AnthropicKey.parse(null))
    }

    @Test
    fun noKeyIsOffWithoutAskingAnthropic() {
        val http = FakeGet()
        val s = AiHealth(key = { null }, http = http, nowMs = { now }).status()
        assertEquals(AiStatus.State.OFF, s.state)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun aGoodKeyIsOnAndTheCheckSendsNothingButTheKey() {
        val http = FakeGet()
        val ai = AiHealth(key = { goodKey }, http = http, nowMs = { now })
        val s = ai.status()
        assertEquals(AiStatus.State.ON, s.state)
        assertEquals(now, s.checkedAtMs)
        val (url, headers) = http.calls.single()
        assertEquals("https://api.anthropic.com/v1/models?limit=1", url)
        assertEquals(goodKey, headers["x-api-key"])
        assertEquals("2023-06-01", headers["anthropic-version"])
        // The answer never carries the key.
        assertFalse(goodKey in s.toJson().toString())
    }

    @Test
    fun aGoodAnswerIsTrustedForSixHoursAndANewKeyIsCheckedAtOnce() {
        val http = FakeGet()
        var key = goodKey
        val ai = AiHealth(key = { key }, http = http, nowMs = { now })
        ai.status()
        now += 5 * 3_600_000L
        ai.status()
        assertEquals(1, http.calls.size)
        now += 3_600_000L
        ai.status()
        assertEquals(2, http.calls.size)
        key = "sk-ant-api03-" + "b".repeat(40)
        ai.status()
        assertEquals(3, http.calls.size)
    }

    @Test
    fun failuresSayWhyAndAreCheckedAgainAfterTenMinutes() {
        val http = FakeGet(status = 401)
        val ai = AiHealth(key = { goodKey }, http = http, nowMs = { now })
        assertEquals("Anthropic refused the key", ai.status().reason)
        assertEquals(AiStatus.State.FAILING, ai.status().state)
        assertEquals(1, http.calls.size)
        now += 10 * 60_000L
        http.status = 429
        assertEquals("Anthropic is limiting requests (spend cap or rate limit)", ai.status().reason)
        now += 10 * 60_000L
        http.fail = true
        assertEquals("Couldn't reach Anthropic", ai.status().reason)
        now += 10 * 60_000L
        http.fail = false; http.status = 200
        assertEquals(AiStatus.State.ON, ai.status().state)
    }

    @Test
    fun theKeyTakenAwayTurnsItOff() {
        var key: String? = goodKey
        val ai = AiHealth(key = { key }, http = FakeGet(), nowMs = { now })
        assertEquals(AiStatus.State.ON, ai.status().state)
        key = null
        assertEquals(AiStatus.State.OFF, ai.status().state)
    }

    @Test
    fun keyedDevicesAskWhetherAiIsOn() = testApplication {
        val devices = InMemoryDeviceRegistry()
        val foldSecret = devices.enrol("hh", "fold")
        val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
        val bare = devices.enrol("hh", "old")
        application { mekaSync(InMemoryServerOpStore(), devices, ai = AiHealth(key = { goodKey }, http = FakeGet(), nowMs = { now })) }

        val ok = client.post("/v1/ai/status") { with(foldKey) { signed(foldSecret, "/v1/ai/status", "{}") } }
        assertEquals(HttpStatusCode.OK, ok.status)
        val o = Json.parseToJsonElement(ok.bodyAsText()).jsonObject
        assertEquals("on", o["state"]!!.jsonPrimitive.content)
        assertFalse(goodKey in ok.bodyAsText())
        // Unsigned, unkeyed and the release-only publisher are refused.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/ai/status") { header("Authorization", "Bearer $foldSecret"); setBody("{}") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/ai/status") { header("Authorization", "Bearer $bare"); setBody("{}") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/ai/status") { header("Authorization", "Publisher github-build"); setBody("{}") }.status)
    }

    @Test
    fun withoutTheAiSecretTheRouteIsLeftOut() = testApplication {
        val devices = InMemoryDeviceRegistry()
        val foldSecret = devices.enrol("hh", "fold")
        val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
        application { mekaSync(InMemoryServerOpStore(), devices) }
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/ai/status") { with(foldKey) { signed(foldSecret, "/v1/ai/status", "{}") } }.status)
    }
}
