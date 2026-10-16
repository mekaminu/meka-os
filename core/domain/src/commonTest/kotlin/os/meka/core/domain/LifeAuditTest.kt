package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LifeAuditTest {
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    /** Fri 16 Oct 2026. */
    private val today = d(10, 16)
    private val cal = LocalCalendar.UTC

    private fun pay(subject: String, preview: String?, day: Long, name: String = "Streamco") =
        LifeAuditRules.payment(name, "billing@streamco.example", subject, preview, day)

    @Test
    fun aPaymentNeedsAnAmountACompanyAndNoStop() {
        val p = pay("Your receipt from Streamco", "Total: £10.99 · thanks for being a member", d(10, 8))!!
        assertEquals(1099L, p.pence)
        assertEquals("Streamco", p.merchant)
        assertTrue(p.planWords)
        assertNull(pay("Your receipt from Streamco", "Thanks for watching", d(10, 8))) // no amount
        assertNull(pay("Your refund", "We've refunded £10.99", d(10, 8)))
        assertNull(pay("Payment failed", "We couldn't take £10.99", d(10, 8)))
        assertNull(pay("New films this week", "From £3.99", d(10, 8))) // not a payment
        assertNull(pay("Your membership renews soon", "It renews on 3 Nov for £10.99 a month", d(10, 8))) // a renewal notice
        assertTrue(LifeAuditRules.stopped("Your membership was cancelled", null))
    }

    @Test
    fun twoPaymentsAMonthApartAreMonthly() {
        val ps = listOfNotNull(
            pay("Your receipt from Streamco", "Total: £10.99", d(9, 8)),
            pay("Your receipt from Streamco", "Total: £10.99", d(10, 8)),
        )
        val s = LifeAuditRules.subscriptions(ps, today).single()
        assertEquals(RenewalRepeat.MONTHLY, s.repeat)
        assertEquals(d(11, 8), s.nextDay)
        assertEquals(2, s.seen)
        assertEquals(1099L, s.pence)
    }

    @Test
    fun oneMonthlyOrYearlyPlanPaymentCounts() {
        val gym = pay("Your monthly membership payment", "Payment of £24.00 received", d(10, 1), "Fit Gym")!!
        val cloud = pay("Your annual plan has renewed", "Charged £79.99 per year", d(9, 20), "Cloud Co")!!
        val subs = LifeAuditRules.subscriptions(listOf(gym, cloud), today)
        assertEquals(listOf("Cloud Co", "Fit Gym"), subs.map { it.merchant })
        assertEquals(RenewalRepeat.YEARLY, subs[0].repeat)
        assertEquals(d(9, 20) + 365, subs[0].nextDay)
        assertEquals(d(11, 1), subs[1].nextDay)
        assertEquals(667L, LifeAuditRules.monthlyPence(7999, RenewalRepeat.YEARLY))
    }

    @Test
    fun ordersStopsAndLapsedOnesAreLeftOut() {
        // A shop's orders at different prices, or one plain receipt: not a subscription.
        val orders = listOfNotNull(
            pay("Your order confirmation", "Order total: £32.10", d(9, 1), "Shopco"),
            pay("Your order confirmation", "Order total: £8.50", d(10, 1), "Shopco"),
            pay("Your receipt", "Total: £4.20", d(10, 2), "Cafe"),
        )
        assertTrue(LifeAuditRules.subscriptions(orders, today).isEmpty())
        val monthly = listOfNotNull(pay("Your receipt from Streamco", "Total: £10.99", d(8, 20)), pay("Your receipt from Streamco", "Total: £10.99", d(9, 20)))
        // Due 20 Oct: a cancellation after the last payment stops it.
        assertTrue(LifeAuditRules.subscriptions(monthly, today, mapOf("streamco" to d(9, 25))).isEmpty())
        assertEquals(1, LifeAuditRules.subscriptions(monthly, today, mapOf("streamco" to d(8, 1))).size)
        // Lapsed: due 20 Sep, nothing since.
        val lapsed = listOfNotNull(pay("Your receipt from Streamco", "Total: £10.99", d(7, 20)), pay("Your receipt from Streamco", "Total: £10.99", d(8, 20)))
        assertTrue(LifeAuditRules.subscriptions(lapsed, today).isEmpty())
        // A few days late rolls on to next month.
        val late = listOfNotNull(pay("Your receipt from Streamco", "Total: £10.99", d(8, 12)), pay("Your receipt from Streamco", "Total: £10.99", d(9, 12)))
        assertEquals(d(11, 12), LifeAuditRules.subscriptions(late, today).single().nextDay)
    }

    @Test
    fun theCardAddsUpTheMonthAndSkipsWhatIsKnown() {
        val ps = listOfNotNull(
            pay("Your receipt from Streamco", "Total: £10.99", d(9, 8)),
            pay("Your receipt from Streamco", "Total: £10.99", d(10, 8)),
            pay("Your annual plan has renewed", "Charged £79.99 per year", d(9, 20), "Cloud Co"),
        )
        val finds = LifeAuditRules.subscriptions(ps, today).map { s ->
            MailRenewalRules.read(LifeAuditRules.findId("acc", s.merchant), LifeAuditRules.serverFields(s, "Personal"))!!
        }
        assertEquals(2, finds.first { it.merchant == "Streamco" }.seen)
        val now = cal.toEpochMs(today, 600)
        val card = assertNotNull(MailRenewalRules.card(finds, emptyList(), now, cal))
        assertEquals(LifeAuditRules.TITLE, card.title)
        assertEquals("2 subscriptions · about £17.66 a month · nothing goes on Renewals until you tap Add", card.headline)
        val row = card.rows.first { it.title == "Streamco" }
        assertEquals("£10.99 a month · paid twice in 60 days · next about Sun 8 Nov", row.line)
        assertEquals("On Renewals: Streamco · next payment about Sun 8 Nov", MailRenewalRules.addedLine(finds.first { it.merchant == "Streamco" }, today))
        // On the radar already, or a renewal find of its own: not offered twice.
        val radar = listOf(MailRenewalRules.obligationFields(finds.first { it.merchant == "Streamco" }, now, cal))
        assertEquals(listOf("Cloud Co"), MailRenewalRules.card(finds, radar, now, cal)!!.rows.map { it.title })
        val renewal = MailRenewalRules.read("Cloud Co", null, "Your plan renews soon", "Renews on 20/09/2027", today)
        assertNull(renewal) // too far ahead: no renewal find, so the subscription stands
        val rf = MailFind("r", MailRenewalRules.KIND_RENEWAL, "Personal", "Cloud Co", d(11, 1), null, ObligationKind.SUBSCRIPTION,
            RenewalRepeat.YEARLY, today, null, MailFindState.OPEN, 0)
        assertEquals(listOf("Renews Sun 1 Nov · Subscription"), MailRenewalRules.card(finds + rf, radar, now, cal)!!.rows.map { it.line })
        // Not needed: gone.
        val dismissed = finds.map { it.copy(state = MailFindState.DISMISSED) }
        assertNull(MailRenewalRules.card(dismissed, emptyList(), now, cal))
        // Add writes a monthly Subscription due on the next payment day, with the audit's note.
        val f = MailRenewalRules.obligationFields(finds.first { it.merchant == "Streamco" }, now, cal)
        assertTrue(f[ActionableFields.NOTES]!!.textOrNull!!.startsWith("Found by the life audit in your Personal mail (2 payments in 60 days"))
        assertEquals(1099L, f[ObligationFields.COST_MINOR]!!.longOrNull)
    }
}
