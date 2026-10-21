package os.meka.core.domain

/**
 * What Talk does between his last word and the answer's first sound.
 *
 * Talking to Ada like a conversation, slice 1 (Meka, 2026-10-21 20:32: "I hate that the AI keeps saying 1 sec… feels
 * weird"): Ada never says a holding line any more ("One sec, Meka…" is gone, and none are made ahead). The wait is
 * shown by the orb, which shows Thinking at once ([TalkPhase.THINKING], its own motion), and only once [CUE_AFTER_MS]
 * has gone with nothing started does one soft, breath-like tone play, once per question; most answers start well
 * before it, so most turns have no sound at all between his words and hers. The speed log's trace is unchanged.
 *
 * (Before: a tick at 0.3 s and "One sec, Meka…" at 1.2 s, the orb speed pass, slice 4.)
 */
object AnswerWaitRules {
    /** After this with nothing started, the one soft breath-like tone plays (once per question). */
    const val CUE_AFTER_MS = 2_500L

    /** Ada says nothing while she works out an answer: no holding line, ever. */
    const val SPEAKS_WHILE_WAITING = false

    /** The soft tone plays: [waitingMs] since the question was sent, and no answer has started. */
    fun cues(waitingMs: Long, answerStarted: Boolean): Boolean = !answerStarted && waitingMs >= CUE_AFTER_MS
}
