package os.meka.core.domain

/**
 * The orb speed pass, slice 6 (Meka 2026-10-20 19:57: "partial results sent early for quick look-ups"). When Talk on
 * the Fold decides he has finished (0.6 s after his last word, [TalkEndpoint]) it stops the recogniser, which then
 * takes a moment more to hand over its final words. For a **quick look-up** that only reads ("what's on tomorrow?",
 * "when's the bin day?") the question is asked **ahead** with the words heard so far, while the final words are still
 * on their way; when they come and say the same ([same]), the answer already on its way is the one used, so the wait
 * for the final words is gone. When they differ, the early question is dropped and the final one asked as before.
 *
 * Only questions that change nothing go ahead ([worthIt]): a plain question (it starts like one), not an open turn
 * (the companion's larger model: [CompanionRules.isOpen]), no word that acts (add, move, pay, send…), nothing in Money
 * (behind his fingerprint). A dropped question costs at most one small look-up; nothing it says is spoken or kept.
 * Non-AI, pure; nothing is stored.
 */
object AskAheadRules {
    /** Fewer words than this are too likely to grow ("what's" → "what's on tomorrow"). */
    const val MIN_WORDS = 3

    /** The starts of a plain question. */
    private val QUESTION_STARTS = listOf(
        "what", "whats", "when", "whens", "where", "wheres", "who", "whos", "which", "is", "isnt", "are", "arent", "am",
        "do", "does", "did", "have", "has", "hasnt", "havent", "will", "wont", "any", "anything", "how many", "how much",
        "how long", "hows", "how is", "how are", "how far", "how late",
    )

    /** Words that ask Navilife to do something: those questions wait for his final words. */
    private val ACTION_WORDS = setOf(
        "add", "remind", "put", "move", "cancel", "delete", "remove", "mark", "book", "send", "text", "message", "email",
        "reply", "call", "ring", "pay", "buy", "order", "start", "stop", "end", "pause", "resume", "skip", "set", "clear",
        "turn", "open", "play", "read", "finish", "finished", "done", "tick", "snooze", "archive", "unsubscribe", "block",
        "save", "keep", "forget", "remember", "trade", "transfer", "change", "reschedule", "accept", "decline", "share",
        "plan", "take", "note", "log", "track", "record", "update", "edit", "rename", "approve", "dismiss", "hide",
    )

    /** His words as compared: lower case, letters, digits and single spaces only ("What's on?" → "whats on"). */
    fun norm(words: String): String =
        words.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * Whether [words] (heard so far) may be asked ahead: a plain read-only question of at least [MIN_WORDS] words
     * that is not an open turn (given his [earlier] questions in this conversation) and not about Money.
     */
    fun worthIt(words: String, earlier: List<String> = emptyList()): Boolean {
        val q = norm(words)
        val tokens = q.split(' ').filter { it.isNotEmpty() }
        if (tokens.size < MIN_WORDS) return false
        if (QUESTION_STARTS.none { q == it || q.startsWith("$it ") }) return false
        if (tokens.any { it in ACTION_WORDS }) return false
        if (CompanionRules.isOpen(words, earlier)) return false
        if (MoneyAskRules.isAbout(words)) return false
        return true
    }

    /** Whether the final words say what was asked ahead (case, punctuation and spacing aside). */
    fun same(ahead: String, final: String): Boolean {
        val a = norm(ahead)
        return a.isNotEmpty() && a == norm(final)
    }

    /**
     * The question the flow asks now ([question], from his final words) uses the one asked ahead ([ahead], null when
     * none) when they are the same; anything else is asked afresh and the early one dropped.
     */
    fun use(ahead: String?, question: String): Boolean = ahead != null && same(ahead, question)
}
