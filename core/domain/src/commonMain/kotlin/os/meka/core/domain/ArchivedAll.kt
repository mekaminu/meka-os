package os.meka.core.domain

/*
 * "Archived for you" shows which emails, slice 3 (Meka, 2026-10-15 20:29, item (4)): **All tidied mail**. The card lists
 * the newest tidies only ("+ 4 earlier tidies"); this is the whole list behind it: every email Autopilot and the daily
 * Junk review tidied in the last 30 days (as long as Put back works), newest first under its day, with a **sender
 * filter** (the senders with the most tidied emails as chips, "Deliveroo · 12"). Each row opens the same preview as on
 * the card ([MailTidiedAskRules.preview]: Put back, Always keep, Unsubscribe). Tidies from before the emails were listed
 * (2026-10-16) are counted in one line. Read from the synced tidies only: nothing new is stored. Every word of a
 * subject is the sender's (ADR-006): shown as plain text, never acted on. Non-AI, pure, unit-tested.
 */

/** One sender chip in the filter. */
data class ArchivedAllSender(
    /** The filter's key: the sender's name in lower case. */
    val id: String,
    /** "Deliveroo · 12". */
    val label: String,
    val selected: Boolean,
    /** "Show only Deliveroo's 12 emails". */
    val spoken: String,
)

/** One email in the list. */
data class ArchivedAllRow(
    /** "batchId|key": unique across the list (the same email can sit in two tidies, e.g. rescued then tidied). */
    val id: String,
    /** The row as the card shows it (its time is the clock only: the day is the heading). */
    val row: ArchivedEmailRow,
    /** "Deliveroo". */
    val from: String,
    /** "deliveroo.co.uk · Personal". */
    val meta: String,
    val spoken: String,
)

/** A day's emails: "Today", "Yesterday", "Wed 14 Oct". */
data class ArchivedAllDay(val id: String, val label: String, val rows: List<ArchivedAllRow>)

data class ArchivedAllList(
    /** "All tidied mail". */
    val title: String,
    /** "212 emails tidied in the last 30 days · from 46 senders", or the filter's "Deliveroo · 12 emails tidied …". */
    val summary: String,
    /** "All" first, then the senders with the most tidied emails (the chosen one always shown). */
    val senders: List<ArchivedAllSender>,
    /** No sender chosen. */
    val allSelected: Boolean,
    val days: List<ArchivedAllDay>,
    /** "Showing the newest 150 · choose a sender to see all of theirs" when the list was cut; else null. */
    val more: String?,
    /** "Before Navilife listed each email: 3 tidies · 41 emails (counts only)"; else null. */
    val countsOnly: String?,
    /** What shows when there is nothing: "Nothing tidied in the last 30 days". */
    val empty: String?,
    val note: String,
    val spoken: String,
)

object ArchivedAllRules {
    const val TITLE = "All tidied mail"
    const val OPEN = "All tidied mail"
    const val ALL = "All"
    const val CLOSE = "Close"
    /** Emails listed at once; a sender's filter lists theirs within it. */
    const val MAX_ROWS = 150
    /** Sender chips besides All. */
    const val SENDERS_SHOWN = 12
    const val EMPTY = "Nothing tidied in the last 30 days"
    const val NOTE = "Every email Navilife tidied in the last 30 days, while Put back still works. " +
        "Tap one to see it, put it back or keep its sender. Shown as the sender wrote it, nothing in it is acted on."

    private const val DAY_MS = 86_400_000L

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    /** The tidies the list reads: Autopilot's and the Junk review's from the last 30 days (not the approved clean-ups). */
    private fun inWindow(batches: List<MailTidyBatch>, nowMs: Long) = batches.filter {
        !it.backlog && it.count > 0 && it.atMs <= nowMs && nowMs - it.atMs <= MailAutopilotRules.UNDO_DAYS * DAY_MS
    }

    /**
     * The list, filtered to one sender when [sender] (an [ArchivedAllSender.id]) is given; a sender with nothing left
     * in the window shows the whole list.
     */
    fun list(batches: List<MailTidyBatch>, sender: String?, nowMs: Long, cal: LocalCalendar): ArchivedAllList {
        val window = inWindow(batches, nowMs)
        val all = window.flatMap { b -> b.emails.map { b to it } }
            .sortedWith(compareByDescending<Pair<MailTidyBatch, MailTidiedEmail>> { it.second.receivedAtMs }.thenBy { it.second.key })
        val bySender = all.groupBy { it.second.from.lowercase() }
        val chosen = sender?.lowercase()?.takeIf { it in bySender }
        val shownEmails = if (chosen != null) bySender.getValue(chosen) else all
        val cut = shownEmails.take(MAX_ROWS)
        val today = cal.epochDayOf(nowMs)

        val days = cut.groupBy { cal.epochDayOf(it.second.receivedAtMs) }.entries
            .sortedByDescending { it.key }
            .map { (day, es) ->
                ArchivedAllDay(
                    id = "d$day", label = CallLogRules.dayLabel(day, today),
                    rows = es.map { (b, e) ->
                        val chip = MailTidiedRules.chip(e)
                        val time = LocalClock.formatMinute(cal.minuteOfDay(e.receivedAtMs))
                        // An email's own ask first; else what happened to its whole tidy ("Put back in the inbox").
                        val state = MailTidiedAskRules.state(b, e.key) ?: MailAutopilotPanelRules.state(b)
                        val meta = listOfNotNull(e.domain, b.account).joinToString(" · ")
                        ArchivedAllRow(
                            id = "${b.id}|${e.key}",
                            row = ArchivedEmailRow(e.key, e.subject, chip, time, state, b.id),
                            from = e.from, meta = meta,
                            spoken = listOfNotNull(e.from, e.subject, chip, time, state).joinToString(", ") + ". Opens its preview",
                        )
                    },
                )
            }

        // The chips: senders by how many emails were tidied (then by name), the chosen one kept in.
        val ranked = bySender.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<Pair<MailTidyBatch, MailTidiedEmail>>>> { it.value.size }.thenBy { it.key },
        )
        val top = ranked.take(SENDERS_SHOWN).let { t ->
            if (chosen != null && t.none { it.key == chosen }) t.dropLast(1) + ranked.first { it.key == chosen } else t
        }
        val senders = top.map { (id, es) ->
            val name = es.first().second.from
            ArchivedAllSender(id, "$name · ${es.size}", id == chosen, "Show only $name's ${plural(es.size, "email")}")
        }

        val listed = window.filter { it.emails.isNotEmpty() }
        val unlisted = window.filter { it.emails.isEmpty() }
        // A tidy whose list couldn't hold every email it moved: the rest are counted, not named.
        val unnamed = listed.sumOf { maxOf(0, it.count - it.emails.size) }
        val summary = when {
            chosen != null -> {
                val name = bySender.getValue(chosen).first().second.from
                "$name · ${plural(shownEmails.size, "email")} tidied in the last 30 days"
            }
            all.isEmpty() -> EMPTY
            else -> "${plural(all.size, "email")} tidied in the last 30 days · from ${plural(bySender.size, "sender")}" +
                if (unnamed > 0) " · ${plural(unnamed, "more", "more")} not named" else ""
        }
        val more = if (shownEmails.size > cut.size) {
            if (chosen != null) "Showing the newest $MAX_ROWS of ${shownEmails.size}"
            else "Showing the newest $MAX_ROWS · choose a sender to see all of theirs"
        } else null
        val countsOnly = unlisted.takeIf { it.isNotEmpty() && chosen == null }?.let { u ->
            "Before Navilife listed each email: ${plural(u.size, "tidy", "tidies")} · ${plural(u.sumOf { it.count }, "email")} (counts only)"
        }
        return ArchivedAllList(
            title = TITLE,
            summary = summary,
            senders = senders,
            allSelected = chosen == null,
            days = days,
            more = more,
            countsOnly = countsOnly,
            empty = if (all.isEmpty() && unlisted.isEmpty()) EMPTY else null,
            note = NOTE,
            spoken = "$TITLE. $summary.",
        )
    }
}
