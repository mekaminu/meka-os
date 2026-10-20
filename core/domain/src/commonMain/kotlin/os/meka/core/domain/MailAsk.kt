package os.meka.core.domain

/*
 * Inbox autopilot, slice 9a (build plan; non-AI, pure, no regexes): **the brief and Ask/Talk read the sorted mail**.
 *
 * Only the emails the server sorted as Needs you reach the devices (`mail_item`, [MailNeedsRules]), so that is what
 * these lines and answers know: who, the subject, the provider's preview, the account and when, for the last
 * [MailNeedsRules.WINDOW_MS]. Newsletters, receipts, notifications and promos were tidied away and are not here.
 *
 * Every word of an email is someone else's (ADR-006): an Ask context carrying mail lines is untrusted, so nothing the
 * model proposes from it runs by itself, and the brief says only how many emails need him and who from (a sender's
 * name made plain and short), never a subject or a preview.
 */

/** The brief's mail: how many emails need him now and the first senders, newest first ([MailAskRules.brief]). */
data class BriefMail(val count: Int, val business: Int, val senders: List<String>)

/**
 * The companion, slice 7: an email Navilife's autopilot tidied out of the inbox that a question names ("did anything
 * come from Amazon?"), with its tidy's account and whether it was put back since ([MailAskRules.tidiedHits]).
 */
data class MailTidiedHit(val email: MailTidiedEmail, val account: String, val putBack: Boolean)

object MailAskRules {
    /** Emails sent with a mail question, at most (after the head line). */
    const val ASK_EMAILS = 8
    /** The companion, slice 7: tidied emails sent with a question that names a sender, at most (after their head line). */
    const val TIDIED_EMAILS = 4
    const val ASK_LINES = 1 + ASK_EMAILS + 1 + TIDIED_EMAILS
    /** Senders the brief names, at most. */
    const val BRIEF_SENDERS = 2
    /** A sender's name in the brief, at most. */
    const val BRIEF_NAME = 30
    /** The local answer names this many emails, at most. */
    const val LOCAL_EMAILS = 3

    private val WORDS = listOf("email", "e-mail", "inbox", "my mail", "the mail", "any mail", "gmail", "outlook", "hotmail")
    private val SAID = listOf("say", "said", "send", "sent", "write", "wrote", "reply", "replied", "want", "wants", "ask", "asked")

    /** Words that never name a sender or a subject in a question. */
    private val STOP = setOf(
        "what", "whats", "did", "does", "do", "the", "a", "an", "my", "me", "i", "im", "ive", "is", "are", "was", "were", "any",
        "anything", "something", "important", "urgent", "new", "latest", "last", "today", "yesterday", "this", "week", "there",
        "email", "emails", "e-mail", "mail", "mails", "inbox", "gmail", "outlook", "hotmail", "from", "about", "have", "has",
        "had", "got", "get", "in", "on", "of", "to", "for", "and", "or", "with", "need", "needs", "you", "your", "tell",
        "read", "show", "check", "it", "they", "them", "their", "he", "she", "him", "her", "us", "we", "our", "that", "who",
        "when", "where", "which", "how", "why", "can", "could", "would", "please", "navilife", "meka", "else", "still",
        "waiting", "answer", "message", "messages", "people", "someone", "anyone", "be", "been", "should", "will",
        "stuff", "thing", "things", "all", "much", "many", "lot", "go", "through", "clear", "zero", "ten", "minute", "look",
        "come", "came", "arrived", "heard", "hear", "back",
    ) + SAID

    /**
     * Whether [question] is about his email: it names email or the inbox ("anything important in my email?"), or asks
     * what someone said or sent ("what did the school say?"), which mail may answer as well as anything else.
     */
    fun isAbout(question: String): Boolean {
        val q = question.lowercase()
        if (WORDS.any { it in q }) return true
        val words = words(q)
        val did = words.indexOfFirst { it == "did" || it == "has" || it == "have" }
        if (did < 0) return false
        val after = words.drop(did + 1)
        if (after.any { it in SAID }) return true
        // The companion, slice 7: "did anything come from Amazon?", "have I heard from the school?".
        val from = after.indexOf("from")
        return from > 0 && after.take(from).any { it in CAME }
    }

    /** Words that, before "from", ask whether mail came from someone. */
    private val CAME = setOf("come", "came", "arrived", "heard", "hear", "anything")

    /** The question's words that may name a sender or a subject ("school", "dentist", "invoice"), lower case. */
    fun terms(question: String): List<String> =
        words(question.lowercase()).filter { it.length >= 3 && it !in STOP }.distinct()

    /** Whether [i] matches one of [terms]: a word of its sender or subject starting with the term. */
    fun matches(i: MailItem, terms: List<String>): Boolean {
        if (terms.isEmpty()) return false
        val ws = words((i.from + " " + i.subject).lowercase())
        return terms.any { t -> ws.any { it.startsWith(t) || (it.length >= 4 && t.startsWith(it)) } }
    }

    /** Emails of the last [MailNeedsRules.WINDOW_MS] still on a device: not gone from the inbox some other way. */
    private fun recent(items: List<MailItem>, nowMs: Long): List<MailItem> =
        items.filter { nowMs - it.receivedAtMs <= MailNeedsRules.WINDOW_MS && it.receivedAtMs <= nowMs + 60_000L }
            .sortedWith(compareByDescending<MailItem> { it.receivedAtMs }.thenBy { it.id })

    private fun state(i: MailItem, nowMs: Long, cal: LocalCalendar): String? = when {
        MailNeedsRules.done(i) -> "done (archived)"
        i.goneAtMs != null -> "no longer in the inbox"
        i.snoozedUntilMs != null && i.snoozedUntilMs > nowMs -> "snoozed till ${untilLabel(i.snoozedUntilMs, nowMs, cal)}"
        MailNeedsRules.replySent(i) -> "replied, still in the inbox"
        else -> null
    }

    /** "15:00" today, "tomorrow 08:00", "Mon 19 Oct" after. */
    private fun untilLabel(atMs: Long, nowMs: Long, cal: LocalCalendar): String {
        val today = cal.epochDayOf(nowMs)
        val day = cal.epochDayOf(atMs)
        val time = LocalClock.formatMinute(cal.minuteOfDay(atMs))
        return when (day) {
            today -> time
            today + 1 -> "tomorrow $time"
            else -> CivilDate.shortLabel(day)
        }
    }

    /**
     * The Ask lines (kind `mail`): "Mail · 6 need you · Personal 4 · Business 2 · 1 snoozed · last 14 days", then each
     * email (the ones [terms] name first, then those that need him, newest first): "Sam Taylor · Personal · Mon 12 Oct ·
     * “Invoice for October” · the preview's first words", with "done (archived)" or "snoozed till …" where so.
     */
    fun askLines(items: List<MailItem>, question: String, nowMs: Long, cal: LocalCalendar): List<String> {
        val all = recent(items, nowMs)
        val needing = all.filter { MailNeedsRules.showing(it, nowMs) }
        val snoozed = all.count { it.goneAtMs == null && !MailNeedsRules.done(it) && it.snoozedUntilMs != null && it.snoozedUntilMs > nowMs }
        val byAccount = needing.groupBy { it.account }.entries.sortedByDescending { it.value.size }.map { "${it.key} ${it.value.size}" }
        val head = listOfNotNull(
            "Mail",
            if (needing.isEmpty()) "nothing needs him" else "${needing.size} need${if (needing.size == 1) "s" else ""} him",
            byAccount.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            if (snoozed > 0) "$snoozed snoozed" else null,
            "emails sorted as needing him, last 14 days",
        ).joinToString(" · ")
        val t = terms(question)
        val ordered = (all.filter { matches(it, t) } + needing + all).distinctBy { it.id }
        val lines = ordered.take(ASK_EMAILS).map { i ->
            listOfNotNull(
                i.from, i.account, MailNeedsRules.whenLabel(i.receivedAtMs, nowMs, cal),
                "“${i.subject.trim()}”", state(i, nowMs, cal), i.preview?.trim()?.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
        }
        return listOf(head) + lines
    }

    /**
     * The device's own answer when the AI can't be reached and [question] [isAbout] mail; null otherwise. Named
     * ("what did the school say?"): the newest matching emails, or that none of the recent ones are from them. Else
     * how many need him and the first few, by who and subject.
     */
    fun localAnswer(question: String, items: List<MailItem>, nowMs: Long, cal: LocalCalendar, tidied: List<MailTidiedHit> = emptyList()): String? {
        if (!isAbout(question)) return null
        val all = recent(items, nowMs)
        val t = terms(question)
        if (t.isNotEmpty()) {
            val hits = all.filter { matches(it, t) }
            // The companion, slice 7: what Navilife tidied from them, after what needed him.
            val tidiedSaid = when {
                tidied.isEmpty() -> null
                tidied.size == 1 -> "Navilife tidied one: ${tidiedSaid(tidied[0], nowMs, cal)}."
                else -> "Navilife tidied ${tidied.size}: " + tidied.take(LOCAL_EMAILS).joinToString("; ") { tidiedSaid(it, nowMs, cal) } +
                    (if (tidied.size > LOCAL_EMAILS) "; and ${tidied.size - LOCAL_EMAILS} more." else ".")
            }
            if (hits.isEmpty()) {
                if (tidiedSaid != null) return "Nothing from that needed you. $tidiedSaid"
                if (!WORDS.any { it in question.lowercase() }) return null
                return "Nothing from that among the emails that needed you in the last 14 days."
            }
            val said = hits.take(LOCAL_EMAILS).joinToString("; ") { i ->
                listOfNotNull("${i.from}: “${i.subject.trim()}”", "${i.account}, ${MailNeedsRules.whenLabel(i.receivedAtMs, nowMs, cal)}",
                    state(i, nowMs, cal)).joinToString(", ")
            }
            val answer = if (hits.size == 1) "$said." else "${hits.size} emails: $said" + (if (hits.size > LOCAL_EMAILS) "; and ${hits.size - LOCAL_EMAILS} more." else ".")
            return if (tidiedSaid == null) answer else "$answer $tidiedSaid"
        }
        val needing = all.filter { MailNeedsRules.showing(it, nowMs) }
        if (needing.isEmpty()) return "No emails need you right now."
        val named = needing.take(LOCAL_EMAILS).map { "${it.from} about “${it.subject.trim()}”" }
        val more = needing.size - named.size
        val head = MailNeedsRules.headline(needing.size)
        return "$head: " + named.joinToString(", ") + (if (more > 0) ", and $more more." else ".")
    }

    /**
     * The companion, slice 7: the emails Navilife tidied in the last [MailNeedsRules.WINDOW_MS] whose sender, domain or
     * subject a word of [question] names, newest first, each once; none when the question names nothing (a plain "my
     * email" is about what needs him). Kept emails aren't tidied, so they're not here.
     */
    fun tidiedHits(batches: List<MailTidyBatch>, question: String, nowMs: Long): List<MailTidiedHit> {
        val t = terms(question)
        if (t.isEmpty()) return emptyList()
        return batches.flatMap { b ->
            b.emails.filter { e ->
                e.tidy != MailTidy.KEEP && nowMs - e.receivedAtMs <= MailNeedsRules.WINDOW_MS && e.receivedAtMs <= nowMs + 60_000L &&
                    tidiedMatches(e, t)
            }.map { e ->
                MailTidiedHit(e, b.account, b.undoneAtMs != null || b.emailDone.any { it.key == e.key && it.action == MailTidiedAction.PUT_BACK })
            }
        }.sortedWith(compareByDescending<MailTidiedHit> { it.email.receivedAtMs }.thenBy { it.email.key }).distinctBy { it.email.key }
    }

    private fun tidiedMatches(e: MailTidiedEmail, terms: List<String>): Boolean {
        val ws = words((e.from + " " + e.domain.orEmpty().replace('.', ' ') + " " + e.subject).lowercase())
        return terms.any { t -> ws.any { it.startsWith(t) || (it.length >= 4 && t.startsWith(it)) } }
    }

    /** Where a tidied email is now: "in the archive", "in Trash", "back in the inbox". */
    private fun whereNow(h: MailTidiedHit): String = when {
        h.putBack || h.email.tidy == MailTidy.RESCUE -> "back in the inbox"
        h.email.tidy == MailTidy.TRASH || h.email.tidy == MailTidy.JUNK_TRASH -> "in Trash"
        else -> "in the archive"
    }

    /**
     * The companion, slice 7: the Ask lines (kind `mail`, after [askLines]) for the tidied emails a question names:
     * "Tidied · 3 from what he asked about, moved out of the inbox by Navilife, last 14 days", then each, "Tidied ·
     * Amazon · Personal · Mon 12 Oct · “Your order has shipped” · Receipt £12.99 · in the archive". Empty for none.
     */
    fun tidiedLines(hits: List<MailTidiedHit>, nowMs: Long, cal: LocalCalendar): List<String> {
        if (hits.isEmpty()) return emptyList()
        val head = "Tidied · ${hits.size} from what he asked about, moved out of the inbox by Navilife's autopilot, last 14 days"
        return listOf(head) + hits.take(TIDIED_EMAILS).map { h ->
            listOf("Tidied", h.email.from, h.account, MailNeedsRules.whenLabel(h.email.receivedAtMs, nowMs, cal),
                "“${h.email.subject.trim()}”", MailTidiedRules.chip(h.email), whereNow(h)).joinToString(" · ")
        }
    }

    /** "Amazon: “Your order has shipped”, Receipt £12.99, Personal, Mon 12 Oct, in the archive". */
    private fun tidiedSaid(h: MailTidiedHit, nowMs: Long, cal: LocalCalendar): String =
        "${h.email.from}: “${h.email.subject.trim()}”, ${MailTidiedRules.chip(h.email)}, ${h.account}, " +
            "${MailNeedsRules.whenLabel(h.email.receivedAtMs, nowMs, cal)}, ${whereNow(h)}"

    /** The brief's mail: the emails that need him now, newest first, with the first [BRIEF_SENDERS] senders made plain. */
    fun brief(items: List<MailItem>, nowMs: Long): BriefMail? {
        val needing = MailNeedsRules.showingSorted(items, nowMs)
        if (needing.isEmpty()) return null
        return BriefMail(
            count = needing.size,
            business = needing.count { it.account == MailLabel.BUSINESS.label },
            senders = needing.mapNotNull { plainName(it.from) }.distinct().take(BRIEF_SENDERS),
        )
    }

    /**
     * A sender's name as the brief may say it: letters, digits, spaces and ' & . - only, at most [BRIEF_NAME]
     * characters; null when anything else is in it (an address, a link, markup) or nothing is left.
     */
    fun plainName(raw: String): String? {
        val s = raw.trim().trim('"', '\'').trim()
        if (s.isEmpty() || s.length > BRIEF_NAME) return null
        if (s.any { !(it.isLetterOrDigit() || it == ' ' || it == '\'' || it == '&' || it == '.' || it == '-') }) return null
        if (!s.any { it.isLetter() }) return null
        val out = StringBuilder()
        var space = false
        for (c in s) {
            if (c == ' ') { space = true; continue }
            if (space && out.isNotEmpty()) out.append(' ')
            space = false
            out.append(c)
        }
        return out.toString()
    }

    /**
     * The brief's sentence: "3 emails need you, from Sam Taylor, Oakfield School and 1 more." · "One email needs you,
     * from Sam Taylor." · "2 emails need you, 1 for the business." when no sender can be said. [named]: say senders.
     */
    fun briefSentence(m: BriefMail, named: Boolean = true): String {
        val head = if (m.count == 1) "One email needs you" else "${m.count} emails need you"
        val senders = if (named) m.senders else emptyList()
        if (senders.isEmpty()) {
            val biz = if (m.business > 0 && m.business < m.count) ", ${m.business} for the business" else if (m.business > 0 && m.count > 1) ", all for the business" else ""
            return "$head$biz."
        }
        val more = m.count - senders.size
        val list = when {
            more > 0 -> senders.joinToString(", ") + " and $more more"
            senders.size == 1 -> senders[0]
            else -> senders.dropLast(1).joinToString(", ") + " and " + senders.last()
        }
        return "$head, from $list."
    }

    /** Letters and digits runs (an apostrophe inside a word is dropped: "what's" → "whats"). By hand, no regex. */
    private fun words(s: String): List<String> {
        val out = mutableListOf<String>()
        val w = StringBuilder()
        for (c in s) {
            when {
                c.isLetterOrDigit() -> w.append(c)
                (c == '\'' || c == '’') && w.isNotEmpty() -> {}
                else -> if (w.isNotEmpty()) { out += w.toString(); w.clear() }
            }
        }
        if (w.isNotEmpty()) out += w.toString()
        return out
    }
}
