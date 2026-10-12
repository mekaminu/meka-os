package os.meka.core.domain

/*
 * "Plan my day" said to Talk (build plan V1, "A morning brief worth listening to", slice 5; non-AI, pure).
 *
 * The brief ends "Say “plan my day” or “more news”", and Talk keeps listening after it. "More news" is a read-out
 * ([ReadOutRules]); "plan my day" opens the planner (Today → Plan my day) on the device that heard it, exactly as its
 * button does: nothing is planned or changed until Meka taps Apply there. Recognised on the device, so nothing goes to
 * the AI for it and it doesn't count towards the question cap. MEKA says one short line, the conversation ends, then
 * the planner opens (ending first, so leaving Ask for Today never cuts MEKA off).
 */

/** A place Talk can open when asked ([TalkOpenRules.request]). */
enum class TalkPlace { PLAN }

object TalkOpenRules {
    /** What MEKA says before the planner opens. */
    const val OPENING_PLAN = "Here's a plan for today. Apply it if it looks right."

    /** What the conversation remembers MEKA did. */
    const val OPENED_PLAN = "Opened Plan my day."

    /** Words around the request that change nothing ("ok MEKA, can you plan my day for me please"). */
    private val DROP = setOf(
        "please", "meka", "hey", "ok", "okay", "yes", "yeah", "yep", "sure", "go", "ahead", "and", "then", "so", "now", "just",
        "um", "umm", "uh", "er", "erm", "can", "could", "would", "will", "you", "me", "for", "let's", "lets", "let", "us",
        "i", "want", "to", "like", "help", "need", "out",
    )

    /** What's left once [DROP] is gone: the planner, asked for plainly. */
    private val PLAN = setOf(
        listOf("plan", "my", "day"), listOf("plan", "the", "day"), listOf("plan", "today"), listOf("plan", "my", "today"),
        listOf("plan", "day"), listOf("plan", "my", "day", "today"), listOf("plan", "the", "day", "today"),
        listOf("make", "a", "plan"), listOf("make", "a", "plan", "today"), listOf("make", "a", "plan", "the", "day"),
        listOf("make", "my", "plan"), listOf("make", "a", "day", "plan"), listOf("day", "plan"),
        listOf("open", "the", "planner"), listOf("open", "planner"), listOf("open", "plan", "my", "day"),
        listOf("show", "my", "plan"), listOf("show", "the", "plan"), listOf("my", "plan", "today"),
        listOf("what's", "my", "plan", "today"), listOf("whats", "my", "plan", "today"),
        listOf("what's", "the", "plan", "today"), listOf("whats", "the", "plan", "today"),
    )

    /**
     * The place Meka asked Talk to open, or null: "plan my day", "plan my day please", "can you plan the day for me",
     * "let's plan today", "make a plan for the day", "what's my plan for today". Anything with more in it ("plan my day
     * around the dentist", "plan a trip") goes to MEKA as a question.
     */
    fun request(utterance: String): TalkPlace? {
        val ws = ReadOutRules.words(utterance).filter { it !in DROP }
        return if (ws in PLAN) TalkPlace.PLAN else null
    }

    /** What MEKA says before opening [place]. */
    fun opening(place: TalkPlace): String = when (place) {
        TalkPlace.PLAN -> OPENING_PLAN
    }

    /** What the conversation remembers about opening [place]. */
    fun remembered(place: TalkPlace): String = when (place) {
        TalkPlace.PLAN -> OPENED_PLAN
    }
}
