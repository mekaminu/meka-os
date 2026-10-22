package os.meka.core.domain

/**
 * The in-call scam warning (build plan "Fix first", Meka 2026-10-22 19:49: a scam caller pretending to be the police
 * rang through at 17:00 and Navilife didn't catch it), slice 2. Non-AI and pure.
 *
 * Whenever a number nobody knows rings through (off work, the assistant off or paused, a stranger's redial) and always
 * when the network name claims an official body, the Fold puts a warning over the incoming-call screen and on the
 * lock screen (a heads-up notification, shown in full on the lock screen), and Ada says one short line in his earbuds
 * while it rings when earbuds or headphones are connected (never out of the phone's speaker). Never for family, his
 * always-notify list, contacts, anyone he called lately, the household, his call assistant, his own number or an
 * emergency number.
 */
data class InCallWarning(
    /** "Unknown number", "Withheld number", or "Says it's the police · unknown number". */
    val title: String,
    /** The advice, the same every time so it is recognised at a glance. */
    val line: String,
    /** For a claimed official body: how to check that one ([OfficialCallerRules.headsUp]); null otherwise. */
    val check: String?,
    /** Ada's one line in his earbuds. */
    val spoken: String,
    val claim: OfficialClaim?,
    /** Slice 4: the number looks like an official switchboard's ([SpoofHintRules]); null otherwise. */
    val spoof: SpoofLookalike? = null,
) {
    /** The notification's expanded text: the spoof hint, the advice, then how to check a claimed body. */
    val big: String get() = listOfNotNull(spoof?.hint, line, check).joinToString("\n")
}

object InCallWarningRules {
    const val CHANNEL_NAME = "Scam warnings on calls"
    const val CHANNEL_ABOUT = "A warning over the incoming-call screen when a number you don't know rings through"

    /** How long the warning stays up at most (a long call keeps it; the after-call note replaces it sooner). */
    const val SHOW_FOR_MS = 30 * 60_000L

    const val ADVICE = "If they say they're the police or your bank: never give card details or PINs, or move money. " +
        "Hang up and call 101 or the number on your card from another phone."

    /** The line in Work mode → Spam protection (both apps) saying the warning exists. */
    const val SETTINGS_LINE = "When a number you don't know rings through, a scam warning shows over the call screen " +
        "and on the lock screen, and Ada says it in your earbuds if they're in. A number that looks like a police, bank " +
        "or HMRC switchboard says “Could be spoofed”; add your own force's and bank's in About you → Official numbers."

    /** The reasons a call rang that leave the caller unvetted: off work, the assistant off or paused, a redial. */
    private val UNVETTED = setOf(
        CallReason.OFF_WORK, CallReason.SWITCHED_OFF, CallReason.PAUSED, CallReason.REPEAT, CallReason.OFFICIAL_CLAIM,
    )

    /**
     * Whether a call that is about to ring gets the warning. [decision] null (screening didn't answer in time): no
     * warning, nothing is known. [ownNumber]: the phone's own number (a test call).
     */
    fun shouldWarn(decision: CallDecision?, signals: CallSignals, ownNumber: Boolean = false): Boolean {
        if (decision == null || decision.verdict != CallVerdict.RING || ownNumber) return false
        if (decision.reason == CallReason.OFFICIAL_CLAIM) return true
        // Slice 4: a number that looks like an official switchboard is warned even when it's a saved contact or one
        // he called lately (a spoofed bank number shows the bank's contact name); never family, his always-notify
        // list, the household, his call assistant or an emergency number.
        if (decision.spoof != null && decision.reason !in NEVER_SPOOF_WARNED && decision.listedName == null && !signals.household && !signals.fromAssistant) return true
        if (decision.reason !in UNVETTED) return false
        if (decision.listedName != null) return false
        return !(signals.knownContact || signals.calledRecently || signals.household || signals.fromAssistant)
    }

    private val NEVER_SPOOF_WARNED = setOf(CallReason.FAMILY, CallReason.ALWAYS_RING, CallReason.EMERGENCY, CallReason.ASSISTANT)

    /**
     * The warning for a caller [shouldWarn] picked; [withheld] when the number was withheld; [spoof] when it looks like
     * an official switchboard (slice 4: the title says "Could be spoofed" unless a claimed body already heads it).
     */
    fun warning(signals: CallSignals, withheld: Boolean, spoof: SpoofLookalike? = null): InCallWarning {
        val claim = OfficialCallerRules.claim(signals.networkName)
        val plain = if (withheld) "Withheld number" else "Unknown number"
        val title = when {
            claim != null -> "Says it's ${claim.who} · ${plain.lowercase()}"
            spoof != null -> spoof.title
            else -> plain
        }
        val spoken = if (spoof != null) {
            spoof.spoken
        } else if (claim != null) {
            "Careful: this caller says they're ${claim.who}. Never give card details or move money. Hang up and call back on a number you know."
        } else {
            "Unknown caller. If they say they're the police or your bank, never give card details or move money."
        }
        return InCallWarning(title, ADVICE, claim?.let { OfficialCallerRules.headsUp(it) }, spoken, claim, spoof)
    }

    /** Ada says it only into his ears: headphones or earbuds connected, never the speaker (the ringtone is there). */
    fun speak(headphones: Boolean): Boolean = headphones
}
