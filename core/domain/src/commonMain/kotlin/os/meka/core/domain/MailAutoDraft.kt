package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * **Drafted replies come to him** (Meka, 2026-10-15 20:59: "haven't seen it reply to any email … haven't seen it ask me
 * to approve a draft"). Non-AI, pure, unit-tested; no regexes.
 *
 * When the server publishes an email that needs him ([MailNeedsRules.serverFields]) it also says whether it **needs a
 * reply** ([needsReply]: a real person — sorted as a person, someone he has written to, or the AI's "needs you" — not a
 * no-reply address, and the subject or first words ask him something or ask for something) and, if so, **what they
 * want** in one line ([wants], taken from their own words). For each such email, once, the server asks MEKA's AI for a
 * reply in his tone (the same `mail.draft` call and [MailDraftRules.check] screen as Draft it, metered), at most
 * [DAILY_CAP] a day for the household and [PER_CHECK] a check, only for mail of the last [MAX_AGE_MS], and writes the
 * checked draft on the email ([MailAutoDraftFields.AUTO_DRAFT]); an unusable answer is recorded as tried and never
 * asked again. Needs you then shows the draft on the email's card with **Send** (his tap; the reply path's 20-second
 * hold and Undo), **Edit**, **Not needed** (the draft and the "needs a reply" go; the email stays) and Snooze.
 * **Nothing is ever sent without his tap** (Level 3, hard rule).
 */
object MailAutoDraftFields {
    // The server's.
    /** The email asks him something (Bool). */
    const val REPLY_NEEDED = "replyNeeded"
    /** "Asks if you can take the boys on Saturday" — their words, one line ("" when none). */
    const val WANTS = "wants"
    /** Navilife's checked draft. */
    const val AUTO_DRAFT = "autoDraft"
    const val AUTO_DRAFT_AT = "autoDraftAtMs"
    /** Asked, but nothing usable came back: never asked again. */
    const val AUTO_DRAFT_TRIED_AT = "autoDraftTriedAtMs"
    // An app's.
    /** Not needed on the card. */
    const val DISMISSED_AT = "draftDismissedAtMs"
}

object MailAutoDraftRules {
    const val DAILY_CAP = 10
    const val PER_CHECK = 3
    /** Older mail isn't drafted (so the first check after this lands doesn't draft the whole fortnight). */
    const val MAX_AGE_MS = 3 * 86_400_000L
    const val MAX_WANTS = 90
    const val DRAFT_LABEL = "Navilife's draft · goes only when you tap Send"
    const val SEND = "Send"
    const val EDIT = "Edit"
    const val NOT_NEEDED = "Not needed"
    const val LATER = "Remind me later"
    const val NOT_NEEDED_LINE = "Draft put away · the email stays in Needs you"
    const val NONE_WAITING = "No emails waiting on you ✓"
    const val NEEDS_REPLY = "Needs a reply"

    /** The sort reasons that mean a person wrote (never a list, a shop, a no-reply address or a public body's system). */
    private val PERSON_REASONS = setOf(MailSortReason.PERSON, MailSortReason.KNOWN_PERSON, MailSortReason.AI_NEEDS_YOU)

    /** Words that ask him for something, matched at a word start (lower case, no regexes). */
    private val ASKS = listOf(
        "can you", "could you", "would you", "will you", "are you", "do you", "did you", "have you", "can we", "could we",
        "shall we", "should we", "would it be", "is it ok", "is that ok", "please", "let me know", "any chance", "what do you think",
        "rsvp", "please confirm", "can i", "could i", "may i", "when are you", "when can", "are we", "how about",
    )

    /** The subject or first words ask him something: a question mark, or a request ([ASKS]). */
    fun asks(subject: String?, preview: String?): Boolean {
        val s = stripRe(subject.orEmpty())
        val p = preview.orEmpty()
        return s.contains('?') || p.contains('?') || hasAsk(s.lowercase()) || hasAsk(p.lowercase())
    }

    /** Whether the server marks this email "needs a reply" (see the file's note). */
    fun needsReply(reason: MailSortReason?, fromAddress: String?, subject: String?, preview: String?): Boolean =
        reason in PERSON_REASONS && !MailSortRules.isAutomated(fromAddress) && asks(subject, preview)

    /**
     * What they want, in their words: the first sentence of the preview that asks (a question or a request), else the
     * subject when it asks, else the first sentence; cut at a word to [MAX_WANTS]. Null when there are no words.
     */
    fun wants(subject: String?, preview: String?): String? {
        val sentences = sentences(MailNeedsRules.cleanPreview(preview).orEmpty().removeSuffix("…"))
        val subj = stripRe(subject.orEmpty()).trim().takeIf { it.isNotEmpty() && it != "(no subject)" }
        val pick = sentences.firstOrNull { it.contains('?') || hasAsk(it.lowercase()) }
            ?: subj?.takeIf { it.contains('?') || hasAsk(it.lowercase()) }
            ?: sentences.firstOrNull()
            ?: subj
            ?: return null
        return cut(pick)
    }

    /** What the server adds to an email's fields when it publishes it. */
    fun serverFields(needsReply: Boolean, wants: String?): Map<String, FieldValue> = mapOf(
        MailAutoDraftFields.REPLY_NEEDED to needsReply.fv(),
        MailAutoDraftFields.WANTS to (if (needsReply) wants.orEmpty() else "").fv(),
    )

    /** He hasn't answered it, put the draft away, or sent anything: it still needs a reply. */
    fun needsReply(i: MailItem): Boolean =
        i.replyNeeded && i.draftDismissedAtMs == null && i.replyAskedAtMs == null && !MailNeedsRules.replySent(i)

    /** Navilife's draft shows on the card. */
    fun draftShowing(i: MailItem): Boolean = needsReply(i) && !i.autoDraft.isNullOrBlank()

    /** The server should draft it now (the daily cap is the caller's: [usedToday]). */
    fun due(i: MailItem, nowMs: Long): Boolean =
        needsReply(i) && i.autoDraft == null && i.autoDraftAtMs == null && i.autoDraftTriedAtMs == null &&
            MailNeedsRules.showing(i, nowMs) && nowMs - i.receivedAtMs <= MAX_AGE_MS

    /** How many drafts the household has had asked for today (drafted or tried), against [DAILY_CAP]. */
    fun usedToday(items: List<MailItem>, nowMs: Long, cal: LocalCalendar): Int {
        val today = cal.epochDayOf(nowMs)
        return items.count { i -> listOfNotNull(i.autoDraftAtMs, i.autoDraftTriedAtMs).any { cal.epochDayOf(it) == today } }
    }

    /** The emails to draft at this check: due ones, newest first, inside today's room and [PER_CHECK]. */
    fun toDraft(mine: List<MailItem>, all: List<MailItem>, nowMs: Long, cal: LocalCalendar): List<MailItem> {
        val room = (DAILY_CAP - usedToday(all, nowMs, cal)).coerceAtMost(PER_CHECK)
        if (room <= 0) return emptyList()
        return mine.filter { due(it, nowMs) }.sortedByDescending { it.receivedAtMs }.take(room)
    }

    fun draftedFields(text: String, nowMs: Long): Map<String, FieldValue> =
        mapOf(MailAutoDraftFields.AUTO_DRAFT to text.fv(), MailAutoDraftFields.AUTO_DRAFT_AT to nowMs.fv())

    fun triedFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailAutoDraftFields.AUTO_DRAFT_TRIED_AT to nowMs.fv())

    /** Not needed: the draft goes and the email no longer counts as needing a reply. Undo writes 0. */
    fun dismissFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailAutoDraftFields.DISMISSED_AT to nowMs.fv())

    /** "3 emails need a reply · 2 drafted" over the emails on the card; null when none does. */
    fun replyLine(showing: List<MailItem>): String? {
        val n = showing.count { needsReply(it) }
        if (n == 0) return null
        val d = showing.count { draftShowing(it) }
        val head = if (n == 1) "1 email needs a reply" else "$n emails need a reply"
        return if (d == 0) head else "$head · $d drafted"
    }

    /** For Mail and Today: the same line, or [NONE_WAITING] when no email on the card needs a reply. */
    fun waitingLine(items: List<MailItem>, nowMs: Long): String =
        replyLine(MailNeedsRules.showingSorted(items, nowMs)) ?: NONE_WAITING

    private fun hasAsk(text: String): Boolean = ASKS.any { w ->
        var i = text.indexOf(w)
        var found = false
        while (i >= 0 && !found) {
            val before = i == 0 || !text[i - 1].isLetter()
            val after = i + w.length >= text.length || !text[i + w.length].isLetter()
            found = before && after
            i = text.indexOf(w, i + 1)
        }
        found
    }

    private fun stripRe(subject: String): String {
        var t = subject.trim()
        while (true) {
            val colon = t.indexOf(':')
            if (colon !in 2..3) break
            val head = t.substring(0, colon).lowercase()
            if (head != "re" && head != "fw" && head != "fwd") break
            t = t.substring(colon + 1).trim()
        }
        return t
    }

    /** Sentences split after . ! ? followed by a space (no regexes); greetings like "Hi Sam," stay with what follows. */
    private fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val b = StringBuilder()
        for ((k, c) in text.withIndex()) {
            b.append(c)
            if ((c == '.' || c == '!' || c == '?') && (k + 1 >= text.length || text[k + 1] == ' ')) {
                out += b.toString().trim(); b.clear()
            }
        }
        if (b.isNotBlank()) out += b.toString().trim()
        return out.map(::dropGreeting).filter { it.isNotEmpty() && !greeting(it) }
    }

    /** "Hi Sam, can you …" → "can you …" with its first letter raised. */
    private fun dropGreeting(s: String): String {
        val comma = s.indexOf(',')
        if (comma < 0 || comma == s.length - 1) return s
        val head = s.substring(0, comma)
        if (!greeting(head) || head.split(' ').size > 3) return s
        val rest = s.substring(comma + 1).trim()
        return rest.replaceFirstChar { it.uppercaseChar() }
    }

    /** "Hi Sam," / "Hello!" / "Dear Alex," on their own aren't what anyone wants. */
    private fun greeting(s: String): Boolean {
        val l = s.lowercase().trimEnd(',', '!', '.')
        val first = l.substringBefore(' ')
        return (first == "hi" || first == "hello" || first == "hey" || first == "dear" || first == "morning" || first == "evening") && l.split(' ').size <= 3
    }

    private fun cut(s: String): String {
        val t = s.trim()
        if (t.length <= MAX_WANTS) return t
        val words = t.split(' ')
        val out = StringBuilder()
        for (w in words) {
            if (out.length + 1 + w.length > MAX_WANTS - 1) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(w)
        }
        if (out.isEmpty()) out.append(t.take(MAX_WANTS - 1))
        return out.toString().trimEnd(',', '.', ';', ':', '-') + "…"
    }
}
