package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Renewals keep their email (Meka, 2026-10-28), slice 1. */
class MailSourceTest {
    private val received = CivilDate.toEpochDay(2026, 10, 25)
    private val cal = LocalCalendar.UTC

    @Test
    fun theMessageIsKeptOnTheServersRowOnly() {
        val r = MailSourceRules.remoteId("m123", "t9")
        assertEquals("m123", MailSourceRules.messageOf(r))
        assertEquals("t9", MailSourceRules.threadOf(r))
        assertEquals("m123", MailSourceRules.messageOf(MailSourceRules.remoteId("m123", null)))
        assertNull(MailSourceRules.threadOf(MailSourceRules.remoteId("m123", null)))
        // A follow-up's or a calendar's row keeps something else.
        assertNull(MailSourceRules.messageOf("cal/evt"))
        assertNull(MailSourceRules.messageOf(null))
    }

    @Test
    fun anOlderFindIsMatchedBySubjectOnItsDayElseBySender() {
        assertTrue(MailSourceRules.sameEmail("Listly", received, "Your plan renews soon", "Listly", "hi@listly.example", "Your plan renews soon", received))
        assertTrue(MailSourceRules.sameEmail("Listly", received, "Your plan renews soon ", null, null, "your plan renews soon", received))
        assertFalse(MailSourceRules.sameEmail("Listly", received, "Your plan renews soon", "Listly", null, "Your plan renews soon", received + 1))
        assertFalse(MailSourceRules.sameEmail("Listly", received, "Your plan renews soon", "Listly", null, "Welcome", received))
        // No subject kept: the sender decides.
        assertTrue(MailSourceRules.sameEmail("Listly", received, null, "Listly", "hi@listly.example", "anything", received))
        assertFalse(MailSourceRules.sameEmail("Listly", received, null, "Other Co", "x@other.example", "anything", received))
        assertFalse(MailSourceRules.sameEmail(null, received, null, "Listly", null, "anything", received))
    }

    @Test
    fun theEmailsCancelByDayIsReadAndAddFillsIt() {
        val r = MailRenewalRules.read(
            "Streamly", "billing@streamly.example", "Your membership renews soon",
            "Renews on 25 Nov for £3 a month · cancel by 20 Nov to avoid the charge", received,
        )!!
        assertEquals(CivilDate.toEpochDay(2026, 11, 20), r.cancelByDay)
        assertEquals(300L, r.pence)
        // A cancel-by day after the renewal (or before the email) is a misread.
        assertNull(MailRenewalRules.read("S", null, "Renews on 25 Nov", "cancel before 30 Nov", received)!!.cancelByDay)
        assertNull(MailRenewalRules.read("S", null, "Renews on 25 Nov", null, received)!!.cancelByDay)

        val f = MailRenewalRules.read("f1", MailRenewalRules.serverFields(r, "Personal", "Your membership renews soon", received, "\"Streamly Billing\""))!!
        assertEquals("Streamly Billing", f.fromName)
        assertEquals(r.cancelByDay, f.cancelByDay)
        val o = MailRenewalRules.obligationFields(f, cal.toEpochMs(received, 600), cal)
        assertEquals(cal.toEpochMs(CivilDate.toEpochDay(2026, 11, 20), Lists.DATE_MINUTE), o[ObligationFields.CANCEL_BY_AT]?.longOrNull)
        assertEquals(300L, o[ObligationFields.COST_MINOR]?.longOrNull)
        // An older find (no sender, no cancel-by) still reads.
        val old = MailRenewalRules.read("f0", MailRenewalRules.serverFields(r.copy(cancelByDay = null), "Personal", null, received))!!
        assertNull(old.fromName)
        assertNull(MailRenewalRules.obligationFields(old, 0, cal)[ObligationFields.CANCEL_BY_AT])
    }

    @Test
    fun theBoxSaysWhoWhatAndWhenAsTiles() {
        val r = MailRenewal(CivilDate.toEpochDay(2026, 11, 25), 1099, "Streamly", ObligationKind.SUBSCRIPTION, RenewalRepeat.MONTHLY, CivilDate.toEpochDay(2026, 11, 20))
        val f = MailRenewalRules.read("f1", MailRenewalRules.serverFields(r, "Personal", "Your membership renews soon", received, "Streamly"))!!
        val b = MailSourceRules.box(f, received)
        assertEquals("f1", b.handle)
        assertEquals("Streamly", b.from)
        assertEquals("“Your membership renews soon”", b.subject)
        assertEquals("${CivilDate.shortLabel(received)} · Personal mail", b.received)
        assertEquals(listOf("Amount", "Plan", "Renews", "Cancel by"), b.tiles.map { it.label })
        assertEquals("£10.99 a month", b.tiles[0].value)
        assertEquals("Open the email", b.openLabel)
        assertTrue(b.spoken.startsWith("From the email: Streamly."), b.spoken)
        val fb = MailSourceRules.fallback(b, cal, 0)
        assertEquals("Your membership renews soon", fb.subject)
        assertEquals("Personal", fb.account)
        // Nothing read but the day: one tile; no sender name kept: the company.
        val bare = MailRenewalRules.read("f2", MailRenewalRules.serverFields(r.copy(pence = null, repeat = RenewalRepeat.NONE, cancelByDay = null), "Business", null, received))!!
        val b2 = MailSourceRules.box(bare, received)
        assertEquals("Streamly", b2.from)
        assertNull(b2.subject)
        assertEquals(listOf("Renews"), b2.tiles.map { it.label })
    }

    @Test
    fun aRenewalAddedFromAnEmailShowsItsBoxAndOneTypedHasNone() {
        val world = SyncWorld()
        val d = world.device("android")
        var n = 0
        val renewals = Renewals(d.replica, { "R${n++}" }, { world.clock.nowMs })
        val today = world.clock.nowMs.floorDiv(CivilDate.DAY_MS)
        val r = MailRenewal(today + 30, 499, "Listly", ObligationKind.SUBSCRIPTION, RenewalRepeat.YEARLY)
        val findId = "mflist"
        d.replica.commitLocal(EntityTypes.MAIL_FIND, findId, MailRenewalRules.serverFields(r, "Personal", "Your plan renews", today - 4))
        val f = MailRenewalRules.read(findId, d.replica.entity(EntityTypes.MAIL_FIND, findId)!!.fields)!!
        d.replica.commitLocal(EntityTypes.OBLIGATION, MailRenewalRules.obligationId(findId), MailRenewalRules.obligationFields(f, world.clock.nowMs, LocalCalendar.UTC))
        renewals.add("Gym", ObligationKind.SUBSCRIPTION, today + 10, RenewalRepeat.MONTHLY)
        val items = renewals.items().associateBy { it.title }
        val box = assertNotNull(items.getValue("Listly").fromEmail)
        assertEquals(findId, box.handle)
        assertEquals("“Your plan renews”", box.subject)
        assertNull(items.getValue("Gym").fromEmail)

        // A bill the server wrote from an email: its own id is the handle, its title the sender.
        val bill = MailBill(payee = "Octopus Energy", dueDay = today + 5, pence = 8250, directDebit = false)
        d.replica.commitLocal(EntityTypes.OBLIGATION, "mbx", MailBillRules.fields(bill, "Personal", today - 1, world.clock.nowMs, LocalCalendar.UTC))
        val b = assertNotNull(renewals.items().single { it.id == "mbx" }.fromEmail)
        assertEquals("mbx", b.handle)
        assertEquals("Personal", b.account)
        assertEquals(listOf("Amount", "Due"), b.tiles.map { it.label })
        assertEquals("£82.50", b.tiles[0].value)
    }

    @Test
    fun theWholeEmailsRenewalPriceFillsACostNeverWritten() {
        // Slice 3: the price beside the renewal words, not an offer elsewhere in the email.
        assertEquals(2400L, MailRenewalRules.bodyPrice("Save £5 at our shop!\n\nYour plan renews on 3 Nov for £24.00 a year."))
        assertEquals(41230L, MailRenewalRules.bodyPrice("Renewal price: £412.30. Your policy renews on 1 Dec."))
        assertNull(MailRenewalRules.bodyPrice("Save £5 at our shop this week. Thanks for being with us."))
        assertNull(MailRenewalRules.bodyPrice("Your plan renews soon."))
        assertNull(MailRenewalRules.bodyPrice(null))
        // Far past the renewal words: not taken.
        assertNull(MailRenewalRules.bodyPrice("Your plan renews on 3 Nov." + " ".repeat(5) + "x".repeat(300) + " for £9.99"))
        // Only a Cost never written: a cost he set or cleared stands.
        val text = "It renews on 3 Nov for £24.00."
        assertEquals(mapOf(ObligationFields.COST_MINOR to 2400L.fv(), ObligationFields.CURRENCY to "GBP".fv()), MailSourceRules.costFill(emptyMap(), text))
        assertNull(MailSourceRules.costFill(mapOf(ObligationFields.COST_MINOR to os.meka.core.sync.FieldValue.Null), text))
        assertNull(MailSourceRules.costFill(mapOf(ObligationFields.COST_MINOR to 999L.fv()), text))
        assertNull(MailSourceRules.costFill(emptyMap(), "Nothing to see here."))
    }
}
