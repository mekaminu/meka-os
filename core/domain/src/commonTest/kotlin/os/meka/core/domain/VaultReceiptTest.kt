package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Receipts → Vault: a tidied receipt kept as a Vault document, its warranty as the date, one per email. */
class VaultReceiptTest {
    private val cal = LocalCalendar.UTC
    private val day = CivilDate.toEpochDay(2026, 10, 10)
    private fun at(d: Long, minute: Int = 600) = cal.toEpochMs(d, minute)

    private fun email(id: String, subject: String, pence: Long?, preview: String? = null, tidy: MailTidy = MailTidy.RECEIPT, from: String = "Currys") =
        MailTidiedEmail(MailTidiedRules.key("acc", id), tidy, null, at(day), from, "currys.co.uk", subject, pence, preview = preview)

    private fun batch(emails: List<MailTidiedEmail>): MailTidyBatch {
        val fields = MailAutopilotRules.batchFields(at(day, 700), "Personal", emails.groupingBy { it.tidy }.eachCount()) + MailTidiedRules.fields(emails)
        return MailAutopilotRules.readBatch("mt1", fields)!!
    }

    @Test
    fun aPricedReceiptOverTheThresholdTakesRenewalsWarrantyAndAddsNoSecondHeadsUp() {
        val e = email("m1", "Your Currys order", 49_900, "Order total: £499.00 · 2-year guarantee")
        val (entry, r) = assertNotNull(VaultReceiptRules.entry(batch(listOf(e)), e, cal, MailWarrantyLevel.HUNDRED))
        assertEquals("Currys receipt", entry.title)
        assertEquals(DocKind.RECEIPT, entry.kind)
        assertEquals(CivilDate.toEpochDay(2028, 10, 9), entry.expiresDay)
        assertTrue(r.onRenewals)
        assertEquals(r, VaultReceiptRules.decode(VaultReceiptRules.encode(r)))
        val item = DocumentItem("d1", entry.title, entry.kind, entry.expiresDay, 0, receipt = r)
        val v = DocumentRules.view(listOf(item), day + 1, cal)
        assertEquals("Receipt · £499.00 · bought Sat 10 Oct 2026 · Personal mail", v.rows.single().line)
        assertEquals("Warranty until Mon 9 Oct 2028", v.rows.single().status)
        assertTrue(v.rows.single().fromMail)
        // Renewals reminds about it; the Vault doesn't, even on the day.
        assertTrue(DocumentRules.notices(listOf(item), at(entry.expiresDay!!), cal).isEmpty())
    }

    @Test
    fun aCheapReceiptKeepsAStatedWarrantyWithItsOwnHeadsUpsAndAnUnstatedOneHasNoDate() {
        val stated = email("m2", "Order confirmed · 12-month warranty", 2_500)
        val (entry, r) = VaultReceiptRules.entry(batch(listOf(stated)), stated, cal, MailWarrantyLevel.HUNDRED)!!
        assertEquals(CivilDate.toEpochDay(2027, 10, 9), entry.expiresDay)
        assertFalse(r.onRenewals)
        val item = DocumentItem("d2", entry.title, entry.kind, entry.expiresDay, 0, receipt = r)
        assertEquals(1, DocumentRules.notices(listOf(item), at(entry.expiresDay!! - 30), cal).size)
        assertTrue(VaultReceiptRules.status(item, entry.expiresDay!! - 20).startsWith("Warranty ends in 2 weeks"))
        val plain = email("m3", "Thanks for your order", null, from = "Tesco")
        val (e3, r3) = VaultReceiptRules.entry(batch(listOf(plain)), plain, cal, MailWarrantyLevel.HUNDRED)!!
        assertNull(e3.expiresDay)
        assertEquals("Receipt · bought Sat 10 Oct 2026 · Personal mail", VaultReceiptRules.rowLine(r3))
        assertEquals("Kept from your mail · no warranty date", VaultReceiptRules.status(DocumentItem("d3", e3.title, e3.kind, null, 0, receipt = r3), day))
        // A promo isn't a receipt.
        val promo = email("m4", "20% off", null, tidy = MailTidy.ARCHIVE)
        assertNull(VaultReceiptRules.entry(batch(listOf(promo)), promo, cal, MailWarrantyLevel.HUNDRED))
        assertNull(VaultReceiptRules.offer(promo, emptyList()))
    }

    @Test
    fun keepingTwiceIsOneDocumentAndTheOfferFollowsIt() {
        val world = SyncWorld()
        var n = 0
        val docs = Documents(world.device("android").replica, { "doc${n++}" }, { at(day) }, cal)
        val e = email("m5", "Your Currys order", 49_900)
        val other = email("m6", "Your Currys order", 9_900)
        val b = batch(listOf(e, other))
        assertEquals(VaultReceiptRules.KEEP, VaultReceiptRules.offer(e, docs.items())!!.action)
        val (entry, r) = VaultReceiptRules.entry(b, e, cal, MailWarrantyLevel.HUNDRED)!!
        val kept = docs.keepReceipt(entry, r)
        assertFalse(kept.refused)
        assertTrue(kept.line.startsWith("Kept “Currys receipt” in the Vault · warranty until"))
        assertTrue(docs.keepReceipt(entry, r).refused)
        // Another Currys receipt is its own document, not merged by title.
        val (e2, r2) = VaultReceiptRules.entry(b, other, cal, MailWarrantyLevel.HUNDRED)!!
        docs.keepReceipt(e2, r2)
        assertEquals(2, docs.items().size)
        val offer = VaultReceiptRules.offer(e, docs.items())!!
        assertTrue(offer.kept)
        assertEquals(VaultReceiptRules.TAKE_OUT, offer.action)
        assertTrue(docs.remove(docs.byEmail(e.key)!!.id))
        assertFalse(VaultReceiptRules.offer(e, docs.items())!!.kept)
        assertNull(VaultReceiptRules.decode("r0\u001fx"))
        assertNull(VaultReceiptRules.decode(null))
        assertEquals(FieldValue.Null, FieldValue.Null)
    }
}
