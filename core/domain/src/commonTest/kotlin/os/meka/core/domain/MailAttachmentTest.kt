package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Read the full email, slice 2: which attachments open where, the bytes checked against the name, names made safe. */
class MailAttachmentTest {
    private val r = MailAttachmentRules
    private val pdf = "%PDF-1.7\n1 0 obj".encodeToByteArray()
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16)
    private fun b64(b: ByteArray) = "b64:${b.size}"

    @Test
    fun picturesAndPdfsOpenInNavilifeOthersGoToAnAppAndRunnableFilesNever() {
        assertEquals(r.VIEW, r.plan("consent.pdf", "application/pdf", 240_400))
        assertEquals(r.VIEW, r.plan("IMG_0042.JPG", null, 3_000_000))
        assertEquals(r.VIEW, r.plan("scan", "application/pdf", 1_000))
        assertEquals(r.SYSTEM, r.plan("menu.docx", null, 30_000))
        assertEquals(r.SYSTEM, r.plan("invite.ics", "text/calendar", 900))
        assertEquals(r.SYSTEM, r.plan("photos.zip", "application/zip", 9_000_000))
        // Anything that could run a program, a page or a macro, however it's dressed.
        listOf("setup.exe", "invoice.pdf.exe", "Statement.PDF.js", "update.apk", "report.docm", "figures.xlsm", "page.html",
            "logo.svg", "run.sh", "Installer.dmg", "shortcut.lnk", "letter.hta").forEach {
            assertEquals(r.REFUSED, r.plan(it, null, 1_000), it)
        }
        assertEquals(r.REFUSED, r.plan("statement", "text/html", 1_000))
        assertEquals(r.REFUSED, r.plan("logo.png", "image/svg+xml", 1_000))
        assertEquals(r.TOO_BIG, r.plan("video.mov", "video/quicktime", 40_000_000))
        // Refused wins over too big: the line says why it's never opened.
        assertEquals(r.REFUSED, r.plan("huge.exe", null, 40_000_000))
        assertEquals(r.REFUSED_LINE, r.planLine(r.REFUSED))
        assertNull(r.planLine(r.VIEW))
        // The reader's rows carry their place and plan.
        val rows = MailReaderRules.attachmentRows(listOf(MailAttachmentFacts("a.pdf", 10, null), MailAttachmentFacts("b.exe", 10, null)))
        assertEquals(listOf(0 to r.VIEW, 1 to r.REFUSED), rows.map { it.index to it.plan })
    }

    @Test
    fun theBytesMustBeWhatTheNameSays() {
        val pdfRow = MailReaderRules.attachmentRows(listOf(MailAttachmentFacts("consent.pdf", 20, "application/pdf"))).single()
        val opened = r.opened(pdfRow, MailMessageAnswer.OK, pdf, ::b64)
        assertEquals(r.VIEW, opened.state)
        assertEquals(VaultFileRules.PDF, opened.viewer!!.type)
        assertTrue(opened.viewer!!.isPdf)
        assertEquals("consent.pdf", opened.viewer!!.title)
        // A ".pdf" that is really a picture (or anything else) is refused.
        assertEquals(r.LIED_LINE, r.opened(pdfRow, MailMessageAnswer.OK, png, ::b64).line)
        assertEquals(r.LIED_LINE, r.opened(pdfRow, MailMessageAnswer.OK, "<html><script>".encodeToByteArray(), ::b64).line)
        val photo = MailReaderRules.attachmentRows(listOf(MailAttachmentFacts("photo.jpg", 20, "image/jpeg"))).single()
        assertEquals(VaultFileRules.JPEG, r.opened(photo, MailMessageAnswer.OK, jpeg, ::b64).viewer!!.type)
        // A PNG named .jpg is still a picture; a PDF named .jpg isn't shown as one.
        assertEquals(VaultFileRules.PNG, r.opened(photo, MailMessageAnswer.OK, png, ::b64).viewer!!.type)
        assertEquals(r.LIED_LINE, r.opened(photo, MailMessageAnswer.OK, pdf, ::b64).line)
        // A Word file goes to another app with its type.
        val doc = MailReaderRules.attachmentRows(listOf(MailAttachmentFacts("menu.docx", 20, null))).single()
        val d = r.opened(doc, MailMessageAnswer.OK, "PK\u0003\u0004".encodeToByteArray(), ::b64)
        assertEquals(r.SYSTEM, d.state)
        assertEquals("b64:4", d.base64)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", d.mime)
        assertNull(d.viewer)
        // The server's states become lines.
        assertEquals(r.GONE_LINE, r.opened(doc, MailMessageAnswer.GONE, null, ::b64).line)
        assertEquals(r.RECONNECT_LINE, r.opened(doc, MailMessageAnswer.RECONNECT, null, ::b64).line)
        assertEquals(r.OFFLINE_LINE, r.opened(doc, null, null, ::b64).line)
        assertEquals(r.TOO_BIG_LINE, r.opened(doc, r.TOO_BIG, null, ::b64).line)
        assertEquals(r.FAILED_LINE, r.opened(doc, MailMessageAnswer.FAILED, null, ::b64).line)
        // A refused row never opens, whatever came back.
        val exe = MailReaderRules.attachmentRows(listOf(MailAttachmentFacts("setup.exe", 20, null))).single()
        assertEquals(r.REFUSED_LINE, r.opened(exe, MailMessageAnswer.OK, pdf, ::b64).line)
    }

    @Test
    fun namesAreMadeSafeToWriteAndTheConfirmSaysWhoSentIt() {
        assertEquals("passwd", r.safeFileName("../../etc/passwd"))
        assertEquals("report.pdf", r.safeFileName("C:\\Users\\x\\report.pdf"))
        assertEquals("bashrc", r.safeFileName(".bashrc"))
        assertEquals("invoicefdp.exe", r.safeFileName("invoice\u202Efdp.exe"))
        assertEquals("a_b.txt", r.safeFileName("a:b.txt"))
        assertEquals("attachment", r.safeFileName("..."))
        assertTrue(r.safeFileName("x".repeat(300) + ".pdf").let { it.endsWith(".pdf") && it.length <= 84 })
        assertEquals("text/csv", r.mimeFor("data.csv", "application/octet-stream"))
        assertEquals("text/calendar", r.mimeFor("x", "text/calendar; method=REQUEST"))
        val c = r.confirm("menu.docx", "Oakfield School")
        assertEquals("Open menu.docx in another app?", c.title)
        assertTrue(c.body.contains("Oakfield School"))
        assertNotNull(r.sniff("junk\n%PDF-1.4".encodeToByteArray()))
        assertNull(r.sniff(ByteArray(0)))
    }

    @Test
    fun onlyAReadEmailCarriesItsHandle() {
        val cal = LocalCalendar.UTC
        val fb = MailReaderFallback("Sam", "Hi", "preview", 0, "Personal")
        val src = MailReaderSource(MailReaderSource.ITEM, "mi1234")
        assertEquals(src, MailReaderRules.view(MailMessageAnswer(MailMessageAnswer.OK, text = "x"), fb, 0, cal, false, src).source)
        assertNull(MailReaderRules.view(MailMessageAnswer(MailMessageAnswer.GONE), fb, 0, cal, false, src).source)
        assertNull(MailReaderRules.view(null, fb, 0, cal, false, src).source)
    }
}
