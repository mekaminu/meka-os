package os.meka.core.domain

/**
 * Needs you grouped and folding (APPROVED by Meka 2026-11-10 21:51, `docs/design/needs-you/needs-you-grouped.dc.html`),
 * non-AI and pure: every section is one box with an icon, a title, a count and a one-line peek, tapped to open or close.
 * To decide and Messages start open, the rest closed; Navilife remembers what he left open; Open all / Close all sits at
 * the top; a filter chip opens just its groups; long groups show [PAGE_SIZE] a page with "1–5 of 14" and ‹ ›.
 * Nothing here sends, marks or answers anything: it only says what is open and what a closed box says.
 */
enum class NeedsYouGroup(
    /** The id kept on the device for what he left open. */
    val id: String,
    val title: String,
    val openByDefault: Boolean,
    /** A 24-unit stroke path, drawn the same on the Fold and the Mac (the mockup's icons). */
    val path: String,
) {
    DECIDE("decide", "To decide", true, "M12 8v5M12 16.5v.5M12 3l9 16H3z"),
    MESSAGES("msgs", "Messages", true, "M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z"),
    FYI("fyi", "FYI", false, "M12 16v-4M12 8h.01M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z"),
    CHATS("chats", "Group chats", false, "M17 20v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2M10 10a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM21 20v-2a4 4 0 0 0-3-3.9"),
    MAIL("mail", "Mail to answer", false, "M4 6h16v12H4zM4 7l8 6 8-6"),
    RENEWALS("renew", "Renewals found in mail", false, "M21 12a9 9 0 1 1-3-6.7L21 8M21 3v5h-5"),
    TIDIED("tidy", "Mail tidied today", false, "M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14"),
}

/** A box's head: its title, the count in its badge, the peek under the title and what a screen reader says. */
data class NeedsYouGroupHead(
    val group: NeedsYouGroup,
    val title: String,
    /** "4"; empty when the group has nothing to count. */
    val count: String,
    /** Closed: what is inside in one line. Open: the page line ("1–5 of 14") or nothing. */
    val peek: String,
    val open: Boolean,
    val spoken: String,
)

/** One page of a long group: rows [from] until [to] of [total]; [label] is "1–5 of 14", empty when one page holds all. */
data class NeedsYouPage(val page: Int, val pages: Int, val from: Int, val to: Int, val total: Int, val label: String) {
    val hasPrev: Boolean get() = page > 0
    val hasNext: Boolean get() = page < pages - 1
    fun <T> of(rows: List<T>): List<T> = rows.subList(from.coerceAtMost(rows.size), to.coerceAtMost(rows.size))
}

object NeedsYouGroupRules {
    const val PAGE_SIZE = 5
    const val OPEN_ALL = "Open all"
    const val CLOSE_ALL = "Close all"
    const val FYI_LEAD = "Nothing here needs a reply"
    const val CLEAR_ALL = "Clear all"
    const val FYI_PEEK = "No reply needed"
    const val RENEWALS_PEEK = "nothing added until you tap Add"
    const val LISTS_TITLE = "From your lists"
    const val HINT = "Tap a heading to open or close it · up to 5 in a page, ‹ › for the next · Navilife remembers which groups you left open"

    /** The groups open before he has opened or closed any: To decide and Messages. */
    val DEFAULT_OPEN: Set<NeedsYouGroup> = NeedsYouGroup.entries.filter { it.openByDefault }.toSet()

    /** The chip's groups: Decide → To decide; Reply → Messages; FYI → FYI and Group chats; Mail → the three mail boxes. */
    fun shows(filter: NeedsYouFilter, group: NeedsYouGroup): Boolean = when (filter) {
        NeedsYouFilter.ALL -> true
        NeedsYouFilter.DECIDE -> group == NeedsYouGroup.DECIDE
        NeedsYouFilter.REPLY -> group == NeedsYouGroup.MESSAGES
        NeedsYouFilter.FYI -> group == NeedsYouGroup.FYI || group == NeedsYouGroup.CHATS
        NeedsYouFilter.MAIL -> group == NeedsYouGroup.MAIL || group == NeedsYouGroup.RENEWALS || group == NeedsYouGroup.TIDIED
    }

    fun toggle(open: Set<NeedsYouGroup>, group: NeedsYouGroup): Set<NeedsYouGroup> =
        if (group in open) open - group else open + group

    /** Picking a chip other than All opens every group it shows, so its list is there at once (the mockup). */
    fun pick(open: Set<NeedsYouGroup>, filter: NeedsYouFilter): Set<NeedsYouGroup> =
        if (filter == NeedsYouFilter.ALL) open else open + NeedsYouGroup.entries.filter { shows(filter, it) }

    /** "Close all" while any shown group is open, else "Open all". */
    fun allLabel(open: Set<NeedsYouGroup>, shown: List<NeedsYouGroup>): String =
        if (shown.any { it in open }) CLOSE_ALL else OPEN_ALL

    /** Open all opens every shown group; Close all closes them (groups not shown keep their state). */
    fun all(open: Set<NeedsYouGroup>, shown: List<NeedsYouGroup>): Set<NeedsYouGroup> =
        if (shown.any { it in open }) open - shown.toSet() else open + shown

    /** What is kept on the device: the open groups' ids, comma separated ("" = all closed). */
    fun encode(open: Set<NeedsYouGroup>): String = NeedsYouGroup.entries.filter { it in open }.joinToString(",") { it.id }

    /** What he left open; [DEFAULT_OPEN] before he has opened or closed anything (null). Unknown ids are skipped. */
    fun decode(saved: String?): Set<NeedsYouGroup> {
        if (saved == null) return DEFAULT_OPEN
        val ids = saved.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return NeedsYouGroup.entries.filter { it.id in ids }.toSet()
    }

    /** The page [page] of [total] rows, [PAGE_SIZE] a page, clamped to the pages there are. */
    fun page(total: Int, page: Int, size: Int = PAGE_SIZE): NeedsYouPage {
        val n = total.coerceAtLeast(0)
        val pages = maxOf(1, (n + size - 1) / size)
        val p = page.coerceIn(0, pages - 1)
        val from = p * size
        val to = minOf(n, from + size)
        val label = if (n > size) "${from + 1}–$to of $n" else ""
        return NeedsYouPage(p, pages, from, to, n, label)
    }

    /** "Alex, Card game testing, Sam, Jo": the first [max] names, "+ 2 more" after them. */
    fun names(names: List<String>, max: Int = 4): String {
        val shown = names.map { it.trim() }.filter { it.isNotEmpty() }
        if (shown.isEmpty()) return ""
        val head = shown.take(max).joinToString(", ")
        return if (shown.size > max) "$head + ${shown.size - max} more" else head
    }

    /** "1 approval · 2 requests · 1 from your lists": what waits in To decide, by kind, leaving out what is nothing. */
    fun decidePeek(approvals: Int, requests: Int, asks: Int, fromLists: Int): String = listOfNotNull(
        plural(approvals, "approval", "approvals").takeIf { approvals > 0 },
        plural(requests, "request", "requests").takeIf { requests > 0 },
        plural(asks, "plan to make", "plans to make").takeIf { asks > 0 },
        "$fromLists from your lists".takeIf { fromLists > 0 },
    ).joinToString(" · ")

    /**
     * Slice 2: To decide's peek from what is really in it: "1 approval · 2 tasks · 2 to chase from your lists" (the
     * stack's lists card by its own line, every other stack card a task to decide).
     */
    fun decidePeekOf(approvals: Int, requests: Int, asks: Int, cards: List<DecisionCard>): String {
        val lists = cards.firstOrNull { it.kind == DecisionKind.LISTS }
        val tasks = cards.count { it.kind != DecisionKind.LISTS }
        return listOfNotNull(
            plural(approvals, "approval", "approvals").takeIf { approvals > 0 },
            plural(requests, "request", "requests").takeIf { requests > 0 },
            plural(asks, "plan to make", "plans to make").takeIf { asks > 0 },
            plural(tasks, "task", "tasks").takeIf { tasks > 0 },
            lists?.let { listsPeek(it.why) },
        ).joinToString(" · ")
    }

    /** "2 to chase from your lists" (the peek); "From your lists" when the line says nothing. */
    fun listsPeek(why: String): String = if (why.isBlank()) LISTS_TITLE else "${why.trim()} from your lists"

    /** "2 to chase · from your lists": the one row inside To decide that takes the big lists card's place. */
    fun listsLead(why: String): String = if (why.isBlank()) LISTS_TITLE else "${why.trim()} · from your lists"

    /**
     * The big "From your lists" card leaves the stack while boxed: it is [listsLead]'s row with Go through instead.
     * Returns the lists card (or null) and the cards the stack still shows, in their order.
     */
    fun splitLists(cards: List<DecisionCard>): Pair<DecisionCard?, List<DecisionCard>> {
        val lists = cards.firstOrNull { it.kind == DecisionKind.LISTS }
        return lists to cards.filter { it !== lists }
    }

    /**
     * To decide's foot line, where the "While you were at work" card was: at work, the held line ("At work until 17:30 ·
     * 37 held for later ›", [heldLabel]); after work, "While you were at work · 2 people · 5 messages · 1 urgent ›".
     * Null when nothing is held or waiting. A tap opens the after-work summary; nothing is cleared or marked read.
     */
    fun afterWorkFoot(summary: AfterWorkSummary, heldLabel: String?): String? {
        if (summary.isEmpty) return null
        if (heldLabel != null) return "$heldLabel ›"
        val urgent = summary.urgentPeople.takeIf { it > 0 }?.let { "$it urgent" }
        return listOfNotNull(summary.title, summary.headline, urgent).joinToString(" · ") + " ›"
    }

    /** "No reply needed · Alex, Sam, Jo". */
    fun fyiPeek(names: List<String>): String = listOf(FYI_PEEK, names(names, 3)).filter { it.isNotEmpty() }.joinToString(" · ")

    /** "Streamly, Music Plus · nothing added until you tap Add". */
    fun renewalsPeek(names: List<String>): String = listOf(names(names, 3), RENEWALS_PEEK).filter { it.isNotEmpty() }.joinToString(" · ")

    /** "41 tidied · 12 newsletters · 24 promos · 5 junk & scams" from the tiles. */
    fun tidiedPeek(tiles: List<NeedsYouMailTile>): String = tiles.joinToString(" · ") { "${it.number} ${it.label}" }

    /**
     * The head of [group]: [count] (0 shows no number), the [peek] while closed, and while open the page's line when it
     * has more than one page ([page]) or nothing.
     */
    fun head(group: NeedsYouGroup, count: Int, peek: String, open: Boolean, page: NeedsYouPage? = null): NeedsYouGroupHead {
        val shownPeek = if (open) page?.label.orEmpty() else peek
        val countText = if (count > 0) count.toString() else ""
        val spoken = buildString {
            append(group.title)
            if (count > 0) append(", $count")
            append(". ")
            append(if (open) "Open" else "Closed")
            if (!open && peek.isNotBlank()) append(". $peek")
            append('.')
        }
        return NeedsYouGroupHead(group, group.title, countText, shownPeek, open, spoken)
    }

    /** "1 to decide · 4 to reply · 5 FYI · 14 mail", leaving out what is nothing; "All clear" when all are. */
    fun subtitle(decide: Int, reply: Int, fyi: Int, mail: Int): String {
        val base = NeedsYouLayoutRules.subtitle(decide, reply, fyi)
        if (mail <= 0) return base
        return if (base == NeedsYouLayoutRules.ALL_CLEAR) "$mail mail" else "$base · $mail mail"
    }

    private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
}
