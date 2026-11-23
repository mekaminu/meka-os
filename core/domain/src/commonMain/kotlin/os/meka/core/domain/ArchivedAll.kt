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

/**
 * One kind chip (Meka 2026-11-18 23:34): "Last 30 days", then today's four as the tiles count them ("Today · 84",
 * "Newsletters · 26", "Promos · 38", "Junk & scams · 20"); a tile's tap opens the list on its chip.
 */
data class ArchivedAllKindChip(
    /** A [TidiedKind.id]; null for the last 30 days. */
    val kind: String?,
    val label: String,
    val selected: Boolean,
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
    /** The kind chips while today has tidies or a kind is chosen; else empty. */
    val kinds: List<ArchivedAllKindChip> = emptyList(),
    /** The chosen [TidiedKind.id]: today's emails of that kind; null for the last 30 days. */
    val kind: String? = null,
    /** "All senders" first, then the senders with the most tidied emails (the chosen one always shown). */
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
    /** Tidied mail, slice 4: "Trash all 38 promos" behind a confirm, on a chosen kind; null otherwise or when none can go. */
    val trashAll: ArchivedTrashAll? = null,
)

/**
 * Tidied mail, slice 5 (Meka 2026-11-18 23:34, part (4): "five a page with ‹ › as the rest of Needs you"): one page of
 * the list. [days] holds only this page's rows under their day headings; [rows] is every row on the page; [all] is every
 * row in the list (what a selection counts across pages); [page] is "6–10 of 38" with ‹ ›.
 */
data class ArchivedAllPageView(
    val days: List<ArchivedAllDay>,
    val rows: List<ArchivedAllRow>,
    val all: List<ArchivedAllRow>,
    val page: NeedsYouPage,
)

object ArchivedAllRules {
    const val TITLE = "All tidied mail"
    const val OPEN = "All tidied mail"
    /**
     * The sender row's first chip (week 5, A9): "All senders", never a bare "All" beside the kind row's "Today · 8"
     * and "Last 30 days", so it can't read as the day filter.
     */
    const val ALL = "All senders"
    const val CLOSE = "Close"
    /** Emails listed at once; a sender's filter lists theirs within it. */
    const val MAX_ROWS = 150
    /** Sender chips besides All. */
    const val SENDERS_SHOWN = 12
    const val EMPTY = "Nothing tidied in the last 30 days"
    const val LAST_30 = "Last 30 days"
    /** What a chosen kind shows when none of today's are of it. */
    fun emptyToday(k: TidiedKind) = if (k == TidiedKind.ALL) "Nothing tidied yet today" else "No ${k.many} tidied today"
    const val NOTE = "Every email Navilife tidied in the last 30 days, while Put back still works. " +
        "Tap one to see it, put it back or keep its sender. Shown as the sender wrote it, nothing in it is acted on."

    private const val DAY_MS = 86_400_000L

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    /** The tidies the list reads: Autopilot's and the Junk review's from the last 30 days (not the approved clean-ups). */
    private fun inWindow(batches: List<MailTidyBatch>, nowMs: Long) = batches.filter {
        !it.backlog && it.count > 0 && it.atMs <= nowMs && nowMs - it.atMs <= MailAutopilotRules.UNDO_DAYS * DAY_MS
    }

    /** Emails a page (as Needs you's groups). */
    const val PAGE_SIZE = NeedsYouGroupRules.PAGE_SIZE

    /** Page [page] of [list], [PAGE_SIZE] emails a page, newest first; a page past the end shows the last one. */
    fun paged(list: ArchivedAllList, page: Int): ArchivedAllPageView {
        val all = list.days.flatMap { it.rows }
        val p = NeedsYouGroupRules.page(all.size, page, PAGE_SIZE)
        val ids = p.of(all).map { it.id }.toSet()
        val days = list.days.mapNotNull { d -> d.rows.filter { it.id in ids }.takeIf { it.isNotEmpty() }?.let { d.copy(rows = it) } }
        return ArchivedAllPageView(days, days.flatMap { it.rows }, all, p)
    }

    /**
     * The list, filtered to one sender when [sender] (an [ArchivedAllSender.id]) is given; a sender with nothing left
     * in the window shows the whole list.
     */
    fun list(batches: List<MailTidyBatch>, sender: String?, nowMs: Long, cal: LocalCalendar, kind: String? = null): ArchivedAllList {
        val chosenKind = TidiedKind.of(kind)
        val todays = NeedsYouMailRules.today(batches, nowMs, cal)
        // A tile's kind: today's tidies that still stand, as the tiles count them; else the last 30 days.
        val window = if (chosenKind != null) todays else inWindow(batches, nowMs)
        val all = window.flatMap { b -> b.emails.filter { chosenKind == null || chosenKind.matches(it.tidy) }.map { b to it } }
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
                            row = ArchivedEmailRow(e.key, e.subject, chip, time, state, b.id, MailTidiedAskRules.swipes(b, e, nowMs).takeIf { it.any }),
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

        val tiles = NeedsYouMailRules.tiles(todays)
        val kinds = if (tiles.isEmpty() && chosenKind == null) emptyList() else {
            listOf(ArchivedAllKindChip(null, LAST_30, chosenKind == null, "Show every email tidied in the last 30 days")) +
                TidiedKind.entries.map { k ->
                    val n = tiles.firstOrNull { it.kind == k.id }?.number ?: "0"
                    ArchivedAllKindChip(k.id, "${k.chip} · $n", k == chosenKind,
                        if (k == TidiedKind.ALL) "Show today's $n tidied" else "Show today's $n ${k.many}")
                }
        }
        val listed = window.filter { it.emails.isNotEmpty() }
        val unlisted = window.filter { it.emails.isEmpty() }
        // A tidy whose list couldn't hold every email it moved: the rest are counted, not named.
        val unnamed = if (chosenKind != null) {
            val counted = tiles.firstOrNull { it.kind == chosenKind.id }?.number?.toIntOrNull() ?: 0
            maxOf(0, counted - all.size)
        } else listed.sumOf { maxOf(0, it.count - it.emails.size) }
        val since = if (chosenKind != null) "today" else "in the last 30 days"
        val noun = chosenKind?.takeIf { it != TidiedKind.ALL }
        fun nouns(n: Int) = if (noun != null) plural(n, noun.one, noun.many) else plural(n, "email")
        val summary = when {
            chosen != null -> {
                val name = bySender.getValue(chosen).first().second.from
                "$name · ${nouns(shownEmails.size)} tidied $since"
            }
            chosenKind != null && all.isEmpty() -> emptyToday(chosenKind)
            chosenKind != null -> "${nouns(all.size)} tidied today · from ${plural(bySender.size, "sender")}" +
                if (unnamed > 0) " · ${plural(unnamed, "more", "more")} not named" else ""
            all.isEmpty() -> EMPTY
            else -> "${plural(all.size, "email")} tidied in the last 30 days · from ${plural(bySender.size, "sender")}" +
                if (unnamed > 0) " · ${plural(unnamed, "more", "more")} not named" else ""
        }
        val more = if (shownEmails.size > cut.size) {
            if (chosen != null) "Showing the newest $MAX_ROWS of ${shownEmails.size}"
            else "Showing the newest $MAX_ROWS · choose a sender to see all of theirs"
        } else null
        val countsOnly = unlisted.takeIf { it.isNotEmpty() && chosen == null && chosenKind == null }?.let { u ->
            "Before Navilife listed each email: ${plural(u.size, "tidy", "tidies")} · ${plural(u.sumOf { it.count }, "email")} (counts only)"
        }
        return ArchivedAllList(
            title = TITLE,
            summary = summary,
            kinds = kinds,
            kind = chosenKind?.id,
            senders = senders,
            allSelected = chosen == null,
            days = days,
            more = more,
            countsOnly = countsOnly,
            empty = when {
                chosenKind != null -> if (all.isEmpty()) emptyToday(chosenKind) else null
                all.isEmpty() && unlisted.isEmpty() -> EMPTY
                else -> null
            },
            note = NOTE,
            spoken = "$TITLE. $summary.",
            trashAll = MailTidiedBulkRules.trashAll(
                chosenKind,
                chosen?.let { bySender.getValue(it).first().second.from },
                shownEmails.filter { (b, e) -> MailTidiedAction.TRASH in MailTidiedAskRules.available(b, e, nowMs) }
                    .map { (b, e) -> "${b.id}|${e.key}" }.distinct(),
            ),
        )
    }
}
