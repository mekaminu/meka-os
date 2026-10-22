package os.meka.core.domain

/**
 * Talking to Ada like a conversation, slice 7 (speech that sounds alive): how a piece of Ada's own words is marked up
 * for Amazon Polly. A short reaction at the start ("Oh nice.", "Ha, fair.", "Honestly?") gets a small natural pause
 * after it, and the whole piece is said a little quicker than Polly's default ([RATE]), as a person chatting does.
 * Only Navilife's own words are ever marked up; every `&`, `<`, `>`, `"` and `'` is escaped, so nothing in the words
 * can become a tag. Google's Chirp 3 HD voices take no SSML and are sent the plain words. (Non-AI, pure.)
 */
object SpokenSsmlRules {
    /** A touch quicker than Polly's default pace. */
    const val RATE = "106%"
    /** The pause after a reaction that opens a piece. */
    const val REACTION_PAUSE_MS = 250
    /** A reaction is a first sentence of at most this many words. */
    const val REACTION_WORDS = 3

    /**
     * [text] as SSML: `<speak><prosody rate="106%">…</prosody></speak>`, with `<break time="250ms"/>` after an opening
     * [reaction] when more follows it. Whitespace is collapsed; blank text stays blank SSML.
     */
    fun ssml(text: String): String {
        val words = text.trim().replace(Regex("""\s+"""), " ")
        val r = reaction(words)
        val body = if (r == null) escape(words)
        else escape(r) + """<break time="${REACTION_PAUSE_MS}ms"/>""" + " " + escape(words.substring(r.length).trim())
        return """<speak><prosody rate="$RATE">$body</prosody></speak>"""
    }

    /**
     * The reaction that opens [text], or null: a first sentence of at most [REACTION_WORDS] words ending with `.`, `!`
     * or `?` ("Oh nice.", "Ah, that's annoying.", "Ha, fair.", "Honestly?"), when more words follow it.
     */
    fun reaction(text: String): String? {
        val t = text.trim()
        val end = t.indexOfFirst { it == '.' || it == '!' || it == '?' }
        if (end <= 0 || end + 1 >= t.length || t[end + 1] != ' ') return null
        val first = t.substring(0, end + 1)
        if (t.substring(end + 1).isBlank() || first.any { it.isDigit() }) return null
        val count = first.split(' ').count { it.isNotBlank() }
        return first.takeIf { count in 1..REACTION_WORDS }
    }

    /** [text] safe inside SSML. */
    fun escape(text: String): String = buildString {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
