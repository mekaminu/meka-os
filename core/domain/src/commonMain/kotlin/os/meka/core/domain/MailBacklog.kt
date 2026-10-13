package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Inbox autopilot, slice 8: the backlog clean-up, once per account (Meka, 2026-10-12: "scan the existing inbox, group by
 * sender/category, show a plan … and apply only after he taps Approve"). Autopilot only tidies mail that arrives after
 * its dry-run day; this is everything already sitting in the inbox from before then.
 *
 * Slice 8a: the server looks through that older mail (headers only, a few hundred a check), plans each email with
 * [MailBacklogRules.plan] and publishes the plan as one `mail_backlog` entity per account (ADR-008 addendum 2026-10-13);
 * both apps show it in Mail. **Nothing moves**: Approve, applying it and its 30-day Undo are slice 8b.
 *
 * The plan reuses Autopilot's never-touch rules ([MailTidyRules.plan]: only sure newsletters, receipts, notifications
 * and promos still in the inbox; never people, public bodies and schools, banks, his partner, or a thread he wrote in)
 * and adds two of its own from Meka's brief: tidy-kind mail is only planned once it is older than [ARCHIVE_AFTER_DAYS]
 * days ("Archive 3,412 newsletters/promos older than 30 days"), and mail the rules call junk (Gmail's spam label) goes to
 * Trash (recoverable for 30 days, never deleted for good). Everything else is kept ("keep 212 that may need you").
 */
enum class MailBacklogKind(val one: String, val many: String, val tidy: MailTidy) {
    KEEP("kept", "kept", MailTidy.KEEP),
    NEWSLETTER("newsletter", "newsletters", MailTidy.NEWSLETTER),
    RECEIPT("receipt or order", "receipts and orders", MailTidy.RECEIPT),
    ARCHIVE("notification or promo", "notifications and promos", MailTidy.ARCHIVE),
    /** Junk still in the inbox: to Trash (Gmail's / Outlook's own, recoverable 30 days). */
    JUNK("junk", "junk", MailTidy.TRASH);

    companion object {
        fun of(stored: String?): MailBacklogKind? = entries.firstOrNull { it.name == stored }
    }
}

/** What the backlog planner sees of one older email. */
data class MailBacklogFacts(
    val fromAddress: String?,
    val inInbox: Boolean,
    val sorted: MailSorted?,
    val receivedAtMs: Long,
    val keepAddresses: Set<String> = emptySet(),
    /** In a thread Meka wrote in (known once the scan has finished listing): kept. */
    val replied: Boolean = false,
)

/** The server's fields on a `mail_backlog` entity (LWW). The plan's counts, never an email id, subject or address. */
object MailBacklogFields {
    /** The account's label as Mail shows it ("Personal"), never the address. */
    const val ACCOUNT = "account"
    const val STARTED_AT = "startedAtMs"
    /** Emails looked at so far. */
    const val SCANNED = "scanned"
    /** Set once every older email was looked at and the threads he wrote in were taken out: the plan is final. */
    const val READY_AT = "readyAtMs"
    const val NEWSLETTERS = "newsletters"
    const val RECEIPTS = "receipts"
    const val ARCHIVED = "archived"
    const val JUNK = "junk"
    const val KEPT = "kept"
    /** "Weekly Digest 412 · Oakfield 230 · Daily Deals 118": the senders with the most to archive. */
    const val TOP = "top"
    /** Slice 8b: an app writes this when Meka taps Approve (the server applies the plan from then on). */
    const val APPROVED_AT = "approvedAtMs"

    fun countField(k: MailBacklogKind): String = when (k) {
        MailBacklogKind.KEEP -> KEPT
        MailBacklogKind.NEWSLETTER -> NEWSLETTERS
        MailBacklogKind.RECEIPT -> RECEIPTS
        MailBacklogKind.ARCHIVE -> ARCHIVED
        MailBacklogKind.JUNK -> JUNK
    }
}

/** One account's backlog plan as the apps read it. */
data class MailBacklog(
    val id: String,
    val account: String,
    val startedAtMs: Long,
    val scanned: Int,
    val readyAtMs: Long?,
    val counts: Map<MailBacklogKind, Int>,
    val top: String?,
    val approvedAtMs: Long?,
) {
    val ready: Boolean get() = readyAtMs != null
    /** Everything the plan would move (archived plus Trash). */
    val toMove: Int get() = counts.filterKeys { it != MailBacklogKind.KEEP }.values.sum()
}

/** Mail's backlog lines for one account. */
data class MailBacklogRow(
    val title: String,
    /** "Archive 3,412 · Trash 640 junk · keep 212 that may need you" (or the scan's progress). */
    val line: String,
    /** What goes where, and who sends most; null while scanning. */
    val detail: String?,
    /** "Nothing moves until you approve …". */
    val note: String,
    val ready: Boolean,
)

object MailBacklogRules {
    /** Tidy-kind mail is only planned once it is this old (Meka's brief: "older than 30 days"). */
    const val ARCHIVE_AFTER_DAYS = 30
    private const val DAY_MS = 86_400_000L
    /** How many senders the plan names. */
    const val TOP_SENDERS = 3

    const val TITLE = "Backlog clean-up"
    const val NOTE = "Nothing moves until you approve · Approve comes in the next update"
    const val SCANNING_NOTE = "Headers only · nothing moves"

    /** One entity per account, so every check writes the same one. */
    fun entityId(accountId: String): String = "mb" + ActivityRules.fnv64("mailbacklog:$accountId")

    /**
     * Where one older email would go: Trash for sure junk; its Autopilot tidy when it is a sure newsletter, receipt,
     * notification or promo older than [ARCHIVE_AFTER_DAYS] days; otherwise kept. Protected senders, his partner and
     * threads he wrote in are always kept.
     */
    fun plan(f: MailBacklogFacts, nowMs: Long): MailBacklogKind {
        val s = f.sorted ?: return MailBacklogKind.KEEP
        if (!f.inInbox || f.replied || MailTidyRules.protectedSender(f.fromAddress, f.keepAddresses)) return MailBacklogKind.KEEP
        if (s.sort == MailSort.JUNK && s.sure) return MailBacklogKind.JUNK
        if (nowMs - f.receivedAtMs < ARCHIVE_AFTER_DAYS * DAY_MS) return MailBacklogKind.KEEP
        return when (MailTidyRules.plan(MailTidyFacts(f.fromAddress, f.inInbox, s, f.keepAddresses, replied = f.replied))) {
            MailTidy.NEWSLETTER -> MailBacklogKind.NEWSLETTER
            MailTidy.RECEIPT -> MailBacklogKind.RECEIPT
            MailTidy.ARCHIVE -> MailBacklogKind.ARCHIVE
            MailTidy.KEEP, MailTidy.TRASH -> MailBacklogKind.KEEP
        }
    }

    /**
     * "Weekly Digest 412 · Oakfield 230 · Daily Deals 118": the [TOP_SENDERS] senders with the most planned to move
     * (by display name, else the address's domain), ties by name. [movers] one entry per planned email.
     */
    fun topSenders(movers: List<Pair<String?, String?>>): String? {
        val counts = movers.map { (name, address) -> MailUnsubscribeRules.displayName(name, address) }.groupingBy { it }.eachCount()
        if (counts.isEmpty()) return null
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP_SENDERS).joinToString(" · ") { "${it.key} ${SpeechRules.grouped(it.value.toLong())}" }
    }

    /** The server's fields: the counts so far (every kind, zeros included, so a recount can lower one), and when final. */
    fun fields(account: String, startedAtMs: Long, scanned: Int, counts: Map<MailBacklogKind, Int>, top: String?, readyAtMs: Long?): Map<String, FieldValue> =
        mapOf(
            MailBacklogFields.ACCOUNT to account.fv(),
            MailBacklogFields.STARTED_AT to startedAtMs.fv(),
            MailBacklogFields.SCANNED to scanned.toLong().fv(),
            MailBacklogFields.TOP to (top ?: "").fv(),
        ) + MailBacklogKind.entries.associate { MailBacklogFields.countField(it) to (counts[it] ?: 0).toLong().fv() } +
            (readyAtMs?.let { mapOf(MailBacklogFields.READY_AT to it.fv()) } ?: emptyMap())

    fun read(id: String, f: Map<String, FieldValue>): MailBacklog? {
        fun text(k: String) = (f[k] as? FieldValue.Text)?.value?.takeIf { it.isNotBlank() }
        fun long(k: String) = (f[k] as? FieldValue.Int64)?.value
        val started = long(MailBacklogFields.STARTED_AT) ?: return null
        return MailBacklog(
            id = id,
            account = text(MailBacklogFields.ACCOUNT) ?: "Mail",
            startedAtMs = started,
            scanned = long(MailBacklogFields.SCANNED)?.toInt() ?: 0,
            readyAtMs = long(MailBacklogFields.READY_AT)?.takeIf { it > 0 },
            counts = MailBacklogKind.entries.mapNotNull { k -> long(MailBacklogFields.countField(k))?.toInt()?.takeIf { it > 0 }?.let { k to it } }.toMap(),
            top = text(MailBacklogFields.TOP),
            approvedAtMs = long(MailBacklogFields.APPROVED_AT)?.takeIf { it > 0 },
        )
    }

    private fun n(v: Int) = SpeechRules.grouped(v.toLong())

    /** Mail's lines for one account's plan: the scan's progress, then the plan itself. */
    fun row(b: MailBacklog): MailBacklogRow {
        if (!b.ready) {
            val so = if (b.scanned > 0) " · ${n(b.scanned)} so far" else ""
            return MailBacklogRow(TITLE, "Looking through the older mail in the inbox$so…", null, SCANNING_NOTE, ready = false)
        }
        val kept = b.counts[MailBacklogKind.KEEP] ?: 0
        val keepPart = "keep ${n(kept)} that may need you"
        if (b.toMove == 0) {
            val line = if (kept == 0) "Nothing older in the inbox to clean up" else "Nothing to clean up · $keepPart"
            return MailBacklogRow(TITLE, line, null, "New mail is Autopilot's", ready = true)
        }
        val archive = listOf(MailBacklogKind.NEWSLETTER, MailBacklogKind.RECEIPT, MailBacklogKind.ARCHIVE).sumOf { b.counts[it] ?: 0 }
        val junk = b.counts[MailBacklogKind.JUNK] ?: 0
        val line = listOfNotNull(
            archive.takeIf { it > 0 }?.let { "Archive ${n(it)}" },
            junk.takeIf { it > 0 }?.let { "Trash ${n(it)} junk" },
            keepPart.takeIf { kept > 0 },
        ).joinToString(" · ")
        val each = listOf(MailBacklogKind.NEWSLETTER, MailBacklogKind.RECEIPT, MailBacklogKind.ARCHIVE).mapNotNull { k ->
            b.counts[k]?.takeIf { it > 0 }?.let { c -> "${n(c)} ${if (c == 1) k.one else k.many} ${k.tidy.destination}" }
        }
        val detail = listOfNotNull(
            each.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let { "$it · only mail older than $ARCHIVE_AFTER_DAYS days" },
            junk.takeIf { it > 0 }?.let { "Junk → Trash, recoverable for 30 days" },
            b.top?.let { "Most from $it" },
        ).joinToString("\n").takeIf { it.isNotEmpty() }
        return MailBacklogRow(TITLE, line, detail, NOTE, ready = true)
    }

    /** The account's plan (by its label as Mail shows it), or null before the server started one. */
    fun rowFor(backlogs: List<MailBacklog>, account: String): MailBacklogRow? =
        backlogs.filter { it.account == account }.maxByOrNull { it.startedAtMs }?.let { row(it) }
}
