package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Read the full email inside Navilife, slice 1: the sanitiser, the links, and the reader's view. */
class MailReaderTest {
    private val cal = LocalCalendar.UTC
    private val now = 1_790_985_600_000L // a Tuesday, 00:00 UTC

    @Test
    fun theSanitiserStripsScriptsFramesFormsAndEventHandlers() {
        val dirty = """
            <html><head><title>Hi</title><script>steal()</script><meta http-equiv="refresh" content="0;url=https://evil.example">
            <link rel="stylesheet" href="https://evil.example/x.css"><base href="https://evil.example/"></head>
            <body onload="steal()"><p class="lead" onclick="steal()" id="x">Hello <b>Meka</b> &amp; family</p>
            <iframe src="https://evil.example"><p>inside the frame</p></iframe>
            <form action="https://evil.example/login"><input name="password"><button>Sign in</button>Your bank</form>
            <svg><script>steal()</script><circle/></svg><object data="x.swf">obj text</object>
            <a href="javascript:steal()">bad</a> <a href="jav&#x61;script:steal()">sneaky</a> <a href="https://good.example/a?b=1&amp;c=2">good</a>
            <img src="x" onerror="steal()"><SCRIPT type="text/javascript">steal()</SCRIPT><!-- <script>x</script> -->
            <div style="background:url(https://track.example/p.gif);color:red">styled</div>
            <style>@import url(https://evil.example/a.css); .a{color:blue} .b{background-image:url(https://t.example/x)}</style>
            </body></html>
        """.trimIndent()
        val s = MailHtmlSanitizer.clean(dirty, images = false).html
        val lower = s.lowercase()
        for (bad in listOf("<script", "steal()", "<iframe", "inside the frame", "<form", "<input", "<button", "sign in", "<svg", "<object",
            "obj text", "onload", "onclick", "onerror", "javascript", "<meta", "<link", "<base", "evil.example", "track.example", "@import", "t.example", "id=\"x\"", "<title")) {
            assertFalse(lower.contains(bad), "still has $bad in $s")
        }
        assertTrue(s.contains("<p class=\"lead\">Hello <b>Meka</b> &amp; family</p>"), s)
        assertTrue(s.contains("Your bank"), "a form's own words stay, its fields go")
        assertTrue(s.contains("<a href=\"https://good.example/a?b=1&amp;c=2\">good</a>"), s)
        assertTrue(s.contains("<a>bad</a>") && s.contains("<a>sneaky</a>"), "a bad link keeps its words, not its address")
        assertTrue(s.contains("style=\"color:red\""), "the safe half of a style stays: $s")
        assertTrue(s.contains(".a{color:blue}"), s)
    }

    @Test
    fun remotePicturesWaitForShowImagesAndTrackingPixelsAreCounted() {
        val html = """<p>Hi</p><img src="https://track.example/open.gif?u=1" width="1" height="1"><img src="http://cdn.example/logo.png" alt="Logo">
            <img src="data:image/png;base64,iVBORw0KGgo=" alt="dot"><img src="cid:part1@x" alt="inline"><img src="data:image/svg+xml,<svg onload=x>">"""
        val off = MailHtmlSanitizer.clean(html, images = false)
        assertEquals(2, off.blockedImages)
        assertFalse(off.html.contains("track.example") || off.html.contains("cdn.example"), off.html)
        assertTrue(off.html.contains("data-blocked=\"1\""))
        assertTrue(off.html.contains("src=\"data:image/png;base64,iVBORw0KGgo=\""), "a picture inside the email shows at once")
        assertFalse(off.html.contains("cid:") || off.html.contains("svg"), off.html)
        val on = MailHtmlSanitizer.clean(html, images = true)
        assertEquals(0, on.blockedImages)
        assertTrue(on.html.contains("src=\"https://track.example/open.gif?u=1\"") && on.html.contains("src=\"http://cdn.example/logo.png\""))
        // The page's own wall: nothing loads unless pictures were asked for.
        assertTrue(MailReaderRules.page(off.html, images = false).contains("img-src data:;"))
        assertTrue(MailReaderRules.page(on.html, images = true).contains("img-src data: https: http:"))
        assertTrue(MailReaderRules.page("", false).contains("default-src 'none'"))
        // Background pictures in styles too.
        assertFalse(MailHtmlSanitizer.clean("<td style=\"background-image:url('https://t.example/a.png')\">x</td>", false).html.contains("url("))
        assertTrue(MailHtmlSanitizer.clean("<td style=\"background-image:url('https://t.example/a.png')\">x</td>", true).html.contains("url("))
        assertFalse(MailHtmlSanitizer.clean("<td style=\"background:url(javascript:x)\">x</td>", true).html.contains("url("))
    }

    @Test
    fun brokenMarkupNeverLetsATagThrough() {
        val s = MailHtmlSanitizer.clean("a < b and c > d <scr<script>ipt>x</script> <img src=\"https://a.example/x\" <p>end", images = false).html
        assertFalse(s.lowercase().contains("<scr"), s)
        assertTrue(s.startsWith("a &lt; b and c &gt; d"), s)
        assertFalse(MailHtmlSanitizer.clean("<a href=\"https://x.example\" onmouseover=\"y()\">t</a>", false).html.contains("onmouseover"))
        // An attribute value can't break out of its quotes.
        val q = MailHtmlSanitizer.clean("<p title='a\" onclick=\"x()'>t</p>", false).html
        assertEquals("<p title=\"a&quot; onclick=&quot;x()\">t</p>", q)
    }

    @Test
    fun theTextFallbackReadsTheWordsInOrder() {
        val t = MailHtmlSanitizer.text("<style>.a{}</style><p>Hello&nbsp;Meka,</p><p>Your order <b>#123</b> has shipped.</p><br><table><tr><td>Total</td><td>£12</td></tr></table><script>x()</script>")
        assertEquals("Hello Meka,\nYour order #123 has shipped.\nTotal £12", t)
        assertEquals("a\n\nb", MailHtmlSanitizer.tidyText("a\n\n\n\n  b  \n\n"))
    }

    @Test
    fun linksAreConfirmedWithTheirRealAddress() {
        val c = assertNotNull(MailReaderLinks.check("https://www.bbc.co.uk/news/123"))
        assertEquals("bbc.co.uk", c.host)
        assertEquals("Open bbc.co.uk?", c.title)
        assertEquals("https://www.bbc.co.uk/news/123", c.shown)
        // The host after an @ is where it really goes.
        assertEquals("evil.example", MailReaderLinks.check("https://paypal.com@evil.example/login")!!.host)
        assertEquals("Write an email", MailReaderLinks.check("mailto:sam@example.com?subject=Hi")!!.action)
        assertNull(MailReaderLinks.check("javascript:alert(1)"))
        assertNull(MailReaderLinks.check("file:///etc/passwd"))
        assertNull(MailReaderLinks.check("intent://scan#Intent;end"))
    }

    private val fallback = MailReaderFallback("Oakfield School", "Trip on Friday", "Please sign the form…", now - 3_600_000, "Personal")

    @Test
    fun theReaderShowsWhoWhenAndTheWholeEmail() {
        val answer = MailMessageAnswer(
            state = MailMessageAnswer.OK,
            from = MailPerson("Oakfield School", "office@oakfield.example"),
            to = listOf(MailPerson("Alex", "alex@example.com"), MailPerson(null, "sam@example.com")),
            cc = listOf(MailPerson("Jordan", "jordan@example.com")),
            atMs = now + 14 * 3_600_000 + 2 * 60_000,
            subject = "Trip on Friday",
            html = "<p>Dear parents,</p><p>Please sign.</p>",
            attachments = listOf(MailAttachmentFacts("consent form.pdf", 240_400, "application/pdf"), MailAttachmentFacts("map.png", 1_250_000, "image/png")),
            blockedImages = 2,
        )
        val v = MailReaderRules.view(answer, fallback, now + 15 * 3_600_000, cal, images = false)
        assertEquals("Oakfield School", v.from)
        assertEquals("office@oakfield.example", v.fromAddress)
        assertEquals("To Alex <alex@example.com>, sam@example.com", v.toLine)
        assertEquals("Cc Jordan <jordan@example.com>", v.ccLine)
        assertEquals("Today · 14:02", v.whenLine)
        assertEquals("Dear parents,\nPlease sign.", v.text)
        assertNotNull(v.page)
        assertEquals(listOf("consent form.pdf" to "240 KB · PDF", "map.png" to "1.3 MB · Picture"), v.attachments.map { it.name to it.meta })
        assertEquals("2 attachments", v.attachmentsLine)
        assertEquals("2 pictures hidden to stop tracking", v.imagesLine)
        assertTrue(v.canShowImages)
        assertNull(v.problem)
        assertTrue(v.note.contains("doesn't mark it read"))
        // Shown: the line goes and there's nothing more to show.
        val shown = MailReaderRules.view(answer.copy(blockedImages = 0), fallback, now, cal, images = true)
        assertNull(shown.imagesLine)
        assertFalse(shown.canShowImages)
    }

    @Test
    fun whenTheEmailCantBeReadThePreviewStaysWithTheReason() {
        val offline = MailReaderRules.view(null, fallback, now, cal, images = false)
        assertEquals(MailReaderRules.OFFLINE, offline.problem)
        assertEquals("Please sign the form…", offline.text)
        assertEquals("Oakfield School", offline.from)
        assertNull(offline.page)
        assertEquals("Yesterday · 23:00", offline.whenLine)
        assertEquals(MailReaderRules.GONE, MailReaderRules.view(MailMessageAnswer(MailMessageAnswer.GONE), fallback, now, cal, false).problem)
        assertEquals(MailReaderRules.RECONNECT, MailReaderRules.view(MailMessageAnswer(MailMessageAnswer.RECONNECT), fallback, now, cal, false).problem)
        assertEquals(MailReaderRules.NO_TEXT, MailReaderRules.view(null, fallback.copy(preview = null), now, cal, false).text)
        // A plain-text email: no page, its words as they are.
        val plain = MailReaderRules.view(MailMessageAnswer(MailMessageAnswer.OK, text = "Line one\r\nLine two"), fallback, now, cal, false)
        assertNull(plain.page)
        assertEquals("Line one\nLine two", plain.text)
        assertEquals("Trip on Friday", plain.subject)
    }

    @Test
    fun sizesReadNaturally() {
        assertEquals("900 bytes", MailReaderRules.size(900))
        assertEquals("12 KB", MailReaderRules.size(12_345))
        assertEquals("4.0 MB", MailReaderRules.size(3_999_000))
        assertEquals("Word", MailReaderRules.kind(null, "Letter.DOCX"))
        assertNull(MailReaderRules.kind("application/octet-stream", "blob"))
    }
}
