package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Inbox autopilot, slice 6a: **the emails that need him, as cards in Needs you** (Meka, 2026-10-12: "the inbox holds
 * only what needs him; each is a card"). Non-AI, pure, unit-tested; no regexes.
 *
 * The server publishes each email it sorted as Needs you that is still in the inbox and came in the last [WINDOW_MS]
 * (at most [MAX_PER_ACCOUNT] per account, newest first) as a `mail_item` entity: the account's label, the sender's
 * name (never the address), the subject, the provider's short preview of the text (Gmail's snippet, Outlook's
 * bodyPreview, cleaned and cut to [MAX_PREVIEW]), when it came and how many emails of its thread MEKA holds. An email
 * that leaves the inbox or Needs you gets [MailItemFields.GONE_AT] and its card goes.
 *
 * On the card: **Done** (an app writes [MailItemFields.DONE_ASKED_AT]; the server archives it at its next check and
 * writes [MailItemFields.DONE_AT]); Undo writes [MailItemFields.UNDO_ASKED_AT] (before the server acted nothing
 * happens; after, the server puts it back in the inbox and writes [MailItemFields.UNDONE_AT]); **Snooze** hides the
 * card until tomorrow 08:00 ([MailItemFields.SNOOZED_UNTIL], synced, 0 clears it). Slice 6b-1: **Task** makes a task
 * from the email ([taskTitle], [taskNotes], id [taskId] so two devices converge on one) and **Waiting for** puts it in
 * Waiting for (who = the sender, chase in [ListRules.DEFAULT_CHASE_DAYS] days); either one is a Done as well (the email
 * is archived at the server's next check) and records what it made ([MailItemFields.TASK_ID] /
 * [MailItemFields.WAITING_ID]); Undo removes what it made and brings the card back. Slice 6b-2a: **Reply** — he writes
 * the reply on the card and taps Send ([MailItemFields.REPLY_TEXT], [MailItemFields.REPLY_ASKED_AT]); the server sends
 * it from the account the email came to, in its thread, at its next check once [REPLY_HOLD_MS] have passed (so Undo
 * stops it), writes [MailItemFields.REPLY_SENT_AT] and archives the email; a send that fails writes
 * [MailItemFields.REPLY_FAILED_AT] and the card comes back with his words kept. Nothing is sent without his tap on Send.
 */
object MailItemFields {
    // The server's.
    const val ACCOUNT = "account"
    const val FROM = "from"
    const val SUBJECT = "subject"
    const val PREVIEW = "preview"
    const val AT = "receivedAtMs"
    const val THREAD = "threadCount"
    /** It left the inbox or Needs you (0 when it is back). */
    const val GONE_AT = "goneAtMs"
    /** The server archived it after a Done. */
    const val DONE_AT = "doneAtMs"
    /** The server put it back after an Undo. */
    const val UNDONE_AT = "undoneAtMs"
    // An app's.
    const val DONE_ASKED_AT = "doneAskedAtMs"
    const val UNDO_ASKED_AT = "undoAskedAtMs"
    const val SNOOZED_UNTIL = "snoozedUntilMs"
    /** Slice 6b-1: the task made from it ("" once undone). */
    const val TASK_ID = "taskId"
    /** Slice 6b-1: the Waiting for item made from it ("" once undone). */
    const val WAITING_ID = "waitingId"
    /** Slice 6b-2a: the reply he wrote (an app's; the server reads it only to send it, never logs it). */
    const val REPLY_TEXT = "replyText"
    /** Slice 6b-2a: when he tapped Send (an app's). */
    const val REPLY_ASKED_AT = "replyAskedAtMs"
    /** Slice 6b-2a: the server sent it (the server's). */
    const val REPLY_SENT_AT = "replySentAtMs"
    /** Slice 6b-2a: the server couldn't send it (the server's), and why in a few words. */
    const val REPLY_FAILED_AT = "replyFailedAtMs"
    const val REPLY_FAILED_WHY = "replyFailedWhy"
    /** Slice 6b-3: the chase he sent from Time to chase (an app's; the server reads it only to send it). */
    const val CHASE_TEXT = "chaseText"
    /** Slice 6b-3: when he tapped Send on the chase, took it back, or put a failed one aside (an app's). */
    const val CHASE_ASKED_AT = "chaseAskedAtMs"
    const val CHASE_UNDO_AT = "chaseUndoAtMs"
    const val CHASE_SEEN_AT = "chaseSeenAtMs"
    /** Slice 6b-3: the server sent the chase, or couldn't and why (the server's). */
    const val CHASE_SENT_AT = "chaseSentAtMs"
    const val CHASE_FAILED_AT = "chaseFailedAtMs"
    const val CHASE_FAILED_WHY = "chaseFailedWhy"
}

data class MailItem(
    val id: String,
    /** "Personal", "Business", "Outlook". */
    val account: String,
    val from: String,
    val subject: String,
    val preview: String?,
    val receivedAtMs: Long,
    val threadCount: Int,
    val goneAtMs: Long?,
    val doneAtMs: Long?,
    val undoneAtMs: Long?,
    val doneAskedAtMs: Long?,
    val undoAskedAtMs: Long?,
    val snoozedUntilMs: Long?,
    /** Slice 6b-1: the task made from it, or null. */
    val taskId: String? = null,
    /** Slice 6b-1: the Waiting for item made from it, or null. */
    val waitingId: String? = null,
    /** Slice 6b-2a: his reply, when he tapped Send, and what the server did with it. */
    val replyText: String? = null,
    val replyAskedAtMs: Long? = null,
    val replySentAtMs: Long? = null,
    val replyFailedAtMs: Long? = null,
    val replyFailedWhy: String? = null,
    /** Slice 6b-3: the chase of the Waiting for item made from it, and what the server did with it. */
    val chaseText: String? = null,
    val chaseAskedAtMs: Long? = null,
    val chaseUndoAtMs: Long? = null,
    val chaseSeenAtMs: Long? = null,
    val chaseSentAtMs: Long? = null,
    val chaseFailedAtMs: Long? = null,
    val chaseFailedWhy: String? = null,
)

/** One email on the card. */
data class MailNeedsRow(
    val id: String,
    /** "Sam Taylor". */
    val from: String,
    /** "Personal · 09:12" or "Business · yesterday 18:40 · 3 in this thread". */
    val line: String,
    val subject: String,
    /** The first words of the email, or null. */
    val preview: String?,
    /** The business account's mail wears a Business chip. */
    val business: Boolean,
    /** What a screen reader says for the row. */
    val spoken: String,
    /** Slice 6b-2a: "Reply didn't send · Gmail didn't take it · your words are kept" when the last Send failed, else null. */
    val notice: String? = null,
    /** Slice 6b-2a: the words of a reply that didn't send, to start the editor with; null otherwise. */
    val replyDraft: String? = null,
    /** Slice 6b-2a: "Sam Taylor" — who Reply answers (the line under the editor names them and the account). */
    val replyLine: String = "",
)

data class MailNeedsCard(
    /** "From your mail". */
    val title: String,
    /** "3 emails need you". */
    val headline: String,
    /** Newest first, at most [MailNeedsRules.SHOWN]. */
    val rows: List<MailNeedsRow>,
    /** "+ 2 more in Gmail and Outlook" or null. */
    val moreLine: String?,
    /** "2 snoozed till tomorrow 08:00" or null. */
    val snoozedLine: String?,
)

object MailNeedsRules {
    const val TITLE = "From your mail"
    const val DONE = "Done"
    const val SNOOZE = "Snooze"
    const val TASK = "Task"
    const val WAITING = "Waiting for"
    const val REPLY = "Reply"
    const val SEND = "Send"
    const val CANCEL = "Cancel"
    const val REPLY_HINT = "Write your reply"
    /** Slice 6b-2a: the server waits this long after Send before sending, so Undo on the bar stops it. */
    const val REPLY_HOLD_MS = 20_000L
    const val MAX_REPLY = 4_000
    const val REPLY_EMPTY = "Write your reply first"
    const val REPLY_TOO_LONG = "Keep it under 4,000 characters"
    const val TASK_LINE = "Task added · email archived at Navilife's next check"
    const val WAITING_LINE = "In Waiting for · chase in 3 days · email archived at Navilife's next check"
    const val BUSINESS_CHIP = "Business"
    const val DONE_LINE = "Done · archived at Navilife's next check"
    const val SNOOZED_LINE = "Snoozed till tomorrow 08:00"
    const val UNKNOWN = "Couldn't find that email"
    /** An email older than this isn't a card (the backlog is slice 8's). */
    const val WINDOW_MS = 14 * 86_400_000L
    const val MAX_PER_ACCOUNT = 20
    const val MAX_PREVIEW = 160
    const val MAX_SUBJECT = 200
    const val MAX_FROM = 60
    const val SHOWN = 5
    /** Snooze: until this time tomorrow. */
    const val SNOOZE_MINUTE = 8 * 60

    /** One entity per account and email, so a retried write lands on the same one. */
    fun itemId(accountId: String, messageId: String): String = "mi" + ActivityRules.fnv64("mailitem:$accountId:$messageId")

    /**
     * A provider's preview made fit for a card: HTML entities read, invisible characters dropped, whitespace run
     * together, cut at a word to [MAX_PREVIEW] with "…". Null when nothing is left.
     */
    fun cleanPreview(raw: String?): String? {
        val s = raw ?: return null
        val decoded = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi in (i + 2)..(i + 9)) {
                    val e = entity(s.substring(i + 1, semi))
                    if (e != null) { decoded.append(e); i = semi + 1; continue }
                }
            }
            when {
                c == '​' || c == '‌' || c == '‍' || c == '﻿' || c == '͏' || c == '­' -> {}
                c.isWhitespace() || c == ' ' -> decoded.append(' ')
                c.code < 0x20 -> {}
                else -> decoded.append(c)
            }
            i++
        }
        val words = decoded.toString().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val out = StringBuilder()
        for (w in words) {
            val next = if (out.isEmpty()) w.length else out.length + 1 + w.length
            if (next > MAX_PREVIEW) {
                if (out.isEmpty()) out.append(w.take(MAX_PREVIEW - 1))
                return out.toString().trimEnd(',', '.', ';', ':', '-') + "…"
            }
            if (out.isNotEmpty()) out.append(' ')
            out.append(w)
        }
        return out.toString()
    }

    private fun entity(name: String): String? = when (name.lowercase()) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos", "#39", "#x27" -> "'"
        "nbsp", "#160", "#xa0" -> " "
        "zwnj", "#8204", "zwj", "#8205", "#8203", "#x200b", "#847", "#x34f" -> ""
        "rsquo", "lsquo", "#8217", "#8216" -> "'"
        "rdquo", "ldquo", "#8220", "#8221" -> "\""
        "ndash", "#8211" -> "–"
        "mdash", "#8212" -> "—"
        "hellip", "#8230" -> "…"
        "pound", "#163" -> "£"
        else -> null
    }

    /** What the server publishes for one email (Needs you, in the inbox, inside the window). */
    fun serverFields(account: String, fromName: String?, fromAddress: String?, subject: String?, preview: String?, receivedAtMs: Long, threadCount: Int): Map<String, FieldValue> =
        mapOf(
            MailItemFields.ACCOUNT to account.fv(),
            MailItemFields.FROM to MailUnsubscribeRules.displayName(fromName, fromAddress).take(MAX_FROM).fv(),
            MailItemFields.SUBJECT to (subject?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_SUBJECT) ?: "(no subject)").fv(),
            MailItemFields.PREVIEW to (cleanPreview(preview) ?: "").fv(),
            MailItemFields.AT to receivedAtMs.fv(),
            MailItemFields.THREAD to threadCount.coerceAtLeast(1).toLong().fv(),
            MailItemFields.GONE_AT to 0L.fv(),
        )

    /** What the server writes once an email has left the inbox or Needs you (its card goes). */
    fun goneFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailItemFields.GONE_AT to nowMs.fv())

    fun read(id: String, f: Map<String, FieldValue>): MailItem? {
        fun text(k: String) = (f[k] as? FieldValue.Text)?.value?.takeIf { it.isNotBlank() }
        fun ms(k: String) = (f[k] as? FieldValue.Int64)?.value?.takeIf { it > 0 }
        return MailItem(
            id = id,
            account = text(MailItemFields.ACCOUNT) ?: return null,
            from = text(MailItemFields.FROM) ?: return null,
            subject = text(MailItemFields.SUBJECT) ?: return null,
            preview = text(MailItemFields.PREVIEW),
            receivedAtMs = ms(MailItemFields.AT) ?: return null,
            threadCount = (f[MailItemFields.THREAD] as? FieldValue.Int64)?.value?.toInt()?.coerceAtLeast(1) ?: 1,
            goneAtMs = ms(MailItemFields.GONE_AT),
            doneAtMs = ms(MailItemFields.DONE_AT),
            undoneAtMs = ms(MailItemFields.UNDONE_AT),
            doneAskedAtMs = ms(MailItemFields.DONE_ASKED_AT),
            undoAskedAtMs = ms(MailItemFields.UNDO_ASKED_AT),
            snoozedUntilMs = ms(MailItemFields.SNOOZED_UNTIL),
            taskId = text(MailItemFields.TASK_ID),
            waitingId = text(MailItemFields.WAITING_ID),
            replyText = (f[MailItemFields.REPLY_TEXT] as? FieldValue.Text)?.value?.takeIf { it.isNotBlank() },
            replyAskedAtMs = ms(MailItemFields.REPLY_ASKED_AT),
            replySentAtMs = ms(MailItemFields.REPLY_SENT_AT),
            replyFailedAtMs = ms(MailItemFields.REPLY_FAILED_AT),
            replyFailedWhy = text(MailItemFields.REPLY_FAILED_WHY),
            chaseText = (f[MailItemFields.CHASE_TEXT] as? FieldValue.Text)?.value?.takeIf { it.isNotBlank() },
            chaseAskedAtMs = ms(MailItemFields.CHASE_ASKED_AT),
            chaseUndoAtMs = ms(MailItemFields.CHASE_UNDO_AT),
            chaseSeenAtMs = ms(MailItemFields.CHASE_SEEN_AT),
            chaseSentAtMs = ms(MailItemFields.CHASE_SENT_AT),
            chaseFailedAtMs = ms(MailItemFields.CHASE_FAILED_AT),
            chaseFailedWhy = text(MailItemFields.CHASE_FAILED_WHY),
        )
    }

    /**
     * He tapped Done (or Task, Waiting for, Send on either app) and hasn't taken it back since. A reply the server
     * couldn't send undoes the Done it came with, so the card comes back with his words.
     */
    fun done(i: MailItem): Boolean {
        val asked = i.doneAskedAtMs ?: return false
        if (i.replyFailedAtMs != null && i.replyAskedAtMs != null && i.replyFailedAtMs >= i.replyAskedAtMs && asked <= i.replyFailedAtMs) return false
        return i.undoAskedAtMs == null || i.undoAskedAtMs < asked
    }

    /** The server should archive it now: Done, any reply sent first, and not archived since that Done. */
    fun archiveDue(i: MailItem): Boolean = done(i) && !replyWaiting(i) && (i.doneAtMs == null || i.doneAtMs < i.doneAskedAtMs!!)

    // ---- Slice 6b-2a: Reply, sent only on his tap ----

    /** What's wrong with [text] as a reply, or null when it can be sent. */
    fun replyProblem(text: String): String? = when {
        text.isBlank() -> REPLY_EMPTY
        text.trim().length > MAX_REPLY -> REPLY_TOO_LONG
        else -> null
    }

    /** Send: a Done that carries his words; the server sends them once [REPLY_HOLD_MS] have passed. */
    fun replyFields(text: String, nowMs: Long): Map<String, FieldValue> =
        doneFields(nowMs) + (MailItemFields.REPLY_TEXT to text.trim().fv()) + (MailItemFields.REPLY_ASKED_AT to nowMs.fv())

    /** "Reply sending from Personal at Navilife's next check · Undo stops it" — the bar's line after Send. */
    fun replySendingLine(account: String): String = "Reply sending from $account at Navilife's next check · Undo stops it"

    fun replySent(i: MailItem): Boolean = i.replyAskedAtMs != null && i.replySentAtMs != null && i.replySentAtMs >= i.replyAskedAtMs

    fun replyFailed(i: MailItem): Boolean = i.replyAskedAtMs != null && i.replyFailedAtMs != null && i.replyFailedAtMs >= i.replyAskedAtMs

    /** He tapped Send, hasn't undone it, and the server hasn't sent it or given up on it yet. */
    fun replyWaiting(i: MailItem): Boolean {
        val asked = i.replyAskedAtMs ?: return false
        if (replySent(i) || replyFailed(i) || i.replyText.isNullOrBlank()) return false
        return i.undoAskedAtMs == null || i.undoAskedAtMs < asked
    }

    /** The server should send it now: waiting, and [REPLY_HOLD_MS] have passed since Send (Undo's window). */
    fun replyDue(i: MailItem, nowMs: Long): Boolean = replyWaiting(i) && nowMs - i.replyAskedAtMs!! >= REPLY_HOLD_MS

    /** What the server writes once the provider took the reply. */
    fun replySentFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailItemFields.REPLY_SENT_AT to nowMs.fv())

    /** What the server writes when it couldn't send it ([why]: a few words, never the reply). The card comes back. */
    fun replyFailedFields(nowMs: Long, why: String): Map<String, FieldValue> =
        mapOf(MailItemFields.REPLY_FAILED_AT to nowMs.fv(), MailItemFields.REPLY_FAILED_WHY to why.take(80).fv(), MailItemFields.GONE_AT to 0L.fv())

    /** Why a reply didn't send, as the server says it. */
    const val WHY_GONE = "the email has gone from the inbox"
    fun whyRefused(provider: String): String = "$provider didn't take it"
    fun whyUnreachable(provider: String): String = "couldn't reach $provider"

    private fun replyNotice(i: MailItem): String? =
        if (replyFailed(i) && !done(i)) "Reply didn't send · ${i.replyFailedWhy ?: "try again"} · your words are kept" else null

    /** The server should put it back now: Done was undone after the server had archived it, and not put back since. */
    fun putBackDue(i: MailItem): Boolean {
        val archived = i.doneAtMs ?: return false
        return !done(i) && i.undoAskedAtMs != null && (i.undoneAtMs == null || i.undoneAtMs < archived)
    }

    /** On the card: not gone, not Done, not snoozed, inside the window. */
    fun showing(i: MailItem, nowMs: Long): Boolean =
        i.goneAtMs == null && !done(i) && (i.snoozedUntilMs == null || i.snoozedUntilMs <= nowMs) && nowMs - i.receivedAtMs <= WINDOW_MS

    private fun snoozed(i: MailItem, nowMs: Long): Boolean =
        i.goneAtMs == null && !done(i) && i.snoozedUntilMs != null && i.snoozedUntilMs > nowMs && nowMs - i.receivedAtMs <= WINDOW_MS

    fun headline(n: Int): String = if (n == 1) "1 email needs you" else "$n emails need you"

    /** "09:12" today, "yesterday 18:40", "Mon 12 Oct" before. */
    fun whenLabel(atMs: Long, nowMs: Long, cal: LocalCalendar): String {
        val today = cal.epochDayOf(nowMs)
        val day = cal.epochDayOf(atMs)
        val time = LocalClock.formatMinute(cal.minuteOfDay(atMs))
        return when (day) {
            today -> time
            today - 1 -> "yesterday $time"
            else -> CivilDate.shortLabel(day)
        }
    }

    /** Needs you's card, or null when no email needs him. Newest first. */
    fun card(items: List<MailItem>, nowMs: Long, cal: LocalCalendar): MailNeedsCard? {
        val all = items.filter { showing(it, nowMs) }.sortedWith(compareByDescending<MailItem> { it.receivedAtMs }.thenBy { it.id })
        if (all.isEmpty()) return null
        val rows = all.take(SHOWN).map { i ->
            val thread = if (i.threadCount > 1) "${i.threadCount} in this thread" else null
            val replied = if (replySent(i)) "replied ${whenLabel(i.replySentAtMs!!, nowMs, cal)}" else null
            val line = listOfNotNull(i.account, whenLabel(i.receivedAtMs, nowMs, cal), thread, replied).joinToString(" · ")
            val notice = replyNotice(i)
            MailNeedsRow(
                id = i.id, from = i.from, line = line, subject = i.subject, preview = i.preview,
                business = i.account == MailLabel.BUSINESS.label,
                spoken = listOfNotNull("${i.from}, ${i.subject}", line, i.preview, notice).joinToString(". "),
                notice = notice,
                replyDraft = if (notice != null) i.replyText else null,
                replyLine = "To ${i.from} · sent from ${i.account} only when you tap Send",
            )
        }
        val more = all.size - rows.size
        val snoozedCount = items.count { snoozed(it, nowMs) }
        return MailNeedsCard(
            title = TITLE,
            headline = headline(all.size),
            rows = rows,
            moreLine = if (more > 0) "+ $more more · newest shown first" else null,
            snoozedLine = when (snoozedCount) {
                0 -> null
                1 -> "1 snoozed"
                else -> "$snoozedCount snoozed"
            },
        )
    }

    /** Done: the card goes on both apps and the server archives the email at its next check. */
    fun doneFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailItemFields.DONE_ASKED_AT to nowMs.fv())

    /** Undo on Done: back on the card (the server puts it back in the inbox if it already archived it). */
    fun undoDoneFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailItemFields.UNDO_ASKED_AT to nowMs.fv())

    /** Snooze: until tomorrow 08:00 (local). */
    fun snoozeUntil(nowMs: Long, cal: LocalCalendar): Long = cal.toEpochMs(cal.epochDayOf(nowMs) + 1, SNOOZE_MINUTE)

    fun snoozeFields(untilMs: Long): Map<String, FieldValue> = mapOf(MailItemFields.SNOOZED_UNTIL to untilMs.fv())

    /** What the server writes after archiving it (a Done) or putting it back (an Undo). */
    fun archivedFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailItemFields.DONE_AT to nowMs.fv())
    fun putBackFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailItemFields.UNDONE_AT to nowMs.fv(), MailItemFields.GONE_AT to 0L.fv())

    // ---- Slice 6b-1: Task and Waiting for ----

    /** The task made from an email: one per email, so Task tapped on both apps offline converges on one task. */
    fun taskId(itemId: String): String = "tm$itemId"

    /** The Waiting for item made from an email: one per email, like [taskId]. */
    fun waitingId(itemId: String): String = "wm$itemId"

    /** The subject without its "Re:", "Fwd:" and "Fw:" (any number, any case); "Email from Sam Taylor" when none is left. */
    fun taskTitle(i: MailItem): String {
        var t = i.subject.trim()
        while (true) {
            val colon = t.indexOf(':')
            if (colon !in 2..3) break
            val head = t.substring(0, colon).lowercase()
            if (head != "re" && head != "fw" && head != "fwd" && head != "aw" && head != "sv") break
            t = t.substring(colon + 1).trim()
        }
        if (t.isEmpty() || t == "(no subject)") return "Email from ${i.from}"
        return t.take(MAX_SUBJECT)
    }

    /** "Email from Sam Taylor · Personal · Sat 17 Oct" — the task's notes, so he knows where to find it. */
    fun taskNotes(i: MailItem, cal: LocalCalendar): String =
        "Email from ${i.from} · ${i.account} · ${CivilDate.shortLabel(cal.epochDayOf(i.receivedAtMs))}"

    /** Task: a Done that also records the task made. */
    fun taskFields(taskId: String, nowMs: Long): Map<String, FieldValue> = doneFields(nowMs) + (MailItemFields.TASK_ID to taskId.fv())

    /** Waiting for: a Done that also records the Waiting for item made. */
    fun waitingFields(waitingId: String, nowMs: Long): Map<String, FieldValue> = doneFields(nowMs) + (MailItemFields.WAITING_ID to waitingId.fv())

    /** Undo on Task or Waiting for: back on the card and nothing recorded as made. */
    fun undoMadeFields(nowMs: Long): Map<String, FieldValue> =
        undoDoneFields(nowMs) + (MailItemFields.TASK_ID to "".fv()) + (MailItemFields.WAITING_ID to "".fv())
}
