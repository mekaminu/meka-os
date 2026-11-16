package os.meka.android.screens

import os.meka.core.facade.MekaCore

/**
 * The Money shots' one full month (two accounts, August to October, groceries, eating out, transport, bills, salary, a
 * pension counted in net worth), shared so shots read side by side agree. Walk-through 2026-11-08 A32: Spending's
 * "1 payment without a category" (MoneyScreensTest's month) sat beside the sorter's "SORT YOUR PAYMENTS · 2 LEFT"
 * (MoneySorterScreensTest's own statement); both now open on this month, which has two payees without a category
 * (Zebra Plover twice, The Garden Shop once): "3 payments without a category" and "2 LEFT". Walk-through 2026-11-15 A13:
 * Accounts' own shots imported shorter statements of their own, so "2026 SO FAR" read three ways (£578.16, £425.17 and
 * £1,050.95 spent) and the card was "Credit card" on one Accounts shot and "Platinum card" on the rest and on Bills and
 * Plan; Accounts' shots open on this month too, and its card is the Platinum Card, marked a card. Walk-through 2026-11-15
 * A13 (g1a 10): Plan's Clear my cards listed a "Rewards card" that no Accounts or Bills shot had; the Rewards Card is now
 * this month's second card everywhere, its statement only a repayment (a transfer, so no spending or income moves).
 * Walk-through 2026-11-15 A13 (g1b 5): the monthly bill was "Netflix.com" here and "Streamly.com" on every shot that
 * imports its own statement; it is Streamly (the invented name) everywhere now. Invented data (rule 13).
 */
internal object MoneyShotMonth {
    // Walk-through 2026-11-08 A8: three months of the council tax and Streamly, so they're found as regular bills and
    // Spending's "Bills to come" and the Bills tab show the same council tax due 1 Nov (it was £0 and no card).
    const val CURRENT = "Date,Description,Amount\n" +
        "01/08/2026,OAKFIELD COUNCIL TAX,-142.00\n" +
        "05/08/2026,SALARY OAKFIELD LTD,2500.00\n" +
        "15/08/2026,STREAMLY.COM,-10.99\n" +
        "01/09/2026,OAKFIELD COUNCIL TAX,-142.00\n" +
        "04/09/2026,TESCO STORES 2041,-62.40\n" +
        "05/09/2026,SALARY OAKFIELD LTD,2500.00\n" +
        "11/09/2026,TESCO STORES 2041,-58.10\n" +
        "15/09/2026,STREAMLY.COM,-10.99\n" +
        "18/09/2026,TFL TRAVEL CH,-38.50\n" +
        "25/09/2026,COSTA COFFEE,-3.20\n" +
        "01/10/2026,OAKFIELD COUNCIL TAX,-142.00\n" +
        "02/10/2026,TESCO STORES 2041,-71.25\n" +
        "03/10/2026,COSTA COFFEE,-3.20\n" +
        "05/10/2026,SALARY OAKFIELD LTD,2500.00\n" +
        "07/10/2026,PRET A MANGER,-6.85\n" +
        "08/10/2026,ZEBRA PLOVER,-12.60\n" +
        "09/10/2026,TESCO STORES 2041,-64.90\n" +
        "12/10/2026,TFL TRAVEL CH,-41.20\n" +
        "14/10/2026,COSTA COFFEE,-3.40\n" +
        "15/10/2026,STREAMLY.COM,-10.99\n" +
        "16/10/2026,SHELL FORECOURT 112,-58.00\n" +
        "17/10/2026,THE GARDEN SHOP OAKFIELD,-24.99\n" +
        "19/10/2026,COSTA COFFEE,-3.40\n" +
        "20/10/2026,ZEBRA PLOVER,-12.60\n" +
        "20/10/2026,TESCO STORES 2041,-55.60\n"

    const val CARD = "Date,Description,Amount\n" +
        "06/10/2026,AMAZON MARKETPLACE,-32.49\n" +
        "13/10/2026,DELIVEROO,-21.80\n" +
        "18/10/2026,CINEWORLD OAKFIELD,-17.50\n"

    /** The second card: only last month's repayment, which Money counts as a transfer (neither spent nor in). */
    const val REWARDS = "Date,Description,Amount\n" +
        "15/10/2026,PAYMENT THANK YOU,50.00\n"

    /** The biggest payee without a category first: Zebra Plover (2 × £12.60) over The Garden Shop (£24.99). */
    val UNSORTED = listOf("Zebra Plover", "The Garden Shop")

    /** The current account's name and the card's, as every Money shot names them (Bills, Plan and Accounts). */
    const val CURRENT_NAME = "Current account"
    const val CARD_NAME = "Platinum Card"
    const val REWARDS_NAME = "Rewards Card"

    /** Both cards, as Bills' CARDS, Accounts and Plan's Clear my cards list them. */
    val CARD_NAMES = listOf(CARD_NAME, REWARDS_NAME)

    /**
     * Imports the month into [core] (Money already open) and marks both cards cards; [pension] adds the £42,000 pension
     * net worth counts (Accounts' shots that start from an empty total leave it out).
     */
    suspend fun fill(core: MekaCore, pension: Boolean = true) {
        check(!core.importMoneyStatement(CURRENT_NAME, CURRENT).refused)
        check(!core.importMoneyStatement(CARD_NAME, CARD).refused)
        core.tick()
        val card = core.moneyView.value.accounts.firstOrNull { it.name == CARD_NAME } ?: error("no card account")
        check(!core.moneyCardKind(card.id, card = true, charge = false).refused)
        addRewards(core)
        if (pension) check(!core.moneyWorthAdd("Pension £42,000").refused)
        core.tick()
    }

    /** Imports [REWARDS] as [REWARDS_NAME] and marks it a card (for shots that import their own current account). */
    suspend fun addRewards(core: MekaCore) {
        check(!core.importMoneyStatement(REWARDS_NAME, REWARDS).refused)
        core.tick()
        val card = core.moneyView.value.accounts.firstOrNull { it.name == REWARDS_NAME } ?: error("no rewards card account")
        check(!core.moneyCardKind(card.id, card = true, charge = false).refused)
        core.tick()
    }
}
