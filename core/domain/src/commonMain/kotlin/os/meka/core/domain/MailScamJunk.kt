package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Phishing that hides its threat in the body (Meka, 2026-11-08: "emails like this are junk, phishing and dodgy: it
 * should be blocked and deleted … I expect the app to handle this"), slice 3: **a sure scam goes to Junk by itself**.
 *
 * At each mail check (unless Navilife or Inbox is paused) the server moves each sure scam still in the inbox to that
 * account's Junk/Spam ([MailTidy.SCAM_JUNK]: recoverable, which also trains the provider's own filter, never a permanent
 * delete), marks it read, and blocks its sender ([MailSenderRule.BLOCK]), so it leaves Needs you. A day's moves for one
 * account are one `mail_tidy` batch ("Moved 3 scam emails to Junk · Personal") that Archived for you and Activity show
 * with the emails, Put back per email, Always keep from this sender, and Undo for [MailAutopilotRules.UNDO_DAYS] days,
 * which also takes off the blocks it set. An unsure one keeps its red "Possible scam" line and stays for him.
 */
object MailScamJunkRules {
    /** At most this many moved per account per check. */
    const val PER_CHECK = 20

    /**
     * Sure enough to move by itself: sorted a scam ([MailSortReason.SCAM]) and either its display name claims a company
     * it isn't sent from ([MailScamRules.nameClaim]: "Outlook Session" from formlab.it) or the server's body check found
     * it ([bodyLine], [MailScamRules.sortedBodyLine]: links elsewhere with pressing words, a greeting with his address, a
     * failed sender check). A company named only in the subject from an unrelated address stays an unsure "Possible scam".
     */
    fun sure(sorted: MailSorted?, fromAddress: String?, fromName: String?, bodyLine: String?): Boolean {
        if (sorted?.reason != MailSortReason.SCAM || sorted.sort != MailSort.NEEDS_YOU) return false
        return MailScamRules.scamLine(bodyLine) || MailScamRules.nameClaim(fromAddress, fromName)
    }

    /**
     * Whether one email moves: [sure], still in the inbox, and never anyone he knows or keeps — not a thread he wrote in,
     * not his partner's or his correspondents' address ([MailTidyFacts.keepAddresses]), not a bank, school or public
     * body ([MailTidyRules.protectedSender]), not a sender he said to keep ([MailSenderRule.KEEP], "Always keep"),
     * never account-security mail.
     */
    fun moves(f: MailTidyFacts, fromName: String?, bodyLine: String?): Boolean {
        val s = f.sorted ?: return false
        if (!f.inInbox || f.replied || f.rule == MailSenderRule.KEEP) return false
        if (MailTidyRules.protectedSender(f.fromAddress, f.keepAddresses) || MailSecurityRules.kept(s.reason)) return false
        return sure(s, f.fromAddress, fromName, bodyLine)
    }

    /** The day's batch for one account; [round] counts up past a batch he already put back that day (1 first). */
    fun batchId(accountId: String, epochDay: Long, round: Int = 1): String =
        "mt" + ActivityRules.fnv64("scamjunk:$accountId:$epochDay" + if (round > 1) ":$round" else "")

    /** "Moved 3 scam emails to Junk · Personal". */
    fun summary(count: Int, account: String): String =
        "Moved $count scam ${if (count == 1) "email" else "emails"} to Junk · $account"

    const val DETAIL_ONE = "Its sender is blocked · Undo puts it back in the inbox"
    const val DETAIL_MANY = "Their senders are blocked · Undo puts them back in the inbox"

    /** The batch's fields: [atMs] is the day's first move (Undo's 30 days run from it), [count] the day's total. */
    fun batchFields(atMs: Long, account: String, count: Int): Map<String, FieldValue> = mapOf(
        MailTidyBatchFields.AT to atMs.fv(),
        MailTidyBatchFields.ACCOUNT to account.fv(),
        MailTidyBatchFields.SUMMARY to summary(count, account).fv(),
        MailTidyBatchFields.DETAIL to (if (count == 1) DETAIL_ONE else DETAIL_MANY).fv(),
        MailTidyBatchFields.COUNT to count.toLong().fv(),
        MailTidyBatchFields.SCAM_JUNKED to count.toLong().fv(),
    )

    /** True for a batch of scams moved to Junk (its Undo also takes off the blocks it set). */
    fun scamBatch(b: MailTidyBatch): Boolean = (b.kinds[MailTidy.SCAM_JUNK] ?: 0) > 0

    /**
     * After an Undo (or Put back of one email) of a scam moved to Junk: the sender's block comes off only while it is
     * still the block this batch set (written at or after the batch's [batchAtMs] and before the Undo [undoAtMs]); a rule
     * he set himself since stands. Null to leave it.
     */
    fun unblockFields(current: Map<String, FieldValue>?, batchAtMs: Long, undoAtMs: Long, nowMs: Long): Map<String, FieldValue>? {
        if (MailSenderRuleRules.read(current) != MailSenderRule.BLOCK) return null
        val at = (current?.get(MailSenderFields.RULE_AT) as? FieldValue.Int64)?.value ?: return null
        if (at < batchAtMs || at > undoAtMs) return null
        return MailSenderRuleRules.fields(null, nowMs)
    }
}
