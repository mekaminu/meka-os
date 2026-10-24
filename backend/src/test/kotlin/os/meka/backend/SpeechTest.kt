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
import kotlin.test.assertFailsWith
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
        // Obi's voice (Brian, a British man's) leads whatever its engine (the Obi plan, slice 2), then generative first.
        assertEquals(listOf("Brian" to "neural", "Amy" to "generative", "Olivia" to "generative", "Arthur" to "neural", "Emma" to "neural"), v.voices.map { it.id to it.engine })
        assertEquals("Brian", v.defaultVoice)
        assertEquals("2026-10", v.month)
        assertEquals(1_000_000L, v.capChars)
        assertEquals(listOf("en-GB"), polly.listed)
        // Only neural here (a region without generative): neural voices, Meka's shortlist first.
        val neuralOnly = SpeechService.rank(gb.map { it.copy(engines = it.engines - "generative") })
        assertEquals(listOf("Brian", "Arthur", "Amy", "Emma", "Olivia"), neuralOnly.map { it.id })
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
        assertEquals("Brian" to "neural", s.speak(SpeechCodec.Request("Hello.")).let { it.voice to it.engine })
        assertEquals("Brian", s.speak(SpeechCodec.Request("Hello.", "Robot")).voice)
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
        assertEquals("Brian", SpeechCodec.decodeVoices(v.bodyAsText()).defaultVoice)

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
        assertEquals(listOf(Triple(SpeechService.WARM_LINE, "Brian", "neural")), polly.said)
        assertEquals(SpeechService.WARM_LINE.length.toLong(), usage.used("2026-10"))
        assertEquals(1, polly.listed.size)
        // A request after warming doesn't read the list again.
        s.speak(SpeechCodec.Request("Hi."))
        assertEquals(1, polly.listed.size)
        // The background refresh reads it, and a failed refresh keeps the old list.
        polly.voices = gb.filter { it.id != "Brian" }
        s.refreshVoices()
        assertEquals("Amy", s.offered().first().id)
        polly.fail = true
        s.refreshVoices()
        assertEquals("Amy", s.offered().first().id)
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

    private val chirp = listOf(
        SpeechVoice("Charon", "Male", setOf("chirp3hd")),
        SpeechVoice("Aoede", "Female", setOf("chirp3hd")),
        SpeechVoice("Aoede-US", "Female", setOf("chirp3hd")),
        SpeechVoice("en-GB-Odd", "Female", setOf("chirp3hd")),
    )

    @Test
    fun googleVoicesComeFirstWithTheirMonthAndPollyStaysTheDefault() {
        val google = FakePolly(chirp)
        val s = SpeechService(FakePolly(gb), InMemorySpeechUsageStore(), nowMs = { now }, google = google)
        val v = s.voices()
        assertEquals(listOf("Charon", "Aoede", "Aoede-US", "Brian"), v.voices.take(4).map { it.id })
        assertEquals(listOf("google", "google", "google", "polly"), v.voices.take(4).map { it.provider })
        assertEquals("Brian", v.defaultVoice)
        assertEquals(SpeechCodec.Google(SpeechCodec.Google.ON, 0, 1_000_000, 900_000, null), v.google)
        // No voice asked for: Polly's default speaks, never a Google voice.
        assertEquals("Brian", s.speak(SpeechCodec.Request("Hello.")).voice)
        // No key: Google's voices are hidden and the line says how to add it.
        val noKey = object : SpeechEngine by FakePolly(chirp) { override fun configured() = false }
        val off = SpeechService(FakePolly(gb), InMemorySpeechUsageStore(), nowMs = { now }, google = noKey).voices()
        assertTrue(off.voices.all { it.provider == "polly" })
        assertEquals(SpeechCodec.Google.NO_KEY, off.google?.state)
        // Google unreachable: Polly's voices still offered, the line says Google failed.
        val down = FakePolly(chirp).apply { fail = true }
        val failed = SpeechService(FakePolly(gb), InMemorySpeechUsageStore(), nowMs = { now }, google = down).voices()
        assertEquals(SpeechCodec.Voices.ON, failed.state)
        assertEquals(SpeechCodec.Google.FAILED, failed.google?.state)
    }

    @Test
    fun aChosenGoogleVoiceSpeaksOnItsOwnMeterAndFallsBackToPollyOnceOnFailure() {
        val google = FakePolly(chirp)
        val polly = FakePolly(gb)
        val pollyUsage = InMemorySpeechUsageStore()
        val googleUsage = InMemorySpeechUsageStore()
        val s = SpeechService(polly, pollyUsage, nowMs = { now }, google = google, googleUsage = googleUsage)
        val r = s.speak(SpeechCodec.Request("Good morning, Meka.", "Aoede"))
        assertEquals("Aoede" to "chirp3hd", r.voice to r.engine)
        assertEquals(Triple("Good morning, Meka.", "Aoede", "chirp3hd"), google.said.single())
        assertEquals(19L, googleUsage.used("2026-10"))
        assertEquals(0L, pollyUsage.used("2026-10"))
        // Google fails: the same words, once, in Polly's default voice; the time is kept for the picker's line.
        google.fail = true
        val fell = s.speak(SpeechCodec.Request("Ten past seven.", "Aoede"))
        assertEquals(SpeechCodec.Response.SPOKEN, fell.state)
        assertEquals("Brian" to "neural", fell.voice to fell.engine)
        assertEquals("Ten past seven.", polly.said.single().first)
        assertEquals(now, s.googleFailedAtMs)
        assertEquals(15L, pollyUsage.used("2026-10"))
        assertEquals(19L, googleUsage.used("2026-10"))
    }

    @Test
    fun afterAGoogleFailurePollyKeepsTheJobFor15MinutesThenGoogleIsTriedAgain() {
        val google = FakePolly(chirp)
        val polly = FakePolly(gb)
        val s = SpeechService(polly, InMemorySpeechUsageStore(), nowMs = { now }, google = google)
        s.offered()
        google.fail = true
        assertEquals("Brian", s.speak(SpeechCodec.Request("One.", "Aoede")).voice)
        assertTrue(s.googleSwitched())
        // Google works again, but the next sentences (and a call line) don't ask it inside the hold: Polly at once.
        google.fail = false
        now += 5 * 60_000L
        assertEquals("Brian", s.speak(SpeechCodec.Request("Two.", "Aoede")).voice)
        assertEquals(null, s.googleClip("Three.", "Aoede"))
        assertTrue(google.said.isEmpty())
        assertEquals(5 * 60_000L, s.googleSwitchedForMs())
        // Past 15 minutes the next one quietly asks Google again: it works and the switch ends, its length kept.
        now += 11 * 60_000L
        assertEquals("Aoede", s.speak(SpeechCodec.Request("Four.", "Aoede")).voice)
        assertFalse(s.googleSwitched())
        assertEquals(16 * 60_000L, s.googleSwitchedForMs())
        // A probe that fails starts the hold over.
        google.fail = true
        s.speak(SpeechCodec.Request("Five.", "Aoede"))
        now += 16 * 60_000L
        s.speak(SpeechCodec.Request("Six.", "Aoede"))
        google.fail = false
        now += 1 * 60_000L
        assertEquals("Brian", s.speak(SpeechCodec.Request("Seven.", "Aoede")).voice)
        assertTrue(s.googleSwitched())
    }

    @Test
    fun oneGoogleTimeoutRetriesGoogleTwoInARowSwitch() {
        val google = object : SpeechEngine by FakePolly(chirp) {
            override fun synthesize(text: String, voice: String, engine: String): ByteArray =
                throw java.net.http.HttpTimeoutException("slow")
        }
        val s = SpeechService(FakePolly(gb), InMemorySpeechUsageStore(), nowMs = { now }, google = google)
        s.speak(SpeechCodec.Request("One.", "Aoede"))
        assertFalse(s.googleSwitched())
        s.speak(SpeechCodec.Request("Two.", "Aoede"))
        assertTrue(s.googleSwitched())
        assertTrue(SpeechService.isTimeout(RuntimeException(java.net.SocketTimeoutException())))
        assertFalse(SpeechService.isTimeout(IllegalStateException("refused")))
    }

    @Test
    fun pollysClipsAreCountedInTheAiBudgetAndStopWhenItIsSpent() {
        val google = FakePolly(chirp)
        val polly = FakePolly(gb)
        val store = InMemoryAiUsageStore()
        val budget = AiBudget(1_000L, store, nowMs = { now }) // $0.001: 34 generative characters
        val s = SpeechService(polly, InMemorySpeechUsageStore(), nowMs = { now }, google = google, budget = budget)
        // A Google clip is inside its free tier: nothing counted.
        assertEquals(SpeechCodec.Response.SPOKEN, s.speak(SpeechCodec.Request("Good morning, Meka.", "Aoede")).state)
        assertTrue(store.month("2026-10").isEmpty())
        // Polly's generative Amy: 15 characters at $30 a million.
        assertEquals(SpeechCodec.Response.SPOKEN, s.speak(SpeechCodec.Request("Ten past seven.", "Amy")).state)
        assertEquals(AiUsage(SpeechService.VOICE_FEATURE, 1, 0, 0, 450), store.month("2026-10").single())
        assertEquals(450L, store.day("2026-10-09").single().microUsd)
        // Neural Emma costs less a character.
        s.speak(SpeechCodec.Request("Ten past seven.", "Emma"))
        assertEquals(450L + 240, store.month("2026-10").single().microUsd)
        assertEquals(16L * 10, PollyPrices.microUsd("neural", 10))
        assertEquals(4L * 10, PollyPrices.microUsd("standard", 10))
        // Spent: Polly hands back to the device's voice until the 1st, and nothing more is said or counted.
        s.speak(SpeechCodec.Request("It's a work day, finishing at half five."))
        val over = s.speak(SpeechCodec.Request("Hi."))
        assertEquals(SpeechCodec.Response.OVER, over.state)
        assertEquals(SpeechService.BUDGET_OVER, over.reason)
        assertEquals(3, polly.said.size)
        assertEquals(3L, store.month("2026-10").single().calls)
        // Warming at start is counted too; without a budget nothing changes.
        val other = InMemoryAiUsageStore()
        SpeechService(FakePolly(gb), InMemorySpeechUsageStore(), nowMs = { now }, budget = AiBudget(20_000_000L, other, nowMs = { now })).warm()
        assertEquals(SpeechService.WARM_LINE.length * 16L, other.month("2026-10").single().microUsd) // Obi's neural Brian
        assertEquals(SpeechCodec.Response.SPOKEN, SpeechService(FakePolly(gb), InMemorySpeechUsageStore(), nowMs = { now }).speak(SpeechCodec.Request("Hi.")).state)
    }

    @Test
    fun pastNineHundredThousandGoogleCharactersPollySpeaksSoTheFreeTierIsNeverExceeded() {
        val google = FakePolly(chirp)
        val polly = FakePolly(gb)
        val googleUsage = InMemorySpeechUsageStore().apply { add("2026-10", 899_990) }
        val s = SpeechService(polly, InMemorySpeechUsageStore(), nowMs = { now }, google = google, googleUsage = googleUsage)
        assertEquals("Aoede", s.speak(SpeechCodec.Request("Hi there.", "Aoede")).voice) // 899,999: still free
        val over = s.speak(SpeechCodec.Request("Good morning.", "Aoede"))
        assertEquals("Brian", over.voice)
        assertEquals(1, google.said.size)
        assertEquals(899_999L, googleUsage.used("2026-10"))
        assertEquals(null, s.googleFailedAtMs) // the cap isn't a failure
        // A new month starts Google's count again.
        now += 31L * 86_400_000L
        assertEquals("Aoede", s.speak(SpeechCodec.Request("Hello.", "Aoede")).voice)
    }

    @Test
    fun googlesRequestsAndAnswersAreReadSafely() {
        assertEquals("en-GB-Chirp3-HD-Aoede", GoogleSpeech.fullName("Aoede"))
        assertEquals("en-US-Chirp3-HD-Aoede", GoogleSpeech.fullName("Aoede-US"))
        assertEquals(null, GoogleSpeech.fullName("Amy;drop"))
        val body = """{"voices":[{"languageCodes":["en-GB"],"name":"en-GB-Chirp3-HD-Aoede","ssmlGender":"FEMALE"},
            {"languageCodes":["en-GB"],"name":"en-GB-Neural2-A","ssmlGender":"FEMALE"},
            {"languageCodes":["en-GB"],"name":"en-GB-Chirp3-HD-Charon","ssmlGender":"MALE"}]}"""
        assertEquals(listOf("Aoede" to "Female", "Charon" to "Male"), GoogleSpeech.parseVoices(body, "en-GB").map { it.id to it.gender })
        assertEquals(listOf("Aoede-US"), GoogleSpeech.parseVoices(body.replace("en-GB", "en-US"), "en-US").filter { it.id.startsWith("Aoede") }.map { it.id })
        assertTrue(GoogleSpeech.parseVoices("not json", "en-GB").isEmpty())
        val req = GoogleSpeech.request("It's ten past seven.", "en-GB-Chirp3-HD-Aoede")
        assertTrue(""""languageCode":"en-GB"""" in req && """"name":"en-GB-Chirp3-HD-Aoede"""" in req && """"audioEncoding":"MP3"""" in req, req)
        assertContentEquals("ID3".toByteArray(), GoogleSpeech.parseAudio("""{"audioContent":"${Base64.getEncoder().encodeToString("ID3".toByteArray())}"}"""))
        assertEquals(null, GoogleSpeech.parseAudio("""{"error":{"code":403}}"""))
        // The key goes in a header, never the URL.
        val seen = mutableListOf<Pair<String, Map<String, String>>>()
        val g = GoogleSpeech(
            { "AIzaTESTKEY0000000000000000" },
            { url, h -> seen += url to h; 200 to body },
            { url, h, _ -> seen += url to h; 200 to """{"audioContent":"SUQz"}""" },
        )
        assertEquals(listOf("Aoede", "Charon"), g.voices("en-GB").take(2).map { it.id })
        assertTrue(g.synthesize("Hello.", "Aoede", "chirp3hd").isNotEmpty())
        assertTrue(seen.all { (url, h) -> "AIza" !in url && h[GoogleSpeech.KEY_HEADER] == "AIzaTESTKEY0000000000000000" })
        // No key: not configured, nothing sent.
        assertFalse(GoogleSpeech({ null }, { _, _ -> error("sent") }, { _, _, _ -> error("sent") }).configured())
    }

    @Test
    fun callsPlayAChosenGoogleVoiceOverAPollyVoice() {
        val offered = listOf(SpeechCodec.Voice("Aoede", "Female", "chirp3hd", SpeechCodec.GOOGLE), SpeechCodec.Voice("Amy", "Female", "generative"))
        // The Polly voice stands behind the Google one: it says any line whose clip isn't ready.
        assertEquals(CallVoice("Amy", "generative", google = "Aoede"), CallVoice.choose("Aoede", offered))
        assertEquals(CallVoice("Amy", "generative"), CallVoice.choose("Amy", offered))
        assertEquals(CallVoice("Amy", "generative"), CallVoice.choose("Charon", offered)) // not offered: no Google voice
        assertEquals(CallVoice.DEFAULT, CallVoice.choose("Aoede-US", emptyList()))
    }

    @Test
    fun aCallLineInAGoogleVoiceIsOnGooglesMeterAndNeverPolly() {
        val google = FakePolly(chirp)
        val polly = FakePolly(gb)
        val googleUsage = InMemorySpeechUsageStore()
        val s = SpeechService(polly, InMemorySpeechUsageStore(), nowMs = { now }, google = google, googleUsage = googleUsage)
        assertEquals(SpeechCodec.Google.ON, s.googleState())
        assertContentEquals("ID3:Aoede:Goodbye.".toByteArray(), s.googleClip("Goodbye.", "Aoede"))
        assertEquals(8L, googleUsage.used("2026-10"))
        assertTrue(polly.said.isEmpty())
        // Not a Google voice here: nothing made, nothing counted, not a failure.
        assertEquals(null, s.googleClip("Goodbye.", "Amy"))
        assertEquals(null, s.googleClip("Goodbye.", "Puck"))
        assertEquals(null, s.googleFailedAtMs)
        // Past 900k: none (Polly says it on the call), still not a failure.
        googleUsage.add("2026-10", 899_990)
        assertEquals(null, s.googleClip("Goodbye.", "Aoede"))
        assertEquals(null, s.googleFailedAtMs)
        // Google fails: none, and the time is kept for Health and the picker.
        val failing = FakePolly(chirp)
        val f = SpeechService(polly, InMemorySpeechUsageStore(), nowMs = { now }, google = failing)
        f.offered()
        failing.fail = true
        assertEquals(null, f.googleClip("Goodbye.", "Aoede"))
        assertEquals(now, f.googleFailedAtMs)
        assertTrue(polly.said.isEmpty())
        // Health's state: none without a key, failed when Google can't be reached.
        val noKey = object : SpeechEngine by FakePolly(chirp) { override fun configured() = false }
        assertEquals(null, SpeechService(FakePolly(gb), InMemorySpeechUsageStore(), nowMs = { now }, google = noKey).googleState())
        assertEquals(null, SpeechService(FakePolly(gb), InMemorySpeechUsageStore(), nowMs = { now }).googleState())
        val down = FakePolly(chirp).apply { fail = true }
        assertEquals(SpeechCodec.Google.FAILED, SpeechService(FakePolly(gb), InMemorySpeechUsageStore(), nowMs = { now }, google = down).googleState())
    }

    @Test
    fun theDeployReportSaysWhetherGoogleSpeaksWithCountsOnly() {
        val google = FakePolly(chirp)
        val googleUsage = InMemorySpeechUsageStore()
        val s = SpeechService(FakePolly(gb), InMemorySpeechUsageStore(), nowMs = { now }, google = google, googleUsage = googleUsage)
        val line = s.report()
        assertTrue(line.startsWith("Google voice on · 3 Chirp 3 HD voices (2 British) · probe clip 1 KB in "), line)
        assertTrue(line.contains(" ms · 1k of 1M characters in 2026-10 · "), line)
        assertTrue(line.endsWith("Polly ${SpeechService(FakePolly(gb), InMemorySpeechUsageStore(), nowMs = { now }).offered().size} voices"), line)
        assertEquals(Triple(SpeechService.PROBE_LINE, "Charon", "chirp3hd"), google.said.single()) // the first British voice, Obi's
        assertEquals(SpeechService.PROBE_LINE.length.toLong(), googleUsage.used("2026-10"))
        // No key: says so, nothing sent to Google.
        val noKey = object : SpeechEngine by FakePolly(chirp) { override fun configured() = false }
        assertTrue(SpeechService(FakePolly(gb), InMemorySpeechUsageStore(), nowMs = { now }, google = noKey).report().startsWith("Google voice off · no key"))
        // Google refuses the clip: the report carries its reason words, never its message.
        val refusing = GoogleSpeech({ "k" + "x".repeat(30) }, { _, _ -> 200 to """{"voices":[{"name":"en-GB-Chirp3-HD-Aoede","ssmlGender":"FEMALE"}]}""" }) { _, _, _ ->
            403 to """{"error":{"code":403,"message":"Requests from project 123 are blocked.","status":"PERMISSION_DENIED","details":[{"reason":"API_KEY_SERVICE_BLOCKED"}]}}"""
        }
        val r = SpeechService(FakePolly(gb), InMemorySpeechUsageStore(), nowMs = { now }, google = refusing).report()
        assertTrue(r.contains("probe clip failed (IllegalStateException: synthesize 403 PERMISSION_DENIED API_KEY_SERVICE_BLOCKED)"), r)
        assertFalse(r.contains("123"), r)
        // The meter can't be read (its table missing): no clip, and the line says why.
        val broken = object : SpeechUsageStore {
            override fun add(month: String, chars: Long) = error("no table")
            override fun used(month: String): Long = error("no table")
        }
        val nb = SpeechService(FakePolly(gb), InMemorySpeechUsageStore(), nowMs = { now }, google = FakePolly(chirp), googleUsage = broken).report()
        assertTrue(nb.startsWith("Google voice: 3 Chirp 3 HD voices (2 British) · probe clip skipped · Google's meter can't be read (IllegalStateException)"), nb)
        // A key Google won't answer for: no voices listed.
        val down = FakePolly(chirp).apply { fail = true }
        assertTrue(SpeechService(FakePolly(gb), InMemorySpeechUsageStore(), nowMs = { now }, google = down).report().startsWith("Google voice: key read but no voices listed"))
    }

    @Test
    fun pollyIsSentSsmlAndAnEngineThatRefusesItGetsThePlainWordsFromThenOn() {
        // Talking to Obi like a conversation, slice 7: a small pause after a reaction, a touch quicker.
        val calls = mutableListOf<Pair<String, String>>()
        val plainOnly = mutableSetOf<String>()
        val ok = PollySpeech.sayMarkedUp("Oh nice. What sort?", "generative", plainOnly) { w, t -> calls += w to t; byteArrayOf(1) }
        assertEquals(1, ok.size)
        assertEquals("ssml", calls.single().second)
        assertTrue("<break time=\"250ms\"/>" in calls.single().first, calls.single().first)
        assertTrue("rate=\"106%\"" in calls.single().first)
        // Refused as SSML: said plainly at once, and that engine is asked plainly after.
        calls.clear()
        val refusing: (String, String) -> ByteArray = { w, t -> calls += w to t; if (t == "ssml") error("InvalidSsml") else byteArrayOf(2) }
        assertEquals(2, PollySpeech.sayMarkedUp("Ha, fair. Good call.", "neural", plainOnly, refusing)[0].toInt())
        assertEquals(listOf("ssml", "text"), calls.map { it.second })
        assertEquals("Ha, fair. Good call.", calls.last().first)
        assertEquals(setOf("neural"), plainOnly)
        calls.clear()
        PollySpeech.sayMarkedUp("Right.", "neural", plainOnly, refusing)
        assertEquals(listOf("text"), calls.map { it.second })
        // A plain failure is still a failure, and doesn't mark the engine.
        assertFailsWith<IllegalStateException> { PollySpeech.sayMarkedUp("Hi.", "standard", plainOnly) { _, _ -> error("down") } }
        assertFalse("standard" in plainOnly)
    }
}
