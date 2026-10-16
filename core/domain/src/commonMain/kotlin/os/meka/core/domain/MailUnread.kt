package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/** Today's mail line: "Personal · 412 unread · 380 are newsletters, promos or receipts Navilife can tidy". */
data class MailUnreadLine(val text: String, val spoken: String, val lit: Boolean)

/**
 * Inbox fixes, slice 7 (build plan item (4), Meka 2026-10-15: "hundreds of unread messages"): **how much unread mail
 * each account holds, and how much of it Navilife can tidy**, on Today under the header on both apps. Non-AI, pure.
 *
 * Each check the server counts the inbox's unread mail (the provider's own count: Gmail's INBOX label, Outlook's
 * inbox folder) and how many of the unread emails it holds from the last 60 days its rules would tidy, and writes both
 * on the account's `mail_backlog` entity ([MailBacklogFields.INBOX_UNREAD], [MailBacklogFields.TIDY_UNREAD]; additive
 * LWW fields, ADR-008 addendum). To spare the devices a sync on every small change it writes only when the count is
 * new, moved by [STEP] or more, or [REWRITE_EVERY_MS] has passed with any change. Counts only, never an id or address.
 */
object MailUnreadRules {
    /** A smaller change waits until this long after the last count. */
    const val REWRITE_EVERY_MS = 30 * 60_000L
    const val STEP = 10
    /** A count older than this is left off Today (the account hasn't been checked for a while). */
    const val STALE_MS = 6 * 3_600_000L

    const val NONE_UNREAD = "Mail · nothing unread ✓"

    fun fields(unread: Int, tidyUnread: Int, atMs: Long): Map<String, FieldValue> = mapOf(
        MailBacklogFields.INBOX_UNREAD to unread.coerceAtLeast(0).toLong().fv(),
        MailBacklogFields.TIDY_UNREAD to tidyUnread.coerceIn(0, unread.coerceAtLeast(0)).toLong().fv(),
        MailBacklogFields.UNREAD_COUNTED_AT to atMs.fv(),
    )

    /** Whether the server writes this check's counts over [prev] (the account's backlog entity as the apps hold it). */
    fun writeDue(prev: MailBacklog?, unread: Int, tidyUnread: Int, nowMs: Long): Boolean {
        val was = prev?.inboxUnread ?: return true
        val at = prev.unreadCountedAtMs ?: return true
        if (was == unread && prev.tidyUnread == tidyUnread) return false
        if (kotlin.math.abs(was - unread) >= STEP || kotlin.math.abs(prev.tidyUnread - tidyUnread) >= STEP) return true
        // Down to nothing unread (or from it) shows at once: "nothing unread ✓" is worth the sync.
        if ((was == 0) != (unread == 0)) return true
        return nowMs - at >= REWRITE_EVERY_MS
    }

    /** The same, from the entity's raw fields (the server's view; the backlog's plan fields may not be written yet). */
    fun writeDueFields(prev: Map<String, FieldValue>?, unread: Int, tidyUnread: Int, nowMs: Long): Boolean {
        fun long(k: String) = (prev?.get(k) as? FieldValue.Int64)?.value
        val was = long(MailBacklogFields.INBOX_UNREAD)?.toInt()
        val at = long(MailBacklogFields.UNREAD_COUNTED_AT)?.takeIf { it > 0 }
        val b = if (was == null || at == null) null else MailBacklog(
            id = "", account = "", startedAtMs = 0, scanned = 0, readyAtMs = null, counts = emptyMap(), top = null, approvedAtMs = null,
            inboxUnread = was, tidyUnread = long(MailBacklogFields.TIDY_UNREAD)?.toInt() ?: 0, unreadCountedAtMs = at,
        )
        return writeDue(b, unread, tidyUnread, nowMs)
    }

    private fun n(v: Int) = SpeechRules.grouped(v.toLong())

    private fun order(account: String): Int = MailLabel.entries.indexOfFirst { it.label == account }.let { if (it < 0) MailLabel.entries.size else it }

    /**
     * Today's line, or null before any account has been counted (or every count is stale): one account
     * "Personal · 412 unread · 380 are newsletters, promos or receipts Navilife can tidy"; several "Unread mail ·
     * Personal 412 · Outlook 82 · Business 5 · 380 Navilife can tidy"; nothing unread anywhere [NONE_UNREAD]. Never lit:
     * it's a picture of the inbox, not something to do.
     */
    fun todayLine(backlogs: List<MailBacklog>, nowMs: Long): MailUnreadLine? {
        val counted = backlogs.filter { b ->
            b.inboxUnread != null && b.unreadCountedAtMs != null && nowMs - b.unreadCountedAtMs <= STALE_MS
        }.sortedWith(compareBy<MailBacklog> { order(it.account) }.thenBy { it.account })
        if (counted.isEmpty()) return null
        val total = counted.sumOf { it.inboxUnread ?: 0 }
        if (total == 0) return MailUnreadLine(NONE_UNREAD, "Nothing unread in your mail.", false)
        val tidy = counted.sumOf { it.tidyUnread.coerceAtMost(it.inboxUnread ?: 0) }
        val unreadOnes = counted.filter { (it.inboxUnread ?: 0) > 0 }
        if (unreadOnes.size == 1) {
            val b = unreadOnes.single()
            val u = b.inboxUnread ?: 0
            val tidyPart = if (tidy > 0) " · ${n(tidy)} ${if (tidy == 1) "is a newsletter, promo or receipt" else "are newsletters, promos or receipts"} Navilife can tidy" else ""
            val spokenTidy = if (tidy > 0) "; ${n(tidy)} of them ${if (tidy == 1) "is a newsletter, promo or receipt" else "are newsletters, promos or receipts"} Navilife can tidy" else ""
            return MailUnreadLine(
                "${b.account} · ${n(u)} unread$tidyPart",
                "${n(u)} unread ${if (u == 1) "email" else "emails"} in ${b.account}$spokenTidy.",
                false,
            )
        }
        val each = unreadOnes.joinToString(" · ") { "${it.account} ${n(it.inboxUnread ?: 0)}" }
        val tidyPart = if (tidy > 0) " · ${n(tidy)} Navilife can tidy" else ""
        val spokenEach = unreadOnes.map { b -> "${n(b.inboxUnread ?: 0)} in ${b.account}" }
        val spokenList = spokenEach.dropLast(1).joinToString(", ") + " and " + spokenEach.last()
        val spokenTidy = if (tidy > 0) "; ${n(tidy)} of them are newsletters, promos or receipts Navilife can tidy" else ""
        return MailUnreadLine("Unread mail · $each$tidyPart", "Unread mail: $spokenList$spokenTidy.", false)
    }
}
