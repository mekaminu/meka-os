package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MEKA's voice on the wire (build plan V1, Weather and a voice, item 3: Polly). */
class SpeechCodecTest {

    @Test
    fun aRequestRoundTripsAndLimitsAreRefusedNotTrimmed() {
        val r = SpeechCodec.Request("Good morning, Meka. You've got three things today and it's 14 degrees.", "Amy")
        assertEquals(r, SpeechCodec.decodeRequest(SpeechCodec.encodeRequest(r)))
        val noVoice = SpeechCodec.Request("Moved it to tomorrow at 09:00.")
        assertEquals(noVoice, SpeechCodec.decodeRequest(SpeechCodec.encodeRequest(noVoice)))
        assertTrue("voice" !in SpeechCodec.encodeRequest(noVoice))
        // Surrounding spaces go; the text itself is kept as written.
        assertEquals("Hello.", SpeechCodec.decodeRequest(SpeechCodec.encodeRequest(SpeechCodec.Request("  Hello.  "))).text)
        fun bad(x: SpeechCodec.Request) = assertFailsWith<WireFormatException> { SpeechCodec.decodeRequest(SpeechCodec.encodeRequest(x)) }
        bad(SpeechCodec.Request("   "))
        bad(SpeechCodec.Request("a".repeat(SpeechCodec.MAX_TEXT + 1)))
        bad(SpeechCodec.Request("bell\u0007"))
        bad(SpeechCodec.Request("Hi", "Polly.Amy-Generative"))
        bad(SpeechCodec.Request("Hi", "A"))
        // A line break inside a reply is fine.
        assertEquals("One.\nTwo.", SpeechCodec.decodeRequest(SpeechCodec.encodeRequest(SpeechCodec.Request("One.\nTwo."))).text)
        assertFailsWith<WireFormatException> { SpeechCodec.decodeRequest("""{"w":2,"text":"Hi"}""") }
        assertFailsWith<WireFormatException> { SpeechCodec.decodeRequest("""{"w":1}""") }
        assertFailsWith<WireFormatException> { SpeechCodec.decodeRequest("not json") }
    }

    @Test
    fun responsesAndTheVoicesListRoundTrip() {
        val spoken = SpeechCodec.Response(SpeechCodec.Response.SPOKEN, "SUQzBAAAAAAA", "mp3", "Amy", "generative")
        assertEquals(spoken, SpeechCodec.decodeResponse(SpeechCodec.encodeResponse(spoken)))
        val over = SpeechCodec.Response(SpeechCodec.Response.OVER, reason = "This month's voice is used up; MEKA's own voice speaks until the 1st")
        assertEquals(over, SpeechCodec.decodeResponse(SpeechCodec.encodeResponse(over)))
        assertNull(SpeechCodec.decodeResponse(SpeechCodec.encodeResponse(over)).audio)

        val voices = SpeechCodec.Voices(
            SpeechCodec.Voices.ON,
            listOf(SpeechCodec.Voice("Amy", "Female", "generative"), SpeechCodec.Voice("Brian", "Male", "neural")),
            defaultVoice = "Amy", month = "2026-10", usedChars = 4_210, capChars = 1_000_000,
        )
        assertEquals(voices, SpeechCodec.decodeVoices(SpeechCodec.encodeVoices(voices)))
        val off = SpeechCodec.Voices(SpeechCodec.Voices.OFF, reason = "MEKA's voice isn't set up on this server")
        assertEquals(off, SpeechCodec.decodeVoices(SpeechCodec.encodeVoices(off)))
        // A voice that isn't a plain name is dropped rather than shown.
        val odd = """{"w":1,"state":"on","voices":[{"id":"Amy","gender":"Female","engine":"neural"},{"id":"<b>","gender":"","engine":""}],"usedChars":0,"capChars":1}"""
        assertEquals(listOf("Amy"), SpeechCodec.decodeVoices(odd).voices.map { it.id })
        SpeechCodec.decodeVoicesRequest(SpeechCodec.encodeVoicesRequest())
        assertFailsWith<WireFormatException> { SpeechCodec.decodeVoicesRequest("{}") }
    }
}
