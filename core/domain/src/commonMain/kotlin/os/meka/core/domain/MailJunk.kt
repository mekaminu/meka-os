package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Inbox autopilot, slice 3c: the daily Junk review (Meka, 2026-10-12: "Junk folder reviewed daily: obvious spam/scams →
 * Trash, anything legit-looking in Junk rescued to the inbox and flagged; nothing from contacts, his wife, the school,
 * the bank/HMRC/employer domains or threads he replied to is ever … trashed"). Non-AI, pure, unit-tested; the server runs
 * it once a day per account over the Junk folder's recent headers (never a body), only while Autopilot may act (on, MEKA
 * not paused, the account's dry-run day over). What it moves is one `mail_tidy` batch flagged
 * [MailTidyBatchFields.JUNK], with Undo like any tidy (Trash back to Junk, a rescued email back to Junk).
 *
 * Inbox fixes, slice 3: everything else still in Junk after [MailJunkRules.CLEAR_AFTER_MS] goes to Trash too, and
 * [MailJunkClearRules] empties the folder's older mail once, on Meka's Approve.
 *
 * Rescue needs a strong sign the email is real: his partner's address (About you), a thread he wrote in, or an exact
 * address that already writes to him (mail from it sits in his inbox, sorted as needing him or FYI). Trash needs an
 * obvious scam: a scam phrase in the subject or name, or a name claiming a well-known company or public body sent
 * from an address that isn't theirs. Banks, public bodies and schools are never trashed and never rescued on their own
 * say-so (a spoof is the classic scam): they stay in Junk. Anything unclear stays where the provider put it.
 */
enum class MailJunkVerdict { TRASH, RESCUE, LEAVE }

/** What the review sees of one email in Junk. */
data class MailJunkFacts(
    val fromAddress: String?,
    val fromName: String?,
    val subject: String?,
    /** Addresses never trashed and always rescued (lower case): his partner's. */
    val keepAddresses: Set<String> = emptySet(),
    /** In a thread Meka wrote in. */
    val replied: Boolean = false,
    /** Mail from this exact address already sits in his inbox, sorted as needing him or FYI. */
    val knownSender: Boolean = false,
    /** Inbox fixes, slice 3: when it arrived, and now: Junk older than [MailJunkRules.CLEAR_AFTER_MS] goes to Trash. */
    val receivedAtMs: Long? = null,
    val nowMs: Long = 0L,
)

object MailJunkRules {
    /** Reviewed once a day: the next review waits this long after the last. */
    const val REVIEW_EVERY_MS = 20 * 3_600_000L
    /** Junk received in this window is looked at (a missed day is still covered). */
    const val WINDOW_MS = 4 * 86_400_000L
    /**
     * Inbox fixes, slice 3 (Meka, 2026-10-15: "the Junk mailbox has a lot of rubbish spam"): the provider already called
     * it spam, so anything still in Junk after two days goes to Trash (recoverable 30 days), unless it's someone he knows
     * (rescued) or a bank, public body or school (left for him to check).
     */
    const val CLEAR_AFTER_MS = 2 * 86_400_000L
    /** At most this many emails moved per review, so a mistake can never sweep the folder. */
    const val MAX_PER_REVIEW = 100

    /** Phrases scams and spam use (lower case, matched in the subject and the sender's name). */
    val SCAM_PHRASES = listOf(
        "you have won", "you've won", "winner", "lottery", "claim your prize", "claim your reward", "prize draw",
        "congratulations", "you have been selected", "you've been selected", "inheritance", "beneficiary", "next of kin",
        "million dollars", "million pounds", "million usd", "bitcoin", "crypto", "btc ", "investment opportunity",
        "double your", "viagra", "cialis", "casino", "free spins", "betting bonus", "hot singles", "sugar daddy",
        "verify your account", "verify your identity", "confirm your identity", "account suspended", "account has been suspended",
        "account will be closed", "unusual sign-in", "unusual activity", "password expires", "password will expire",
        "mailbox is full", "storage is full", "payment failed", "update your payment", "final notice", "tax refund",
        "refund pending", "you are owed", "parcel is held", "parcel held", "delivery failed", "redelivery fee", "customs fee",
        "gift card", "act now", "urgent response", "risk-free", "100% free", "work from home and earn",
    )

    /** Names scams pretend to be (lower case) with the domains that are really theirs. */
    val CLAIMED_SENDERS: Map<String, List<String>> = mapOf(
        "paypal" to listOf("paypal.com", "paypal.co.uk"),
        "amazon" to listOf("amazon.co.uk", "amazon.com", "amazon.de", "amazonses.com"),
        "apple" to listOf("apple.com", "icloud.com"),
        "microsoft" to listOf("microsoft.com", "outlook.com", "office.com"),
        "netflix" to listOf("netflix.com"),
        "royal mail" to listOf("royalmail.com", "royalmail.co.uk"),
        "dpd" to listOf("dpd.co.uk", "dpdlocal.co.uk", "dpd.com"),
        "evri" to listOf("evri.com", "myhermes.co.uk"),
        "dhl" to listOf("dhl.com", "dhl.co.uk"),
        "hmrc" to listOf("gov.uk"),
        "dvla" to listOf("gov.uk"),
        "tv licensing" to listOf("tvlicensing.co.uk"),
        "nhs" to listOf("nhs.uk", "nhs.net"),
        "ee " to listOf("ee.co.uk"),
        "vodafone" to listOf("vodafone.co.uk", "vodafone.com"),
        "o2" to listOf("o2.co.uk"),
        "barclays" to listOf("barclays.co.uk", "barclays.com"),
        "hsbc" to listOf("hsbc.co.uk", "hsbc.com"),
        "lloyds" to listOf("lloydsbank.co.uk", "lloydsbank.com"),
        "natwest" to listOf("natwest.com"),
        "santander" to listOf("santander.co.uk"),
        "nationwide" to listOf("nationwide.co.uk"),
        "monzo" to listOf("monzo.com"),
    )

    private fun endsWithDomain(domain: String, suffix: String) = domain == suffix || domain.endsWith(".$suffix")

    /** Why it reads as a scam (the matched phrase or claim), or null. */
    fun scamSignal(fromAddress: String?, fromName: String?, subject: String?): String? {
        val text = " " + listOfNotNull(subject, fromName).joinToString(" ").lowercase() + " "
        SCAM_PHRASES.firstOrNull { it in text }?.let { return it.trim() }
        val name = " " + (fromName ?: "").lowercase() + " "
        val domain = MailSortRules.domain(fromAddress)
        for ((claim, domains) in CLAIMED_SENDERS) {
            val token = claim.trim()
            val claimed = Regex("(^|[^a-z0-9])" + Regex.escape(token) + "([^a-z0-9]|$)").containsMatchIn(name)
            if (claimed && (domain == null || domains.none { endsWithDomain(domain, it) })) return "claims to be $token"
        }
        return null
    }

    fun verdict(f: MailJunkFacts): MailJunkVerdict {
        val address = f.fromAddress?.trim()?.trimStart('<')?.trimEnd('>')?.lowercase()
        // His partner and threads he wrote in: never trashed; rescued.
        if ((address != null && address in f.keepAddresses) || f.replied) return MailJunkVerdict.RESCUE
        val scam = scamSignal(f.fromAddress, f.fromName, f.subject)
        // Banks, public bodies and schools: never trashed, never rescued on their own say-so.
        if (MailTidyRules.protectedSender(f.fromAddress, emptySet())) return MailJunkVerdict.LEAVE
        // Account-security mail from the provider's real domain: never trashed, never rescued on its own say-so.
        if (MailSecurityRules.kind(f.fromAddress, f.subject) != null) return MailJunkVerdict.LEAVE
        // Someone who already writes to him is never trashed; rescued unless it reads as a scam (a hacked account).
        if (f.knownSender && address != null) return if (scam == null) MailJunkVerdict.RESCUE else MailJunkVerdict.LEAVE
        if (scam != null) return MailJunkVerdict.TRASH
        val received = f.receivedAtMs ?: return MailJunkVerdict.LEAVE
        return if (f.nowMs - received >= CLEAR_AFTER_MS) MailJunkVerdict.TRASH else MailJunkVerdict.LEAVE
    }

    fun tidyOf(v: MailJunkVerdict): MailTidy = when (v) {
        MailJunkVerdict.TRASH -> MailTidy.JUNK_TRASH
        MailJunkVerdict.RESCUE -> MailTidy.RESCUE
        MailJunkVerdict.LEAVE -> MailTidy.KEEP
    }

    /** Whether the account's Junk is due a look: never looked, or the last look was [REVIEW_EVERY_MS] ago. */
    fun due(lastReviewMs: Long?, nowMs: Long): Boolean = lastReviewMs == null || nowMs - lastReviewMs >= REVIEW_EVERY_MS

    /** One batch per account and review. */
    fun batchId(accountId: String, atMs: Long): String = "mt" + ActivityRules.fnv64("mailjunk:$accountId:$atMs")

    /**
     * The review's tidy as Mail, Activity and "Archived for you" show it: "Junk checked · 3 to Trash · 1 rescued ·
     * Personal" over "3 junk emails → Trash · 1 email rescued from Junk → inbox, flagged".
     */
    fun lines(trashed: Int, rescued: Int, account: String): Pair<String, String> {
        val summary = listOfNotNull("Junk checked", trashed.takeIf { it > 0 }?.let { "$it to Trash" },
            rescued.takeIf { it > 0 }?.let { "$it rescued" }, account).joinToString(" · ")
        val detail = listOfNotNull(
            trashed.takeIf { it > 0 }?.let { "$it ${if (it == 1) MailTidy.JUNK_TRASH.one else MailTidy.JUNK_TRASH.many} ${MailTidy.JUNK_TRASH.destination}" },
            rescued.takeIf { it > 0 }?.let { "$it ${if (it == 1) MailTidy.RESCUE.one else MailTidy.RESCUE.many} ${MailTidy.RESCUE.destination}" },
        ).joinToString(" · ")
        return summary to detail
    }

    fun batchFields(atMs: Long, account: String, counts: Map<MailTidy, Int>): Map<String, FieldValue> {
        val (summary, detail) = lines(counts[MailTidy.JUNK_TRASH] ?: 0, counts[MailTidy.RESCUE] ?: 0, account)
        return MailAutopilotRules.batchFields(atMs, account, counts) + mapOf(
            MailTidyBatchFields.SUMMARY to summary.fv(),
            MailTidyBatchFields.DETAIL to detail.fv(),
            MailTidyBatchFields.JUNK to true.fv(),
        )
    }
}

/**
 * Inbox fixes, slice 3: **Empty Junk**, once per account (Meka, 2026-10-15 21:06: "Junk 322" untouched in Outlook). The
 * daily review only looks at the last few days, so the folder's older mail waits for one Needs you card per account,
 * "Empty Junk · Outlook" · "322 emails your provider called spam → Trash …", → **Approve** (the same synced approval as
 * the backlog clean-up, on the same `mail_backlog` entity: [MailBacklogFields.JUNK_APPROVED_AT]). Then the server moves
 * a couple of hundred a check, each by [MailJunkRules.verdict] (people he knows rescued, banks, public bodies and schools
 * left), as one `mail_tidy` batch flagged junk whose Undo puts them back in Junk. Never deleted for good.
 */
object MailJunkClearRules {
    /** The card's id: the account's `mail_backlog` id behind this prefix, so the apps' Approve reaches core unchanged. */
    const val CARD_PREFIX = "junk:"
    /** Emails looked at per check (Gmail reads each header; Outlook's listing caps at 100). */
    const val PER_CHECK_GMAIL = 200
    const val PER_CHECK_OUTLOOK = 100
    /** The folder is counted again at most this often while the card waits. */
    const val RECOUNT_EVERY_MS = 6 * 3_600_000L

    fun cardId(backlogId: String): String = CARD_PREFIX + backlogId
    fun isCard(id: String): Boolean = id.startsWith(CARD_PREFIX)
    fun backlogIdOf(cardId: String): String = cardId.removePrefix(CARD_PREFIX)

    /** The clean-out's one tidy per account (so its Undo is one tap). */
    fun batchId(accountId: String): String = "mt" + ActivityRules.fnv64("mailjunkclear:$accountId")

    /** Offered while Junk holds something and he hasn't approved (or it already ran). */
    fun canApprove(b: MailBacklog): Boolean = b.junkFolder > 0 && b.junkApprovedAtMs == null && b.junkFinishedAtMs == null
    /** The bar's Undo after Approve only stands while nothing has moved. */
    fun canUnapprove(b: MailBacklog): Boolean = b.junkApprovedAtMs != null && b.junkCleared == 0 && b.junkFinishedAtMs == null

    /** Whether the server moves more this check: approved, not finished, Navilife not paused, no Undo asked. */
    fun shouldClear(b: MailBacklog, paused: Boolean, batch: MailTidyBatch?): Boolean {
        if (b.junkApprovedAtMs == null || b.junkFinishedAtMs != null || paused) return false
        return batch == null || (batch.undoAskedAtMs == null && batch.undoneAtMs == null)
    }

    /** Whether the server counts the folder again: not approved or done, and never counted or [RECOUNT_EVERY_MS] ago. */
    fun countDue(b: MailBacklog?, nowMs: Long): Boolean {
        if (b == null || b.junkApprovedAtMs != null || b.junkFinishedAtMs != null) return false
        val at = b.junkCountedAtMs ?: return true
        // Review opens its own list, slice 1: a count made before the sender list existed is made again once.
        // (Not more than every half hour, in case the provider can't list the folder.)
        if (b.junkFolder > 0 && b.junkSenders == null && nowMs - at >= 30 * 60_000L) return true
        return nowMs - at >= RECOUNT_EVERY_MS
    }

    /** Slice 9: older Junk the count looks at to say exactly what stays (Gmail reads each header; Outlook lists 100 a page). */
    const val LOOK_MAX_GMAIL = 200
    const val LOOK_MAX_OUTLOOK = 500

    /**
     * Slice 9: what the count's look found, from each looked-at email's tidy ([MailJunkRules.tidyOf]): KEEP stays for him
     * to check, RESCUE goes back to the inbox. [full] when the look reached the end of the folder's older mail.
     */
    fun lookFields(tidies: List<MailTidy>, full: Boolean): Map<String, FieldValue> = mapOf(
        MailBacklogFields.JUNK_KEEP to tidies.count { it == MailTidy.KEEP }.toLong().fv(),
        MailBacklogFields.JUNK_RESCUE to tidies.count { it == MailTidy.RESCUE }.toLong().fv(),
        MailBacklogFields.JUNK_LOOKED to tidies.size.toLong().fv(),
        MailBacklogFields.JUNK_LOOK_FULL to full.fv(),
    )

    fun countFields(count: Int, atMs: Long): Map<String, FieldValue> =
        mapOf(MailBacklogFields.JUNK_FOLDER to count.toLong().fv(), MailBacklogFields.JUNK_COUNTED_AT to atMs.fv())
    fun approveFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailBacklogFields.JUNK_APPROVED_AT to nowMs.fv())
    fun unapproveFields(): Map<String, FieldValue> = mapOf(MailBacklogFields.JUNK_APPROVED_AT to 0L.fv())
    fun progressFields(cleared: Int, finishedAtMs: Long?): Map<String, FieldValue> =
        mapOf(MailBacklogFields.JUNK_CLEARED to cleared.toLong().fv()) +
            (finishedAtMs?.let { mapOf(MailBacklogFields.JUNK_FINISHED_AT to it.fv()) } ?: emptyMap())

    const val CARD_YES = "Approve"
    const val CARD_LATER = "Later"
    const val CARD_OPEN = "Review"

    /**
     * "Empty Junk · Outlook" · "316 emails your provider called spam → Trash, recoverable for 30 days · keep 6 to check:
     * banks, schools and public bodies · 2 from people you know go back to the inbox · nothing moves until you approve".
     * Before the count has looked at the folder (slice 9), the kinds without numbers; when the look stopped short of the
     * end, "at least" what it found.
     */
    fun card(b: MailBacklog): DecisionCard? {
        if (!canApprove(b)) return null
        fun g(v: Int) = SpeechRules.grouped(v.toLong())
        val looked = b.junkLooked
        val kept = MailJunkReviewRules.keptCount(b)
        val n = (if (looked != null && b.junkLookFull) (b.junkFolder - b.junkKeep - b.junkRescue).coerceAtLeast(0) else b.junkFolder) - kept
        val atLeast = if (b.junkLookFull) "" else "at least "
        val keep = when {
            looked == null -> "banks, schools and public bodies stay for you to check"
            b.junkKeep == 0 && b.junkLookFull -> "nothing for you to check"
            b.junkKeep == 0 -> "banks, schools and public bodies stay for you to check"
            else -> "keep $atLeast${g(b.junkKeep)} to check: banks, schools and public bodies"
        }
        val rescue = when {
            looked == null -> "people you know go back to the inbox"
            b.junkRescue == 0 && b.junkLookFull -> null
            b.junkRescue == 0 -> "people you know go back to the inbox"
            else -> "$atLeast${g(b.junkRescue)} from people you know go back to the inbox"
        }
        // Everything older is for him to check (or too new for the clean-out): nothing to approve.
        if (looked != null && b.junkLookFull && n <= 0 && b.junkRescue == 0) return null
        val why = listOfNotNull(
            if (n <= 0) null else "${g(n)} ${if (n == 1) "email" else "emails"} your provider called spam → Trash, recoverable for 30 days",
            kept.takeIf { it > 0 }?.let { "${g(it)} kept in Junk" },
            keep,
            rescue,
            "nothing moves until you approve",
        ).joinToString(" · ")
        return DecisionCard(
            id = cardId(b.id), kind = DecisionKind.MAIL_CLEANUP, title = "Empty Junk · ${b.shownName}", why = why, taskId = null,
            yesLabel = CARD_YES, laterLabel = CARD_LATER, openLabel = CARD_OPEN,
            yes = DecisionEffect.APPROVE_BACKLOG, later = DecisionEffect.SET_ASIDE, open = DecisionEffect.OPEN_REVIEW,
            urgent = false,
        )
    }

    /** The clean-out's tidy: "Junk emptied · 318 to Trash · 2 rescued · Outlook" over what went where (cumulative). */
    fun batchFields(atMs: Long, account: String, counts: Map<MailTidy, Int>): Map<String, FieldValue> {
        val trashed = counts[MailTidy.JUNK_TRASH] ?: 0
        val rescued = counts[MailTidy.RESCUE] ?: 0
        val summary = listOfNotNull("Junk emptied", trashed.takeIf { it > 0 }?.let { "${SpeechRules.grouped(it.toLong())} to Trash" },
            rescued.takeIf { it > 0 }?.let { "$it rescued" }, account).joinToString(" · ")
        return MailJunkRules.batchFields(atMs, account, counts) + mapOf(MailTidyBatchFields.SUMMARY to summary.fv())
    }
}
