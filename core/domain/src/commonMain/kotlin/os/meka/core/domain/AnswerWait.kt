package os.meka.core.domain

/**
 * The orb speed pass, slice 4 (Meka, 2026-10-20 19:57: "a lot of wait"; "silence never feels dead"): what Talk does
 * between his last word and the answer's first sound. The orb shows Thinking at once ([TalkPhase.THINKING], its own
 * motion); a soft tick once [CUE_AFTER_MS] has gone with nothing started (a quick look-up that answers sooner has
 * none); and once [HOLD_AFTER_MS] has gone with still nothing, a short holding line in Navilife's voice, played from
 * memory ("One sec, Meka…"), once per question. The holding lines are made ahead with the hello's openers
 * ([holdLines]), so on a slow network nothing is asked of the server at that moment; a line not made yet is skipped.
 * The answer then follows the line without cutting it short.
 */
object AnswerWaitRules {
    /** After this with nothing started, the soft thinking tick plays. */
    const val CUE_AFTER_MS = 300L
    /** After this with nothing started, the holding line is said (once per question). */
    const val HOLD_AFTER_MS = 1_200L

    private val OPENERS = listOf("One sec", "Bear with me", "Just a moment", "Let me see")

    /**
     * The holding lines, each with his first name as typed in About you ([HelloRules.firstName]): "One sec, Meka…";
     * without a name, "One sec…". Only Navilife's own words.
     */
    fun holdLines(name: String?): List<String> {
        val first = HelloRules.firstName(name)
        return OPENERS.map { if (first == null) "$it…" else "$it, $first…" }
    }

    /** Which holding line comes next after [last] (by index): in turn, so never the same twice in a row. */
    fun nextIndex(last: Int?, size: Int): Int = if (last == null || size <= 1) 0 else (last + 1).mod(size)

    /** The soft tick plays: [waitingMs] since the question was sent, and no answer has started. */
    fun cues(waitingMs: Long, answerStarted: Boolean): Boolean = !answerStarted && waitingMs >= CUE_AFTER_MS

    /** The holding line is said: [waitingMs] since the question was sent, nothing started, and not [held] already. */
    fun holds(waitingMs: Long, answerStarted: Boolean, held: Boolean): Boolean =
        !answerStarted && !held && waitingMs >= HOLD_AFTER_MS
}
