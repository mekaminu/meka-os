package os.meka.core.domain

import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money manager, slice 1: statement files read, merchants categorised, the month added up. Invented statements only. */
class MoneyTest {
    private val oct16 = CivilDate.toEpochDay(2026, 10, 16)

    @Test
    fun readsTheCommonStatementShapes() {
        // One signed amount, day-first dates, quotes with commas, a £ and thousands.
        val a = MoneyCsv.read(
            "Date,Description,Amount,Balance\n" +
                "15/10/2026,\"CARD PAYMENT TO TESCO STORES 2041, ON 14 OCT\",-23.40,100.00\n" +
                "14/10/2026,SALARY OAKFIELD LTD,\"£2,500.00\",123.40\n" +
                "13/10/2026,,,\n",
        )
        assertNull(a.problem)
        assertEquals(2, a.rows.size)
        assertEquals(1, a.skipped)
        assertEquals(MoneyRow(CivilDate.toEpochDay(2026, 10, 15), -2340, "CARD PAYMENT TO TESCO STORES 2041, ON 14 OCT"), a.rows[0])
        assertEquals(250_000L, a.rows[1].pence)
        // Paid out / Paid in columns, ISO dates with a time, a preamble before the header.
        val b = MoneyCsv.read(
            "Account,Everyday\n\nTransaction Date,Transaction Description,Paid out,Paid in\n" +
                "2026-10-02T09:12:00,COSTA COFFEE,3.20,\n2026-10-03,REFUND AMAZON,,12.99\n",
        )
        assertEquals(listOf(-320L, 1299L), b.rows.map { it.pence })
        assertEquals(CivilDate.toEpochDay(2026, 10, 2), b.rows[0].day)
        // Semicolons, "15 Oct 2026" and (brackets) for money out.
        val c = MoneyCsv.read("Date;Name;Value\n15 Oct 2026;Netflix;(10.99)\n")
        assertEquals(-1099L, c.rows.single().pence)
        // A card statement listing purchases as positive: turned round, and said so.
        val card = MoneyCsv.read("Date,Description,Amount\n01/10/2026,AMAZON.CO.UK,45.00\n02/10/2026,PRET A MANGER,6.10\n03/10/2026,DELIVEROO,21.50\n05/10/2026,PAYMENT RECEIVED - THANK YOU,-200.00\n")
        assertTrue(card.flipped)
        assertEquals(listOf(-4500L, -610L, -2150L, 20_000L), card.rows.map { it.pence })
        // Not a statement.
        assertEquals(MoneyCsv.NOT_CSV, MoneyCsv.read("OFXHEADER:100\nDATA:OFXSGML").problem)
        assertEquals(MoneyCsv.NO_HEADER, MoneyCsv.read("a,b,c\n1,2,3\n").problem)
        assertEquals(-1230L, MoneyCsv.pence("12.30 DR"))
        assertEquals(500L, MoneyCsv.pence("+5"))
        assertNull(MoneyCsv.day("31/02/2026"))
        assertEquals(CivilDate.toEpochDay(2026, 3, 1), MoneyCsv.day("01-Mar-26"))
    }

    @Test
    fun merchantsAndCategories() {
        assertEquals("TESCO STORES", MoneyCategoryRules.merchantKey("CARD PAYMENT TO TESCO STORES 2041 ON 14 OCT"))
        assertEquals("AMZN MKTP", MoneyCategoryRules.merchantKey("AMZN MKTP UK*AB12CD34"))
        assertEquals("Tesco Stores", MoneyCategoryRules.merchantLabel("TESCO STORES"))
        assertEquals("TFL Travel Charge", MoneyCategoryRules.merchantLabel("TFL TRAVEL CHARGE"))
        assertEquals(MoneyCategory.GROCERIES, MoneyCategoryRules.categoryOf("SAINSBURYS S/MKTS", -1200))
        assertEquals(MoneyCategory.TAKEAWAY, MoneyCategoryRules.categoryOf("UBER EATS", -1500))
        assertEquals(MoneyCategory.TRANSPORT, MoneyCategoryRules.categoryOf("UBER *TRIP", -900))
        assertEquals(MoneyCategory.TRANSPORT, MoneyCategoryRules.categoryOf("TFL TRAVEL CHARGE", -280))
        assertEquals(MoneyCategory.COFFEE, MoneyCategoryRules.categoryOf("COSTA COFFEE", -320))
        assertEquals(MoneyCategory.SUBSCRIPTIONS, MoneyCategoryRules.categoryOf("NETFLIX.COM", -1099))
        assertEquals(MoneyCategory.SHOPPING, MoneyCategoryRules.categoryOf("AMZN MKTP UK", -4500))
        // A whole word only: "business" isn't a bus.
        assertEquals(MoneyCategory.OTHER, MoneyCategoryRules.categoryOf("BUSINESS SUPPLIES CO", -4500))
        assertEquals(MoneyCategory.INCOME, MoneyCategoryRules.categoryOf("SALARY OAKFIELD LTD", 250_000))
        // Money back from a shop nets off that shop's category; a payment to the card is a transfer.
        assertEquals(MoneyCategory.SHOPPING, MoneyCategoryRules.categoryOf("REFUND AMAZON", 1299))
        assertEquals(MoneyCategory.TRANSFERS, MoneyCategoryRules.categoryOf("PAYMENT RECEIVED - THANK YOU", 20_000))
        assertEquals(MoneyCategory.TRANSFERS, MoneyCategoryRules.categoryOf("TRANSFER TO SAVINGS", -5_000))
    }

    private fun txn(id: String, y: Int, m: Int, d: Int, pence: Long, text: String) =
        MoneyTxn(id, "a-current", CivilDate.toEpochDay(y, m, d), pence, text)

    @Test
    fun theMonthAgainstTheSixBefore() {
        val acct = listOf(MoneyAccount("a-current", "Current account", "csv", null))
        // April to September: £100 a month on groceries. September: £200, and coffee.
        val past = (4..9).map { m -> txn("g$m", 2026, m, 10, -10_000, "TESCO STORES") }
        val sept = listOf(txn("g9b", 2026, 9, 20, -10_000, "TESCO STORES"), txn("c9", 2026, 9, 21, -320, "COSTA"), txn("s9", 2026, 9, 25, 250_000, "SALARY"))
        val octo = listOf(txn("o1", 2026, 10, 2, -4_000, "TESCO STORES"), txn("o2", 2026, 10, 3, -5_000, "TRANSFER TO SAVINGS"))
        val all = past + sept + octo
        val now = MoneyRules.view(acct, all, emptyMap(), oct16, LocalCalendar.UTC, 0)
        assertFalse(now.locked)
        val m = assertNotNull(now.month)
        assertEquals("October so far", m.label)
        assertEquals("Spent £40", m.spentLine)
        // Transfers aren't spending; the month in progress isn't judged.
        assertEquals(listOf("groceries"), m.categories.map { it.key })
        assertEquals("1 payment", m.categories.single().line)
        assertEquals("Your 6-month average is £117.20 a month", m.vsLine)
        assertTrue(now.earlier)
        assertFalse(now.later)
        // September: £203.20 against an average over April–August of £100.
        val sep = MoneyRules.view(acct, all, emptyMap(), oct16, LocalCalendar.UTC, 1).month!!
        assertEquals("September 2026", sep.label)
        assertEquals("Spent £203.20 · in £2,500", sep.spentLine)
        assertEquals("£103.20 more than your 6-month average", sep.vsLine)
        assertTrue(sep.vsLit)
        val groceries = sep.categories.first()
        assertEquals("£200", groceries.amount)
        assertTrue(groceries.over)
        assertEquals("2 payments · £100 over your 6-month average", groceries.line)
        assertEquals("none in the 6 months before", sep.categories[1].line.substringAfter(" · "))
        assertEquals(listOf("−£3.20", "−£100", "−£100").sorted(), sep.txns.filter { !it.moneyIn }.map { it.amount }.sorted())
        // His correction: Costa is Eating out, for every Costa payment.
        val taught = MoneyRules.view(acct, all, mapOf("COSTA" to MoneyCategory.EATING_OUT), oct16, LocalCalendar.UTC, 1).month!!
        assertTrue("eating_out" in taught.categories.map { it.key })
        assertTrue(taught.txns.first { it.merchant == "Costa" }.taught)
        // The tile never shows an amount.
        assertEquals("Current account · updated Fri 16 Oct", MoneyRules.tile(acct.map { it.copy(importedAtMs = oct16 * CivilDate.DAY_MS) }, LocalCalendar.UTC)?.text)
        assertNull(MoneyRules.tile(emptyList(), LocalCalendar.UTC))
        assertEquals(MoneyRules.EMPTY, MoneyRules.view(acct, emptyList(), emptyMap(), oct16, LocalCalendar.UTC, 0).empty)
    }

    private var opN = 0
    private var clockMs = 1_000L
    private fun replica(): Replica = Replica("hh", "android", HlcClock("android", { clockMs++ }), InMemoryReplicaStore(), MekaSchema) { "op" + opN++ }

    @Test
    fun importTwiceAddsNothingTwiceAndOneRefresh() {
        val r = replica()
        var told = 0
        r.addListener { told++ }
        val money = Money(r) { oct16 * CivilDate.DAY_MS }
        r.addListener { money.changed(it) }
        val file = "Date,Description,Amount\n01/10/2026,COSTA,-3.20\n01/10/2026,COSTA,-3.20\n02/10/2026,TESCO,-20.00\n"
        val first = money.import("Current account", file)
        assertFalse(first.refused)
        assertEquals("Added 3 transactions to Current account · 1 Oct 2026 – 2 Oct 2026", first.line)
        assertEquals(1, told)
        assertEquals(3, money.txns().size)
        val again = money.import("Current account", file + "03/10/2026,PRET,-4.00\n")
        assertEquals("Added 1 transaction to Current account · 1 Oct 2026 – 3 Oct 2026 · 3 already here", again.line)
        assertEquals(4, money.txns().size)
        assertTrue(money.import("", file).refused)
        // Teach a category, Undo it.
        val costa = money.txns().first { it.text == "COSTA" }
        val saved = money.setCategory(costa.id, "eating_out")
        assertEquals("Costa is Eating out from now on · 2 payments", saved.line)
        assertEquals(MoneyCategory.EATING_OUT, money.rules()["COSTA"])
        assertTrue(money.undoCategory(saved.undo!!))
        assertNull(money.rules()["COSTA"])
        // Remove the account, then import it again: it comes back.
        assertEquals("Removed Current account · 4 transactions", money.removeAccount("a-current-account").line)
        assertTrue(money.accounts().isEmpty())
        assertTrue(money.txns().isEmpty())
        money.import("Current account", file)
        assertEquals(3, money.txns().size)
        money.deleteAll()
        assertTrue(money.accounts().isEmpty() && money.txns().isEmpty() && money.rules().isEmpty())
    }
}
