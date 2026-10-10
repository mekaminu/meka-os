package os.meka.core.domain

/**
 * "Unknown caller · Block?" (build plan "Call assistant live — polish" 8b b, Meka 2026-10-09 after a spoofed scam
 * call): after a call from a number nobody knows, the Fold posts one quiet notification offering Block and
 * Report to 7726, so a scam number goes on the block list in one tap without opening MEKA. Non-AI and pure.
 *
 * The Fold's call screening marks the call to look at ([watch]); a minute later the phone reads how it ended from its
 * own call log (only with the call-log permission Meka granted; never sent anywhere) and [offer] words the
 * notification, at most once a day per number. Calls declined at work aren't offered: they are in the after-work
 * summary, whose held message already has Block · Report to 7726, and work mode posts nothing that can wait.
 */
enum class CallOutcome {
    MISSED,
    DECLINED,
    ANSWERED,
    /** Sent to the assistant as likely spam (the network couldn't verify the number). */
    TO_ASSISTANT,
    /** No call-log permission, or the entry wasn't found: worded without how it ended. */
    UNKNOWN,
}

data class UnknownCallOffer(
    /** The number's key ([CallScreeningRules.callerKey]); one notification per number. */
    val key: String,
    /** The number as it called, for Block and the 7726 text. */
    val number: String,
    /** "Unknown caller · 01904 618691" */
    val title: String,
    /** "Missed call at 14:05 · Block it?" */
    val line: String,
    /** Saved with the number on the block list: "Missed call · Fri 9 Oct". */
    val why: String,
    /** "Call 01904618691", for Report to 7726. */
    val reportText: String,
)

object UnknownCallRules {
    /** The first look at the call log, once the call has likely ended. */
    const val CHECK_AFTER_MS = 60_000L
    /** A call still going (no log entry yet) is looked at again, 2, 4, 6… minutes later (linear back-off)… */
    const val RECHECK_MS = 2 * 60_000L
    /** …this many looks at most (about 20 minutes after the first), then offered without how it ended. */
    const val MAX_CHECKS = 5
    /** One offer per number a day: a scammer ringing five times gets one notification. */
    const val ONCE_PER_MS = 24 * 60 * 60_000L
    /** A log entry this long before the screening is a different call. */
    const val LOG_SLOP_MS = 10_000L

    const val CHANNEL_NAME = "Unknown callers"
    const val CHANNEL_ABOUT = "After a call from a number nobody knows: Block it or report it to 7726. Quiet, no sound."
    const val BLOCK_ACTION = BlockedCallerRules.BLOCK_LABEL
    const val REPORT_ACTION = BlockedCallerRules.REPORT_LABEL

    /** Android's `CallLog.Calls.TYPE` values (stable since API 1/24), so the mapping is tested here. */
    const val LOG_INCOMING = 1
    const val LOG_MISSED = 3
    const val LOG_VOICEMAIL = 4
    const val LOG_REJECTED = 5
    const val LOG_BLOCKED = 6

    /**
     * Whether the phone should look at this call afterwards: a real number, not on the family or always-notify lists,
     * not a contact, not someone Meka called lately, not this phone's own number, and either let ring or sent to the
     * assistant as likely spam (or as a Suspected spam number). Blocked calls, calls declined at work, the off switch's own logic and withheld
     * numbers (nothing to block) are left alone.
     */
    fun watch(decision: CallDecision, number: String?, signals: CallSignals, ownNumber: Boolean = false): Boolean {
        if (BlockedCallerRules.keyOf(number) == null || ownNumber) return false
        if (decision.listedName != null || signals.knownContact || signals.calledRecently) return false
        return decision.verdict == CallVerdict.RING || decision.reason == CallReason.LIKELY_SPAM || decision.reason == CallReason.SUSPECTED_SPAM
    }

    /** How a call ended, from its call-log [type] and [durationS]; null [type] (no entry, no permission) is [CallOutcome.UNKNOWN]. */
    fun outcomeOf(type: Int?, durationS: Long, sentToAssistant: Boolean = false): CallOutcome = when {
        sentToAssistant -> CallOutcome.TO_ASSISTANT
        type == null -> CallOutcome.UNKNOWN
        type == LOG_MISSED -> CallOutcome.MISSED
        type == LOG_REJECTED || type == LOG_BLOCKED || type == LOG_VOICEMAIL -> CallOutcome.DECLINED
        type == LOG_INCOMING -> if (durationS > 0) CallOutcome.ANSWERED else CallOutcome.MISSED
        else -> CallOutcome.UNKNOWN
    }

    /**
     * The notification for a call from [number] at [atMs] that ended as [outcome] ([durationS] when answered), or null:
     * not a number, on the block list now ([blocked], keys), or already offered in the last day ([offered]).
     */
    fun offer(
        number: String?,
        outcome: CallOutcome,
        durationS: Long,
        atMs: Long,
        nowMs: Long,
        blocked: Set<String>,
        offered: List<ScreenedCall>,
        cal: LocalCalendar,
    ): UnknownCallOffer? {
        val key = BlockedCallerRules.keyOf(number) ?: return null
        if (key in blocked) return null
        if (offered.any { it.callerKey == key && nowMs - it.atMs in 0 until ONCE_PER_MS }) return null
        val at = LocalClock.formatMinute(cal.minuteOfDay(atMs))
        val dayLabel = SearchRules.dayLabel(cal.epochDayOf(atMs), cal.epochDayOf(nowMs))
        val day = when (dayLabel) { "Today" -> ""; "Yesterday" -> "yesterday "; else -> "$dayLabel " }
        val line = when (outcome) {
            CallOutcome.MISSED -> "Missed call ${day}at $at"
            CallOutcome.DECLINED -> "Declined ${day}at $at"
            CallOutcome.ANSWERED -> "Call ${day}at $at · ${length(durationS)}"
            CallOutcome.TO_ASSISTANT -> "Sent to your assistant ${day}at $at · the network couldn't verify the number"
            CallOutcome.UNKNOWN -> "Called ${day}at $at"
        } + " · Block it?"
        val what = when (outcome) {
            CallOutcome.MISSED -> "Missed call"
            CallOutcome.DECLINED -> "Declined call"
            CallOutcome.ANSWERED -> "Answered call"
            CallOutcome.TO_ASSISTANT -> "Likely spam call"
            CallOutcome.UNKNOWN -> "Unknown caller"
        }
        val shown = BlockedCallerRules.display(number!!)
        return UnknownCallOffer(
            key = key,
            number = number.trim(),
            title = "Unknown caller · $shown",
            line = line,
            why = "$what · ${CivilDate.shortLabel(cal.epochDayOf(atMs))}",
            reportText = BlockedCallerRules.reportText(number)!!,
        )
    }

    /** The offers made after [key] was offered at [nowMs]: older than a day dropped, one entry per number. */
    fun remember(offered: List<ScreenedCall>, key: String, nowMs: Long): List<ScreenedCall> =
        (offered.filter { nowMs - it.atMs in 0 until ONCE_PER_MS && it.callerKey != key } + ScreenedCall(key, nowMs)).takeLast(MAX_REMEMBERED)

    private const val MAX_REMEMBERED = 100

    /** "45 s", "2 min", "1 h 05". */
    fun length(durationS: Long): String = when {
        durationS < 60 -> "${durationS.coerceAtLeast(0)} s"
        durationS < 3600 -> "${durationS / 60} min"
        else -> "${durationS / 3600} h ${(durationS % 3600 / 60).toString().padStart(2, '0')}"
    }
}
