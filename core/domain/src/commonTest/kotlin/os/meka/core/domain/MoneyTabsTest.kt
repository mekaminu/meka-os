package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money redesign (B), slice 1: five tabs, each with only its own sections. Invented shops and accounts (rule 13). */
class MoneyTabsTest {
    private val cat = MoneyCategoryRow("groceries", "Groceries", "£402", 0.4f, "£20 under usual", false, "Groceries £402")
    private val txn = MoneyTxnRow("t1", "Today", "Corner Shop", "−£4.20", false, "groceries", "Groceries", "Current account", false, "Corner Shop")
    private fun month(offset: Int = 0) = MoneyMonth(offset, "October so far", "Spent £1,234 · in £2,500", null, false, listOf(cat), listOf(txn), 0)

    private val bank = MoneyBankView("FROM YOUR BANKS", "Fetched 08:14 · 3 accounts", false, false, emptyList(), null, "From your banks")
    private val regular = MoneyRegular("£388 a month", emptyList())
    private val refunds = MoneyRefundsView(MoneyRefunds.HEAD, emptyList(), null)
    private val alert = MoneyAlert("x1", "double", "Corner Shop twice", "−£4.20 twice on Friday", true, "Corner Shop twice")

    private fun open(m: MoneyMonth? = month()) = MoneyView(
        locked = false, summary = "Spent £1,234", empty = null, month = m,
        accounts = listOf(MoneyAccountRow("a-current", "Current account", "120 payments")),
        earlier = true, later = false, regular = regular, refunds = refunds, alerts = listOf(alert), bank = bank,
    )

    @Test
    fun fiveTabsInTheMockupsOrderAndMoneyOpensOnSpending() {
        assertEquals(listOf("Spending", "Bills", "Plan", "Trading", "Accounts"), MoneyTabs.ALL.map { it.label })
        assertEquals(MoneyTab.SPENDING, MoneyTabs.FIRST)
        // Every section lives on exactly one tab.
        MoneySection.entries.forEach { s -> assertTrue(MoneyTabs.tabOf(s) in MoneyTabs.ALL) }
    }

    @Test
    fun eachTabShowsOnlyItsOwnSectionsInOrder() {
        val v = open()
        assertEquals(listOf(MoneySection.MONTH, MoneySection.ALERTS, MoneySection.CATEGORIES, MoneySection.PAYMENTS), MoneyTabs.sections(MoneyTab.SPENDING, v))
        assertEquals(listOf(MoneySection.REFUNDS, MoneySection.REGULAR), MoneyTabs.sections(MoneyTab.BILLS, v))
        assertEquals(
            listOf(MoneySection.BANK, MoneySection.IMPORT_STATEMENT, MoneySection.ACCOUNTS, MoneySection.FOOT),
            MoneyTabs.sections(MoneyTab.ACCOUNTS, v),
        )
        // Nothing appears on two tabs: the long page is gone.
        val all = MoneyTabs.ALL.flatMap { MoneyTabs.sections(it, v) }
        assertEquals(all.size, all.toSet().size)
        assertTrue(MoneyTabs.shows(MoneyTab.BILLS, MoneySection.REGULAR, v))
        assertTrue(!MoneyTabs.shows(MoneyTab.SPENDING, MoneySection.REGULAR, v))
    }

    @Test
    fun anEmptyTabSaysWhatWillShowThereAndAccountsIsNeverEmpty() {
        val v = open()
        assertNull(MoneyTabs.empty(MoneyTab.SPENDING, v))
        assertEquals(MoneyTabs.EMPTY_PLAN, MoneyTabs.empty(MoneyTab.PLAN, v))
        assertEquals(MoneyTabs.EMPTY_TRADING, MoneyTabs.empty(MoneyTab.TRADING, v))
        // Nothing imported yet: Spending and Bills point to Accounts, where Import is.
        val nothing = MoneyView(false, MoneyRules.EMPTY_SUMMARY, MoneyRules.EMPTY, null, emptyList(), earlier = false, later = false)
        assertEquals(MoneyTabs.EMPTY_SPENDING, MoneyTabs.empty(MoneyTab.SPENDING, nothing))
        assertEquals(MoneyTabs.EMPTY_BILLS, MoneyTabs.empty(MoneyTab.BILLS, nothing))
        assertNull(MoneyTabs.empty(MoneyTab.ACCOUNTS, nothing))
        assertTrue(MoneySection.IMPORT_STATEMENT in MoneyTabs.sections(MoneyTab.ACCOUNTS, nothing))
        // This-month sections never show without a month.
        assertTrue(MoneyTabs.sections(MoneyTab.BILLS, open(m = null)).isEmpty())
    }

    @Test
    fun lockedOrOpeningShowsNoTabContent() {
        MoneyTabs.ALL.forEach { t ->
            assertTrue(MoneyTabs.sections(t, MoneyView.LOCKED).isEmpty())
            assertNull(MoneyTabs.empty(t, MoneyView.LOCKED))
            assertTrue(MoneyTabs.sections(t, open().copy(skeleton = true)).isEmpty())
        }
    }

    @Test
    fun billsAndPlanAreAboutThisMonthSoPickingThemStepsBackFromAnEarlierMonth() {
        val august = open(month(offset = 2))
        assertEquals(2, MoneyTabs.stepOnPick(MoneyTab.BILLS, august))
        assertEquals(2, MoneyTabs.stepOnPick(MoneyTab.PLAN, august))
        assertNull(MoneyTabs.stepOnPick(MoneyTab.SPENDING, august))
        assertNull(MoneyTabs.stepOnPick(MoneyTab.BILLS, open()))
        assertNull(MoneyTabs.stepOnPick(MoneyTab.BILLS, open(m = null)))
    }

    @Test
    fun aScreenReaderHearsTheTabAndWhetherItIsChosen() {
        assertEquals("Bills tab, selected", MoneyTabs.spoken(MoneyTab.BILLS, chosen = true))
        assertEquals("Plan tab", MoneyTabs.spoken(MoneyTab.PLAN, chosen = false))
    }
}
