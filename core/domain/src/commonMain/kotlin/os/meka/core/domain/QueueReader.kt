package os.meka.core.domain

/**
 * Train-time queue, slice 2: **an article's words saved on the phone for reading offline**. Non-AI, pure.
 *
 * When an article is saved for the train (and again whenever the queue is looked at), the Fold itself fetches the page
 * over Wi-Fi, as its browser would — nothing goes through MEKA's server and nothing is shared with anyone — and these
 * rules keep only the readable words: the page's title, its headings and paragraphs, in order, from the `<article>`
 * (else `<main>`, else the body) with scripts, menus, headers, footers, asides and forms dropped. The words are kept
 * as a device-local value of the phone's store (never synced, never sent to MEKA's AI: a page is other people's words,
 * ADR-006), so the queue reads them on the train without a signal. Podcasts aren't fetched: their own app downloads
 * episodes. A page with too little to read (an app shell, a paywall) isn't kept and opens online as before.
 */
data class ReaderBlock(val text: String, val heading: Boolean)

/** One saved page as the reader shows it. */
data class ReaderPage(
    val title: String,
    val blocks: List<ReaderBlock>,
    val words: Int,
    /** "6 min read". */
    val minutes: String,
    /** True when the page was longer than [ReaderRules.MAX_TEXT] and the rest is left online. */
    val cut: Boolean,
    val savedAtMs: Long = 0L,
)

/** A waiting article whose words the phone should fetch now. */
data class QueueFetch(val id: String, val url: String)

/**
 * Train-time queue completeness (2026-10-18): how often this device tried to keep a page's words and couldn't (the
 * fetch failed, or a paywall or an app shell left too little to read), and when it last tried. Device-local.
 */
data class QueueFailure(val count: Int, val atMs: Long)

object ReaderRules {
    /** The most of a page's HTML read (characters); the rest is ignored. */
    const val MAX_HTML = 2_000_000
    /** The most of a page's words kept (characters of text). */
    const val MAX_TEXT = 80_000
    /** Less than this to read and the page isn't kept (an app shell, a paywall's teaser). */
    const val MIN_TEXT = 400
    const val MIN_PARAGRAPH = 40
    const val WORDS_PER_MINUTE = 230
    /** How long after a failed fetch the phone tries that page again. */
    const val RETRY_MS = 6 * 3_600_000L
    /** At most this many pages fetched in one go. */
    const val BATCH = 5
    /** The storage cap: at most this many articles' words kept on the device at once (each at most [MAX_TEXT]). */
    const val MAX_SAVED = 30
    /** After this many failed tries a page is left online for good (a paywall, a page that needs its app). */
    const val GIVE_UP = 3

    /** A waiting article whose words couldn't be kept yet: it opens online, and the phone tries again later. */
    const val ONLINE_FOR_NOW = "Online only for now · tries again on Wi-Fi later"
    /** A waiting article tried [GIVE_UP] times: it opens online, and the phone stops trying. */
    const val ONLINE_ONLY = "Online only · this page can't be kept to read offline"

    const val SAVED = "saved to read offline"
    const val CUT_LINE = "The rest of this article is online."
    const val OPEN_ONLINE = "Open online ↗"

    private val DROP = Regex(
        """<(script|style|noscript|svg|nav|header|footer|aside|form|button|iframe|template|figure|select|canvas)\b[^>]*>[\s\S]*?</\1\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val COMMENT = Regex("""<!--[\s\S]*?-->""")
    private val BLOCK = Regex("""<(p|h1|h2|h3|h4|li|blockquote|pre)\b[^>]*>([\s\S]*?)</\1\s*>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]*>""")
    private val SPACE = Regex("""\s+""")
    private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});""")
    private val TITLE = Regex("""<title\b[^>]*>([\s\S]*?)</title\s*>""", RegexOption.IGNORE_CASE)
    private val META = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    /** Lines that are a site's furniture rather than the article. */
    private val FURNITURE = listOf(
        "advertisement", "sign up", "subscribe", "copyright", "©", "share this", "read more", "related:", "follow us",
        "accept cookies", "we use cookies", "this site uses cookies", "image source", "image caption", "getty images",
    )

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "rsquo" to "’",
        "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”", "ndash" to "–", "mdash" to "—", "hellip" to "…", "pound" to "£",
        "euro" to "€", "copy" to "©", "reg" to "®", "trade" to "™", "middot" to "·", "bull" to "•", "laquo" to "«",
        "raquo" to "»", "eacute" to "é", "egrave" to "è", "aacute" to "á", "iacute" to "í", "oacute" to "ó",
        "uacute" to "ú", "ntilde" to "ñ", "ccedil" to "ç", "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "times" to "×",
        "deg" to "°", "frac12" to "½", "shy" to "", "zwj" to "", "zwnj" to "", "thinsp" to " ", "ensp" to " ", "emsp" to " ",
    )

    /** `&amp;` → `&`, `&#8217;` → `’`; unknown entities stay as written. */
    fun decode(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let(::codePoint) ?: m.value
            else -> NAMED[e] ?: NAMED[e.lowercase()] ?: m.value
        }
    }

    private fun codePoint(cp: Int): String? = when {
        cp == 0 || cp in 0xD800..0xDFFF || cp > 0x10FFFF -> null
        cp < 0x10000 -> cp.toChar().toString()
        else -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }

    /** Markup to plain words on one line. */
    fun text(html: String): String = decode(TAG.replace(html, " ")).replace(SPACE, " ").trim()

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(tag)
            ?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }

    /** The page's own title: its `og:title`, else `<title>`, else null. */
    fun title(html: String): String? {
        val og = META.findAll(html).firstOrNull { m ->
            attr(m.value, "property")?.equals("og:title", true) == true || attr(m.value, "name")?.equals("og:title", true) == true
        }?.let { attr(it.value, "content") }?.let(::text)?.takeIf { it.isNotEmpty() }
        return og ?: TITLE.find(html)?.groupValues?.get(1)?.let(::text)?.takeIf { it.isNotEmpty() }
    }

    /** The part of the page the article is in: the longest `<article>`, else `<main>`, else the body, else all. */
    fun scope(html: String): String {
        fun inner(tag: String): List<String> =
            Regex("""<$tag\b[^>]*>([\s\S]*?)</$tag\s*>""", RegexOption.IGNORE_CASE).findAll(html).map { it.groupValues[1] }.toList()
        inner("article").maxByOrNull { it.length }?.takeIf { text(it).length >= MIN_TEXT }?.let { return it }
        inner("main").maxByOrNull { it.length }?.takeIf { text(it).length >= MIN_TEXT }?.let { return it }
        val body = Regex("""<body\b[^>]*>""", RegexOption.IGNORE_CASE).find(html)
        return if (body != null) html.substring(body.range.last + 1) else html
    }

    private fun furniture(t: String): Boolean {
        val l = t.lowercase()
        return t.length < 160 && FURNITURE.any { l.startsWith(it) || (t.length < 80 && l.contains(it)) }
    }

    /** "1 min read" · "6 min read". */
    fun minutes(words: Int): String = "${maxOf(1, (words + WORDS_PER_MINUTE - 1) / WORDS_PER_MINUTE)} min read"

    /**
     * The readable words of a fetched page, or null when there's too little to read. [fallbackTitle] (the queue's
     * title) is used when the page names none.
     */
    fun extract(html: String, fallbackTitle: String): ReaderPage? {
        val clean = DROP.replace(COMMENT.replace(html.take(MAX_HTML), " "), " ")
        val title = title(html.take(MAX_HTML))?.take(QueueRules.MAX_TITLE) ?: fallbackTitle
        val blocks = ArrayList<ReaderBlock>()
        var chars = 0
        var cut = false
        for (m in BLOCK.findAll(scope(clean))) {
            val tag = m.groupValues[1].lowercase()
            val t = text(m.groupValues[2])
            val heading = tag.length == 2 && tag[0] == 'h'
            val keep = when {
                t.isEmpty() || furniture(t) -> false
                heading -> t.length in 2..160 && !(tag == "h1" && t.equals(title, ignoreCase = true))
                else -> t.length >= MIN_PARAGRAPH
            }
            if (!keep || blocks.lastOrNull()?.text == t) continue
            if (chars + t.length > MAX_TEXT) { cut = true; break }
            blocks += ReaderBlock(t, heading)
            chars += t.length
        }
        while (blocks.lastOrNull()?.heading == true) blocks.removeAt(blocks.lastIndex)
        val paragraphs = blocks.filter { !it.heading }
        if (paragraphs.size < 2 || paragraphs.sumOf { it.text.length } < MIN_TEXT) return null
        val words = blocks.sumOf { b -> b.text.split(' ').count { it.isNotEmpty() } }
        return ReaderPage(title, blocks, words, minutes(words), cut)
    }

    /** The row's extra line once saved: "6 min read · saved to read offline". */
    fun rowLine(page: ReaderPage): String = "${page.minutes} · $SAVED"

    /**
     * The waiting articles to fetch now: not yet saved, not podcasts, http(s) only, not tried and failed in the last
     * [RETRY_MS], and not given up on ([GIVE_UP] failures). Newest first, at most [BATCH], and never more than
     * [MAX_SAVED] kept at once (the storage cap: the rest wait for room, made as articles are done or removed).
     */
    fun toFetch(items: List<QueueItem>, saved: Set<String>, failures: Map<String, QueueFailure>, nowMs: Long): List<QueueFetch> {
        val room = minOf(BATCH, MAX_SAVED - saved.size)
        if (room <= 0) return emptyList()
        return items.asSequence()
            .filter { !it.done && it.kind == QueueKind.ARTICLE && it.id !in saved }
            .filter { it.url.startsWith("https://", true) || it.url.startsWith("http://", true) }
            .filter { i -> failures[i.id]?.let { it.count < GIVE_UP && nowMs - it.atMs >= RETRY_MS } ?: true }
            .take(room).map { QueueFetch(it.id, it.url) }.toList()
    }

    /** The waiting links: the only ones whose words (and failed tries) the device keeps. */
    private fun waitingIds(items: List<QueueItem>): Set<String> = items.filter { !it.done }.mapTo(HashSet()) { it.id }

    /**
     * Saved pages to drop: their link was removed or marked done (the offline copy goes once it's read; Back fetches it
     * again on Wi-Fi), so only waiting articles take room on the phone.
     */
    fun stale(saved: Set<String>, items: List<QueueItem>, nowMs: Long): Set<String> {
        val kept = waitingIds(items)
        return saved.filterTo(HashSet()) { it !in kept }
    }

    /** One more failed try for a page. */
    fun failed(before: QueueFailure?, nowMs: Long): QueueFailure = QueueFailure((before?.count ?: 0) + 1, nowMs)

    /** Failed tries worth keeping: only for links still waiting. */
    fun liveFailures(failures: Map<String, QueueFailure>, items: List<QueueItem>): Map<String, QueueFailure> {
        val kept = waitingIds(items)
        return failures.filterKeys { it in kept }
    }

    /** The row's line for a waiting article whose words aren't kept: "Online only for now · …" or "Online only · …". */
    fun onlineLine(f: QueueFailure?): String? = when {
        f == null || f.count <= 0 -> null
        f.count >= GIVE_UP -> ONLINE_ONLY
        else -> ONLINE_FOR_NOW
    }

    /** Each waiting article not saved on this device that failed at least once, with its [onlineLine]. */
    fun onlineLines(items: List<QueueItem>, saved: Set<String>, failures: Map<String, QueueFailure>): Map<String, String> =
        items.filter { !it.done && it.kind == QueueKind.ARTICLE && it.id !in saved }
            .mapNotNull { i -> onlineLine(failures[i.id])?.let { i.id to it } }.toMap()
}

/**
 * A saved page as the device keeps it (one device-local string per page): a header line, the title, when it was saved,
 * whether it was cut, then one line per block ("# " a heading, "p " a paragraph). Anything unreadable is no page.
 */
object ReaderCodec {
    private const val HEAD = "meka-page/1"
    const val INDEX_KEY = "queue.pages"
    /** The failed tries ([QueueFailure]): one "id<TAB>count<TAB>last try ms" per line. */
    const val FAILED_KEY = "queue.failed"
    fun key(id: String) = "queue.page.$id"

    fun encode(p: ReaderPage): String = buildString {
        append(HEAD).append('\n')
        append(p.title.replace('\n', ' ')).append('\n')
        append(p.savedAtMs).append('\n')
        append(if (p.cut) "cut" else "whole").append('\n')
        p.blocks.forEach { b -> append(if (b.heading) "# " else "p ").append(b.text.replace('\n', ' ')).append('\n') }
    }

    fun decode(s: String?): ReaderPage? {
        val lines = s?.split('\n') ?: return null
        if (lines.size < 4 || lines[0] != HEAD) return null
        val savedAt = lines[2].toLongOrNull() ?: return null
        val blocks = lines.drop(4).mapNotNull { l ->
            when {
                l.startsWith("# ") -> ReaderBlock(l.substring(2), true)
                l.startsWith("p ") -> ReaderBlock(l.substring(2), false)
                else -> null
            }
        }
        if (blocks.isEmpty()) return null
        val words = blocks.sumOf { b -> b.text.split(' ').count { it.isNotEmpty() } }
        return ReaderPage(lines[1], blocks, words, ReaderRules.minutes(words), lines[3] == "cut", savedAt)
    }

    /** The index of saved pages: one "id<TAB>minutes line" per line. */
    fun encodeIndex(index: Map<String, String>): String =
        index.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" }

    fun decodeIndex(s: String?): Map<String, String> = s.orEmpty().split('\n').mapNotNull { l ->
        val tab = l.indexOf('\t')
        if (tab <= 0) null else l.substring(0, tab) to l.substring(tab + 1)
    }.toMap()

    fun encodeFailures(failures: Map<String, QueueFailure>): String =
        failures.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value.count}\t${it.value.atMs}" }

    fun decodeFailures(s: String?): Map<String, QueueFailure> = s.orEmpty().split('\n').mapNotNull { l ->
        val parts = l.split('\t')
        if (parts.size != 3 || parts[0].isEmpty()) return@mapNotNull null
        val count = parts[1].toIntOrNull() ?: return@mapNotNull null
        val at = parts[2].toLongOrNull() ?: return@mapNotNull null
        parts[0] to QueueFailure(count, at)
    }.toMap()
}
