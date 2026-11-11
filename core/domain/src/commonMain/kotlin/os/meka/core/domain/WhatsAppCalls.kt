package os.meka.core.domain

/**
 * WhatsApp calls in Work mode (Meka approved 2026-11-11 18:06: "work mode only", "one fixed message", "ask them to call
 * my mobile and the assistant can pick it up"). The Fold's notification listener (Android's official notification
 * access; no WhatsApp library, no accessibility taps, never answering a call, no audio) sees WhatsApp's incoming-call
 * notification and, while Work mode holds everything else, declines it through the notification's own Decline.
 *
 * The one standing approval: Meka's fixed at-work message, sent through the notification's own Reply only to someone
 * who just WhatsApp-called while Work mode is on, at most once per caller a day, never to a group or a group call and
 * never with any other words. Everything else keeps "ask me, then send" (autonomy Level 3). Off until he turns it on.
 *
 * Kept free of Android types so it is unit-tested here; the Fold reads the notification and acts.
 */
enum class WhatsAppCallKind(val label: String) { VOICE("voice"), VIDEO("video") }

/** An incoming WhatsApp call as its notification showed it. [group] is the group's name for a group call. */
data class WhatsAppIncomingCall(val caller: String, val kind: WhatsAppCallKind, val group: String?) {
    val isGroup: Boolean get() = group != null
}

/** Meka's choice in Work mode → Call assistant → WhatsApp calls (on the Fold only). */
data class WhatsAppCallSettings(val on: Boolean = false, val message: String = WhatsAppCallRules.DEFAULT_MESSAGE)

enum class WhatsAppCallVerdict { RING, DECLINE, DECLINE_AND_REPLY }

enum class WhatsAppCallWhy {
    /** The switch is off: WhatsApp calls ring as before. */
    OFF,
    /** Work mode isn't on: never declined, never answered with the message. */
    NOT_AT_WORK,
    /** Family or Always notify: their calls ring, as on the phone. */
    LET_THROUGH,
    /** A group call: declined, never messaged. */
    GROUP,
    /** This caller already had the message today. */
    ALREADY_TODAY,
    /** The message promises the call assistant, which can't pick up right now; declined without it. */
    ASSISTANT_OFF,
    /** Declined, and the message goes. */
    REPLY,
}

data class WhatsAppCallDecision(val verdict: WhatsAppCallVerdict, val why: WhatsAppCallWhy)

/** How the at-work message went for one declined call. */
enum class WhatsAppReplyOutcome {
    SENT,
    /** WhatsApp offered no Reply on the call's notifications, so nothing was sent. */
    NO_REPLY_ACTION,
    /** Not tried: a group call, already sent today, or the assistant can't pick up. */
    NOT_TRIED,
}

object WhatsAppCallRules {
    const val TITLE = "WhatsApp calls"
    const val SWITCH = "Decline them at work and send this message"
    const val DEFAULT_MESSAGE =
        "I'm at work and can't take WhatsApp calls. If it's urgent, please ring my mobile and my assistant will pick up. " +
            "Otherwise I'll call you back after work."
    const val MAX_MESSAGE = 400
    const val NOTE =
        "Only while Work mode is on. Family and Always notify still ring. The message goes once a day to each person who " +
            "WhatsApp-calls you, never to groups, and only through WhatsApp's own Reply. Turning this on is your yes."
    const val OFF_LINE = "Off · WhatsApp calls ring as usual"
    const val ON_LINE = "On · at work, WhatsApp calls are declined and the caller gets your message"
    const val ASSISTANT_OFF_LINE =
        "Your call assistant can't pick up right now, so a caller who rings your mobile wouldn't reach it: calls are " +
            "declined without the message until it's on again."
    const val CALL_BACK = "Call back"
    const val WHY = "Work mode · WhatsApp calls"

    /** A post of the same ringing call within this window is the same call (WhatsApp re-posts while it rings). */
    const val SAME_CALL_MS = 60_000L

    /** How long the Fold waits after declining for a notification from the caller that offers Reply. */
    const val REPLY_WAIT_MS = 30_000L

    /** Android's Notification.CATEGORY_CALL. */
    const val CATEGORY_CALL = "call"

    /** Notification.CallStyle's call types (EXTRA_CALL_TYPE). */
    const val CALL_TYPE_INCOMING = 1

    private val incomingText = Regex("incoming\\s+(group\\s+)?(voice|video)?\\s*call", RegexOption.IGNORE_CASE)
    private val groupText = Regex("\\bgroup\\b", RegexOption.IGNORE_CASE)
    private val videoText = Regex("\\bvideo\\b", RegexOption.IGNORE_CASE)
    private val answerWords = setOf("answer", "accept")
    private val declineWords = setOf("decline", "reject")

    /**
     * The incoming WhatsApp call a notification shows, or null when it is anything else (a message, an ongoing call,
     * a missed call, another app). [callType] is CallStyle's call type when the notification carries one;
     * [callPerson] CallStyle's person; [actionTitles] its actions' titles.
     */
    fun incoming(
        isWhatsApp: Boolean,
        category: String?,
        title: String?,
        text: String?,
        actionTitles: List<String>,
        callType: Int? = null,
        callPerson: String? = null,
    ): WhatsAppIncomingCall? {
        if (!isWhatsApp) return null
        val words = actionTitles.map { it.trim().lowercase() }
        val ringing = when (callType) {
            null, 0 -> (category == CATEGORY_CALL || words.any { it in answerWords }) &&
                (words.any { it in answerWords } || incomingText.containsMatchIn(text.orEmpty()))
            else -> callType == CALL_TYPE_INCOMING
        }
        if (!ringing) return null
        val body = text.orEmpty()
        val group = groupText.containsMatchIn(body)
        val person = callPerson?.trim()?.takeIf { it.isNotEmpty() }
        // The title names the caller (or, for a group call, the group); a bare "Incoming call" title doesn't.
        val name = title?.trim()?.takeIf { it.isNotEmpty() && !incomingText.containsMatchIn(it) && !it.equals("WhatsApp", true) }
            ?: person ?: return null
        val kind = if (videoText.containsMatchIn(body) || videoText.containsMatchIn(title.orEmpty())) WhatsAppCallKind.VIDEO else WhatsAppCallKind.VOICE
        return WhatsAppIncomingCall(person ?: name, kind, if (group) name else null)
    }

    /** The index of the notification's Decline among [actionTitles], or null when it offers none. */
    fun declineIndex(actionTitles: List<String>): Int? =
        actionTitles.indexOfFirst { it.trim().lowercase() in declineWords }.takeIf { it >= 0 }

    /**
     * What to do with [call]. [repliedToday]: the people (by [People.key]) who already had the message today.
     * [assistantCanPickUp]: the call assistant is on, screening and not paused, so the message's promise holds.
     */
    fun decide(
        call: WhatsAppIncomingCall,
        settings: WhatsAppCallSettings,
        atWork: Boolean,
        lists: PeopleLists,
        repliedToday: Set<String>,
        assistantCanPickUp: Boolean,
    ): WhatsAppCallDecision {
        fun d(v: WhatsAppCallVerdict, w: WhatsAppCallWhy) = WhatsAppCallDecision(v, w)
        return when {
            !settings.on -> d(WhatsAppCallVerdict.RING, WhatsAppCallWhy.OFF)
            !atWork -> d(WhatsAppCallVerdict.RING, WhatsAppCallWhy.NOT_AT_WORK)
            !call.isGroup && (lists.isFamily(call.caller) || lists.isAlwaysNotify(call.caller)) ->
                d(WhatsAppCallVerdict.RING, WhatsAppCallWhy.LET_THROUGH)
            call.isGroup -> d(WhatsAppCallVerdict.DECLINE, WhatsAppCallWhy.GROUP)
            People.key(call.caller) in repliedToday -> d(WhatsAppCallVerdict.DECLINE, WhatsAppCallWhy.ALREADY_TODAY)
            promisesAssistant(message(settings)) && !assistantCanPickUp -> d(WhatsAppCallVerdict.DECLINE, WhatsAppCallWhy.ASSISTANT_OFF)
            else -> d(WhatsAppCallVerdict.DECLINE_AND_REPLY, WhatsAppCallWhy.REPLY)
        }
    }

    /** The words that go: his message, tidied; blank means the suggested one. */
    fun message(settings: WhatsAppCallSettings): String = cleaned(settings.message) ?: DEFAULT_MESSAGE

    /** [text] trimmed, inner runs of spaces kept to one, capped at [MAX_MESSAGE]; null when blank. */
    fun cleaned(text: String?): String? =
        text?.trim()?.replace(Regex("[ \\t]+"), " ")?.take(MAX_MESSAGE)?.trim()?.takeIf { it.isNotEmpty() }

    /** Whether the message tells callers the assistant will pick up (so it is held back while it can't). */
    fun promisesAssistant(message: String): Boolean = Regex("\\bassistant\\b|\\bobi\\b", RegexOption.IGNORE_CASE).containsMatchIn(message)

    /** The settings row's line. */
    fun line(settings: WhatsAppCallSettings, assistantCanPickUp: Boolean): String = when {
        !settings.on -> OFF_LINE
        promisesAssistant(message(settings)) && !assistantCanPickUp -> ASSISTANT_OFF_LINE
        else -> ON_LINE
    }

    /** Whether a post for [callerKey] at [nowMs] is the same ringing call as one handled at [lastAtMs]. */
    fun sameCall(lastAtMs: Long?, nowMs: Long): Boolean = lastAtMs != null && nowMs - lastAtMs in 0 until SAME_CALL_MS

    // Once per caller per day: "key@epochDay;…" in the Fold's small file.

    fun repliedToday(log: Map<String, Long>, today: Long): Set<String> = log.filterValues { it == today }.keys

    /** [log] with [caller] marked for [today]; earlier days dropped. */
    fun recordReplied(log: Map<String, Long>, caller: String, today: Long): Map<String, Long> =
        log.filterValues { it == today } + (People.key(caller) to today)

    fun encode(log: Map<String, Long>): String = log.entries.joinToString(";") { "${it.key}@${it.value}" }

    fun decode(s: String?): Map<String, Long> = s.orEmpty().split(';').mapNotNull { part ->
        val at = part.lastIndexOf('@').takeIf { it > 0 } ?: return@mapNotNull null
        val day = part.substring(at + 1).toLongOrNull() ?: return@mapNotNull null
        part.substring(0, at) to day
    }.toMap()

    /** Activity's line for one declined call (synced, Meka's own data). */
    fun activitySummary(call: WhatsAppIncomingCall, outcome: WhatsAppReplyOutcome, why: WhatsAppCallWhy): String {
        val who = call.group?.let { "a WhatsApp group call in $it" } ?: "a WhatsApp ${call.kind.label} call from ${call.caller}"
        val tail = when {
            call.isGroup -> null
            outcome == WhatsAppReplyOutcome.SENT -> "sent your at-work message"
            outcome == WhatsAppReplyOutcome.NO_REPLY_ACTION -> "WhatsApp offered no reply, so your message wasn't sent"
            why == WhatsAppCallWhy.ALREADY_TODAY -> "your message went earlier today"
            why == WhatsAppCallWhy.ASSISTANT_OFF -> "message held back: your call assistant can't pick up"
            else -> null
        }
        return "Declined $who" + (tail?.let { " · $it" } ?: "")
    }

    /** The held item's words in the after-work summary ("Called on WhatsApp · at-work message sent"). */
    fun heldNote(outcome: WhatsAppReplyOutcome, why: WhatsAppCallWhy): String = when {
        outcome == WhatsAppReplyOutcome.SENT -> "at-work message sent"
        outcome == WhatsAppReplyOutcome.NO_REPLY_ACTION -> "declined · message not sent, WhatsApp offered no reply"
        why == WhatsAppCallWhy.ALREADY_TODAY -> "declined · message sent earlier today"
        why == WhatsAppCallWhy.ASSISTANT_OFF -> "declined · message held back"
        else -> "declined"
    }

    /** The after-work summary's line for a WhatsApp call item ([CapturedItem.displayLine]). */
    fun displayLine(item: CapturedItem): String = "Called on WhatsApp" + (item.text?.trim()?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")

    /** Whether [item] is a WhatsApp call Work mode declined (its Call back opens the chat). */
    fun isWhatsAppCall(item: CapturedItem): Boolean = item.app == CaptureApp.WHATSAPP && item.kind == CaptureKind.MISSED_CALL

    /** WhatsApp's own click-to-chat link for Call back ("https://wa.me/447700900123"); null without a number. */
    fun callBackLink(number: String?): String? {
        val raw = number?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val digits = raw.filter { it.isDigit() }
        val intl = when {
            raw.startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.startsWith("0") -> "44" + digits.drop(1)
            else -> digits
        }
        return if (intl.length >= 8) "https://wa.me/$intl" else null
    }
}
