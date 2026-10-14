package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailWarrantyTest {
    /** Wed 14 Oct 2026. */
    private val received = CivilDate.toEpochDay(2026, 10, 14)
    private fun receipt(pence: Long?, merchant: String? = "Shop") = MailReceipt(pence, merchant)

    @Test
    fun theStatedLengthIsRead() {
        assertEquals(24, MailWarrantyRules.statedMonths("Your order", "Includes a 2-year warranty"))
        assertEquals(24, MailWarrantyRules.statedMonths("Your order", "2 year manufacturer's warranty included"))
        assertEquals(12, MailWarrantyRules.statedMonths("12-month guarantee", null))
        assertEquals(60, MailWarrantyRules.statedMonths("Order", "Guaranteed for 5 years"))
        assertEquals(36, MailWarrantyRules.statedMonths("Order", "Warranty: three years"))
        assertNull(MailWarrantyRules.statedMonths("Order", "Add a 3-year warranty for just £29"))
        assertNull(MailWarrantyRules.statedMonths("Order", "Protect it with an extended 3 year warranty"))
        assertNull(MailWarrantyRules.statedMonths("Order", "Arriving Friday"))
        assertNull(MailWarrantyRules.statedMonths("Pan", "200 year guarantee")) // too long to be real
    }

    @Test
    fun aboveTheThresholdAWarrantyEndsAYearOnUnlessItSays() {
        val year = MailWarrantyRules.read(receipt(45_000), "Your order has shipped", "Total £450.00", received, MailWarrantyLevel.HUNDRED)!!
        assertEquals(CivilDate.toEpochDay(2027, 10, 13), year.endDay)
        assertEquals(false, year.stated)
        val two = MailWarrantyRules.read(receipt(45_000), "Your order", "Total £450.00 · 2-year warranty", received, MailWarrantyLevel.HUNDRED)!!
        assertEquals(CivilDate.toEpochDay(2028, 10, 13), two.endDay)
        assertTrue(two.stated)
    }

    @Test
    fun nothingUnderTheThresholdOffUnpricedOrNotGoods() {
        assertNull(MailWarrantyRules.read(receipt(9_999), "Order", "Total £99.99", received, MailWarrantyLevel.HUNDRED))
        assertEquals(10_000, MailWarrantyRules.read(receipt(10_000), "Order", "Total £100.00", received, MailWarrantyLevel.HUNDRED)!!.pence)
        assertNull(MailWarrantyRules.read(receipt(45_000), "Order", "Total £450", received, MailWarrantyLevel.OFF))
        assertNull(MailWarrantyRules.read(receipt(null), "Your refund", "We've refunded £450", received, MailWarrantyLevel.FIFTY))
        assertNull(MailWarrantyRules.read(receipt(12_000, "Trainline"), "Your tickets", "Total £120.00", received, MailWarrantyLevel.HUNDRED))
        assertNull(MailWarrantyRules.read(receipt(30_000), "Hotel booking confirmed", "Total £300", received, MailWarrantyLevel.HUNDRED))
        // A ticket that names a warranty is still read (rare, but the words win).
        assertTrue(MailWarrantyRules.read(receipt(30_000), "Event speaker order", "2 year warranty", received, MailWarrantyLevel.HUNDRED) != null)
    }

    @Test
    fun monthsCarryOverTheYearAndShortMonths() {
        assertEquals(CivilDate.toEpochDay(2027, 2, 28), MailWarrantyRules.plusMonths(CivilDate.toEpochDay(2026, 8, 31), 6))
        assertEquals(CivilDate.toEpochDay(2028, 2, 29), MailWarrantyRules.plusMonths(CivilDate.toEpochDay(2026, 2, 28), 24).let { it + 1 })
    }

    @Test
    fun theSettingDefaultsToAHundredAndReadsBack() {
        assertEquals(MailWarrantyLevel.HUNDRED, MailWarrantyRules.read(null))
        MailWarrantyLevel.entries.forEach { l ->
            assertEquals(l, MailWarrantyRules.read(MailWarrantyRules.settingFields(l, 1)))
        }
        assertEquals(MailWarrantyLevel.TWO_FIFTY, MailWarrantyLevel.ofPence(30_000))
        assertTrue(MailWarrantyRules.settingLine(MailWarrantyLevel.OFF).startsWith("Off"))
        assertEquals("Orders of £250 or more · Renewals reminds you a month before the warranty ends", MailWarrantyRules.settingLine(MailWarrantyLevel.TWO_FIFTY))
    }

    @Test
    fun theReminderIsAWarrantyOnTheRadarAndTheLineSaysSo() {
        val w = MailWarrantyRules.read(receipt(45_000, "Currys"), "Order", "Total £450 · 2 year guarantee", received, MailWarrantyLevel.HUNDRED)!!
        val f = MailWarrantyRules.fields(w, "Personal", received, 5, LocalCalendar.fixedOffset(0))
        assertEquals(FieldValue.Text("Warranty ends · Currys order"), f[ActionableFields.TITLE])
        assertEquals(FieldValue.Text(ObligationKind.WARRANTY.name), f[ObligationFields.KIND])
        assertEquals(FieldValue.Int64(45_000), f[ObligationFields.COST_MINOR])
        assertTrue((f[ActionableFields.NOTES] as FieldValue.Text).value.contains("the email says 2 years"))
        assertEquals("Warranty ends · your order", MailWarrantyRules.title(null))
        val (_, detail) = MailAutopilotRules.lines(mapOf(MailTidy.RECEIPT to 1), "Personal", listOf(receipt(45_000, "Currys")), returns = 1, warranties = 1)
        assertTrue(detail.endsWith(" · 1 return date → Renewals · 1 warranty → Renewals"), detail)
        assertEquals(" · 2 warranties → Renewals", MailWarrantyRules.suffix(2))
    }
}
