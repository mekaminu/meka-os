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

    /** The line under the title: what's due ("1 promise due · 2 to chase"), else [NOTHING_DUE]. */
    fun summary(view: ListsView): String = view.dueLine ?: NOTHING_DUE

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
