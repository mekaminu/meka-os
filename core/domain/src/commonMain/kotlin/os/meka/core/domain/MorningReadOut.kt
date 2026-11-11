package os.meka.core.domain

/*
 * The morning read-out (Meka 2026-11-11 06:40: "My start of day reading out is no longer happening"; non-AI, pure).
 *
 * The Today rebuild took the Morning brief card off Today (2026-11-09), and with it the way his day started: the card
 * was where the brief was opened and heard each morning, so after the rebuild only the wake alarm's Dismiss still read
 * it. Now the brief is read on its own, without the card: the first open of Today in the morning reads it once, in
 * Obi's voice, with a "Reading your morning brief · Stop" line over Today. Once a day on the device (the wake alarm's
 * read counts), never once the brief has been opened today, never in quiet hours, at work, on a call, or with the
 * phone on silent or vibrate unless headphones (or the car) are on.
 */
object MorningReadOutRules {
    const val LINE = "Reading your morning brief"
    const val STOP = "Stop"
    const val PREPARING = "Getting your morning brief…"

    /** Why the read-out doesn't start on this open (for tests and the voice log); null when it reads. */
    enum class Hold { ALREADY_TODAY, NOT_MORNING, NOT_READY, SEEN, QUIET_HOURS, AT_WORK, ON_CALL, SILENT }

    /**
     * Whether this open of Today reads the brief: [lastDay] the epoch day it last read on this device (null: never),
     * [minuteOfDay] now, [startMinute] when the brief starts (the end of quiet hours, else 07:00), until noon
     * ([BriefRules.END_MIN]); [ready] the brief has its day in; [seenToday] it was already opened today (on any device).
     */
    fun hold(
        lastDay: Long?, today: Long, minuteOfDay: Int, startMinute: Int, ready: Boolean, seenToday: Boolean,
        quiet: Boolean, atWork: Boolean, onCall: Boolean, silent: Boolean, private: Boolean,
    ): Hold? = when {
        lastDay == today -> Hold.ALREADY_TODAY
        minuteOfDay < startMinute || minuteOfDay >= BriefRules.END_MIN -> Hold.NOT_MORNING
        !ready -> Hold.NOT_READY
        seenToday -> Hold.SEEN
        quiet -> Hold.QUIET_HOURS
        atWork -> Hold.AT_WORK
        onCall -> Hold.ON_CALL
        silent && !private -> Hold.SILENT
        else -> null
    }

    fun reads(
        lastDay: Long?, today: Long, minuteOfDay: Int, startMinute: Int, ready: Boolean, seenToday: Boolean,
        quiet: Boolean, atWork: Boolean, onCall: Boolean, silent: Boolean, private: Boolean,
    ): Boolean = hold(lastDay, today, minuteOfDay, startMinute, ready, seenToday, quiet, atWork, onCall, silent, private) == null

    /** The line over Today while it reads (or gets the words ready), with Stop beside it. */
    fun line(preparing: Boolean): String = if (preparing) PREPARING else LINE
}
