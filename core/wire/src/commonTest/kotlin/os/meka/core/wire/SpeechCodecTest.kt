package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        val over = SpeechCodec.Response(SpeechCodec.Response.OVER, reason = "This month's voice is used up; Navilife's own voice speaks until the 1st")
        assertEquals(over, SpeechCodec.decodeResponse(SpeechCodec.encodeResponse(over)))
        assertNull(SpeechCodec.decodeResponse(SpeechCodec.encodeResponse(over)).audio)

        val voices = SpeechCodec.Voices(
            SpeechCodec.Voices.ON,
            listOf(SpeechCodec.Voice("Amy", "Female", "generative"), SpeechCodec.Voice("Brian", "Male", "neural")),
            defaultVoice = "Amy", month = "2026-10", usedChars = 4_210, capChars = 1_000_000,
        )
        assertEquals(voices, SpeechCodec.decodeVoices(SpeechCodec.encodeVoices(voices)))
        val off = SpeechCodec.Voices(SpeechCodec.Voices.OFF, reason = "Navilife's voice isn't set up on this server")
        assertEquals(off, SpeechCodec.decodeVoices(SpeechCodec.encodeVoices(off)))
        // A voice that isn't a plain name is dropped rather than shown.
        val odd = """{"w":1,"state":"on","voices":[{"id":"Amy","gender":"Female","engine":"neural"},{"id":"<b>","gender":"","engine":""}],"usedChars":0,"capChars":1}"""
        assertEquals(listOf("Amy"), SpeechCodec.decodeVoices(odd).voices.map { it.id })
        SpeechCodec.decodeVoicesRequest(SpeechCodec.encodeVoicesRequest())
        assertFailsWith<WireFormatException> { SpeechCodec.decodeVoicesRequest("{}") }
    }

    @Test
    fun aDeviceCanAskForRawMp3AndAnOlderRequestStaysJson() {
        val r = SpeechCodec.Request("One moment…", "Amy", binary = true)
        assertTrue("\"as\":\"mp3\"" in SpeechCodec.encodeRequest(r))
        assertEquals(r, SpeechCodec.decodeRequest(SpeechCodec.encodeRequest(r)))
        // A request without the flag (an older device) gets JSON as before; an unknown "as" is ignored, not refused.
        assertEquals(false, SpeechCodec.decodeRequest(SpeechCodec.encodeRequest(SpeechCodec.Request("Hi"))).binary)
        assertEquals(false, SpeechCodec.decodeRequest("""{"w":1,"text":"Hi","as":"wav"}""").binary)
    }

    @Test
    fun aRawClipBecomesTheSameAnswerAndAnUnusableOneFails() {
        val ok = SpeechCodec.binaryResponse("SUQzBAAAAAAA", "Amy", "generative")
        assertEquals(SpeechCodec.Response(SpeechCodec.Response.SPOKEN, "SUQzBAAAAAAA", "mp3", "Amy", "generative"), ok)
        // Headers that aren't plain names are dropped, never trusted; the clip still plays.
        val odd = SpeechCodec.binaryResponse("SUQz", "Amy<script>", "gen-2")
        assertEquals(SpeechCodec.Response.SPOKEN, odd.state)
        assertNull(odd.voice)
        assertNull(odd.engine)
        assertEquals(SpeechCodec.Response.FAILED, SpeechCodec.binaryResponse("", "Amy", "neural").state)
        assertEquals(SpeechCodec.Response.FAILED, SpeechCodec.binaryResponse("A".repeat(SpeechCodec.MAX_AUDIO_B64 + 1), "Amy", "neural").state)
        assertTrue(SpeechCodec.MAX_AUDIO_BYTES * 4 / 3 <= SpeechCodec.MAX_AUDIO_B64)
    }

    @Test
    fun googleVoicesAndTheirMonthTravelAndAnOlderAnswerHasNone() {
        val voices = SpeechCodec.Voices(
            SpeechCodec.Voices.ON,
            listOf(
                SpeechCodec.Voice("Aoede", "Female", "chirp3hd", SpeechCodec.GOOGLE),
                SpeechCodec.Voice("Charon-US", "Male", "chirp3hd", SpeechCodec.GOOGLE),
                SpeechCodec.Voice("Amy", "Female", "generative"),
            ),
            defaultVoice = "Amy", month = "2026-10", usedChars = 10, capChars = 1_000_000,
            google = SpeechCodec.Google(SpeechCodec.Google.ON, 84_000, 1_000_000, 900_000, 1_791_504_000_000L),
        )
        assertEquals(voices, SpeechCodec.decodeVoices(SpeechCodec.encodeVoices(voices)))
        val noKey = voices.copy(google = SpeechCodec.Google(SpeechCodec.Google.NO_KEY))
        assertEquals(noKey, SpeechCodec.decodeVoices(SpeechCodec.encodeVoices(noKey)))
        // An older server: no provider (Polly) and no google block.
        val old = """{"w":1,"state":"on","voices":[{"id":"Amy","gender":"Female","engine":"neural"}],"usedChars":0,"capChars":1}"""
        val decoded = SpeechCodec.decodeVoices(old)
        assertEquals(SpeechCodec.POLLY, decoded.voices.single().provider)
        assertNull(decoded.google)
        // A provider this app doesn't know is left out; an American Google voice is asked for by its id.
        val other = """{"w":1,"state":"on","voices":[{"id":"Amy","gender":"","engine":"neural","provider":"acme"}],"usedChars":0,"capChars":1}"""
        assertTrue(SpeechCodec.decodeVoices(other).voices.isEmpty())
        assertEquals("Charon-US", SpeechCodec.decodeRequest(SpeechCodec.encodeRequest(SpeechCodec.Request("Hi", "Charon-US"))).voice)
        assertFailsWith<WireFormatException> { SpeechCodec.decodeRequest("""{"w":1,"text":"Hi","voice":"Charon-FR"}""") }
        // A Google clip's engine comes through the header.
        assertEquals("chirp3hd", SpeechCodec.binaryResponse("SUQz", "Aoede", "chirp3hd").engine)
    }

    @Test
    fun aClipsMouthSoundsTravelCompactAndAreNeverHalfTrusted() {
        // Obi's face, slice 2.
        val r = SpeechCodec.Request("Right, done.", "Brian", binary = true, visemes = true)
        assertEquals(r, SpeechCodec.decodeRequest(SpeechCodec.encodeRequest(r)))
        assertFalse("visemes" in SpeechCodec.encodeRequest(r.copy(visemes = false)))
        val marks = listOf(SpeechCodec.Mark(125, "a"), SpeechCodec.Mark(0, "sil"), SpeechCodec.Mark(62, "p"), SpeechCodec.Mark(-5, "p"), SpeechCodec.Mark(80, "zz"))
        val text = SpeechCodec.encodeMarks(marks)
        assertEquals("0:sil 62:p 125:a", text)
        assertEquals(listOf(SpeechCodec.Mark(0, "sil"), SpeechCodec.Mark(62, "p"), SpeechCodec.Mark(125, "a")), SpeechCodec.decodeMarks(text))
        assertNull(SpeechCodec.encodeMarks(emptyList()))
        // Out of order, unknown, malformed or too long: none at all.
        for (bad in listOf("62:p 0:a", "0:p 5:Q", "0p", ":p", "x:p", "0:p  5:a", "1".repeat(9) + ":p", "0:p ".repeat(SpeechCodec.MAX_MARKS + 1).trim())) {
            assertTrue(SpeechCodec.decodeMarks(bad).isEmpty(), bad)
        }
        // A long piece is cut at the limits, still in order and readable.
        val many = (0 until 2_000).map { SpeechCodec.Mark(it * 50L, if (it % 2 == 0) "p" else "a") }
        val cut = SpeechCodec.encodeMarks(many)!!
        assertTrue(cut.length <= SpeechCodec.MAX_MARKS_CHARS)
        assertTrue(SpeechCodec.decodeMarks(cut).size in 1..SpeechCodec.MAX_MARKS)
        // In JSON and through the header; a bad header is dropped and the clip kept.
        val spoken = SpeechCodec.Response(SpeechCodec.Response.SPOKEN, "SUQz", "mp3", "Brian", "neural", visemes = text)
        assertEquals(spoken, SpeechCodec.decodeResponse(SpeechCodec.encodeResponse(spoken)))
        assertEquals(text, SpeechCodec.binaryResponse("SUQz", "Brian", "neural", text).visemes)
        val bad = SpeechCodec.binaryResponse("SUQz", "Brian", "neural", "0:p\r\nX-Evil: 1")
        assertNull(bad.visemes)
        assertEquals(SpeechCodec.Response.SPOKEN, bad.state)
    }
}
