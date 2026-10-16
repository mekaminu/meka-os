package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailPromiseTest {
    /** Wed 14 Oct 2026. */
    private val received = CivilDate.toEpochDay(2026, 10, 14)
    private val cal = LocalCalendar.UTC

    private fun read(subject: String?, preview: String?, name: String? = "Sam Taylor", address: String? = "sam@example.com", automated: Boolean = false) =
        MailPromiseRules.read(name, address, subject, preview, received, automated)

    @Test
    fun aPersonSaysTheyWillSendSomethingByADay() {
        val p = read("Re: contract", "Thanks Alex. I'll send you the signed contract by Friday. Speak soon")!!
        assertEquals("Sam Taylor", p.who)
        assertEquals("Signed contract", p.what)
        assertEquals(CivilDate.toEpochDay(2026, 10, 16), p.byDay)
        val dated = read("Quote", "We will send over the quote on 21/10 as agreed")!!
        assertEquals("Quote", dated.what)
        assertEquals(CivilDate.toEpochDay(2026, 10, 21), dated.byDay)
        val tomorrow = read("Photos", "I’ll forward the photos tomorrow")!!
        assertEquals("Photos", tomorrow.what)
        assertEquals(received + 1, tomorrow.byDay)
    }

    @Test
    fun repliesCallsAndWorkingDays() {
        val r = read("Your enquiry", "We will get back to you within 5 working days.", "Oakfield Council", "enquiries@council.example")!!
        assertEquals("A reply", r.what)
        assertEquals(CivilDate.toEpochDay(2026, 10, 21), r.byDay) // Wed + 5 working days = Wed 21 Oct
        val call = read("Boiler", "I'll call you next week to book it in", "Plumber Pete", null)!!
        assertEquals("A call", call.what)
        assertEquals(CivilDate.toEpochDay(2026, 10, 23), call.byDay) // Friday of next week
        val noDay = read("Re: invoice", "Thanks, I'll let you know")!!
        assertEquals("An answer", noDay.what)
        assertNull(noDay.byDay)
        assertEquals(MailPromiseRules.addWorkingDays(CivilDate.toEpochDay(2026, 10, 16), 1), CivilDate.toEpochDay(2026, 10, 19))
    }

    @Test
    fun refundsFromNoReplyButNothingElseAutomated() {
        val refund = read("Your return", "You will receive your refund within 3 days", "Shopco", "noreply@shopco.example", automated = true)!!
        assertEquals("Refund", refund.what)
        assertEquals(received + 3, refund.byDay)
        assertNull(read("Order update", "We'll send the parcel tomorrow", "Shopco", "noreply@shopco.example", automated = true))
    }

    @Test
    fun nothingWhenItIsntAPromise() {
        assertNull(read("Re: contract", "Sorry, I won't send the contract until Monday"))
        assertNull(read("Re: contract", "Can you send the contract by Friday?")) // he's asked, not them
        assertNull(read("Order placed", "We'll send you an email when it ships"))
        assertNull(read("Sign in", "We'll send your verification code by text"))
        assertNull(read("Lunch?", "Fancy lunch on Friday?"))
        // "it" takes the subject's topic.
        assertEquals("Plans for Saturday", read("Re: Plans for Saturday", "I'll send it over tomorrow")!!.what)
    }

    @Test
    fun theCardShowsPromisesWithRenewalsAndAddWritesWaitingFor() {
        val p = read("Re: contract", "I'll send you the signed contract by Friday")!!
        val id = MailPromiseRules.findId("acc1", "m1")
        assertNotEquals(id, MailPromiseRules.findId("acc1", "m2"))
        val f = MailRenewalRules.read(id, MailPromiseRules.serverFields(p, "Business", "Re: contract", received))!!
        assertEquals(MailPromiseRules.KIND_PROMISE, f.kind)
        assertTrue(f.byStated)
        val now = cal.toEpochMs(received, 600)
        val card = MailRenewalRules.card(listOf(f), emptyList(), now, cal)!!
        assertEquals("Promises from your mail", card.title)
        assertEquals("1 to add to Waiting for · nothing goes on until you tap Add", card.headline)
        val row = card.rows.single()
        assertEquals("Sam Taylor", row.title)
        assertEquals("Waiting for: Signed contract · by Fri 16 Oct", row.line)
        assertEquals("Business mail · “Re: contract”", row.from)
        assertTrue(row.business)
        assertEquals("Add Sam Taylor's promise to Waiting for", row.addSpoken)
        // With a renewal: both, soonest first.
        val r = MailRenewalRules.read("Your membership renews soon", null, "Streamly renews on 3 Nov", null, received)!!
        val fr = MailRenewalRules.read("fr", MailRenewalRules.serverFields(r, "Personal", null, received))!!
        val both = MailRenewalRules.card(listOf(fr, f), emptyList(), now, cal)!!
        assertEquals("Renewals and promises from your mail", both.title)
        assertEquals("1 to add to Renewals · 1 to Waiting for · nothing goes on until you tap Add", both.headline)
        assertEquals(listOf(id, "fr"), both.rows.map { it.id })
        // Chased the day after the day they gave.
        assertEquals(3, MailPromiseRules.chaseInDays(f, received))
        assertEquals("On Waiting for: Signed contract from Sam Taylor · chase Sat 17 Oct", MailPromiseRules.addedLine(f, received))
        // Already on Waiting for (Add's own item): gone; a week past its day: gone.
        val w = WaitingItem(MailPromiseRules.waitingId(id), "Signed contract", "Sam Taylor", null, now, null, null, DueState.NONE, "", false)
        assertFalse(MailPromiseRules.showable(f, listOf(w), received))
        assertFalse(MailPromiseRules.showable(f, emptyList(), f.renewDay + MailPromiseRules.STALE_DAYS + 1))
        // No day given: the default chase day, said so.
        val loose = read("Re: invoice", "I'll let you know")!!
        val fl = MailRenewalRules.read("fl", MailPromiseRules.serverFields(loose, "Personal", null, received))!!
        assertEquals("Waiting for: An answer · no day given · chase Sat 17 Oct", MailPromiseRules.line(fl, received))
    }

    @Test
    fun aParcelOnItsWayIsNeverAPromise() {
        // 2026-10-16: "John Lewis · Waiting for: Again" from a DPD update; a parcel is a Parcels item.
        assertNull(read("Your parcel is with DPD", "We'll deliver again tomorrow between 10:00 and 11:00", "John Lewis", "orders@johnlewis.example"))
        assertNull(read("Your order has been dispatched", "We will send tracking details shortly", "Shopco", "hello@shopco.example"))
        assertTrue(ParcelRules.isParcelMail("hello@shopco.example", "Your parcel is with DPD", null))
        assertTrue(!ParcelRules.isParcelMail("sam@example.com", "Re: contract", "I'll send you the signed contract by Friday"))
        // A refund promised in a parcel's email still counts.
        assertEquals("Refund", read("Your return parcel arrived", "You will receive your refund within 5 days", "Shopco", "hello@shopco.example")!!.what)
        // A stray adverb is never the thing awaited.
        assertEquals("A reply", read("Re: plans", "Sorry, I'll reply again tomorrow")!!.what)
        assertEquals("Plans", read("Re: plans", "I'll send again tomorrow")!!.what)
    }
}
