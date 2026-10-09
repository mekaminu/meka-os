package os.meka.core.domain

/**
 * The call assistant's screening rules (build plan M1, Needs Meka #9, approved 2026-10-07). Non-AI and pure, so both
 * apps and the tests read the same decision.
 *
 * During work mode, with the assistant switched on, the Fold's call screening lets family, the always-ring list
 * (the always-notify list) and repeat callers (a second call within [REPEAT_WINDOW_MS]) ring; every other call is
 * declined, so the carrier's "forward when busy" sends it on (to voicemail today, to the assistant's number once
 * it is set up). Off work, or with the switch off, every call rings as usual. Nothing is ever answered or blocked
 * for good, and a withheld number gets the repeat rule too (an emergency callback often has no number).
 */
enum class CallVerdict {
    RING,
    /** Declined as busy: the carrier's "forward when busy" sends it to the assistant. */
    DECLINE,
    /** On Meka's block list: rejected silently (no ring, no notification; it stays in the call log). */
    BLOCK,
}

enum class CallReason(val label: String) {
    SWITCHED_OFF("Call assistant off"),
    OFF_WORK("Off work"),
    FAMILY("Family"),
    ALWAYS_RING("Always notify"),
    REPEAT("Called again within 3 minutes"),
    AT_WORK("Declined at work"),
    BLOCKED("On your block list"),
    LIKELY_SPAM("Failed the network's caller check"),
    WITHHELD_QUIET("Withheld number in quiet hours"),
}

/**
 * What the Fold knows about an incoming call beyond its number (spam protection, build plan "Call assistant live —
 * polish" 8b). All read on the phone and never sent anywhere.
 */
data class CallSignals(
    /** Android says the number failed the network's caller check (STIR/SHAKEN `VERIFICATION_FAILED`): likely spoofed. */
    val verificationFailed: Boolean = false,
    /** The number is in the phone's contacts. */
    val knownContact: Boolean = false,
    /** Meka called this number in the last [CallScreeningRules.CALLED_RECENTLY_DAYS] days. */
    val calledRecently: Boolean = false,
    /** Notification quiet hours are on now. */
    val quietHours: Boolean = false,
) {
    companion object { val NONE = CallSignals() }
}

data class CallDecision(val verdict: CallVerdict, val reason: CallReason, val callerKey: String, val listedName: String? = null) {
    val rings: Boolean get() = verdict == CallVerdict.RING
}

/** A call the assistant declined, kept a few minutes so a second call from the same number rings. */
data class ScreenedCall(val callerKey: String, val atMs: Long)

object CallScreeningRules {
    const val REPEAT_WINDOW_MS = 3 * 60_000L
    const val WITHHELD = "withheld"
    /** The assistant's phone number (Twilio, live since 2026-10-09 20:49), as the Work screens show it. */
    const val ASSISTANT_NUMBER = "01767 667246"

    /**
     * The Truecaller trade-off (call assistant polish 8, Meka switched from Truecaller on 2026-10-09): Android lets one
     * app screen calls, so the Work screens say what stops and how to switch back.
     */
    const val ONE_SCREENER_TITLE = "One app screens calls"
    val ONE_SCREENER_LINES = listOf(
        "Android lets one app screen calls. While MEKA does, Truecaller (or any other caller-ID app) stops screening " +
            "and blocking calls; its app still opens and still looks numbers up.",
        "MEKA's spam protection covers what it did: your block list, numbers your network can't verify, withheld " +
            "numbers in quiet hours, and Block · Report to 7726 on a message left by someone you don't know.",
        "To switch back: Settings → Apps → Choose default apps → Caller ID & spam app → Truecaller. MEKA's work-hours " +
            "screening stops until you pick MEKA again.",
    )
    /** The Fold's button to Android's default-apps screen. */
    const val OPEN_DEFAULT_APPS = "Open default apps"

    /** The key a caller's number is remembered by: the phone number's key, or [WITHHELD]. */
    fun callerKey(number: String?): String {
        val n = number?.trim().orEmpty()
        if (n.isEmpty() || n.none { it.isDigit() }) return WITHHELD
        val key = People.key(n)
        return if (key.startsWith("tel:")) key else "tel:" + n.filter { it.isDigit() }
    }

    /** Anyone Meka called in this many days is never blocked or treated as spam. */
    const val CALLED_RECENTLY_DAYS = 90

    /**
     * Spam protection runs on every call, any time of day (8b): a number on the [blocked] list (keys from [callerKey])
     * is rejected silently even with the assistant off; with the assistant on, a number that failed the network's
     * caller check, and a withheld number in quiet hours, go to the assistant instead of ringing. Family, the
     * always-notify list, contacts and anyone Meka called lately are never blocked or treated as spam, and a withheld
     * number calling again within 3 minutes still rings. Then the work rules as before.
     */
    fun decide(
        switchedOn: Boolean,
        atWork: Boolean,
        number: String?,
        lists: PeopleLists,
        recent: List<ScreenedCall>,
        nowMs: Long,
        blocked: Set<String> = emptySet(),
        signals: CallSignals = CallSignals.NONE,
    ): CallDecision {
        val key = callerKey(number)
        val listed = number?.let { lists.nameForNumber(it) }
        val trusted = listed != null || signals.knownContact || signals.calledRecently
        val repeat = recent.any { it.callerKey == key && nowMs - it.atMs in 0..REPEAT_WINDOW_MS }
        return when {
            !trusted && key != WITHHELD && key in blocked -> CallDecision(CallVerdict.BLOCK, CallReason.BLOCKED, key)
            !switchedOn -> CallDecision(CallVerdict.RING, CallReason.SWITCHED_OFF, key, listed)
            !trusted && key != WITHHELD && signals.verificationFailed -> CallDecision(CallVerdict.DECLINE, CallReason.LIKELY_SPAM, key)
            !trusted && key == WITHHELD && signals.quietHours && !repeat -> CallDecision(CallVerdict.DECLINE, CallReason.WITHHELD_QUIET, key)
            !atWork -> CallDecision(CallVerdict.RING, CallReason.OFF_WORK, key, listed)
            listed != null && lists.family.contains(listed) -> CallDecision(CallVerdict.RING, CallReason.FAMILY, key, listed)
            listed != null -> CallDecision(CallVerdict.RING, CallReason.ALWAYS_RING, key, listed)
            repeat -> CallDecision(CallVerdict.RING, CallReason.REPEAT, key)
            else -> CallDecision(CallVerdict.DECLINE, CallReason.AT_WORK, key)
        }
    }

    /** The recent declines after [decision]: older ones dropped, a decline added, a call let through forgets its caller. */
    fun remember(recent: List<ScreenedCall>, decision: CallDecision, nowMs: Long): List<ScreenedCall> {
        val kept = recent.filter { nowMs - it.atMs in 0..REPEAT_WINDOW_MS && it.callerKey != decision.callerKey }
        return if (decision.verdict == CallVerdict.DECLINE) (kept + ScreenedCall(decision.callerKey, nowMs)).takeLast(MAX_RECENT) else kept
    }

    private const val MAX_RECENT = 50

    /** "tel:7700900123@1700000000000;withheld@…", for the Fold's small preferences file. */
    fun encode(recent: List<ScreenedCall>): String = recent.joinToString(";") { "${it.callerKey}@${it.atMs}" }

    fun decode(s: String?): List<ScreenedCall> = s.orEmpty().split(';').mapNotNull { part ->
        val at = part.lastIndexOf('@').takeIf { it > 0 } ?: return@mapNotNull null
        val ms = part.substring(at + 1).toLongOrNull() ?: return@mapNotNull null
        ScreenedCall(part.substring(0, at), ms)
    }

    /**
     * The Work screen's line for the assistant. [screeningAllowed] is whether Android lets MEKA screen calls (the
     * Fold's call-screening role; the Mac passes null and only shows the switch).
     */
    fun statusLine(switchedOn: Boolean, atWork: Boolean, screeningAllowed: Boolean?): String = when {
        !switchedOn -> "Off · calls ring as usual"
        screeningAllowed == false -> "On · allow MEKA to screen calls on the Fold"
        atWork -> "Screening calls · family, always-notify and repeat callers ring"
        else -> "On at work · calls ring as usual now · spam goes to the assistant"
    }

    /** Whether the Fold should remember [decision] for the repeat rule (any call it declined, at any time). */
    fun remembers(decision: CallDecision): Boolean = decision.verdict == CallVerdict.DECLINE

    /** The Activity entry for a call screening stopped (blocked or sent to the assistant as likely spam); null otherwise. */
    fun activityLine(decision: CallDecision, number: String?): Pair<String, String>? {
        val who = if (decision.callerKey == WITHHELD) "Withheld number" else BlockedCallerRules.display(number.orEmpty())
        return when (decision.reason) {
            CallReason.BLOCKED -> "Blocked a call from $who" to "It is on your block list"
            CallReason.LIKELY_SPAM -> "Sent a likely spam call from $who to the assistant" to "The network couldn't verify the caller's number"
            CallReason.WITHHELD_QUIET -> "Sent a withheld call to the assistant" to "Withheld numbers don't ring in quiet hours (a second call within 3 minutes does)"
            else -> null
        }
    }
}
