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

    fun sent(i: MailItem): Boolean = i.chaseAskedAtMs != null && i.chaseSentAtMs != null && i.chaseSentAtMs >= i.chaseAskedAtMs

    fun failed(i: MailItem): Boolean = i.chaseAskedAtMs != null && i.chaseFailedAtMs != null && i.chaseFailedAtMs >= i.chaseAskedAtMs

    private fun undone(i: MailItem): Boolean = i.chaseUndoAtMs != null && i.chaseAskedAtMs != null && i.chaseUndoAtMs >= i.chaseAskedAtMs

    /** He tapped Send, hasn't taken it back, and the server hasn't sent it or given up on it yet. */
    fun waiting(i: MailItem): Boolean = i.chaseAskedAtMs != null && !i.chaseText.isNullOrBlank() && !sent(i) && !failed(i) && !undone(i)

    /** The server should send it now: waiting, and Undo's window ([MailNeedsRules.REPLY_HOLD_MS]) has passed. */
    fun due(i: MailItem, nowMs: Long): Boolean = waiting(i) && nowMs - i.chaseAskedAtMs!! >= MailNeedsRules.REPLY_HOLD_MS

    /** A failed chase he hasn't sent again or put aside (Not yet) since. */
    private fun failedOpen(i: MailItem): Boolean = failed(i) && !undone(i) && (i.chaseSeenAtMs == null || i.chaseSeenAtMs < i.chaseFailedAtMs!!)

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
    fun card(items: List<MailItem>, waiting: List<WaitingItem>, shownName: String?, nowMs: Long, cal: LocalCalendar): MailChaseCard? {
        val open = waiting.associateBy { it.id }
        val all = items.mapNotNull { i ->
            val w = i.waitingId?.let(open::get) ?: return@mapNotNull null
            val failedNow = failedOpen(i)
            if (!failedNow && (w.state != DueState.DUE || waiting(i))) return@mapNotNull null
            Triple(i, w, failedNow)
        }.sortedWith(compareBy<Triple<MailItem, WaitingItem, Boolean>> { it.second.sinceMs }.thenBy { it.first.id })
        if (all.isEmpty()) return null
        val today = cal.epochDayOf(nowMs)
        val rows = all.take(SHOWN).map { (i, w, failedNow) ->
            val since = cal.epochDayOf(w.sinceMs)
            val sinceLabel = if (since >= today) "waiting since today" else "waiting since ${CivilDate.shortLabel(since)}"
            val chased = w.lastChasedMs?.let { "last chased ${CivilDate.shortLabel(cal.epochDayOf(it))}" }
            val dueLabel = w.chaseDay?.let { d -> if (d < today) "chase was due ${CivilDate.shortLabel(d)}" else "chase today" }
            val line = listOfNotNull(i.account, sinceLabel, chased, if (failedNow) null else dueLabel).joinToString(" · ")
            val who = w.who?.takeIf { it.isNotBlank() } ?: i.from
            val notice = if (failedNow) "Chase didn't send · ${i.chaseFailedWhy ?: "try again"} · your words are kept" else null
            MailChaseRow(
                id = i.id, waitingId = w.id, from = who, title = w.title, line = line,
                business = i.account == MailLabel.BUSINESS.label,
                draft = if (failedNow) i.chaseText ?: draft(i.from, shownName) else draft(i.from, shownName),
                replyLine = "To ${i.from} · in the same thread, from ${i.account}, only when you tap Send",
                notice = notice,
                spoken = listOfNotNull("Chase $who about ${w.title}", line, notice).joinToString(". "),
            )
        }
        val more = all.size - rows.size
        return MailChaseCard(TITLE, headline(all.size), rows, if (more > 0) "+ $more more in Lists → Waiting for" else null)
    }
}
