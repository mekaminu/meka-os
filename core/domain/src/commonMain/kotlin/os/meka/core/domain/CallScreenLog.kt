package os.meka.core.domain

/**
 * Spam calls still ring, slice 2: the diagnosis. The Fold keeps its last [MAX] screened calls (newest first) and sends
 * them, with whether Navilife holds the Caller ID & spam role, the spam switch and whether it may read contacts and the
 * call log, alongside the notification trace; deploy's `publish-fold` job prints them as "Call screening" notices so a
 * build run can see whether a call that rang ever reached the screening service, and why it was let through.
 *
 * Never a number: each caller is a [tag], a hash of the number's key salted with a random secret that never leaves the
 * phone, so the same caller has the same tag on one phone but the tag can't be turned back into the number (the
 * repository and its CI logs are public, build plan rule 13).
 */
data class ScreenedCallEntry(
    val atMs: Long,
    /** "RING" · "DECLINE" · "BLOCK" · [TIMEOUT] (no answer in time, so it rang). */
    val verdict: String,
    /** e.g. "#3fa2c1 · Failed the network's caller check · blocked · net failed · not a contact". */
    val detail: String,
)

/** The phone's call-screening state for the report. */
data class CallScreenReport(
    val roleHeld: Boolean,
    val protection: Boolean,
    /** Whether Navilife may read contacts and the call log (without, contacts and recent callers aren't recognised). */
    val lookups: Boolean,
    /** Newest first, at most [CallScreenLogRules.MAX]. */
    val calls: List<ScreenedCallEntry>,
)

object CallScreenLogRules {
    const val MAX = 20
    const val TIMEOUT = "TIMEOUT"
    const val DETAIL_MAX = 120

    /** "withheld" for no number; else "#" and 6 hex of a salted FNV-1a hash of the number's key. */
    fun tag(number: String?, salt: String): String {
        if (number.isNullOrBlank()) return "withheld"
        val key = People.key(number)
        var h = 0x811c9dc5.toInt()
        for (c in "$salt|$key") {
            h = h xor c.code
            h *= 0x01000193
        }
        return "#" + (h.toLong() and 0xffffffL).toString(16).padStart(6, '0')
    }

    /** The verification state as the report says it: "net failed" · "net passed" · "net not checked". */
    fun net(verification: Int?): String = when (verification) {
        VERIFICATION_FAILED -> "net failed"
        VERIFICATION_PASSED -> "net passed"
        else -> "net not checked"
    }

    /** Android's `Connection.VERIFICATION_STATUS_*` values (kept here so the rules stay platform-free). */
    const val VERIFICATION_PASSED = 1
    const val VERIFICATION_FAILED = 2

    /** One screened call; [decision] null = no answer within the time Android gives, so it rang. */
    fun entry(
        atMs: Long, tag: String, decision: CallDecision?, signals: CallSignals?, verification: Int?, protection: Boolean,
    ): ScreenedCallEntry {
        val parts = buildList {
            add(tag)
            add(decision?.reason?.label ?: "no answer in time · rang")
            if (decision?.autoBlockId != null) add("auto-blocked")
            add(net(verification))
            if (signals != null) {
                add(if (signals.knownContact) "contact" else "not a contact")
                if (signals.calledRecently) add("called in 90 days")
                if (signals.household) add("household")
                if (signals.networkName != null) add("network name sent")
            }
            if (!protection) add("protection off")
        }
        return ScreenedCallEntry(atMs, decision?.verdict?.name ?: TIMEOUT, parts.joinToString(" · ").take(DETAIL_MAX))
    }

    /**
     * Whether a call from the caller tagged [tag] since [sinceMs] rang because its network name claimed an official body
     * (police, bank, NHS, school): such a caller is never auto-blocked (spam calls still ring, slice 3).
     */
    fun claimedOfficial(log: List<ScreenedCallEntry>, tag: String, sinceMs: Long): Boolean =
        log.any { it.atMs >= sinceMs && it.detail.startsWith("$tag · ") && CallReason.OFFICIAL_CLAIM.label in it.detail }

    fun add(log: List<ScreenedCallEntry>, e: ScreenedCallEntry): List<ScreenedCallEntry> =
        (listOf(e) + log).sortedByDescending { it.atMs }.take(MAX)

    /** One line per entry, "at\tverdict\tdetail"; tabs and newlines in a detail become spaces. */
    fun encode(log: List<ScreenedCallEntry>): String =
        log.take(MAX).joinToString("\n") { "${it.atMs}\t${clean(it.verdict)}\t${clean(it.detail)}" }

    /** Unreadable lines are skipped; null or blank = empty. */
    fun decode(s: String?): List<ScreenedCallEntry> = s.orEmpty().lineSequence().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size != 3) return@mapNotNull null
        val at = p[0].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        if (p[1].isBlank()) return@mapNotNull null
        ScreenedCallEntry(at, p[1], p[2].take(DETAIL_MAX))
    }.sortedByDescending { it.atMs }.take(MAX).toList()

    /** The publish job's summary line: "role held · protection on · contacts and call log readable · 3 screened, last 20:41". */
    fun summary(r: CallScreenReport, at: (Long) -> String): String = listOf(
        if (r.roleHeld) "Caller ID & spam role held" else "Caller ID & spam role NOT held (calls never reach Navilife)",
        if (r.protection) "protection on" else "protection OFF",
        if (r.lookups) "contacts and call log readable" else "contacts/call log NOT readable",
        if (r.calls.isEmpty()) "no calls screened yet" else "${r.calls.size} screened, last ${at(r.calls.first().atMs)}",
    ).joinToString(" · ")

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
}
