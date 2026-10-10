package os.meka.core.domain

/**
 * Daily receipt (build plan V1), non-AI: an evening line on Today, "MEKA handled 12 things today", that opens the
 * activity log. It counts today's entries of what MEKA did on its own (reminders and digests it sent, changes it made
 * that are still in place, calls it stopped or the call assistant handled, phone builds it published); Meka's own calendar edits, family
 * links and watch links are things he did, so they aren't counted. Nothing new is stored: it is read from the log.
 */
data class DailyReceipt(
    /** "MEKA handled 12 things today" */
    val line: String,
    /** The biggest parts, at most [ReceiptRules.PARTS_SHOWN]: "5 reminders · 3 calls stopped · 2 digests". */
    val detail: String,
    val count: Int,
)

object ReceiptRules {
    /** How many kinds the detail names (the rest are still in the count). */
    const val PARTS_SHOWN = 3

    /** The kinds that are MEKA acting for Meka, in the order the detail names them when counts tie. */
    val HANDLED: List<ActivityKind> = listOf(
        ActivityKind.CHANGED, ActivityKind.REMINDED, ActivityKind.SCREENED, ActivityKind.CALL,
        ActivityKind.DIGEST, ActivityKind.PUBLISHED,
    )

    fun counts(i: ActivityItem): Boolean =
        i.kind in HANDLED && !(i.kind == ActivityKind.CHANGED && i.undoneAtMs != null)

    /** Today's receipt, or null when MEKA hasn't handled anything today. */
    fun receipt(items: List<ActivityItem>, nowMs: Long, cal: LocalCalendar): DailyReceipt? {
        val today = cal.epochDayOf(nowMs)
        val handled = items.filter { counts(it) && cal.epochDayOf(it.atMs) == today && it.atMs <= nowMs }
        if (handled.isEmpty()) return null
        val byKind = handled.groupingBy { it.kind }.eachCount()
        val parts = HANDLED.filter { (byKind[it] ?: 0) > 0 }
            .sortedWith(compareByDescending<ActivityKind> { byKind[it] ?: 0 }.thenBy { HANDLED.indexOf(it) })
            .take(PARTS_SHOWN)
            .map { part(it, byKind.getValue(it)) }
        val n = handled.size
        return DailyReceipt(
            line = "MEKA handled ${if (n == 1) "1 thing" else "$n things"} today",
            detail = parts.joinToString(" · "),
            count = n,
        )
    }

    fun part(kind: ActivityKind, n: Int): String = when (kind) {
        ActivityKind.CHANGED -> plural(n, "change")
        ActivityKind.REMINDED -> plural(n, "reminder")
        ActivityKind.SCREENED -> plural(n, "call stopped", "calls stopped")
        ActivityKind.CALL -> plural(n, "call handled", "calls handled")
        ActivityKind.DIGEST -> plural(n, "digest")
        ActivityKind.PUBLISHED -> plural(n, "phone build")
        else -> plural(n, "thing")
    }
}
