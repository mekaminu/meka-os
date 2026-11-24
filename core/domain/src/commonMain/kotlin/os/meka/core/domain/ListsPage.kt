package os.meka.core.domain

/**
 * Ask → Lists in the page shape (every page under Ask in one shape, slice 17, the last page; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what Lists keeps, and that nothing is chased, decided,
 * renewed or bought for him), one gold **+ Add** whose sheet holds every way in (Something I promised · Something I'm
 * waiting for · Someday · A decision · A renewal or bill · Shopping; no open input boxes left on the page), and three
 * numbers (promises due or to chase, how soon the next renewal is, what's on the shopping list). Lists keeps its five
 * tabs under the numbers (each list has its own actions and the Renewals tab its own cards), so this page carries no
 * cards of its own. Built from the [ListsView] both apps already have; nothing new is read or sent. Non-AI, pure.
 */
object ListsPageRules {
    const val TITLE = "Lists"
    const val INFO = "Promises both ways, someday ideas, decisions and why you made them, renewals and bills, and the shopping list you share. Navilife tells you when a chase, a review or a renewal is due; it never chases, decides, renews or buys anything for you."
    const val ADD_TITLE = "Add to Lists"
    /** The line under the title while nothing is due (the page's summary). */
    const val NOTHING_DUE = "Nothing to chase, review or renew today."
    /**
     * The one line while every list is empty (walk-through 2026-11-22, A15: the empty page said "nothing" five times,
     * the summary, three "None" tiles and the tab's own instruction). It is then the only thing said: the tiles and
     * the tabs' empty lines are left out until something is on a list.
     */
    const val BLANK_LINE = "Nothing on your lists yet. Tap + Add for a promise, a renewal or the shopping."

    /** Each tab's empty line, while another list has something in it (keyed as [tabFor]). */
    const val WAITING_EMPTY = "Nothing promised either way. Add what you told someone you'd do, or a reply, a parcel or a refund you're waiting on, and Navilife tells you when it's due."
    const val SOMEDAY_EMPTY = "Ideas, trips, things to buy or read. They stay out of Today and the planner until you say \"Do it now\"."
    const val DECISIONS_EMPTY = "Write down what you decided and why, so it isn't re-made. Add a review date if it should be looked at again."
    const val RENEWALS_EMPTY = "MOT, insurance, the boiler service, subscriptions and bills. Add each with its date and Navilife shows it in good time, with a cancel-by reminder if you set one."

    const val ADD_PROMISE = "promise"
    const val ADD_WAITING = "waiting"
    const val ADD_SOMEDAY = "someday"
    const val ADD_DECISION = "decision"
    const val ADD_RENEWAL = "renewal"
    const val ADD_SHOPPING = "shopping"
    val ADD_CHOICES = listOf(
        AddChoice(ADD_PROMISE, "Something I promised", "What you told someone you'd do, who to and by when"),
        AddChoice(ADD_WAITING, "Something I'm waiting for", "A reply, a parcel or a refund, and when to chase it"),
        AddChoice(ADD_SOMEDAY, "Someday", "An idea, a trip, a thing to buy or read; it stays out of Today"),
        AddChoice(ADD_DECISION, "A decision", "What you decided and why, with a day to look at it again"),
        AddChoice(ADD_RENEWAL, "A renewal or bill", "Insurance, a subscription, a service: its day, cost and cancel-by"),
        AddChoice(ADD_SHOPPING, "Shopping", "Things to buy, with commas between them: milk, bread"),
    )

    /** The tab a choice's list lives on ("waiting", "someday", "decisions", "renewals", "shopping"), to show after an add. */
    fun tabFor(choice: String): String = when (choice) {
        ADD_PROMISE, ADD_WAITING -> "waiting"
        ADD_SOMEDAY -> "someday"
        ADD_DECISION -> "decisions"
        ADD_RENEWAL -> "renewals"
        else -> "shopping"
    }

    /** Whether every list is empty: nothing promised or awaited, no someday, decision, renewal or shopping. */
    fun blank(view: ListsView): Boolean =
        view.promisesBothWays == 0 && view.someday.isEmpty() && view.decisions.isEmpty() &&
            view.renewals.count == 0 && view.renewals.done.isEmpty() &&
            view.shopping.toBuy.isEmpty() && view.shopping.got.isEmpty()

    /** The line under the title: what's due ("1 promise due · 2 to chase"), else [BLANK_LINE] on a blank page, else [NOTHING_DUE]. */
    fun summary(view: ListsView): String = view.dueLine ?: if (blank(view)) BLANK_LINE else NOTHING_DUE

    /** Whether the three numbers show: not on a blank page, where [BLANK_LINE] says it once. */
    fun showsTiles(view: ListsView): Boolean = !blank(view)

    /**
     * What an empty tab says ("waiting", "someday", "decisions", "renewals", "shopping"): null when the tab has
     * something in it, or when the whole page is blank and [BLANK_LINE] above has said it.
     */
    fun tabEmpty(view: ListsView, tab: String): String? {
        if (blank(view)) return null
        return when (tab) {
            "waiting" -> WAITING_EMPTY.takeIf { view.promisesBothWays == 0 }
            "someday" -> SOMEDAY_EMPTY.takeIf { view.someday.isEmpty() }
            "decisions" -> DECISIONS_EMPTY.takeIf { view.decisions.isEmpty() }
            "renewals" -> RENEWALS_EMPTY.takeIf { view.renewals.count == 0 && view.renewals.done.isEmpty() }
            "shopping" -> ShoppingRules.EMPTY_LINE.takeIf { view.shopping.toBuy.isEmpty() && view.shopping.got.isEmpty() }
            else -> null
        }
    }

    private fun promisesTile(view: ListsView): PageTile {
        val due = view.promisesDue + view.chaseDue
        val both = view.promisesBothWays
        return when {
            due > 0 -> PageTile("$due", "due or to chase", true)
            both > 0 -> PageTile("$both", if (both == 1) "promise either way" else "promises either way", false)
            else -> PageTile("None", "promised or awaited", false)
        }
    }

    private fun renewalTile(view: ListsView, today: Long): PageTile {
        val r = view.renewals
        // What needs him first (overdue or a cancel-by close), else the soonest coming up.
        val next = r.attention.firstOrNull() ?: r.all.minByOrNull { it.dueDay }
            ?: return PageTile("No dates", "to renew yet", false)
        val lit = r.dueCount > 0
        return when {
            next.state == RenewalState.OVERDUE -> PageTile("Overdue", next.title, true)
            next.state == RenewalState.CANCEL_BY && next.cancelByDay != null ->
                PageTile(LifePageRules.until(next.cancelByDay, today), "to cancel ${next.title}", true)
            else -> PageTile(LifePageRules.until(next.dueDay, today), "to ${next.title}", lit)
        }
    }

    private fun shoppingTile(view: ListsView): PageTile {
        val n = view.shopping.count
        return if (n > 0) PageTile("$n", "to buy", false) else PageTile("Nothing", "to buy", false)
    }

    /** The three numbers: promises (due or both ways), the next renewal, the shopping list. */
    fun tiles(view: ListsView, today: Long): List<PageTile> =
        listOf(promisesTile(view), renewalTile(view, today), shoppingTile(view))

    /** The page: its title, ⓘ, three numbers and + Add's choices (the lists themselves stay under their tabs). */
    fun page(view: ListsView, today: Long): LifePageView =
        LifePageView(TITLE, INFO, tiles(view, today), emptyList(), ADD_TITLE, ADD_CHOICES)
}
