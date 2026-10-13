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
 * Which emails a tidy moved stays on the server (their ids only), so the card lists tidies, not emails; Gmail and Outlook
 * keep them under Navilife/Newsletters, Navilife/Receipts or Archive. Non-AI, pure, unit-tested.
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
    /** "+ 4 earlier tidies · in Activity" when more than [ArchivedForYouRules.SHOWN]; else null. */
    val moreLine: String?,
    /** What a screen reader says for the card's header. */
    val spoken: String,
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
        val parts = listOf(MailTidy.NEWSLETTER, MailTidy.RECEIPT, MailTidy.ARCHIVE, MailTidy.TRASH).mapNotNull { t ->
            standing.sumOf { it.kinds[t] ?: 0 }.takeIf { it > 0 }?.let { n -> "$n ${if (n == 1) t.one else t.many}" }
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
        val unseen = batches.filter { it.atMs > floor && it.atMs <= nowMs && it.count > 0 }
        if (unseen.none { it.atMs <= slot }) return null
        val sorted = unseen.sortedWith(compareByDescending<MailTidyBatch> { it.atMs }.thenBy { it.id })
        val today = cal.epochDayOf(nowMs)
        val lines = sorted.take(SHOWN).map { b ->
            val day = cal.epochDayOf(b.atMs)
            val clock = LocalClock.formatMinute(cal.minuteOfDay(b.atMs))
            ArchivedForYouLine(
                id = b.id,
                time = if (day == today) clock else "${CivilDate.shortLabel(day)} · $clock",
                account = b.account,
                detail = b.detail ?: b.summary,
                canPutBack = b.canUndo && nowMs - b.atMs <= MailAutopilotRules.UNDO_DAYS * 86_400_000L,
                state = MailAutopilotPanelRules.state(b),
            )
        }
        val more = sorted.size - lines.size
        val slotLabel = GroupDigestRules.slotLabel(cal.minuteOfDay(slot)) ?: "Evening digest"
        val head = headline(unseen)
        return ArchivedForYouCard(
            title = TITLE,
            slot = slotLabel,
            headline = head,
            note = NOTE,
            lines = lines,
            moreLine = if (more > 0) "+ $more earlier ${if (more == 1) "tidy" else "tidies"} · in Activity" else null,
            spoken = "$TITLE. $head.",
        )
    }
}
