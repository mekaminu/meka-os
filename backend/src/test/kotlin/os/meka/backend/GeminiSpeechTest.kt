package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.core.domain.LiveModeRules
import os.meka.core.wire.SpeechCodec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One voice for Obi everywhere (2026-11-13): the read-outs in Live's voice through Gemini, Polly standing in. */
class GeminiSpeechTest {
    private class FakePolly : SpeechEngine {
        val said = mutableListOf<Pair<String, String>>()
        override fun voices(language: String) = listOf(SpeechVoice("Brian", "Male", setOf("neural")), SpeechVoice("Amy", "Female", setOf("generative")))
        override fun synthesize(text: String, voice: String, engine: String): ByteArray {
            said += text to voice
            return "ID3:$voice".toByteArray()
        }
    }

    private fun answer(pcm: ByteArray, mime: String = "audio/L16;codec=pcm;rate=24000", usage: String = ""","usageMetadata":{"promptTokenCount":20,"candidatesTokenCount":50}""") =
        """{"candidates":[{"content":{"role":"model","parts":[{"inlineData":{"mimeType":"$mime","data":"${Base64.getEncoder().encodeToString(pcm)}"}}]}}]$usage}"""

    private class Google(var status: Int = 200, var body: String = "", var throws: Throwable? = null) : HttpExchange {
        val sent = mutableListOf<Triple<String, Map<String, String>, String?>>()
        override fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
            sent += Triple(url, headers, body)
            throws?.let { throw it }
            return status to this.body
        }
    }

    private var now = 1_791_504_000_000L // 2026-10-09 00:00 UTC
    private val pcm = ByteArray(24_000 * 2) { (it % 7).toByte() } // one second

    @Test
    fun askedForObisLiveVoiceGeminiSaysItInBritishEnglishAndTheBudgetCountsIt() {
        val google = Google(body = answer(pcm))
        val gemini = GeminiSpeech({ "k-123" }, google)
        val store = InMemoryAiUsageStore()
        val polly = FakePolly()
        val s = SpeechService(polly, InMemorySpeechUsageStore(), nowMs = { now }, budget = AiBudget(20_000_000L, store, nowMs = { now }), gemini = gemini)
        val said = s.say(SpeechCodec.Request("Good morning, Meka.", LiveModeRules.DEFAULT_VOICE, binary = true))
        assertEquals(SpeechCodec.Response.SPOKEN, said.response.state)
        assertEquals("Charon", said.response.voice)
        assertEquals(GeminiSpeech.ENGINE, said.response.engine)
        assertEquals(SpeechService.WAV, said.response.format)
        assertEquals(1000L, WavClip.ms(said.mp3!!))
        assertTrue(polly.said.isEmpty())
        // The request: the model, the key in a header (never the URL), Live's voice, the British style, then the words.
        val (url, headers, body) = google.sent.single()
        assertEquals("${GeminiSpeech.BASE}/models/${GeminiSpeech.DEFAULT_MODEL}:generateContent", url)
        assertEquals("k-123", headers["x-goog-api-key"])
        assertFalse(url.contains("k-123"))
        val o = Json.parseToJsonElement(body!!).jsonObject
        val text = o["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.startsWith(GeminiSpeech.STYLE) && text.endsWith("Good morning, Meka."), text)
        assertTrue(GeminiSpeech.STYLE.contains("British English"))
        val cfg = o["generationConfig"]!!.jsonObject
        assertEquals("AUDIO", cfg["responseModalities"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("Charon", cfg["speechConfig"]!!.jsonObject["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content)
        // Counted as Voice at list price: 20 tokens in at $0.50/M (10 µ$) + 50 out at $10/M (500 µ$).
        val row = store.month("2026-10").single()
        assertEquals(SpeechService.GEMINI_FEATURE, row.feature)
        assertEquals(510L, row.microUsd)
    }

    @Test
    fun aVoiceThatIsntLivesOrNoKeyStaysWithPolly() {
        val google = Google(body = answer(pcm))
        val polly = FakePolly()
        val s = SpeechService(polly, InMemorySpeechUsageStore(), nowMs = { now }, gemini = GeminiSpeech({ "k" }, google))
        assertEquals("Amy", s.speak(SpeechCodec.Request("Hi.", "Amy")).voice)
        assertEquals("Brian", s.speak(SpeechCodec.Request("Hi.")).voice)
        assertTrue(google.sent.isEmpty())
        val noKey = SpeechService(polly, InMemorySpeechUsageStore(), nowMs = { now }, gemini = GeminiSpeech({ null }, google))
        assertEquals("Brian", noKey.speak(SpeechCodec.Request("Hi.", "Charon")).voice)
        assertTrue(google.sent.isEmpty())
        // Without Gemini at all, Live's voice is Polly's default as before.
        assertEquals("Brian", SpeechService(polly, InMemorySpeechUsageStore(), nowMs = { now }).speak(SpeechCodec.Request("Hi.", "Charon")).voice)
    }

    @Test
    fun aFailureIsSaidOnceByPollyAndRestsGeminiAWhile() {
        val google = Google(status = 429, body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}""")
        val polly = FakePolly()
        val s = SpeechService(polly, InMemorySpeechUsageStore(), nowMs = { now }, gemini = GeminiSpeech({ "k" }, google))
        val r = s.speak(SpeechCodec.Request("Ten past seven.", "Charon"))
        assertEquals(SpeechCodec.Response.SPOKEN, r.state)
        assertEquals("Brian", r.voice)
        assertEquals(SpeechCodec.WHY_FAILED, r.why, "the device is told why Polly said it")
        assertEquals(listOf("Ten past seven." to "Brian"), polly.said)
        assertNotNull(s.geminiFailedAtMs)
        assertEquals("refused (429 RESOURCE_EXHAUSTED)", s.geminiFailedWhy)
        assertTrue(s.geminiSwitched())
        // Resting: Polly at once, Google not asked.
        google.status = 200; google.body = answer(pcm)
        val again = s.speak(SpeechCodec.Request("Again.", "Charon"))
        assertEquals("Brian", again.voice)
        assertEquals(SpeechCodec.WHY_RESTING, again.why)
        assertEquals(1, google.sent.size)
        // After the hold, Gemini again.
        now += 16 * 60_000L
        val later = s.speak(SpeechCodec.Request("Later.", "Charon"))
        assertEquals("Charon", later.voice)
        assertNull(later.why)
        // A voice that isn't Live's was never Gemini's: no reason to give.
        assertNull(s.speak(SpeechCodec.Request("Plain.", "Brian")).why)
    }

    @Test
    fun oneTimeoutTriesAgainTwoInARowRest() {
        val google = Google(throws = java.net.http.HttpTimeoutException("slow"))
        val s = SpeechService(FakePolly(), InMemorySpeechUsageStore(), nowMs = { now }, gemini = GeminiSpeech({ "k" }, google))
        val one = s.speak(SpeechCodec.Request("One.", "Charon"))
        assertEquals("Brian", one.voice)
        assertEquals(SpeechCodec.WHY_SLOW, one.why)
        assertEquals("too slow", s.geminiFailedWhy)
        assertFalse(s.geminiSwitched())
        s.speak(SpeechCodec.Request("Two.", "Charon"))
        assertTrue(s.geminiSwitched())
    }

    @Test
    fun aSpentBudgetLeavesGeminiAlone() {
        val google = Google(body = answer(pcm))
        val store = InMemoryAiUsageStore()
        val budget = AiBudget(100L, store, nowMs = { now })
        budget.recordCost("ask", 200L)
        val s = SpeechService(FakePolly(), InMemorySpeechUsageStore(), nowMs = { now }, budget = budget, gemini = GeminiSpeech({ "k" }, google))
        assertEquals(SpeechCodec.Response.OVER, s.speak(SpeechCodec.Request("Hi.", "Charon")).state)
        assertTrue(google.sent.isEmpty())
    }

    @Test
    fun googlesAnswerIsReadLeniently() {
        val (bytes, rate) = GeminiSpeech.parseAudio(answer(pcm, "audio/L16;codec=pcm;rate=16000"))!!
        assertEquals(pcm.size, bytes.size)
        assertEquals(16_000, rate)
        assertEquals(24_000, GeminiSpeech.parseAudio(answer(pcm, "audio/pcm"))!!.second)
        assertNull(GeminiSpeech.parseAudio(answer(pcm, "audio/mpeg")))
        assertNull(GeminiSpeech.parseAudio("""{"candidates":[]}"""))
        assertNull(GeminiSpeech.parseAudio("not json"))
        assertEquals(20L to 50L, GeminiSpeech.parseTokens(answer(pcm)))
        assertNull(GeminiSpeech.parseTokens(answer(pcm, usage = "")))
        // No usage: estimated, rounded up (one second = 25 audio tokens).
        assertEquals(25L, GeminiSpeech.estimateTokens("Hi.", pcm.size, 24_000).second)
        assertEquals("Charon", GeminiSpeech.liveVoice(" charon "))
        assertNull(GeminiSpeech.liveVoice("Brian"))
        assertEquals(1L, GeminiSpeech.microUsd(0L to 0L))
    }

    @Test
    fun clipsFitTheDevicesLimit16BitThenMuLawThenNone() {
        val short = WavClip.fit(pcm, 24_000)!!
        assertEquals("RIFF", String(short, 0, 4))
        assertEquals(44 + pcm.size, short.size)
        assertEquals(1, short[20].toInt()) // PCM
        // 30 s: too big as 16-bit (1.44 MB), fits as μ-law (720 KB).
        val long = ByteArray(24_000 * 2 * 30)
        val mu = WavClip.fit(long, 24_000)!!
        assertTrue(mu.size <= SpeechCodec.MAX_AUDIO_BYTES)
        assertEquals(7, mu[20].toInt()) // μ-law
        assertEquals(30_000L, WavClip.ms(mu))
        // A minute: nothing (Polly says it).
        assertNull(WavClip.fit(ByteArray(24_000 * 2 * 60), 24_000))
        assertNull(WavClip.fit(ByteArray(1), 24_000))
        assertNull(WavClip.ms("ID3 not a wav at all, long enough to read a header".toByteArray()))
        // G.711's known points: silence 0xFF, full scale 0x80 / 0x00.
        assertEquals(0xFF, WavClip.mulawByte(0))
        assertEquals(0x80, WavClip.mulawByte(32_767))
        assertEquals(0x00, WavClip.mulawByte(-32_768))
    }

    @Test
    fun theReportProbesObisVoice() {
        val google = Google(body = answer(pcm))
        val s = SpeechService(FakePolly(), InMemorySpeechUsageStore(), nowMs = { now }, gemini = GeminiSpeech({ "k" }, google))
        val line = s.geminiReport()
        assertTrue(line.startsWith(" · Gemini voice (${GeminiSpeech.DEFAULT_MODEL}) on · probe clip in Charon 47 KB, 1000 ms long"), line)
        assertTrue(s.report().endsWith(line.substringBefore(", in ")) || s.report().contains("Gemini voice"))
        google.status = 403; google.body = """{"error":{"status":"PERMISSION_DENIED"}}"""
        assertEquals(" · Gemini voice (${GeminiSpeech.DEFAULT_MODEL}): probe clip failed (refused (403 PERMISSION_DENIED))", s.geminiReport())
        assertEquals("", SpeechService(FakePolly(), InMemorySpeechUsageStore(), nowMs = { now }).geminiReport())
        assertEquals(" · Gemini voice off · no key", SpeechService(FakePolly(), InMemorySpeechUsageStore(), nowMs = { now }, gemini = GeminiSpeech({ null }, google)).geminiReport())
    }
}
