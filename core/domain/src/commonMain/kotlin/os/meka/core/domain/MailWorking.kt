package os.meka.core.domain

/**
 * Approve must land, the last slice (Meka 2026-10-25 15:10: "I approved marking emails as read but my count hasn't
 * changed"): after his Approve on Needs you the card leaves, so Needs you keeps a quiet **Working on it** section saying
 * what Navilife is doing with each account's mail, counting up as the server reports: "Marking read · Personal Gmail" ·
 * "500 so far · people, banks, schools and anything needing you stay unread", then "Marked read · Personal Gmail" for
 * [SHOW_DONE_MS] after it finished. The same for the backlog clean-up ("Cleaning up · 1,200 of 3,256 moved…"). A tap
 * opens Mail. Context, not a decision: the body weight, no buttons. Pure; one line per account and job.
 */
object MailWorkingRules {
    const val LABEL = "Working on it"
    /** A finished job stays this long, so he sees it land. */
    const val SHOW_DONE_MS = 6 * 60 * 60_000L
    const val SENDING = "on its way to Navilife…"
    const val HAS_IT = "Navilife has it · starts at its next check"
    const val PAUSED = "paused with everything Navilife does on its own"
    const val OPEN_LABEL = "Open Mail"

    private fun n(v: Int) = SpeechRules.grouped(v.toLong())

    fun readId(b: MailBacklog) = "work-read-${b.id}"
    fun cleanId(b: MailBacklog) = "work-clean-${b.id}"

    /**
     * Newest plan per account ([MailBacklogRules.newestPerAccount]), in the order given; for each, the clean-up's line
     * then Mark read's. An approval treated as lost isn't here (its card is back on the stack with the reason).
     */
    fun lines(backlogs: List<MailBacklog>, batches: List<MailTidyBatch> = emptyList(), paused: Boolean = false, nowMs: Long): List<MeanwhileLine> =
        MailBacklogRules.newestPerAccount(backlogs).flatMap { b ->
            val batch = batches.firstOrNull { t ->
                t.backlog && (if (b.accountKey != null && t.accountKey != null) t.accountKey == b.accountKey else t.account == b.account)
            }
            listOfNotNull(cleanLine(b, batch, paused, nowMs), readLine(b, paused, nowMs))
        }

    /** Mark read's line, or null when not asked, lost, or finished more than [SHOW_DONE_MS] ago. */
    fun readLine(b: MailBacklog, paused: Boolean, nowMs: Long): MeanwhileLine? {
        val asked = b.markReadApprovedAtMs ?: return null
        if (MailMarkReadRules.running(b)) {
            if (MailMarkReadRules.lost(b, nowMs)) return null
            val meta = when {
                paused -> PAUSED
                b.markedRead > 0 -> "${n(b.markedRead)} so far · ${MailMarkReadRules.KEEPS_UNREAD}"
                MailMarkReadRules.unseen(b) -> SENDING
                else -> HAS_IT
            }
            return MeanwhileLine(readId(b), "Marking read · ${b.shownName}", meta)
        }
        val done = b.markReadFinishedAtMs ?: return null
        if (done < asked || nowMs - done > SHOW_DONE_MS) return null
        val meta = if (b.markedRead > 0) {
            "${n(b.markedRead)} ${if (b.markedRead == 1) "newsletter or promo" else "newsletters and promos"} · they stay in the inbox"
        } else "Nothing left to mark read"
        return MeanwhileLine(readId(b), "Marked read · ${b.shownName}", meta)
    }

    /** The clean-up's line once approved, or null (not approved, lost, put back, or finished long ago). */
    fun cleanLine(b: MailBacklog, batch: MailTidyBatch?, paused: Boolean, nowMs: Long): MeanwhileLine? {
        val approved = b.approvedAtMs ?: return null
        val ready = b.readyAtMs ?: return null
        if (approved < ready && b.applied == 0) return null
        if (batch?.undoAskedAtMs != null || batch?.undoneAtMs != null) return null
        b.finishedAtMs?.let { done ->
            if (nowMs - done > SHOW_DONE_MS) return null
            return MeanwhileLine(cleanId(b), "Cleaned up · ${b.shownName}",
                "${n(b.applied)} older ${if (b.applied == 1) "email" else "emails"} moved · Undo in Mail for 30 days")
        }
        if (b.applied == 0 && MailBacklogRules.lost(b, nowMs)) return null
        val meta = when {
            paused -> PAUSED
            b.applied > 0 -> "${n(b.applied)} of ${n(b.toMove)} moved…"
            MailBacklogRules.unseen(b) -> SENDING
            else -> HAS_IT
        }
        return MeanwhileLine(cleanId(b), "Cleaning up · ${b.shownName}", meta)
    }
}
