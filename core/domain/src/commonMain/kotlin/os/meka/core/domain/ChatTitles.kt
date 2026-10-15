package os.meka.core.domain

/**
 * A chat's name as the person sees it in the app, without the count a messaging app adds to a bundled notification
 * (Fold review 2026-10-12, bug 1: WhatsApp titles a group's notification "WTFC U7s (2 messages)" once a second message
 * arrives, so the same group showed as two cards). Non-AI and pure; read by hand (no regex) so the JVM and
 * Kotlin/Native read it the same.
 */
object ChatTitleRules {

    /**
     * "WTFC U7s (2 messages)" → "WTFC U7s"; also "(3 new messages)", "WTFC U7s: 3 new messages", "WTFC U7s · 2 new
     * messages" and "WTFC U7s - 2 messages". A name that is only a count, or has nothing after the cut, is kept as it
     * came (trimmed, runs of spaces made one).
     */
    fun clean(name: String): String {
        val t = squash(name)
        if (t.isEmpty()) return t
        var out = t
        // "(2 messages)" at the end.
        if (out.endsWith(")")) {
            val open = out.lastIndexOf('(')
            if (open > 0 && isCount(out.substring(open + 1, out.length - 1))) out = out.substring(0, open).trimEnd()
        }
        // "…: 3 new messages", "… · 3 new messages", "… - 3 messages", "… – 3 messages".
        for (sep in SEPARATORS) {
            val i = out.lastIndexOf(sep)
            if (i > 0 && isCount(out.substring(i + sep.length))) {
                out = out.substring(0, i).trimEnd()
                break
            }
        }
        return out.ifEmpty { t }
    }

    /** [clean] for a nullable name; null or blank stays null. */
    fun cleanOrNull(name: String?): String? = name?.let { clean(it) }?.takeIf { it.isNotEmpty() }

    private val SEPARATORS = listOf(": ", " · ", " - ", " – ")

    /** "2 messages", "1 message", "3 new messages", "12 New Messages". */
    internal fun isCount(s: String): Boolean {
        val words = squash(s).lowercase().split(' ')
        if (words.size !in 2..3) return false
        if (words[0].isEmpty() || !words[0].all { it in '0'..'9' }) return false
        if (words.size == 3 && words[1] != "new") return false
        return words.last() == "message" || words.last() == "messages"
    }

    private fun squash(s: String): String {
        val b = StringBuilder()
        var space = false
        for (c in s.trim()) {
            if (c.isWhitespace()) {
                if (!space) b.append(' ')
                space = true
            } else {
                b.append(c)
                space = false
            }
        }
        return b.toString()
    }
}
