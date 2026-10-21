package os.meka.core.domain

/**
 * Talking to Ada like a conversation, slice 6 (Meka 2026-10-21 20:32; non-AI, pure): **conversation mode**.
 *
 * After Ada has said her line the microphone opens again on its own and stays open for [WINDOW_MS], so he can just
 * carry on talking without a tap; the orb shows Listening with [LINE] under it. The window runs from the moment her
 * line ends ([TalkFlow.spoke]); a recogniser that gives up on silence sooner (Android's on-device one, Apple's "no
 * speech") listens again inside it ([listenAgain]); once it is over with nothing of his heard, the conversation ends
 * quietly ([lapsed], as [TalkFlow.silence]). "Thanks, Ada", "cheers Ada", "that's all Ada" end it too: the name is a
 * filler to [TalkRules] like "Meka" and "Navilife". Talking over her (barge-in) is unchanged.
 *
 * The Fold (Talk, the orb and the bedside clock share one controller) and the Mac do the same. Nothing new is kept or
 * sent: it is the same recogniser Talk listens with, only kept open across its own early time-out.
 */
object ConversationModeRules {
    /** How long the microphone stays open after Ada has spoken, with nothing of his heard. */
    const val WINDOW_MS = 8_000L

    /** Under the orb while the window is open. */
    const val LINE = "Go on, I'm listening · say “thanks, Ada” to finish"

    /**
     * The recogniser gave up on silence inside the window opened at [openedAtMs] (null: not after one of Ada's lines),
     * with nothing of his [heard]: open it again rather than end.
     */
    fun listenAgain(openedAtMs: Long?, nowMs: Long, heard: Boolean): Boolean =
        openedAtMs != null && !heard && nowMs - openedAtMs < WINDOW_MS

    /** The window opened at [openedAtMs] is over with nothing of his [heard]: the conversation ends quietly. */
    fun lapsed(openedAtMs: Long?, nowMs: Long, heard: Boolean): Boolean =
        openedAtMs != null && !heard && nowMs - openedAtMs >= WINDOW_MS

    /**
     * The orb's line: [LINE] while listening in the window ([followOn]) with nothing heard yet, else the usual
     * [TalkOrb.label].
     */
    fun label(phase: TalkPhase, mac: Boolean, talkOver: Boolean, followOn: Boolean): String =
        if (phase == TalkPhase.LISTENING && followOn) LINE else TalkOrb.label(phase, mac, talkOver)
}
