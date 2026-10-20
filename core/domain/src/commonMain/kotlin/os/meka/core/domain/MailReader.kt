package os.meka.core.domain

/*
 * Read the full email inside Navilife (Meka, 2026-10-20 17:50: "what if I wanted to see the full email details"), slice 1.
 * A tap on an email on From your mail or in Archived for you opens a reader with who it's from (name and address), To and
 * Cc, when, the account, the subject, the whole body and the attachments by name and size. The server fetches the email
 * from the provider at the moment of the tap by Navilife's own handle (the card's id, or the tidy and the email's key:
 * the provider's ids never reach the apps, ADR-008 addendum), sanitises its HTML with [MailHtmlSanitizer] and hands it
 * back; nothing of it is stored, logged, or given to the AI. Mail is untrusted (ADR-006): the apps show it in a web view
 * with JavaScript off, no file access, a Content-Security-Policy that loads nothing ([MailReaderRules.page]), remote
 * pictures off until he taps Show images (that blocks tracking pixels), and every link confirmed with its real address
 * before the browser opens it ([MailReaderLinks]). Reading it never marks it read. Non-AI, pure, unit-tested.
 */

/** A sender or recipient as the provider gave it; either part may be missing. */
data class MailPerson(val name: String?, val address: String?) {
    /** "Alex Smith <alex@example.com>", or whichever part there is. */
    val label: String get() {
        val n = name?.trim()?.takeIf { it.isNotEmpty() }
        val a = address?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            n != null && a != null && !n.equals(a, ignoreCase = true) -> "$n <$a>"
            else -> n ?: a ?: "Unknown sender"
        }
    }
}

/** One attachment: its name, size in bytes and type (as the provider says; never opened by the server). */
data class MailAttachmentFacts(val name: String, val bytes: Long, val type: String?)

/** One email as the server read it from the provider, before it is made safe (server only; never kept). */
data class MailMessageFacts(
    val from: MailPerson,
    val to: List<MailPerson>,
    val cc: List<MailPerson>,
    val atMs: Long,
    val subject: String?,
    /** The HTML body as sent (untrusted), or null. */
    val html: String?,
    /** The plain-text body as sent, or null. */
    val text: String?,
    val attachments: List<MailAttachmentFacts>,
)

/** [MailHtmlSanitizer.clean]'s answer: safe HTML, and how many remote pictures were held back. */
data class MailSanitised(val html: String, val blockedImages: Int)

/**
 * An allow-list HTML sanitiser for email bodies (pure Kotlin, so the server and the tests share it). Only formatting
 * tags and attributes survive; scripts, styles' imports and remote URLs, frames, objects, forms and their fields,
 * SVG/MathML, event handlers (`on…`), `id`, `background` and every non-http(s)/mailto link are dropped. Remote pictures
 * (`http:`/`https:` `src`) are kept only when [clean]'s `images` is true; `data:image/…` ones always; `cid:` ones never
 * (inline attachments aren't fetched). Attribute values are entity-decoded before their URL is checked and escaped again
 * on the way out, so `jav&#x61;script:` can't slip through. The apps' page adds a CSP as a second wall.
 */
object MailHtmlSanitizer {
    /** The most of the email's HTML read (bigger is cut, then the reader leans on the plain text). */
    const val MAX_IN = 1_500_000
    /** The most safe HTML handed back; past this the reader shows the plain text instead. */
    const val MAX_OUT = 700_000

    private val KEEP = setOf(
        "a", "abbr", "address", "b", "bdi", "bdo", "big", "blockquote", "br", "caption", "center", "cite", "code", "col",
        "colgroup", "dd", "del", "dfn", "div", "dl", "dt", "em", "figcaption", "figure", "font", "h1", "h2", "h3", "h4",
        "h5", "h6", "hr", "i", "img", "ins", "kbd", "li", "mark", "ol", "p", "pre", "q", "s", "samp", "section", "small",
        "span", "strike", "strong", "sub", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "time", "tr", "tt", "u",
        "ul", "wbr", "article", "header", "footer", "main", "nav", "aside", "style",
    )
    /** Dropped with everything inside them. */
    private val DROP_ALL = setOf(
        "script", "iframe", "frame", "frameset", "object", "embed", "applet", "noscript", "template", "svg", "math",
        "title", "textarea", "select", "button", "audio", "video", "canvas", "noembed", "noframes", "xmp", "plaintext",
        "portal", "dialog",
    )
    /** Their text is raw (no tags inside), read to their own end tag. */
    private val RAW = setOf("script", "style", "textarea", "title", "xmp", "noscript", "iframe", "noembed", "noframes", "plaintext")
    private val VOID = setOf("br", "hr", "img", "col", "wbr", "input", "meta", "link", "base", "area", "source", "track", "param", "keygen")
    private val ATTRS = setOf(
        "style", "align", "valign", "bgcolor", "width", "height", "border", "cellpadding", "cellspacing", "colspan",
        "rowspan", "dir", "lang", "title", "color", "face", "size", "class", "alt", "href", "src", "start", "type",
        "span", "nowrap", "summary", "datetime", "cite",
    )
    private val BAD_CSS = listOf("url(", "expression", "javascript:", "vbscript:", "behavior", "-moz-binding", "@import", "position")

    fun clean(html: String, images: Boolean): MailSanitised {
        val s = if (html.length > MAX_IN) html.substring(0, MAX_IN) else html
        val out = StringBuilder(minOf(s.length, MAX_OUT) + 64)
        var blocked = 0
        var i = 0
        // Skipping a dropped element: its name and how deep inside it we are.
        var skipName: String? = null
        var skipDepth = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '<') {
                if (skipName == null) out.append(if (c == '>') "&gt;" else c.toString())
                i++
                continue
            }
            if (s.startsWith("<!--", i)) {
                val end = s.indexOf("-->", i + 4)
                i = if (end < 0) s.length else end + 3
                continue
            }
            if (i + 1 < s.length && (s[i + 1] == '!' || s[i + 1] == '?')) {
                val end = s.indexOf('>', i + 2)
                i = if (end < 0) s.length else end + 1
                continue
            }
            val tag = readTag(s, i)
            if (tag == null) {
                if (skipName == null) out.append("&lt;")
                i++
                continue
            }
            i = tag.end
            val name = tag.name
            if (skipName != null) {
                if (name == skipName) {
                    if (tag.closing) skipDepth-- else if (!tag.selfClosing) skipDepth++
                    if (skipDepth <= 0) { skipName = null; skipDepth = 0 }
                }
                continue
            }
            if (!tag.closing && name in RAW) {
                // Read the raw text to its own end tag; only a style's is kept (made safe).
                val endAt = indexOfIgnoreCase(s, "</$name", i)
                val body = if (endAt < 0) s.substring(i) else s.substring(i, endAt)
                i = if (endAt < 0) s.length else (s.indexOf('>', endAt).let { if (it < 0) s.length else it + 1 })
                if (name == "style") out.append("<style>").append(safeCss(body, images).replace("<", "")).append("</style>")
                continue
            }
            if (name in DROP_ALL) {
                if (!tag.closing && !tag.selfClosing && name !in VOID) { skipName = name; skipDepth = 1 }
                continue
            }
            if (name !in KEEP) continue
            if (tag.closing) { if (name !in VOID) out.append("</").append(name).append('>'); continue }
            out.append('<').append(name)
            var hadSrc = false
            for ((k, raw) in tag.attrs) {
                if (k !in ATTRS) continue
                val v = ReaderRules.decode(raw)
                when (k) {
                    "href" -> if (name == "a") MailReaderLinks.safeHref(v)?.let { out.attr("href", it) }
                    "src" -> if (name == "img") {
                        val u = v.trim()
                        val lower = compact(u)
                        when {
                            lower.startsWith("data:image/") && !lower.startsWith("data:image/svg") -> { out.attr("src", u); hadSrc = true }
                            lower.startsWith("https://") || lower.startsWith("http://") ->
                                if (images) { out.attr("src", u); hadSrc = true } else { blocked++; out.append(" data-blocked=\"1\"") }
                            else -> {}
                        }
                    }
                    "style" -> safeCss(v, images).takeIf { it.isNotBlank() }?.let { out.attr("style", it) }
                    "cite" -> {}
                    else -> out.attr(k, v)
                }
            }
            if (name == "img" && !hadSrc && tag.attrs.none { it.first == "alt" }) out.append(" alt=\"\"")
            out.append('>')
            if (out.length > MAX_OUT) break
        }
        val html2 = if (out.length > MAX_OUT) "" else out.toString()
        return MailSanitised(html2, blocked)
    }

    /**
     * Plain words from HTML (for the reader's text fallback and for the offline view): tags dropped, block tags as line
     * breaks, entities read, blank lines run together. Scripts, styles and the other dropped elements say nothing.
     */
    fun text(html: String): String {
        val s = if (html.length > MAX_IN) html.substring(0, MAX_IN) else html
        val out = StringBuilder()
        var i = 0
        var skipName: String? = null
        var skipDepth = 0
        val blocks = setOf("p", "div", "br", "tr", "li", "h1", "h2", "h3", "h4", "h5", "h6", "table", "blockquote", "hr", "section", "article", "header", "footer", "ul", "ol", "pre")
        val text = StringBuilder()
        fun flush() { if (text.isNotEmpty()) { out.append(ReaderRules.decode(text.toString())); text.clear() } }
        while (i < s.length) {
            val c = s[i]
            if (c != '<') { if (skipName == null) text.append(c); i++; continue }
            if (s.startsWith("<!--", i)) { val e = s.indexOf("-->", i + 4); i = if (e < 0) s.length else e + 3; continue }
            if (i + 1 < s.length && (s[i + 1] == '!' || s[i + 1] == '?')) { val e = s.indexOf('>', i + 2); i = if (e < 0) s.length else e + 1; continue }
            val tag = readTag(s, i)
            if (tag == null) { if (skipName == null) text.append('<'); i++; continue }
            i = tag.end
            if (skipName != null) {
                if (tag.name == skipName) { if (tag.closing) skipDepth-- else if (!tag.selfClosing) skipDepth++; if (skipDepth <= 0) skipName = null }
                continue
            }
            if (!tag.closing && tag.name in RAW) {
                val endAt = indexOfIgnoreCase(s, "</${tag.name}", i)
                i = if (endAt < 0) s.length else (s.indexOf('>', endAt).let { if (it < 0) s.length else it + 1 })
                continue
            }
            if (tag.name in DROP_ALL) { if (!tag.closing && !tag.selfClosing) { skipName = tag.name; skipDepth = 1 }; continue }
            if (tag.name in blocks) { flush(); if (out.isNotEmpty() && out.last() != '\n') out.append('\n') }
            else if (tag.name == "td" || tag.name == "th") { flush(); out.append(' ') }
        }
        flush()
        return tidyText(out.toString())
    }

    /** Lines trimmed, runs of spaces made one, at most one blank line in a row, no invisible characters. */
    fun tidyText(raw: String): String {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { line ->
            line.filter { it != '​' && it != '‌' && it != '‍' && it != '﻿' && it != '͏' && it != '­' && (it.code >= 0x20 || it == '\t') }
                .replace(' ', ' ').replace('\t', ' ').split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        }
        val out = mutableListOf<String>()
        for (l in lines) {
            if (l.isEmpty() && (out.isEmpty() || out.last().isEmpty())) continue
            out += l
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out.joinToString("\n")
    }

    /** CSS with every way out removed: declarations that load or run anything, or pin themselves over the page. */
    internal fun safeCss(css: String, images: Boolean): String {
        val decoded = css.replace("\\", "")
        // Whole @-rules that fetch (@import, @font-face) go first.
        var s = decoded
        for (at in listOf("@import", "@font-face", "@namespace")) {
            while (true) {
                val k = indexOfIgnoreCase(s, at, 0)
                if (k < 0) break
                val semi = s.indexOf(';', k)
                val brace = s.indexOf('{', k)
                val end = when {
                    brace in 0 until (if (semi < 0) Int.MAX_VALUE else semi) -> s.indexOf('}', brace).let { if (it < 0) s.length else it + 1 }
                    semi >= 0 -> semi + 1
                    else -> s.length
                }
                s = s.substring(0, k) + s.substring(end)
            }
        }
        // Then each declaration that loads or runs something (url(…) only when pictures are shown, never otherwise).
        val sb = StringBuilder()
        var depth = 0
        val decl = StringBuilder()
        fun endDecl(term: Char?) {
            val d = decl.toString()
            val lower = compact(d)
            val bad = BAD_CSS.any { b -> lower.contains(b.replace(" ", "")) && !(b == "url(" && images && safeUrls(d)) }
            if (!bad && d.isNotBlank()) { sb.append(d); if (term != null) sb.append(term) }
            decl.clear()
        }
        for (ch in s) {
            when (ch) {
                ';' -> endDecl(';')
                '{' -> { endDecl(null); sb.append('{'); depth++ }
                '}' -> { endDecl(null); sb.append('}'); depth-- }
                else -> decl.append(ch)
            }
        }
        endDecl(null)
        return sb.toString().trim()
    }

    /** Every url(…) in a declaration points at an http(s) picture (shown only after Show images). */
    private fun safeUrls(d: String): Boolean {
        val lower = d.lowercase()
        var k = lower.indexOf("url(")
        if (k < 0) return true
        while (k >= 0) {
            val inner = lower.substring(k + 4).trimStart(' ', '"', '\'')
            if (!(inner.startsWith("https://") || inner.startsWith("http://"))) return false
            k = lower.indexOf("url(", k + 4)
        }
        return true
    }

    private fun StringBuilder.attr(k: String, v: String) {
        append(' ').append(k).append("=\"")
        for (ch in v) when (ch) {
            '&' -> append("&amp;"); '"' -> append("&quot;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            else -> if (ch.code >= 0x20 || ch == '\t') append(ch)
        }
        append('"')
    }

    /** Lower case without whitespace or control characters (how browsers read a scheme). */
    internal fun compact(s: String): String = buildString { for (ch in s) if (ch.code > 0x20 && ch != ' ') append(ch.lowercaseChar()) }

    private fun indexOfIgnoreCase(s: String, what: String, from: Int): Int {
        var k = from
        while (k <= s.length - what.length) {
            var n = 0
            while (n < what.length && s[k + n].lowercaseChar() == what[n].lowercaseChar()) n++
            if (n == what.length) return k
            k++
        }
        return -1
    }

    private class Tag(val name: String, val closing: Boolean, val selfClosing: Boolean, val attrs: List<Pair<String, String>>, val end: Int)

    /** The tag starting at [at] ('<'), or null when it isn't one (a stray '<'). */
    private fun readTag(s: String, at: Int): Tag? {
        var i = at + 1
        val closing = i < s.length && s[i] == '/'
        if (closing) i++
        val nameStart = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == ':')) i++
        if (i == nameStart || !s[nameStart].isLetter()) return null
        val name = s.substring(nameStart, i).lowercase()
        val attrs = mutableListOf<Pair<String, String>>()
        var selfClosing = false
        while (i < s.length) {
            val c = s[i]
            when {
                c == '>' -> return Tag(name, closing, selfClosing, attrs, i + 1)
                c == '/' -> { selfClosing = true; i++ }
                c.isWhitespace() -> i++
                else -> {
                    selfClosing = false
                    val ks = i
                    while (i < s.length && !s[i].isWhitespace() && s[i] != '=' && s[i] != '>' && s[i] != '/') i++
                    val key = s.substring(ks, i).lowercase()
                    while (i < s.length && s[i].isWhitespace()) i++
                    var value = ""
                    if (i < s.length && s[i] == '=') {
                        i++
                        while (i < s.length && s[i].isWhitespace()) i++
                        if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                            val q = s[i]
                            val e = s.indexOf(q, i + 1)
                            value = if (e < 0) s.substring(i + 1) else s.substring(i + 1, e)
                            i = if (e < 0) s.length else e + 1
                        } else {
                            val vs = i
                            while (i < s.length && !s[i].isWhitespace() && s[i] != '>') i++
                            value = s.substring(vs, i)
                        }
                    }
                    if (key.isNotEmpty() && attrs.none { it.first == key }) attrs += key to value
                }
            }
        }
        return Tag(name, closing, selfClosing, attrs, s.length)
    }
}

/** A link in an email he tapped: what the confirm says before the browser opens it. */
data class MailLinkCheck(
    val url: String,
    /** "bbc.co.uk" (the real host, without "www."), or the address for an email link. */
    val host: String,
    /** "Open bbc.co.uk?" */
    val title: String,
    /** The whole real address, cut to [MailReaderLinks.MAX_SHOWN]. */
    val shown: String,
    /** "Open" / "Write an email". */
    val action: String,
)

/** Links in an email: only https, http and mailto are ever followed, and each only after he sees where it really goes. */
object MailReaderLinks {
    const val MAX_SHOWN = 300
    const val CANCEL = "Cancel"
    const val NOT_OPENED = "That link can't be opened from Navilife"

    /** [href] when it is a link Navilife will offer (https, http, mailto), else null. */
    fun safeHref(href: String): String? {
        val u = href.trim()
        val c = MailHtmlSanitizer.compact(u)
        return if (c.startsWith("https://") || c.startsWith("http://") || c.startsWith("mailto:")) u else null
    }

    /** What to ask before opening [url], or null when it isn't a link Navilife opens. */
    fun check(url: String): MailLinkCheck? {
        val u = safeHref(url) ?: return null
        if (MailHtmlSanitizer.compact(u).startsWith("mailto:")) {
            val to = u.substringAfter(':').substringBefore('?').trim().take(120)
            return MailLinkCheck(u, to, "Write an email to ${to.ifEmpty { "this address" }}?", u.take(MAX_SHOWN), "Write an email")
        }
        val afterScheme = u.substringAfter("//")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase().removePrefix("www.")
        if (host.isEmpty()) return null
        return MailLinkCheck(u, host, "Open $host?", u.take(MAX_SHOWN) + if (u.length > MAX_SHOWN) "…" else "", "Open")
    }
}

/**
 * One attachment row in the reader: "report.pdf" · "240 KB · PDF". [index] is its place in the email's list (how the
 * server finds it again); [plan] is how a tap opens it ([MailAttachmentRules.plan]).
 */
data class MailAttachmentRow(
    val name: String,
    val meta: String,
    val index: Int = 0,
    val plan: String = MailAttachmentRules.VIEW,
    val type: String? = null,
    val bytes: Long = 0,
)

/** What the reader shows; [page] is ready for the web view, or null when the email has no HTML (then [text]). */
data class MailReaderView(
    val from: String,
    val fromAddress: String?,
    val toLine: String?,
    val ccLine: String?,
    val whenLine: String,
    val account: String,
    val subject: String,
    val page: String?,
    val text: String,
    val attachments: List<MailAttachmentRow>,
    val attachmentsLine: String?,
    /** "2 pictures hidden to stop tracking" while remote pictures are held back; null when there are none (or shown). */
    val imagesLine: String?,
    val canShowImages: Boolean,
    /** "Read from your Personal mail just now · Navilife doesn't keep it · reading it doesn't mark it read". */
    val note: String,
    /** Why the whole email couldn't be read (then [text] is the preview Navilife already had), else null. */
    val problem: String?,
    val spoken: String,
    /** Which email this is, for opening its attachments ([MailAttachmentRules]); null when it couldn't be read. */
    val source: MailReaderSource? = null,
)

/** Navilife's own handle for the email in a reader: a card's id ([MailReaderSource.ITEM]) or a tidy's email ([TIDIED]). */
data class MailReaderSource(val kind: String, val id: String, val batch: String? = null) {
    companion object {
        const val ITEM = "item"
        const val TIDIED = "tidied"
    }
}

object MailReaderRules {
    const val SHOW_IMAGES = "Show images"
    const val IMAGES_SHOWN = "Pictures shown for this email"
    const val READ_WHOLE = "Read the whole email"
    const val CLOSE = "Close"
    const val LOADING = "Getting the whole email…"
    const val GONE = "This email isn't in your mailbox any more · here's what Navilife had"
    const val OFFLINE = "Couldn't reach your server · here's the preview Navilife had"
    const val FAILED = "Couldn't read the whole email from your mail just now · here's the preview Navilife had"
    const val RECONNECT = "Your mail account needs signing in again (Ask → Settings → Mail) · here's the preview"
    const val UNKNOWN = "Navilife couldn't find this email · here's the preview it had"
    const val NO_TEXT = "(No text in this email)"
    /** The most of the plain text shown (the rest is in the mail app). */
    const val MAX_TEXT = 60_000

    /** "Tue 20 Oct · 14:02" (today: "Today · 14:02"). */
    fun whenLine(atMs: Long, nowMs: Long, cal: LocalCalendar): String {
        val day = cal.epochDayOf(atMs)
        val today = cal.epochDayOf(nowMs)
        val m = cal.minuteOfDay(atMs)
        val time = "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
        val d = when (day) {
            today -> "Today"
            today - 1 -> "Yesterday"
            else -> CivilDate.shortLabel(day)
        }
        return "$d · $time"
    }

    /** "240 KB", "1.2 MB", "900 bytes". */
    fun size(bytes: Long): String = when {
        bytes < 1_000 -> "$bytes bytes"
        bytes < 1_000_000 -> "${(bytes + 500) / 1_000} KB"
        else -> {
            val tenths = (bytes + 50_000) / 100_000
            "${tenths / 10}.${tenths % 10} MB"
        }
    }

    /** "PDF", "Word", "Picture"… from the type or the name's ending; null when it can't be told. */
    fun kind(type: String?, name: String): String? {
        val t = type?.lowercase().orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            t == "application/pdf" || ext == "pdf" -> "PDF"
            t.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "heic", "webp") -> "Picture"
            ext in setOf("doc", "docx") || t.contains("wordprocessing") || t == "application/msword" -> "Word"
            ext in setOf("xls", "xlsx", "csv") || t.contains("spreadsheet") || t == "application/vnd.ms-excel" -> "Spreadsheet"
            ext in setOf("ppt", "pptx") || t.contains("presentation") -> "Slides"
            ext == "ics" || t == "text/calendar" -> "Calendar invite"
            ext == "zip" || t == "application/zip" -> "Zip"
            t.startsWith("audio/") -> "Audio"
            t.startsWith("video/") -> "Video"
            ext == "txt" || t == "text/plain" -> "Text"
            else -> null
        }
    }

    fun attachmentRows(list: List<MailAttachmentFacts>): List<MailAttachmentRow> =
        list.mapIndexed { i, a ->
            val name = a.name.ifBlank { "Attachment" }
            MailAttachmentRow(name, listOfNotNull(size(a.bytes), kind(a.type, a.name)).joinToString(" · "), i, MailAttachmentRules.plan(name, a.type, a.bytes), a.type, a.bytes)
        }

    private fun people(label: String, list: List<MailPerson>): String? {
        if (list.isEmpty()) return null
        val shown = list.take(8).joinToString(", ") { it.label }
        return "$label $shown" + if (list.size > 8) " and ${list.size - 8} more" else ""
    }

    /**
     * The web view's whole page for safe [html]: a CSP that loads nothing (pictures only when [images]), no scripts,
     * the email on white paper as it was designed (in Dark too, like a printed letter), pictures and tables kept inside
     * the width, held-back pictures hidden.
     */
    fun page(html: String, images: Boolean): String {
        val img = if (images) "data: https: http:" else "data:"
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src $img; font-src data:; form-action 'none'; base-uri 'none'\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<meta name=\"color-scheme\" content=\"light\">" +
            "<style>html,body{margin:0;padding:0;background:#ffffff;color:#1b1b1b}" +
            "body{padding:16px;font:15px/1.45 -apple-system,system-ui,Roboto,sans-serif;overflow-wrap:anywhere;word-wrap:break-word}" +
            "img{max-width:100%;height:auto}table{max-width:100%}pre{white-space:pre-wrap}a{color:#8a6a1f}" +
            "img[data-blocked]{display:none}</style></head><body>" + html + "</body></html>"
    }

    /**
     * The reader from the server's answer, or (when the email couldn't be read) from what Navilife already had: the
     * sender, subject and preview of the card, with the reason.
     */
    fun view(
        r: MailMessageAnswer?, fallback: MailReaderFallback, nowMs: Long, cal: LocalCalendar, images: Boolean,
        source: MailReaderSource? = null,
    ): MailReaderView {
        val ok = r?.state == MailMessageAnswer.OK
        val problem = when {
            r == null -> OFFLINE
            ok -> null
            r.state == MailMessageAnswer.GONE -> GONE
            r.state == MailMessageAnswer.RECONNECT -> RECONNECT
            r.state == MailMessageAnswer.UNKNOWN -> UNKNOWN
            else -> FAILED
        }
        val atMs = if (ok && r!!.atMs > 0) r.atMs else fallback.atMs
        val from = if (ok) r!!.from.name?.trim()?.takeIf { it.isNotEmpty() } ?: r.from.address ?: fallback.from else fallback.from
        val address = if (ok) r!!.from.address?.trim()?.takeIf { it.isNotEmpty() && !it.equals(from, true) } else null
        val subject = (if (ok) r!!.subject else null)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback.subject.ifBlank { MailTidiedRules.NO_SUBJECT }
        val html = if (ok) r!!.html?.takeIf { it.isNotBlank() } else null
        val rawText = if (ok) r!!.text?.takeIf { it.isNotBlank() } ?: html?.let { MailHtmlSanitizer.text(it) } else fallback.preview
        val text = rawText?.let { MailHtmlSanitizer.tidyText(it) }?.takeIf { it.isNotEmpty() }?.let { if (it.length > MAX_TEXT) it.take(MAX_TEXT) + "…" else it } ?: NO_TEXT
        val blocked = if (ok) r!!.blockedImages else 0
        val attachments = if (ok) attachmentRows(r!!.attachments) else emptyList()
        val attachmentsLine = when (attachments.size) {
            0 -> null
            1 -> "1 attachment"
            else -> "${attachments.size} attachments"
        }
        val account = fallback.account
        val note = if (ok) "Read from your $account mail just now · Navilife doesn't keep it · reading it here doesn't mark it read"
            else "Navilife keeps only this preview of your $account mail"
        val toLine = if (ok) people("To", r!!.to) else null
        val ccLine = if (ok) people("Cc", r!!.cc) else null
        val whenLine = whenLine(atMs, nowMs, cal)
        val spoken = listOfNotNull(
            "Email from $from" + (address?.let { ", $it" } ?: ""), subject, whenLine, "$account account", toLine, ccLine,
            attachmentsLine, problem,
        ).joinToString(". ")
        return MailReaderView(
            from = from, fromAddress = address, toLine = toLine, ccLine = ccLine, whenLine = whenLine, account = account,
            subject = subject, page = html?.let { page(it, images) }, text = text, attachments = attachments,
            attachmentsLine = attachmentsLine,
            imagesLine = when {
                images && ok && html != null -> null
                blocked == 1 -> "1 picture hidden to stop tracking"
                blocked > 1 -> "$blocked pictures hidden to stop tracking"
                else -> null
            },
            canShowImages = ok && !images && blocked > 0,
            note = note, problem = problem, spoken = spoken, source = if (ok) source else null,
        )
    }
}

/** What the reader falls back on: the card's own sender, subject, preview, time and account. */
data class MailReaderFallback(val from: String, val subject: String, val preview: String?, val atMs: Long, val account: String)

/** The server's answer for one email (the wire's [os.meka.core.wire] codec reads it into this). */
data class MailMessageAnswer(
    val state: String,
    val from: MailPerson = MailPerson(null, null),
    val to: List<MailPerson> = emptyList(),
    val cc: List<MailPerson> = emptyList(),
    val atMs: Long = 0,
    val subject: String? = null,
    /** Already sanitised by the server ([MailHtmlSanitizer]). */
    val html: String? = null,
    val text: String? = null,
    val attachments: List<MailAttachmentFacts> = emptyList(),
    val blockedImages: Int = 0,
) {
    companion object {
        const val OK = "ok"
        /** No longer in the mailbox (deleted for good, or moved where Navilife can't follow). */
        const val GONE = "gone"
        /** The handle isn't one of this household's emails. */
        const val UNKNOWN = "unknown"
        /** The mail account's sign-in has ended. */
        const val RECONNECT = "reconnect"
        /** The provider couldn't be read just now (or mail isn't set up on the server). */
        const val FAILED = "failed"
        val STATES = setOf(OK, GONE, UNKNOWN, RECONNECT, FAILED)
    }
}
