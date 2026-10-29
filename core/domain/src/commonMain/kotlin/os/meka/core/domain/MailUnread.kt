package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Today's mail line: "Personal · 412 unread", and under it [tidy] "Tidy 380 newsletters, promos and receipts ›" when
 * Navilife's rules would tidy some, a tap opening Mail, where each account's clean-up lists what goes where and its
 * Approve (Meka 2026-10-24 14:47: say what "1,048 Navilife can tidy" means, with a tap to start the clean-up).
 */
data class MailUnreadLine(val text: String, val spoken: String, val lit: Boolean, val tidy: String? = null)

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
    /** An unchanged count is written again this long after the last, well inside [STALE_MS]. */
    const val RESTAMP_MS = 2 * 3_600_000L
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
        // Approve must land (2026-10-25): an unchanged count is still stamped again now and then, so it never goes stale
        // ([STALE_MS]) while the account is being checked (a stale count took Mark read's card away and refused his tap).
        if (was == unread && prev.tidyUnread == tidyUnread) return nowMs - at >= RESTAMP_MS
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
    fun todayLine(backlogs: List<MailBacklog>, nowMs: Long): MailUnreadLine? = todayLine(backlogs, nowMs, null)

    /**
     * The unread count went up (2026-10-18): with the server's account list, each account is named by label and
     * provider when the label alone could mislead ("Personal Outlook · 136 unread"), the server's own fresh count is
     * used, and the accounts Navilife can't read are named ("· Personal Gmail and Business Gmail not read yet"), so the
     * line never reads as the whole of his mail when it isn't. With nothing countable but unreadable accounts:
     * "Mail · Personal Gmail not read yet · see Health".
     */
    fun todayLine(backlogs: List<MailBacklog>, nowMs: Long, accounts: List<MailAccountFacts>?): MailUnreadLine? {
        data class Count(val label: String, val full: String, val provider: String?, val unread: Int, val tidy: Int, val order: Int)
        val list = accounts?.filter { MailProviders.isMail(it.provider) }
        val counted = backlogs.filter { b ->
            b.inboxUnread != null && b.unreadCountedAtMs != null && nowMs - b.unreadCountedAtMs <= STALE_MS
        }.map { b ->
            // Two accounts sharing a label ("Personal" on Outlook and Gmail) are told apart by the server's key.
            val a = list?.let { l ->
                b.accountKey?.let { k -> l.firstOrNull { MailAccountRules.key(it.provider, it.email) == k } } ?: MailHealthRules.accountFor(b.account, l)
            }
            val live = a?.inboxUnread?.takeIf { a.lastSyncAtMs != null && nowMs - a.lastSyncAtMs <= STALE_MS }?.toInt()
            val unread = live ?: b.inboxUnread ?: 0
            Count(
                label = a?.let(MailHealthRules::labelOf) ?: b.account, full = a?.let(MailHealthRules::name) ?: b.shownName,
                provider = a?.provider, unread = unread, tidy = b.tidyUnread.coerceAtMost(unread), order = order(b.account),
            )
        }.sortedWith(compareBy<Count> { it.order }.thenBy { it.full })
        val missing = MailHealthRules.unreadableNames(list, nowMs)
        val missingPart = missing?.let { " · $it not read yet" } ?: ""
        val missingSpoken = missing?.let { " Navilife can't read $it yet." } ?: ""
        if (counted.isEmpty()) {
            return missing?.let { MailUnreadLine("Mail · $it not read yet · see Health", "Navilife can't read $it yet.", false) }
        }
        val total = counted.sumOf { it.unread }
        if (total == 0) {
            return if (missing == null) MailUnreadLine(NONE_UNREAD, "Nothing unread in your mail.", false)
            else MailUnreadLine("Mail · nothing unread in ${counted.joinToString(", ") { it.full }}$missingPart", "Nothing unread in the mail Navilife reads.$missingSpoken", false)
        }
        val tidy = counted.sumOf { it.tidy }
        val tidyLine = tidyLine(tidy)
        val spokenTidy = if (tidy > 0) " ${n(tidy)} of them ${if (tidy == 1) "is a newsletter, promo or receipt" else "are newsletters, promos or receipts"} Navilife can tidy; double tap Tidy to see the clean-up." else ""
        val unreadOnes = counted.filter { it.unread > 0 }
        if (unreadOnes.size == 1) {
            val b = unreadOnes.single()
            val u = b.unread
            return MailUnreadLine(
                "${b.full} · ${n(u)} unread$missingPart",
                "${n(u)} unread ${if (u == 1) "email" else "emails"} in ${b.full}.$spokenTidy$missingSpoken",
                false, tidyLine,
            )
        }
        val names = shortNames(unreadOnes.map { Triple(it.label, it.provider, it.full) })
        val each = unreadOnes.zip(names).joinToString(" · ") { (c, name) -> "$name ${n(c.unread)}" }
        val spokenEach = unreadOnes.map { b -> "${n(b.unread)} in ${b.full}" }
        val spokenList = spokenEach.dropLast(1).joinToString(", ") + " and " + spokenEach.last()
        return MailUnreadLine("Unread mail · $each$missingPart", "Unread mail: $spokenList.$spokenTidy$missingSpoken", false, tidyLine)
    }

    /** "Tidy 1,048 newsletters, promos and receipts ›" (a tap opens Mail's clean-up); null with nothing to tidy. */
    fun tidyLine(tidy: Int): String? = when {
        tidy <= 0 -> null
        tidy == 1 -> "Tidy 1 newsletter, promo or receipt ›"
        else -> "Tidy ${n(tidy)} newsletters, promos and receipts ›"
    }

    /**
     * Reconnect clears at once, (2): the several-accounts line names each by its label ("Personal", "Business"); two
     * sharing a label are named by their provider instead ("Outlook 154 · Gmail 1,048 · Business 28"), and by the full
     * name ("Personal Gmail") if the provider doesn't tell them apart either. Each entry: label, provider (null when the
     * account list isn't known), full name.
     */
    fun shortNames(accounts: List<Triple<String, String?, String>>): List<String> {
        val byLabel = accounts.groupingBy { it.first }.eachCount()
        val first = accounts.map { (label, provider, full) ->
            when {
                (byLabel[label] ?: 0) <= 1 -> label
                provider != null -> MailProviders.name(provider)
                else -> full
            }
        }
        val counts = first.groupingBy { it }.eachCount()
        return accounts.zip(first).map { (a, name) -> if ((counts[name] ?: 0) > 1) a.third else name }
    }
}
