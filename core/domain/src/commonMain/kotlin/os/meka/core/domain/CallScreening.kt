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
enum class CallVerdict { RING, DECLINE }

enum class CallReason(val label: String) {
    SWITCHED_OFF("Call assistant off"),
    OFF_WORK("Off work"),
    FAMILY("Family"),
    ALWAYS_RING("Always notify"),
    REPEAT("Called again within 3 minutes"),
    AT_WORK("Declined at work"),
}

data class CallDecision(val verdict: CallVerdict, val reason: CallReason, val callerKey: String, val listedName: String? = null) {
    val rings: Boolean get() = verdict == CallVerdict.RING
}

/** A call the assistant declined, kept a few minutes so a second call from the same number rings. */
data class ScreenedCall(val callerKey: String, val atMs: Long)

object CallScreeningRules {
    const val REPEAT_WINDOW_MS = 3 * 60_000L
    const val WITHHELD = "withheld"

    /** The key a caller's number is remembered by: the phone number's key, or [WITHHELD]. */
    fun callerKey(number: String?): String {
        val n = number?.trim().orEmpty()
        if (n.isEmpty() || n.none { it.isDigit() }) return WITHHELD
        val key = People.key(n)
        return if (key.startsWith("tel:")) key else "tel:" + n.filter { it.isDigit() }
    }

    fun decide(
        switchedOn: Boolean,
        atWork: Boolean,
        number: String?,
        lists: PeopleLists,
        recent: List<ScreenedCall>,
        nowMs: Long,
    ): CallDecision {
        val key = callerKey(number)
        val listed = number?.let { lists.nameForNumber(it) }
        return when {
            !switchedOn -> CallDecision(CallVerdict.RING, CallReason.SWITCHED_OFF, key, listed)
            !atWork -> CallDecision(CallVerdict.RING, CallReason.OFF_WORK, key, listed)
            listed != null && lists.family.contains(listed) -> CallDecision(CallVerdict.RING, CallReason.FAMILY, key, listed)
            listed != null -> CallDecision(CallVerdict.RING, CallReason.ALWAYS_RING, key, listed)
            recent.any { it.callerKey == key && nowMs - it.atMs in 0..REPEAT_WINDOW_MS } ->
                CallDecision(CallVerdict.RING, CallReason.REPEAT, key)
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
        else -> "On at work · calls ring as usual now"
    }
}
