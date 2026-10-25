package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Review opens its own list (Meka, 2026-10-17 04:03: "check every swipe/approval card's Review goes to its own list"),
 * slice 4: **the backlog clean-up's and Mark read's reviews**. Slice 1 gave Empty Junk its sheet; the other two cards'
 * ↑ Review still opened Mail. Now:
 *
 * - **Clean up <account>**: once the plan is final the server writes, on the account's `mail_backlog` entity, who the
 *   planned emails are from, grouped by sender and where they'd go ([MailBacklogFields.PLAN_SENDERS], same encoding as
 *   [MailJunkReviewRules.encode], a hashed key per sender, never an address). The sheet lists them the most first
 *   ("Weekly Digest · weekly.example · 412 emails · newsletters → Archive"), each with a tick; taking it off keeps that
 *   sender's older mail in the inbox ([MailBacklogFields.PLAN_KEEP_SENDERS], synced, Undo) and the clean-up never moves
 *   it. Approve inside counts only what's still ticked.
 * - **Mark N read**: when the server counts the unread newsletters, promos and receipts it writes who they're from, with
 *   two subjects each ([MailBacklogFields.READ_SENDERS]); untick a sender and their mail stays unread
 *   ([MailBacklogFields.READ_KEEP_SENDERS]); "Mark N read" inside counts the rest.
 *
 * Both reuse [JunkReview] so the two apps' existing sheet shows them. Every word in a list is the sender's (ADR-006):
 * plain text, never acted on. Non-AI, pure, unit-tested.
 */
object MailCleanupReviewRules {
    /** Where a clean-up sender's mail can go (each [MailBacklogKind]'s tidy, KEEP aside). */
    val PLAN_TIDIES = setOf(MailTidy.NEWSLETTER, MailTidy.RECEIPT, MailTidy.ARCHIVE, MailTidy.TRASH)
    /** What "Mark N read" marks. */
    val READ_TIDIES = setOf(MailTidy.NEWSLETTER, MailTidy.RECEIPT, MailTidy.ARCHIVE)

    const val PLAN_HINT = "Take the tick off a sender to keep their older mail in your inbox. Nothing moves until you approve."
    const val PLAN_WAITING = "Navilife lists who these are from at its next check (within the hour). Nothing moves until you approve."
    const val PLAN_KEPT_BAR = "Kept in your inbox · the clean-up won't move their mail"
    const val PLAN_UNKEPT_BAR = "Moving with the rest"

    const val READ_HINT = "Take the tick off a sender to leave their mail unread. Nothing moves; nothing is deleted."
    const val READ_WAITING = "Navilife lists who these are from at its next count (within the hour). Nothing is marked until you tap."
    const val READ_NOTE = "They stay where they are, just marked read · people, banks, schools and anything needing you stay unread"
    const val READ_KEPT_BAR = "Left unread · Navilife won't mark their mail read"
    const val READ_UNKEPT_BAR = "Marked read with the rest"

    private fun g(v: Int) = SpeechRules.grouped(v.toLong())
    private fun emails(v: Int) = "${g(v)} ${if (v == 1) "email" else "emails"}"
    private fun senders(v: Int) = "${g(v)} ${if (v == 1) "sender" else "senders"}"

    // ---- What the server writes ----

    /** The clean-up's senders from the planned emails (KEEP left out). [items] (fromAddress, fromName, receivedAtMs, kind). */
    fun planSenders(items: List<PlanLooked>): List<MailJunkSender> =
        MailJunkReviewRules.group(items.filter { it.kind != MailBacklogKind.KEEP }.map {
            MailJunkReviewRules.Looked(it.fromAddress, it.fromName, null, it.receivedAtMs, it.kind.tidy, null)
        }).map { it.copy(scam = null) }

    data class PlanLooked(val fromAddress: String?, val fromName: String?, val receivedAtMs: Long, val kind: MailBacklogKind)

    fun planFields(senders: List<MailJunkSender>): Map<String, FieldValue> =
        mapOf(MailBacklogFields.PLAN_SENDERS to MailJunkReviewRules.encode(senders).fv())

    /** Mark read's senders from the unread tidy-kind emails ([MailJunkReviewRules.Looked] with their tidy). */
    fun readSenders(looked: List<MailJunkReviewRules.Looked>): List<MailJunkSender> =
        MailJunkReviewRules.group(looked.filter { it.tidy in READ_TIDIES }).map { it.copy(scam = null) }

    fun readFields(senders: List<MailJunkSender>): Map<String, FieldValue> =
        mapOf(MailBacklogFields.READ_SENDERS to MailJunkReviewRules.encode(senders).fv())

    /** Whether the server must leave this email in the inbox: its sender was unticked in the clean-up's review. */
    fun keptInInbox(b: MailBacklog?, fromAddress: String?, fromName: String?): Boolean =
        b != null && b.planKeepSenders.isNotEmpty() && MailJunkReviewRules.senderKey(fromAddress, fromName) in b.planKeepSenders

    /** Whether "Mark N read" must leave this email unread: its sender was unticked. */
    fun keptUnread(b: MailBacklog?, fromAddress: String?, fromName: String?): Boolean =
        b != null && b.readKeepSenders.isNotEmpty() && MailJunkReviewRules.senderKey(fromAddress, fromName) in b.readKeepSenders

    // ---- Counts net of his ticks ----

    private fun plan(b: MailBacklog) = MailJunkReviewRules.decode(b.planSenders, PLAN_TIDIES)
    private fun read(b: MailBacklog) = MailJunkReviewRules.decode(b.readSenders, READ_TIDIES)

    /** Emails the clean-up would archive and trash once kept senders are left out (the plan's counts before the list came). */
    fun planMoving(b: MailBacklog): Pair<Int, Int> {
        val archive0 = listOf(MailBacklogKind.NEWSLETTER, MailBacklogKind.RECEIPT, MailBacklogKind.ARCHIVE).sumOf { b.counts[it] ?: 0 }
        val junk0 = b.counts[MailBacklogKind.JUNK] ?: 0
        val list = plan(b) ?: return archive0 to junk0
        val kept = list.filter { it.key in b.planKeepSenders }
        val keptJunk = kept.filter { it.tidy == MailTidy.TRASH }.sumOf { it.count }
        val keptArchive = kept.sumOf { it.count } - keptJunk
        return (archive0 - keptArchive).coerceAtLeast(0) to (junk0 - keptJunk).coerceAtLeast(0)
    }

    /** Emails kept in the inbox by his untick. */
    fun planKeptCount(b: MailBacklog): Int = (plan(b) ?: emptyList()).filter { it.key in b.planKeepSenders }.sumOf { it.count }

    /** How many "Mark N read" would mark once kept senders are left out. */
    fun readMarking(b: MailBacklog): Int {
        val list = read(b) ?: return b.tidyUnread
        val kept = list.filter { it.key in b.readKeepSenders }.sumOf { it.count }
        return (b.tidyUnread - kept).coerceAtLeast(0)
    }

    // ---- The apps' writes ----

    fun planKeptFields(current: Set<String>, key: String, keep: Boolean): Map<String, FieldValue> =
        mapOf(MailBacklogFields.PLAN_KEEP_SENDERS to MailJunkReviewRules.encodeKept(if (keep) current + key else current - key).fv())

    fun readKeptFields(current: Set<String>, key: String, keep: Boolean): Map<String, FieldValue> =
        mapOf(MailBacklogFields.READ_KEEP_SENDERS to MailJunkReviewRules.encodeKept(if (keep) current + key else current - key).fv())

    // ---- The sheets ----

    private fun where(t: MailTidy): String = when (t) {
        MailTidy.NEWSLETTER -> "newsletters → Archive"
        MailTidy.RECEIPT -> "receipts → Archive"
        MailTidy.ARCHIVE -> "notifications and promos → Archive"
        MailTidy.TRASH -> "junk → Trash"
        else -> t.many
    }

    /** The clean-up card's sheet for [b]; null when there's nothing to approve. */
    fun planReview(b: MailBacklog): JunkReview? {
        val card = MailBacklogRules.planCard(b)
        if (card == null && !MailBacklogRules.canApprove(b)) return null
        val title = "Clean up ${b.shownName}"
        val list = plan(b)
        val stays = b.counts[MailBacklogKind.KEEP] ?: 0
        val staysLine = stays.takeIf { it > 0 }?.let { "${emails(it)} ${if (it == 1) "stays" else "stay"} in your inbox: they may need you" }
        if (list == null) {
            return JunkReview(
                cardId = b.id, title = title, summary = card?.why?.substringBefore(" · nothing moves") ?: "", rescues = emptyList(),
                senders = emptyList(), staysLine = staysLine, hint = PLAN_WAITING, partLine = null,
                approveLabel = MailBacklogRules.CARD_YES, canApprove = card != null, note = MailBacklogRules.NOTE,
            )
        }
        val kept = b.planKeepSenders
        val (archive, junk) = planMoving(b)
        val going = archive + junk
        val goingSenders = list.count { it.key !in kept }
        val keptN = planKeptCount(b)
        val summary = listOfNotNull(
            archive.takeIf { it > 0 }?.let { "${g(it)} to archive" },
            junk.takeIf { it > 0 }?.let { "${g(it)} junk to Trash" },
            goingSenders.takeIf { going > 0 }?.let { "from ${senders(it)}" },
            keptN.takeIf { it > 0 }?.let { "${g(it)} kept in your inbox" },
        ).joinToString(" · ").replace(" · from ", " from ").let { if (going == 0) "Nothing to move" + if (it.isEmpty()) "" else " · $it" else it }
        val left = MailJunkReviewRules.leftOut(b.planSenders)
        return JunkReview(
            cardId = b.id, title = title, summary = summary, rescues = emptyList(),
            senders = list.map { s ->
                val isKept = s.key in kept
                JunkReviewRow(
                    key = s.key, title = s.name, meta = listOfNotNull(s.domain, emails(s.count), where(s.tidy)).joinToString(" · "),
                    subjects = emptyList(), scam = false, scamLine = null, kept = isKept, count = s.count,
                    state = when {
                        isKept -> "Stays in your inbox · you kept it"
                        s.tidy == MailTidy.TRASH -> "Going to Trash"
                        else -> "Going to Archive"
                    },
                )
            },
            staysLine = staysLine, hint = PLAN_HINT,
            partLine = left.takeIf { it > 0 }?.let { "${g(it)} more senders weren't listed for room; they move by the same rules." },
            approveLabel = if (going > 0) "Approve · move ${g(going)}" else MailBacklogRules.CARD_YES,
            canApprove = card != null && going > 0,
            note = MailBacklogRules.NOTE,
            sendersLabel = "TO MOVE · ${g(list.size)} ${if (list.size == 1) "SENDER" else "SENDERS"}",
        )
    }

    /** The "Mark N read" card's sheet for [b]; null when there's nothing to approve. */
    fun readReview(b: MailBacklog, nowMs: Long = 0L): JunkReview? {
        if (!MailMarkReadRules.canApprove(b, nowMs)) return null
        val card = MailMarkReadRules.card(b, nowMs)
        val title = "Mark read · ${b.account}"
        val list = read(b)
        if (list == null) {
            return JunkReview(
                cardId = MailMarkReadRules.cardId(b.id), title = title, summary = card?.why?.substringBefore(" · they stay") ?: "",
                rescues = emptyList(), senders = emptyList(), staysLine = null, hint = READ_WAITING, partLine = null,
                approveLabel = MailMarkReadRules.CARD_YES, canApprove = card != null, note = READ_NOTE,
            )
        }
        val kept = b.readKeepSenders
        val marking = readMarking(b)
        val keptN = list.filter { it.key in kept }.sumOf { it.count }
        val goingSenders = list.count { it.key !in kept }
        val summary = listOfNotNull(
            "${g(marking)} to mark read" + if (goingSenders > 0 && marking > 0) " from ${senders(goingSenders)}" else "",
            keptN.takeIf { it > 0 }?.let { "${g(it)} left unread" },
        ).joinToString(" · ")
        val listed = list.sumOf { it.count }
        val left = MailJunkReviewRules.leftOut(b.readSenders)
        return JunkReview(
            cardId = MailMarkReadRules.cardId(b.id), title = title, summary = summary, rescues = emptyList(),
            senders = list.map { s ->
                val isKept = s.key in kept
                JunkReviewRow(
                    key = s.key, title = s.name, meta = listOfNotNull(s.domain, emails(s.count), s.tidy.many).joinToString(" · "),
                    subjects = s.subjects, scam = false, scamLine = null, kept = isKept, count = s.count,
                    state = if (isKept) "Stays unread · you kept it" else "Marked read",
                )
            },
            staysLine = null, hint = READ_HINT,
            partLine = when {
                left > 0 -> "${g(left)} more senders weren't listed for room; they're marked by the same rules."
                b.tidyUnread > listed -> "${emails(b.tidyUnread - listed)} more arrived since the list was made; they're marked by the same rules."
                else -> null
            },
            approveLabel = if (marking > 0) "Mark ${g(marking)} read" else MailMarkReadRules.CARD_YES,
            canApprove = card != null && marking > 0,
            note = READ_NOTE,
            sendersLabel = "TO MARK READ · ${g(list.size)} ${if (list.size == 1) "SENDER" else "SENDERS"}",
        )
    }
}
