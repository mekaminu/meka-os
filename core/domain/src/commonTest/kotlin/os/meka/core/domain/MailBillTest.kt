package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailBillTest {
    /** Wed 14 Oct 2026. */
    private val received = CivilDate.toEpochDay(2026, 10, 14)

    private fun read(subject: String?, preview: String?, name: String? = "Octopus Energy", address: String? = "hello@octopus.example") =
        MailBillRules.read(name, address, subject, preview, received)

    @Test
    fun aBillWithADueDayAndAnAmount() {
        val b = read("Your Octopus Energy bill is ready", "Your bill is £84.20 · payment due 28 Oct")!!
        assertEquals(CivilDate.toEpochDay(2026, 10, 28), b.dueDay)
        assertEquals(8420L, b.pence)
        assertEquals("Octopus Energy", b.payee)
        assertFalse(b.directDebit)
        val card = read("Your statement is ready", "Minimum payment of £25.00 due by 03/11/2026 · statement balance £412.06", "Card Co", "statements@cardco.example")!!
        assertEquals(CivilDate.toEpochDay(2026, 11, 3), card.dueDay)
        assertEquals(2500L, card.pence)
        val invoice = read("Invoice 5521", "Amount due: £120 · pay by Friday", "Acme Plumbing", "accounts@acme.example")!!
        assertEquals(CivilDate.toEpochDay(2026, 10, 16), invoice.dueDay)
        assertEquals(12000L, invoice.pence)
    }

    @Test
    fun aDirectDebitIsMarkedAndItsDayRead() {
        val dd = read("Your Direct Debit is changing", "We'll collect £45.00 by Direct Debit · it will be collected on or after 1 November", "Water Co", "billing@water.example")!!
        assertTrue(dd.directDebit)
        assertEquals(CivilDate.toEpochDay(2026, 11, 1), dd.dueDay)
        assertEquals(4500L, dd.pence)
        assertEquals("Water Co direct debit", MailBillRules.title(dd))
        assertTrue(MailBillRules.isDirectDebit(MailBillRules.title(dd)))
    }

    @Test
    fun nothingWhenItIsUnsure() {
        assertNull(read("Thank you for your payment", "Payment received · £84.20 · bill due 28 Oct"))
        assertNull(read("Your refund", "We've refunded £20 from your bill due 28 Oct"))
        assertNull(read("Your order has shipped", "Arriving Friday · £20")) // not a bill
        assertNull(read("Your bill is ready", "View it in the app")) // no due day
        assertNull(read("Your bill", "Payment due 1 Oct 2025")) // before the email
        assertNull(read("Your bill", "Payment due 14/06/2027")) // too far
        assertNull(read(null, null))
    }

    @Test
    fun oneReminderPerPayeeAndDayOnTheRadar() {
        val b = read("Your bill is ready", "£84.20 due 28 Oct")!!
        // The reminder email for the same bill lands on the same id; another day is another bill.
        assertEquals(MailBillRules.obligationId("a", "m1", b), MailBillRules.obligationId("a", "m2", b))
        assertNotEquals(MailBillRules.obligationId("a", "m1", b), MailBillRules.obligationId("a", "m1", b.copy(dueDay = b.dueDay + 30)))
        val f = MailBillRules.fields(b, "Personal", received, 1L, LocalCalendar.UTC)
        assertEquals(FieldValue.Text("Octopus Energy bill"), f[ActionableFields.TITLE])
        assertEquals(FieldValue.Text(ObligationKind.BILL.name), f[ObligationFields.KIND])
        assertEquals(FieldValue.Int64(LocalCalendar.UTC.toEpochMs(b.dueDay, Lists.DATE_MINUTE)), f[ActionableFields.DUE_AT])
        assertEquals(FieldValue.Int64(8420), f[ObligationFields.COST_MINOR])
        assertEquals(FieldValue.Null, f[ObligationFields.RECURRENCE])
        assertNull(f[ObligationFields.PAY_LINK]) // never a link from an email
        assertEquals(FieldValue.Text(Trust.UNTRUSTED_EXTERNAL.name), f[ActionableFields.PROVENANCE_TRUST])
        val notes = (f[ActionableFields.NOTES] as FieldValue.Text).value
        assertTrue(notes.contains("Personal mail on Wed 14 Oct") && notes.contains("Navilife never pays"), notes)
        assertEquals("A bill from your mail", MailBillRules.title(b.copy(payee = null)))
    }

    @Test
    fun aBillHeAlreadyTracksIsLeftToIt() {
        val b = read("Your bill is ready", "£84.20 due 28 Oct")!!
        val cal = LocalCalendar.UTC
        fun ob(title: String, kind: ObligationKind, day: Long, life: Lifecycle = Lifecycle.ACTIVE) = mapOf(
            ActionableFields.TITLE to title.fv(), ObligationFields.KIND to kind.name.fv(),
            ActionableFields.DUE_AT to cal.toEpochMs(day, Lists.DATE_MINUTE).fv(), ActionableFields.LIFECYCLE to life.name.fv(),
        )
        assertTrue(MailBillRules.alreadyOnRadar(b, listOf(ob("Octopus energy", ObligationKind.BILL, b.dueDay + 2)), cal))
        assertFalse(MailBillRules.alreadyOnRadar(b, listOf(ob("Octopus energy", ObligationKind.BILL, b.dueDay + 20)), cal))
        assertFalse(MailBillRules.alreadyOnRadar(b, listOf(ob("Octopus energy", ObligationKind.INSURANCE, b.dueDay)), cal))
        assertFalse(MailBillRules.alreadyOnRadar(b, listOf(ob("Octopus energy", ObligationKind.BILL, b.dueDay, Lifecycle.DONE)), cal))
        assertFalse(MailBillRules.alreadyOnRadar(b, listOf(ob("Water", ObligationKind.BILL, b.dueDay)), cal))
    }

    @Test
    fun theHeadsUpSaysADirectDebitHasNothingToPay() {
        val world = SyncWorld()
        world.clock.nowMs = received * CivilDate.DAY_MS + 10 * 3_600_000L
        var n = 0
        val r = Renewals(world.device("android").replica, { "B${n++}" }, { world.clock.nowMs })
        r.add("Water Co direct debit", ObligationKind.BILL, received + 3, cost = "45")
        val notice = BillRules.notices(r.items(), world.clock.nowMs, LocalCalendar.UTC).single()
        assertEquals("Water Co direct debit due Sat 17 Oct · £45", notice.title)
        assertEquals("In 3 days · taken by Direct Debit · nothing to pay", notice.text)
        assertTrue(notice.actions.isEmpty())
    }
}
