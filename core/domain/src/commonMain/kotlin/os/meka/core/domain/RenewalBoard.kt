package os.meka.core.domain

/*
 * Renewals keep their email, slice 2 (Meka 2026-10-28, mockup "Renewals" approved 22:20): the Renewals list as cards.
 * Three tiles (next 30 days · to cancel · a year), the tabs Coming up · All · Done, and each renewal a card with a date
 * tile, its cost and a "cancel in N days" badge. An open card shows Renews · Cancel by · Repeats tiles, the From the
 * email box and Renewed · Cancelled it · Edit; Edit is a sheet of rows (Renews, Repeats, Remind me, Cancel by, Kind,
 * Cost, Pay link), each opening its own choices. The disclaimer is said once, at the foot of the list.
 * Pure: both apps draw exactly these words.
 */

enum class RenewalTab(val label: String) { COMING_UP("Coming up"), ALL("All"), DONE("Done") }

/** A small labelled number: the board's tiles and an open card's. */
data class RenewalTile(val label: String, val value: String, val warn: Boolean = false)

data class RenewalCard(
    val id: String,
    val title: String,
    /** The date tile: "NOV" over "25". */
    val month: String,
    val day: String,
    /** "Subscription · Personal mail", "MOT · AB12 CDE", "Bill". */
    val sub: String,
    /** "£24/yr", "£9.99/mo", "£45"; null when the cost isn't known. */
    val cost: String?,
    /** "cancel in 25 days", "in 67 days", "overdue 3 days", "cancelled Thu 29 Oct". */
    val badge: String,
    /** The badge is lit (amber): a cancel-by day ahead, overdue, or needing doing now. */
    val warn: Boolean,
    /** The open card's tiles: Renews (or Due / Expires / Ends) · Cancel by · Repeats. */
    val tiles: List<RenewalTile>,
    /** On the Done tab: closed, so the card offers only Put it back. */
    val closed: Boolean,
    val spoken: String,
    val item: RenewalItem,
)

/** What a row of the Edit sheet changes. */
enum class RenewalEditKey { DUE, REPEATS, REMIND, CANCEL_BY, KIND, COST, PAY_LINK }

/** One row of the Edit sheet: "Repeats" with "Every year ›"; tapping it opens that row's choices. */
data class RenewalEditRow(val key: RenewalEditKey, val label: String, val value: String)

data class RenewalBoard(
    val tiles: List<RenewalTile>,
    val tab: RenewalTab,
    val cards: List<RenewalCard>,
    /** The line in place of cards when the tab has none; null when it has some. */
    val empty: String?,
    /** Said once, at the foot of the list. */
    val footer: String,
)

object RenewalBoardRules {
    const val NEXT_30 = "Next 30 days"
    const val TO_CANCEL = "To cancel"
    const val A_YEAR = "A year"
    const val EDIT = "Edit"
    const val SAVE = "Done"
    const val DELETE = "Delete"
    const val PUT_BACK = "Put it back"
    const val FOOTER = "Navilife never pays or cancels anything: do that on the company's own site or app."
    /** The board's tiles look this far ahead. */
    const val AHEAD_DAYS = 30

    val TABS: List<RenewalTab> = RenewalTab.entries.toList()
    /** Index access for Swift. */
    val tabCount: Int get() = TABS.size
    fun tabAt(index: Int): RenewalTab = TABS[index]

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** "Cancelled it" → "Cancelled"; "Stop tracking" → "Stopped"; "Keeping it" → "Kept". */
    fun closedWord(stopLabel: String): String = when {
        stopLabel.startsWith("Cancelled") -> "Cancelled"
        stopLabel.startsWith("Keeping") -> "Kept"
        else -> "Stopped"
    }

    fun board(v: RenewalsView, today: Long, tab: RenewalTab): RenewalBoard {
        val live = v.all
        val next30 = live.count { it.dueDay <= today + AHEAD_DAYS }
        val toCancel = live.count { c -> c.cancelByDay?.let { it >= today && it <= today + AHEAD_DAYS && c.dueDay >= today } == true }
        val tiles = listOf(
            RenewalTile(NEXT_30, "$next30"),
            RenewalTile(TO_CANCEL, "$toCancel", warn = toCancel > 0),
            RenewalTile(A_YEAR, if (v.yearlyPence > 0) RenewalRules.formatPence(roundPounds(v.yearlyPence)) else "—"),
        )
        val items = when (tab) {
            RenewalTab.COMING_UP -> v.attention + v.upcoming
            RenewalTab.ALL -> live
            RenewalTab.DONE -> v.done
        }
        val cards = items.map { card(it, today) }
        val empty = if (cards.isNotEmpty()) null else when (tab) {
            RenewalTab.COMING_UP -> if (live.isEmpty()) null else "Nothing in the next three months. All shows the rest."
            RenewalTab.ALL -> null
            RenewalTab.DONE -> "Nothing renewed off the list or cancelled in the last three months."
        }
        return RenewalBoard(tiles, tab, cards, empty, FOOTER)
    }

    /** Whole pounds for the year tile (it is an estimate): £1,139.88 → £1,140. */
    private fun roundPounds(pence: Long): Long = ((pence + 50) / 100) * 100

    fun card(i: RenewalItem, today: Long): RenewalCard {
        val d = CivilDate.fromEpochDay(i.dueDay)
        val closed = i.closedDay != null
        val (badge, warn) = badge(i, today)
        val sub = listOfNotNull(
            RenewalRules.kindLabel(i.kind).takeIf { i.kind != ObligationKind.OTHER && !i.title.contains(it, ignoreCase = true) },
            i.subject?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(" · ").ifEmpty { RenewalRules.kindLabel(i.kind) }
        val cost = i.costPence?.let { costShort(it, i.repeats) }
        val tiles = listOf(
            RenewalTile(dueWord(i), RenewalRules.dayWord(i.dueDay, today).replaceFirstChar { it.uppercase() }, warn = !closed && i.dueDay < today),
            RenewalTile(MailSourceRules.CANCEL_BY, i.cancelByDay?.let { RenewalRules.dayWord(it, today).replaceFirstChar { c -> c.uppercase() } } ?: "—",
                warn = !closed && i.state == RenewalState.CANCEL_BY),
            RenewalTile("Repeats", repeatShort(i.repeats, i.repeatLabel)),
        )
        val spoken = listOfNotNull(i.title, sub, cost, badge).joinToString(", ")
        return RenewalCard(i.id, i.title, MONTHS[d.month - 1], d.day.toString().padStart(2, '0'), sub, cost, badge, warn, tiles, closed, spoken, i)
    }

    /** "Renews", "Due", "Expires", "Ends" (the open card's first tile). */
    fun dueWord(i: RenewalItem): String = RenewalRules.verb(i.kind).replaceFirstChar { it.uppercase() }

    /** The badge under the cost, and whether it is lit. */
    fun badge(i: RenewalItem, today: Long): Pair<String, Boolean> {
        i.closedDay?.let { day -> return "${(i.closedHow ?: "Done").lowercase()} ${RenewalRules.dayWord(day, today)}" to false }
        if (i.dueDay < today) return "overdue ${days(today - i.dueDay)}" to true
        val cancel = i.cancelByDay
        if (cancel != null && cancel >= today) {
            val n = cancel - today
            return (if (n == 0L) "cancel today" else if (n == 1L) "cancel tomorrow" else "cancel in ${days(n)}") to true
        }
        val n = i.dueDay - today
        val word = if (n == 0L) "today" else if (n == 1L) "tomorrow" else "in ${days(n)}"
        return word to i.needsAttention
    }

    private fun days(n: Long): String = if (n == 1L) "1 day" else "$n days"

    /** "£24/yr", "£9.99/mo", "£30/3 mo", "£45" (a one-off). */
    fun costShort(pence: Long, repeats: RenewalRepeat?): String {
        val amount = RenewalRules.formatPence(pence)
        return when (repeats) {
            RenewalRepeat.MONTHLY -> "$amount/mo"
            RenewalRepeat.QUARTERLY -> "$amount/3 mo"
            RenewalRepeat.HALF_YEARLY -> "$amount/6 mo"
            RenewalRepeat.YEARLY -> "$amount/yr"
            RenewalRepeat.NONE, null -> amount
        }
    }

    /** "Yearly", "Monthly", "3 months", "6 months", "No"; another rule keeps its own words. */
    fun repeatShort(r: RenewalRepeat?, fallback: String): String = when (r) {
        RenewalRepeat.NONE -> "No"
        RenewalRepeat.MONTHLY -> "Monthly"
        RenewalRepeat.QUARTERLY -> "3 months"
        RenewalRepeat.HALF_YEARLY -> "6 months"
        RenewalRepeat.YEARLY -> "Yearly"
        null -> fallback
    }

    /** The Edit sheet's rows, each with what it is now. */
    fun editRows(i: RenewalItem, today: Long): List<RenewalEditRow> {
        val cancelDays = i.cancelByDay?.let { (i.dueDay - it).toInt() }
        return listOf(
            RenewalEditRow(RenewalEditKey.DUE, dueWord(i), RenewalRules.dayWord(i.dueDay, today).replaceFirstChar { it.uppercase() }),
            RenewalEditRow(RenewalEditKey.REPEATS, "Repeats", i.repeatLabel),
            RenewalEditRow(RenewalEditKey.REMIND, "Remind me", leadLabel(i.leadDays)),
            RenewalEditRow(RenewalEditKey.CANCEL_BY, MailSourceRules.CANCEL_BY, cancelLabel(cancelDays)),
            RenewalEditRow(RenewalEditKey.KIND, "Kind", RenewalRules.kindLabel(i.kind)),
            RenewalEditRow(RenewalEditKey.COST, "Cost", i.costPence?.let { RenewalRules.costLabel(it, RenewalRules.ruleFor(i.repeats ?: RenewalRepeat.NONE, i.dueDay)) } ?: "Not set"),
            RenewalEditRow(RenewalEditKey.PAY_LINK, "Pay link", i.payLink?.let(BillRules::site) ?: "Not set"),
        )
    }

    /** "A week before", or "10 days before" for a lead time no preset has. */
    fun leadLabel(days: Int): String =
        RenewalRules.LEAD_CHOICES.firstOrNull { it.days == days }?.label ?: if (days == 0) "On the day" else "${days(days.toLong())} before"

    /** "3 days before", "None", or "10 days before". */
    fun cancelLabel(daysBefore: Int?): String = when (daysBefore) {
        null -> "None"
        else -> RenewalRules.CANCEL_CHOICES.firstOrNull { it.days == daysBefore }?.label ?: "${days(daysBefore.toLong())} before"
    }

    /** The row's key at [index] (Swift). */
    fun editKeyAt(index: Int): RenewalEditKey = RenewalEditKey.entries[index]
}
