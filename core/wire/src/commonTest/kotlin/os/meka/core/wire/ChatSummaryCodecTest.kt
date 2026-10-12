package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A busy chat's summary on the wire (messages grouped per chat, slice 3c). */
class ChatSummaryCodecTest {
    private val request = ChatSummaryCodec.Request(
        "2026-10-12", "Monday 12 October 2026 · 11:20",
        listOf(
            ChatSummaryCodec.Chat("Barça lads", true, listOf(ChatSummaryCodec.Line("Tunde", "11:01", "Asks about a ticket"))),
            ChatSummaryCodec.Chat("Tunde", false, listOf(ChatSummaryCodec.Line("Tunde", "10:30", "Shares the kick-off time"))),
        ),
    )

    @Test
    fun aRequestAndAResponseRoundTrip() {
        assertEquals(request, ChatSummaryCodec.decodeRequest(ChatSummaryCodec.encodeRequest(request)))
        val r = ChatSummaryCodec.Response("answered", listOf(ChatSummaryCodec.ChatAnswer("Tunde", "Kick-off time"), ChatSummaryCodec.ChatAnswer("Barça lads")))
        assertEquals(r, ChatSummaryCodec.decodeResponse(ChatSummaryCodec.encodeResponse(r)))
        val off = ChatSummaryCodec.Response("off", reason = "MEKA's AI is off")
        assertEquals(off, ChatSummaryCodec.decodeResponse(ChatSummaryCodec.encodeResponse(off)))
    }

    @Test
    fun aRequestOutsideTheLimitsIsRefused() {
        val bad = listOf(
            request.copy(chats = emptyList()),
            request.copy(date = "12/10/2026"),
            request.copy(chats = List(ChatSummaryCodec.MAX_CHATS + 1) { request.chats[0].copy(name = "Chat $it") }),
            request.copy(chats = listOf(request.chats[0].copy(lines = emptyList()))),
            request.copy(chats = listOf(request.chats[0].copy(lines = listOf(ChatSummaryCodec.Line("Tunde", "1101", "hi"))))),
            request.copy(chats = listOf(request.chats[0].copy(lines = listOf(ChatSummaryCodec.Line("Tunde", "11:01", "x".repeat(ChatSummaryCodec.MAX_LINE + 1)))))),
            request.copy(chats = listOf(request.chats[0].copy(name = "x".repeat(ChatSummaryCodec.MAX_NAME + 1)))),
        )
        bad.forEach { b -> assertFailsWith<WireFormatException> { ChatSummaryCodec.decodeRequest(ChatSummaryCodec.encodeRequest(b)) } }
    }

    @Test
    fun theModelsAnswerIsReadOnlyWhenItIsTheJson() {
        val fenced = "```json\n{\"chats\":[{\"name\":\"Tunde\",\"summary\":\"Kick-off\"},{\"summary\":\"no name\"}]}\n```"
        assertEquals(listOf(ChatSummaryCodec.ChatAnswer("Tunde", "Kick-off")), ChatSummaryCodec.parseModelAnswer(fenced))
        assertTrue(ChatSummaryCodec.parseModelAnswer("They talked about football").isEmpty())
        assertTrue(ChatSummaryCodec.parseModelAnswer("{\"chats\": \"none\"}").isEmpty())
    }
}
