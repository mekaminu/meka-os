package os.meka.core.domain

/**
 * Meka 2026-10-24 14:34 (build 0.1.2125, ringer muted): tapping the orb flew it to the middle, then about two seconds
 * later the stage closed by itself, nothing he said was heard. The stage used to close whenever the conversation
 * ended ([HelloEntrance.next]), so a microphone that failed to start, a hello that never began or a silence all looked
 * the same: a stage that vanished. Now it stays until he has spoken (and been answered) or swipes it away; when it
 * can't listen it says why on the stage, and a tap tries again. Why it last closed is kept on the device (no words)
 * and shown in Health → Obi's voice.
 */
enum class StageClose(val line: String) {
    SWIPED("you swiped it away"),
    SPOKE("after you'd spoken"),
    OPENED_ASK("an answer opened Ask"),
    OPENED_CARD("you opened one of its cards"),
    /** The Mac: he closed Talk himself (the Fold's stage is swiped away). */
    CLOSED("you closed it"),
    NO_SPEECH("no speech heard for a minute"),
    PROBLEM("the microphone couldn't start"),
    NOT_STARTED("listening never started"),
}

/** What the stage says under the orb when it can't carry on by itself. */
enum class StageNote { NONE, NOT_STARTED, NOTHING_HEARD, PROBLEM }

data class StageCall(val next: StageNext, val note: StageNote = StageNote.NONE, val close: StageClose? = null)

/** The last close on this device: why and when. */
data class StageClosed(val why: StageClose, val atMs: Long)

object StageRules {
    /** A stage left with nothing happening (nothing said, or it couldn't listen) shrinks back after this long. */
    const val IDLE_CLOSE_MS = 60_000L
    const val OPEN_SETTINGS = "Open settings"
    const val TRY_AGAIN = "Tap to try again"

    /**
     * Once the entrance has settled: stay or close, and what to say. [started]: the conversation has begun since the
     * greeting was due; [spoke]: he has said something this stage (heard words); [problem]: why it couldn't listen;
     * [msSinceGreet]: since the greeting was due; [idleMs]: how long the conversation has been over (null while it runs).
     */
    fun call(
        phase: TalkPhase,
        started: Boolean,
        spoke: Boolean,
        needsAsk: Boolean,
        problem: TalkProblem?,
        msSinceGreet: Long,
        idleMs: Long?,
    ): StageCall {
        if (needsAsk) return StageCall(StageNext.CLOSE, close = StageClose.OPENED_ASK)
        if (phase != TalkPhase.ENDED) return StageCall(StageNext.STAY)
        if (started && spoke && problem == null) return StageCall(StageNext.CLOSE, close = StageClose.SPOKE)
        val (note, why) = when {
            problem != null -> StageNote.PROBLEM to StageClose.PROBLEM
            started -> StageNote.NOTHING_HEARD to StageClose.NO_SPEECH
            msSinceGreet >= HelloEntrance.NO_START_MS -> StageNote.NOT_STARTED to StageClose.NOT_STARTED
            else -> return StageCall(StageNext.STAY) // the hello is still on its way
        }
        val idle = idleMs ?: (msSinceGreet - HelloEntrance.NO_START_MS).coerceAtLeast(0)
        return if (idle >= IDLE_CLOSE_MS) StageCall(StageNext.CLOSE, note, why) else StageCall(StageNext.STAY, note)
    }

    /** The stage's line for [note]; null for [StageNote.NONE]. [mac] picks the Mac's wording of a problem. */
    fun line(note: StageNote, problem: TalkProblem?, mac: Boolean = false): String? = when (note) {
        StageNote.NONE -> null
        StageNote.NOT_STARTED -> "I couldn't start listening. $TRY_AGAIN, or swipe down to close."
        StageNote.NOTHING_HEARD -> "I didn't hear anything. Tap to talk, or swipe down to close."
        StageNote.PROBLEM -> when (problem) {
            null -> "I couldn't start listening. $TRY_AGAIN."
            TalkProblem.NO_PERMISSION -> "I can't hear you: the microphone is off for Navilife."
            else -> (if (mac) problem.macLine else problem.line) + " $TRY_AGAIN."
        }
    }

    /** The button under the line: the microphone's settings when Navilife isn't allowed it; null otherwise (a tap retries). */
    fun button(note: StageNote, problem: TalkProblem?): String? =
        if (note == StageNote.PROBLEM && problem == TalkProblem.NO_PERMISSION) OPEN_SETTINGS else null

    /** A tap on the stage with [note] showing starts listening again (never with the settings button showing). */
    fun tapRetries(note: StageNote, problem: TalkProblem?): Boolean = note != StageNote.NONE && button(note, problem) == null

    /** Health → Obi's voice: "Last conversation closed 14:34: no speech heard for a minute". */
    fun healthLine(c: StageClosed?, nowMs: Long, cal: LocalCalendar): String? =
        c?.let { "Last conversation closed ${HealthRules.at(it.atMs, nowMs, cal)}: ${it.why.line}" }

    /** Ask's list: the title is item 0, the field (where the conversation lives) item 1; past it, it isn't composed. */
    const val FIELD_INDEX = 1
    fun fieldHidden(firstVisibleIndex: Int): Boolean = firstVisibleIndex > FIELD_INDEX

    /** A close as text for the device's storage (`atMs` and the reason's name); no words. */
    fun encode(c: StageClosed): String = "${c.atMs} ${c.why.name}"

    fun decode(text: String?): StageClosed? {
        val parts = text?.trim()?.split(' ') ?: return null
        if (parts.size != 2) return null
        val at = parts[0].toLongOrNull() ?: return null
        val why = StageClose.entries.firstOrNull { it.name == parts[1] } ?: return null
        return StageClosed(why, at)
    }
}
