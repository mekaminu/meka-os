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
 * both apps show it in Mail. Slice 8b: nothing moves until Meka taps **Approve** (an app writes [MailBacklogFields.APPROVED_AT]);
 * then the server applies the plan a few hundred emails a check as **one** `mail_tidy` batch ([tidyBatchId], flagged
 * [MailTidyBatchFields.BACKLOG]) whose Undo puts everything back for 30 days.
 *
 * The plan reuses Autopilot's never-touch rules ([MailTidyRules.plan]: only sure newsletters, receipts, notifications
 * and promos still in the inbox; never people, public bodies and schools, banks, his partner, or a thread he wrote in)
 * and adds two of its own from Meka's brief: tidy-kind mail is only planned once it is older than [ARCHIVE_AFTER_DAYS]
 * days (7 since Inbox fixes, slice 2: "Archive 3,412 newsletters/promos older than a week"), and mail the rules call junk (Gmail's spam label) goes to
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
    /** Slice 8b: an app writes this when Meka taps Approve (the server applies the plan from then on); 0 = not approved. */
    const val APPROVED_AT = "approvedAtMs"
    /** Slice 8b (server): emails moved so far, when the first moved, when the last did, and how many an Undo put back. */
    const val APPLIED = "applied"
    const val APPLY_STARTED_AT = "applyStartedAtMs"
    const val FINISHED_AT = "finishedAtMs"
    const val PUT_BACK = "putBack"
    /**
     * Inbox fixes, slice 2: which planning rules made the plan ([MailBacklogRules.PLAN_VERSION]; absent = 1, the
     * 30-day rules). A plan made by older rules, not yet approved, is made again from scratch by the server.
     */
    const val PLAN_VERSION = "planVersion"

    /**
     * Inbox fixes, slice 3 (the Junk clean-out, [MailJunkClearRules]): emails in the account's Junk folder when last
     * counted, and when; Meka's Approve on "Empty Junk" (0 = not approved, an app writes it); how many the server has
     * moved since, and when it finished. Counts only, never an email id, subject or address.
     */
    const val JUNK_FOLDER = "junkFolder"
    const val JUNK_COUNTED_AT = "junkCountedAtMs"
    const val JUNK_APPROVED_AT = "junkApprovedAtMs"
    const val JUNK_CLEARED = "junkCleared"
    const val JUNK_FINISHED_AT = "junkFinishedAtMs"

    /**
     * Inbox fixes, slice 9 (the Empty Junk card's exact counts, [MailJunkClearRules.lookFields]): of the Junk folder's
     * emails older than two days that the server looked at when it counted, how many stay for him to check (banks,
     * schools, public bodies), how many go back to the inbox (people he knows), how many it looked at, and whether that
     * was all of them. Counts only.
     */
    const val JUNK_KEEP = "junkKeep"
    const val JUNK_RESCUE = "junkRescue"
    const val JUNK_LOOKED = "junkLooked"
    const val JUNK_LOOK_FULL = "junkLookFull"

    /**
     * Review opens its own list, slice 1 ([MailJunkReviewRules]): who the looked-at Junk is from, grouped by sender (the
     * server writes it with the count; a key per sender, never an address), and the senders Meka took the tick off so
     * their mail stays in Junk (comma-separated keys; the apps write it, the server never trashes them).
     */
    const val JUNK_SENDERS = "junkSenders"
    const val JUNK_KEEP_SENDERS = "junkKeepSenders"

    /**
     * Inbox fixes, slice 7 (Today's unread line, [MailUnreadRules]): the inbox's unread count as the provider gives it,
     * how many of those unread ones Navilife's rules would tidy (newsletters, promos, notifications, receipts), and when
     * they were counted. Counts only.
     */
    const val INBOX_UNREAD = "inboxUnread"
    const val TIDY_UNREAD = "tidyUnread"
    const val UNREAD_COUNTED_AT = "unreadCountedAtMs"

    /**
     * Inbox fixes, slice 8 ([MailMarkReadRules]): Meka's tap on "Mark N read" (an app writes it; 0 = withdrawn), how many
     * unread newsletters, promos and receipts the server has marked read since, and when it found none left. Counts only.
     */
    const val MARK_READ_APPROVED_AT = "markReadApprovedAtMs"
    const val MARKED_READ = "markedRead"
    const val MARK_READ_FINISHED_AT = "markReadFinishedAtMs"

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
    /** Slice 8b: emails the server has moved so far, from when, and when it finished. */
    val applied: Int = 0,
    val applyStartedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    /** Slice 8b: how many an Undo has put back so far. */
    val putBack: Int = 0,
    /** Inbox fixes, slice 2: the planning rules' version (1 before it was written). */
    val planVersion: Int = 1,
    /** Inbox fixes, slice 3: the Junk folder's clean-out ([MailJunkClearRules]). */
    val junkFolder: Int = 0,
    val junkCountedAtMs: Long? = null,
    val junkApprovedAtMs: Long? = null,
    val junkCleared: Int = 0,
    val junkFinishedAtMs: Long? = null,
    /** Inbox fixes, slice 9: what the count's look found (null [junkLooked]: not looked yet). */
    val junkKeep: Int = 0,
    val junkRescue: Int = 0,
    val junkLooked: Int? = null,
    val junkLookFull: Boolean = false,
    /** Review opens its own list, slice 1: the looked-at Junk by sender (encoded, [MailJunkReviewRules.decode]) and his kept senders. */
    val junkSenders: String? = null,
    val junkKeepSenders: Set<String> = emptySet(),
    /** Inbox fixes, slice 7: the inbox's unread count, the unread ones Navilife can tidy, and when ([MailUnreadRules]). */
    val inboxUnread: Int? = null,
    val tidyUnread: Int = 0,
    val unreadCountedAtMs: Long? = null,
    /** Inbox fixes, slice 8: "Mark N read" ([MailMarkReadRules]). */
    val markReadApprovedAtMs: Long? = null,
    val markedRead: Int = 0,
    val markReadFinishedAtMs: Long? = null,
) {
    val ready: Boolean get() = readyAtMs != null
    val approved: Boolean get() = approvedAtMs != null
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
    /** Slice 8b: the plan is final and nothing has moved: Mail shows **Approve** (the entity's id). */
    val approveId: String? = null,
    /** Slice 8b: the clean-up's tidy can still be undone: Mail shows **Undo** (the `mail_tidy` batch's id). */
    val undoId: String? = null,
    /** Slice 8b: approved and still moving, or putting back: the line counts up. */
    val working: Boolean = false,
    /** Slice 8b: approved but nothing moved yet: Mail shows **Cancel** (the entity's id). */
    val cancelId: String? = null,
)

object MailBacklogRules {
    /**
     * Tidy-kind mail is only planned once it is this old. Meka's brief said 30 days; Inbox fixes (2026-10-15 20:59,
     * "hundreds of unread messages, nothing is archived") lowered it to 7 for newsletters, promos and notifications.
     */
    const val ARCHIVE_AFTER_DAYS = 7

    /**
     * Inbox fixes, slice 2: the planning rules' version. 2 = the 7-day age and slice 1's better sort (bulk headers,
     * Outlook's list headers read per sender); 3 = account-security mail is never moved ([MailSecurityRules]). A plan
     * written under an older version that nothing has moved under is started again by the server ([needsReplan]).
     */
    const val PLAN_VERSION = 3
    private const val DAY_MS = 86_400_000L
    /** How many senders the plan names. */
    const val TOP_SENDERS = 3

    const val TITLE = "Backlog clean-up"
    const val NOTE = "Nothing moves until you approve · Undo puts it all back for 30 days"
    const val APPROVE = "Approve"
    const val UNDO = "Undo"
    const val CANCEL = "Cancel"
    const val APPROVED_LINE = "Approved · cleaning up from Navilife's next check…"
    const val APPROVED_NOTE = "A few hundred emails a check · Undo puts it all back for 30 days"
    const val PAUSED_NOTE = "Paused with everything Navilife does on its own · Resume in What Navilife may do"
    const val PUTTING_BACK = "Putting everything back in the inbox, a few hundred a check…"
    const val NOT_APPROVED = "Nothing to approve yet"
    const val APPROVED_BAR = "Approved · the clean-up starts at Navilife's next check"
    const val UNAPPROVED_BAR = "Cancelled · nothing moved"
    const val ALREADY_STARTED = "Already started · Undo under Backlog clean-up puts it all back"
    /** Slice 8b: emails the server moves per check (Gmail labels them in batches; Outlook moves one at a time). */
    const val APPLY_PER_CHECK_GMAIL = 500
    const val APPLY_PER_CHECK_OUTLOOK = 100
    /** Slice 8b: emails an Undo puts back per check (one call each). */
    const val UNDO_PER_CHECK = 300
    const val SCANNING_NOTE = "Headers only · nothing moves"

    /** One entity per account, so every check writes the same one. */
    fun entityId(accountId: String): String = "mb" + ActivityRules.fnv64("mailbacklog:$accountId")

    /** Slice 8b: the one `mail_tidy` batch the account's clean-up is applied as (so its Undo is one tap). */
    fun tidyBatchId(accountId: String): String = "mt" + ActivityRules.fnv64("mailbacklogtidy:$accountId")

    /** What an app writes on Approve (Meka's tap), and on the bar's Undo before anything moved. */
    fun approveFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailBacklogFields.APPROVED_AT to nowMs.fv())
    fun unapproveFields(): Map<String, FieldValue> = mapOf(MailBacklogFields.APPROVED_AT to 0L.fv())

    /**
     * Inbox fixes, slice 2: the server starts the plan again (forgetting what it read) when it was made by older rules
     * and Meka hasn't approved it and nothing has moved: what he'd approve is then what today's rules would do.
     */
    fun needsReplan(b: MailBacklog?): Boolean = b != null && b.planVersion < PLAN_VERSION && !b.approved && b.applied == 0

    /** Approve is offered once the plan is final, has something to move, and nothing has moved yet. */
    fun canApprove(b: MailBacklog): Boolean = b.ready && b.toMove > 0 && !b.approved && b.applied == 0

    /** The bar's Undo after Approve only stands while the server hasn't started. */
    fun canUnapprove(b: MailBacklog): Boolean = b.approved && b.applied == 0 && b.applyStartedAtMs == null

    /**
     * Slice 8b: whether the server moves more of the plan this check: approved after the plan was final, not finished,
     * MEKA not paused (the kill switch), and no Undo asked on its tidy.
     */
    fun shouldApply(b: MailBacklog, paused: Boolean, batch: MailTidyBatch?): Boolean {
        val approved = b.approvedAtMs ?: return false
        val ready = b.readyAtMs ?: return false
        if (approved < ready || paused || b.finishedAtMs != null) return false
        return batch == null || (batch.undoAskedAtMs == null && batch.undoneAtMs == null)
    }

    /** The server's progress fields (slice 8b). */
    fun progressFields(applied: Int, startedAtMs: Long?, finishedAtMs: Long?, putBack: Int): Map<String, FieldValue> =
        mapOf(MailBacklogFields.APPLIED to applied.toLong().fv(), MailBacklogFields.PUT_BACK to putBack.toLong().fv()) +
            (startedAtMs?.let { mapOf(MailBacklogFields.APPLY_STARTED_AT to it.fv()) } ?: emptyMap()) +
            (finishedAtMs?.let { mapOf(MailBacklogFields.FINISHED_AT to it.fv()) } ?: emptyMap())

    /**
     * Slice 8b: the clean-up's tidy as Mail and Activity show it: "Backlog clean-up · 3,412 older emails · Personal" over
     * what went where; flagged [MailTidyBatchFields.BACKLOG] so "Archived for you" leaves it to Mail. [counts] cumulative.
     */
    fun batchFields(atMs: Long, account: String, counts: Map<MailTidy, Int>): Map<String, FieldValue> {
        val total = counts.filterKeys { it != MailTidy.KEEP }.values.sum()
        val detail = MailTidy.entries.filter { it != MailTidy.KEEP }.mapNotNull { t ->
            counts[t]?.takeIf { it > 0 }?.let { c -> if (t == MailTidy.TRASH) "${n(c)} junk → Trash" else "${n(c)} ${if (c == 1) t.one else t.many} ${t.destination}" }
        }.joinToString(" · ")
        return MailAutopilotRules.batchFields(atMs, account, counts) + mapOf(
            MailTidyBatchFields.SUMMARY to "$TITLE · ${n(total)} older ${if (total == 1) "email" else "emails"} · $account".fv(),
            MailTidyBatchFields.DETAIL to detail.fv(),
            MailTidyBatchFields.BACKLOG to true.fv(),
        )
    }

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
            MailTidy.KEEP, MailTidy.TRASH, MailTidy.JUNK_TRASH, MailTidy.RESCUE -> MailBacklogKind.KEEP
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
            MailBacklogFields.PLAN_VERSION to PLAN_VERSION.toLong().fv(),
            // Inbox fixes, slice 2: 0 while scanning, so a plan made again isn't shown as final.
            MailBacklogFields.READY_AT to (readyAtMs ?: 0L).fv(),
        ) + MailBacklogKind.entries.associate { MailBacklogFields.countField(it) to (counts[it] ?: 0).toLong().fv() }

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
            applied = long(MailBacklogFields.APPLIED)?.toInt() ?: 0,
            applyStartedAtMs = long(MailBacklogFields.APPLY_STARTED_AT)?.takeIf { it > 0 },
            finishedAtMs = long(MailBacklogFields.FINISHED_AT)?.takeIf { it > 0 },
            putBack = long(MailBacklogFields.PUT_BACK)?.toInt() ?: 0,
            planVersion = long(MailBacklogFields.PLAN_VERSION)?.toInt() ?: 1,
            junkFolder = long(MailBacklogFields.JUNK_FOLDER)?.toInt() ?: 0,
            junkCountedAtMs = long(MailBacklogFields.JUNK_COUNTED_AT)?.takeIf { it > 0 },
            junkApprovedAtMs = long(MailBacklogFields.JUNK_APPROVED_AT)?.takeIf { it > 0 },
            junkCleared = long(MailBacklogFields.JUNK_CLEARED)?.toInt() ?: 0,
            junkFinishedAtMs = long(MailBacklogFields.JUNK_FINISHED_AT)?.takeIf { it > 0 },
            junkKeep = long(MailBacklogFields.JUNK_KEEP)?.toInt()?.coerceAtLeast(0) ?: 0,
            junkRescue = long(MailBacklogFields.JUNK_RESCUE)?.toInt()?.coerceAtLeast(0) ?: 0,
            junkLooked = long(MailBacklogFields.JUNK_LOOKED)?.toInt()?.takeIf { it >= 0 },
            junkLookFull = (f[MailBacklogFields.JUNK_LOOK_FULL] as? FieldValue.Bool)?.value == true,
            junkSenders = text(MailBacklogFields.JUNK_SENDERS),
            junkKeepSenders = MailJunkReviewRules.decodeKept(text(MailBacklogFields.JUNK_KEEP_SENDERS)),
            inboxUnread = long(MailBacklogFields.INBOX_UNREAD)?.toInt()?.takeIf { it >= 0 },
            tidyUnread = long(MailBacklogFields.TIDY_UNREAD)?.toInt()?.coerceAtLeast(0) ?: 0,
            unreadCountedAtMs = long(MailBacklogFields.UNREAD_COUNTED_AT)?.takeIf { it > 0 },
            markReadApprovedAtMs = long(MailBacklogFields.MARK_READ_APPROVED_AT)?.takeIf { it > 0 },
            markedRead = long(MailBacklogFields.MARKED_READ)?.toInt()?.coerceAtLeast(0) ?: 0,
            markReadFinishedAtMs = long(MailBacklogFields.MARK_READ_FINISHED_AT)?.takeIf { it > 0 },
        )
    }

    private fun n(v: Int) = SpeechRules.grouped(v.toLong())

    /**
     * Mail's lines for one account's plan: the scan's progress, the plan with Approve, then (slice 8b) the clean-up
     * moving, done with Undo, and put back. [batch] the clean-up's tidy once the server started it.
     */
    fun row(b: MailBacklog, batch: MailTidyBatch? = null, paused: Boolean = false, nowMs: Long = 0L): MailBacklogRow {
        val r = planRow(b, batch, paused, nowMs)
        // Inbox fixes, slice 8: what "Mark N read" did (or is doing) sits under the plan.
        val marked = MailMarkReadRules.line(b) ?: return r
        return r.copy(detail = listOfNotNull(r.detail, marked).joinToString("\n"))
    }

    private fun planRow(b: MailBacklog, batch: MailTidyBatch?, paused: Boolean, nowMs: Long): MailBacklogRow {
        if (b.approved || b.applied > 0) applying(b, batch, paused, nowMs)?.let { return it }
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
            each.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let { "$it · only mail older than a week" },
            junk.takeIf { it > 0 }?.let { "Junk → Trash, recoverable for 30 days" },
            b.top?.let { "Most from $it" },
        ).joinToString("\n").takeIf { it.isNotEmpty() }
        return MailBacklogRow(TITLE, line, detail, NOTE, ready = true, approveId = b.id.takeIf { canApprove(b) })
    }

    /** "Archived 3,412 · 640 junk to Trash", from the plan's counts (what moved). */
    private fun movedParts(b: MailBacklog): String {
        val archive = listOf(MailBacklogKind.NEWSLETTER, MailBacklogKind.RECEIPT, MailBacklogKind.ARCHIVE).sumOf { b.counts[it] ?: 0 }
        val junk = b.counts[MailBacklogKind.JUNK] ?: 0
        return listOfNotNull(archive.takeIf { it > 0 }?.let { "archived ${n(it)}" }, junk.takeIf { it > 0 }?.let { "${n(it)} junk to Trash" })
            .joinToString(" · ")
    }

    /** Slice 8b: the lines once Meka approved (null: show the plan, e.g. an approval written before the plan was final). */
    private fun applying(b: MailBacklog, batch: MailTidyBatch?, paused: Boolean, nowMs: Long): MailBacklogRow? {
        val total = b.toMove
        val undoable = batch != null && batch.canUndo && (nowMs == 0L || nowMs - batch.atMs <= MailAutopilotRules.UNDO_DAYS * 86_400_000L)
        val undoId = batch?.id?.takeIf { undoable }
        return when {
            batch?.undoneAtMs != null ->
                MailBacklogRow(TITLE, batch.undoNote ?: "Put back in the inbox", null, "New mail is Autopilot's", ready = true)
            batch?.undoAskedAtMs != null -> {
                val so = if (b.putBack > 0) " · ${n(b.putBack)} of ${n(b.applied)} so far" else ""
                MailBacklogRow(TITLE, "$PUTTING_BACK$so", null, "Headers only · nothing is deleted", ready = true, working = true)
            }
            b.finishedAtMs != null -> {
                val moved = movedParts(b).replaceFirstChar { it.uppercase() }
                MailBacklogRow(TITLE, "Cleaned up · ${n(b.applied)} older ${if (b.applied == 1) "email" else "emails"} moved", moved.takeIf { it.isNotEmpty() },
                    if (undoId != null) "Undo puts them all back in the inbox · for 30 days" else "New mail is Autopilot's", ready = true, undoId = undoId)
            }
            b.readyAtMs == null || (b.approvedAtMs ?: 0) < b.readyAtMs -> null
            b.applied == 0 && batch == null ->
                MailBacklogRow(TITLE, APPROVED_LINE, null, if (paused) PAUSED_NOTE else APPROVED_NOTE, ready = true, working = !paused,
                    cancelId = b.id.takeIf { canUnapprove(b) })
            else -> MailBacklogRow(TITLE, "Cleaning up · ${n(b.applied)} of ${n(total)} moved…", null,
                if (paused) PAUSED_NOTE else APPROVED_NOTE, ready = true, undoId = undoId, working = !paused)
        }
    }

    /** The account's plan (by its label as Mail shows it), or null before the server started one. */
    fun rowFor(backlogs: List<MailBacklog>, account: String, batches: List<MailTidyBatch> = emptyList(), paused: Boolean = false, nowMs: Long = 0L): MailBacklogRow? =
        backlogs.filter { it.account == account }.maxByOrNull { it.startedAtMs }?.let { b ->
            row(b, batches.firstOrNull { it.backlog && it.account == account }, paused, nowMs)
        }

    /** Inbox fixes, slice 2: the Needs you card's words. */
    const val CARD_YES = "Approve"
    const val CARD_LATER = "Later"
    const val CARD_OPEN = "Review"

    /**
     * Inbox fixes, slice 2 (Meka hadn't found Approve in Mail): one Needs you card per account whose plan is final and
     * waiting for his Approve: "Clean up Personal" · "Archive 2,140 newsletters and promos · Trash 310 junk · keep 212
     * that may need you · nothing moves until you approve". → Approve (the same synced approval as Mail's, Undo while
     * nothing has moved), ← Later (set aside on this screen), ↑ Review (Mail, where the plan lists what goes where).
     * Newest plan per account; accounts in the order given (Mail's order). Not while Navilife is paused.
     */
    fun cards(backlogs: List<MailBacklog>, paused: Boolean = false, nowMs: Long = 0L): List<DecisionCard> {
        if (paused) return emptyList()
        val newest = backlogs.groupBy { it.account }.values.mapNotNull { list -> list.maxByOrNull { it.startedAtMs } }
        // Inbox fixes, slice 3: each account's "Empty Junk" follows its clean-up card.
        // Inbox fixes, slice 8: then "Mark N read" for the unread newsletters and promos still in the inbox.
        return newest.flatMap { b -> listOfNotNull(planCard(b), MailMarkReadRules.card(b, nowMs), MailJunkClearRules.card(b)) }
    }

    private fun planCard(b: MailBacklog): DecisionCard? {
        if (!canApprove(b)) return null
        return run {
                val archive = listOf(MailBacklogKind.NEWSLETTER, MailBacklogKind.RECEIPT, MailBacklogKind.ARCHIVE).sumOf { b.counts[it] ?: 0 }
                val junk = b.counts[MailBacklogKind.JUNK] ?: 0
                val kept = b.counts[MailBacklogKind.KEEP] ?: 0
                val why = listOfNotNull(
                    archive.takeIf { it > 0 }?.let { "Archive ${n(it)} ${if (it == 1) "newsletter or promo" else "newsletters and promos"} older than a week" },
                    junk.takeIf { it > 0 }?.let { "Trash ${n(it)} junk" },
                    kept.takeIf { it > 0 }?.let { "keep ${n(it)} that may need you" },
                    "nothing moves until you approve",
                ).joinToString(" · ")
                DecisionCard(
                    id = b.id, kind = DecisionKind.MAIL_CLEANUP, title = "Clean up ${b.account}", why = why, taskId = null,
                    yesLabel = CARD_YES, laterLabel = CARD_LATER, openLabel = CARD_OPEN,
                    yes = DecisionEffect.APPROVE_BACKLOG, later = DecisionEffect.SET_ASIDE, open = DecisionEffect.OPEN_MAIL,
                    urgent = false,
                )
            }
    }
}
