package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Follow-up radar, slice 1 (build plan; non-AI, pure): **emails he sent that got no reply**. A few times a day the
 * server lists what each connected account sent in the last [MailFollowUpRules.WINDOW_DAYS] days (recipients, subject,
 * the provider's preview and the time; never the body). The newest email he sent in a conversation that asks
 * something ("Could you send the quote?", "Let me know if Tuesday works") and has had no answer from anyone else in
 * that conversation after [MailFollowUpRules.DAYS] days becomes one *find* (`mail_find`, kind FOLLOW_UP). Both apps show
 * it in Needs you with the renewals and promises ("No reply yet"): who, "No reply since you wrote on Mon 12 Oct · 3
 * days", the account and the subject, with **Add** (a Waiting for item from them, to chase today; Undo takes it off)
 * and **Not needed** (Undo). Nothing reaches Waiting for, and nothing is sent, without his tap.
 *
 * When they answer (or he writes again in the conversation himself) the server marks the find answered and it
 * leaves both apps by itself. Not for emails to his own addresses, to no-reply or automated senders, to more than
 * [MailFollowUpRules.MAX_RECIPIENTS] people, calendar answers ("Accepted: …"), automatic replies, or emails that only
 * thank or confirm.
 */

/** One email he sent, as the server reads it: the first recipient, how many, subject, preview and when. */
data class MailSentFacts(
    val toName: String?,
    val toAddress: String?,
    /** To and Cc together. */
    val recipients: Int,
    val subject: String?,
    val preview: String?,
    val sentAtMs: Long,
)

/** A follow-up to offer: who he's waiting on and what Waiting for will say ("Reply about the quote"). */
data class MailFollowUp(val who: String, val what: String)

/**
 * Follow-up radar, slice 3: how many days he waits for one person's reply before Navilife offers a chase (synced
 * `follow_up_wait`, one per person, keyed by the name the row shows; [days] 1–14). Absent = [MailFollowUpRules.DAYS].
 */
data class FollowUpWait(val id: String, val who: String, val days: Int, val atMs: Long)

/** The `follow_up_wait` entity's fields (ADR-008 addendum 2026-10-15, Follow-up radar slice 3). */
object FollowUpWaitFields {
    const val WHO = "who"
    /** Int64, 1–14; 0 = back to the default (Undo of a first choice). */
    const val DAYS = "days"
    const val AT = "atMs"
}

/** One chip under "Wait 3 days ▾": "5 days", whether it's his choice for this person, and what it says aloud. */
data class WaitChoice(val days: Int, val label: String, val chosen: Boolean, val spoken: String)

object MailFollowUpRules {
    const val KIND_FOLLOW_UP = "FOLLOW_UP"
    /** Days without an answer before it's offered, unless he set his own for that person ([FollowUpWait]). */
    const val DAYS = 3
    /** The server writes a find from this many days, so a one-day person can be offered; the apps wait the rest. */
    const val MIN_DAYS = 1
    /** The waits he can pick per person. */
    val WAIT_CHOICES = listOf(1, 2, 3, 5, 7, 14)
    /** How far back his sent mail is read. */
    const val WINDOW_DAYS = 21
    const val WINDOW_MS = WINDOW_DAYS * 86_400_000L
    /** An unanswered find stays offered this long after it was first due. */
    const val STALE_DAYS = 14
    const val MAX_RECIPIENTS = 3
    const val TITLE = "No reply yet"
    /** [MailFindFields.ANSWERED_WHY]'s values. */
    const val ANSWERED_REPLIED = "replied"
    const val ANSWERED_WROTE_AGAIN = "wrote again"

    private const val MAX_TOPIC = 45

    /** Subjects that are never a question waiting on someone. */
    private val SKIP_SUBJECT = listOf(
        "accepted:", "declined:", "tentative:", "tentatively accepted:", "updated invitation", "invitation:",
        "automatic reply", "auto reply", "auto-reply", "autoreply", "out of office", "out of the office", "read:", "delivered:",
        "undeliverable",
    )

    /** Words that ask the other person for something. */
    private val ASKS = listOf(
        "let me know", "could you", "can you", "would you", "will you", "are you able", "are you free", "is it possible",
        "please confirm", "please send", "please let", "please could", "please can", "please advise", "please reply",
        "get back to me", "when can", "when could", "when will", "any update", "any news", "do you know", "do you have",
        "did you", "have you", "what do you think", "thoughts", "look forward to hearing", "looking forward to hearing",
        "awaiting your", "await your", "need your", "your thoughts", "when would", "which day", "what time",
    )

    /** Only thanks or a confirmation: nothing to wait for. */
    private val CLOSERS = listOf("thanks", "thank you", "many thanks", "cheers", "great", "perfect", "lovely", "ok", "okay", "noted", "will do", "sounds good")

    /** Whether the subject or preview asks the other person for something. */
    fun asks(subject: String?, preview: String?): Boolean {
        val text = listOfNotNull(subject?.let(::topic), preview).joinToString(" ").lowercase()
        if (text.isBlank()) return false
        if ('?' in text) return true
        return ASKS.any { phrase -> contains(text, phrase) }
    }

    /** [phrase] as whole words in [text]. */
    private fun contains(text: String, phrase: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(phrase, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else text[i - 1]
            val end = i + phrase.length
            val after = if (end >= text.length) ' ' else text[end]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = i + 1
        }
    }

    /** "Re: Fwd: Quote for the kitchen" → "Quote for the kitchen"; null when nothing is left. */
    fun topic(subject: String?): String? {
        var s = subject?.trim() ?: return null
        while (true) {
            val lower = s.lowercase()
            val prefix = listOf("re:", "fwd:", "fw:", "aw:", "re :", "fwd :").firstOrNull { lower.startsWith(it) } ?: break
            s = s.substring(prefix.length).trim()
        }
        return s.takeIf { it.isNotEmpty() }
    }

    /** Waiting for's title: "Reply about Quote for the kitchen" (cut at a word), else "A reply". */
    fun what(subject: String?): String {
        val t = topic(subject) ?: return "A reply"
        val cut = if (t.length <= MAX_TOPIC) t else t.take(MAX_TOPIC).substringBeforeLast(' ').ifBlank { t.take(MAX_TOPIC) } + "…"
        return "Reply about $cut"
    }

    /**
     * Whether a sent email is one to follow up (timing and answers aside): one to three people, the first not one of
     * [own] addresses nor automated, not a calendar answer or automatic reply, and asking something rather than only
     * thanking or confirming. [automated]: the first recipient's address looks like a no-reply or system sender.
     */
    fun read(f: MailSentFacts, own: Set<String>, automated: Boolean): MailFollowUp? {
        val address = f.toAddress?.trim()?.lowercase()?.takeIf { '@' in it } ?: return null
        if (address in own || automated) return null
        if (f.recipients < 1 || f.recipients > MAX_RECIPIENTS) return null
        val subject = f.subject?.trim()?.lowercase().orEmpty()
        if (SKIP_SUBJECT.any { subject.startsWith(it) || subject.contains(" $it") }) return null
        if (!asks(f.subject, f.preview)) return null
        val opening = f.preview?.trim()?.lowercase()?.trimEnd('.', '!', ' ').orEmpty()
        if (opening in CLOSERS) return null
        val who = f.toName?.trim()?.removeSurrounding("\"")?.trim()?.takeIf { it.isNotEmpty() && '@' !in it } ?: address
        return MailFollowUp(who.take(60), what(f.subject))
    }

    /**
     * Sent on [sentDay]: should the server write its find today (at least [MIN_DAYS] without an answer, inside the
     * window)? The apps then show it once that person's own wait has passed ([showable]).
     */
    fun due(sentDay: Long, today: Long): Boolean = today - sentDay in MIN_DAYS.toLong()..WINDOW_DAYS.toLong()

    // ---- Slice 3: per-person days ----

    /** The key a person's wait is kept under: the name the row shows, folded. */
    fun waitKey(who: String?): String = who?.trim()?.lowercase()?.replace(Regex("\\s+"), " ").orEmpty()

    fun waitId(who: String?): String = "fw" + ActivityRules.fnv64("followupwait:" + waitKey(who))

    fun readWait(id: String, fields: Map<String, FieldValue>): FollowUpWait? {
        val who = (fields[FollowUpWaitFields.WHO] as? FieldValue.Text)?.value?.takeIf { it.isNotBlank() } ?: return null
        val days = (fields[FollowUpWaitFields.DAYS] as? FieldValue.Int64)?.value?.toInt() ?: return null
        if (days !in 1..WAIT_CHOICES.last()) return null
        val at = (fields[FollowUpWaitFields.AT] as? FieldValue.Int64)?.value ?: 0L
        return FollowUpWait(id, who, days, at)
    }

    /** [days] 0 puts that person back on the default. */
    fun waitFields(who: String, days: Int, nowMs: Long): Map<String, FieldValue> = linkedMapOf(
        FollowUpWaitFields.WHO to who.trim().take(60).fv(),
        FollowUpWaitFields.DAYS to days.toLong().fv(),
        FollowUpWaitFields.AT to nowMs.fv(),
    )

    /** How many days he waits for [who]: his own choice for them, else [DAYS]. */
    fun daysFor(who: String?, waits: List<FollowUpWait>): Int {
        val key = waitKey(who)
        if (key.isEmpty()) return DAYS
        return waits.filter { waitKey(it.who) == key }.maxByOrNull { it.atMs }?.days ?: DAYS
    }

    /** "1 day", "5 days", "1 week", "2 weeks". */
    fun waitLabel(days: Int): String = when (days) {
        1 -> "1 day"
        7 -> "1 week"
        14 -> "2 weeks"
        else -> "$days days"
    }

    /** The pill on a row: "Wait 3 days ▾". */
    fun waitPill(days: Int): String = "Wait ${waitLabel(days)} ▾"

    /** Over the chips: "How long to wait for Sam Taylor's replies before offering a chase". */
    fun waitHeading(who: String): String = "How long to wait for ${who}'s replies before offering a chase"

    fun waitChoices(who: String, current: Int): List<WaitChoice> = WAIT_CHOICES.map { d ->
        WaitChoice(d, waitLabel(d), d == current, "Wait ${waitLabel(d)} for $who" + if (d == current) ", chosen" else "")
    }

    /**
     * The bar's line after a choice: "Waiting 1 week for Sam Taylor's replies · this one comes back Mon 19 Oct" when
     * the row now waits, else "Waiting 1 day for Sam Taylor's replies from now on".
     */
    fun waitSetLine(f: MailFind, days: Int, today: Long): String {
        val who = title(f)
        val back = f.receivedDay + days
        return if (back > today) "Waiting ${waitLabel(days)} for $who's replies · this one comes back ${CivilDate.shortLabel(back)}"
        else "Waiting ${waitLabel(days)} for $who's replies from now on"
    }

    /** Someone other than him answered in the conversation after [sentAtMs]. [inbound]: (from address, received at). */
    fun replied(sentAtMs: Long, inbound: List<Pair<String?, Long>>, own: Set<String>): Boolean =
        inbound.any { (from, at) -> at > sentAtMs && (from?.lowercase() ?: "") !in own }

    /** One find per account and sent email. */
    fun findId(accountId: String, messageId: String): String = "mf" + ActivityRules.fnv64("mailfollowup:$accountId:$messageId")

    /** The find's fields, written once by the server; its day is the day it became due. */
    fun serverFields(fu: MailFollowUp, account: String, subject: String?, sentDay: Long): Map<String, FieldValue> = linkedMapOf(
        MailFindFields.KIND to KIND_FOLLOW_UP.fv(),
        MailFindFields.ACCOUNT to account.fv(),
        MailFindFields.MERCHANT to fu.who.fv(),
        MailFindFields.DAY to (sentDay + DAYS).fv(),
        MailFindFields.WHAT to fu.what.fv(),
        MailFindFields.RECEIVED_DAY to sentDay.fv(),
        MailFindFields.SUBJECT to (subject?.trim()?.take(MailRenewalRules.MAX_SUBJECT)?.takeIf { it.isNotEmpty() }?.fv() ?: FieldValue.Null),
        // His own words, but the recipient's name came from his address book or their own headers.
        MailFindFields.TRUST to Trust.UNTRUSTED_EXTERNAL.name.fv(),
    )

    /** The server's mark once they answered or he wrote again: the find leaves both apps. */
    fun answeredFields(nowMs: Long, why: String): Map<String, FieldValue> =
        mapOf(MailFindFields.ANSWERED_AT to nowMs.fv(), MailFindFields.ANSWERED_WHY to why.fv())

    fun title(f: MailFind): String = f.merchant ?: MailPromiseRules.NO_NAME

    private fun daysWord(n: Long): String = if (n == 1L) "1 day" else "$n days"

    /** "No reply since you wrote on Mon 12 Oct · 3 days". */
    fun line(f: MailFind, today: Long): String =
        "No reply since you wrote on ${CivilDate.shortLabel(f.receivedDay)} · ${daysWord((today - f.receivedDay).coerceAtLeast(0))}"

    fun row(f: MailFind, today: Long, waits: List<FollowUpWait> = emptyList()): MailFindRow {
        val title = title(f)
        val line = line(f, today)
        val days = daysFor(f.merchant, waits)
        return MailFindRow(
            id = f.id, title = title, line = line, from = MailRenewalRules.fromLine(f),
            business = f.account == MailLabel.BUSINESS.label,
            spoken = "$title. $line. From your ${f.account} mail.",
            addSpoken = "Add $title to Waiting for, to chase",
            notNeededSpoken = "No need to chase $title",
            waitPill = waitPill(days),
            waitSpoken = "Waiting ${waitLabel(days)} for $title's replies. Change how long",
            waitHeading = waitHeading(title),
            waits = waitChoices(title, days),
        )
    }

    /**
     * Whether [f] is an open follow-up to show: not answered by them (or by him writing again), that person's wait
     * ([daysFor]: his own, else [DAYS]) passed, not more than [STALE_DAYS] past it, and not already on Waiting for
     * (Add's item, or one from the same person with the same title).
     */
    fun showable(f: MailFind, waiting: List<WaitingItem>, today: Long, waits: List<FollowUpWait> = emptyList()): Boolean {
        if (f.kind != KIND_FOLLOW_UP || f.state != MailFindState.OPEN || f.answeredAtMs != null) return false
        val dueDay = f.receivedDay + daysFor(f.merchant, waits)
        if (today < dueDay || dueDay + STALE_DAYS < today) return false
        val wid = MailPromiseRules.waitingId(f.id)
        return waiting.none { w ->
            w.id == wid || (f.merchant != null && w.who?.equals(f.merchant, ignoreCase = true) == true && w.title.equals(f.what, ignoreCase = true))
        }
    }

    /** Waiting for's note: where it came from. */
    fun note(f: MailFind): String =
        "You emailed them from your ${f.account} mail on ${CivilDate.shortLabel(f.receivedDay)}" +
            (f.subject?.let { " (“$it”)" } ?: "") + " and had no reply; added on your tap."

    /** The bar's line after Add: "On Waiting for: Reply about Quote from Sam Taylor · chase today". */
    fun addedLine(f: MailFind): String =
        "On Waiting for: ${f.what ?: "A reply"}" + (f.merchant?.let { " from $it" } ?: "") + " · chase today"
}

/** Follow-up radar, slice 4: one person in Mail → Chase waits, with the chips to change it and Back to usual. */
data class FollowUpWaitRow(
    val id: String,
    val who: String,
    val days: Int,
    /** "Waits 1 week before a chase is offered · set Mon 12 Oct". */
    val line: String,
    val spoken: String,
    val choices: List<WaitChoice>,
    val resetLabel: String,
    val resetSpoken: String,
)

/** Mail → Chase waits: the people he set his own wait for (the default for everyone else), on both apps. */
data class FollowUpWaitsPanel(val title: String, val line: String, val rows: List<FollowUpWaitRow>)

/*
 * Follow-up radar, slice 4 (pure): **his per-person waits in one place**. Mail settings (Ask → Settings → Mail on both
 * apps) lists everyone he set a wait for under "Wait 3 days ▾" on a No reply yet row, newest choice per person, by
 * name: "Sam Taylor · Waits 1 week before a chase is offered · set Mon 12 Oct", with the same chips to change it and
 * **Back to usual** (the default [MailFollowUpRules.DAYS]); each with Undo. Nothing is sent.
 */
object FollowUpWaitsRules {
    const val TITLE = "Chase waits"
    const val RESET = "Back to usual"

    /** "3 days" — the wait for anyone he hasn't set. */
    val usual: String get() = MailFollowUpRules.waitLabel(MailFollowUpRules.DAYS)

    val EMPTY_LINE: String
        get() = "Navilife offers a chase after $usual without a reply. To wait longer or shorter for someone, use " +
            "${MailFollowUpRules.waitPill(MailFollowUpRules.DAYS)} on their No reply yet row in Needs you."

    /** The newest wait per person (a 0 "back to usual" hides them), sorted by name. */
    fun current(waits: List<FollowUpWait>): List<FollowUpWait> =
        waits.groupBy { MailFollowUpRules.waitKey(it.who) }
            .filterKeys { it.isNotEmpty() }
            .mapNotNull { (_, ws) -> ws.maxByOrNull { it.atMs } }
            .sortedBy { MailFollowUpRules.waitKey(it.who) }

    fun panel(waits: List<FollowUpWait>, zone: LocalCalendar): FollowUpWaitsPanel {
        val rows = current(waits).map { row(it, zone) }
        val line = when (rows.size) {
            0 -> EMPTY_LINE
            1 -> "$usual for everyone else · your own wait for 1 person"
            else -> "$usual for everyone else · your own wait for ${rows.size} people"
        }
        return FollowUpWaitsPanel(TITLE, line, rows)
    }

    fun row(w: FollowUpWait, zone: LocalCalendar): FollowUpWaitRow {
        val label = MailFollowUpRules.waitLabel(w.days)
        val set = if (w.atMs > 0) " · set ${CivilDate.shortLabel(zone.epochDayOf(w.atMs))}" else ""
        return FollowUpWaitRow(
            id = w.id, who = w.who, days = w.days,
            line = "Waits $label before a chase is offered$set",
            spoken = "${w.who}: waits $label before a chase is offered",
            choices = MailFollowUpRules.waitChoices(w.who, w.days),
            resetLabel = RESET,
            resetSpoken = "Put ${w.who} back on the usual $usual",
        )
    }

    /** After a chip: "Waiting 1 week for Sam Taylor's replies from now on". */
    fun changedLine(who: String, days: Int): String = "Waiting ${MailFollowUpRules.waitLabel(days)} for $who's replies from now on"

    /** After Back to usual: "Sam Taylor back to the usual 3 days". */
    fun resetLine(who: String): String = "$who back to the usual $usual"

    const val UNDONE = "Put back as it was"
}
