package os.meka.core.domain

/**
 * Obi on calls, recordings with both voices (Meka 2026-11-08 21:08: "the recording starts when the caller talks; I want
 * to hear what the AI says too"). A call Obi talks to live is recorded by the phone service itself, both sides on their
 * own channel, from the moment Google opens Obi's side (so his first word is on it) until his part of the call ends; a
 * put-through to Meka's mobile is never recorded. The recording is then kept exactly like a caller's message (the same
 * key, encryption, 7-day keep, Play and Delete recording) and deleted from the phone service.
 *
 * Nothing is recorded when "Keep callers' recordings" is Don't keep, or the server has nowhere to keep it; then Obi
 * doesn't say the call is recorded either.
 */
object CallRecordingRules {
    /** Said straight after Obi's opening on a recorded call. */
    const val RECORDED_LINE = "This call is recorded."

    /** The phone service's recording settings: each side on its own channel, both tracks. */
    const val CHANNELS = "dual"
    const val TRACK = "both"

    /** How long the server waits for a live call's message to be written before it keeps the recording beside it. */
    const val WAIT_FOR_MESSAGE_MS = 20_000L
    const val WAIT_STEP_MS = 1_000L

    /** Whether a live call is recorded: a recording can be kept ([keepDays] > 0) and the server has a store for it. */
    fun records(keepDays: Int, hasStore: Boolean): Boolean = hasStore && keepDays > 0

    /** Obi's opening line on a call: the usual one, then [RECORDED_LINE] when [recorded]. */
    fun opening(recorded: Boolean): String = if (recorded) ObiCallRules.OPENING + " " + RECORDED_LINE else ObiCallRules.OPENING

    /** Only a finished recording is kept; anything else (absent or failed) is only deleted. */
    fun completed(status: String?): Boolean = status?.trim()?.lowercase() == "completed"

    // ---- Booking calls (slice 2): Obi's outbound calls are recorded the same way, kept beside the call card ----

    /** The result card's label over the player. */
    const val BOOKING_LABEL = "Listen to the call"
    /** Read out with the player, and the player's own words for what it plays. */
    const val BOOKING_SPOKEN = "the call's recording"
    /** Under the player: whose voices, how long it's kept. */
    const val BOOKING_FOOT = "Obi and them, from Obi's first word · kept 7 days, then deleted"

    private val bookingIdPattern = Regex("^[0-9a-f]{32}$")
    private val householdPattern = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** A call card's id as the apps make them ([IdGenerator]); anything else is refused. */
    fun isBookingId(id: String): Boolean = bookingIdPattern.matches(id)

    /**
     * The bucket key of a call card's recording, under the household's recordings prefix so the hourly sweep (and
     * "Don't keep") covers it like a caller's message; null for an id or household that couldn't be one.
     */
    fun bookingKey(householdId: String, bookingId: String): String? {
        if (!householdPattern.matches(householdId) || !isBookingId(bookingId)) return null
        return "${VoiceRecordingRules.PREFIX}$householdId/call-$bookingId.mp3"
    }

    /**
     * Whether a call card's recording is offered: kept ([audio]), the call over ([endedAtMs]) and younger than the
     * chosen [keepDays] (7; none with "Don't keep").
     */
    fun bookingPlayable(audio: Boolean, endedAtMs: Long?, nowMs: Long, keepDays: Int = VoiceRecordingRules.DEFAULT_KEEP_DAYS): Boolean =
        audio && endedAtMs != null && nowMs - endedAtMs in 0 until VoiceRecordingRules.keepMs(keepDays)

    // ---- Slice 3: the player shows who's speaking along the words ----

    /**
     * [raw]'s line starts ([BookingWords.times]) as numbers, only when there is one per line of the [lines]-line
     * transcript, none negative, and in order; otherwise empty (the player then shows no speaker).
     */
    fun wordTimes(raw: String?, lines: Int): List<Long> {
        if (raw.isNullOrBlank() || lines <= 0) return emptyList()
        val ts = raw.split(',').map { it.trim().toLongOrNull() ?: return emptyList() }
        if (ts.size != lines || ts.any { it < 0 } || ts.zipWithNext().any { (a, b) -> b < a }) return emptyList()
        return ts
    }

    /** A line shows this much before its recorded start (the words reach the server a moment after they're said). */
    const val LEAD_MS = 250L

    /**
     * Who is speaking [positionMs] into the call's recording, and what they're saying: the last line that has started
     * by then. Null before the first line, without times, or when the times don't fit the lines.
     */
    fun speaking(lines: List<BookingWordLine>, atMs: List<Long>, positionMs: Long): SpeakingNow? {
        if (lines.isEmpty() || atMs.size != lines.size || positionMs < 0) return null
        val i = atMs.indexOfLast { it <= positionMs + LEAD_MS }
        if (i < 0) return null
        val l = lines[i]
        return SpeakingNow(l.who, l.text, l.obi, i)
    }

    /** [speaking] for a call card's row (the Mac's player reads it this way). */
    fun speakingInRow(row: BookingCallRow, positionMs: Long): SpeakingNow? = speaking(row.transcript, row.transcriptAtMs, positionMs)

    /** What a screen reader says for the speaker line: "Obi is speaking: …". */
    fun speakingSpoken(s: SpeakingNow): String = "${s.who} is speaking: ${s.text}"
}

/** Who is speaking at a point in a call's recording ([CallRecordingRules.speaking]); [index] is the transcript line. */
data class SpeakingNow(val who: String, val text: String, val obi: Boolean, val index: Int)
