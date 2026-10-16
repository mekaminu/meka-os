package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Inbox fixes, slice 8 (Meka, 2026-10-15 20:59: "I thought it would mark emails as read"): **Mark N read**, once per
 * account. Autopilot marks what it tidies read, and the backlog clean-up marks what it archives, but the newsletters,
 * promos and receipts already in the inbox that are younger than the clean-up's week stay unread, so his counts barely
 * fall. Today's line already counts them ([MailUnreadRules], [MailBacklog.tidyUnread]); this offers one Needs you card
 * per account, "Mark 380 read · Personal" · "380 unread newsletters, promos and receipts in your inbox · they stay where
 * they are, just marked read", → **Mark read** (synced on the account's `mail_backlog` entity,
 * [MailBacklogFields.MARK_READ_APPROVED_AT]; Undo while nothing has been marked). The server then marks them read a few
 * hundred a check (the same tidy rules that counted them, so people, banks, schools and his partner are never touched)
 * and finishes when none are left. Nothing moves, nothing is deleted; it never marks anything without his tap. Non-AI.
 */
object MailMarkReadRules {
    /** Fewer than this isn't worth a card. */
    const val MIN = 5
    /** After one run finished, the card comes back once this long has passed and enough have built up again. */
    const val OFFER_AGAIN_AFTER_MS = 7 * 86_400_000L
    /** Emails marked per check (Gmail marks a thousand a call; Outlook one PATCH each). */
    const val PER_CHECK_GMAIL = 500
    const val PER_CHECK_OUTLOOK = 100

    /** The card's id: the account's `mail_backlog` id behind this prefix, so the apps' Approve reaches core unchanged. */
    const val CARD_PREFIX = "read:"
    fun cardId(backlogId: String): String = CARD_PREFIX + backlogId
    fun isCard(id: String): Boolean = id.startsWith(CARD_PREFIX)
    fun backlogIdOf(cardId: String): String = cardId.removePrefix(CARD_PREFIX)

    const val CARD_YES = "Mark read"
    const val CARD_LATER = "Later"
    const val CARD_OPEN = "Review"
    const val ALREADY_STARTED = "Already started · they stay in the inbox, marked read"
    const val UNAPPROVED_BAR = "Cancelled · nothing marked"

    private fun n(v: Int) = SpeechRules.grouped(v.toLong())

    /** A run asked and not finished. */
    fun running(b: MailBacklog): Boolean {
        val asked = b.markReadApprovedAtMs ?: return false
        val done = b.markReadFinishedAtMs ?: return true
        return done < asked
    }

    /**
     * Offered while the inbox holds at least [MIN] unread tidy-kind emails (counted within [MailUnreadRules.STALE_MS] of
     * [nowMs]; 0 skips that check), nothing is running, and he hasn't asked before, or the last run finished more than
     * [OFFER_AGAIN_AFTER_MS] ago.
     */
    fun canApprove(b: MailBacklog, nowMs: Long = 0L): Boolean {
        val counted = b.unreadCountedAtMs ?: return false
        if (nowMs > 0 && nowMs - counted > MailUnreadRules.STALE_MS) return false
        if (b.tidyUnread < MIN || running(b)) return false
        val finished = b.markReadFinishedAtMs ?: return b.markReadApprovedAtMs == null
        return (if (nowMs > 0) nowMs else counted) - finished >= OFFER_AGAIN_AFTER_MS && counted > finished
    }

    /** The bar's Undo only stands while nothing has been marked. */
    fun canUnapprove(b: MailBacklog): Boolean = running(b) && b.markedRead == 0

    /** Whether the server marks more this check: asked, not finished, Navilife not paused (the kill switch). */
    fun shouldMark(b: MailBacklog, paused: Boolean): Boolean = !paused && running(b)

    /** What an app writes on his tap (a new run: the last run's count and end cleared), and on the bar's Undo. */
    fun approveFields(nowMs: Long): Map<String, FieldValue> = mapOf(
        MailBacklogFields.MARK_READ_APPROVED_AT to nowMs.fv(),
        MailBacklogFields.MARKED_READ to 0L.fv(),
        MailBacklogFields.MARK_READ_FINISHED_AT to 0L.fv(),
    )
    fun unapproveFields(): Map<String, FieldValue> = mapOf(MailBacklogFields.MARK_READ_APPROVED_AT to 0L.fv())

    /** The server's progress (cumulative for this run), and when it found none left. */
    fun progressFields(marked: Int, finishedAtMs: Long?): Map<String, FieldValue> =
        mapOf(MailBacklogFields.MARKED_READ to marked.toLong().fv()) +
            (finishedAtMs?.let { mapOf(MailBacklogFields.MARK_READ_FINISHED_AT to it.fv()) } ?: emptyMap())

    /** The bar's line after his tap. */
    fun approvedBar(b: MailBacklog): String =
        "Marking ${n(b.tidyUnread)} read from Navilife's next check · they stay in the inbox"

    /**
     * "Mark 380 read · Personal" · "380 unread newsletters, promos and receipts in your inbox · they stay where they
     * are, just marked read · people, banks and schools are never touched".
     */
    fun card(b: MailBacklog, nowMs: Long = 0L): DecisionCard? {
        if (!canApprove(b, nowMs)) return null
        val c = b.tidyUnread
        val why = listOf(
            "${n(c)} unread ${if (c == 1) "newsletter, promo or receipt" else "newsletters, promos and receipts"} in your inbox",
            "they stay where they are, just marked read",
            "people, banks and schools are never touched",
        ).joinToString(" · ")
        return DecisionCard(
            id = cardId(b.id), kind = DecisionKind.MAIL_CLEANUP, title = "Mark ${n(c)} read · ${b.account}", why = why, taskId = null,
            yesLabel = CARD_YES, laterLabel = CARD_LATER, openLabel = CARD_OPEN,
            yes = DecisionEffect.APPROVE_BACKLOG, later = DecisionEffect.SET_ASIDE, open = DecisionEffect.OPEN_MAIL,
            urgent = false,
        )
    }

    /** Mail's line under the account's clean-up: "Marking read · 120 so far…" / "Marked 380 newsletters and promos read"; null when never asked. */
    fun line(b: MailBacklog): String? = when {
        running(b) -> if (b.markedRead > 0) "Marking newsletters and promos read · ${n(b.markedRead)} so far…" else "Marking newsletters and promos read from Navilife's next check…"
        b.markReadFinishedAtMs != null && b.markReadApprovedAtMs != null ->
            if (b.markedRead > 0) "Marked ${n(b.markedRead)} ${if (b.markedRead == 1) "newsletter or promo" else "newsletters and promos"} read · they stay in the inbox"
            else "Nothing left to mark read"
        else -> null
    }
}
