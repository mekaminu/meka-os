package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Inbox autopilot, slice 3a: what MEKA would do with each sorted email (Meka, 2026-10-12: newsletters and receipts
 * auto-archived but shown to him, notifications and promos archived). Non-AI, pure, unit-tested. The server runs it
 * over the headers it keeps; slice 3a only counts the result (a dry run, "would tidy 52"), nothing in the mailbox moves.
 * Slice 3b applies it (archive + label, each with Undo and an off switch).
 *
 * The never-touch rules: only an email still in the inbox, sorted **sure**, and in a tidy bucket is ever planned;
 * Needs you, FYI and Junk (its own daily review later) stay; mail from public bodies and schools, banks and building
 * societies, or an address Meka keeps ([MailTidyFacts.keepAddresses]: his partner, later his contacts) stays whatever
 * it was sorted as.
 */
enum class MailTidy(val one: String, val many: String, val label: String?) {
    /** Left exactly where it is. */
    KEEP("kept", "kept", null),
    /** Archived with the label MEKA/Newsletters. */
    NEWSLETTER("newsletter", "newsletters", "Navilife/Newsletters"),
    /** Archived with the label MEKA/Receipts. */
    RECEIPT("receipt or order", "receipts and orders", "Navilife/Receipts"),
    /** Archived (notifications and promos), no label. */
    ARCHIVE("notification or promo", "notifications and promos", null),
    /**
     * Slice 5b: a newsletter or promo from a sender Meka unsubscribed from, arriving after the unsubscribe: moved to
     * Trash (recoverable for 30 days, never deleted for good), with Undo like any tidy.
     */
    TRASH("from an unsubscribed sender", "from unsubscribed senders", null),
    /**
     * Slice 3c, the daily Junk review: an obvious scam or spam in the Junk folder moved to Trash (recoverable for 30 days,
     * never deleted for good); Undo puts it back in Junk.
     */
    JUNK_TRASH("junk email", "junk emails", null),
    /** Slice 3c: an email that looks real rescued from Junk to the inbox and flagged (Gmail: starred, labelled); Undo returns it to Junk. */
    RESCUE("email rescued from Junk", "emails rescued from Junk", "Navilife/Rescued");

    /**
     * Inbox fixes, slice 1 (Meka, 2026-10-15: "I thought it would mark emails as read"): a tidy that takes mail away from
     * him also marks it read, so his unread counts fall (unless he turned [MailAutopilotSetting.markRead] off); Undo
     * marks it unread again. A rescued email is his to read: never.
     */
    val marksRead: Boolean get() = this != KEEP && this != RESCUE

    /** How a batch line names where it went: "→ Navilife/Newsletters", "archived", "→ Trash", "→ inbox, flagged". */
    val destination: String get() = when {
        this == RESCUE -> "→ inbox, flagged"
        label != null -> "→ $label"
        this == TRASH || this == JUNK_TRASH -> "→ Trash"
        else -> "archived"
    }

    companion object {
        fun of(stored: String?): MailTidy? = entries.firstOrNull { it.name == stored }
    }
}

/** What the planner sees of one email. */
data class MailTidyFacts(
    val fromAddress: String?,
    val inInbox: Boolean,
    /** Its sort, or null before it's sorted. */
    val sorted: MailSorted?,
    /** Addresses never tidied (lower case): his partner's, later his contacts'. */
    val keepAddresses: Set<String> = emptySet(),
    /** Slice 3b: the email is in a thread Meka has written in (he replied, or started it): never tidied. */
    val replied: Boolean = false,
    /**
     * Slice 5b: the sender is one Meka unsubscribed from (the unsubscribe went through) and this email came after it
     * ([MailUnsubscribeRules.trashes]).
     */
    val unsubscribed: Boolean = false,
    /** Meka's own rule for the sender ([MailSenderRule]): never tidy, or always tidy; null for none. */
    val rule: MailSenderRule? = null,
)

object MailTidyRules {
    /** Banks and building societies (generic UK list): their mail is never tidied away, whatever it looks like. */
    val PROTECTED_DOMAINS = listOf(
        "barclays.co.uk", "barclays.com", "hsbc.co.uk", "hsbc.com", "lloydsbank.co.uk", "lloydsbank.com", "halifax.co.uk",
        "bankofscotland.co.uk", "natwest.com", "rbs.co.uk", "santander.co.uk", "nationwide.co.uk", "tsb.co.uk",
        "firstdirect.com", "monzo.com", "starlingbank.com", "revolut.com", "metrobankonline.co.uk", "co-operativebank.co.uk",
        "virginmoney.com", "americanexpress.com", "aexp.com", "chase.co.uk", "coventrybuildingsociety.co.uk",
        "yorkshirebuildingsociety.co.uk", "skipton.co.uk",
    )

    private fun endsWithDomain(domain: String, suffix: String) = domain == suffix || domain.endsWith(".$suffix")

    /** True for a sender MEKA never tidies away: public bodies and schools, banks, an address Meka keeps. */
    fun protectedSender(fromAddress: String?, keepAddresses: Set<String>): Boolean {
        val address = fromAddress?.trim()?.trimStart('<')?.trimEnd('>')?.lowercase()
        if (address != null && address in keepAddresses) return true
        val domain = MailSortRules.domain(fromAddress) ?: return false
        return (MailSortRules.PUBLIC_SUFFIXES + PROTECTED_DOMAINS).any { endsWithDomain(domain, it) }
    }

    fun plan(f: MailTidyFacts): MailTidy {
        val s = f.sorted ?: return MailTidy.KEEP
        if (!f.inInbox || f.replied || protectedSender(f.fromAddress, f.keepAddresses)) return MailTidy.KEEP
        // Account-security mail is never tidied, not even by his "always tidy" on the sender.
        if (MailSecurityRules.kept(s.reason)) return MailTidy.KEEP
        // His "never tidy" wins over everything below, the unsubscribed sender's Trash included.
        if (f.rule == MailSenderRule.KEEP) return MailTidy.KEEP
        // Slice 5b: only what still reads as a newsletter or promo goes to Trash (a receipt or a person's email from the
        // same address is planned as usual), sure or not: he already said he doesn't want them.
        if (f.unsubscribed && (s.sort == MailSort.NEWSLETTER || s.sort == MailSort.PROMO)) return MailTidy.TRASH
        // His "always tidy", sure or not.
        MailSenderRuleRules.plan(f.rule, s.sort)?.let { return it }
        if (!s.sure) return MailTidy.KEEP
        return when (s.sort) {
            MailSort.NEWSLETTER -> MailTidy.NEWSLETTER
            MailSort.RECEIPT -> MailTidy.RECEIPT
            MailSort.NOTIFICATION, MailSort.PROMO -> MailTidy.ARCHIVE
            MailSort.NEEDS_YOU, MailSort.FYI, MailSort.JUNK -> MailTidy.KEEP
        }
    }

    /**
     * The account's dry-run line in Mail: "Autopilot, dry run · would tidy 52 · 30 newsletters → MEKA/Newsletters ·
     * 12 receipts and orders → MEKA/Receipts · 10 notifications and promos archived · nothing moved yet"; null before
     * the server has planned anything (an older server, or nothing sorted yet). [counts] by [MailTidy] name; KEEP ignored.
     */
    fun dryRunLine(counts: Map<String, Int>?): String? {
        if (counts == null) return null
        val parts = MailTidy.entries.filter { it != MailTidy.KEEP }.mapNotNull { t ->
            counts[t.name]?.takeIf { it > 0 }?.let { n -> n to t }
        }
        val total = parts.sumOf { it.first }
        if (total == 0) return NOTHING_TO_TIDY
        val each = parts.joinToString(" · ") { (n, t) -> "$n ${if (n == 1) t.one else t.many} ${t.destination}" }
        return "Autopilot, dry run · would tidy $total · $each · nothing moved yet"
    }

    const val NOTHING_TO_TIDY = "Autopilot, dry run · nothing to tidy · nothing moved yet"
}

/**
 * Inbox autopilot, slice 3b: Autopilot's switch, one `context_mode` entity (id [MailAutopilotRules.ENTITY_ID], ADR-008
 * addendum 2026-10-13): `on` (Bool) and `changedAtMs` (Int64), LWW, written by either app. Absent = off: slice 3b-1
 * builds the server's half and nothing moves until an app turns it on (3b-2 adds the switch, Activity and Undo).
 */
object MailAutopilotFields {
    const val ON = "on"
    const val CHANGED_AT = "changedAtMs"
    /** Inbox fixes, slice 1: "Mark tidied mail as read" (Bool); absent = on. */
    const val MARK_READ = "markRead"
}

data class MailAutopilotSetting(val on: Boolean, val changedAtMs: Long?, val markRead: Boolean = true)

/**
 * One tidy the server did in one account (slice 3b): a `mail_tidy` entity (ADR-008 addendum 2026-10-13), id
 * [MailAutopilotRules.batchId]. The server writes what it did ([AT], [ACCOUNT], [SUMMARY], [DETAIL], [COUNT]) and, once
 * put back, [UNDONE_AT] and [UNDO_NOTE]; an app asks for Undo by writing [UNDO_ASKED_AT], which the server acts on at its
 * next check (every email back in the inbox, the label taken off, and never tidied again). LWW throughout. Which
 * emails were moved stays on the server (their ids only), never in the synced data.
 */
object MailTidyBatchFields {
    const val AT = "atMs"
    /** The account's label as Mail shows it ("Personal", "Business", "Outlook"), never the address. */
    const val ACCOUNT = "account"
    /**
     * Inbox fixes, slice 5: which account the batch belongs to ([MailAccountRules.key]: a hash of the provider and the
     * address, never the address), so Mail shows each account only its own tidies even when two accounts carry the
     * same label. Absent on batches written before slice 5 (those match by [ACCOUNT]).
     */
    const val ACCOUNT_KEY = "accountKey"
    const val SUMMARY = "summary"
    const val DETAIL = "detail"
    const val COUNT = "count"
    const val UNDO_ASKED_AT = "undoAskedAtMs"
    const val UNDONE_AT = "undoneAtMs"
    const val UNDO_NOTE = "undoNote"
    /** Slice 4: how many of each kind the batch tidied (Int64), for "Archived for you"; absent on older batches. */
    const val NEWSLETTERS = "newsletters"
    const val RECEIPTS = "receipts"
    const val ARCHIVED = "archived"
    /** Slice 5b: later mail from unsubscribed senders moved to Trash. */
    const val TRASHED = "trashed"
    /** Slice 8b: the backlog clean-up's tidy (Bool): Mail shows it under Backlog clean-up, "Archived for you" leaves it out. */
    const val BACKLOG = "backlog"
    /** Slice 3c: the daily Junk review's counts (Int64) and its flag (Bool). */
    const val JUNK_TRASHED = "junkTrashed"
    const val RESCUED = "rescued"
    const val JUNK = "junk"
    /**
     * Slice 3d: what the batch's receipts cost (Int64, pence, the priced ones only), how many were priced (Int64) and
     * who from (Text, names one per line, most first); absent when none said.
     */
    const val RECEIPTS_PENCE = "receiptsPence"
    const val RECEIPTS_PRICED = "receiptsPriced"
    const val MERCHANTS = "merchants"
    /**
     * "Archived for you" shows which emails, slice 1 (2026-10-16): the emails the batch moved, one per line
     * ([MailTidiedRules.encode]: sender's name and domain, subject, when, what was done, a receipt's price; never the
     * address or the body), Text; absent on older batches and on the backlog and Empty Junk clean-ups.
     */
    const val EMAILS = "emails"
    /**
     * Slice 2 (2026-10-16): what he asked for on single emails from the preview (Put back, Always keep from this
     * sender, Unsubscribe), one per line ([MailTidiedAskRules.encodeAsks]), Text, an app's; and the server's answers
     * ([EMAIL_DONE], one per line with the line the apps show), Text, the server's. Absent until he asks.
     */
    const val EMAIL_ASKS = "emailAsks"
    const val EMAIL_DONE = "emailDone"

    /** The count field of each tidy kind. */
    fun kindField(t: MailTidy): String? = when (t) {
        MailTidy.NEWSLETTER -> NEWSLETTERS
        MailTidy.RECEIPT -> RECEIPTS
        MailTidy.ARCHIVE -> ARCHIVED
        MailTidy.TRASH -> TRASHED
        MailTidy.JUNK_TRASH -> JUNK_TRASHED
        MailTidy.RESCUE -> RESCUED
        MailTidy.KEEP -> null
    }
}

/** A tidy batch as the apps read it. */
data class MailTidyBatch(
    val id: String,
    val atMs: Long,
    val account: String,
    val summary: String,
    val detail: String?,
    val count: Int,
    val undoAskedAtMs: Long?,
    val undoneAtMs: Long?,
    val undoNote: String?,
    /** Slice 4: how many of each kind (empty on a batch written before slice 4). */
    val kinds: Map<MailTidy, Int> = emptyMap(),
    /** Slice 8b: the backlog clean-up's one tidy. */
    val backlog: Boolean = false,
    /** Slice 3c: a daily Junk review. */
    val junk: Boolean = false,
    /** Slice 3d: the priced receipts' total in pence and how many were priced (null/0 when none said). */
    val receiptsPence: Long? = null,
    val receiptsPriced: Int = 0,
    /** Slice 3d: who the receipts were from, most first. */
    val merchants: List<String> = emptyList(),
    /** Inbox fixes, slice 5: [MailTidyBatchFields.ACCOUNT_KEY], or null on an older batch. */
    val accountKey: String? = null,
    /** Slice 1 of "Archived for you shows which emails": what it moved, newest first (empty on an older batch). */
    val emails: List<MailTidiedEmail> = emptyList(),
    /** Slice 2: his asks on single emails, and the server's answers. */
    val emailAsks: List<MailTidiedAsk> = emptyList(),
    val emailDone: List<MailTidiedDone> = emptyList(),
) {
    val canUndo: Boolean get() = undoneAtMs == null && undoAskedAtMs == null
}

/** Pure rules, unit-tested; the server runs them each check, the apps read the same fields. */
object MailAutopilotRules {
    const val ENTITY_ID = "mail_autopilot"
    /** The first day after an account connects is a dry run: nothing moves (Meka's completeness note, 2026-10-12). */
    const val DRY_RUN_MS = 24 * 3_600_000L
    /** At most this many emails tidied per account per check, so a mistake can never sweep the whole inbox. */
    const val MAX_PER_CHECK = 100
    /** Undo is offered for 30 days (the Activity log's window). */
    const val UNDO_DAYS = 30

    /**
     * Slice 3b-2: absent = **on** (Meka's decision, 2026-10-12: newsletters and receipts auto-archived but shown to him;
     * with Undo in Mail and Activity and the switch in Mail and What Navilife may do, it is on unless he turns it off).
     * Each account's first day is still a dry run ([since]).
     */
    const val DEFAULT_ON = true

    fun read(fields: Map<String, FieldValue>?): MailAutopilotSetting = MailAutopilotSetting(
        on = fields?.get(MailAutopilotFields.ON)?.boolOrNull ?: DEFAULT_ON,
        changedAtMs = (fields?.get(MailAutopilotFields.CHANGED_AT) as? FieldValue.Int64)?.value?.takeIf { it > 0 },
        markRead = fields?.get(MailAutopilotFields.MARK_READ)?.boolOrNull ?: true,
    )

    /** Inbox fixes, slice 1: the "Mark tidied mail as read" switch's write (the Autopilot switch's own fields untouched). */
    fun markReadFields(on: Boolean): Map<String, FieldValue> = mapOf(MailAutopilotFields.MARK_READ to on.fv())

    fun fields(on: Boolean, nowMs: Long): Map<String, FieldValue> =
        mapOf(MailAutopilotFields.ON to on.fv(), MailAutopilotFields.CHANGED_AT to nowMs.fv())

    /**
     * From when Autopilot tidies this account's mail, or null while it doesn't: it is on, MEKA isn't paused (the kill
     * switch), and the account's dry-run day is over. Only mail received from then on is tidied: the backlog already in
     * the inbox waits for the clean-up slice, which only applies after Meka taps Approve.
     */
    fun since(setting: MailAutopilotSetting, paused: Boolean, connectedAtMs: Long?, nowMs: Long): Long? {
        if (!setting.on || paused || connectedAtMs == null) return null
        val dryRunOver = connectedAtMs + DRY_RUN_MS
        if (nowMs < dryRunOver) return null
        return maxOf(dryRunOver, setting.changedAtMs ?: 0L)
    }

    /** One id per account and moment, so a retried write lands on the same entity. */
    fun batchId(accountId: String, atMs: Long): String = "mt" + ActivityRules.fnv64("mailtidy:$accountId:$atMs")

    /**
     * What the batch says in Activity and Mail: "Tidied 6 emails · Personal" over "3 newsletters → Navilife/Newsletters
     * · 2 receipts and orders → Navilife/Receipts · 1 notification or promo archived". [counts] KEEP ignored.
     */
    fun lines(counts: Map<MailTidy, Int>, account: String, receipts: List<MailReceipt> = emptyList(), returns: Int = 0, warranties: Int = 0): Pair<String, String> {
        val parts = MailTidy.entries.filter { it != MailTidy.KEEP }.mapNotNull { t -> counts[t]?.takeIf { it > 0 }?.let { it to t } }
        val total = parts.sumOf { it.first }
        val summary = "Tidied $total ${if (total == 1) "email" else "emails"} · $account"
        // Slice 3d: "2 receipts and orders → Navilife/Receipts · £84.20 · Amazon, Tesco".
        val receiptSuffix = MailReceiptRules.suffix(
            counts[MailTidy.RECEIPT] ?: 0, receipts.count { it.pence != null },
            MailReceiptRules.total(receipts), MailReceiptRules.merchants(receipts),
        ) + MailReturnRules.suffix(returns) + MailWarrantyRules.suffix(warranties) // Slices 3e/3f: " · 1 return date → Renewals · 1 warranty → Renewals".
        val detail = parts.joinToString(" · ") { (n, t) ->
            "$n ${if (n == 1) t.one else t.many} ${t.destination}" + if (t == MailTidy.RECEIPT) receiptSuffix else ""
        }
        return summary to detail
    }

    /** Slice 3d: the receipts' fields on a batch (none when nothing was priced or named). */
    fun receiptFields(receipts: List<MailReceipt>): Map<String, FieldValue> {
        val out = mutableMapOf<String, FieldValue>()
        MailReceiptRules.total(receipts)?.let {
            out[MailTidyBatchFields.RECEIPTS_PENCE] = it.fv()
            out[MailTidyBatchFields.RECEIPTS_PRICED] = receipts.count { r -> r.pence != null }.toLong().fv()
        }
        MailReceiptRules.merchants(receipts).takeIf { it.isNotEmpty() }?.let { out[MailTidyBatchFields.MERCHANTS] = it.take(10).joinToString("\n").fv() }
        return out
    }

    fun batchFields(atMs: Long, account: String, counts: Map<MailTidy, Int>, receipts: List<MailReceipt> = emptyList(), returns: Int = 0, warranties: Int = 0): Map<String, FieldValue> {
        val (summary, detail) = lines(counts, account, receipts, returns, warranties)
        return receiptFields(receipts) + mapOf(
            MailTidyBatchFields.AT to atMs.fv(),
            MailTidyBatchFields.ACCOUNT to account.fv(),
            MailTidyBatchFields.SUMMARY to summary.fv(),
            MailTidyBatchFields.DETAIL to detail.fv(),
            MailTidyBatchFields.COUNT to counts.filterKeys { it != MailTidy.KEEP }.values.sum().toLong().fv(),
        ) + counts.mapNotNull { (t, n) -> MailTidyBatchFields.kindField(t)?.takeIf { n > 0 }?.let { it to n.toLong().fv() } }
    }

    fun readBatch(id: String, f: Map<String, FieldValue>): MailTidyBatch? {
        val at = (f[MailTidyBatchFields.AT] as? FieldValue.Int64)?.value ?: return null
        fun text(k: String) = (f[k] as? FieldValue.Text)?.value?.takeIf { it.isNotBlank() }
        fun ms(k: String) = (f[k] as? FieldValue.Int64)?.value?.takeIf { it > 0 }
        return MailTidyBatch(
            id = id, atMs = at,
            account = text(MailTidyBatchFields.ACCOUNT) ?: "Mail",
            summary = text(MailTidyBatchFields.SUMMARY) ?: return null,
            detail = text(MailTidyBatchFields.DETAIL),
            count = (f[MailTidyBatchFields.COUNT] as? FieldValue.Int64)?.value?.toInt() ?: 0,
            undoAskedAtMs = ms(MailTidyBatchFields.UNDO_ASKED_AT),
            undoneAtMs = ms(MailTidyBatchFields.UNDONE_AT),
            undoNote = text(MailTidyBatchFields.UNDO_NOTE),
            kinds = MailTidy.entries.mapNotNull { t ->
                val k = MailTidyBatchFields.kindField(t) ?: return@mapNotNull null
                (f[k] as? FieldValue.Int64)?.value?.toInt()?.takeIf { it > 0 }?.let { t to it }
            }.toMap(),
            backlog = f[MailTidyBatchFields.BACKLOG]?.boolOrNull == true,
            junk = f[MailTidyBatchFields.JUNK]?.boolOrNull == true,
            receiptsPence = (f[MailTidyBatchFields.RECEIPTS_PENCE] as? FieldValue.Int64)?.value?.takeIf { it > 0 },
            receiptsPriced = (f[MailTidyBatchFields.RECEIPTS_PRICED] as? FieldValue.Int64)?.value?.toInt() ?: 0,
            merchants = text(MailTidyBatchFields.MERCHANTS)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            accountKey = text(MailTidyBatchFields.ACCOUNT_KEY),
            emails = MailTidiedRules.decode(text(MailTidyBatchFields.EMAILS)),
            emailAsks = MailTidiedAskRules.decodeAsks(text(MailTidyBatchFields.EMAIL_ASKS)),
            emailDone = MailTidiedAskRules.decodeDone(text(MailTidyBatchFields.EMAIL_DONE)),
        )
    }

    /** The server's answer to an Undo: "Put back 6 emails in the inbox" (slice 3c's Junk review: "in Junk") or, when some had gone (deleted since), "Put back 4 of 6 · 2 had gone". */
    fun undoneNote(putBack: Int, total: Int, place: String = "in the inbox"): String = when {
        putBack == total -> "Put back $total ${if (total == 1) "email" else "emails"} $place"
        else -> "Put back $putBack of $total · ${total - putBack} had gone"
    }

    /** An Undo the server should act on: asked, not done yet, within [UNDO_DAYS] of the tidy. */
    fun undoDue(b: MailTidyBatch, nowMs: Long): Boolean =
        b.undoAskedAtMs != null && b.undoneAtMs == null && nowMs - b.atMs <= UNDO_DAYS * 86_400_000L
}

// ---- Slice 3b-2: the apps' half — the switch, each account's tidies with Undo, Activity ----

/** One tidy under its account in Mail. */
data class MailTidyRow(
    val id: String,
    /** "12:40" today, else "Mon 12 Oct · 12:40". */
    val time: String,
    /** "Tidied 6 emails · Personal". */
    val summary: String,
    /** "3 newsletters → Navilife/Newsletters · …". */
    val detail: String?,
    val canUndo: Boolean,
    /** "Putting back at the next check…" or the server's "Put back 6 emails in the inbox"; null while it stands. */
    val state: String?,
)

/** Autopilot's switch at the top of Mail. */
data class MailAutopilotPanel(
    val on: Boolean,
    val title: String,
    val line: String,
    /** "Turn off" · "Turn on". */
    val action: String,
    /** MEKA's kill switch is on: nothing moves whatever the switch says. */
    val paused: Boolean,
    /** Inbox fixes, slice 5: "Mark tidied mail as read" (synced; absent = on), its line and Turn off / Turn on. */
    val markRead: Boolean = true,
    val markReadTitle: String = MailAutopilotPanelRules.MARK_READ_TITLE,
    val markReadLine: String = MailAutopilotPanelRules.MARK_READ_ON_LINE,
    val markReadAction: String = MailAutopilotPanelRules.TURN_OFF,
)

object MailAutopilotPanelRules {
    const val TITLE = "Autopilot"
    const val TURN_OFF = "Turn off"
    const val TURN_ON = "Turn on"
    const val UNDO = "Undo"
    const val ON_LINE = "On · newsletters, receipts, notifications and promos are archived as they arrive, each with Undo " +
        "here and in Activity; a sender you unsubscribed from goes to Trash. Junk is checked once a day: obvious scams to Trash, " +
        "anything that looks real back in the inbox, flagged. Never mail from people, schools, banks or threads " +
        "you wrote in. Each account's first day is a dry run."
    const val OFF_LINE = "Off · new mail stays where it lands. Turn on to have Navilife tidy it as it arrives."
    const val PAUSED_LINE = "Paused with everything Navilife does on its own · Resume in What Navilife may do"
    const val PUTTING_BACK = "Putting back at the next check…"
    /** An account whose sign-in ended: Autopilot waits for Reconnect (Setup and Today say so too). */
    const val SIGN_IN_ENDED_LINE = "Autopilot · paused · the sign-in ended · Reconnect and it carries on"
    /** How many of an account's tidies Mail lists (the last [SHOWN_DAYS] days); Activity keeps 30 days. */
    const val SHOWN = 5
    const val SHOWN_DAYS = 7

    fun panel(setting: MailAutopilotSetting, paused: Boolean): MailAutopilotPanel = MailAutopilotPanel(
        on = setting.on,
        title = TITLE,
        line = when {
            paused && setting.on -> PAUSED_LINE
            setting.on -> ON_LINE
            else -> OFF_LINE
        },
        action = if (setting.on) TURN_OFF else TURN_ON,
        paused = paused,
        markRead = setting.markRead,
        markReadLine = if (setting.markRead) MARK_READ_ON_LINE else MARK_READ_OFF_LINE,
        markReadAction = if (setting.markRead) TURN_OFF else TURN_ON,
    )

    // ---- Inbox fixes, slice 5: the Mark-read switch in Mail (Meka, 2026-10-15 20:59: "I thought it would mark emails as read") ----

    const val MARK_READ_TITLE = "Mark tidied mail as read"
    const val MARK_READ_ON_LINE = "On · whatever Navilife tidies (archived, cleaned up or sent from Junk to Trash) is marked read, " +
        "so your unread counts fall; Undo puts it back unread"
    const val MARK_READ_OFF_LINE = "Off · tidied mail keeps its unread mark; turn on to have your unread counts fall"

    /** What the switch says once flipped (Mail's note). */
    fun markReadSwitchedLine(on: Boolean): String =
        if (on) "Tidied mail will be marked read from Navilife's next check" else "Tidied mail keeps its unread mark from Navilife's next check"

    /** What switching says (Mail's note, What Navilife may do's undo bar). */
    fun switchedLine(on: Boolean): String =
        if (on) "Autopilot is on · new mail is tidied as it arrives, each with Undo"
        else "Autopilot is off · new mail stays where it lands; what was tidied stays tidied (Undo is still there)"

    /** The tidy's state line: put back (the server's note), asked (waiting for the server's next check), or null. */
    fun state(b: MailTidyBatch): String? = when {
        b.undoneAtMs != null -> b.undoNote ?: "Put back in the inbox"
        b.undoAskedAtMs != null -> PUTTING_BACK
        else -> null
    }

    /**
     * The account's Autopilot line in Mail: off or paused, the dry run's "would tidy …" ([MailTidyRules.dryRunLine]);
     * on, "Autopilot · on · tidied 12 today" (what still stands, put-back ones not counted) or "Autopilot · on ·
     * nothing tidied today · new mail is tidied as it arrives". Completeness: on, but the account's sign-in has ended
     * (revoked or expired, [signInEnded]): nothing can move until he reconnects, so it says so ([SIGN_IN_ENDED_LINE]).
     */
    fun accountLine(
        setting: MailAutopilotSetting, paused: Boolean, wouldTidy: Map<String, Int>?, todays: List<MailTidyBatch>,
        signInEnded: Boolean = false,
    ): String? {
        if (!setting.on || paused) return MailTidyRules.dryRunLine(wouldTidy)
        if (signInEnded) return SIGN_IN_ENDED_LINE
        val n = todays.filter { it.undoneAtMs == null && it.undoAskedAtMs == null }.sumOf { it.count }
        return if (n > 0) "Autopilot · on · tidied $n today" else "Autopilot · on · nothing tidied today · new mail is tidied as it arrives"
    }

    /** The account's tidies, newest first: the last [SHOWN_DAYS] days, at most [SHOWN]. */
    fun rows(batches: List<MailTidyBatch>, nowMs: Long, cal: LocalCalendar): List<MailTidyRow> {
        val today = cal.epochDayOf(nowMs)
        return batches.filter { today - cal.epochDayOf(it.atMs) in 0 until SHOWN_DAYS }
            .sortedWith(compareByDescending<MailTidyBatch> { it.atMs }.thenBy { it.id })
            .take(SHOWN)
            .map { b ->
                val day = cal.epochDayOf(b.atMs)
                val clock = LocalClock.formatMinute(cal.minuteOfDay(b.atMs))
                MailTidyRow(
                    id = b.id,
                    time = if (day == today) clock else "${CivilDate.shortLabel(day)} · $clock",
                    summary = b.summary, detail = b.detail,
                    canUndo = b.canUndo && nowMs - b.atMs <= MailAutopilotRules.UNDO_DAYS * 86_400_000L,
                    state = state(b),
                )
            }
    }

    /** Today's tidies of one account (by its label as Mail shows it). */
    fun todays(batches: List<MailTidyBatch>, account: String, nowMs: Long, cal: LocalCalendar): List<MailTidyBatch> =
        batches.filter { it.account == account && cal.epochDayOf(it.atMs) == cal.epochDayOf(nowMs) }
}
