package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyReceiptsTest {
    private fun day(m: Int, d: Int) = CivilDate.toEpochDay(2026, m, d)
    private fun receipt(key: String, d: Long, pence: Long, merchant: String, subject: String, preview: String? = null) =
        MoneyReceiptMail(key, d, pence, merchant, subject, preview)
    private fun line(id: String, d: Long, pence: Long, words: String) = MoneyReceiptLine(id, d, pence, MoneyCategoryRules.merchantKey(words))

    @Test
    fun theSameShopAndAmountMatchWithinTheWindow() {
        val r = receipt("r1", day(10, 10), 4_999, "Currys", "Your Currys order of a kettle")
        val lines = listOf(
            line("t-early", day(10, 8), -4_999, "CURRYS RETAIL LTD"),
            line("t1", day(10, 12), -4_999, "CURRYS RETAIL LTD"),
            line("t-in", day(10, 12), 4_999, "CURRYS REFUND"),
        )
        // Two days before the email is outside the window; money in is never a receipt's payment.
        assertEquals(mapOf("t1" to r), MoneyReceipts.match(lines, listOf(r)))
        // Six days after is too late.
        assertTrue(MoneyReceipts.match(listOf(line("t2", day(10, 16), -4_999, "CURRYS")), listOf(r)).isEmpty())
        // A day before (a late-evening order) is fine.
        assertEquals(setOf("t3"), MoneyReceipts.match(listOf(line("t3", day(10, 9), -4_999, "CURRYS")), listOf(r)).keys)
    }

    @Test
    fun aNamelessMatchNeedsToBeTheOnlyOneBothWays() {
        val amazon = receipt("r1", day(10, 10), 1_299, "Amazon.co.uk", "Your Amazon.co.uk order of “Phone case”")
        // "AMZNMKTPLACE" doesn't name Amazon, but it is the only £12.99 payment near the only £12.99 receipt.
        assertEquals(setOf("t1"), MoneyReceipts.match(listOf(line("t1", day(10, 11), -1_299, "AMZNMKTPLACE")), listOf(amazon)).keys)
        // Two £12.99 payments near it and neither names the shop: no guess.
        val two = listOf(line("t1", day(10, 11), -1_299, "AMZNMKTPLACE"), line("t2", day(10, 12), -1_299, "CINEWORLD"))
        assertTrue(MoneyReceipts.match(two, listOf(amazon)).isEmpty())
        // Two £12.99 receipts near the one payment: no guess.
        val other = receipt("r2", day(10, 11), 1_299, "Shopco", "Your Shopco receipt")
        assertTrue(MoneyReceipts.match(listOf(line("t1", day(10, 11), -1_299, "AMZNMKTPLACE")), listOf(amazon, other)).isEmpty())
        // ... unless one of them names the payment's shop.
        val shopLine = line("t9", day(10, 12), -1_299, "SHOPCO LONDON")
        assertEquals(mapOf("t9" to other), MoneyReceipts.match(listOf(shopLine), listOf(amazon, other)))
    }

    @Test
    fun eachPaymentAndEachReceiptIsUsedOnce() {
        val a = receipt("r1", day(10, 5), 350, "Pret A Manger", "Your Pret receipt")
        val b = receipt("r2", day(10, 6), 350, "Pret A Manger", "Your Pret receipt")
        val lines = listOf(line("t1", day(10, 5), -350, "PRET A MANGER"), line("t2", day(10, 6), -350, "PRET A MANGER"))
        assertEquals(mapOf("t1" to a, "t2" to b), MoneyReceipts.match(lines, listOf(a, b)))
        assertTrue(MoneyReceipts.sameShop("Tesco", "TESCO STORES"))
        assertFalse(MoneyReceipts.sameShop("Amazon.co.uk", "AMZNMKTPLACE"))
        assertFalse(MoneyReceipts.sameShop("The Shop Ltd", "SHOPCO"))
    }

    @Test
    fun theLineQuotesTheSubjectAndThePreviewOnlyWhenItSaysMore() {
        val r = receipt("r1", day(10, 10), 4_999, "Currys", "Your Currys order", "Kettle, 1.7 litre, brushed steel · Delivery Thu")
        assertEquals("Receipt in your email · “Your Currys order”", MoneyReceipts.line(r))
        assertEquals("Kettle, 1.7 litre, brushed steel · Delivery Thu", MoneyReceipts.detail(r))
        assertNull(MoneyReceipts.detail(r.copy(preview = "Your Currys order")))
        assertNull(MoneyReceipts.detail(r.copy(preview = null)))
        assertEquals("Receipt in your email · Currys", MoneyReceipts.line(r.copy(subject = MailTidiedRules.NO_SUBJECT)))
        assertTrue(MoneyReceipts.line(r.copy(subject = "x".repeat(200))).endsWith("…”"))
        assertNull(MoneyReceipts.monthLine(0, "October"))
        assertEquals("1 of October's payments has a receipt in your email", MoneyReceipts.monthLine(1, "October"))
        assertEquals("3 of September's payments have a receipt in your email", MoneyReceipts.monthLine(3, "September"))
    }

    @Test
    fun receiptsComeFromTidiedReceiptsWithAPriceOnly() {
        val at = day(10, 10) * 86_400_000L + 9 * 3_600_000L
        fun e(id: String, tidy: MailTidy, pence: Long?) =
            MailTidiedEmail(MailTidiedRules.key("acc", id), tidy, null, at, "Currys", "currys.example", "Your Currys order", pence, preview = "Kettle")
        val batch = MailTidyBatch(
            "b1", at, "Personal", "Tidied 3", null, 3, null, null, null,
            emails = listOf(e("1", MailTidy.RECEIPT, 4_999), e("2", MailTidy.RECEIPT, null), e("3", MailTidy.NEWSLETTER, 500)),
        )
        val got = MoneyReceipts.fromTidies(listOf(batch, batch.copy(id = "b2")), LocalCalendar.UTC)
        assertEquals(1, got.size)
        assertEquals(day(10, 10), got.single().day)
        assertEquals(4_999L, got.single().pence)
        assertEquals("Kettle", got.single().preview)
    }

    @Test
    fun theMoneyScreenShowsTheReceiptUnderItsPayment() {
        val acct = listOf(MoneyAccount("a1", "Current account", "csv", null))
        val txns = listOf(
            MoneyTxn("t1", "a1", day(10, 12), -4_999, "CURRYS RETAIL LTD"),
            MoneyTxn("t2", "a1", day(10, 13), -320, "COSTA COFFEE"),
            MoneyTxn("t0", "a1", day(9, 20), -1_000, "BOOTS"),
        )
        val r = receipt("r1", day(10, 10), 4_999, "Currys", "Your Currys order", "Kettle, 1.7 litre")
        val v = MoneyRules.view(acct, txns, emptyMap(), day(10, 19), LocalCalendar.UTC, 0, receipts = listOf(r))
        val m = v.month!!
        val row = m.txns.first { it.id == "t1" }
        assertEquals("Receipt in your email · “Your Currys order”", row.receipt)
        assertEquals("Kettle, 1.7 litre", row.receiptDetail)
        assertTrue(row.spoken.endsWith(", receipt in your email: Your Currys order"))
        assertNull(m.txns.first { it.id == "t2" }.receipt)
        assertEquals("1 of October's payments has a receipt in your email", m.receiptsLine)
        // Without receipts nothing changes.
        assertNull(MoneyRules.view(acct, txns, emptyMap(), day(10, 19), LocalCalendar.UTC, 0).month!!.receiptsLine)
        // September has no matched payment.
        assertNull(MoneyRules.view(acct, txns, emptyMap(), day(10, 19), LocalCalendar.UTC, 1, receipts = listOf(r)).month!!.receiptsLine)
    }
}
