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
     * begun. Plain words (a spoken turn, slice 4 of the conversation item) are read as they are written ([plainWords]).
     */
    fun partialAnswer(prefix: String): Partial? {
        // Talking to Obi like a conversation, slice 4: a spoken turn's answer is his words first, as plain text, then
        // (only when there are any) his actions, sources and note in one short tagged block after them.
        if (isPlain(prefix)) return plainWords(prefix)
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

    /**
     * Talking to Obi like a conversation, slice 7 (the rest said sentence by sentence as it is written): the pieces the
     * device will say after [early] ([rest], then [SpeechRules.pieces]) that can no longer change in [prefix], the
     * model's answer so far: once the words have closed, every one; before that, each but the last, and only when it
     * ends a sentence (the last may still grow, and a later piece joins sentences). At most [MAX_NEXT], within
     * [SpokenTurnRules.MAX_WORDS] words counting [early] (what the server's tidy keeps), and none while the words read
     * like a list ([SpokenTurnRules.isListy]: the tidy would change them) or don't start with [early]. Pure.
     */
    fun nextPieces(prefix: String, early: String): List<String> {
        val partial = partialAnswer(prefix) ?: return emptyList()
        if (early.isBlank() || SpokenTurnRules.isListy(partial.text)) return emptyList()
        val words = squash(TalkRules.speakable(partial.text))
        val e = squash(early)
        if (!words.startsWith(e)) return emptyList()
        val pieces = SpeechRules.pieces(words.removePrefix(e).trim())
        val settled = if (partial.closed) pieces else pieces.dropLast(1).filter(::endsSentence)
        var count = SpokenTurnRules.wordCount(e)
        val out = mutableListOf<String>()
        for (p in settled) {
            count += SpokenTurnRules.wordCount(p)
            if (count > SpokenTurnRules.MAX_WORDS || out.size >= MAX_NEXT || p.length > SpeechRules.MAX_PIECE) break
            out += p
        }
        return out
    }

    /** Pieces after the first said ahead of the answer at most (slice 7): the device says at most three sentences. */
    const val MAX_NEXT = 2

    /**
     * Talking to Obi like a conversation, slice 4: where a spoken turn's words end and what comes after them begins
     * (`<after>{"actions":[…],"sources":[…],"note":"…"}</after>`). Never said; the same tag as [os.meka.core.wire.AskCodec].
     */
    const val AFTER_OPEN = "<after>"
    const val AFTER_CLOSE = "</after>"

    /** Whether [prefix] is plain words (a spoken turn's answer): it has begun, and not with JSON or a code fence. */
    fun isPlain(prefix: String): Boolean {
        val first = prefix.firstOrNull { !it.isWhitespace() } ?: return false
        return first != '{' && first != '`'
    }

    /**
     * The words of a plain answer so far: everything before the [AFTER_OPEN] block (closed once it has begun), or
     * before a `{` or a code fence a model added anyway; a tag only partly written at the end is left out until it is
     * whole or turns out to be words.
     */
    private fun plainWords(prefix: String): Partial {
        val ends = listOf(prefix.indexOf(AFTER_OPEN), prefix.indexOf('{'), prefix.indexOf("```")).filter { it >= 0 }
        ends.minOrNull()?.let { return Partial(prefix.substring(0, it).trim(), closed = true) }
        val lt = prefix.lastIndexOf('<')
        val text = if (lt >= 0 && AFTER_OPEN.startsWith(prefix.substring(lt))) prefix.substring(0, lt) else prefix
        return Partial(text.trim(), closed = false)
    }

    private fun endsSentence(piece: String): Boolean {
        val t = piece.trimEnd().trimEnd { it in CLOSERS }
        return t.isNotEmpty() && t.last() in ENDS
    }

    private fun squash(text: String): String = text.trim().replace(Regex("""\s+"""), " ")
}
