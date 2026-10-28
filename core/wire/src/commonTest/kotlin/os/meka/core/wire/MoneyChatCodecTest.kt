package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Ask Obi's money chat on the wire: a round trip, and requests outside the limits refused. Invented data. */
class MoneyChatCodecTest {
    private val request = MoneyChatCodec.Request(
        "2026-10-28", "Why was September so expensive?",
        listOf("September 2026: spent £1,800, came in £2,500", "  by category: Groceries £400"),
        listOf(MoneyChatCodec.Turn("What if I save £300 a month?", "Saving £300 a month for 6 months puts by £1,800.")),
    )

    @Test
    fun roundTrip() {
        assertEquals(request, MoneyChatCodec.decodeRequest(MoneyChatCodec.encodeRequest(request)))
        val r = MoneyChatCodec.Response(AskCodec.Response.ANSWERED, "September had £400 of groceries.")
        assertEquals(r, MoneyChatCodec.decodeResponse(MoneyChatCodec.encodeResponse(r)))
        val off = MoneyChatCodec.Response(AskCodec.Response.OFF, reason = "off")
        assertEquals(off, MoneyChatCodec.decodeResponse(MoneyChatCodec.encodeResponse(off)))
    }

    @Test
    fun outsideTheLimitsIsRefused() {
        fun bad(r: MoneyChatCodec.Request) = assertFailsWith<WireFormatException> { MoneyChatCodec.decodeRequest(MoneyChatCodec.encodeRequest(r)) }
        bad(request.copy(question = "  "))
        bad(request.copy(question = "x".repeat(MoneyChatCodec.MAX_QUESTION + 1)))
        bad(request.copy(facts = emptyList()))
        bad(request.copy(facts = List(MoneyChatCodec.MAX_FACTS + 1) { "f" }))
        bad(request.copy(facts = listOf("x".repeat(MoneyChatCodec.MAX_FACT + 1))))
        bad(request.copy(history = List(MoneyChatCodec.MAX_HISTORY + 1) { MoneyChatCodec.Turn("q", "a") }))
        bad(request.copy(date = "28/10/2026"))
    }

    @Test
    fun aModelsAnswer() {
        assertEquals("Groceries led.", MoneyChatCodec.parseModelAnswer("  \"Groceries led.\" ").answer)
        assertNull(MoneyChatCodec.parseModelAnswer("   ").answer)
        assertNull(MoneyChatCodec.parseModelAnswer("x".repeat(MoneyChatCodec.MAX_ANSWER + 1)).answer)
    }
}
