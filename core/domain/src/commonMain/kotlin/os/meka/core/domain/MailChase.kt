package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Inbox autopilot, slice 6b-3: **Waiting for's chase, drafted when it's due**. Non-AI, pure, unit-tested; no regexes.
 *
 * A Waiting for item made from an email (slice 6b-1, [MailItem.waitingId]) whose chase day has come shows in Needs you's
 * **Time to chase** card: who, what, which account and since when, with **Chase** — an editor that opens already
 * holding a short chase ([draft]: "Hi Sam, just checking in on this — is there any news? Thanks"), his to change —
 * **Got it** (the thing arrived: off Waiting for) and **Not yet** (chase again in [ListRules.DEFAULT_CHASE_DAYS] days,
 * nothing sent). **Send** writes his words on the email's `mail_item` ([MailItemFields.CHASE_TEXT],
 * [MailItemFields.CHASE_ASKED_AT]) and records the chase on the Waiting for item (next chase in 3 days); the server
 * sends it from the account the email came to, in the email's thread, at its next check once
 * [MailNeedsRules.REPLY_HOLD_MS] have passed (Undo stops it), and writes [MailItemFields.CHASE_SENT_AT]. A send that
 * fails writes [MailItemFields.CHASE_FAILED_AT] and the row comes back with his words and why. Nothing is sent without
 * his tap on Send; the email itself stays where it is (archived since Waiting for).
 */
data class MailChaseRow(
    /** The email's `mail_item` id (what Send, Got it and Not yet act on). */
    val id: String,
    /** The Waiting for item's id. */
    val waitingId: String,
    /** "Sam Taylor". */
    val from: String,
    /** What he's waiting for (the Waiting for item's title). */
    val title: String,
    /** "Personal · waiting since Mon 12 Oct · chase was due Thu 15 Oct". */
    val line: String,
    val business: Boolean,
    /** What the editor opens with: his words after a failed send, else Navilife's chase. */
    val draft: String,
    /** "To Sam Taylor · in the same thread, from Personal, only when you tap Send". */
    val replyLine: String,
    /** "Chase didn't send · Gmail didn't take it · your words are kept", else null. */
    val notice: String?,
    val spoken: String,
)

/**
 * A chase's marks, wherever it lives: on an email's `mail_item` (Inbox autopilot) or on a follow-up's `mail_find`
 * (Follow-up radar, slice 2), under the same field names ([MailItemFields.CHASE_TEXT] …).
 */
data class ChaseMarks(
    val text: String? = null,
    val askedAtMs: Long? = null,
    val undoAtMs: Long? = null,
    val seenAtMs: Long? = null,
    val sentAtMs: Long? = null,
    val failedAtMs: Long? = null,
    val failedWhy: String? = null,
)

data class MailChaseCard(
    /** "Time to chase". */
    val title: String,
    /** "1 to chase by email". */
    val headline: String,
    /** Waiting longest first, at most [MailChaseRules.SHOWN]. */
    val rows: List<MailChaseRow>,
    val moreLine: String?,
)

object MailChaseRules {
    const val TITLE = "Time to chase"
    const val CHASE = "Chase"
    const val GOT_IT = "Got it"
    const val NOT_YET = "Not yet"
    const val SHOWN = 5
    const val GOT_LINE = "Got it · off Waiting for"
    const val NOT_YET_LINE = "Not yet · chase again in 3 days"
    const val UNKNOWN = "Couldn't find that chase"
    /** The email is no longer among the ones Navilife holds (older than 60 days, or deleted). */
    const val WHY_GONE = "Navilife can't find the email any more"

    fun headline(n: Int): String = "$n to chase by email"

    /** "Chase sending to Sam Taylor from Personal at Navilife's next check · Undo stops it". */
    fun sendingLine(from: String, account: String): String = "Chase sending to $from from $account at Navilife's next check · Undo stops it"

    /** The first name to greet: "Sam" for "Sam Taylor"; null for a company-ish or odd name ("Acme Billing" stays "Hi,"). */
    fun greetName(from: String): String? {
        val words = from.trim().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 3) return null
        if (words.any { w -> w.any { !it.isLetter() && it != '-' && it != '\'' } }) return null
        val first = words.first()
        if (first.length < 2 || !first.first().isUpperCase() || first.all { it.isUpperCase() }) return null
        // One word on its own is as likely a company ("Octopus") as a person: greet plainly.
        if (words.size == 1) return null
        return first
    }

    /** Navilife's chase, his to change: short, polite, no facts it doesn't have; signed with his name when About you has it. */
    fun draft(from: String, shownName: String?): String {
        val hi = greetName(from)?.let { "Hi $it," } ?: "Hi,"
        val sign = shownName?.trim()?.takeIf { it.isNotEmpty() }?.let { "Thanks,\n$it" } ?: "Thanks"
        return "$hi\n\nJust checking in on this — is there any news?\n\n$sign"
    }

    fun sent(i: MailItem): Boolean = sent(marks(i))
    fun failed(i: MailItem): Boolean = failed(marks(i))
    fun waiting(i: MailItem): Boolean = waiting(marks(i))
    fun due(i: MailItem, nowMs: Long): Boolean = due(marks(i), nowMs)

    /** An email's chase marks. */
    fun marks(i: MailItem): ChaseMarks = ChaseMarks(
        i.chaseText, i.chaseAskedAtMs, i.chaseUndoAtMs, i.chaseSeenAtMs, i.chaseSentAtMs, i.chaseFailedAtMs, i.chaseFailedWhy,
    )

    fun sent(c: ChaseMarks): Boolean = c.askedAtMs != null && c.sentAtMs != null && c.sentAtMs >= c.askedAtMs

    fun failed(c: ChaseMarks): Boolean = c.askedAtMs != null && c.failedAtMs != null && c.failedAtMs >= c.askedAtMs

    private fun undone(c: ChaseMarks): Boolean = c.undoAtMs != null && c.askedAtMs != null && c.undoAtMs >= c.askedAtMs

    /** He tapped Send, hasn't taken it back, and the server hasn't sent it or given up on it yet. */
    fun waiting(c: ChaseMarks): Boolean = c.askedAtMs != null && !c.text.isNullOrBlank() && !sent(c) && !failed(c) && !undone(c)

    /** The server should send it now: waiting, and Undo's window ([MailNeedsRules.REPLY_HOLD_MS]) has passed. */
    fun due(c: ChaseMarks, nowMs: Long): Boolean = waiting(c) && nowMs - c.askedAtMs!! >= MailNeedsRules.REPLY_HOLD_MS

    /** A failed chase he hasn't sent again or put aside (Not yet) since. */
    private fun failedOpen(c: ChaseMarks): Boolean = failed(c) && !undone(c) && (c.seenAtMs == null || c.seenAtMs < c.failedAtMs!!)

    /** Send: his words for the server. */
    fun sendFields(text: String, nowMs: Long): Map<String, FieldValue> =
        mapOf(MailItemFields.CHASE_TEXT to text.trim().fv(), MailItemFields.CHASE_ASKED_AT to nowMs.fv())

    /** Undo on Send: the server never sends it. */
    fun undoFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailItemFields.CHASE_UNDO_AT to nowMs.fv())

    /** Not yet or Got it on a row whose chase had failed: the failure is put aside. */
    fun seenFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailItemFields.CHASE_SEEN_AT to nowMs.fv())

    fun sentFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailItemFields.CHASE_SENT_AT to nowMs.fv())

    /** [why]: a few words, never the chase itself. */
    fun failedFields(nowMs: Long, why: String): Map<String, FieldValue> =
        mapOf(MailItemFields.CHASE_FAILED_AT to nowMs.fv(), MailItemFields.CHASE_FAILED_WHY to why.take(80).fv())

    /**
     * Time to chase: each email made into a Waiting for item that is still open, whose chase day has come (and no chase
     * is on its way), or whose last chase didn't send. Waiting longest first. Null when there's nothing to chase.
     */
    fun card(
        items: List<MailItem>, waiting: List<WaitingItem>, shownName: String?, nowMs: Long, cal: LocalCalendar,
        finds: List<MailFind> = emptyList(),
    ): MailChaseCard? {
        val open = waiting.associateBy { it.id }
        val today = cal.epochDayOf(nowMs)
        val fromMail: List<Pair<Long, MailChaseRow>> = items.mapNotNull { i ->
            val w = i.waitingId?.let(open::get) ?: return@mapNotNull null
            val c = marks(i)
            val failedNow = failedOpen(c)
            if (!failedNow && (w.state != DueState.DUE || waiting(c))) return@mapNotNull null
            w.sinceMs to mailRow(i, w, failedNow, shownName, today, cal)
        }
        // Follow-up radar, slice 2: his own emails with no reply that he put on Waiting for (Add on "No reply yet").
        val fromSent: List<Pair<Long, MailChaseRow>> = finds.mapNotNull { f ->
            if (f.kind != MailFollowUpRules.KIND_FOLLOW_UP || f.state != MailFindState.ADDED) return@mapNotNull null
            val w = open[MailPromiseRules.waitingId(f.id)] ?: return@mapNotNull null
            val failedNow = failedOpen(f.chase)
            val repliedNow = !failedNow && repliedOpen(f)
            if (!failedNow && !repliedNow && (w.state != DueState.DUE || waiting(f.chase))) return@mapNotNull null
            w.sinceMs to followUpRow(f, w, failedNow, repliedNow, shownName, today, cal)
        }
        val all = (fromMail + fromSent).sortedWith(compareBy<Pair<Long, MailChaseRow>> { it.first }.thenBy { it.second.id })
        if (all.isEmpty()) return null
        val rows = all.take(SHOWN).map { it.second }
        val more = all.size - rows.size
        return MailChaseCard(TITLE, headline(all.size), rows, if (more > 0) "+ $more more in Lists → Waiting for" else null)
    }

    /** They replied to his email after he'd put it on Waiting for, and he hasn't said Got it or Not yet since. */
    fun repliedOpen(f: MailFind): Boolean {
        val at = f.answeredAtMs ?: return false
        if (f.answeredWhy != MailFollowUpRules.ANSWERED_REPLIED) return false
        return f.chase.seenAtMs == null || f.chase.seenAtMs < at
    }

    private fun chasedLabels(w: WaitingItem, today: Long, cal: LocalCalendar): Pair<String?, String?> =
        w.lastChasedMs?.let { "last chased ${CivilDate.shortLabel(cal.epochDayOf(it))}" } to
            w.chaseDay?.let { d -> if (d < today) "chase was due ${CivilDate.shortLabel(d)}" else "chase today" }

    /**
     * Follow-up radar, slice 2: Navilife's chase of his own unanswered email, his to change. It goes in the same
     * conversation, above his email, so it only points at it; signed with his name when About you has it.
     */
    fun followUpDraft(who: String, shownName: String?): String {
        val hi = greetName(who)?.let { "Hi $it," } ?: "Hi,"
        val sign = shownName?.trim()?.takeIf { it.isNotEmpty() }?.let { "Thanks,\n$it" } ?: "Thanks"
        return "$hi\n\nJust following up on my email below — have you had a chance to look at it?\n\n$sign"
    }

    /** "They replied on Thu 15 Oct · Got it if that settles it". */
    fun repliedNotice(answeredAtMs: Long, cal: LocalCalendar): String =
        "They replied on ${CivilDate.shortLabel(cal.epochDayOf(answeredAtMs))} · Got it if that settles it"

    private fun followUpRow(
        f: MailFind, w: WaitingItem, failedNow: Boolean, repliedNow: Boolean, shownName: String?, today: Long, cal: LocalCalendar,
    ): MailChaseRow {
        val who = w.who?.takeIf { it.isNotBlank() } ?: f.merchant ?: MailPromiseRules.NO_NAME
        val (chased, dueLabel) = chasedLabels(w, today, cal)
        val wrote = "you wrote ${CivilDate.shortLabel(f.receivedDay)}"
        val line = listOfNotNull(f.account, wrote, chased, if (failedNow || repliedNow) null else dueLabel).joinToString(" · ")
        val notice = when {
            failedNow -> "Chase didn't send · ${f.chase.failedWhy ?: "try again"} · your words are kept"
            repliedNow -> repliedNotice(f.answeredAtMs!!, cal)
            else -> null
        }
        return MailChaseRow(
            id = f.id, waitingId = w.id, from = who, title = w.title, line = line,
            business = f.account == MailLabel.BUSINESS.label,
            draft = if (failedNow) f.chase.text ?: followUpDraft(who, shownName) else followUpDraft(who, shownName),
            replyLine = "To $who · in the same conversation as your email, from ${f.account}, only when you tap Send",
            notice = notice,
            spoken = listOfNotNull("Chase $who about ${w.title}", line, notice).joinToString(". "),
        )
    }

    private fun mailRow(i: MailItem, w: WaitingItem, failedNow: Boolean, shownName: String?, today: Long, cal: LocalCalendar): MailChaseRow {
        val since = cal.epochDayOf(w.sinceMs)
        val sinceLabel = if (since >= today) "waiting since today" else "waiting since ${CivilDate.shortLabel(since)}"
        val (chased, dueLabel) = chasedLabels(w, today, cal)
        val line = listOfNotNull(i.account, sinceLabel, chased, if (failedNow) null else dueLabel).joinToString(" · ")
        val who = w.who?.takeIf { it.isNotBlank() } ?: i.from
        val notice = if (failedNow) "Chase didn't send · ${i.chaseFailedWhy ?: "try again"} · your words are kept" else null
        return MailChaseRow(
            id = i.id, waitingId = w.id, from = who, title = w.title, line = line,
            business = i.account == MailLabel.BUSINESS.label,
            draft = if (failedNow) i.chaseText ?: draft(i.from, shownName) else draft(i.from, shownName),
            replyLine = "To ${i.from} · in the same thread, from ${i.account}, only when you tap Send",
            notice = notice,
            spoken = listOfNotNull("Chase $who about ${w.title}", line, notice).joinToString(". "),
        )
    }
}
