package os.meka.core.domain

/*
 * Read the full email inside Navilife (Meka, 2026-10-20 17:50: "the attachments listed by name and size (tap one to open
 * it)"), slice 2: **opening an attachment.** A tap on one of the reader's attachments fetches that one file from the
 * provider at that moment (the server, by Navilife's handle for the email and the file's place in its list, checked
 * against its name; nothing kept or logged, no AI) and:
 *
 * - a **picture or PDF** opens inside Navilife, in the Vault's viewer, but only when its bytes really are one (a
 *   ".pdf" that isn't a PDF is refused: the name lied);
 * - **anything else that can't run a program** (Word, Excel, slides, text, a calendar invite, a zip) is offered to
 *   another app on the device only after a confirm naming the file and its sender ("Navilife can't look inside it");
 * - **anything that could run a program or a page** (an app, installer, script, macro-enabled Office file, HTML or SVG,
 *   a shortcut, a disk image) is never opened from Navilife: the row says to use his mail if he trusts it;
 * - **over [MailAttachmentRules.MAX_BYTES]** isn't fetched: open it in the mail app.
 *
 * Mail is untrusted (ADR-006): the file never reaches the AI and is deleted from the device when the viewer closes.
 * Pure, non-AI, unit-tested.
 */

/** What tapping an attachment gave: shown in Navilife ([viewer]), handed to another app ([base64]), or a [line] why not. */
data class MailAttachmentOpened(
    val name: String,
    /** [MailAttachmentRules.VIEW], [MailAttachmentRules.SYSTEM] or [MailAttachmentRules.PROBLEM]. */
    val state: String,
    /** A picture or PDF for the Vault's viewer (VIEW). */
    val viewer: VaultFileOpened? = null,
    /** The file's bytes as base64 for another app (SYSTEM; only Strings cross to the Mac). */
    val base64: String? = null,
    /** The type another app is told (SYSTEM). */
    val mime: String? = null,
    /** A safe file name to hand over (no folders, no hidden or control characters, its ending kept). */
    val fileName: String = MailAttachmentRules.safeFileName(name),
    /** Why it didn't open (PROBLEM). */
    val line: String? = null,
)

/** The confirm before a file goes to another app. */
data class MailAttachmentConfirm(val title: String, val body: String, val action: String, val cancel: String)

object MailAttachmentRules {
    /** The most fetched for one attachment (Gmail's own limit is 25 MB; past this, the mail app). */
    const val MAX_BYTES = 15L * 1024 * 1024

    /** Opens inside Navilife (a picture or PDF). */
    const val VIEW = "view"
    /** Offered to another app after a confirm. */
    const val SYSTEM = "system"
    /** Never opened from Navilife (it could run a program or a page). */
    const val REFUSED = "refused"
    /** Too big to fetch. */
    const val TOO_BIG = "too_big"
    /** It was tried and didn't open (with a line). */
    const val PROBLEM = "problem"

    const val REFUSED_LINE = "Navilife won't open this kind of file, it could run a program · open it in your mail if you trust it"
    const val TOO_BIG_LINE = "Too big to open in Navilife (over 15 MB) · open it in your mail"
    const val GONE_LINE = "That attachment isn't in the email any more"
    const val FAILED_LINE = "Couldn't get the attachment from your mail just now · try again in a moment"
    const val OFFLINE_LINE = "Couldn't reach your server · try again when you're online"
    const val RECONNECT_LINE = "Your mail account needs signing in again (Ask → Settings → Mail)"
    const val LIED_LINE = "This file isn't what its name says, so Navilife won't open it · check it in your mail"
    const val NO_APP_LINE = "No app on this device opens this kind of file"
    const val OPEN = "Open"
    const val CANCEL = "Cancel"

    /** Endings (and types) that can run a program, a script, a page or a macro when opened. */
    private val DANGEROUS = setOf(
        "exe", "msi", "msp", "bat", "cmd", "com", "scr", "pif", "cpl", "vb", "vbs", "vbe", "js", "jse", "mjs", "wsf",
        "wsh", "ws", "ps1", "psm1", "psd1", "sh", "bash", "zsh", "command", "tool", "app", "dmg", "pkg", "mpkg", "apk",
        "aab", "xapk", "apks", "jar", "jnlp", "class", "reg", "lnk", "url", "webloc", "inf", "hta", "htm", "html", "xhtml",
        "shtml", "svg", "svgz", "mht", "mhtml", "xml", "xsl", "iso", "img", "vhd", "vhdx", "dll", "so", "dylib", "sys",
        "drv", "ocx", "scpt", "applescript", "workflow", "action", "docm", "dotm", "xlsm", "xltm", "xlam", "xla", "pptm",
        "potm", "ppam", "ppsm", "sldm", "py", "pyc", "pl", "rb", "php", "msc", "gadget", "appref-ms", "library-ms",
        "settingcontent-ms", "chm", "hlp", "lib", "ade", "adp", "mde", "accde", "one", "onepkg", "xll", "wiz",
    )
    private val DANGEROUS_TYPES = listOf(
        "text/html", "application/xhtml", "image/svg", "application/javascript", "text/javascript", "application/x-msdownload",
        "application/x-msdos-program", "application/vnd.android.package-archive", "application/java-archive",
        "application/x-sh", "application/x-apple-diskimage", "application/x-ms-shortcut", "application/hta",
        "application/vnd.ms-word.document.macroenabled", "application/vnd.ms-excel.sheet.macroenabled",
        "application/vnd.ms-powerpoint.presentation.macroenabled",
    )
    private val PICTURES = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")

    private fun ext(name: String): String = name.trim().trimEnd('.', ' ').substringAfterLast('.', "").lowercase()

    /**
     * How a tap opens [name] ([type] as the provider says, [bytes] its size): [REFUSED] when either its ending or its
     * type could run something (a double ending like "invoice.pdf.exe" is read by its last), [TOO_BIG], [VIEW] for a
     * picture or PDF, else [SYSTEM].
     */
    fun plan(name: String, type: String?, bytes: Long): String {
        val e = ext(name)
        val t = type?.lowercase()?.trim().orEmpty()
        if (e in DANGEROUS || DANGEROUS_TYPES.any { t.startsWith(it) }) return REFUSED
        if (bytes > MAX_BYTES) return TOO_BIG
        val picture = e in PICTURES || (e.isEmpty() && t.startsWith("image/"))
        val pdf = e == "pdf" || (e.isEmpty() && t == VaultFileRules.PDF)
        return if (picture || pdf) VIEW else SYSTEM
    }

    /** The line under the row for a plan that doesn't fetch ([REFUSED], [TOO_BIG]); null for the others. */
    fun planLine(plan: String): String? = when (plan) {
        REFUSED -> REFUSED_LINE
        TOO_BIG -> TOO_BIG_LINE
        else -> null
    }

    /** What the bytes really are, from their first bytes: [VaultFileRules.PDF], a picture's type, or null. */
    fun sniff(head: ByteArray): String? {
        fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xff else -1
        fun ascii(from: Int, s: String) = s.indices.all { at(from + it) == s[it].code }
        // A PDF may have a little junk before its header; look in the first 1 KB.
        val pdfAt = (0..minOf(1024, head.size - 5).coerceAtLeast(-1)).firstOrNull { ascii(it, "%PDF-") }
        return when {
            pdfAt != null -> VaultFileRules.PDF
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> VaultFileRules.JPEG
            at(0) == 0x89 && ascii(1, "PNG") -> VaultFileRules.PNG
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") || ascii(8, "msf1") || ascii(8, "heif")) -> "image/heic"
            ascii(0, "BM") && head.size > 26 -> "image/bmp"
            else -> null
        }
    }

    /** A name safe to write and hand over: no folders, no leading dots, no control or odd characters, its ending kept. */
    fun safeFileName(name: String): String {
        val base = name.replace('\\', '/').substringAfterLast('/')
        val cleaned = buildString {
            for (c in base) when {
                c.code < 0x20 || c.code == 0x7f -> {}
                c in "<>:\"|?*" -> append('_')
                c == '‮' || c == '‭' || c == '‎' || c == '‏' || c == '⁦' || c == '⁧' || c == '⁨' || c == '⁩' -> {}
                else -> append(c)
            }
        }.trim().trimStart('.', ' ').trimEnd('.', ' ')
        val e = ext(cleaned)
        val stem = if (e.isEmpty()) cleaned else cleaned.dropLast(e.length + 1)
        val keepExt = e.take(10)
        val shortStem = stem.take(80).trimEnd('.', ' ').ifEmpty { "attachment" }
        return if (keepExt.isEmpty()) shortStem else "$shortStem.$keepExt"
    }

    /** The type another app is told: the provider's own when it's a plain one, else from the ending, else generic. */
    fun mimeFor(name: String, type: String?): String {
        val t = type?.lowercase()?.trim()?.substringBefore(';')?.trim().orEmpty()
        if (t.matches(Regex("^[a-z0-9.+-]+/[a-z0-9.+-]+$")) && t != "application/octet-stream") return t
        return when (ext(name)) {
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "csv" -> "text/csv"
            "txt" -> "text/plain"
            "ics" -> "text/calendar"
            "zip" -> "application/zip"
            "rtf" -> "application/rtf"
            "odt" -> "application/vnd.oasis.opendocument.text"
            "pages" -> "application/vnd.apple.pages"
            "numbers" -> "application/vnd.apple.numbers"
            "key" -> "application/vnd.apple.keynote"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "eml" -> "message/rfc822"
            else -> "application/octet-stream"
        }
    }

    /** The confirm before [name] goes to another app: who sent it, and that Navilife can't see inside. */
    fun confirm(name: String, from: String): MailAttachmentConfirm = MailAttachmentConfirm(
        title = "Open ${name.take(80)} in another app?",
        body = "It came from ${from.take(80)}. Navilife can't look inside this kind of file, so only open it if you expected it.",
        action = OPEN, cancel = CANCEL,
    )

    /** The server's answer read into what the reader does: [state] is a [MailMessageAnswer] state; [bytes] when ok. */
    fun opened(row: MailAttachmentRow, state: String?, bytes: ByteArray?, base64: (ByteArray) -> String): MailAttachmentOpened {
        val name = row.name
        val problem = { line: String -> MailAttachmentOpened(name, PROBLEM, line = line) }
        if (row.plan == REFUSED) return problem(REFUSED_LINE)
        if (row.plan == TOO_BIG) return problem(TOO_BIG_LINE)
        when (state) {
            null -> return problem(OFFLINE_LINE)
            MailMessageAnswer.OK -> {}
            MailMessageAnswer.GONE, MailMessageAnswer.UNKNOWN -> return problem(GONE_LINE)
            MailMessageAnswer.RECONNECT -> return problem(RECONNECT_LINE)
            TOO_BIG -> return problem(TOO_BIG_LINE)
            REFUSED -> return problem(REFUSED_LINE)
            else -> return problem(FAILED_LINE)
        }
        if (bytes == null || bytes.isEmpty()) return problem(FAILED_LINE)
        if (bytes.size > MAX_BYTES) return problem(TOO_BIG_LINE)
        val real = sniff(bytes.copyOfRange(0, minOf(bytes.size, 1100)))
        if (row.plan == VIEW) {
            // A picture or PDF opens in Navilife only when its bytes agree with its name.
            val wantPdf = ext(name) == "pdf" || (ext(name).isEmpty() && row.type?.lowercase() == VaultFileRules.PDF)
            val ok = if (wantPdf) real == VaultFileRules.PDF else real != null && real != VaultFileRules.PDF
            if (!ok) return problem(LIED_LINE)
            return MailAttachmentOpened(name, VIEW, viewer = VaultFileOpened(name, real!!, base64(bytes), null))
        }
        return MailAttachmentOpened(name, SYSTEM, base64 = base64(bytes), mime = mimeFor(name, row.type))
    }
}
