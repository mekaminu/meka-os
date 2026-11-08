package os.meka.core.domain

/**
 * Obi on calls: **Remind me** on a call back Obi agreed with a live caller (slice 1c's "Call back Sat 7 Nov, 10:30").
 * Non-AI, pure. A tap makes one task, "Call back <who>", on that day at that time with a reminder [LEAD_MIN] minutes
 * before (or at the time itself when that has nearly come), so it reminds through the governor like any task and
 * stays in Today after the after-work card is Done. One task per message ([taskId]), so a tap on the Fold and the
 * Mac makes one. Never for a scam, a call Obi ended, or a time already gone.
 */
object CallbackReminderRules {
    const val REMIND_ME = "Remind me"
    const val SET_PREFIX = "Reminder set"
    const val GONE = "That call back time has gone"
    const val UNKNOWN = "That message has gone"
    /** Minutes before the call back that the reminder rings. */
    const val LEAD_MIN = 10
    private const val MIN_MS = 60_000L

    /** Whether [item] gets Remind me: a call assistant message with a call back agreed, not a scam, still ahead. */
    fun offers(item: CapturedItem, nowMs: Long): Boolean =
        item.fromCallAssistant && !item.endedAsScam && item.ended == null && item.callback != null &&
            (item.callbackAtMs ?: 0L) > nowMs

    /** The one task a message's Remind me makes. */
    fun taskId(itemId: String): String = "callback-$itemId"

    fun taskTitle(item: CapturedItem): String = "Call back ${item.personName.trim().ifEmpty { "the caller" }}"

    /** What the task's notes say: that Obi agreed it, what they said, and their number when there is one. */
    fun taskNotes(item: CapturedItem): String = buildList {
        add("Obi agreed this time with them on a call.")
        (item.summary ?: item.text)?.trim()?.takeIf { it.isNotEmpty() && !item.automated }?.let { add("They said: $it") }
        item.callerNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Their number: $it") }
    }.joinToString("\n")

    /** When the reminder rings: [LEAD_MIN] before the call back, or at it when that is already past; null = gone. */
    fun remindAtMs(callbackAtMs: Long, nowMs: Long): Long? {
        if (callbackAtMs <= nowMs) return null
        val early = callbackAtMs - LEAD_MIN * MIN_MS
        return if (early > nowMs) early else callbackAtMs
    }

    /** "Reminder set · Sat 7 Nov · 10:20", "Reminder set · Today · 17:20". */
    fun setLine(remindAtMs: Long, nowMs: Long, cal: LocalCalendar): String =
        "$SET_PREFIX · " + TaskWhenRules.label(cal.epochDayOf(remindAtMs), cal.minuteOfDay(remindAtMs), cal.epochDayOf(nowMs))

    /**
     * The chip under the message: [REMIND_ME] when it can be tapped, the set line when its task is open with a
     * reminder ahead ([task]), null when there's nothing to offer.
     */
    fun chip(item: CapturedItem, task: Task?, nowMs: Long, cal: LocalCalendar): CallbackChip? {
        val open = task?.takeIf { !it.lifecycle.isTerminal }
        val at = open?.remindAtMs?.takeIf { it > nowMs }
        if (at != null) return CallbackChip(setLine(at, nowMs, cal), done = true)
        if (open != null) return CallbackChip("$SET_PREFIX · ${taskTitle(item)}", done = true)
        return if (offers(item, nowMs)) CallbackChip(REMIND_ME, done = false) else null
    }
}

/** The Remind me chip as shown: [label], and [done] once the task is made (no longer tappable). */
data class CallbackChip(val label: String, val done: Boolean)
