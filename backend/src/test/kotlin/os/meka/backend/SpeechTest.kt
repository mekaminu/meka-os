package os.meka.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.SpeechCodec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MEKA's voice (build plan V1, Weather and a voice, item 3): Amazon Polly behind a keyed route, metered and capped. */
class SpeechTest {
    private class FakePolly(var voices: List<SpeechVoice>) : SpeechEngine {
        val listed = mutableListOf<String>()
        val said = mutableListOf<Triple<String, String, String>>()
        var fail = false

        override fun voices(language: String): List<SpeechVoice> {
            listed += language
            if (fail) error("no network")
            return voices
        }

        override fun synthesize(text: String, voice: String, engine: String): ByteArray {
            if (fail) error("no network")
            said += Triple(text, voice, engine)
            return "ID3:$voice:$text".toByteArray()
        }
    }

    private val gb = listOf(
        SpeechVoice("Brian", "Male", setOf("standard", "neural")),
        SpeechVoice("Emma", "Female", setOf("standard", "neural")),
        SpeechVoice("Amy", "Female", setOf("standard", "neural", "generative")),
        SpeechVoice("Arthur", "Male", setOf("neural")),
        SpeechVoice("Olivia", "Female", setOf("neural", "generative")),
        SpeechVoice("Robot", "Male", setOf("standard")),
    )

    private var now = 1_791_504_000_000L // 2026-10-09 00:00 UTC

    @Test
    fun britishVoicesAreOfferedBestFirstOnTheirBestEngine() {
        val polly = FakePolly(gb)
        val v = SpeechService(polly, InMemorySpeechUsageStore(), nowMs = { now }).voices()
        assertEquals(SpeechCodec.Voices.ON, v.state)
        assertEquals(listOf("Amy" to "generative", "Olivia" to "generative", "Emma" to "neural", "Brian" to "neural", "Arthur" to "neural"), v.voices.map { it.id to it.engine })
        assertEquals("Amy", v.defaultVoice)
        assertEquals("2026-10", v.month)
        assertEquals(1_000_000L, v.capChars)
        assertEquals(listOf("en-GB"), polly.listed)
        // Only neural here (a region without generative): neural voices, Meka's shortlist first.
        val neuralOnly = SpeechService.rank(gb.map { it.copy(engines = it.engines - "generative") })
        assertEquals(listOf("Amy", "Emma", "Brian", "Arthur", "Olivia"), neuralOnly.map { it.id })
        assertTrue(neuralOnly.all { it.engine == "neural" })
    }

    @Test
    fun aReplyIsSaidInTheChosenVoiceAndCounted() {
        val polly = FakePolly(gb)
        val usage = InMemorySpeechUsageStore()
        val s = SpeechService(polly, usage, nowMs = { now })
        val r = s.speak(SpeechCodec.Request("Good morning, Meka.", "Brian"))
        assertEquals(SpeechCodec.Response.SPOKEN, r.state)
        assertEquals("Brian" to "neural", r.voice to r.engine)
        assertEquals("mp3", r.format)
        assertContentEquals("ID3:Brian:Good morning, Meka.".toByteArray(), Base64.getDecoder().decode(r.audio))
        assertEquals(19L, usage.used("2026-10"))
        // No voice, or one this server doesn't offer (a standard-only one included), is said by the default.
        assertEquals("Amy" to "generative", s.speak(SpeechCodec.Request("Hello.")).let { it.voice to it.engine })
        assertEquals("Amy", s.speak(SpeechCodec.Request("Hello.", "Robot")).voice)
        assertEquals("Emma", s.speak(SpeechCodec.Request("Hello.", "emma")).voice)
        assertEquals(37L, usage.used("2026-10"))
        // The voice list is read once and kept for six hours.
        assertEquals(1, polly.listed.size)
        now += 6 * 3_600_000L
        s.speak(SpeechCodec.Request("Hi."))
        assertEquals(2, polly.listed.size)
    }

    @Test
    fun theMonthsCapHandsBackToTheDevicesVoiceUntilTheFirst() {
        val polly = FakePolly(gb)
        val usage = InMemorySpeechUsageStore()
        val s = SpeechService(polly, usage, capChars = 30, nowMs = { now })
        assertEquals(SpeechCodec.Response.SPOKEN, s.speak(SpeechCodec.Request("a".repeat(25))).state)
        val over = s.speak(SpeechCodec.Request("b".repeat(6)))
        assertEquals(SpeechCodec.Response.OVER, over.state)
        assertTrue("1st" in over.reason!!)
        assertEquals(1, polly.said.size) // refused before asking Polly
        assertEquals(SpeechCodec.Response.SPOKEN, s.speak(SpeechCodec.Request("c".repeat(5))).state)
        assertEquals(30L, s.voices().usedChars)
        // A new month starts from nothing.
        now += 31L * 24 * 3_600_000
        assertEquals("2026-11", s.month())
        assertEquals(SpeechCodec.Response.SPOKEN, s.speak(SpeechCodec.Request("b".repeat(6))).state)
    }

    @Test
    fun offAndFailuresSayWhyAndCountNothing() {
        val usage = InMemorySpeechUsageStore()
        assertEquals(SpeechCodec.Response.OFF, SpeechService(null, usage).speak(SpeechCodec.Request("Hi.")).state)
        assertEquals(SpeechCodec.Voices.OFF, SpeechService(null, usage).voices().state)
        val polly = FakePolly(gb).apply { fail = true }
        val s = SpeechService(polly, usage, nowMs = { now })
        val failed = s.speak(SpeechCodec.Request("Hi."))
        assertEquals(SpeechCodec.Response.FAILED, failed.state)
        assertEquals("Couldn't reach Amazon Polly", failed.reason)
        assertEquals(SpeechCodec.Voices.FAILED, s.voices().state)
        // Once Polly answers again the voices are read again at once (a failure isn't cached).
        polly.fail = false
        assertEquals(SpeechCodec.Response.SPOKEN, s.speak(SpeechCodec.Request("Hi.")).state)
        assertEquals(3L, usage.used("2026-10"))
        val none = SpeechService(FakePolly(listOf(SpeechVoice("Robot", "Male", setOf("standard")))), usage, nowMs = { now })
        assertEquals(SpeechCodec.Response.FAILED, none.speak(SpeechCodec.Request("Hi.")).state)
        assertEquals(3L, usage.used("2026-10"))
    }

    @Test
    fun keyedDevicesSpeakAndEveryoneElseIsRefused() = testApplication {
        val devices = InMemoryDeviceRegistry()
        val foldSecret = devices.enrol("hh", "fold")
        val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
        val bare = devices.enrol("hh", "old")
        val polly = FakePolly(gb)
        application { mekaSync(InMemoryServerOpStore(), devices, speech = SpeechService(polly, InMemorySpeechUsageStore(), nowMs = { now })) }

        val voicesBody = SpeechCodec.encodeVoicesRequest()
        val v = client.post("/v1/speech/voices") { with(foldKey) { signed(foldSecret, "/v1/speech/voices", voicesBody) } }
        assertEquals(HttpStatusCode.OK, v.status)
        assertEquals("Amy", SpeechCodec.decodeVoices(v.bodyAsText()).defaultVoice)

        val body = SpeechCodec.encodeRequest(SpeechCodec.Request("Shall I move Book dentist to tomorrow at 09:00?", "Arthur"))
        val ok = client.post("/v1/speech/speak") { with(foldKey) { signed(foldSecret, "/v1/speech/speak", body) } }
        assertEquals(HttpStatusCode.OK, ok.status)
        val r = SpeechCodec.decodeResponse(ok.bodyAsText())
        assertEquals("Arthur", r.voice)
        assertContentEquals("ID3:Arthur:Shall I move Book dentist to tomorrow at 09:00?".toByteArray(), Base64.getDecoder().decode(r.audio))
        assertFalse("dentist" in ok.bodyAsText()) // the words come back only as audio

        val tooLong = SpeechCodec.encodeRequest(SpeechCodec.Request("a".repeat(SpeechCodec.MAX_TEXT + 1)))
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/speech/speak") { with(foldKey) { signed(foldSecret, "/v1/speech/speak", tooLong) } }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/speech/speak") { header("Authorization", "Bearer $foldSecret"); setBody(body) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/speech/speak") { header("Authorization", "Bearer $bare"); setBody(body) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/speech/speak") { header("Authorization", "Publisher github-build"); setBody(body) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/speech/voices") { header("Authorization", "Bearer $bare"); setBody(voicesBody) }.status)
        assertEquals(1, polly.said.size)
    }

    @Test
    fun aDeviceThatAsksGetsRawMp3AndRefusalsStayJson() = testApplication {
        val devices = InMemoryDeviceRegistry()
        val foldSecret = devices.enrol("hh", "fold")
        val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
        val polly = FakePolly(gb)
        val usage = InMemorySpeechUsageStore()
        application { mekaSync(InMemoryServerOpStore(), devices, speech = SpeechService(polly, usage, capChars = 40, nowMs = { now })) }

        val body = SpeechCodec.encodeRequest(SpeechCodec.Request("One moment…", "Brian", binary = true))
        val ok = client.post("/v1/speech/speak") { with(foldKey) { signed(foldSecret, "/v1/speech/speak", body) } }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("audio/mpeg", ok.headers["Content-Type"]?.substringBefore(';'))
        assertEquals("Brian", ok.headers[SpeechCodec.HEADER_VOICE])
        assertEquals("neural", ok.headers[SpeechCodec.HEADER_ENGINE])
        assertContentEquals("ID3:Brian:One moment…".toByteArray(), ok.readRawBytes())
        assertEquals(11L, usage.used("2026-10"))

        // Over the month's cap: JSON "over", even though the device asked for MP3.
        val long = SpeechCodec.encodeRequest(SpeechCodec.Request("b".repeat(30), binary = true))
        val over = client.post("/v1/speech/speak") { with(foldKey) { signed(foldSecret, "/v1/speech/speak", long) } }
        assertEquals("application/json", over.headers["Content-Type"]?.substringBefore(';'))
        assertEquals(SpeechCodec.Response.OVER, SpeechCodec.decodeResponse(over.bodyAsText()).state)
    }

    @Test
    fun warmingSaysOneShortLineAndTheListRefreshesOffTheRequestPath() {
        val polly = FakePolly(gb)
        val usage = InMemorySpeechUsageStore()
        val s = SpeechService(polly, usage, nowMs = { now })
        s.warm()
        assertEquals(listOf(Triple(SpeechService.WARM_LINE, "Amy", "generative")), polly.said)
        assertEquals(SpeechService.WARM_LINE.length.toLong(), usage.used("2026-10"))
        assertEquals(1, polly.listed.size)
        // A request after warming doesn't read the list again.
        s.speak(SpeechCodec.Request("Hi."))
        assertEquals(1, polly.listed.size)
        // The background refresh reads it, and a failed refresh keeps the old list.
        polly.voices = gb.filter { it.id != "Amy" }
        s.refreshVoices()
        assertEquals("Olivia", s.offered().first().id)
        polly.fail = true
        s.refreshVoices()
        assertEquals("Olivia", s.offered().first().id)
        // Warming never throws, and without Polly does nothing.
        s.warm()
        SpeechService(null, usage).warm()
        // At the cap it says nothing.
        val full = InMemorySpeechUsageStore().also { it.add("2026-10", 1_000_000) }
        val p2 = FakePolly(gb)
        SpeechService(p2, full, nowMs = { now }).warm()
        assertTrue(p2.said.isEmpty())
    }

    @Test
    fun withoutPollyTheRoutesAreLeftOut() = testApplication {
        val devices = InMemoryDeviceRegistry()
        val foldSecret = devices.enrol("hh", "fold")
        val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
        application { mekaSync(InMemoryServerOpStore(), devices) }
        val body = SpeechCodec.encodeRequest(SpeechCodec.Request("Hi."))
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/speech/speak") { with(foldKey) { signed(foldSecret, "/v1/speech/speak", body) } }.status)
    }

    /** The meter on Postgres (CI's service container); skipped when MEKA_TEST_DB_URL is unset. */
    @Test
    fun postgresMeterCountsPerMonth() {
        val url = System.getenv("MEKA_TEST_DB_URL")?.takeIf { it.isNotBlank() } ?: return
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
            password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 2
        }).use { ds ->
            Migrations.apply(ds)
            ds.connection.use { c -> c.createStatement().execute("DELETE FROM speech_usage WHERE month IN ('1999-01', '1999-02')") }
            val store = PostgresSpeechUsageStore(ds)
            assertEquals(0L, store.used("1999-01"))
            store.add("1999-01", 120)
            store.add("1999-01", 30)
            store.add("1999-02", 7)
            assertEquals(150L, store.used("1999-01"))
            assertEquals(7L, store.used("1999-02"))
        }
    }
}
