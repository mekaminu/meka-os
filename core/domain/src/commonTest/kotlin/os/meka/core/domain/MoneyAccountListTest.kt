package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money review, Accounts: one clean list with short names, each opening its sheet. Invented banks (rule 13). */
class MoneyAccountListTest {
    private val today = 20_000L

    @Test
    fun shortNamesReadLikeTheMockup() {
        assertEquals("Amex Platinum card", MoneyAccountNames.shortName("American Express Platinum Card"))
        assertEquals("Amex", MoneyAccountNames.shortName("AMERICAN EXP"))
        assertEquals("HSBC Premier card", MoneyAccountNames.shortName("HSBC Personal PREMIER CARD"))
        assertEquals("HSBC current", MoneyAccountNames.shortName("HSBC Current Account"))
        assertEquals("Current account", MoneyAccountNames.shortName("Current account"))
        assertEquals("Oakbank saver", MoneyAccountNames.shortName("OAKBANK SAVER"))
        // A number is never shown in full, short or not.
        assertTrue(MoneyAccountNames.shortName("Revolut GB29 NWBK 6016 1331 9268 19").startsWith("Revolut ••"))
        assertFalse(MoneyAccountNames.revealsNumber(MoneyAccountNames.shortName("Card 4929123456789180")))
        assertEquals("HS", MoneyAccountList.initials("HSBC current"))
        assertEquals("··", MoneyAccountList.initials("••9180"))
    }

    private fun view(): MoneyView {
        val accounts = listOf(
            MoneyAccount("a-cur", "HSBC Current Account", "csv", 0L, MoneyBalance(1_204_00, today, typed = false)),
            MoneyAccount("a-amex", "American Express Platinum Card", "csv", 0L, MoneyBalance(-4_200_00, today, typed = false)),
            MoneyAccount("a-save", "Oakbank Saver", "bank", 0L, null),
        )
        val cards = listOf(MoneyCard("a-amex", "American Express Platinum Card", MoneyCards.CREDIT))
        val worth = MoneyWorth.view(accounts, cards, listOf(MoneyWorthItem("n-pension", "Pension", false, 42_000_00, today)), emptyList(), today)
        val cardsView = MoneyCardsView(MoneyCards.HEAD, cards.map { MoneyCards.row(it, today, emptyList()) }, null,
            listOf(MoneyCardOffer("a-save", "Oakbank Saver")), MoneyCards.FOOT)
        val business = MoneyBusiness.view(accounts, emptyList(), setOf("a-save"), emptyMap(), 20, today)
        return MoneyView(
            locked = false, summary = "", empty = null, month = null,
            accounts = listOf(
                MoneyAccountRow("a-cur", "HSBC Current Account", "120 transactions", txns = 120, updated = "26 Oct"),
                MoneyAccountRow("a-amex", "American Express Platinum Card", "40 transactions", txns = 40),
                MoneyAccountRow("a-save", "Oakbank Saver", "From your bank · 1 transaction", txns = 1, bank = true, reconnect = true),
            ),
            earlier = false, later = false, worth = worth, cards = cardsView, business = business,
        )
    }

    @Test
    fun oneRowPerAccountWithAShortNameAndItsBalanceOrAddBalance() {
        val items = MoneyAccountList.items(view())
        assertEquals(listOf("Amex Platinum card", "HSBC current", "Oakbank saver"), items.map { it.name })
        val amex = items[0]
        assertEquals("−£4,200", amex.amount)
        assertTrue(amex.owes)
        assertTrue(amex.sub.startsWith("Card"))
        assertEquals("AM", amex.initials)
        val cur = items[1]
        assertEquals("£1,204", cur.amount)
        assertEquals("Imported 26 Oct", cur.sub)
        val saver = items[2]
        assertEquals(MoneyAccountList.ADD_BALANCE, saver.amount)
        assertTrue(saver.addBalance)
        assertEquals("Side hustle · Reconnect in Lunch Flow", saver.sub)
        assertTrue(saver.lit)
        // Net worth says it's partial (once, on its own card).
        assertEquals("Partial · 1 account has no balance yet", view().worth!!.missingLine)
        // Net worth keeps only what he typed; the accounts and cards are the list.
        assertEquals(listOf("Pension"), MoneyAccountList.typedRows(view().worth!!).map { it.name })
    }

    @Test
    fun aTradingAccountIsARowThatGoesToTrading() {
        val v = view()
        val row = MoneyWorthRow("w1", "Main", MoneyWorth.TRADING, "£6,512", "Hyperliquid", false, false, false, "Main")
        val items = MoneyAccountList.items(v.copy(worth = v.worth!!.copy(rows = v.worth!!.rows + row)))
        val t = items.last()
        assertEquals("Main", t.name)
        assertFalse(t.opens)
        assertEquals(MoneyAccountList.TRADING_SUB, t.sub)
    }

    @Test
    fun theSheetHoldsWhatTheRowsUsedToScatter() {
        val v = view()
        val amex = assertNotNull(MoneyAccountList.sheet(v, "a-amex"))
        assertEquals("Amex Platinum card", amex.title)
        assertEquals("American Express Platinum Card", amex.fullName)
        assertTrue(amex.card)
        assertFalse(amex.canType)
        assertEquals(false, amex.sideHustle)
        assertTrue(amex.removeAsk.contains("its 40 transactions"))
        val saver = assertNotNull(MoneyAccountList.sheet(v, "a-save"))
        assertFalse(saver.card)
        assertEquals(true, saver.sideHustle)
        assertTrue(saver.canType)
        assertNull(saver.amount)
        assertTrue(saver.removeAsk.contains("its 1 transaction?"))
        val cur = assertNotNull(MoneyAccountList.sheet(v, "a-cur"))
        assertNull(MoneyAccountList.sheet(v, "gone"))
        assertTrue(cur.canType)
        // No side hustle block (no accounts), nothing to switch.
        assertNull(MoneyAccountList.sheet(v.copy(business = null), "a-cur")!!.sideHustle)
    }
}
