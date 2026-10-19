package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyBusinessMailTest {
    private val today = CivilDate.toEpochDay(2026, 10, 19)
    private fun day(m: Int, d: Int) = CivilDate.toEpochDay(2026, m, d)

    @Test
    fun moneyInAndCostsAreReadOnlyWhenTheWordsAreSure() {
        val paid = MoneyBusinessMail.read("Invoicely", "no-reply@invoicely.example", "Acme Ltd paid invoice INV-12", "Amount paid: £300.00")!!
        assertEquals(MoneyBusinessMail.IN, paid.direction)
        assertEquals(30_000L, paid.pence)
        assertEquals("Acme Ltd", paid.party)

        val received = MoneyBusinessMail.read("PayCo", "service@payco.example", "You've received a payment", "Sam Taylor sent you £45.50 GBP")!!
        assertEquals(MoneyBusinessMail.IN, received.direction)
        assertEquals(4_550L, received.pence)
        assertEquals("Sam Taylor", received.party)

        val payout = MoneyBusinessMail.read("Stripe", "notifications@stripe.example", "Your payout of £120.00 is on its way", null)!!
        assertEquals(MoneyBusinessMail.IN, payout.direction)
        assertEquals("Stripe", payout.party)

        val cost = MoneyBusinessMail.read("Canva", "receipts@canva.example", "Your Canva receipt", "Total: £10.99")!!
        assertEquals(MoneyBusinessMail.OUT, cost.direction)
        assertEquals(1_099L, cost.pence)
        assertEquals("Canva", cost.party)

        // His own payment acknowledged is a cost, never money in.
        val ack = MoneyBusinessMail.read("Hostco", "billing@hostco.example", "Payment confirmation · we've received your payment", "Invoice INV-88 · total £24.00")!!
        assertEquals(MoneyBusinessMail.OUT, ack.direction)

        // Not money moving, or no amount: nothing.
        assertNull(MoneyBusinessMail.read("Hostco", "billing@hostco.example", "Invoice INV-90 · payment due 30 Oct", "Amount due £24.00"))
        assertNull(MoneyBusinessMail.read("Shopco", "orders@shopco.example", "Your refund of £12.00 has been processed", null))
        assertNull(MoneyBusinessMail.read("Hostco", "billing@hostco.example", "Payment failed for your invoice", "£24.00"))
        assertNull(MoneyBusinessMail.read("Builder", "quotes@builder.example", "Your quote", "Total £400"))
        assertNull(MoneyBusinessMail.read("Canva", "receipts@canva.example", "Your Canva receipt", "Thanks for using Canva"))
        assertNull(MoneyBusinessMail.read("Alex", "alex@example.org", "Lunch on Friday?", "It'll be about £20 each"))
    }

    @Test
    fun theFindIsWrittenOnceWithItsWayAndReadBack() {
        val inv = MailInvoice(MoneyBusinessMail.IN, 30_000, "Acme Ltd")
        val fields = MoneyBusinessMail.serverFields(inv, "Business", "Acme Ltd paid invoice INV-12", day(10, 3))
        val f = MailRenewalRules.read("x", fields)!!
        assertTrue(MoneyBusinessMail.isInvoice(f))
        assertEquals(MoneyBusinessMail.IN, f.direction)
        assertEquals("Acme Ltd", f.merchant)
        assertEquals(day(10, 3), f.receivedDay)
        assertEquals(MoneyBusinessMail.findId("acc", "m1"), MoneyBusinessMail.findId("acc", "m1"))
        assertFalse(MoneyBusinessMail.findId("acc", "m1") == MoneyBusinessMail.findId("acc", "m2"))
        // An invoice find never shows on the mail finds card.
        assertNull(MailRenewalRules.card(listOf(f), emptyList(), today * 86_400_000L, LocalCalendar.UTC))
    }

    private fun find(id: String, dir: String, pence: Long, who: String?, d: Long, state: MailFindState = MailFindState.OPEN) =
        MailFind(id, MoneyBusinessMail.KIND_INVOICE, "Business", who, d, pence, ObligationKind.SUBSCRIPTION, RenewalRepeat.NONE, d,
            "Subject $id", state, 0L, direction = dir)

    private fun line(id: String, account: String, d: Long, pence: Long, merchant: String, cat: MoneyCategory = MoneyCategory.OTHER) =
        MoneyWatchLine(id, account, d, pence, merchant, cat, merchant)

    @Test
    fun eachEmailIsCheckedAgainstTheStatementsOnce() {
        val finds = listOf(
            find("f1", MoneyBusinessMail.IN, 30_000, "Acme Ltd", day(10, 3)),
            find("f2", MoneyBusinessMail.OUT, 1_099, "Canva", day(9, 20)),
            find("f3", MoneyBusinessMail.IN, 30_000, "Acme Ltd", day(10, 4)), // the same money twice: one line only
            find("f4", MoneyBusinessMail.OUT, 2_400, "Hostco", day(9, 1)), // never in the statements
            find("f5", MoneyBusinessMail.OUT, 5_000, "Old", day(3, 1)), // last tax year
            find("f6", MoneyBusinessMail.OUT, 700, "Gone", day(10, 1), MailFindState.DISMISSED),
        )
        val lines = listOf(
            line("t1", "a-shop", day(10, 6), 30_000, "acme ltd", MoneyCategory.INCOME),
            line("t2", "a-current", day(9, 21), -1_099, "canva", MoneyCategory.SUBSCRIPTIONS),
            line("t3", "a-current", day(10, 12), -500, "tesco", MoneyCategory.GROCERIES),
        )
        val v = MoneyBusinessMail.view(finds, lines, setOf("a-shop"), emptyMap(), today)!!
        assertEquals(MoneyBusinessMail.HEAD, v.head)
        assertEquals("4 this tax year · in £600 · costs £34.99 · 2 in your statements", v.line)
        assertEquals(listOf("f3", "f1", "f2", "f4"), v.rows.map { it.id })
        val f1 = v.rows.first { it.id == "f1" }
        assertEquals("Acme Ltd · £300", f1.title)
        assertEquals("Paid to you · Sat 3 Oct · in your statements · counted", f1.line)
        assertNull(f1.businessTxnId)
        val f3 = v.rows.first { it.id == "f3" }
        assertFalse(f3.matched)
        assertFalse(f3.lit) // the statements reach 12 Oct, before 4 + 10 days
        assertEquals("Paid to you · Sun 4 Oct · not in your statements yet", f3.line)
        val f2 = v.rows.first { it.id == "f2" }
        assertEquals("A cost · Sun 20 Sep · in your statements · not counted as business yet", f2.line)
        assertEquals("t2", f2.businessTxnId)
        val f4 = v.rows.first { it.id == "f4" }
        assertTrue(f4.lit)
        assertEquals("A cost · Tue 1 Sep · not in your statements · paid another way?", f4.line)
        assertEquals("“Subject f4”", f4.quote)

        // Once Canva is marked Business its line counts and the chip goes.
        val marked = MoneyBusinessMail.view(finds, lines, setOf("a-shop"), mapOf("canva" to true), today)!!
        assertNull(marked.rows.first { it.id == "f2" }.businessTxnId)
        assertTrue(marked.rows.first { it.id == "f2" }.line.endsWith("counted"))
    }

    @Test
    fun withoutStatementsItSaysSoAndNothingMeansNoBlock() {
        val v = MoneyBusinessMail.view(listOf(find("f1", MoneyBusinessMail.OUT, 1_099, null, day(10, 1))), emptyList(), emptySet(), emptyMap(), today)!!
        assertEquals("1 this tax year · costs £10.99", v.line)
        assertEquals("A cost · £10.99", v.rows.single().title)
        assertTrue(v.rows.single().line.endsWith("add a statement to check it"))
        assertNull(v.moreLine)
        assertNull(MoneyBusinessMail.view(emptyList(), emptyList(), emptySet(), emptyMap(), today))
        val many = (1..8).map { find("f$it", MoneyBusinessMail.OUT, 100L * it, "Shop $it", day(10, it)) }
        val m = MoneyBusinessMail.view(many, emptyList(), emptySet(), emptyMap(), today)!!
        assertEquals(MoneyBusinessMail.SHOWN, m.rows.size)
        assertEquals("and 2 more this tax year", m.moreLine)
        assertNotNull(m.rowAt(0))
    }
}
