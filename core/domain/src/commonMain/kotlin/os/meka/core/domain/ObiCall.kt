package os.meka.core.domain

/**
 * Obi on a call (build plan "the assistant becomes Obi", slice 5: the honesty rule; Meka 2026-10-22/23). Whenever Obi
 * speaks to someone on Meka's behalf he opens with his name and that he is Meka's AI assistant, and if a caller asks
 * whether he's a robot, an AI or a real person he says plainly "I am, yes, Meka's AI assistant." — then carries on.
 *
 * Non-AI and pure, so the call assistant answers inside the phone service's wait. The caller's words are untrusted
 * (ADR-006): a question here only ever adds the plain answer; it never changes what the assistant may do.
 */
object ObiCallRules {
    /** How every call Obi takes or makes opens (the greetings in [CallAssistantScript] start with it). */
    const val OPENING = "Hi, this is Obi, Meka's AI assistant."
    /** His answer when asked whether he's a person, a robot or an AI. */
    const val AI_ANSWER = "I am, yes, Meka's AI assistant."
    /** After answering, asked once more in place of "who's calling?" ([CallIntroRules.INTRO_QUESTION]). */
    const val ASK_WHO_AGAIN = "So, may I ask who's calling, and what it's about?"
    /** After answering, in place of "is it urgent?" (without the "Sorry" of a misheard answer). */
    const val URGENT_AFTER_ANSWER = "And is your message urgent? Say yes or no, or press 1 for yes."

    /** Every fixed line this adds, for the call assistant's ready clips. */
    val LINES = listOf(AI_ANSWER, ASK_WHO_AGAIN, URGENT_AFTER_ANSWER)

    private const val WHAT = "(a |an )?(robot|bot|machine|computer|recording|recorded message|ai|a i|artificial intelligence|" +
        "automated (system|assistant|service|message|voice)|automated|real person|actual person|real human|human|person|real)"

    private val questions = listOf(
        // "are you a robot?", "are you real?", "are you AI or a person?"
        Regex("\\bare (you|u) $WHAT\\b"),
        // "is this a robot?", "is this a recording?", "is that a real person?", "is this AI?"
        Regex("\\bis (this|that|it) $WHAT\\b"),
        // "am I talking to a robot?", "am I speaking with a real person?"
        Regex("\\bam i (talking|speaking|through) (to|with) $WHAT\\b"),
        // "is this Meka or a robot?", "is there a real person there?"
        Regex("\\bis there a (real |actual )?(person|human)\\b"),
        // "you're a robot, aren't you?", "you're AI"
        Regex("\\byou're $WHAT\\b"),
        Regex("\\byou are $WHAT\\b"),
    )

    /** Ordinary phrases the patterns would catch that aren't the question ("is it real urgent", "is this a person's line"). */
    private val notTheQuestion = Regex("\\b(real(ly)? urgent|real quick|really)\\b")

    private fun norm(speech: String?): String =
        speech?.lowercase()?.replace('’', '\'')?.replace(Regex("[^a-z0-9' ]+"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    /** Whether the caller asked if they're talking to a person or a machine. */
    fun asksIfAi(speech: String?): Boolean {
        val s = norm(speech)
        if (s.isEmpty()) return false
        val hit = questions.firstOrNull { it.containsMatchIn(s) } ?: return false
        val m = hit.find(s)!!.value
        // "is it real urgent" / "are you really busy": "real" here isn't asking about him.
        return !(m.endsWith(" real") && notTheQuestion.containsMatchIn(s.substring(s.indexOf(m))))
    }

    /**
     * The caller's words with the question taken out, so what's left (who they are, yes or no) is read as before; null
     * when nothing else was said.
     */
    fun withoutQuestion(speech: String?): String? {
        var s = norm(speech)
        if (s.isEmpty()) return null
        for (q in questions) s = q.replace(s, " ")
        // The leftovers of a question: "hello", "sorry", "or", "or not", "is it".
        s = s.replace(Regex("\\b(or not|or|hello|hi|sorry|excuse me|wait|hang on|um|uh|erm|please)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return s.takeIf { it.isNotEmpty() }
    }
}
