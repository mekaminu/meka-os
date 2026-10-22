package os.meka.core.domain

/**
 * "Was that a scam?" (build plan "Fix first", Meka 2026-10-22 19:49: a scam caller pretending to be the police rang
 * through at 17:00 and Navilife didn't catch it), slice 3. Non-AI and pure.
 *
 * After a call from a number nobody knows that rang through and that he took (or whose ending the phone can't read),
 * and always after a caller claiming an official body, the quiet after-call note asks "Was that a scam?" with
 * **Scam · Block · Fine** instead of only Block; Activity → Calls asks the same in the call's detail. Scam blocks the
 * number with "You said it was a scam" and marks it as a scam (so it is never let off as Not spam by itself), then
 * offers to report it: Action Fraud's page for calls, or a text to 7726 (both his tap; nothing is sent by Navilife).
 * Fine remembers the number on this phone only, so Navilife doesn't ask about it again (the in-call warning still
 * shows: a number can be spoofed).
 */
object ScamAskRules {
    const val QUESTION = "Was that a scam?"
    const val SCAM = "Scam"
    const val BLOCK = BlockedCallerRules.BLOCK_LABEL
    const val FINE = "Fine"

    /** Why the number is on the block list after Scam (with what happened: "You said it was a scam · Rang · today"). */
    const val SCAM_WHY = "You said it was a scam"

    const val ACTION_FRAUD = "Report to Action Fraud"
    /** Action Fraud's page for reporting fraud and cyber crime (the UK's national reporting centre). */
    const val ACTION_FRAUD_URL = "https://www.actionfraud.police.uk/reporting-fraud-and-cyber-crime"

    /** The after-call note once Scam was tapped. */
    const val SCAM_DONE = "Blocked as a scam · it won't ring again"
    const val REPORT_HINT = "To report it: Action Fraud for calls, or a text to 7726, your network's free scam line. You send it."

    /** The after-call note once Fine was tapped (it goes by itself a few seconds later). */
    const val FINE_DONE = "Marked fine · Navilife won't ask about this number again"
    const val FINE_NOTE_MS = 8_000L

    /** At most this many numbers are remembered as fine (the oldest go first). */
    const val MAX_FINE = 200

    /**
     * Whether the after-call note asks: a call that rang through and he took ([CallOutcome.ANSWERED]) or whose ending
     * the phone can't read ([CallOutcome.UNKNOWN]), and any caller claiming an official body. Never a call sent to the
     * assistant (it didn't ring) or one he missed or declined (he didn't hear them: Block stays the offer).
     */
    fun asks(outcome: CallOutcome, claim: OfficialClaim?): Boolean = when (outcome) {
        CallOutcome.TO_ASSISTANT -> false
        CallOutcome.ANSWERED, CallOutcome.UNKNOWN -> true
        CallOutcome.MISSED, CallOutcome.DECLINED -> claim != null
    }

    /** What Scam writes as why: "You said it was a scam · Rang · today", at most [BlockedCallerRules.MAX_WHY]. */
    fun scamWhy(happened: String?): String =
        (SCAM_WHY + (happened?.trim()?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")).take(BlockedCallerRules.MAX_WHY)

    /** Whether a block list entry's why says he marked it a scam. */
    fun isScam(why: String?): Boolean = why?.startsWith(SCAM_WHY) == true

    /**
     * Whether Activity → Calls asks about a call the phone screened: it rang through, from a real number nobody knows
     * (not a contact, someone he called, the household, the assistant or an emergency service), not on the block list
     * and not marked fine.
     */
    fun asksInLog(c: PhoneCall, blocked: Set<String>, fine: Set<String>): Boolean {
        if (c.verdict != CallVerdict.RING || c.known) return false
        if (c.reason == CallReason.EMERGENCY || c.reason == CallReason.ASSISTANT || c.reason == CallReason.FAMILY ||
            c.reason == CallReason.ALWAYS_RING) return false
        val key = BlockedCallerRules.keyOf(c.number) ?: return false
        return key !in blocked && key !in fine
    }

    /** The numbers marked fine with [key] added (newest last, at most [MAX_FINE]). */
    fun addFine(fine: List<String>, key: String): List<String> = (fine.filter { it != key } + key).takeLast(MAX_FINE)

    /** One key per line; blank lines skipped. */
    fun encode(fine: List<String>): String = fine.takeLast(MAX_FINE).joinToString("\n")

    fun decode(s: String?): List<String> = s.orEmpty().lineSequence().map { it.trim() }.filter { it.startsWith("tel:") }.toList().takeLast(MAX_FINE)
}
