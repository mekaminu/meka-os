package os.meka.core.domain

/**
 * The companion, slice 9 (talks like a person: speech that starts within about a second). A spoken answer used to wait
 * for the model to finish writing all of it (its words, then its actions and note) before anything was said. Now the
 * server reads the model's answer as it is written, and the moment its first sentence is whole it says that sentence
 * and sends it ahead; the device starts speaking while the rest is still being written, then carries on with the rest
 * once the whole answer has come and been checked. These are the pure rules both ends share. (Non-AI.)
 *
 * Only Navilife's own words are ever said early, and the early piece is only words: any action in the answer still
 * comes with the whole answer and is still a card that waits for Meka's tap (ADR-006).
 */
object StreamedSpeechRules {
    /** A sentence ends with one of these, perhaps followed by a closing quote or bracket. */
    private const val ENDS = ".!?…"
    private const val CLOSERS = "\"'”’)]"

    /** The model's `"answer"` so far: its words and whether the string has closed. */
    data class Partial(val text: String, val closed: Boolean)

    /**
     * The words of the `"answer"` field in [prefix], the start of a model's JSON answer (`{"answer":"…","actions":[…]}`)
     * as written so far: JSON escapes decoded, an escape cut off at the end left out. Null until the field's string has
     * begun, or when the model isn't writing JSON (a plain-words answer is said once it is whole, as before).
     */
    fun partialAnswer(prefix: String): Partial? {
        val key = Regex(""""answer"\s*:\s*"""").find(prefix) ?: return null
        // The field must be the object's own, near its start (not "answer" inside some other string).
        val brace = prefix.indexOf('{')
        if (brace < 0 || brace > key.range.first) return null
        val out = StringBuilder()
        var i = key.range.last + 1
        while (i < prefix.length) {
            val c = prefix[i]
            when {
                c == '"' -> return Partial(out.toString(), closed = true)
                c == '\\' -> {
                    if (i + 1 >= prefix.length) return Partial(out.toString(), closed = false)
                    when (val e = prefix[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b', 'f' -> out.append(' ')
                        'u' -> {
                            if (i + 6 > prefix.length) return Partial(out.toString(), closed = false)
                            val code = prefix.substring(i + 2, i + 6).toIntOrNull(16) ?: return Partial(out.toString(), closed = false)
                            out.append(code.toChar())
                            i += 4
                        }
                        else -> out.append(e) // \" \\ \/
                    }
                    i += 2
                }
                else -> { out.append(c); i++ }
            }
        }
        return Partial(out.toString(), closed = false)
    }

    /**
     * The piece to say early from [prefix] (the model's answer so far), or null while there isn't one yet: the first
     * piece the device will say ([TalkRules.firstPiece]), once it is a whole sentence with the next one begun (or the
     * answer's words have closed), so it can't change as the rest is written.
     */
    fun earlyPiece(prefix: String): String? {
        val partial = partialAnswer(prefix) ?: return null
        val piece = TalkRules.firstPiece(partial.text) ?: return null
        if (partial.text.isBlank()) return null
        if (partial.closed) return piece
        if (!endsSentence(piece)) return null
        val all = SpeechRules.pieces(TalkRules.speakable(partial.text))
        return piece.takeIf { all.size >= 2 && all.first() == piece }
    }

    /**
     * What is left to say of [spoken] (the whole answer as the device says it, [TalkRules.spoken]) once [early] has been
     * said: the rest after it when [spoken] starts with it, else everything after its own first piece (the device's
     * checks changed the words; never [early] twice). Empty when there's nothing more.
     */
    fun rest(spoken: String, early: String): String {
        val s = squash(spoken)
        val e = squash(early)
        if (e.isNotEmpty() && s.startsWith(e)) return s.removePrefix(e).trim()
        return SpeechRules.pieces(s).drop(1).joinToString(" ").trim()
    }

    private fun endsSentence(piece: String): Boolean {
        val t = piece.trimEnd().trimEnd { it in CLOSERS }
        return t.isNotEmpty() && t.last() in ENDS
    }

    private fun squash(text: String): String = text.trim().replace(Regex("""\s+"""), " ")
}
