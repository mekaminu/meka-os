package os.meka.core.domain

/**
 * The talk survives rotation and folding (Meka 2026-11-03 13:17: "when I'm using the orb and the phone rotates it goes
 * to a different tab and cuts my discussion: stay on the orb while I'm chatting"). Pure.
 *
 * Rotating the Fold's cover screen to landscape crosses the shell's wide line (bottom bar → rail) and tilting it can read
 * as standing on a table (the bedside clock). Neither may end a conversation: while a talk runs the shell keeps its
 * screens where they are (only re-laid for the new size) and the bedside clock waits until the talk has ended.
 */
object TalkLayoutRules {
    /** Whether a talk is running in [phase] (anything but ended). */
    fun talking(phase: TalkPhase): Boolean = phase != TalkPhase.ENDED

    /**
     * Whether the bedside clock takes the screen: as [BedsideEntryRules.shows] says ([shows]), but never while a talk
     * runs ([talking]); it comes up once the talk ends if the Fold is still standing.
     */
    fun bedside(shows: Boolean, talking: Boolean): Boolean = shows && !talking

    /**
     * Whether a change of the screen's shape from [before] to [after] (a layout's name, e.g. "BOTTOM_BAR", "RAIL";
     * null on the first reading) is noted in the talk's log for Health: only a real change during a talk.
     */
    fun noteLayout(before: String?, after: String, talking: Boolean): Boolean = talking && before != null && before != after
}
