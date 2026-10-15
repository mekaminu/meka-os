package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailRenewalTest {
    /** Wed 14 Oct 2026. */
    private val received = CivilDate.toEpochDay(2026, 10, 14)
    private val cal = LocalCalendar.UTC

    private fun read(subject: String?, preview: String?, name: String? = "Streamly", address: String? = "info@streamly.example") =
        MailRenewalRules.read(name, address, subject, preview, received)

    @Test
    fun aSubscriptionWithADayAndAPrice() {
        val r = read("Your membership renews soon", "Your Streamly membership renews on 3 Nov for £10.99 a month")!!
        assertEquals(CivilDate.toEpochDay(2026, 11, 3), r.renewDay)
        assertEquals(1099L, r.pence)
        assertEquals("Streamly", r.merchant)
        assertEquals(ObligationKind.SUBSCRIPTION, r.kind)
        assertEquals(RenewalRepeat.MONTHLY, r.repeat)
        val annual = read("Your annual plan will auto-renew on Friday", "Price: £79.99 per year", "Cloud Co", "billing@cloud.example")!!
        assertEquals(CivilDate.toEpochDay(2026, 10, 16), annual.renewDay)
        assertEquals(7999L, annual.pence)
        assertEquals(RenewalRepeat.YEARLY, annual.repeat)
    }

    @Test
    fun aPolicyIsInsuranceAndYearly() {
        val r = read("Your car insurance is due for renewal", "Renewal date: 12/12/2026 · your renewal premium is £412.30", "Safe Drive Insurance", "renewals@safedrive.example")!!
        assertEquals(CivilDate.toEpochDay(2026, 12, 12), r.renewDay)
        assertEquals(41230L, r.pence)
        assertEquals(ObligationKind.INSURANCE, r.kind)
        assertEquals(RenewalRepeat.YEARLY, r.repeat)
    }

    @Test
    fun nothingWhenItIsDoneStoppedOrUnsure() {
        assertNull(read("Your membership has been renewed", "Thanks · next renewal on 3 Nov 2027"))
        assertNull(read("Your subscription was cancelled", "It will not renew on 3 Nov"))
        assertNull(read("Auto-renew is off", "Your plan ends on 3 Nov and won't renew"))
        assertNull(read("Your order has shipped", "Arriving Friday · £20")) // not a renewal
        assertNull(read("Time to renew?", "Have a look at our plans")) // no day
        assertNull(read("Your plan renews on 1 Oct", null)) // before the email
        assertNull(read("Your policy renews on 14/06/2027", null)) // too far ahead
    }

    @Test
    fun theCardShowsOpenFindsNotOnTheRadarSoonestFirst() {
        val a = read("Your membership renews soon", "Renews on 3 Nov for £10.99 a month")!!
        val b = read("Your car insurance is due for renewal", "Renewal date: 28/10/2026", "Safe Drive Insurance", "x@safedrive.example")!!
        val fa = MailRenewalRules.read("fa", MailRenewalRules.serverFields(a, "Personal", "Your membership renews soon", received))!!
        val fb = MailRenewalRules.read("fb", MailRenewalRules.serverFields(b, "Business", null, received))!!
        val now = cal.toEpochMs(received, 600)
        val card = MailRenewalRules.card(listOf(fa, fb), emptyList(), now, cal)!!
        assertEquals("Renewals from your mail", card.title)
        assertEquals("2 to add to Renewals · nothing goes on until you tap Add", card.headline)
        assertEquals(listOf("fb", "fa"), card.rows.map { it.id })
        assertEquals("Safe Drive Insurance", card.rows[0].title)
        assertTrue(card.rows[0].business)
        assertEquals("Renews Tue 3 Nov · £10.99 a month · Subscription", card.rows[1].line)
        assertEquals("Personal mail · “Your membership renews soon”", card.rows[1].from)
        // Something he typed for that company near that day: not offered.
        val typed = mapOf(
            ActionableFields.TITLE to "Streamly".fv(),
            ActionableFields.DUE_AT to cal.toEpochMs(a.renewDay + 2, 540).fv(),
            ActionableFields.LIFECYCLE to Lifecycle.ACTIVE.name.fv(),
        )
        assertEquals(listOf("fb"), MailRenewalRules.card(listOf(fa, fb), listOf(typed), now, cal)!!.rows.map { it.id })
        // Answered or past: gone.
        val added = fa.copy(state = MailFindState.ADDED)
        val dismissed = fb.copy(state = MailFindState.DISMISSED)
        assertNull(MailRenewalRules.card(listOf(added, dismissed), emptyList(), now, cal))
        assertNull(MailRenewalRules.card(listOf(fa), emptyList(), cal.toEpochMs(a.renewDay + 1, 600), cal))
    }

    @Test
    fun addWritesTheReminderAndTheIdsAreStable() {
        val r = read("Your membership renews soon", "Renews on 3 Nov for £10.99 a month")!!
        val id = MailRenewalRules.findId("acc1", "m1", r)
        assertEquals(id, MailRenewalRules.findId("acc1", "m2", r)) // the reminder email lands on the same find
        assertNotEquals(id, MailRenewalRules.findId("acc2", "m1", r))
        val f = MailRenewalRules.read(id, MailRenewalRules.serverFields(r, "Personal", null, received))!!
        val o = MailRenewalRules.obligationFields(f, 1L, cal)
        assertEquals("Streamly", o[ActionableFields.TITLE]?.textOrNull)
        assertEquals(ObligationKind.SUBSCRIPTION.name, o[ObligationFields.KIND]?.textOrNull)
        assertEquals(1099L, o[ObligationFields.COST_MINOR]?.longOrNull)
        assertEquals(cal.toEpochMs(r.renewDay, Lists.DATE_MINUTE), o[ActionableFields.DUE_AT]?.longOrNull)
        assertNotNull(o[ObligationFields.RECURRENCE]?.textOrNull)
        assertEquals(Trust.UNTRUSTED_EXTERNAL.name, o[ActionableFields.PROVENANCE_TRUST]?.textOrNull)
        assertTrue(MailRenewalRules.alreadyOnRadar("Streamly", r.renewDay, listOf(o), cal))
        assertEquals("On Renewals: Streamly · renews Tue 3 Nov", MailRenewalRules.addedLine(f, received))
        val stated = MailRenewalRules.read(id, MailRenewalRules.serverFields(r, "Personal", null, received) + MailRenewalRules.stateFields(MailFindState.DISMISSED, 5L))!!
        assertEquals(MailFindState.DISMISSED, stated.state)
        assertNull(MailRenewalRules.read("x", mapOf<String, FieldValue>()))
    }
}
