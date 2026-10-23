package os.meka.android.ask

import os.meka.core.domain.LipSync

/**
 * Obi's face, slice 3b: the mouth of the clip playing now, from its own mouth sounds (Polly's viseme marks via
 * [os.meka.core.facade.MekaCore.speechMarks]): [MekaSpeaker] starts it as a marked clip begins and ends it when the clip
 * is let go; [ObiFaceView] asks [frame] each frame and, while there is one, draws it instead of the loudness mouth.
 * Main thread only (the speaker's player callbacks and Compose's frames both run there).
 */
object ObiMouth {
    private var cues: List<LipSync.Cue> = emptyList()
    private var position: (() -> Long)? = null
    private var owner: Any? = null

    /** A clip owned by [who] starts: [cues] timed against [positionMs] (the player's own position, so pauses keep in step). */
    fun start(who: Any, cues: List<LipSync.Cue>, positionMs: () -> Long) {
        if (cues.isEmpty()) {
            owner?.let { end(it) }
            return
        }
        owner = who
        this.cues = cues
        position = positionMs
    }

    /** [who]'s clip is over (or let go): the face goes back to the loudness mouth. A later clip's mouth is left alone. */
    fun end(who: Any) {
        if (owner !== who) return
        owner = null
        cues = emptyList()
        position = null
    }

    /** What the marked mouth shows now, or null when no marked clip is playing. */
    fun frame(): LipSync.Frame? {
        val at = position ?: return null
        val t = runCatching { at() }.getOrNull() ?: return null
        return LipSync.at(cues, t)
    }
}
