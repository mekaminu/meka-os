package os.meka.core.domain

/**
 * "Handle this" (build plan V1, slice 1): Meka shares an email, a message or any text with MEKA and asks it to handle
 * it. Non-AI and pure: whether a share is worth reading, the one text that goes out (cut to size), the share's own
 * message id (so sharing the same text twice the same day makes no second card), which of the model's kinds may come
 * back, and the lines the sheet shows.
 *
 * What was shared is untrusted (ADR-006): it only ever becomes request cards (the same cards a watched person's
 * message makes, with "You shared · 21:40" for who asked), and nothing is added, sent, paid or opened until Meka taps.
 * Shared cards wait in Needs you on both apps but never raise a notification of their own: Meka is looking at them.
 */
object HandleThisRules {
    /** The share's message id starts with this; [MessageRequestRules.card] reads it for the "You shared" line. */
    const val MESSAGE_PREFIX = "share:"
    /** Who asked, on a shared card's stored `person` field. */
    const val PERSON = "You shared"

    const val ACTION = "Handle this"
    const val READING_LINE = "Reading it…"
    const val NOTHING_LINE = "Nothing in that for you to do · add it as a task instead"
    const val ALREADY_LINE = "Already handled · anything still open is in Needs you"
    const val LEFT_LINE = "Anything you leave waits in Needs you"
    const val DONE_LINE = "Handled"

    /** At most this much of the shared text goes out (the wire refuses more). */
    const val MAX_TEXT = 6_000
    const val MAX_SUBJECT = 200
    /** A share needs at least this many letters outside its links to be worth reading. */
    const val MIN_LETTERS = 12

    /** The model's kinds a share may become: something to do, to be at, or to remember (never a work-from-home day). */
    val KINDS = setOf(RequestKind.TASK.wire, RequestKind.EVENT.wire, RequestKind.REMINDER.wire)

    private val link = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /** Whether a card came from a share rather than someone's message. */
    fun isShared(messageId: String): Boolean = messageId.startsWith(MESSAGE_PREFIX)

    /**
     * Whether [text] (and [subject]) are worth sending at all: words beyond a bare link. MEKA never opens a link, so a
     * link on its own has nothing in it to read.
     */
    fun worthAsking(text: String, subject: String?): Boolean {
        val words = (subject.orEmpty() + " " + text).replace(link, " ")
        return words.count { it.isLetter() } >= MIN_LETTERS
    }

    /** The text as it goes out: control characters gone, runs of blank lines folded, cut at [MAX_TEXT] on a word. */
    fun outgoingText(text: String): String {
        val t = text.replace("\r\n", "\n").replace(Regex("""[\u0000-\u0008\u000B-\u001F\u007F]"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n").trim()
        if (t.length <= MAX_TEXT) return t
        val cut = t.take(MAX_TEXT)
        val at = cut.lastIndexOf(' ').takeIf { it >= MAX_TEXT - 200 } ?: MAX_TEXT
        return cut.take(at).trimEnd()
    }

    /** The subject as it goes out: one line, at most [MAX_SUBJECT] characters; "" when there is none. */
    fun outgoingSubject(subject: String?): String =
        subject.orEmpty().replace(Regex("""\s+"""), " ").trim().take(MAX_SUBJECT).trim()

    /**
     * The share's message id: the same text shared again on the same [day] is the same message (its cards aren't
     * made twice); on another day it is read afresh.
     */
    fun messageId(text: String, subject: String?, day: Long): String =
        MESSAGE_PREFIX + ActivityRules.fnv64(outgoingSubject(subject) + "\n" + outgoingText(text)) + ":" + day

    /** What a shared card quotes: "Subject · first words of the text". */
    fun quoteText(text: String, subject: String?): String {
        val s = outgoingSubject(subject)
        val body = outgoingText(text).replace(Regex("""\s+"""), " ").trim()
        return listOf(s, body).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** The model's proposals that a share may become, before [MessageRequestRules.check] reads them. */
    fun allowed(raw: List<RawRequestProposal>): List<RawRequestProposal> = raw.filter { it.kind in KINDS }

    /** "1 thing to add" · "3 things to add". */
    fun readyLine(count: Int): String = if (count == 1) "1 thing to add" else "$count things to add"
}
