package os.meka.core.domain

/**
 * The inbox's unread count went up (Meka, 2026-10-18 14:33: "Personal Gmail · Inbox 2,235 unread" while Today had said
 * "Personal · 1,075 unread"). Found in the deploy run's Mail autopilot report: both Gmail accounts "error (apioff) · ok
 * never" (the Gmail API is still off in Navilife's Google Cloud project, Needs Meka #4), so Navilife has never read or
 * tidied either Gmail inbox; the only account it reads is Outlook, which carries the **Personal** label (inbox 12,437 ·
 * 136 unread, down from 1,075 after Mark read). Today's line said "Personal" for the Outlook account and left the
 * unread Gmail accounts out without a word, so it read as if the personal Gmail had been tidied. Non-AI, pure.
 *
 * This names each account by label and provider wherever the label alone could mislead ("Personal Outlook"), says on
 * Today which accounts Navilife can't read, and gives Health one row per mail account so drift is visible:
 * "Inbox 12,437 · 136 unread · tidied today 14 · clean-up 3,505 done · checked 3 min ago".
 */
object MailHealthRules {
    const val KEY_PREFIX = "mail:"
    /** A check older than this is worth a look (the server checks every few minutes). */
    const val STALE_MS = 60 * 60_000L
    /** "Undo in the last 72 h" in the report. */
    const val UNDO_WINDOW_MS = 72 * 3_600_000L
    const val DAY_MS = 24 * 3_600_000L

    private fun n(v: Long) = SpeechRules.grouped(v)
    private fun n(v: Int) = SpeechRules.grouped(v.toLong())

    /** The label as the server stores it, shown ("Personal"); "Mail" when none was set (the server's own default). */
    fun labelOf(a: MailAccountFacts): String = MailLabel.of(a.label)?.label ?: "Mail"

    /**
     * "Personal Gmail", "Personal Outlook", "Business Gmail"; just "Outlook" when the label is the provider's own name.
     */
    fun name(label: String, provider: String): String {
        val p = MailProviders.name(provider)
        return if (label.equals(p, ignoreCase = true)) p else "$label $p"
    }

    fun name(a: MailAccountFacts): String = name(labelOf(a), a.provider)

    /** Navilife has never read it, or its last check failed and it hasn't read it for [STALE_MS]. */
    fun unreadable(a: MailAccountFacts, nowMs: Long): Boolean =
        a.lastSyncAtMs == null || (a.status != "ok" && nowMs - a.lastSyncAtMs > STALE_MS)

    /** The account a backlog (or tidy) labelled [label] belongs to: the read one first when two share a label. */
    fun accountFor(label: String, accounts: List<MailAccountFacts>): MailAccountFacts? =
        accounts.filter { labelOf(it) == label }.sortedByDescending { it.lastSyncAtMs ?: -1 }.firstOrNull()

    /**
     * The backlog clean-up's state in a few words: "clean-up 3,505 done" · "clean-up 1,200 of 3,505 done · 2,305
     * left" · "clean-up waiting for your Approve (3,505)" · "clean-up undone · 3,505 put back"; null when there's none.
     */
    fun backlogState(b: MailBacklog?): String? {
        b ?: return null
        return when {
            !b.ready -> "clean-up being planned"
            b.putBack > 0 -> "clean-up undone · ${n(b.putBack)} put back"
            b.toMove == 0 && b.applied == 0 -> null
            b.finishedAtMs != null -> "clean-up ${n(b.applied)} done"
            MailBacklogRules.unseen(b) -> "clean-up approved · not seen by Navilife yet"
            b.approved -> "clean-up ${n(b.applied)} of ${n(b.toMove)} done · ${n((b.toMove - b.applied).coerceAtLeast(0))} left"
            else -> "clean-up waiting for your Approve (${n(b.toMove)})"
        }
    }

    /**
     * Approve must land (2026-10-25): Mark read's state in a few words: "mark read running · 500 so far" · "mark read
     * finished · 1,071 marked" · "mark read asked · not seen by Navilife yet" · "mark read not asked (380 could be)";
     * null when there's nothing to say.
     */
    fun markReadState(b: MailBacklog?): String? {
        b ?: return null
        if (b.markReadApprovedAtMs == null) return if (b.tidyUnread >= MailMarkReadRules.MIN) "mark read not asked (${n(b.tidyUnread)} could be)" else null
        return when {
            MailMarkReadRules.unseen(b) -> "mark read asked · not seen by Navilife yet"
            MailMarkReadRules.running(b) -> "mark read running · ${n(b.markedRead)} so far"
            else -> "mark read finished · ${n(b.markedRead)} marked"
        }
    }

    /**
     * The account's own backlog: by the server's account key; one written before the key existed by its label, and
     * only for the account [mine] says reads that label.
     */
    fun backlogFor(a: MailAccountFacts, backlogs: List<MailBacklog>, mine: Boolean): MailBacklog? {
        val key = MailAccountRules.key(a.provider, a.email)
        return backlogs.filter { it.accountKey == key }.maxByOrNull { it.startedAtMs }
            ?: if (mine) backlogs.filter { it.accountKey == null && it.account == labelOf(a) }.maxByOrNull { it.startedAtMs } else null
    }

    /** How many emails Autopilot tidied for [label] since [sinceMs] (new mail only: not the clean-up, not undone). */
    fun tidiedSince(label: String, tidies: List<MailTidyBatch>, sinceMs: Long): Int =
        tidies.filter { it.account == label && !it.backlog && it.undoneAtMs == null && it.atMs >= sinceMs }.sumOf { it.count }

    /** Undos asked for [label] since [sinceMs] (an Undo puts mail back in the inbox, unread). */
    fun undosSince(label: String, tidies: List<MailTidyBatch>, sinceMs: Long): Int =
        tidies.count { it.account == label && (it.undoAskedAtMs ?: it.undoneAtMs ?: -1) >= sinceMs }

    /**
     * The deploy report's extra words for one account (counts only): "tidied 24h 14 · clean-up 3,505 done · marked
     * 1,071 read · undo 72h 0".
     */
    fun reportExtra(label: String, backlog: MailBacklog?, tidies: List<MailTidyBatch>, nowMs: Long): String = listOfNotNull(
        "tidied 24h ${tidiedSince(label, tidies, nowMs - DAY_MS)}",
        backlogState(backlog),
        markReadState(backlog),
        "undo 72h ${undosSince(label, tidies, nowMs - UNDO_WINDOW_MS)}",
    ).joinToString(" · ")

    /**
     * Health's row per mail account, in label order: "Personal Outlook · Inbox 12,437 · 136 unread · tidied today 14 ·
     * clean-up 3,505 done · checked 3 min ago"; an account Navilife can't read says why (the Gmail API being off is
     * critical: its mail is never read or tidied). Null [accounts]: couldn't ask (one "Couldn't check" row).
     */
    fun healthRows(
        accounts: List<MailAccountFacts>?, backlogs: List<MailBacklog>, tidies: List<MailTidyBatch>, nowMs: Long, cal: LocalCalendar,
    ): List<HealthRow> {
        val list = accounts ?: return listOf(HealthRow(KEY_PREFIX, "Mail", "Couldn't check", HealthState.UNKNOWN))
        val today = cal.epochDayOf(nowMs)
        val order = { a: MailAccountFacts -> MailLabel.entries.indexOfFirst { it.name == a.label }.let { if (it < 0) MailLabel.entries.size else it } }
        return list.filter { MailProviders.isMail(it.provider) }.sortedWith(compareBy(order).thenBy { it.provider }).map { a ->
            val key = KEY_PREFIX + a.provider + ":" + a.email
            val title = "Mail · " + name(a)
            val label = labelOf(a)
            val mine = accountFor(label, list) == a
            when {
                a.status == "needs_reconnect" ->
                    HealthRow(key, title, "Sign-in ended · Reconnect it in Mail · its mail isn't read or tidied until then", HealthState.BAD)
                unreadable(a, nowMs) -> {
                    val never = if (a.lastSyncAtMs == null) "Never read yet, so nothing here has been tidied · " else ""
                    val why = MailCheckRules.errorLine(a.provider, a.why, a.lastSyncAtMs, nowMs)
                    HealthRow(key, title, never + why, HealthState.BAD)
                }
                else -> {
                    val backlog = backlogFor(a, backlogs, mine)
                    val unread = a.inboxUnread ?: backlog?.inboxUnread?.toLong()
                    val tidiedToday = if (mine) tidies.filter { it.account == label && !it.backlog && it.undoneAtMs == null && cal.epochDayOf(it.atMs) == today }.sumOf { it.count } else 0
                    val parts = listOfNotNull(
                        a.inboxTotal?.let { "Inbox ${n(it)}" },
                        // Approve must land, (5): the inbox's own unread (a provider's app may count every folder).
                        unread?.let { "${n(it)} inbox unread" },
                        "tidied today ${n(tidiedToday)}",
                        backlogState(backlog),
                        markReadState(backlog),
                        a.lastSyncAtMs?.let { "checked ${MailAccountRules.ago(it, nowMs)}" },
                    )
                    val stale = a.lastSyncAtMs != null && nowMs - a.lastSyncAtMs > STALE_MS
                    HealthRow(key, title, parts.joinToString(" · "), if (stale || a.status != "ok") HealthState.WARN else HealthState.OK)
                }
            }
        }
    }

    /** "Personal Gmail and Business Gmail" (Today's "not read yet" words), or null when every account is read. */
    fun unreadableNames(accounts: List<MailAccountFacts>?, nowMs: Long): String? {
        val names = accounts.orEmpty().filter { MailProviders.isMail(it.provider) && unreadable(it, nowMs) }.map(::name).distinct()
        if (names.isEmpty()) return null
        return if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}
