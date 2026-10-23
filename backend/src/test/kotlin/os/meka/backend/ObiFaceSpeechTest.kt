package os.meka.backend

import io.ktor.client.request.post
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.SpeechCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Obi's face, slice 2: each clip can bring its mouth sounds (Polly's viseme speech marks), only when asked. */
class ObiFaceSpeechTest {
    private class FakePolly(
        var marks: List<SpeechCodec.Mark> = listOf(SpeechCodec.Mark(0, "sil"), SpeechCodec.Mark(40, "p"), SpeechCodec.Mark(120, "a"), SpeechCodec.Mark(400, "sil")),
        var clip: (String) -> ByteArray = { "ID3:$it".toByteArray() },
    ) : SpeechEngine {
        val marked = mutableListOf<Triple<String, String, String>>()
        var marksFail = false
        override fun voices(language: String) = listOf(
            SpeechVoice("Brian", "Male", setOf("standard", "neural", "generative")),
            SpeechVoice("Emma", "Female", setOf("standard", "neural")),
        )
        override fun synthesize(text: String, voice: String, engine: String): ByteArray = clip(text)
        override fun visemes(text: String, voice: String, engine: String): List<SpeechCodec.Mark> {
            marked += Triple(text, voice, engine)
            if (marksFail) error("no marks")
            return marks
        }
    }

    private val now = 1_791_504_000_000L // 2026-10-09

    /** [frames] MPEG-2 Layer III frames at 24 kHz, 48 kbit/s (Polly's MP3): 144 bytes and 24 ms each. */
    private fun mp3(frames: Int): ByteArray {
        val frame = ByteArray(144).also { it[0] = 0xFF.toByte(); it[1] = 0xF3.toByte(); it[2] = 0x64; it[3] = 0xC4.toByte() }
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 5, 1, 2, 3, 4, 5)
        return id3 + (0 until frames).fold(ByteArray(0)) { acc, _ -> acc + frame }
    }

    @Test
    fun marksComeOnlyWhenAskedAndAreCountedLikeAClip() {
        val polly = FakePolly()
        val usage = InMemorySpeechUsageStore()
        val s = SpeechService(polly, usage, nowMs = { now })
        val plain = s.say(SpeechCodec.Request("Right, done.", "Emma"))
        assertNull(plain.visemes)
        assertTrue(polly.marked.isEmpty())
        assertEquals(12L, usage.used("2026-10"))
        val marked = s.say(SpeechCodec.Request("Right, done.", "Emma", visemes = true))
        assertEquals("0:sil 40:p 120:a 400:sil", marked.visemes)
        assertEquals(listOf(Triple("Right, done.", "Emma", "neural")), polly.marked)
        // The clip and its marks are each billed by the character.
        assertEquals(36L, usage.used("2026-10"))
        // The JSON answer carries them too.
        assertEquals("0:sil 40:p 120:a 400:sil", s.speak(SpeechCodec.Request("Right, done.", "Emma", visemes = true)).visemes)
    }

    @Test
    fun noMarksNeverStopTheClip() {
        val polly = FakePolly().apply { marksFail = true }
        val usage = InMemorySpeechUsageStore()
        val s = SpeechService(polly, usage, nowMs = { now })
        val said = s.say(SpeechCodec.Request("Hello.", "Emma", visemes = true))
        assertEquals(SpeechCodec.Response.SPOKEN, said.response.state)
        assertNull(said.visemes)
        assertEquals(6L, usage.used("2026-10")) // failed marks aren't counted
        polly.marksFail = false
        polly.marks = emptyList()
        assertNull(s.say(SpeechCodec.Request("Hello.", "Emma", visemes = true)).visemes)
        // Near the month's cap the marks are left out first; the clip is still said.
        val tight = SpeechService(FakePolly(), InMemorySpeechUsageStore(), capChars = 8, nowMs = { now })
        val near = tight.say(SpeechCodec.Request("Hiya.", "Emma", visemes = true))
        assertEquals(SpeechCodec.Response.SPOKEN, near.response.state)
        assertNull(near.visemes)
    }

    @Test
    fun aGenerativeClipBorrowsTheNeuralMarksFittedToItsLength() {
        // 100 frames = 2400 ms; the neural marks end at 400 ms, so they stretch to end 60 ms before the clip does.
        val polly = FakePolly(clip = { mp3(100) })
        val s = SpeechService(polly, InMemorySpeechUsageStore(), nowMs = { now })
        val said = s.say(SpeechCodec.Request("Right, done.", "Brian", visemes = true))
        assertEquals("generative", said.response.engine)
        assertEquals(listOf(0L, 234L, 702L, 2340L), SpeechCodec.decodeMarks(said.visemes).map { it.atMs })
        assertEquals("generative", polly.marked.single().third) // the engine picks neural for the marks itself
    }

    @Test
    fun mp3LengthCountsFramesAndRefusesWhatIsntMp3() {
        assertEquals(2400L, Mp3Length.ms(mp3(100)))
        assertEquals(24L, Mp3Length.ms(mp3(1)))
        assertNull(Mp3Length.ms("ID3:not audio".toByteArray()))
        assertNull(Mp3Length.ms(ByteArray(0)))
    }

    @Test
    fun pollysMarksAreReadLineByLineAndOnlyVisemes() {
        val lines = """
            {"time":0,"type":"viseme","value":"sil"}
            {"time":6,"type":"word","start":0,"end":5,"value":"Right"}
            {"time":62,"type":"viseme","value":"r"}
            not json
            {"time":-3,"type":"viseme","value":"p"}
            {"time":130,"type":"viseme","value":"<script>"}
            {"time":"150","type":"viseme","value":"a"}
            {"time":180,"type":"viseme","value":"a"}
        """.trimIndent()
        assertEquals(
            listOf(SpeechCodec.Mark(0, "sil"), SpeechCodec.Mark(62, "r"), SpeechCodec.Mark(180, "a")),
            PollySpeech.parseMarks(lines),
        )
        assertEquals(emptyList<SpeechCodec.Mark>(), PollySpeech.parseMarks(""))
    }

    @Test
    fun fittingLeavesMarksAloneWhenItCant() {
        val m = listOf(SpeechCodec.Mark(0, "sil"))
        assertEquals(m, SpeechService.fitMarks(m, 2000))
        val two = listOf(SpeechCodec.Mark(0, "p"), SpeechCodec.Mark(100, "sil"))
        assertEquals(two, SpeechService.fitMarks(two, 50))
        assertEquals(listOf(0L, 940L), SpeechService.fitMarks(two, 1000).map { it.atMs })
    }

    @Test
    fun theRouteSendsTheMarksBesideTheRawClip() = testApplication {
        val devices = InMemoryDeviceRegistry()
        val foldSecret = devices.enrol("hh", "fold")
        val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
        application { mekaSync(InMemoryServerOpStore(), devices, speech = SpeechService(FakePolly(), InMemorySpeechUsageStore(), nowMs = { now })) }
        val body = SpeechCodec.encodeRequest(SpeechCodec.Request("Right, done.", "Emma", binary = true, visemes = true))
        val ok = client.post("/v1/speech/speak") { with(foldKey) { signed(foldSecret, "/v1/speech/speak", body) } }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("0:sil 40:p 120:a 400:sil", ok.headers[SpeechCodec.HEADER_VISEMES])
        assertContentEquals("ID3:Right, done.".toByteArray(), ok.readRawBytes())
        val plain = SpeechCodec.encodeRequest(SpeechCodec.Request("Right, done.", "Emma", binary = true))
        val p = client.post("/v1/speech/speak") { with(foldKey) { signed(foldSecret, "/v1/speech/speak", plain) } }
        assertNull(p.headers[SpeechCodec.HEADER_VISEMES])
    }
}
