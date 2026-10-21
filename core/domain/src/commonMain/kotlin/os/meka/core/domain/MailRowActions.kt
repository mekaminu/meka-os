package os.meka.core.domain

/**
 * Inbox junk fix, slice 3 (Meka, 2026-10-17 03:56): **a note to self**. An email from one of his own addresses is FYI
 * ([MailSortRules.mine]), never "needs you"; but one that holds a link (he sent himself something to read later) comes
 * to From your mail as a "Note to self" row offering **Save as task** (a task named after the subject or the link's
 * site, the link in its notes; the email archived) and **Done**. Headers and the provider's preview only.
 */
object MailNoteRules {
    const val SAVE = "Save as task"
    const val NOTE = "Note to self"
    /** The longest site name kept on the `mail_item`. */
    const val MAX_SITE = 60

    /** The first web link in [subject] or [preview] whole (not cut by the preview's "…"), or null. */
    fun url(subject: String?, preview: String?): String? {
        for (text in listOfNotNull(subject, preview)) {
            for (raw in text.split(' ', '\n', '\t', '<', '>', '"', '(', ')')) {
                val w = raw.trimEnd('.', ',', ';', ':', '!', '?', '\'')
                if (w.endsWith("…")) continue
                val lower = w.lowercase()
                val ok = (lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("www.")) && site(w) != null
                if (ok) return w
            }
        }
        return null
    }

    /** "bbc.co.uk" for "https://www.bbc.co.uk/news/…": the host without "www.", or null when it isn't one. */
    fun site(link: String): String? {
        var s = link.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        val host = s.takeWhile { it != '/' && it != '?' && it != '#' && it != ':' }.lowercase().removePrefix("www.")
        if (host.length !in 4..MAX_SITE || '.' !in host || host.startsWith('.') || host.endsWith('.')) return null
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        return host
    }

    /**
     * The site the server writes as [MailItemFields.NOTE] for an inbox email: only one sorted as his own
     * ([MailSortReason.OWN]) that carries a link (a word that starts a link but is cut by the preview still counts for
     * the site); "" otherwise.
     */
    fun noteSite(reason: MailSortReason?, subject: String?, preview: String?): String {
        if (reason != MailSortReason.OWN) return ""
        url(subject, preview)?.let { u -> site(u)?.let { return it } }
        for (text in listOfNotNull(subject, preview)) {
            for (w in text.split(' ', '\n', '\t')) {
                val l = w.lowercase()
                if (l.startsWith("https://") || l.startsWith("http://") || l.startsWith("www.")) site(w.removeSuffix("…"))?.let { return it }
            }
        }
        return ""
    }

    /** "Note to self · a link to bbc.co.uk", above the sender. */
    fun line(site: String): String = "$NOTE · a link to $site"

    /** The task's name: the subject (without Re:/Fwd:) when it says something, else "Look at bbc.co.uk". */
    fun taskTitle(i: MailItem, site: String): String {
        val t = MailNeedsRules.taskTitle(i)
        val bare = t == "Email from ${i.from}" || site(t) != null && t.split(' ').size == 1
        return if (bare) "Look at $site" else t
    }

    /** "Link you emailed yourself: https://… · Personal · Sat 17 Oct" (the site when the preview cut the link). */
    fun taskNotes(i: MailItem, site: String, cal: LocalCalendar): String =
        "Link you emailed yourself: ${url(i.subject, i.preview) ?: site} · ${i.account} · ${CivilDate.shortLabel(cal.epochDayOf(i.receivedAtMs))}"
}

/** The answers an email row in From your mail can offer (both apps draw them from [MailRowActionRules]). */
enum class MailRowAction { BOOK, SUGGEST, REPLY, DONE, TASK, BIN, WAITING, SNOOZE, UNSUBSCRIBE, ALWAYS_TIDY, BLOCK, TO_DAY }

/** What the row shows straight away, and what waits under ⋯ More. */
data class MailRowActions(val shown: List<MailRowAction>, val more: List<MailRowAction>)

/**
 * Inbox junk fix, slice 3 (4): **every answer looks tappable**. He read the grey answers as "not activated", so every
 * answer on an email wears the same accent text (Bin & block keeps the critical colour), and only the main ones show:
 * Book / Suggest times when they apply, **Reply · Done · Task**, and on a possible scam **Bin & block**; the rest
 * (Waiting for, Snooze, Unsubscribe, Always tidy, Block sender) unfold under **⋯ More** (expand spring; reduced
 * motion: cross-fade), **Fewer** folds them back. A note to self shows **Save as task · Done**, Snooze under More.
 */
object MailRowActionRules {
    const val MORE = "⋯ More"
    const val FEWER = "Fewer"

    /**
     * The row's answers. [tidy], [block] and [unsubscribe] say whether this surface offers them at all (the ten-minute
     * inbox and the card do; null callbacks elsewhere). [toDay] (Week board item (5)) puts **To a day…** first under More.
     */
    fun actions(row: MailNeedsRow, tidy: Boolean = true, block: Boolean = true, unsubscribe: Boolean = true, toDay: Boolean = false): MailRowActions {
        if (row.note != null) return MailRowActions(listOf(MailRowAction.TASK, MailRowAction.DONE), listOfNotNull(MailRowAction.TO_DAY.takeIf { toDay }, MailRowAction.SNOOZE))
        val shown = buildList {
            if (row.canBook) add(MailRowAction.BOOK)
            if (row.asksToMeet) add(MailRowAction.SUGGEST)
            add(MailRowAction.REPLY)
            add(MailRowAction.DONE)
            add(MailRowAction.TASK)
            if (block && row.canBlock && row.scam) add(MailRowAction.BIN)
        }
        val more = buildList {
            if (toDay) add(MailRowAction.TO_DAY)
            add(MailRowAction.WAITING)
            add(MailRowAction.SNOOZE)
            if (unsubscribe && row.canUnsubscribe) add(MailRowAction.UNSUBSCRIBE)
            if (tidy) add(MailRowAction.ALWAYS_TIDY)
            if (block && row.canBlock && !row.scam) add(MailRowAction.BLOCK)
        }
        return MailRowActions(shown, more)
    }

    /** The answer's label on this row. */
    fun label(a: MailRowAction, row: MailNeedsRow): String = when (a) {
        MailRowAction.BOOK -> MeetingPickRules.BOOK
        MailRowAction.SUGGEST -> MeetingRequestRules.SUGGEST
        MailRowAction.REPLY -> MailNeedsRules.REPLY
        MailRowAction.DONE -> MailNeedsRules.DONE
        MailRowAction.TASK -> if (row.note != null) MailNoteRules.SAVE else MailNeedsRules.TASK
        MailRowAction.BIN -> MailSenderRuleRules.BIN
        MailRowAction.WAITING -> MailNeedsRules.WAITING
        MailRowAction.SNOOZE -> MailNeedsRules.SNOOZE
        MailRowAction.UNSUBSCRIBE -> MailCardUnsubRules.UNSUBSCRIBE
        MailRowAction.ALWAYS_TIDY -> MailSenderRuleRules.ALWAYS_TIDY
        MailRowAction.BLOCK -> MailSenderRuleRules.BLOCK
        MailRowAction.TO_DAY -> BoardFeedRules.TO_DAY
    }

    /** What a screen reader says for it. */
    fun spoken(a: MailRowAction, row: MailNeedsRow): String = when (a) {
        MailRowAction.BOOK -> "Book it: ${row.pickedLine ?: ""}, in your calendar"
        MailRowAction.SUGGEST -> "Put your free times in a reply to ${row.from}; nothing is sent until you tap Send"
        MailRowAction.REPLY -> "Reply to ${row.from}"
        MailRowAction.DONE -> "${MailNeedsRules.DONE}: archive ${row.subject}"
        MailRowAction.TASK -> if (row.note != null) "Save ${row.subject} as a task and archive the email" else "Make a task from ${row.subject} and archive the email"
        MailRowAction.BIN -> "Bin and block ${row.from}: this email and their later ones go to Trash"
        MailRowAction.WAITING -> "Waiting for ${row.from} on ${row.subject}, chase in 3 days"
        MailRowAction.SNOOZE -> "${MailNeedsRules.SNOOZE} ${row.subject} till tomorrow 8 o'clock"
        MailRowAction.UNSUBSCRIBE -> "Unsubscribe from ${row.from} and archive this email"
        MailRowAction.ALWAYS_TIDY -> "Archive this, and ${row.from}'s future emails skip your inbox and go to Archive"
        MailRowAction.BLOCK -> "Block ${row.from}: this email and their later ones go to Trash"
        MailRowAction.TO_DAY -> BoardFeedRules.spoken(row.subject)
    }

    /** Bin & block keeps the critical colour; every other answer wears the accent. */
    fun critical(a: MailRowAction): Boolean = a == MailRowAction.BIN

    fun moreSpoken(open: Boolean, row: MailNeedsRow): String =
        if (open) "Fewer answers for ${row.from}" else "More answers for ${row.from}: waiting for, snooze, unsubscribe, always tidy, block"
}
