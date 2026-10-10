package os.meka.core.domain

/**
 * Quick capture by voice on the Galaxy Watch (build plan "Galaxy Watch", slice 4a). Meka taps Capture on the watch (or
 * on its tile), says something, and it lands in MEKA the way typing it into Today's capture bar would: a task, or a
 * quick alarm or timer for "timer 20 min" / "alarm 6:30" ([QuickAlarmRules]). The watch listens with Android's
 * **on-device** recogniser only, as the Fold does (ADR-006): if the watch has none, it says so and doesn't listen; it
 * never falls back to a recogniser that would send Meka's voice away. The words heard go through no AI.
 *
 * After a capture the watch shows what it did for [UNDO_MS] with Undo, which deletes the task or cancels the alarm on
 * every device. Pure, no AI; nothing is stored here.
 */
data class WatchCaptured(
    /** "Added “Buy milk”" · "Timer set · 20 min · ends 14:52". */
    val line: String,
    /** The new task's id, or null when [alarmId] is set. */
    val taskId: String?,
    /** The quick alarm or timer's id, or null when [taskId] is set. */
    val alarmId: String?,
)

/** Why the watch didn't capture anything (each has its line in [WatchCaptureRules.failLine]). */
enum class WatchListenFailure {
    /** The watch has no on-device recogniser; MEKA never uses one that sends the voice away. */
    NO_RECOGNISER,
    /** English for on-device speech isn't on the watch. */
    NO_LANGUAGE,
    /** The microphone isn't allowed. */
    NO_MIC,
    /** Nothing was heard, or nothing that could be made out. */
    NOT_HEARD,
    /** The recogniser was busy or failed in some other way. */
    FAILED,
}

object WatchCaptureRules {
    const val BUTTON = "Capture"
    const val LISTENING = "Listening…"
    const val HINT = "Say a task, or “timer 20 min”"
    /** What a screen reader says for the tile's Capture. */
    const val TILE_SPOKEN = "Capture. Double tap to add something by voice."
    const val UNDO = "Undo"
    const val UNDONE = "Taken back"
    /** How long the watch shows what it captured, with Undo. */
    const val UNDO_MS = 5_000L
    /** How long a failure's line stays before the button comes back. */
    const val FAIL_MS = 4_000L
    /** Longest text kept from one capture (a watch capture is a line, not a note). */
    const val MAX_CHARS = 200
    /** How much of the title "Added “…”" shows on a round screen. */
    const val LINE_TITLE_MAX = 32
    /** The tile's launch extra that opens MEKA already listening. */
    const val LISTEN_EXTRA = "os.meka.wear.LISTEN"

    private val spaces = Regex("""\s+""")

    /**
     * What was heard, made ready to capture: one line, spaces collapsed, the first letter capitalised and a lone full
     * stop the recogniser added dropped; at most [MAX_CHARS], cut at a word. Null when nothing was said.
     */
    fun clean(heard: String?): String? {
        var t = heard?.replace(spaces, " ")?.trim() ?: return null
        if (t.endsWith('.') && !t.endsWith("..")) t = t.dropLast(1).trimEnd()
        if (t.isEmpty()) return null
        if (t.length > MAX_CHARS) {
            val cut = t.take(MAX_CHARS)
            val space = cut.lastIndexOf(' ')
            t = (if (space >= MAX_CHARS / 2) cut.take(space) else cut).trimEnd()
        }
        return t.replaceFirstChar { it.uppercaseChar() }
    }

    /** "Added “Buy milk and eggs”", the title cut at a word to fit the round screen. */
    fun addedLine(title: String): String = "Added “${WatchTileRules.shorten(title, LINE_TITLE_MAX)}”"

    /** What the watch shows once the capture went in; null for [CaptureOutcome.Empty] (nothing captured). */
    fun captured(outcome: CaptureOutcome, title: String): WatchCaptured? = when (outcome) {
        is CaptureOutcome.TaskAdded -> WatchCaptured(addedLine(title), taskId = outcome.taskId, alarmId = null)
        is CaptureOutcome.AlarmSet -> WatchCaptured(outcome.line, taskId = null, alarmId = outcome.alarmId)
        CaptureOutcome.Empty -> null
    }

    fun failLine(f: WatchListenFailure): String = when (f) {
        WatchListenFailure.NO_RECOGNISER -> "This watch can't listen on its own · capture on your phone"
        WatchListenFailure.NO_LANGUAGE -> "English speech isn't on this watch yet · add it in the watch's settings"
        WatchListenFailure.NO_MIC -> "MEKA needs the microphone to listen · allow it, then tap Capture"
        WatchListenFailure.NOT_HEARD -> "Didn't catch that · tap Capture to try again"
        WatchListenFailure.FAILED -> "Couldn't listen just now · try again"
    }

    /** What a screen reader says once it's captured: the line, then that Undo is there for a few seconds. */
    fun spoken(c: WatchCaptured): String = "${c.line}. $UNDO for ${UNDO_MS / 1000} seconds."
}
