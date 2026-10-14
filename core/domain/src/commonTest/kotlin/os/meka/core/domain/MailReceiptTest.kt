package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailReceiptTest {
    @Test
    fun amountAfterATotalWord() {
        assertEquals(8420L, MailReceiptRules.amount("Your order #112-4455 has been placed", "Thanks for shopping. Order total: £84.20. Delivery Thursday"))
        assertEquals(1299L, MailReceiptRules.amount("You paid £12.99 to Oakfield Café", null))
        assertEquals(120400L, MailReceiptRules.amount("Receipt", "Item £4.00, item £1,200.00. Grand total £1,204.00 inc VAT"))
        assertEquals(500L, MailReceiptRules.amount("Payment of GBP 5 received", null))
    }

    @Test
    fun oneAmountWithoutATotalWord() {
        assertEquals(350L, MailReceiptRules.amount("Your e-ticket", "Coffee £3.50 · thank you"))
        assertEquals(350L, MailReceiptRules.amount("Coffee £3.50", "Coffee £3.50 · thank you"))
    }

    @Test
    fun unsureLeavesItUnpriced() {
        // Several prices and no total, a refund, another currency, nothing at all, an order number misread as money.
        assertNull(MailReceiptRules.amount("Your basket", "Apples £1.20, bread £2.10"))
        assertNull(MailReceiptRules.amount("Your refund of £20.00 is on its way", null))
        assertNull(MailReceiptRules.amount("Order confirmed", "Total: \$40.00"))
        assertNull(MailReceiptRules.amount("Your order has shipped", "Arriving Friday"))
        assertNull(MailReceiptRules.amount(null, null))
        assertNull(MailReceiptRules.amount("Order £99999999", null))
    }

    @Test
    fun merchantFromTheNameElseTheDomain() {
        assertEquals("Amazon", MailReceiptRules.merchant("Amazon.co.uk", "auto-confirm@amazon.co.uk"))
        assertEquals("Tesco Groceries", MailReceiptRules.merchant("Tesco Groceries", "no-reply@tesco.com"))
        assertEquals("Oakfield", MailReceiptRules.merchant("Orders | Oakfield", "orders@oakfield.example"))
        assertEquals("Trainline", MailReceiptRules.merchant("no-reply", "auto@email.trainline.com"))
        assertEquals("Shopco", MailReceiptRules.merchant(null, "orders@shopco.co.uk"))
        assertEquals("Shop", MailReceiptRules.merchant(null, "auto-confirm@shop.example"))
        assertNull(MailReceiptRules.merchant(null, null))
    }

    @Test
    fun formatAndSuffix() {
        assertEquals("£84.20", MailReceiptRules.format(8420))
        assertEquals("£1,204.05", MailReceiptRules.format(120405))
        assertEquals("£0.99", MailReceiptRules.format(99))
        assertEquals(" · £84.20 · Amazon, Tesco", MailReceiptRules.suffix(2, 2, 8420, listOf("Amazon", "Tesco")))
        assertEquals(" · £84.20 on 1 · A, B, C + 1 more", MailReceiptRules.suffix(4, 1, 8420, listOf("A", "B", "C", "D")))
        assertEquals(" · Amazon", MailReceiptRules.suffix(1, 0, null, listOf("Amazon")))
        assertEquals("", MailReceiptRules.suffix(1, 0, null, emptyList()))
    }

    @Test
    fun batchCarriesTheTotalAndTheCardSaysIt() {
        val receipts = listOf(
            MailReceiptRules.read("Amazon.co.uk", "a@amazon.co.uk", "Your order", "Order total: £60.00"),
            MailReceiptRules.read("Tesco", "no-reply@tesco.com", "Receipt", "You paid £24.20"),
            MailReceiptRules.read("Amazon.co.uk", "a@amazon.co.uk", "Dispatched", "On its way"),
        )
        val fields = MailAutopilotRules.batchFields(1_000L, "Personal", mapOf(MailTidy.RECEIPT to 3, MailTidy.NEWSLETTER to 2), receipts)
        val b = MailAutopilotRules.readBatch("b1", fields)!!
        assertEquals(8420L, b.receiptsPence)
        assertEquals(2, b.receiptsPriced)
        assertEquals(listOf("Amazon", "Tesco"), b.merchants)
        assertTrue(b.detail!!.contains("3 receipts and orders → Navilife/Receipts · £84.20 on 2 · Amazon, Tesco"), b.detail)
        assertEquals("Tidied 5 · 2 newsletters · 3 receipts and orders £84.20 on 2", ArchivedForYouRules.headline(listOf(b)))
        // A batch without receipts keeps its old fields and lines.
        val plain = MailAutopilotRules.readBatch("b2", MailAutopilotRules.batchFields(1_000L, "Personal", mapOf(MailTidy.NEWSLETTER to 1)))!!
        assertNull(plain.receiptsPence)
        assertEquals("1 newsletter → Navilife/Newsletters", plain.detail)
        assertEquals("Tidied 1 · 1 newsletter", ArchivedForYouRules.headline(listOf(plain)))
    }

    @Test
    fun allPricedHeadline() {
        val receipts = listOf(MailReceiptRules.read("Tesco", null, "Receipt", "Total £10.00"))
        val b = MailAutopilotRules.readBatch("b3", MailAutopilotRules.batchFields(1L, "Personal", mapOf(MailTidy.RECEIPT to 1), receipts))!!
        assertEquals("Tidied 1 · 1 receipt or order £10.00", ArchivedForYouRules.headline(listOf(b)))
    }
}
