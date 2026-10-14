package os.meka.core.domain

/**
 * Voice notes into actions, slice 4 — a voice note on the Galaxy Watch. Meka taps **🎙 Voice note** under Capture (or
 * says "take a voice note" to Capture) and rambles; the watch's **on-device** recogniser writes it down as he talks
 * (ADR-006: never a recogniser that sends his voice away), listening again after each pause until he taps Done, at
 * most [VoiceNoteRules.MAX_MS]. Nothing is recorded or kept on the watch: there is no audio, and the words go once to
 * the AI's tidy-up (the same `POST /v1/ai/voice-note` the phone uses), whose cards sync to Needs you on the phone and
 * the Mac. When the AI can't answer (offline, off) or the note is a few words, they are kept as one task (as Capture
 * would) so nothing he said is lost, with Undo. Pure; nothing is stored here.
 */
data class WatchVoiceNoted(
    /** "Tidied · 2 things to add" · "Navilife couldn't read it just now · your words are kept as a task". */
    val line: String,
    /** "On your phone in Needs you" when there are cards; null otherwise. */
    val whereLine: String?,
    /** The AI's tidy note, cut for a round screen; null when there is none. */
    val tidy: String?,
    /** "Mentions Sam" · null. */
    val peopleLine: String?,
    /** The task the words were kept as when the AI couldn't read them (Undo deletes it); null otherwise. */
    val taskId: String?,
    /** How many cards wait in Needs you. */
    val cards: Int,
)

object WatchVoiceNoteRules {
    const val BUTTON = "🎙 Voice note"
    const val LABEL = "VOICE NOTE"
    const val HINT = "Ramble away · tap Done when you've finished"
    const val READING = "Reading it…"
    const val DONE = VoiceNoteRules.STOP
    const val PAUSE = VoiceNoteRules.PAUSE
    const val RESUME = VoiceNoteRules.RESUME
    const val CANCEL = VoiceNoteRules.CANCEL
    const val UNDO = WatchCaptureRules.UNDO
    const val UNDONE = "Taken back"
    const val CANCELLED = "Voice note cancelled · nothing kept"
    const val NOTHING_HEARD = "Didn't catch any words · tap Voice note to try again"
    const val WHERE = "On your phone in Needs you"
    const val NOTE = "Words only · nothing is recorded on the watch"
    /** What a screen reader says for the button. */
    const val SPOKEN = "Voice note. Double tap and talk; Navilife pulls out what to add."
    /** How long the result stays (it has more to read than a capture's line). */
    const val RESULT_MS = 8_000L
    /** How much of the tidy note a round screen shows. */
    const val TIDY_MAX = 120
    /** A recogniser that heard nothing this long in a row (no words at all) is let go: he's stopped talking. */
    const val QUIET_STOP_MS = 60_000L

    /** Whether what Capture heard asks for a voice note ("take a voice note", "voice memo"). */
    fun asks(heard: String?): Boolean =
        heard != null && TalkOpenRules.request(heard, TalkOpenRules.WATCH) == TalkPlace.VOICE_NOTE

    /** The line under the dot: "Recording · 0:42", "Paused · 1:10", "… · stops at 5:00". */
    fun recordingLine(ms: Long, paused: Boolean): String = VoiceNoteRules.recordingLine(ms, paused)

    /** Whether the brass of the last 30 seconds lights the line. */
    fun nearEnd(ms: Long): Boolean = ms >= VoiceNoteRules.WARN_MS

    /**
     * After the recogniser finishes one stretch: listen again unless he paused or stopped, the five minutes are up, or
     * it has heard nothing at all for [QUIET_STOP_MS] (the note then ends by itself, as if Done).
     */
    fun listenAgain(elapsedMs: Long, paused: Boolean, stopped: Boolean, quietMs: Long): Boolean =
        !paused && !stopped && !VoiceNoteRules.atLimit(elapsedMs) && quietMs < QUIET_STOP_MS

    /** The words so far, as shown while recording: the latest [MAX_LIVE] characters, cut at a word. */
    fun live(segments: List<String>, partial: String?): String? {
        val t = (segments + listOfNotNull(partial?.takeIf { it.isNotBlank() })).joinToString(" ") { it.trim() }.trim()
        if (t.isEmpty()) return null
        if (t.length <= MAX_LIVE) return t
        val tail = t.takeLast(MAX_LIVE)
        return "…" + tail.substring((tail.indexOf(' ') + 1).coerceAtMost(tail.length)).trimStart()
    }
    private const val MAX_LIVE = 90

    /** The words that go to the AI (as on the phone: segments joined into sentences). "" when nothing was heard. */
    fun words(segments: List<String>, partial: String?): String =
        VoiceNoteRules.transcript(segments + listOfNotNull(partial?.takeIf { it.isNotBlank() }))

    /** The tidy note for a round screen: at most [TIDY_MAX] characters, cut at a word; null when empty. */
    fun shortTidy(tidy: String): String? {
        val t = tidy.replace(Regex("""\s+"""), " ").trim()
        if (t.isEmpty()) return null
        if (t.length <= TIDY_MAX) return t
        val cut = t.take(TIDY_MAX)
        return cut.take(cut.lastIndexOf(' ').takeIf { it > TIDY_MAX / 2 } ?: TIDY_MAX).trimEnd(',', ';', ' ') + "…"
    }

    /** When the AI read it: [cards] offered (0: nothing to add), its [tidy] note and [people]. */
    fun tidied(cards: Int, tidy: String, people: List<String>): WatchVoiceNoted = WatchVoiceNoted(
        line = VoiceNoteRules.tidiedLine(cards),
        whereLine = if (cards > 0) WHERE else null,
        tidy = shortTidy(tidy),
        peopleLine = VoiceNoteRules.peopleLine(people),
        taskId = null,
        cards = cards,
    )

    const val KEPT_OFFLINE = "Navilife couldn't read it just now · your words are kept as a task"
    const val KEPT_SHORT = "Kept as a task"

    /**
     * When the AI couldn't answer (offline, off, over budget: [offline]) or the note was too short to read
     * ([offline] false): the words were kept as task [taskId].
     */
    fun keptAsTask(taskId: String, offline: Boolean): WatchVoiceNoted = WatchVoiceNoted(
        line = if (offline) KEPT_OFFLINE else KEPT_SHORT,
        whereLine = null, tidy = null, peopleLine = null, taskId = taskId, cards = 0,
    )

    /** What a screen reader says for the result: the line, the tidy note, where the cards are, and Undo. */
    fun spoken(v: WatchVoiceNoted): String = listOfNotNull(
        v.line, v.tidy, v.peopleLine, v.whereLine, if (v.taskId != null) "$UNDO for ${RESULT_MS / 1000} seconds" else null,
    ).joinToString(". ") + "."
}
