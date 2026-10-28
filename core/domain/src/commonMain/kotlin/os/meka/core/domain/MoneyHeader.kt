package os.meka.core.domain

/**
 * Money's top as the approved mockup (`docs/design/money/Main.dc.html`, checked beside the app 2026-10-28): the title
 * row is "Money" with an **Updated** pill on its right ("Updated 06:10", a tap asks the banks for a sync; "Updating…"
 * while that waits), the line under the title only says something when Money is locked or nothing is in yet (the
 * mockup has no "2 accounts · 23 transactions" line; Accounts lists them), and Spending's Categories · Needs vs wants
 * chips sit on the row over the category rows with "All N ›" (the mockup's place), not over the ring.
 */
object MoneyHeader {
    const val UPDATING = "Updating…"

    /** What a screen reader hears for the pill: "Updated 06:10, refresh". */
    const val SPOKEN_TAIL = ", refresh"

    /** The pill's words, or null before the banks have synced (statement files only: nothing to refresh). */
    fun pill(bank: MoneyBankView?): String? {
        bank ?: return null
        if (bank.refreshing) return UPDATING
        return bank.updated?.let { "Updated $it" }
    }

    fun spoken(pill: String): String = pill + SPOKEN_TAIL

    /** Whether the line under the title shows: locked, or nothing imported yet (it says how to start). */
    fun showsSummary(v: MoneyView): Boolean = v.locked || v.empty != null

    /** Whether Spending's view chips take the place of WHERE IT WENT's label (only when needs and wants can be told apart). */
    fun chipsInHead(sp: MoneySpendingView?): Boolean = sp?.needs != null
}
