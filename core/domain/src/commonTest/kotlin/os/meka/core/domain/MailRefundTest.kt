package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Returns and warranties, completeness slice 2: a refund email closes the refund row on Waiting for, on his tap. */
class MailRefundTest {
    private val cal = LocalCalendar.UTC
    private val today = CivilDate.toEpochDay(2026, 10, 16)

    private fun read(subject: String?, preview: String? = null, name: String? = "Amazon.co.uk", address: String? = "no-reply@amazon.co.uk") =
        MailRefundRules.read(name, address, subject, preview)

    private fun wait(id: String, title: String, who: String?, sinceDay: Long = today - 5) = WaitingItem(
        id, title, who, null, cal.toEpochMs(sinceDay, 9 * 60), null, null, DueState.NONE, "", false,
    )

    private fun find(id: String = "mf1", merchant: String? = "Amazon", pence: Long? = 4000, day: Long = today - 1, state: MailFindState = MailFindState.OPEN) =
        MailFind(
            id = id, kind = MailRefundRules.KIND_REFUND, account = "Personal", merchant = merchant, renewDay = day, pence = pence,
            obligationKind = ObligationKind.OTHER, repeat = RenewalRepeat.NONE, receivedDay = day, subject = "Your refund", state = state,
            decidedAtMs = 0, what = null, byStated = false, answeredAtMs = null, answeredWhy = null, chase = ChaseMarks(),
        )

    @Test
    fun readsRefundsThatHappenedNotOnesAskedForOrToCome() {
        val r = read("Your refund of £40.00 has been processed")!!
        assertEquals("Amazon", r.merchant)
        assertEquals(4000L, r.pence)
        assertNotNull(read("Refund confirmation", "We've refunded £12.99 to your Visa"))
        assertNotNull(read("Your order", "We have issued a refund for your return"))
        assertNotNull(read("Refunded: Blue jumper", "£25 has been refunded to your original payment method"))
        assertNull(read("We've received your refund request"))
        assertNull(read("Your return", "Once we receive it, your refund will be processed within 5 days"))
        assertNull(read("Your refund is being processed"))
        assertNull(read("Refund declined", "Your refund was declined"))
        assertNull(read("Your order has shipped"))
        assertNull(read(null, null))
        // Two different amounts: no price, still a refund.
        assertNull(read("Refund issued", "£5 of your £40 order has been refunded")!!.pence)
    }

    @Test
    fun closesTheSameShopsRefundRowTheSameAmountFirst() {
        val other = wait("w1", "Refund · Next order · £30", "Next")
        val older = wait("w2", "Refund · Amazon order · £12", "Amazon", today - 9)
        val same = wait("w3", "Refund · Amazon order · £40", "Amazon", today - 3)
        assertEquals("w3", MailRefundRules.match(find(), listOf(other, older, same), cal)?.id)
        // No amount read: the oldest.
        assertEquals("w2", MailRefundRules.match(find(pence = null), listOf(other, older, same), cal)?.id)
        // A promise's "Refund" from them closes too; "Amazon UK" is Amazon.
        assertEquals("p", MailRefundRules.match(find(merchant = "Amazon UK"), listOf(wait("p", "Refund", "Amazon")), cal)?.id)
        // Nothing from that shop, or not a refund row: nothing to close, nothing shows.
        assertNull(MailRefundRules.match(find(), listOf(other, wait("x", "Signed contract", "Amazon")), cal))
        assertTrue(!MailRefundRules.showable(find(), listOf(other), today, cal))
        // A row made more than a week after the email isn't closed by it.
        assertNull(MailRefundRules.match(find(day = today - 20), listOf(wait("late", "Refund · Amazon order", "Amazon", today - 2)), cal))
        // Answered: not shown again.
        assertTrue(!MailRefundRules.showable(find(state = MailFindState.DISMISSED), listOf(same), today, cal))
    }

    @Test
    fun theCardSaysWhatItClosesWithItsOwnPills() {
        val same = wait("w3", "Refund · Amazon order · £40", "Amazon")
        val card = MailRenewalRules.card(listOf(find()), emptyList(), cal.toEpochMs(today, 600), cal, listOf(same))!!
        assertEquals(MailRefundRules.TITLE, card.title)
        assertEquals("1 refund came · nothing closes until you tap Close it", card.headline)
        val row = card.rows.single()
        assertEquals("Refund from Amazon", row.title)
        assertEquals("Refunded £40 yesterday".replace("yesterday", CivilDate.shortLabel(today - 1)) + " · closes “Refund · Amazon order · £40” on Waiting for", row.line)
        assertEquals("Close it", row.addLabel)
        assertEquals("Not this one", row.notNeededLabel)
        assertEquals("Closed Refund · Amazon order · £40 · Amazon refunded it ${CivilDate.shortLabel(today - 1)}", MailRefundRules.closedLine(find(), same, today))
        // No open refund row: no card.
        assertNull(MailRenewalRules.card(listOf(find()), emptyList(), cal.toEpochMs(today, 600), cal, emptyList()))
    }

    @Test
    fun aRefundPromiseInTheSameEmailIsCovered() {
        val promise = find(id = "mp").copy(kind = MailPromiseRules.KIND_PROMISE, what = "Refund", renewDay = today + 3)
        val same = wait("w3", "Refund · Amazon order · £40", "Amazon")
        val card = MailRenewalRules.card(listOf(find(), promise), emptyList(), cal.toEpochMs(today, 600), cal, listOf(same))!!
        assertEquals(listOf("mf1"), card.rows.map { it.id })
        // With no refund row open, the promise shows as before.
        val alone = MailRenewalRules.card(listOf(find(), promise), emptyList(), cal.toEpochMs(today, 600), cal, emptyList())!!
        assertEquals(listOf("mp"), alone.rows.map { it.id })
        assertEquals("Add", alone.rows.single().addLabel)
    }
}
