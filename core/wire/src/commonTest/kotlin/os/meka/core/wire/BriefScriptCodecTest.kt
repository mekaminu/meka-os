package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** "A morning brief worth listening to", slice 2: the brief's facts out, the spoken text back. */
class BriefScriptCodecTest {
    private val request = BriefScriptCodec.Request("2026-10-12", "Monday 12 October 2026 · 07:30", false,
        listOf("Good morning, Meka.", "It's Monday 12 October, an office day."))

    @Test
    fun roundTripsAndRefusesWhatIsOutsideTheLimits() {
        assertEquals(request, BriefScriptCodec.decodeRequest(BriefScriptCodec.encodeRequest(request)))
        assertFailsWith<WireFormatException> { BriefScriptCodec.decodeRequest(BriefScriptCodec.encodeRequest(request.copy(facts = emptyList()))) }
        assertFailsWith<WireFormatException> { BriefScriptCodec.decodeRequest(BriefScriptCodec.encodeRequest(request.copy(facts = listOf("x".repeat(301))))) }
        assertFailsWith<WireFormatException> { BriefScriptCodec.decodeRequest(BriefScriptCodec.encodeRequest(request.copy(facts = List(41) { "a" }))) }
        assertFailsWith<WireFormatException> { BriefScriptCodec.decodeRequest(BriefScriptCodec.encodeRequest(request.copy(date = "today"))) }
        // Slice 3: the length goes along; an older device's request (no length) is Standard; an unknown one is refused.
        val full = request.copy(length = "full")
        assertEquals(full, BriefScriptCodec.decodeRequest(BriefScriptCodec.encodeRequest(full)))
        val old = BriefScriptCodec.encodeRequest(request).replace(",\"length\":\"standard\"", "")
        assertTrue("length" !in old, old)
        assertEquals("standard", BriefScriptCodec.decodeRequest(old).length)
        assertFailsWith<WireFormatException> { BriefScriptCodec.decodeRequest(BriefScriptCodec.encodeRequest(request.copy(length = "epic"))) }
        val r = BriefScriptCodec.Response("answered", "Good morning, Meka.")
        assertEquals(r, BriefScriptCodec.decodeResponse(BriefScriptCodec.encodeResponse(r)))
    }

    @Test
    fun aModelAnswerIsReadOnlyAsTheJson() {
        assertEquals("Hi, Meka.", BriefScriptCodec.parseModelAnswer("```json\n{\"script\":\"Hi, Meka.\"}\n```").script)
        assertEquals("", BriefScriptCodec.parseModelAnswer("Good morning, Meka!").script)
        assertEquals("", BriefScriptCodec.parseModelAnswer("{\"script\": 4}").script)
        assertTrue(BriefScriptCodec.parseModelAnswer("{\"script\":\"" + "a".repeat(5_000) + "\"}").script.length == BriefScriptCodec.MAX_SCRIPT)
    }

    @Test
    fun slice6CarriesTheMonthsLinesAndTheAisLine() {
        val r = request.copy(recent = listOf("Small steps still count.", "Start before you feel ready."))
        assertEquals(r, BriefScriptCodec.decodeRequest(BriefScriptCodec.encodeRequest(r)))
        assertTrue("recent" !in BriefScriptCodec.encodeRequest(request))
        assertFailsWith<WireFormatException> { BriefScriptCodec.decodeRequest(BriefScriptCodec.encodeRequest(request.copy(recent = List(31) { "Line $it." }))) }
        assertFailsWith<WireFormatException> { BriefScriptCodec.decodeRequest(BriefScriptCodec.encodeRequest(request.copy(recent = listOf("a".repeat(161))))) }
        val answer = BriefScriptCodec.parseModelAnswer("{\"script\":\"Hi, Meka.\",\"line\":\" Renew passport first. \"}")
        assertEquals("Renew passport first.", answer.line)
        assertEquals(answer, BriefScriptCodec.decodeResponse(BriefScriptCodec.encodeResponse(answer)))
        assertEquals("", BriefScriptCodec.parseModelAnswer("{\"script\":\"Hi.\"}").line)
    }
}
