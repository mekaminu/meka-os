package os.meka.core.domain

/**
 * The companion, slice 1 (Meka 2026-10-19 20:14: "interactive like a human talking to someone, and thinks outside
 * Navilife"). Ask, Talk and Hello answer general questions too (knowledge, advice, ideas, explanations, banter), not
 * only his day. Navilife's server tells an **open** turn from a **quick look-up** with [isOpen]: an open turn goes to
 * the larger model and is metered as [FEATURE] ("Chat" in Activity), a look-up stays on the small model as before.
 * Non-AI, pure; nothing is stored.
 */
object CompanionRules {
    /** The meter an open turn counts against, inside the same monthly AI budget. */
    const val FEATURE = "ask.companion"

    /** A question this long is a conversation, not a look-up, whatever its words. */
    const val LONG_WORDS = 16

    /** Openings that ask for thinking, not a fact from his day. */
    private val OPEN_STARTS = listOf(
        "why", "explain", "tell me about", "tell me a", "tell me something", "teach me", "how does", "how do", "how would",
        "how can", "how should", "how come", "what do you think", "what's the difference", "what is the difference",
        "what's a ", "what is a ", "what's an ", "what is an ", "what are ", "what does ", "what would", "what if", "who was",
        "who is", "who were", "who invented", "who wrote", "is it true", "can you explain", "could you explain", "help me",
        "give me ideas", "give me some", "any ideas", "ideas for", "should i", "would you", "what should i", "recommend",
        "suggest", "brainstorm", "compare", "imagine", "let's talk", "lets talk", "talk to me", "i'm thinking", "im thinking",
        "i've been thinking", "ive been thinking", "i feel", "i'm feeling", "im feeling", "i'm bored", "im bored",
        "fancy a chat", "do you know", "do you like", "have you heard", "write me", "write a", "describe",
    )

    /** Words anywhere that mark an open turn. */
    private val OPEN_WORDS = listOf(
        "brainstorm", "ideas", "advice", "explain", "difference between", "pros and cons", "your opinion", "your view",
        "what do you think", "recipe", "joke", "poem", "story", "history of", "meaning of", "in general",
    )

    /** Openings that are plainly a look-up of his own day, his lists or an action. */
    private val LOOKUP_STARTS = listOf(
        "what's on", "whats on", "what is on", "what have i got", "what've i got", "what's my", "whats my", "what is my",
        "what's next", "whats next", "when's", "whens", "when is", "when do i", "when did i", "when am i", "where's my",
        "wheres my", "where is my", "is it going to", "will it", "am i ", "do i have", "have i got", "add ", "remind ",
        "move ", "show ", "open ", "set ", "start ", "skip ", "book ", "mark ", "put ", "plan my", "what time",
        "what are my", "whats left", "what's left", "how many", "how long until", "how long till",
    )

    /** Short follow-ups that carry on whatever came before ("why?", "go on", "and then?"). */
    private val FOLLOW_UPS = listOf("go on", "and then", "tell me more", "more", "why", "how so", "really", "such as", "like what", "what else", "and", "but", "so")

    /**
     * Whether [question] is an open turn: it asks for thinking (an explanation, advice, ideas, an opinion), is long, or
     * is a short follow-up to an open turn in [earlier] (his earlier questions in this conversation, oldest first). A
     * plain look-up ("what's on today?", "when's the dentist?") or an action is never open, even mid-conversation.
     */
    fun isOpen(question: String, earlier: List<String> = emptyList()): Boolean {
        val q = norm(question)
        if (q.isEmpty()) return false
        val words = q.split(' ').size
        if (LOOKUP_STARTS.any { q.startsWith(it) } && words < LONG_WORDS) return false
        if (OPEN_STARTS.any { q.startsWith(it) }) return true
        if (OPEN_WORDS.any { it in q }) return true
        if (words >= LONG_WORDS) return true
        val last = earlier.lastOrNull() ?: return false
        val followUp = words <= 6 && FOLLOW_UPS.any { q == it || q.startsWith("$it ") }
        return followUp && isOpen(last, earlier.dropLast(1))
    }

    /** Lower case, apostrophes kept, other punctuation and extra spaces dropped. */
    private fun norm(s: String): String =
        s.lowercase().replace('’', '\'').replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
}
