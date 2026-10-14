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
    /**
     * Slice 6: for a call the phone screened (not a held message), what Block puts on the list as why ("Rang · today");
     * the Fold blocks the [number] with it. Null for a held call (blocked by its id).
     */
    val blockWhy: String? = null,
)

/**
 * Call notifications, slice 6: a call the Fold's screening saw — rang through, sent to the call assistant, or stopped
 * before it rang. Kept on the phone only (its own preferences, the last 7 days), never synced, sent or logged; the
 * number is display only. [reason] null = Navilife didn't answer Android in time, so it rang. [known] = a contact,
 * someone he called lately, the household or a listed person (their calls that rang aren't listed: Activity → Calls is
 * for calls Navilife handled and numbers he doesn't know).
 */
data class PhoneCall(
    val atMs: Long,
    val number: String?,
    val verdict: CallVerdict,
    val reason: CallReason?,
    val known: Boolean,
)

object PhoneCallRules {
    const val MAX = 40
    const val KEEP_MS = 7 * 86_400_000L

    /** [call] added, newest first, the last 7 days, at most [MAX]. */
    fun add(list: List<PhoneCall>, call: PhoneCall, nowMs: Long): List<PhoneCall> =
        (listOf(call) + list).filter { nowMs - it.atMs < KEEP_MS }.sortedByDescending { it.atMs }.take(MAX)

    /** One line per call, "at\tverdict\treason\tknown\tnumber" ("-" for none). */
    fun encode(list: List<PhoneCall>): String = list.take(MAX).joinToString("\n") {
        listOf(it.atMs.toString(), it.verdict.name, it.reason?.name ?: "-", if (it.known) "1" else "0", clean(it.number) ?: "-").joinToString("\t")
    }

    /** Unreadable lines skipped; null or blank = none. */
    fun decode(s: String?): List<PhoneCall> = s.orEmpty().lineSequence().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size != 5) return@mapNotNull null
        val at = p[0].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val verdict = CallVerdict.entries.firstOrNull { it.name == p[1] } ?: return@mapNotNull null
        val reason = CallReason.entries.firstOrNull { it.name == p[2] }
        PhoneCall(at, p[4].takeIf { it != "-" && it.isNotBlank() }, verdict, reason, p[3] == "1")
    }.sortedByDescending { it.atMs }.take(MAX).toList()

    private fun clean(n: String?) = n?.replace('\t', ' ')?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
}

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
    const val HINT = "Calls your call assistant took, calls Navilife stopped, numbers you don't know and calls you missed at work. Tap one for what happened"
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

    // ---- Slice 6: calls the phone screened ----
    const val PHONE_PREFIX = "phone:"
    const val RANG_THROUGH = "Rang through"
    const val STOPPED = "Stopped before it rang"
    const val TO_ASSISTANT = "Sent to your call assistant"
    const val NO_MESSAGE = "Left no message"
    const val NO_RING = "No ring and no notification"
    const val TOO_SLOW = "Navilife didn't decide in time, so it rang"
    /** A held call within this long after a screened call is the same call (its message or missed-call notice). */
    const val SAME_CALL_MS = 10 * 60_000L

    fun isPhone(id: String): Boolean = id.startsWith(PHONE_PREFIX)

    /** Why a call rang, in plain words ("Family", "Called again within 3 minutes"); null when nothing worth saying. */
    fun rangWhy(reason: CallReason?): String? = when (reason) {
        null -> TOO_SLOW
        CallReason.FAMILY -> "Family"
        CallReason.ALWAYS_RING -> "On Always notify"
        CallReason.REPEAT -> "Called again within 3 minutes"
        CallReason.OFFICIAL_CLAIM -> "Claims to be an official body, so Navilife never blocks it"
        CallReason.EMERGENCY -> "An emergency service is never screened"
        CallReason.PAUSED -> CallReason.PAUSED.label
        else -> null
    }

    /** The row's one line for a screened call. */
    fun phoneLine(c: PhoneCall): String = when (c.verdict) {
        CallVerdict.RING -> RANG_THROUGH + when (c.reason) {
            CallReason.OFFICIAL_CLAIM -> " · claims to be an official body"
            CallReason.EMERGENCY -> " · emergency service"
            else -> ""
        }
        CallVerdict.DECLINE -> "$TO_ASSISTANT · ${NO_MESSAGE.lowercase()}"
        CallVerdict.BLOCK -> STOPPED + (c.reason?.let { " · " + lowerFirst(it.label) } ?: "")
    }

    /** The screened call's timeline: when it rang, what Navilife did and why. */
    fun phoneTimeline(c: PhoneCall, cal: LocalCalendar): List<String> {
        val at = LocalClock.formatMinute(cal.minuteOfDay(c.atMs))
        return when (c.verdict) {
            CallVerdict.RING -> listOfNotNull("Rang · $at", RANG_THROUGH, rangWhy(c.reason))
            CallVerdict.DECLINE -> listOfNotNull("Rang · $at", TO_ASSISTANT + (c.reason?.let { " · " + lowerFirst(it.label) } ?: ""), NO_MESSAGE)
            CallVerdict.BLOCK -> listOfNotNull("Called · $at", STOPPED + (c.reason?.let { " · " + lowerFirst(it.label) } ?: ""), NO_RING)
        }
    }

    /** What Block on a screened call writes as why: "Rang · today", "Sent to your call assistant · 9 Oct". */
    fun phoneBlockWhy(c: PhoneCall, nowMs: Long, cal: LocalCalendar): String {
        val label = SearchRules.dayLabel(cal.epochDayOf(c.atMs), cal.epochDayOf(nowMs))
        val day = if (label == "Today" || label == "Yesterday") label.lowercase() else label
        val what = when (c.verdict) {
            CallVerdict.RING -> "Rang"
            CallVerdict.DECLINE -> TO_ASSISTANT
            CallVerdict.BLOCK -> "Called"
        }
        return "$what · $day"
    }

    /**
     * Which screened calls to list: not a known caller's call that rang, and not one a held call already covers (its
     * message or missed-call notice from the same number, or both withheld, within [SAME_CALL_MS] after it).
     */
    fun phoneShown(phone: List<PhoneCall>, held: List<HeldCall>): List<PhoneCall> = phone.filter { c ->
        if (c.verdict == CallVerdict.RING && c.known) return@filter false
        val key = BlockedCallerRules.keyOf(c.number)
        held.none { h ->
            val dt = h.item.atMs - c.atMs
            dt in -60_000L..SAME_CALL_MS && BlockedCallerRules.keyOf(CallNoticeRules.numberOf(h.item)) == key &&
                (key != null || h.item.personName == CallAssistantRules.WITHHELD_NAME)
        }
    }

    /** A screened call as a call item, so the chip, who and block line read as for any call. */
    fun phoneItem(c: PhoneCall): CapturedItem = CapturedItem(
        id = PHONE_PREFIX + c.atMs + "-" + (BlockedCallerRules.keyOf(c.number) ?: CallScreeningRules.WITHHELD),
        app = CaptureApp.PHONE, kind = CaptureKind.MISSED_CALL,
        personName = c.number?.takeIf { BlockedCallerRules.keyOf(it) != null } ?: CallAssistantRules.WITHHELD_NAME,
        text = null, conversation = null, atMs = c.atMs,
        callerNumber = c.number?.takeIf { BlockedCallerRules.keyOf(it) != null },
    )

    private fun lowerFirst(s: String) = s.replaceFirstChar { it.lowercase() }

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
        phone: List<PhoneCall> = emptyList(),
    ): CallLogView {
        val shown = phoneShown(phone.filter { nowMs - it.atMs < PhoneCallRules.KEEP_MS }, calls)
        if (calls.isEmpty() && shown.isEmpty()) return CallLogView.EMPTY
        val today = cal.epochDayOf(nowMs)
        val entries = calls.map { it.item.atMs to (it to null as PhoneCall?) } + shown.map { it.atMs to (null as HeldCall? to it) }
        val rows = entries.sortedWith(compareByDescending<Pair<Long, Pair<HeldCall?, PhoneCall?>>> { it.first }
            .thenBy { it.second.first?.item?.id ?: "" }).take(MAX).map { (_, e) ->
            val (c, p) = e
            val item = named(c?.item ?: phoneItem(p!!), names, lists)
            val cleared = c?.cleared ?: false
            val (chip, isBlocked) = chip(item, blocked)
            val base = detail(item, cleared, blocked, cal)
            cal.epochDayOf(item.atMs) to CallLogRow(
                id = item.id,
                time = LocalClock.formatMinute(cal.minuteOfDay(item.atMs)),
                who = CallNoticeRules.who(item),
                line = p?.let { phoneLine(it) } ?: line(item, cleared),
                chip = chip, blocked = isBlocked,
                urgent = p == null && item.kind == CaptureKind.VOICE_MESSAGE && CallNoticeRules.urgent(item),
                automated = p == null && item.automated,
                detail = if (p == null) base else base.copy(timeline = phoneTimeline(p, cal), blockWhy = phoneBlockWhy(p, nowMs, cal)),
            )
        }
        val days = rows.groupBy({ it.first }, { it.second }).entries.sortedByDescending { it.key }
            .map { (day, r) -> CallLogDay(dayLabel(day, today), r) }
        return CallLogView(days)
    }
}
