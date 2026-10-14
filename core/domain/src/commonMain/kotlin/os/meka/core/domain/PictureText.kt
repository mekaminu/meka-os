package os.meka.core.domain

/**
 * Screenshots and photos that do something (build plan V1, slice 1) and Photo of a letter → task: a screenshot or
 * photo shared to Navilife, or a letter photographed from the capture sheet, is read **on the device** (ML Kit's
 * bundled text recognition on the Fold, Vision on the Mac; Needs Meka #23's yes). Only the words it found go on, into
 * the same paths shared text takes (the parking timer, Handle this, the field), and only on his tap; the picture itself
 * is never sent, uploaded or kept.
 *
 * Pure and shared by both apps: how big a picture is read, how the recogniser's lines are tidied (a screenshot's status
 * bar, page furniture and lone symbols dropped), and the lines the sheet shows.
 */
object PictureTextRules {
    /** A picture bigger than this on disk isn't opened (a camera's RAW or a huge scan); the sheet says so. */
    const val MAX_BYTES = 40L * 1024 * 1024
    /** The long side the picture is shrunk to before it's read: enough for a letter's small print, light on memory. */
    const val READ_MAX_PX = 2_400
    /** Reading gives up after this long (a very busy picture on a slow phone). */
    const val READ_MS = 8_000L
    /** At most this much of what was read is kept for the sheet (Handle this cuts its own share further). */
    const val MAX_TEXT = 8_000
    /** A line needs at least this many letters to stay: "14:02", "85%", "4G", "•" and "‹" go. */
    const val MIN_LINE_LETTERS = 2
    /** What was read needs at least this many letters to be worth showing (as Handle this's own floor). */
    const val MIN_LETTERS = HandleThisRules.MIN_LETTERS

    const val READING_LINE = "Reading the picture on this phone…"
    const val READING_LINE_MAC = "Reading the picture on this Mac…"
    const val NOTHING_LINE = "No words in that picture · type it instead"
    const val TOO_BIG_LINE = "That picture is too big to read here · crop it or take a screenshot of the part that matters"
    const val COULD_NOT_OPEN_LINE = "Navilife couldn't open that picture · try sharing it again"
    const val TOOK_TOO_LONG_LINE = "That picture took too long to read · crop it to the part with the words"
    /** The capture sheet's button for a letter or a sign; the camera app takes it, nothing lands in the gallery. */
    const val PHOTO_ACTION = "Photo of a letter"
    /** The Mac's Read the copied picture with no picture on the clipboard. */
    const val NO_COPIED_LINE = "No picture on the clipboard · copy a screenshot with ⌃⇧⌘4 first"
    const val NO_CAMERA_LINE = "No camera app on this phone to take it · share a photo to Navilife instead"
    const val PHOTO_LABEL = "Photograph a letter, ticket or sign: Navilife reads it on this phone"
    /** Under the field once read: where the words came from, and that the picture is gone. */
    const val NOT_KEPT = "the picture isn't kept"

    /** Kinds of picture, for the one line under the field. */
    enum class Source { SHARED, CAMERA, MAC }

    /** [w]×[h] shrunk (never grown) so its long side is at most [maxPx]; each side at least 1. */
    fun readSize(w: Int, h: Int, maxPx: Int = READ_MAX_PX): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 1 to 1
        val long = maxOf(w, h)
        if (long <= maxPx) return w to h
        val scale = maxPx.toDouble() / long
        return maxOf(1, (w * scale).toInt()) to maxOf(1, (h * scale).toInt())
    }

    /** Whether a picture of [bytes] (null: unknown, as from some apps' shares) is opened at all. */
    fun tooBig(bytes: Long?): Boolean = bytes != null && bytes > MAX_BYTES

    private val spaces = Regex("""[ \t ]+""")
    private val control = Regex("""[\u0000-\u0008\u000B-\u001F\u007F]""")

    /** Status-bar and app-chrome lines a screenshot carries on its own: they're words, but never his. */
    private val chrome = setOf(
        "lte", "5g", "4g", "3g", "wifi", "wi-fi", "vo lte", "volte", "back", "share", "edit", "done", "cancel", "more",
        "search", "menu", "home", "close", "reply", "forward", "delete", "archive", "inbox", "options",
    )

    private fun letters(s: String): Int = s.count { it.isLetter() }

    /**
     * The recogniser's [raw] lines tidied: spaces folded, control characters gone, lines with fewer than
     * [MIN_LINE_LETTERS] letters (a clock, a battery, a bullet) and bare app-chrome words dropped, a line repeated
     * straight after itself kept once, blank runs folded, cut at [MAX_TEXT] on a line. Empty when nothing is left.
     */
    fun tidy(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val out = ArrayList<String>()
        var blank = false
        var size = 0
        for (line0 in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = line0.replace(control, " ").replace(spaces, " ").trim()
            if (line.isEmpty()) { blank = out.isNotEmpty(); continue }
            if (letters(line) < MIN_LINE_LETTERS) continue
            if (line.lowercase().trim('.', ':', '›', '‹', '<', '>', ' ') in chrome) continue
            if (out.lastOrNull() == line) continue
            val add = (if (blank && out.isNotEmpty()) 1 else 0) + line.length + (if (out.isEmpty()) 0 else 1)
            if (size + add > MAX_TEXT) break
            if (blank && out.isNotEmpty()) out += ""
            out += line
            size += add
            blank = false
        }
        return out.joinToString("\n").trim()
    }

    /** Whether the tidied [text] has enough in it to offer anything (else [NOTHING_LINE]). */
    fun worthOffering(text: String): Boolean = letters(text) >= MIN_LETTERS

    /** Words in [text], for the line under the field. */
    fun wordCount(text: String): Int = text.split(Regex("""\s+""")).count { w -> w.any { it.isLetterOrDigit() } }

    /**
     * The quiet line under the field once read: "Read 84 words from the picture · the picture isn't kept" (a
     * camera photo: "from the photo"; one word: "1 word").
     */
    fun readLine(text: String, source: Source): String {
        val n = wordCount(text)
        val what = when (source) {
            Source.CAMERA -> "the photo"
            Source.SHARED, Source.MAC -> "the picture"
        }
        return "Read ${if (n == 1) "1 word" else "$n words"} from $what · $NOT_KEPT"
    }
}
