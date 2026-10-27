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
    // Spending: the month, its review, payments worth a look, categories, the payments without one, and the payments.
    // (Money review, Meka 2026-10-26 22:12: the year moved to Accounts and the tips to Plan's money check.)
    // Layout rules (Money review, Meka 2026-10-26 22:18): Spending ends on one short foot line ("Read-only · not
    // financial advice") with a small ⓘ opening where the numbers came from ([MoneyTabs.info]), said once per tab.
    MONTH, TRAVEL_BANNER, REVIEW, ALERTS, CATEGORIES, UNSORTED, PAYMENTS, SPEND_FOOT,
    // Bills: the card and the month's bills by date (B3), payday and safe to spend, the cards' due dates, refunds on the
    // way, the regular payments (only while the Bills card isn't there to list them: Money review, Meka 2026-10-26 22:12).
    // Layout rules on Bills (2026-10-27): the tab ends on the same one foot line as Spending, its ⓘ saying where the
    // bills, the cards and the refunds come from (said once, not under each card).
    BILLS, PAYDAY, CARDS, REFUNDS, REGULAR, BILLS_FOOT,
    // Plan: tips from his numbers (the money check's ideas lead), What if I save… ([MoneySave], the mockup's Plan), the
    // forecast at his pace, money goals, challenges, paying abroad. (Plan's goals card, 2026-10-27: goals follow Clear my
    // cards, as the mockup.) Layout rules on Plan (2026-10-27, [MoneyPlanLayout]): the tab ends on the same one foot line,
    // its ⓘ saying how What if I save, Clear my cards, the forecast, the challenges and paying abroad are worked out.
    TIPS, SAVE, CLEAR, GOALS, FORECAST, CHALLENGES, TRAVEL, PLAN_FOOT,
    // Trading: Hyperliquid by public address.
    TRADING,
    // Accounts: his banks, net worth, then the accounts as one clean list (Money review, Meka 2026-10-26 22:12: a tap opens
    // an account's sheet, where It's a card, Side hustle account, Import and Remove now live), the year in review, the side
    // hustle, shared costs, his own categories (slice 3), Import a statement (one row opening a sheet) and the foot.
    // Accounts' rest (2026-10-27, mockup): the side hustle, shared costs and Import, export and delete are one MORE card
    // of three rows, each opening a sheet ([MoneyMore]), after his own categories.
    // Layout rules on Accounts (2026-10-27): his banks, net worth and the year are each one card, and the tab ends on the
    // same one foot line ([FOOT] now draws [MoneyTabs.FOOT_SHORT]), its ⓘ saying where the accounts, the banks, net
    // worth and the year come from; net worth's and the year's source lines left their cards.
    BANK, WORTH, ACCOUNTS, YEAR, OWN_CATEGORIES, MORE, FOOT,
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
        MoneySection.MONTH, MoneySection.TRAVEL_BANNER, MoneySection.REVIEW, MoneySection.ALERTS,
        MoneySection.CATEGORIES, MoneySection.UNSORTED, MoneySection.PAYMENTS, MoneySection.SPEND_FOOT -> MoneyTab.SPENDING
        MoneySection.BILLS, MoneySection.PAYDAY, MoneySection.CARDS, MoneySection.REFUNDS, MoneySection.REGULAR, MoneySection.BILLS_FOOT -> MoneyTab.BILLS
        MoneySection.TIPS, MoneySection.SAVE, MoneySection.CLEAR, MoneySection.FORECAST, MoneySection.GOALS, MoneySection.CHALLENGES, MoneySection.TRAVEL, MoneySection.PLAN_FOOT -> MoneyTab.PLAN
        MoneySection.TRADING -> MoneyTab.TRADING
        MoneySection.BANK, MoneySection.WORTH, MoneySection.ACCOUNTS, MoneySection.YEAR,
        MoneySection.OWN_CATEGORIES, MoneySection.MORE, MoneySection.FOOT -> MoneyTab.ACCOUNTS
    }

    /** Whether [v] has anything for section [s]. Import and the foot are always there (Accounts is never empty). */
    fun present(s: MoneySection, v: MoneyView): Boolean = when (s) {
        MoneySection.MONTH -> v.month != null
        MoneySection.TRAVEL_BANNER -> v.month != null && v.travel?.banner != null
        MoneySection.REVIEW -> v.month != null && v.review != null
        MoneySection.ALERTS -> v.month != null && v.alerts.isNotEmpty()
        MoneySection.YEAR -> v.month != null && v.year != null
        MoneySection.TIPS -> v.month != null && (v.tips.isNotEmpty() || v.hiddenTips != null)
        MoneySection.SAVE -> v.month != null && v.save != null
        MoneySection.CLEAR -> v.month != null && v.clear != null
        MoneySection.CATEGORIES -> v.month?.categories?.isNotEmpty() == true
        MoneySection.UNSORTED -> v.month?.unsorted != null
        MoneySection.PAYMENTS -> v.month?.txns?.isNotEmpty() == true
        MoneySection.SPEND_FOOT -> v.month != null
        MoneySection.BILLS -> v.month != null && v.bills != null
        MoneySection.PAYDAY -> v.month != null && v.payday != null
        // Money review (Meka 2026-10-26 22:12): Bills shows his cards as one-line rows; the accounts he could mark as a
        // card are a one-time setup on Accounts, and the regular payments are already the Bills card's list.
        MoneySection.CARDS -> v.month != null && v.cards?.rows?.isNotEmpty() == true
        MoneySection.REFUNDS -> v.month != null && v.refunds != null
        MoneySection.REGULAR -> v.month != null && v.regular != null && v.bills == null
        // The foot only under something of Bills' own, so an empty Bills still says how bills get found.
        MoneySection.BILLS_FOOT -> BILLS_OWN.any { present(it, v) }
        MoneySection.FORECAST -> v.month != null && v.forecast != null
        MoneySection.GOALS -> v.month != null && v.goals != null
        MoneySection.CHALLENGES -> v.month != null && v.challenges != null
        MoneySection.TRAVEL -> v.month != null && v.travel != null
        // The foot only under something of Plan's own, so an empty Plan keeps its one line.
        MoneySection.PLAN_FOOT -> PLAN_OWN.any { present(it, v) }
        MoneySection.TRADING -> v.trading != null
        MoneySection.BANK -> v.bank != null
        MoneySection.WORTH -> v.worth != null
        MoneySection.OWN_CATEGORIES -> v.ownRows.isNotEmpty()
        MoneySection.MORE, MoneySection.FOOT -> true
        MoneySection.ACCOUNTS -> v.accounts.isNotEmpty() || v.worth?.rows?.any { it.kind == MoneyWorth.TRADING } == true
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

    /** The Bills sections that have their own content (the foot shows under any of them). */
    private val BILLS_OWN = listOf(MoneySection.BILLS, MoneySection.PAYDAY, MoneySection.CARDS, MoneySection.REFUNDS, MoneySection.REGULAR)

    /** The Plan sections that have their own content (the foot shows under any of them). */
    private val PLAN_OWN = listOf(
        MoneySection.TIPS, MoneySection.SAVE, MoneySection.CLEAR, MoneySection.GOALS, MoneySection.FORECAST,
        MoneySection.CHALLENGES, MoneySection.TRAVEL,
    )

    /** Layout rule (c): the one foot line a tab ends on; its ⓘ opens [info]. */
    const val FOOT_SHORT = "Read-only · not financial advice"
    const val INFO = "ⓘ"
    const val INFO_SPOKEN = "About these numbers"
    const val INFO_TITLE = "About these numbers"
    const val INFO_DONE = "Done"
    const val TRANSFERS_LEFT_OUT = "Transfers between your accounts are left out of spent and came in."

    /**
     * What the ⓘ at the foot of [tab] says: where the numbers came from and how they were worked out, then Money's
     * read-only line (layout rule (c): said once, at the foot, not under every card). Null for a tab with no foot of its own.
     */
    fun info(tab: MoneyTab, v: MoneyView): List<String>? = when (tab) {
        MoneyTab.SPENDING -> listOf("${v.source.from}. ${v.source.worked}", TRANSFERS_LEFT_OUT, MoneyRules.FOOT)
        MoneyTab.BILLS -> listOfNotNull(
            BILLS_FROM,
            CARDS_FROM.takeIf { v.cards?.rows?.isNotEmpty() == true },
            REFUNDS_FROM.takeIf { v.refunds != null },
            v.refunds?.foot,
            MoneyRules.FOOT,
        )
        MoneyTab.PLAN -> listOfNotNull(
            PLAN_FROM,
            MoneySave.BASIS.takeIf { v.save != null },
            MoneyClear.BASIS.takeIf { v.clear != null },
            v.forecast?.basis,
            v.challenges?.foot,
            v.travel?.foot,
            MoneyRules.FOOT,
        )
        MoneyTab.ACCOUNTS -> listOfNotNull(
            ACCOUNTS_FROM,
            BANK_FROM.takeIf { v.bank != null },
            v.worth?.foot,
            v.year?.foot,
            MoneyRules.FOOT,
        )
        MoneyTab.TRADING -> null
    }

    const val BILLS_FROM = "Bills are found from payments that repeat each month, Lists → Renewals and your cards' due dates. Until payday counts a card due before payday by its statement."
    const val CARDS_FROM = "Cards show what you type off each statement, with a reminder 3 days before each payment and before 0% ends."
    const val REFUNDS_FROM = "Refunds on the way are the refunds on Waiting for, checked against your statements. A chase is only copied for you to send yourself."

    const val ACCOUNTS_FROM = "Accounts lists each account from your statements and your banks. A card's balance counts as owed, and an account without a balance leaves net worth partial."
    const val BANK_FROM = "Your banks fill Money through Lunch Flow, read-only, every few hours and when you tap Refresh; Navilife never logs in to your bank."

    const val PLAN_FROM = "Plan works from your own statements: your average month, your cards and your goals. Nothing here moves or pays money."

    /** What a screen reader says for a tab pill. */
    fun spoken(tab: MoneyTab, chosen: Boolean): String = tab.label + " tab" + if (chosen) ", selected" else ""
}
