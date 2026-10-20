package os.meka.backend

import os.meka.core.domain.TalkWarmRules
import os.meka.core.wire.TalkWarmCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The orb speed pass, slice 5: "he's about to talk" opens the server's connections to the AI and to Navilife's voice
 * with free requests (no answer written, nothing said, nothing counted), at most every 15 s.
 */
class TalkWarmTest {
    private var now = 1_791_504_000_000L
    private val goodKey = "sk-ant-api03-" + "a".repeat(40)

    private class Engine(private val answers: Boolean = true) : SpeechEngine {
        var touched = 0
        var said = 0
        override fun voices(language: String) = listOf(
            SpeechVoice("Amy", "Female", setOf("neural", "generative")),
            SpeechVoice("Aoede", "Female", setOf(GoogleSpeech.ENGINE)),
        )
        override fun synthesize(text: String, voice: String, engine: String): ByteArray { said++; return "ID3".toByteArray() }
        override fun touch(): Boolean { touched++; if (!answers) error("down"); return true }
    }

    @Test
    fun aRequestWarmsBothOnce() {
        var ai = 0
        var voice = 0
        val w = TalkWarm({ ai++; true }, { voice++; true }, nowMs = { now }, start = { it() })
        assertEquals(TalkWarmCodec.Response.WARMING, w.request().state)
        assertEquals(1, ai)
        assertEquals(1, voice)
        now += TalkWarmRules.SERVER_GAP_MS - 1
        assertEquals(TalkWarmCodec.Response.RECENT, w.request().state)
        assertEquals(1, ai)
        now += 1
        assertEquals(TalkWarmCodec.Response.WARMING, w.request().state)
        assertEquals(2, ai)
        assertEquals(2, voice)
    }

    @Test
    fun nothingToWarmIsOff() {
        assertEquals(TalkWarmCodec.Response.OFF, TalkWarm(null, null, nowMs = { now }, start = { it() }).request().state)
    }

    @Test
    fun aFailureNeverReachesTheDevice() {
        var voice = 0
        val w = TalkWarm({ error("down") }, { voice++; true }, nowMs = { now }, start = { it() })
        assertEquals(TalkWarmCodec.Response.WARMING, w.request().state)
        assertEquals(1, voice)
        val unstartable = TalkWarm({ true }, null, nowMs = { now }, start = { error("no threads") })
        assertEquals(TalkWarmCodec.Response.WARMING, unstartable.request().state)
    }

    @Test
    fun theVoiceWarmsGoogleWhenItHasAKeyElsePolly() {
        val polly = Engine()
        val google = Engine()
        val s = SpeechService(polly, InMemorySpeechUsageStore(), nowMs = { now }, google = google)
        assertTrue(s.touch())
        assertEquals(1, google.touched)
        assertEquals(0, polly.touched)
        assertEquals(0, google.said + polly.said) // nothing said, nothing counted

        val noKey = object : SpeechEngine by Engine() { override fun configured() = false }
        val p2 = Engine()
        assertTrue(SpeechService(p2, InMemorySpeechUsageStore(), nowMs = { now }, google = noKey).touch())
        assertEquals(1, p2.touched)
    }

    @Test
    fun aVoiceThatCantBeReachedIsFalseNotAnError() {
        assertFalse(SpeechService(Engine(answers = false), InMemorySpeechUsageStore(), nowMs = { now }).touch())
        assertFalse(SpeechService(null, InMemorySpeechUsageStore(), nowMs = { now }).touch())
    }

    @Test
    fun googlesWarmUpIsTheBritishVoiceListWithTheKeyInAHeader() {
        val gets = mutableListOf<Pair<String, Map<String, String>>>()
        val g = GoogleSpeech({ "gkey" }, { url, h -> gets += url to h; 200 to "{}" }, { _, _, _ -> error("nothing is said") })
        assertTrue(g.touch())
        assertEquals("${GoogleSpeech.BASE}/voices?languageCode=en-GB", gets.single().first)
        assertEquals("gkey", gets.single().second[GoogleSpeech.KEY_HEADER])
        assertFalse(GoogleSpeech({ null }, { _, _ -> error("not sent") }, { _, _, _ -> error("not sent") }).touch())
    }

    @Test
    fun theAisWarmUpIsOneFreeModelListAndCostsNothing() {
        val calls = mutableListOf<Pair<String, String>>()
        val http = HttpExchange { method, url, _, _ -> calls += method to url; 200 to """{"data":[{"id":"claude-haiku-9"}]}""" }
        val store = InMemoryAiUsageStore()
        val budget = AiBudget(20_000_000, store, nowMs = { now })
        val p = AnthropicProvider({ goodKey }, budget, http, ModelCatalog({ goodKey }, http, nowMs = { now }))
        assertTrue(p.touch())
        assertEquals(listOf("GET" to AnthropicProvider.WARM_URL), calls)
        assertTrue(store.month("2026-10").isEmpty())
        assertFalse(AnthropicProvider({ null }, budget, http).touch())
        assertEquals(1, calls.size)
        val down = AnthropicProvider({ goodKey }, budget, HttpExchange { _, _, _, _ -> throw java.io.IOException("down") })
        assertFalse(down.touch())
    }
}
