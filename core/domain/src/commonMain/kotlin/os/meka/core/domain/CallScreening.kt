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
    /** On Suspected spam (MEKA's AI flagged their message; Meka hasn't chosen Block or Not spam yet). */
    SUSPECTED_SPAM("On Suspected spam"),
    /** The assistant would have taken it, but its credit ran out ([CallCreditRules]): it rings instead. */
    PAUSED("Call assistant paused · Twilio credit"),
    /**
     * Spam calls still ring, slice 1: the network couldn't verify the number, so spam protection rejected it before it
     * rang and put it on the block list (with Undo in Activity). Added 2026-10-13.
     */
    AUTO_BLOCKED("Failed the network's caller check · blocked"),
    /**
     * The caller's network name claims an official body (police, bank, NHS, school, HMRC): never auto-blocked, it
     * rings, and a heads-up afterwards says how to check ([OfficialCallerRules]). Added 2026-10-13.
     */
    OFFICIAL_CLAIM("Claims to be an official body"),
    /** An emergency or NHS number (999, 112, 101, 111…): never screened. Added 2026-10-13. */
    EMERGENCY("Emergency service"),
    /**
     * Spam-call lookup (2026-10-17): the web's report sites call the number a scam, surely enough to block
     * ([NumberLookupRules.shouldBlock]); rejected before it rang and put on the block list (Undo in Activity).
     */
    REPORTED_SCAM("Reported online as a scam · blocked"),
    /** Spam-call lookup: reported as nuisance or sales calls (or a scam, not surely): to the assistant when it's on. */
    REPORTED_NUISANCE("Reported online as nuisance or sales calls"),
    /**
     * The call assistant's own number ([CallSignals.fromAssistant]): it is putting an urgent caller through
     * ([PutThroughRules]), so it always rings, at work too. Added 2026-10-17.
     */
    ASSISTANT("Your call assistant putting a caller through"),
    /**
     * "Screen unknown callers all the time" ([ScreenUnknownRules], 2026-10-22): a number nobody knows, outside work,
     * went to the assistant instead of ringing.
     */
    UNKNOWN_ALWAYS("Unknown number · screened all the time"),
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
    /**
     * The name the network sent with the call (Android's `callerDisplayName`, CNAP), read on the phone only; null when
     * none. Used only to spot a caller claiming to be the police, a bank, the NHS or a school ([OfficialCallerRules]).
     */
    val networkName: String? = null,
    /** The number is in the household profile (About you: partner, the assistant, voicemail): never screened as spam. */
    val household: Boolean = false,
    /**
     * Spam-call lookup (2026-10-17): what the web's report sites say about the number, when Navilife's server answered
     * in time ([NumberLookupRules.BEFORE_RING_WAIT_MS]); null when it wasn't asked, didn't know or didn't answer.
     */
    val reputation: NumberReputation? = null,
    /**
     * The call comes from the call assistant's own number (About you): it is putting an urgent caller through, so it
     * rings ([CallReason.ASSISTANT]). Added 2026-10-17.
     */
    val fromAssistant: Boolean = false,
) {
    companion object { val NONE = CallSignals() }
}

data class CallDecision(
    val verdict: CallVerdict,
    val reason: CallReason,
    val callerKey: String,
    val listedName: String? = null,
    /** Set by the facade when this call put the number on the block list ([CallReason.AUTO_BLOCKED]): Activity's entry, for Undo. */
    val autoBlockId: String? = null,
    /** Scam call fix, slice 4: set by the facade when the number looks like an official switchboard ([SpoofHintRules]). */
    val spoof: SpoofLookalike? = null,
) {
    val rings: Boolean get() = verdict == CallVerdict.RING
}

/** A call the assistant declined, kept a few minutes so a second call from the same number rings. */
data class ScreenedCall(val callerKey: String, val atMs: Long)

object CallScreeningRules {
    const val REPEAT_WINDOW_MS = 3 * 60_000L
    const val WITHHELD = "withheld"
    /**
     * The assistant's phone number as About you has it (rule 13, release 2 slice 3: never in the code); null when it
     * isn't typed there. The Work screens and Setup show it.
     */
    val assistantNumber: String? get() = Household.names.assistantNumber

    /** "Navilife's assistant (01632 960000)", or without the number when About you has none. */
    fun assistantName(number: String? = assistantNumber): String =
        if (number == null) "Navilife's assistant" else "Navilife's assistant ($number)"

    /** "Test it: ring 01632 960000 from another phone"; with no number, where to add it. */
    fun testLine(number: String? = assistantNumber): String =
        if (number == null) "Add the assistant's number in Ask → Settings → About you to see it here"
        else "Test it: ring $number from another phone"

    /**
     * The Truecaller trade-off (call assistant polish 8, Meka switched from Truecaller on 2026-10-09): Android lets one
     * app screen calls, so the Work screens say what stops and how to switch back.
     */
    const val ONE_SCREENER_TITLE = "One app screens calls"
    val ONE_SCREENER_LINES = listOf(
        "Android lets one app screen calls. While Navilife does, Truecaller (or any other caller-ID app) stops screening " +
            "and blocking calls; its app still opens and still looks numbers up.",
        "Navilife's spam protection covers what it did: your block list, numbers your network can't verify, withheld " +
            "numbers in quiet hours, and Block · Report to 7726 on a message left by someone you don't know and in a quiet " +
            "notification after their call.",
        "To switch back: Settings → Apps → Choose default apps → Caller ID & spam app → Truecaller. Navilife's work-hours " +
            "screening stops until you pick Navilife again.",
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
     * caller check, a number on Suspected spam ([suspected]) and a withheld number in quiet hours, go to the assistant
     * instead of ringing (a redial doesn't get through on the repeat rule). Family, the
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
        /** Numbers on Suspected spam ([BlockedCallersView.suspectedKeys]): to the assistant, as likely spam. */
        suspected: Set<String> = emptySet(),
        /**
         * The assistant is paused for want of credit ([CallCredit.paused]): nothing is sent to it (a declined call
         * would reach a number that can't answer), so every call that would have been declined rings; the block list
         * still rejects silently.
         */
        paused: Boolean = false,
        /**
         * Spam protection ([WorkModeState.spamProtection]; on unless turned off in Advanced): a number that failed the
         * network's caller check is blocked before it rings, whatever the call assistant's switch says. Off: as before
         * slice 1 (to the assistant with the switch on, else it rings).
         */
        spamProtection: Boolean = true,
        /**
         * "Screen unknown callers all the time" ([ScreenUnknownRules]): with the assistant on, outside work a number
         * that isn't family, always-notify, a contact, someone Meka called lately or the household goes to the
         * assistant as at work; a second call within 3 minutes still rings and emergency numbers are never screened.
         */
        screenUnknownAlways: Boolean = false,
    ): CallDecision {
        val d = decideUnpaused(switchedOn, atWork, number, lists, recent, nowMs, blocked, signals, suspected, spamProtection, screenUnknownAlways)
        return if (paused && d.verdict == CallVerdict.DECLINE) CallDecision(CallVerdict.RING, CallReason.PAUSED, d.callerKey, d.listedName) else d
    }

    private fun decideUnpaused(
        switchedOn: Boolean,
        atWork: Boolean,
        number: String?,
        lists: PeopleLists,
        recent: List<ScreenedCall>,
        nowMs: Long,
        blocked: Set<String>,
        signals: CallSignals,
        suspected: Set<String>,
        spamProtection: Boolean,
        screenUnknownAlways: Boolean,
    ): CallDecision {
        val key = callerKey(number)
        val listed = number?.let { lists.nameForNumber(it) }
        val trusted = listed != null || signals.knownContact || signals.calledRecently || signals.household
        val repeat = recent.any { it.callerKey == key && nowMs - it.atMs in 0..REPEAT_WINDOW_MS }
        val official = OfficialCallerRules.claim(signals.networkName) != null
        return when {
            OfficialCallerRules.isEmergency(number) -> CallDecision(CallVerdict.RING, CallReason.EMERGENCY, key, listed)
            signals.fromAssistant && key != WITHHELD -> CallDecision(CallVerdict.RING, CallReason.ASSISTANT, key, listed)
            !trusted && key != WITHHELD && key in blocked -> CallDecision(CallVerdict.BLOCK, CallReason.BLOCKED, key)
            !trusted && key != WITHHELD && official -> CallDecision(CallVerdict.RING, CallReason.OFFICIAL_CLAIM, key)
            spamProtection && !trusted && key != WITHHELD && signals.verificationFailed ->
                CallDecision(CallVerdict.BLOCK, CallReason.AUTO_BLOCKED, key)
            spamProtection && !trusted && key != WITHHELD && NumberLookupRules.shouldBlock(signals.reputation) ->
                CallDecision(CallVerdict.BLOCK, CallReason.REPORTED_SCAM, key)
            !switchedOn -> CallDecision(CallVerdict.RING, CallReason.SWITCHED_OFF, key, listed)
            !trusted && key != WITHHELD && signals.verificationFailed -> CallDecision(CallVerdict.DECLINE, CallReason.LIKELY_SPAM, key)
            !trusted && key != WITHHELD && NumberLookupRules.toAssistant(signals.reputation) ->
                CallDecision(CallVerdict.DECLINE, CallReason.REPORTED_NUISANCE, key)
            !trusted && key != WITHHELD && key in suspected -> CallDecision(CallVerdict.DECLINE, CallReason.SUSPECTED_SPAM, key)
            !trusted && key == WITHHELD && signals.quietHours && !repeat -> CallDecision(CallVerdict.DECLINE, CallReason.WITHHELD_QUIET, key)
            !atWork && screenUnknownAlways && !trusted && !repeat -> CallDecision(CallVerdict.DECLINE, CallReason.UNKNOWN_ALWAYS, key)
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
    fun statusLine(switchedOn: Boolean, atWork: Boolean, screeningAllowed: Boolean?, paused: Boolean = false): String =
        statusLine(switchedOn, atWork, screeningAllowed, paused, screenUnknownAlways = false)

    /** As above, with "Screen unknown callers all the time" ([ScreenUnknownRules]) said off work when it's on. */
    fun statusLine(
        switchedOn: Boolean,
        atWork: Boolean,
        screeningAllowed: Boolean?,
        paused: Boolean,
        screenUnknownAlways: Boolean,
    ): String = when {
        !switchedOn -> "Off · calls ring as usual"
        screeningAllowed == false -> "On · allow Navilife to screen calls on the Fold"
        paused -> "Paused · Twilio credit · calls ring as usual until it's topped up"
        atWork -> "Screening calls · family, always-notify and repeat callers ring"
        screenUnknownAlways -> "Screening unknown numbers · people you know ring as usual"
        else -> "On at work · calls ring as usual now · spam goes to the assistant"
    }

    /** Whether the Fold should remember [decision] for the repeat rule (any call it declined, at any time). */
    fun remembers(decision: CallDecision): Boolean = decision.verdict == CallVerdict.DECLINE

    /** The Activity entry for a call screening stopped (blocked or sent to the assistant as likely spam); null otherwise. */
    fun activityLine(decision: CallDecision, number: String?): Pair<String, String>? {
        val who = if (decision.callerKey == WITHHELD) "Withheld number" else BlockedCallerRules.display(number.orEmpty())
        return when (decision.reason) {
            CallReason.BLOCKED -> "Blocked a call from $who" to "It is on your block list"
            // Auto-blocked calls are logged by the facade as an action with Undo ([AutoBlockRules.activity]).
            CallReason.AUTO_BLOCKED, CallReason.REPORTED_SCAM -> null
            CallReason.REPORTED_NUISANCE -> "Sent a call from $who to the assistant" to "People online report the number for nuisance or sales calls"
            CallReason.LIKELY_SPAM -> "Sent a likely spam call from $who to the assistant" to "The network couldn't verify the caller's number"
            CallReason.SUSPECTED_SPAM -> "Sent a call from $who to the assistant" to "It is on Suspected spam: Navilife's AI thought their last message sounded like a scam"
            CallReason.UNKNOWN_ALWAYS ->
                (if (decision.callerKey == WITHHELD) "Sent a withheld call to the assistant" else "Sent a call from $who to the assistant") to
                    ScreenUnknownRules.ACTIVITY_WHY
            CallReason.WITHHELD_QUIET -> "Sent a withheld call to the assistant" to "Withheld numbers don't ring in quiet hours (a second call within 3 minutes does)"
            else -> null
        }
    }
}

/**
 * "Screen unknown callers all the time" (Meka 2026-10-22 19:49: a scam caller pretending to be the police rang through
 * outside work hours, when unknown numbers ring). Off until Meka turns it on in Work mode → Call assistant. The
 * decision itself is in [CallScreeningRules.decide]; this is the copy both apps show.
 */
object ScreenUnknownRules {
    const val TITLE = "Screen unknown callers all the time"

    /** The line under the switch: what it does now, or why it can't yet. */
    fun line(on: Boolean, assistantOn: Boolean): String = when {
        !assistantOn -> "Turn the call assistant on first · then unknown numbers can go to it evenings and weekends too"
        on -> "On · numbers you don't know go to the assistant evenings and weekends too"
        else -> "Off · outside work every call rings"
    }

    /** The trade-off, said once under the switch. */
    const val TRADE_OFF =
        "Family, your always-notify list, your contacts and anyone you called lately still ring, and so does anyone who " +
            "calls again within 3 minutes. Emergency numbers are never screened. The trade-off: a real call from a number " +
            "you don't know (a delivery driver, a new surgery, a tradesperson) goes to the assistant, which takes a message."

    /** Activity's "why" for a call it sent to the assistant. */
    const val ACTIVITY_WHY = "You don't know the number, and Screen unknown callers all the time is on"
}
