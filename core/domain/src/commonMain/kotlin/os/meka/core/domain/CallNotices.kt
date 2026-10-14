package os.meka.core.domain

import os.meka.core.sync.FieldValue

/**
 * Call notifications that actually tell him something (build plan "Take first", Meka 2026-10-14 18:38: "they didn't
 * tell me who called, whether it was blocked, what it was about, or let me listen … all it said was a number, 6 or 1").
 * Non-AI and pure; the same words on every device.
 *
 * Slice 1: the urgent voice-message alert says who rang (contact name, else the number and "not in your contacts"),
 * what happened ("Sent to your call assistant · 18:02 · left a 0:24 message") and what it was about (the start of the
 * transcript, or "No message: just a keypad press (likely an automated call)"); urgency only when the caller spoke.
 * [automated] marks a robocall: a message under [AUTOMATED_SECONDS], words that are only digits or one word, or "urgent"
 * pressed on the keypad with no words in the message; such a call never breaks through as urgent.
 */
object CallNoticeRules {
    /** A message shorter than this (seconds) is a machine or a hang-up, never a person's message. */
    const val AUTOMATED_SECONDS = 3
    /** [HeldMessageFields.URGENT_BY] values: how the caller answered "is it urgent?". */
    const val BY_KEYPAD = "keypad"
    const val BY_SPEECH = "speech"

    const val NOT_IN_CONTACTS = "not in your contacts"
    const val AUTOMATED_LABEL = "Likely automated call"
    const val KEYPAD_ONLY = "No message: just a keypad press (likely an automated call)"
    const val TOO_SHORT = "No real message: hung up after a moment (likely an automated call)"
    const val ONE_WORD = "No real message (likely an automated call)"
    const val SAID_URGENT = "Said it's urgent"
    const val TRANSCRIBING = "Writing the message down…"
    const val NO_WORDS = "No words came through"
    const val CALL_BACK = "Call back"
    const val BLOCK = BlockedCallerRules.BLOCK_LABEL
    private const val ABOUT_MAX = 140
    /** The AI's summary as shown: one line, at most this many characters. */
    const val SUMMARY_MAX = 80

    /**
     * Call notifications, slice 2: the AI's one-line summary of a message's words as shown ("Dentist confirming
     * Thursday 3pm"): one line, no angle brackets, quotes or links, no trailing full stop, at most [SUMMARY_MAX]
     * characters (cut at a word); null when empty or not a summary.
     */
    fun cleanSummary(raw: String?): String? {
        var t = raw?.replace(Regex("\\s+"), " ")?.replace(Regex("[<>\"“”]"), "")?.trim()?.trimEnd('.', ' ') ?: return null
        if (t.isEmpty() || t.contains("://") || t.contains("www.", ignoreCase = true)) return null
        if (t.length > SUMMARY_MAX) {
            val cut = t.take(SUMMARY_MAX - 1)
            t = (cut.substringBeforeLast(' ').takeIf { it.length >= SUMMARY_MAX / 2 } ?: cut).trimEnd(',', ';', ':', ' ') + "…"
        }
        return t.replaceFirstChar { it.uppercaseChar() }
    }

    /** "keypad" for a key press, "speech" for words, null for no answer ([CallAssistantRules.isUrgentAnswer]'s input). */
    fun answeredBy(digits: String?, speech: String?): String? = when {
        !digits.isNullOrBlank() -> BY_KEYPAD
        !speech.isNullOrBlank() -> BY_SPEECH
        else -> null
    }

    private val token = Regex("[\\p{L}\\p{N}']+")

    /** Whether the words are a person speaking: two or more words, at least one with a letter. */
    fun hasWords(text: String?): Boolean {
        val words = token.findAll(text.orEmpty()).map { it.value }.toList()
        return words.size >= 2 && words.any { w -> w.any { it.isLetter() } }
    }

    /** Words that are only digits ("6", "1 1"), or a single word: what a robocall's tones and prompts leave. */
    fun digitsOrOneWord(text: String?): Boolean {
        val words = token.findAll(text.orEmpty()).map { it.value }.toList()
        if (words.isEmpty()) return false
        return words.size == 1 || words.all { w -> w.all { it.isDigit() } }
    }

    /**
     * A voice message that is a machine, not a person ([seconds] the recording's length when the server knew it,
     * [text] its words, [noTranscript] when none came through, [urgentBy] how "is it urgent?" was answered, [urgent] the answer).
     */
    fun automated(seconds: Int?, text: String?, noTranscript: Boolean, urgentBy: String?, urgent: Boolean): Boolean = when {
        seconds != null && seconds < AUTOMATED_SECONDS -> true
        digitsOrOneWord(text) -> true
        urgent && urgentBy == BY_KEYPAD && (noTranscript || (text != null && !hasWords(text))) -> true
        else -> false
    }

    /**
     * Whether a voice message counts as urgent: never an [automated] one; "urgent" by the keypad only once its words
     * show a person spoke (a robocall pressing 1 never breaks through); "yes" said aloud, or its own words saying urgent.
     */
    fun urgent(item: CapturedItem): Boolean {
        if (item.automated) return false
        if (Urgency.isUrgent(item.text)) return true
        if (!item.urgent) return false
        return item.urgentBy != BY_KEYPAD || hasWords(item.text)
    }

    /** "0:24" */
    fun length(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

    /** The ▶ action: "▶ Listen 0:24", or "▶ Listen" when the length isn't known. */
    fun listenLabel(seconds: Int?): String = "▶ Listen" + (seconds?.takeIf { it > 0 }?.let { " " + length(it) } ?: "")

    /** The number to call back or block, or null (withheld, or a sender a notification named). */
    fun numberOf(item: CapturedItem): String? = BlockedCallerRules.keyOf(item.callerNumber ?: item.personName)?.let { item.callerNumber ?: item.personName }

    /** Whether [item]'s caller is someone he knows by name (a contact or a listed person), not just a number. */
    fun known(item: CapturedItem): Boolean {
        val n = numberOf(item) ?: return item.personName != CallAssistantRules.WITHHELD_NAME
        return item.personName.isNotBlank() && item.personName != BlockedCallerRules.display(n) && BlockedCallerRules.keyOf(item.personName) == null
    }

    /**
     * Who rang: "Dentist", "07700 900123 · not in your contacts", "Withheld number"; with [reputation] (e.g. "flagged
     * as likely spam") after it.
     */
    fun who(item: CapturedItem, reputation: String? = null): String {
        val n = numberOf(item)
        val base = when {
            known(item) -> item.personName
            n != null -> "${BlockedCallerRules.display(n)} · $NOT_IN_CONTACTS"
            else -> item.personName.ifBlank { CallAssistantRules.WITHHELD_NAME }
        }
        return base + (reputation?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    }

    /** What happened: "Sent to your call assistant · 18:02 · left a 0:24 message". */
    fun happened(item: CapturedItem, cal: LocalCalendar): String {
        val at = LocalClock.formatMinute(cal.minuteOfDay(item.atMs))
        val left = when {
            item.seconds != null && item.seconds < AUTOMATED_SECONDS -> "hung up after ${item.seconds.coerceAtLeast(0)} s"
            item.seconds != null -> "left a ${length(item.seconds)} message"
            else -> "left a message"
        }
        return "Sent to your call assistant · $at · $left"
    }

    /** What it was about: the start of the words, or why there's nothing to read. */
    fun about(item: CapturedItem): String {
        val text = item.text?.trim()?.takeIf { it.isNotEmpty() }
        if (item.automated) return when {
            item.seconds != null && item.seconds < AUTOMATED_SECONDS && !hasWords(text) -> TOO_SHORT
            text == null || text.all { !it.isLetter() } || item.urgentBy == BY_KEYPAD -> KEYPAD_ONLY
            else -> ONE_WORD
        }
        item.summary?.let { return it }
        if (text != null) {
            val cut = if (text.length > ABOUT_MAX) text.take(ABOUT_MAX - 1).trimEnd() + "…" else text
            return "“$cut”"
        }
        return if (item.transcribing) TRANSCRIBING else NO_WORDS
    }

    const val NOT_BLOCKED = "Not blocked · it will ring again"
    const val BLOCKED = "Blocked · it won't ring again"
    const val NEVER_BLOCKED = "Never blocked · in your contacts"
    const val WITHHELD_STATUS = "Not blocked · a withheld number can't be blocked"
    const val SUSPECTED_STATUS = "Not blocked · flagged as likely spam, so its calls go to your call assistant"

    /**
     * Whether the number is blocked now and in future, always stated (Meka 18:57): "Blocked · it won't ring again ·
     * added today · Hung up on the assistant", "Not blocked · it will ring again", "Never blocked · in your contacts".
     */
    fun blockStatus(item: CapturedItem, view: BlockedCallersView): String {
        val n = numberOf(item) ?: return if (known(item)) NEVER_BLOCKED else WITHHELD_STATUS
        val key = BlockedCallerRules.keyOf(n)
        view.rows.firstOrNull { it.key == key }?.let { row ->
            return BLOCKED + (row.line.removePrefix("Blocked").trim().takeIf { it.isNotEmpty() }?.let { " · added $it" } ?: "")
        }
        if (known(item)) return NEVER_BLOCKED
        if (key != null && key in view.suspectedKeys) return SUSPECTED_STATUS
        return NOT_BLOCKED
    }

    /**
     * The alert: [title] who, [text] what happened, [big] what happened then what it was about, [sub] "Said it's
     * urgent" when the caller said so, and the buttons' labels (null = no button).
     */
    data class Notice(
        val title: String, val text: String, val big: String, val sub: String?,
        val listen: String?, val callBack: String?, val block: String?,
        /** Slice 3: "Unblock" once the number is on the block list; "Not spam" while the AI has it flagged. */
        val unblock: String? = null, val notSpam: String? = null,
        /** Slice 3: "Undo" on an automatic block (takes it off the list, like Activity's Undo). */
        val undo: String? = null,
    )

    /**
     * The urgent voice-message alert (one per call; updated in place when ▶ Listen arrives). [canPlay] once the
     * recording is kept; [blocked] the block list, for the block-status line (and no Block button once on it).
     */
    fun voiceNotice(item: CapturedItem, cal: LocalCalendar, canPlay: Boolean, blocked: BlockedCallersView = BlockedCallersView.EMPTY, reputation: String? = null): Notice {
        val happened = happened(item, cal)
        val urgentLine = if (urgent(item)) SAID_URGENT else null
        val status = blockStatus(item, blocked)
        // Slice 2: with the AI's summary, the summary is what it was about and the start of the words follows it.
        val summary = item.summary?.takeIf { !item.automated }
        val words = if (summary != null) about(item.copy(summary = null)).takeIf { it.startsWith("“") } else null
        val big = listOfNotNull(happened, urgentLine?.let { "$it · " + about(item) } ?: about(item), words, status).joinToString("\n")
        val n = numberOf(item)
        return Notice(
            title = who(item, reputation),
            // Collapsed, the shade shows one line: the summary says the most, else what happened.
            text = summary ?: happened,
            big = big,
            sub = urgentLine,
            listen = if (canPlay) listenLabel(item.seconds) else null,
            callBack = n?.let { CALL_BACK },
            block = if (n != null && !known(item) && !blocked.has(n)) BLOCK else null,
            unblock = if (n != null && blocked.has(n)) UNBLOCK else null,
            notSpam = if (n != null && !known(item) && !blocked.has(n) && BlockedCallerRules.keyOf(n) in blocked.suspectedKeys) NOT_SPAM else null,
        )
    }

    // ---- Slice 3: one live notification per call, updated in place ----

    const val UNBLOCK = "Unblock"
    const val NOT_SPAM = SuspectedSpamRules.NOT_SPAM_LABEL
    const val UNDO = AutoBlockRules.UNDO
    /** Meka 18:57: a number put on the list automatically says so in the same notification. */
    const val AUTO_BLOCKED = "Blocked · and blocked from now on"
    const val UNBLOCKED = "Unblocked · it will ring again"
    const val MARKED_NOT_SPAM = "Not spam · Navilife won't flag it again · its calls are screened as usual"

    /**
     * The one notification a caller has (the after-call notice, the call assistant's message alert, an automatic block
     * and what Block/Unblock/Not spam did all share it): "call:" + the number's key, or null for a withheld number
     * (which keeps its own alert per message). Also the group, so Android bundles per caller.
     */
    fun tag(number: String?): String? = BlockedCallerRules.keyOf(number)?.let { "call:$it" }

    /** What a caller's notification is showing now, most telling last. */
    enum class Kind { CONFIRM, AFTER_CALL, AUTO_BLOCKED, MESSAGE }

    /** A newer call's notice replaces an older one of any kind after this long. */
    const val FRESH_MS = 60 * 60_000L

    /**
     * Whether [incoming] may replace what the caller's notification shows ([showing], posted at [showingAtMs]; null =
     * nothing showing): never a less telling notice over a more telling one from the last hour (the after-call
     * "Sent to your call assistant" never wipes the message alert it led to); the same kind updates in place.
     */
    fun replaces(incoming: Kind, showing: Kind?, showingAtMs: Long, nowMs: Long): Boolean = when {
        showing == null -> true
        nowMs - showingAtMs !in 0 until FRESH_MS -> true
        else -> incoming.ordinal >= showing.ordinal
    }

    /** What happened before an automatic block: "Blocked before it rang · 14:05", "Rang · 14:05 · 3rd call in 7 days". */
    fun autoBlockHappened(trigger: AutoBlockTrigger, atMs: Long, cal: LocalCalendar, callsInWindow: Int = 0): String {
        val at = LocalClock.formatMinute(cal.minuteOfDay(atMs))
        return when (trigger) {
            AutoBlockTrigger.NETWORK_CHECK -> "Blocked before it rang · $at · your network couldn't verify the number"
            AutoBlockTrigger.REPEAT_CALLER -> "Rang · $at · called $callsInWindow times in ${AutoBlockRules.REPEAT_DAYS} days and never left a message"
            AutoBlockTrigger.ASSISTANT_HANGUP -> "Sent to your call assistant · $at · hung up without saying who they were or why"
            AutoBlockTrigger.AUTOMATED -> "Sent to your call assistant · $at · ${AUTOMATED_LABEL.lowercase()}, no real message"
        }
    }

    /**
     * The automatic block, in the caller's own notification: who, "Blocked · and blocked from now on" collapsed, and
     * expanded what happened then the block line; Undo and Report to 7726 are the buttons.
     */
    fun autoBlockNotice(number: String, trigger: AutoBlockTrigger, atMs: Long, cal: LocalCalendar, callsInWindow: Int = 0): Notice {
        val happened = autoBlockHappened(trigger, atMs, cal, callsInWindow)
        return Notice(
            title = UnknownCallRules.title(number),
            text = AUTO_BLOCKED,
            big = "$happened\n$AUTO_BLOCKED · Undo takes it off your block list",
            sub = null, listen = null, callBack = null, block = null, undo = UNDO,
        )
    }

    /** After Block / Unblock / Not spam on a notice that wasn't a message alert: what it did, in the same notification. */
    fun confirmNotice(number: String, blocked: Boolean? = null, notSpam: Boolean = false): Notice {
        val line = when {
            notSpam -> MARKED_NOT_SPAM
            blocked == true -> BLOCKED
            else -> UNBLOCKED
        }
        return Notice(
            title = UnknownCallRules.title(number), text = line, big = line, sub = null,
            listen = null, callBack = CALL_BACK, block = if (blocked == false || notSpam) BLOCK else null,
            unblock = if (blocked == true) UNBLOCK else null,
        )
    }
}

/**
 * Diagnosis for a build run (call notifications, slice 1): the last [MAX] call-assistant messages as one line each
 * for deploy's `publish-fold` job — time, message length, how many characters the words were, urgent and how it was
 * answered, whether the recording is kept and its size, and whether it reads as automated. **Never** the number or
 * the words.
 */
data class AssistantCallEntry(
    val atMs: Long,
    val seconds: Int?,
    val chars: Int?,
    val transcribed: Boolean,
    val urgent: Boolean,
    /** keypad · speech · none · ? (before the server noted it and no Activity entry says). */
    val answeredBy: String,
    val audio: Boolean,
    val bytes: Int?,
    val automated: Boolean,
    val cleared: Boolean,
    /** The AI summary's length in characters (slice 2); null = none. */
    val summaryChars: Int? = null,
)

object AssistantCallReportRules {
    const val MAX = 10

    /** From an Activity entry's detail ([CallAssistantRules.answerHeard]): keypad, speech or none; null when unknown. */
    fun byFromDetail(detail: String?): String? = when {
        detail == null -> null
        detail.startsWith("Pressed") -> CallNoticeRules.BY_KEYPAD
        detail.startsWith("Heard") -> CallNoticeRules.BY_SPEECH
        detail.startsWith("No answer") -> "none"
        else -> null
    }

    /**
     * The newest [MAX] voice messages from the household's `held_message` fields (id → field → value). [answerDetail]
     * reads the "is it urgent?" Activity entry's detail for messages from before [HeldMessageFields.URGENT_BY];
     * [bytes] the kept recording's size.
     */
    fun entries(
        held: Map<String, Map<String, FieldValue>>,
        answerDetail: (heldId: String) -> String? = { null },
        bytes: (heldId: String) -> Int? = { null },
    ): List<AssistantCallEntry> = held.entries.mapNotNull { (id, f) ->
        if ((f[HeldMessageFields.KIND] as? FieldValue.Text)?.value != CaptureKind.VOICE_MESSAGE.name) return@mapNotNull null
        if ((f[HeldMessageFields.APP] as? FieldValue.Text)?.value != CaptureApp.PHONE.name) return@mapNotNull null
        val at = (f[HeldMessageFields.AT] as? FieldValue.Int64)?.value ?: return@mapNotNull null
        id to at
    }.sortedByDescending { it.second }.take(MAX).map { (id, at) ->
        val f = held.getValue(id)
        fun bool(k: String) = (f[k] as? FieldValue.Bool)?.value == true
        val text = (f[HeldMessageFields.TEXT] as? FieldValue.Text)?.value
        val seconds = (f[HeldMessageFields.SECONDS] as? FieldValue.Int64)?.value?.toInt()
        val noTranscript = bool(HeldMessageFields.NO_TRANSCRIPT)
        val by = (f[HeldMessageFields.URGENT_BY] as? FieldValue.Text)?.value
            ?: runCatching { byFromDetail(answerDetail(id)) }.getOrNull()
        val audio = bool(HeldMessageFields.AUDIO)
        AssistantCallEntry(
            atMs = at, seconds = seconds, chars = text?.length, transcribed = text != null || noTranscript,
            urgent = bool(HeldMessageFields.URGENT), answeredBy = by ?: "?",
            audio = audio, bytes = if (audio) runCatching { bytes(id) }.getOrNull() else null,
            automated = CallNoticeRules.automated(seconds, text, noTranscript, by, bool(HeldMessageFields.URGENT)),
            cleared = bool(HeldMessageFields.CLEARED),
            summaryChars = (f[HeldMessageFields.SUMMARY] as? FieldValue.Text)?.value?.length,
        )
    }

    /** "message 0:24 · words 112 chars · urgent (keypad) · recording kept 184 KB · likely automated". */
    fun line(e: AssistantCallEntry): String = listOfNotNull(
        "message " + (e.seconds?.let { CallNoticeRules.length(it) } ?: "length not noted"),
        when {
            e.chars != null -> "words ${e.chars} chars"
            e.transcribed -> "no words came through"
            e.cleared -> "words cleared (Done)"
            else -> "no words yet"
        },
        (if (e.urgent) "urgent" else "not urgent") + " (answer: ${e.answeredBy})",
        if (e.audio) "recording kept" + (e.bytes?.let { " ${(it + 512) / 1024} KB" } ?: "") else "no recording",
        "likely automated".takeIf { e.automated },
        e.summaryChars?.let { "AI summary $it chars" } ?: "no AI summary".takeIf { e.chars != null && !e.automated },
    ).joinToString(" · ")
}
