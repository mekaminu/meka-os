package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Inbox autopilot, slice 4: the "Archived for you" card (Meka, 2026-10-12: newsletters and receipts auto-archived **but
 * shown to him**). Twice a day, with the digests (12:30 and 18:30, [GroupDigestRules]), Needs you on both apps shows what
 * Autopilot tidied since he last looked: "Tidied 14 · 9 newsletters · 3 receipts and orders · 2 notifications and promos",
 * then each tidy (time, account, what) with **Put back** (the tidy's Undo: the server returns those emails to the inbox
 * at its next check). **Got it** folds the card away on both apps (synced) until the next digest finds new tidies.
 *
 * Gmail and Outlook keep them under Navilife/Newsletters, Navilife/Receipts or Archive. Since 2026-10-16 ("Archived for
 * you shows which emails", slice 1) each tidy also lists its emails by sender ([MailTidiedRules.groups]: "Deliveroo ·
 * 4 promos", each subject with its reason chip and time); a tidy from before then shows its counts only. Non-AI, pure,
 * unit-tested.
 *
 * Seen is one `context_mode` entity (id [ENTITY_ID], ADR-008 addendum 2026-10-13): [ArchivedForYouFields.SEEN_AT]
 * (Int64), LWW, written by either app.
 */
object ArchivedForYouFields {
    const val SEEN_AT = "seenAtMs"
}

/** One tidy on the card. */
data class ArchivedForYouLine(
    val id: String,
    /** "12:40" today, else "Mon 12 Oct · 12:40". */
    val time: String,
    /** "Personal". */
    val account: String,
    /** "3 newsletters → Navilife/Newsletters · 2 receipts and orders → Navilife/Receipts". */
    val detail: String,
    val canPutBack: Boolean,
    /** "Putting back at the next check…" or the server's "Put back 6 emails in the inbox"; null while it stands. */
    val state: String?,
    /** Its emails by sender (empty on a tidy from before 2026-10-16). */
    val emails: List<ArchivedEmailGroup> = emptyList(),
    /** "+ 12 more in this tidy" when it moved more than [emails] shows; else null. */
    val emailsMore: String? = null,
)

data class ArchivedForYouCard(
    /** "Archived for you". */
    val title: String,
    /** "Lunchtime digest" or "Evening digest". */
    val slot: String,
    /** "Tidied 14 · 9 newsletters · 3 receipts and orders · 2 notifications and promos". */
    val headline: String,
    val note: String,
    /** Newest first, at most [ArchivedForYouRules.SHOWN]. */
    val lines: List<ArchivedForYouLine>,
    /** "+ 4 earlier tidies · in All tidied mail" when more than [ArchivedForYouRules.SHOWN]; else null. */
    val moreLine: String?,
    /** What a screen reader says for the card's header. */
    val spoken: String,
    /**
     * 2026-10-18: the same email that came again and again across these tidies, most first, at most
     * [ArchivedForYouRules.REPEATS_SHOWN]: "Came again · GitHub · “[acme/app] Run failed: ci” · 6 times since 09:10".
     */
    val repeats: List<String> = emptyList(),
)

object ArchivedForYouRules {
    const val ENTITY_ID = "mail_archived_seen"
    const val TITLE = "Archived for you"
    const val GOT_IT = "Got it"
    const val PUT_BACK = "Put back"
    const val SHOW = "Show"
    const val HIDE = "Hide"
    const val NOTE = "Still in Gmail and Outlook: newsletters and receipts under their Navilife label, the rest in Archive. " +
        "Put back returns a tidy to the inbox, and it's never tidied again."
    const val GOT_IT_LINE = "Archived for you · seen"
    const val ALL_PUT_BACK = "Nothing left tidied · all put back"
    /** Tidies the card lists; the rest are in Activity. */
    const val SHOWN = 8
    /** A run of the same email shown as "Came again" once it came this often, over two tidies or more. */
    const val REPEAT_MIN = 3
    const val REPEATS_SHOWN = 3
    const val REPEAT_PREFIX = "Came again"
    /** Never reaches further back than this, so a first card after a long gap stays short (Activity keeps 30 days). */
    const val WINDOW_MS = 2 * 86_400_000L

    fun readSeen(fields: Map<String, FieldValue>?): Long? =
        (fields?.get(ArchivedForYouFields.SEEN_AT) as? FieldValue.Int64)?.value?.takeIf { it > 0 }

    fun seenFields(atMs: Long): Map<String, FieldValue> = mapOf(ArchivedForYouFields.SEEN_AT to atMs.fv())

    /** The latest digest time at or before [nowMs]: today's 18:30 or 12:30, else yesterday's 18:30. */
    fun slotStartMs(nowMs: Long, cal: LocalCalendar): Long {
        val day = cal.epochDayOf(nowMs)
        val minute = cal.minuteOfDay(nowMs)
        return when {
            minute >= GroupDigestRules.EVENING_MINUTE -> cal.toEpochMs(day, GroupDigestRules.EVENING_MINUTE)
            minute >= GroupDigestRules.LUNCH_MINUTE -> cal.toEpochMs(day, GroupDigestRules.LUNCH_MINUTE)
            else -> cal.toEpochMs(day - 1, GroupDigestRules.EVENING_MINUTE)
        }
    }

    /**
     * "Tidied 14 · 9 newsletters · 3 receipts and orders · 2 notifications and promos" over the tidies that still stand
     * (put back or being put back aren't counted); a tidy from before slice 4 adds to the total only.
     */
    fun headline(batches: List<MailTidyBatch>): String {
        val standing = batches.filter { it.undoneAtMs == null && it.undoAskedAtMs == null }
        val total = standing.sumOf { it.count }
        if (total == 0) return ALL_PUT_BACK
        // Slice 3d: the receipts' total when the tidies priced them: "3 receipts and orders £84.20" ("… £84.20 on 2").
        val priced = standing.sumOf { it.receiptsPriced }
        val pence = standing.mapNotNull { it.receiptsPence }.takeIf { it.isNotEmpty() }?.sum()
        val parts = listOf(MailTidy.NEWSLETTER, MailTidy.RECEIPT, MailTidy.ARCHIVE, MailTidy.TRASH, MailTidy.JUNK_TRASH, MailTidy.RESCUE).mapNotNull { t ->
            standing.sumOf { it.kinds[t] ?: 0 }.takeIf { it > 0 }?.let { n ->
                val money = if (t == MailTidy.RECEIPT && pence != null && priced > 0) {
                    " " + MailReceiptRules.format(pence) + if (priced < n) " on $priced" else ""
                } else ""
                "$n ${if (n == 1) t.one else t.many}$money"
            }
        }
        return (listOf("Tidied $total") + parts).joinToString(" · ")
    }

    /**
     * The card, or null while there's nothing to show: due once a digest time has come and a tidy from before it hasn't
     * been seen ([seenAtMs], Got it on either app); then it lists every unseen tidy up to now, newest first. Tidies after
     * the latest digest wait for the next one. Nothing older than [WINDOW_MS].
     */
    fun card(batches: List<MailTidyBatch>, seenAtMs: Long?, nowMs: Long, cal: LocalCalendar): ArchivedForYouCard? {
        val slot = slotStartMs(nowMs, cal)
        val floor = maxOf(seenAtMs ?: 0L, nowMs - WINDOW_MS)
        // Slice 8b: the backlog clean-up is his own approved tidy, shown under Mail, not a digest's news.
        val unseen = batches.filter { it.atMs > floor && it.atMs <= nowMs && it.count > 0 && !it.backlog }
        if (unseen.none { it.atMs <= slot }) return null
        val sorted = unseen.sortedWith(compareByDescending<MailTidyBatch> { it.atMs }.thenBy { it.id })
        val today = cal.epochDayOf(nowMs)
        val lines = sorted.take(SHOWN).map { b ->
            val day = cal.epochDayOf(b.atMs)
            val clock = LocalClock.formatMinute(cal.minuteOfDay(b.atMs))
            val (groups, emailsMore) = MailTidiedRules.groups(b, b.emails, nowMs, cal)
            ArchivedForYouLine(
                id = b.id,
                time = if (day == today) clock else "${CivilDate.shortLabel(day)} · $clock",
                account = b.account,
                detail = b.detail ?: b.summary,
                canPutBack = b.canUndo && nowMs - b.atMs <= MailAutopilotRules.UNDO_DAYS * 86_400_000L,
                state = MailAutopilotPanelRules.state(b),
                emails = groups,
                emailsMore = emailsMore,
            )
        }
        val more = sorted.size - lines.size
        val slotLabel = GroupDigestRules.slotLabel(cal.minuteOfDay(slot)) ?: "Evening digest"
        val head = headline(unseen)
        val again = repeats(unseen.filter { it.undoneAtMs == null && it.undoAskedAtMs == null }, nowMs, cal)
        return ArchivedForYouCard(
            title = TITLE,
            slot = slotLabel,
            headline = head,
            note = NOTE,
            lines = lines,
            moreLine = if (more > 0) "+ $more earlier ${if (more == 1) "tidy" else "tidies"} · in ${ArchivedAllRules.TITLE}" else null,
            spoken = "$TITLE. $head." + again.joinToString("") { " $it." },
            repeats = again,
        )
    }

    /**
     * The same email again and again across [batches] (each check tidies what came since the last, so a burst of CI
     * failures is one email per tidy): by sender and [MailTidiedRules.repeatKey], at least [REPEAT_MIN] of them over two
     * tidies or more, most first; "Came again · GitHub · “Run failed: ci” · 6 times since 09:10" (the newest subject, cut).
     */
    fun repeats(batches: List<MailTidyBatch>, nowMs: Long, cal: LocalCalendar): List<String> {
        val today = cal.epochDayOf(nowMs)
        val runs = batches.flatMap { b -> b.emails.map { b.id to it } }
            .mapNotNull { (id, e) -> MailTidiedRules.repeatKey(e.subject)?.let { k -> Triple(e.from.lowercase() + "\u0000" + k, id, e) } }
            .groupBy { it.first }.values
            .filter { r -> r.size >= REPEAT_MIN && r.map { it.second }.distinct().size >= 2 }
            .map { r -> r.map { it.third }.sortedByDescending { it.receivedAtMs } }
            .sortedWith(compareByDescending<List<MailTidiedEmail>> { it.size }.thenByDescending { it.first().receivedAtMs })
        return runs.take(REPEATS_SHOWN).map { r ->
            val newest = r.first()
            val first = r.last().receivedAtMs
            val day = cal.epochDayOf(first)
            val clock = LocalClock.formatMinute(cal.minuteOfDay(first))
            val since = if (day == today) clock else "${CivilDate.shortLabel(day)} · $clock"
            val subject = newest.subject.let { if (it.length <= MailTidiedRules.MAX_REPEAT_SUBJECT) it else it.take(MailTidiedRules.MAX_REPEAT_SUBJECT - 1).trimEnd() + "…" }
            "$REPEAT_PREFIX · ${newest.from} · “$subject” · ${r.size} times since $since"
        }
    }
}
