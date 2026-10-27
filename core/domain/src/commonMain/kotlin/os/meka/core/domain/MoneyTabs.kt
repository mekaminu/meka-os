package os.meka.core.domain

/**
 * Money redesign (B), slice 1 (Meka 2026-10-24 20:20: "hard to use and complex, too many things everywhere"; mockup v4
 * approved 2026-10-25 09:30): Money stops being one long page and opens on five tabs, Spending · Bills · Plan ·
 * Trading · Accounts, each holding only its own sections. Both apps draw the tabs from here and ask [sections] what the
 * chosen tab shows, in order, so the Fold and the Mac can't drift apart.
 */
enum class MoneyTab(val label: String) {
    SPENDING("Spending"), BILLS("Bills"), PLAN("Plan"), TRADING("Trading"), ACCOUNTS("Accounts"),
}

/** One part of the Money screen, in the order the screen draws them. */
enum class MoneySection {
    // Spending: the month, its review, payments worth a look, the year, tips, categories and the payments.
    MONTH, TRAVEL_BANNER, REVIEW, ALERTS, YEAR, TIPS, CATEGORIES, PAYMENTS,
    // Bills: the card and the month's bills by date (B3), payday and safe to spend, the cards' due dates, refunds on the
    // way, the regular payments.
    BILLS, PAYDAY, CARDS, REFUNDS, REGULAR,
    // Plan: the forecast at his pace, money goals, challenges, paying abroad.
    FORECAST, GOALS, CHALLENGES, TRAVEL,
    // Trading: Hyperliquid by public address.
    TRADING,
    // Accounts: his banks, net worth, the side hustle, shared costs, his own categories (slice 3), import, the accounts list, delete and the foot.
    BANK, WORTH, BUSINESS, SHARED_COSTS, OWN_CATEGORIES, IMPORT_STATEMENT, ACCOUNTS, FOOT,
}

object MoneyTabs {
    /** Every tab, in the order the pills show them. */
    val ALL: List<MoneyTab> = listOf(MoneyTab.SPENDING, MoneyTab.BILLS, MoneyTab.PLAN, MoneyTab.TRADING, MoneyTab.ACCOUNTS)

    /** Money always opens on Spending (this month, newest payments first). */
    val FIRST: MoneyTab = MoneyTab.SPENDING

    const val EMPTY_SPENDING = "Connect your banks or import a statement under Accounts, and your month shows here."
    const val EMPTY_BILLS = "No bills found yet · they show once a payment repeats each month, or a card's due date is in (Accounts)."
    const val EMPTY_PLAN = "Your plan shows once a month of payments is in: the forecast at your pace, goals and challenges."
    const val EMPTY_TRADING = "Trading shows here once you add your Hyperliquid public address (the 0x… one, never a key)."

    /** Which tab a section lives on. */
    fun tabOf(s: MoneySection): MoneyTab = when (s) {
        MoneySection.MONTH, MoneySection.TRAVEL_BANNER, MoneySection.REVIEW, MoneySection.ALERTS, MoneySection.YEAR,
        MoneySection.TIPS, MoneySection.CATEGORIES, MoneySection.PAYMENTS -> MoneyTab.SPENDING
        MoneySection.BILLS, MoneySection.PAYDAY, MoneySection.CARDS, MoneySection.REFUNDS, MoneySection.REGULAR -> MoneyTab.BILLS
        MoneySection.FORECAST, MoneySection.GOALS, MoneySection.CHALLENGES, MoneySection.TRAVEL -> MoneyTab.PLAN
        MoneySection.TRADING -> MoneyTab.TRADING
        MoneySection.BANK, MoneySection.WORTH, MoneySection.BUSINESS, MoneySection.SHARED_COSTS, MoneySection.OWN_CATEGORIES,
        MoneySection.IMPORT_STATEMENT, MoneySection.ACCOUNTS, MoneySection.FOOT -> MoneyTab.ACCOUNTS
    }

    /** Whether [v] has anything for section [s]. Import and the foot are always there (Accounts is never empty). */
    fun present(s: MoneySection, v: MoneyView): Boolean = when (s) {
        MoneySection.MONTH -> v.month != null
        MoneySection.TRAVEL_BANNER -> v.month != null && v.travel?.banner != null
        MoneySection.REVIEW -> v.month != null && v.review != null
        MoneySection.ALERTS -> v.month != null && v.alerts.isNotEmpty()
        MoneySection.YEAR -> v.month != null && v.year != null
        MoneySection.TIPS -> v.month != null && (v.tips.isNotEmpty() || v.hiddenTips != null)
        MoneySection.CATEGORIES -> v.month?.categories?.isNotEmpty() == true
        MoneySection.PAYMENTS -> v.month?.txns?.isNotEmpty() == true
        MoneySection.BILLS -> v.month != null && v.bills != null
        MoneySection.PAYDAY -> v.month != null && v.payday != null
        MoneySection.CARDS -> v.month != null && v.cards?.shown == true
        MoneySection.REFUNDS -> v.month != null && v.refunds != null
        MoneySection.REGULAR -> v.month != null && v.regular != null
        MoneySection.FORECAST -> v.month != null && v.forecast != null
        MoneySection.GOALS -> v.month != null && v.goals != null
        MoneySection.CHALLENGES -> v.month != null && v.challenges != null
        MoneySection.TRAVEL -> v.month != null && v.travel != null
        MoneySection.TRADING -> v.trading != null
        MoneySection.BANK -> v.bank != null
        MoneySection.WORTH -> v.worth != null
        MoneySection.BUSINESS -> v.business != null
        MoneySection.SHARED_COSTS -> v.shared != null
        MoneySection.OWN_CATEGORIES -> v.ownRows.isNotEmpty()
        MoneySection.IMPORT_STATEMENT, MoneySection.FOOT -> true
        MoneySection.ACCOUNTS -> v.accounts.isNotEmpty()
    }

    /** What [tab] shows of [v], in the screen's order; nothing while Money is locked or still opening. */
    fun sections(tab: MoneyTab, v: MoneyView): List<MoneySection> =
        if (v.locked || v.skeleton) emptyList() else MoneySection.entries.filter { tabOf(it) == tab && present(it, v) }

    /** Whether [tab] shows section [s] of [v] (the screens ask this for each block). */
    fun shows(tab: MoneyTab, s: MoneySection, v: MoneyView): Boolean = tabOf(s) == tab && !v.locked && !v.skeleton && present(s, v)

    /** The line a tab shows when it has nothing of its own; null when it has something (Accounts always has Import). */
    fun empty(tab: MoneyTab, v: MoneyView): String? {
        if (v.locked || v.skeleton || sections(tab, v).isNotEmpty()) return null
        return when (tab) {
            MoneyTab.SPENDING -> EMPTY_SPENDING
            MoneyTab.BILLS -> EMPTY_BILLS
            MoneyTab.PLAN -> EMPTY_PLAN
            MoneyTab.TRADING -> EMPTY_TRADING
            MoneyTab.ACCOUNTS -> null
        }
    }

    /**
     * Bills and Plan are about this month (payday, cards, the forecast), and only Spending pages through months: picking
     * another tab while an earlier month is shown steps back to this month. The step to pass to `moneyStep`, or null
     * when nothing needs to move.
     */
    fun stepOnPick(tab: MoneyTab, v: MoneyView): Int? {
        if (tab == MoneyTab.SPENDING) return null
        val offset = v.month?.offset ?: return null
        return offset.takeIf { it > 0 }
    }

    /**
     * Whether the payday block lists its own "still to go out" rows: not when the Bills card above already shows them
     * (the payday block then keeps payday, since payday, the balance and safe to spend).
     */
    fun paydayRows(v: MoneyView): Boolean = v.bills == null

    /** What a screen reader says for a tab pill. */
    fun spoken(tab: MoneyTab, chosen: Boolean): String = tab.label + " tab" + if (chosen) ", selected" else ""
}
