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
}
