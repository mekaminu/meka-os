package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Talking to Ada like a conversation, slice 2: the server's timings go out as times and flags only. */
class ServerSpeedCodecTest {
    private val turn = ServerSpeedCodec.Turn(1_791_476_100_000L, open = true, spoken = true, model = "claude-haiku-4-5", webMs = 700, firstTokenMs = 400, firstSentenceMs = 800, clipMs = 1_200, doneMs = 2_000)

    @Test
    fun roundTrips() {
        ServerSpeedCodec.decodeRequest(ServerSpeedCodec.encodeRequest())
        ServerSpeedCodec.decodeRequest("")
        val partial = ServerSpeedCodec.Turn(1_791_476_100_000L, open = false, spoken = false, model = null, answered = false)
        assertEquals(listOf(turn, partial), ServerSpeedCodec.decodeResponse(ServerSpeedCodec.encodeResponse(listOf(turn, partial))))
        assertEquals(turn, ServerSpeedCodec.decodeTurn(ServerSpeedCodec.encodeTurn(turn)))
        assertNull(ServerSpeedCodec.decodeTurn("{}"))
    }

    @Test
    fun aBadTurnIsSkippedAndAnOtherVersionRefused() {
        val body = """{"w":${WireCodec.VERSION},"turns":[{"at":0},{"at":5,"tok":-1,"ok":true},"x"]}"""
        val turns = ServerSpeedCodec.decodeResponse(body)
        assertEquals(1, turns.size)
        assertNull(turns.single().firstTokenMs)
        assertFailsWith<WireFormatException> { ServerSpeedCodec.decodeResponse("""{"w":999,"turns":[]}""") }
    }

    @Test
    fun theAnswerCarriesNoText() {
        val body = ServerSpeedCodec.encodeResponse(listOf(turn))
        assertEquals(setOf("w", "turns", "at", "o", "s", "m", "web", "tok", "sen", "clip", "done", "ok"), Regex("\"([a-z]+)\":").findAll(body).map { it.groupValues[1] }.toSet())
    }
}
