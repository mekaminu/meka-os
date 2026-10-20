package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MailMessageCodecTest {
    @Test
    fun aHandleAndItsAnswerGoRoundTrip() {
        val req = MailMessageCodec.Request(MailMessageCodec.Request.TIDIED, "te0123abcd", "mt99ff00", images = true)
        assertEquals(req, MailMessageCodec.decodeRequest(MailMessageCodec.encodeRequest(req)))
        val item = MailMessageCodec.Request(MailMessageCodec.Request.ITEM, "mi42abcdef")
        assertEquals(item, MailMessageCodec.decodeRequest(MailMessageCodec.encodeRequest(item)))
        val resp = MailMessageCodec.Response(
            state = "ok", from = MailMessageCodec.Person("Alex", "alex@example.com"),
            to = listOf(MailMessageCodec.Person(null, "sam@example.com")), cc = emptyList(), atMs = 1_790_985_600_000L,
            subject = "Hello", html = "<p>Hi</p>", text = "Hi",
            attachments = listOf(MailMessageCodec.Attachment("a.pdf", 1234, "application/pdf")), blockedImages = 3,
        )
        assertEquals(resp, MailMessageCodec.decodeResponse(MailMessageCodec.encodeResponse(resp)))
        // Anything but ok carries only its state; the email's words never show in a log line.
        assertEquals(MailMessageCodec.Response("gone"), MailMessageCodec.decodeResponse(MailMessageCodec.encodeResponse(MailMessageCodec.Response("gone", subject = "secret"))))
        assertFalse(resp.toString().contains("Hello") || resp.toString().contains("alex"))
    }

    @Test
    fun badHandlesAreRefused() {
        assertFailsWith<WireFormatException> { MailMessageCodec.decodeRequest("""{"w":${WireCodec.VERSION},"kind":"item","id":"../etc"}""") }
        assertFailsWith<WireFormatException> { MailMessageCodec.decodeRequest("""{"w":${WireCodec.VERSION},"kind":"tidied","id":"te1234"}""") }
        assertFailsWith<WireFormatException> { MailMessageCodec.decodeRequest("""{"w":${WireCodec.VERSION},"kind":"raw","id":"te1234"}""") }
        assertNull(MailMessageCodec.decodeRequest("""{"w":${WireCodec.VERSION},"kind":"item","id":"mi1234"}""").batch)
    }
}
