package os.meka.core.domain

/**
 * Talking to Ada like a conversation, slice 5 (Meka 2026-10-21 20:32: "it's not like a natural conversation and it's
 * not chatty, feels weird"): what Ada's spoken words look like. A spoken turn is one to three short sentences, like a
 * person on the phone, with no lists, headings or "Here are some options:", and under [MAX_WORDS] words unless he
 * asked for detail. The server asks for that in the spoken-conversation prompt and [tidy]s an answer that came back
 * otherwise, so what is said never reads out list markers or runs on. Pure, plain loops (ADR-011); nothing is stored.
 */
object SpokenTurnRules {
    /** A spoken turn's length when he didn't ask for detail (about ten seconds aloud). */
    const val MAX_WORDS = 40

    /** Room past [MAX_WORDS] for a closing question that hands the conversation back ("Want me to add it?"). */
    const val HAND_BACK_SLACK = 12

    /** What he says when he wants more than a quick spoken turn. */
    private val DETAIL = listOf(
        "in detail", "more detail", "the details", "tell me more", "go on", "carry on", "keep going", "explain",
        "walk me through", "talk me through", "step by step", "elaborate", "break it down", "go into", "the full story",
        "the whole story", "in depth", "properly", "the long version", "everything you know",
    )

    /** Openings of a line that only announces a list ("Here are some options:"), never said. */
    private val LEAD_INS = listOf(
        "here are", "here's", "here is", "these are", "there are a few", "a few options", "some options", "options",
        "the options", "a couple of options", "some ideas", "a few ideas", "ideas",
    )

    /** Whether [question] asks for more than a quick spoken turn. */
    fun wantsDetail(question: String): Boolean {
        val q = " " + question.lowercase().replace('’', '\'') + " "
        return DETAIL.any { q.contains(" $it") }
    }

    /** Words in [text] (runs of non-space). */
    fun wordCount(text: String): Int = text.split(' ', '\n', '\t').count { it.isNotBlank() }

    /** Whether [text] reads like a written answer, not talk: a line that is a list item or a heading, or markdown. */
    fun isListy(text: String): Boolean {
        if ("**" in text || "__" in text || "`" in text) return true
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return lines.any { isItem(it) || it.startsWith("#") } || lines.dropLast(1).any { isLeadIn(it) }
    }

    /**
     * [words] as they should be said for [question]: list items and headings turned into plain sentences, a line that
     * only announces a list left out, markdown dropped; then, unless he asked for detail, whole sentences from the
     * start up to [MAX_WORDS] words (always the first), keeping a last question that hands back within
     * [HAND_BACK_SLACK] more. A look-up of his own day ([cut] false) keeps every sentence, so no event or task is lost
     * (the device still says at most three). The first sentence is never changed when it was plain, so what was
     * already said stays.
     */
    fun tidy(words: String, question: String, cut: Boolean = true): String {
        val lines = words.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return words.trim()
        val kept = lines.filterIndexed { i, l ->
            val heading = l.startsWith("#") || (l.startsWith("**") && l.endsWith("**") && wordCount(l) <= 6)
            !(i < lines.lastIndex && (heading || isLeadIn(l)))
        }.ifEmpty { lines }
        val plain = kept.joinToString(" ") { l ->
            val bare = unmark(stripItem(l))
            if (bare.isNotEmpty() && kept.size > 1 && bare.last() !in ".!?:…,;") "$bare." else bare
        }.let { collapse(it) }
        if (!cut || wantsDetail(question) || wordCount(plain) <= MAX_WORDS) return plain
        val sentences = sentences(plain)
        val out = mutableListOf<String>()
        var count = 0
        for (s in sentences) {
            val n = wordCount(s)
            if (out.isNotEmpty() && count + n > MAX_WORDS) break
            out += s
            count += n
        }
        val last = sentences.last()
        if (out.size < sentences.size && last.endsWith("?") && count + wordCount(last) <= MAX_WORDS + HAND_BACK_SLACK) out += last
        return out.joinToString(" ")
    }

    private fun isItem(line: String): Boolean {
        if (line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* ") || line.startsWith("– ")) return true
        var i = 0
        while (i < line.length && line[i].isDigit()) i++
        return i in 1..2 && i + 1 < line.length && (line[i] == '.' || line[i] == ')') && line[i + 1] == ' '
    }

    private fun stripItem(line: String): String {
        if (!isItem(line)) return line
        if (!line[0].isDigit()) return line.substring(2).trim()
        var i = 0
        while (line[i].isDigit()) i++
        return line.substring(i + 2).trim()
    }

    private fun isLeadIn(line: String): Boolean {
        val l = unmark(line).lowercase().replace('’', '\'')
        return l.endsWith(":") && wordCount(l) <= 10 && LEAD_INS.any { l.startsWith(it) }
    }

    private fun unmark(s: String): String = s.replace("**", "").replace("__", "").replace("`", "").trimStart('#', ' ').trim()

    private fun collapse(s: String): String {
        val b = StringBuilder()
        var space = false
        for (c in s) {
            if (c.isWhitespace()) { space = true; continue }
            if (space && b.isNotEmpty()) b.append(' ')
            space = false
            b.append(c)
        }
        return b.toString()
    }

    /** [text] cut after each . ! ? or … (with a closing quote or bracket) that a space follows. */
    private fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var end = i + 1
                while (end < text.length && text[end] in "”\"’')") end++
                if (end >= text.length || text[end] == ' ') {
                    val piece = text.substring(start, end).trim()
                    if (piece.isNotEmpty()) out += piece
                    start = end
                    i = end
                    continue
                }
            }
            i++
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }
}
