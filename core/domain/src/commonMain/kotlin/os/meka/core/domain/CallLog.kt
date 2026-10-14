package os.meka.core.domain

/**
 * Call notifications that tell him something, slice 4: **Activity → Calls** and the **call detail** (Meka 2026-10-14
 * 18:38 / 18:57). The call assistant's messages and the missed calls held at work, from the last 7 days, newest first:
 * one line each with who, what it was about and a block chip; tapped, the detail — a small timeline (rang 14:02 ·
 * your call assistant answered · message 0:24 · said it's urgent), the AI's summary, the words, the spam flag and its
 * reason, whether the number is blocked now and in future, and the same buttons as the notification. Non-AI and pure;
 * the same words on every device. Numbers and words are display only (untrusted, ADR-006) and never logged.
 */
data class HeldCall(val item: CapturedItem, val cleared: Boolean)

data class CallDetail(
    val who: String,
    /** "Rang · 14:02", "Your call assistant answered", "Left a 0:24 message", "Said it's urgent", … */
    val timeline: List<String>,
    /** The AI's one-line summary (never for an automated call). */
    val summary: String?,
    /** The words in quotes, or why there are none ("Words cleared when you pressed Done"); null for a missed call. */
    val words: String?,
    /** "Flagged as likely spam · …" with the AI's reason, while the number is flagged. */
    val spam: String?,
    /** [CallNoticeRules.blockStatus]: blocked now and in future, always stated. */
    val blockStatus: String,
    /** The number to call back or block; null when withheld. */
    val number: String?,
    /** The block list's key for [number] (Unblock / Not spam take it); null when withheld. */
    val key: String?,
    /** ▶ Listen 0:24, while the server keeps the recording; null otherwise. */
    val listen: String?,
    val callBack: String?,
    val block: String?,
    val unblock: String?,
    val notSpam: String?,
)

data class CallLogRow(
    val id: String,
    val time: String,
    val who: String,
    /** One line: what it was about ("Dentist confirming Thursday 3pm", "Likely automated call", "Rang · you missed it"). */
    val line: String,
    /** The block chip: "Blocked", "Not blocked", "Never blocked", "Flagged as spam", "Withheld". */
    val chip: String,
    val blocked: Boolean,
    val urgent: Boolean,
    val automated: Boolean,
    val detail: CallDetail,
)

data class CallLogDay(val label: String, val rows: List<CallLogRow>)

data class CallLogView(val days: List<CallLogDay>) {
    val isEmpty: Boolean get() = days.isEmpty()
    val count: Int get() = days.sumOf { it.rows.size }

    /** "4 calls in the last 7 days · 1 blocked"; null when there are none. */
    val line: String? get() {
        if (isEmpty) return null
        val blocked = days.sumOf { d -> d.rows.count { it.blocked } }
        return "${plural(count, "call")} in the last 7 days" + (if (blocked > 0) " · $blocked blocked" else "")
    }

    fun row(id: String): CallLogRow? = days.firstNotNullOfOrNull { d -> d.rows.firstOrNull { it.id == id } }

    companion object { val EMPTY = CallLogView(emptyList()) }
}

object CallLogRules {
    const val TITLE = "Recent calls"
    const val HINT = "Calls your call assistant took and calls you missed at work. Tap one for what happened"
    const val MAX = 30

    const val CHIP_BLOCKED = "Blocked"
    const val CHIP_NOT_BLOCKED = "Not blocked"
    const val CHIP_NEVER = "Never blocked"
    const val CHIP_FLAGGED = "Flagged as spam"
    const val CHIP_WITHHELD = "Withheld"

    const val MISSED_LINE = "Rang · you missed it"
    const val ASSISTANT_ANSWERED = "Your call assistant answered"
    const val AWAY = "Outside your work hours"
    const val KEYPAD_URGENT = "Pressed a key for urgent with no words, so it didn't break through"
    const val CLEARED_WORDS = "Words cleared when you pressed Done"
    const val FLAGGED = "Flagged as likely spam"

    /**
     * Slice 5: a call notification's tap opens Activity → Calls with the call unfolded. The open value is
     * [OPEN_PREFIX] + the held message's id ("" when the notice isn't tied to one, e.g. the after-call notice).
     */
    const val OPEN_PREFIX = "calls:"

    fun openCall(heldId: String?): String = OPEN_PREFIX + heldId.orEmpty()

    /** The held id in an open value ("" = just Activity → Calls), or null when [open] isn't a call's. */
    fun callFromOpen(open: String?): String? = open?.takeIf { it.startsWith(OPEN_PREFIX) }?.removePrefix(OPEN_PREFIX)

    /**
     * Which row to unfold for a tapped notification: the row for [heldId] when it is listed, else the newest row from
     * the caller with block-list key [key] (the after-call notice and an automatic block know only the number), else
     * none (the list just opens).
     */
    fun target(view: CallLogView, heldId: String?, key: String?): String? {
        heldId?.takeIf { it.isNotEmpty() }?.let { id -> view.row(id)?.let { return it.id } }
        if (key == null) return null
        return view.days.firstNotNullOfOrNull { d -> d.rows.firstOrNull { it.detail.key == key } }?.id
    }

    /** Names a caller known only by number (as the after-work summary does), keeping the number for Call back / Block. */
    fun named(item: CapturedItem, names: CallerNames, lists: PeopleLists): CapturedItem {
        if (!People.key(item.personName).startsWith("tel:")) return item
        val number = item.callerNumber ?: item.personName
        return item.copy(personName = names.nameOf(number, lists), callerNumber = number)
    }

    fun dayLabel(day: Long, today: Long): String = when (day) {
        today -> "Today"
        today - 1 -> "Yesterday"
        else -> CivilDate.shortLabel(day)
    }

    /** The row's one line: what it was about, then "Said it's urgent" when the caller spoke and said so. */
    fun line(item: CapturedItem, cleared: Boolean): String {
        if (item.kind == CaptureKind.MISSED_CALL) return MISSED_LINE
        if (item.automated) return CallNoticeRules.AUTOMATED_LABEL
        val about = item.summary?.takeIf { !cleared }
            ?: item.seconds?.let { if (it < CallNoticeRules.AUTOMATED_SECONDS) "Hung up after ${it.coerceAtLeast(0)} s" else "Left a ${CallNoticeRules.length(it)} message" }
            ?: "Left a message"
        return about + (if (CallNoticeRules.urgent(item)) " · " + CallNoticeRules.SAID_URGENT else "")
    }

    /** The block chip and whether it is the blocked one. */
    fun chip(item: CapturedItem, blocked: BlockedCallersView): Pair<String, Boolean> {
        val n = CallNoticeRules.numberOf(item) ?: return (if (CallNoticeRules.known(item)) CHIP_NEVER else CHIP_WITHHELD) to false
        val key = BlockedCallerRules.keyOf(n)
        return when {
            blocked.has(n) -> CHIP_BLOCKED to true
            CallNoticeRules.known(item) -> CHIP_NEVER to false
            key != null && key in blocked.suspectedKeys -> CHIP_FLAGGED to false
            else -> CHIP_NOT_BLOCKED to false
        }
    }

    /** The small timeline: when it rang, who answered, the message, urgency and whether it reads as automated. */
    fun timeline(item: CapturedItem, cal: LocalCalendar): List<String> {
        val at = LocalClock.formatMinute(cal.minuteOfDay(item.atMs))
        if (item.kind == CaptureKind.MISSED_CALL) return listOf("Rang · $at", "You missed it")
        return listOfNotNull(
            "Rang · $at" + (if (item.away) " · $AWAY" else ""),
            ASSISTANT_ANSWERED,
            when {
                item.seconds != null && item.seconds < CallNoticeRules.AUTOMATED_SECONDS -> "Hung up after ${item.seconds.coerceAtLeast(0)} s"
                item.seconds != null -> "Left a ${CallNoticeRules.length(item.seconds)} message"
                else -> "Left a message"
            },
            when {
                CallNoticeRules.urgent(item) -> CallNoticeRules.SAID_URGENT
                item.urgent && item.urgentBy == CallNoticeRules.BY_KEYPAD -> KEYPAD_URGENT
                else -> null
            },
            CallNoticeRules.AUTOMATED_LABEL.takeIf { item.automated },
        )
    }

    /** The words in full, or why there are none. */
    fun words(item: CapturedItem, cleared: Boolean): String? {
        if (item.kind == CaptureKind.MISSED_CALL) return null
        if (cleared) return CLEARED_WORDS
        if (item.automated) return CallNoticeRules.about(item) + (item.text?.trim()?.takeIf { it.isNotEmpty() }?.let { " · “$it”" } ?: "")
        item.text?.trim()?.takeIf { it.isNotEmpty() }?.let { return "“$it”" }
        return if (item.transcribing) CallNoticeRules.TRANSCRIBING else CallNoticeRules.NO_WORDS
    }

    fun detail(item: CapturedItem, cleared: Boolean, blocked: BlockedCallersView, cal: LocalCalendar): CallDetail {
        val n = CallNoticeRules.numberOf(item)
        val key = BlockedCallerRules.keyOf(n)
        val known = CallNoticeRules.known(item)
        val isBlocked = blocked.has(n)
        val suspect = key?.let { k -> blocked.suspects.firstOrNull { it.key == k } }?.takeIf { !isBlocked && !known }
        val canPlay = item.hasAudio && !cleared
        return CallDetail(
            who = CallNoticeRules.who(item),
            timeline = timeline(item, cal),
            summary = item.summary?.takeIf { !item.automated && !cleared },
            words = words(item, cleared),
            spam = suspect?.let { s -> FLAGGED + (s.line.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") },
            blockStatus = CallNoticeRules.blockStatus(item, blocked),
            number = n, key = key,
            listen = if (canPlay) CallNoticeRules.listenLabel(item.seconds) else null,
            callBack = n?.let { CallNoticeRules.CALL_BACK },
            block = if (n != null && !known && !isBlocked) CallNoticeRules.BLOCK else null,
            unblock = if (n != null && isBlocked) CallNoticeRules.UNBLOCK else null,
            notSpam = if (suspect != null) CallNoticeRules.NOT_SPAM else null,
        )
    }

    /**
     * Activity → Calls: [calls] ([HeldMessages.calls], newest first) grouped by day, each named with [names] and
     * [lists] (the Fold's contacts; the Mac shows numbers), with [blocked] for the chips and the block line.
     */
    fun view(
        calls: List<HeldCall>, blocked: BlockedCallersView, nowMs: Long, cal: LocalCalendar,
        names: CallerNames = CallerNames.NONE, lists: PeopleLists = PeopleLists(),
    ): CallLogView {
        if (calls.isEmpty()) return CallLogView.EMPTY
        val today = cal.epochDayOf(nowMs)
        val rows = calls.sortedWith(compareByDescending<HeldCall> { it.item.atMs }.thenBy { it.item.id }).take(MAX).map { c ->
            val item = named(c.item, names, lists)
            val (chip, isBlocked) = chip(item, blocked)
            cal.epochDayOf(item.atMs) to CallLogRow(
                id = item.id,
                time = LocalClock.formatMinute(cal.minuteOfDay(item.atMs)),
                who = CallNoticeRules.who(item),
                line = line(item, c.cleared),
                chip = chip, blocked = isBlocked,
                urgent = item.kind == CaptureKind.VOICE_MESSAGE && CallNoticeRules.urgent(item),
                automated = item.automated,
                detail = detail(item, c.cleared, blocked, cal),
            )
        }
        val days = rows.groupBy({ it.first }, { it.second }).entries.sortedByDescending { it.key }
            .map { (day, r) -> CallLogDay(dayLabel(day, today), r) }
        return CallLogView(days)
    }
}
