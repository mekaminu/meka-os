package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        assertEquals(listOf(MoneySection.MONTH, MoneySection.ALERTS, MoneySection.CATEGORIES, MoneySection.PAYMENTS, MoneySection.SPEND_FOOT), MoneyTabs.sections(MoneyTab.SPENDING, v))
        assertEquals(listOf(MoneySection.REFUNDS, MoneySection.REGULAR, MoneySection.BILLS_FOOT), MoneyTabs.sections(MoneyTab.BILLS, v))
        assertEquals(
            listOf(MoneySection.BANK, MoneySection.ACCOUNTS, MoneySection.MORE, MoneySection.FOOT),
            MoneyTabs.sections(MoneyTab.ACCOUNTS, v),
        )
        // Nothing appears on two tabs: the long page is gone.
        val all = MoneyTabs.ALL.flatMap { MoneyTabs.sections(it, v) }
        assertEquals(all.size, all.toSet().size)
        assertTrue(MoneyTabs.shows(MoneyTab.BILLS, MoneySection.REGULAR, v))
        assertTrue(!MoneyTabs.shows(MoneyTab.SPENDING, MoneySection.REGULAR, v))
    }

    @Test
    fun spendingEndsOnOneFootLineWhoseInfoSaysWhereTheNumbersCameFrom() {
        // Layout rule (c): "read-only / not financial advice" once, at the foot of the tab; the source behind ⓘ.
        val v = open().copy(source = MoneySource.BANK)
        assertEquals(MoneySection.SPEND_FOOT, MoneyTabs.sections(MoneyTab.SPENDING, v).last())
        val info = assertNotNull(MoneyTabs.info(MoneyTab.SPENDING, v))
        assertEquals("From your banks through Lunch Flow. Worked out on this device.", info.first())
        assertTrue(info.last() == MoneyRules.FOOT)
        assertEquals("Read-only · not financial advice", MoneyTabs.FOOT_SHORT)
        // No month, no foot; Trading has no foot of its own.
        assertTrue(MoneySection.SPEND_FOOT !in MoneyTabs.sections(MoneyTab.SPENDING, open(m = null)))
        assertNull(MoneyTabs.info(MoneyTab.TRADING, v))
    }

    @Test
    fun accountsEndsOnTheSameFootLineWhoseInfoSaysWhereAccountsBanksWorthAndTheYearComeFrom() {
        // Layout rules on Accounts: the long read-only line and net worth's and the year's source lines live behind ⓘ.
        val v = open()
        assertEquals(MoneySection.FOOT, MoneyTabs.sections(MoneyTab.ACCOUNTS, v).last())
        val info = assertNotNull(MoneyTabs.info(MoneyTab.ACCOUNTS, v))
        assertEquals(MoneyTabs.ACCOUNTS_FROM, info.first())
        assertTrue(MoneyTabs.BANK_FROM in info, "his banks are connected, so the ⓘ says how they're read")
        assertEquals(MoneyRules.FOOT, info.last())
        // No bank, no worth, no year: only what's there is said.
        val bare = assertNotNull(MoneyTabs.info(MoneyTab.ACCOUNTS, open().copy(bank = null)))
        assertEquals(listOf(MoneyTabs.ACCOUNTS_FROM, MoneyRules.FOOT), bare)
        // Nothing on Accounts at all still ends on the foot (Import, export and delete is always there).
        assertEquals(MoneySection.FOOT, MoneyTabs.sections(MoneyTab.ACCOUNTS, open(m = null).copy(accounts = emptyList(), bank = null)).last())
    }

    @Test
    fun billsEndsOnTheSameFootLineWhoseInfoSaysWhereBillsCardsAndRefundsComeFrom() {
        val v = open()
        assertEquals(MoneySection.BILLS_FOOT, MoneyTabs.sections(MoneyTab.BILLS, v).last())
        val info = assertNotNull(MoneyTabs.info(MoneyTab.BILLS, v))
        assertEquals(MoneyTabs.BILLS_FROM, info.first())
        assertTrue(MoneyTabs.REFUNDS_FROM in info, "refunds are on Bills, so the ⓘ says where they come from")
        assertTrue(MoneyTabs.CARDS_FROM !in info, "no cards, no cards line")
        assertEquals(MoneyRules.FOOT, info.last())
        // An empty Bills keeps its empty line and has no foot.
        val nothing = open().copy(regular = null, refunds = null)
        assertTrue(MoneyTabs.sections(MoneyTab.BILLS, nothing).isEmpty())
        assertEquals(MoneyTabs.EMPTY_BILLS, MoneyTabs.empty(MoneyTab.BILLS, nothing))
    }

    @Test
    fun theYearMovesToAccountsAndTheTipsToPlan() {
        // Money review (Meka 2026-10-26 22:12): "2026 so far" and "Tips from your numbers" leave Spending.
        val tip = MoneyTip("t1", "habit", "Coffee adds up", "£64 this month", false, "Coffee adds up")
        val v = open().copy(tips = listOf(tip))
        assertEquals(MoneyTab.ACCOUNTS, MoneyTabs.tabOf(MoneySection.YEAR))
        assertEquals(MoneyTab.PLAN, MoneyTabs.tabOf(MoneySection.TIPS))
        assertTrue(MoneySection.TIPS !in MoneyTabs.sections(MoneyTab.SPENDING, v))
        assertEquals(MoneySection.TIPS, MoneyTabs.sections(MoneyTab.PLAN, v).first())
        assertEquals(MoneyTab.SPENDING, MoneyTabs.tabOf(MoneySection.UNSORTED))
    }

    @Test
    fun theBillsCardLeadsTheBillsTabAndThePaydayBlockStopsListingTheSameRows() {
        val bills = MoneyBills.view(
            listOf(MoneyBills.Due("r-a", "Streamly", 10, 1_099, MoneyBillKind.REGULAR)), emptyList(), "Fri 30 Oct", 5,
        )
        val v = open().copy(bills = bills)
        // Money review (Meka 2026-10-26 22:12): the regular payments are the Bills card's list, so they don't repeat.
        assertEquals(listOf(MoneySection.BILLS, MoneySection.REFUNDS, MoneySection.BILLS_FOOT), MoneyTabs.sections(MoneyTab.BILLS, v))
        assertTrue(!MoneyTabs.paydayRows(v))
        assertTrue(MoneyTabs.paydayRows(open()))
    }

    @Test
    fun billsShowsOnlyCardsAndTheAccountsThatCouldBeOneAreMarkedInTheirSheet() {
        val card = MoneyCards.row(MoneyCard("a-card", "Platinum Card", MoneyCards.CREDIT), 100, emptyList())
        val offer = MoneyCardOffer("a-saver", "Easy Saver")
        val onlyOffers = open().copy(cards = MoneyCardsView(MoneyCards.HEAD, emptyList(), MoneyCards.OFFER_FIRST, listOf(offer), MoneyCards.FOOT))
        assertTrue(MoneySection.CARDS !in MoneyTabs.sections(MoneyTab.BILLS, onlyOffers))
        // Money review, Accounts: "Is one of these a card?" left the page; each account's sheet has It's a card.
        assertEquals(
            listOf(MoneySection.BANK, MoneySection.ACCOUNTS, MoneySection.MORE, MoneySection.FOOT),
            MoneyTabs.sections(MoneyTab.ACCOUNTS, onlyOffers),
        )
        val both = open().copy(cards = MoneyCardsView(MoneyCards.HEAD, listOf(card), MoneyCards.OFFER_MORE, listOf(offer), MoneyCards.FOOT))
        assertTrue(MoneySection.CARDS in MoneyTabs.sections(MoneyTab.BILLS, both))
    }

    @Test
    fun theAccountsListFollowsNetWorthAndShowsForATradingAccountAlone() {
        val worth = MoneyWorth.view(emptyList(), emptyList(), listOf(MoneyWorthItem("n-pension", "Pension", false, 100_00, 100)), emptyList(), 100)
        val v = open().copy(worth = worth)
        val s = MoneyTabs.sections(MoneyTab.ACCOUNTS, v)
        assertEquals(s.indexOf(MoneySection.WORTH) + 1, s.indexOf(MoneySection.ACCOUNTS))
        val noAccounts = MoneyView(false, "", null, null, emptyList(), earlier = false, later = false, worth = worth)
        assertTrue(MoneySection.ACCOUNTS !in MoneyTabs.sections(MoneyTab.ACCOUNTS, noAccounts))
        val tradingRow = MoneyWorthRow("w1", "Main", MoneyWorth.TRADING, "£6,512", "Hyperliquid", false, false, false, "Main")
        val trading = noAccounts.copy(worth = worth.copy(rows = worth.rows + tradingRow))
        assertTrue(MoneySection.ACCOUNTS in MoneyTabs.sections(MoneyTab.ACCOUNTS, trading))
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
        assertTrue(MoneySection.MORE in MoneyTabs.sections(MoneyTab.ACCOUNTS, nothing))
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
