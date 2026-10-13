package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox autopilot, slice 6c: emails linked by thread, order number, or person and topic share one row. */
class MailLinksTest {
    private val cal = LocalCalendar.UTC
    private val now = 1_792_225_800_000L // Sat 17 Oct 2026, 08:30 UTC
    private val hour = 3_600_000L

    private fun item(
        id: String, agoMs: Long, from: String = "Sam Taylor", subject: String = "Subject $id", preview: String? = "Hello there",
        account: String = "Personal", thread: String? = null, extra: Map<String, FieldValue> = emptyMap(),
    ) = MailNeedsRules.read(
        id, MailNeedsRules.serverFields(account, from, null, subject, preview, now - agoMs, 1, threadKey = thread?.let { MailNeedsRules.threadKey("acc", it) }) + extra,
    )!!

    @Test
    fun orderNumbersAreFoundAfterTheirWordsAndNotDates() {
        assertEquals("112-4455", MailLinkRules.orderRef("Your order 112-4455 has shipped"))
        assertEquals("112-4455", MailLinkRules.orderRef("Order #112-4455 confirmed"))
        assertEquals("AB12345", MailLinkRules.orderRef("Booking ref: ab12345"))
        assertEquals("998877", MailLinkRules.orderRef("Re: Ticket no. 998877 — update"))
        assertEquals("55512", MailLinkRules.orderRef("Thanks! #55512"))
        assertNull(MailLinkRules.orderRef("Order on 2026-10-12"))
        assertNull(MailLinkRules.orderRef("Your order has shipped"))
        assertNull(MailLinkRules.orderRef("Invoice 2026"))
        assertNull(MailLinkRules.orderRef("Order 123"))
        assertNull(MailLinkRules.orderRef("Meet at 12345 Main Street"))
        assertNull(MailLinkRules.orderRef(null))
    }

    @Test
    fun topicsDropReplyAndForwardPrefixes() {
        assertEquals("the invoice", MailLinkRules.topic(item("a", hour, subject = "Re: Fwd: The invoice!")))
        assertNull(MailLinkRules.topic(item("b", hour, subject = "Re:")))
    }

    @Test
    fun sameThreadSameOrderAndSamePersonAndTopicLink() {
        val t1 = item("t1", hour, thread = "x")
        val t2 = item("t2", 2 * hour, from = "Alex Reed", thread = "x")
        assertEquals(MailLinkRules.Why.THREAD, MailLinkRules.why(t1, t2))
        val o1 = item("o1", hour, from = "Shop", subject = "Order 112-4455 confirmed")
        val o2 = item("o2", 2 * hour, from = "Parcel Co", subject = "On its way", preview = "Your parcel for order 112-4455 is out")
        assertEquals(MailLinkRules.Why.ORDER, MailLinkRules.why(o1, o2))
        val p1 = item("p1", hour, subject = "Re: Football on Saturday")
        val p2 = item("p2", 2 * hour, subject = "Football on Saturday")
        assertEquals(MailLinkRules.Why.TOPIC, MailLinkRules.why(p1, p2))
        // Not linked: another person on the same topic, another account, another topic.
        assertNull(MailLinkRules.why(p1, item("p3", hour, from = "Alex Reed", subject = "Football on Saturday")))
        assertNull(MailLinkRules.why(p1, item("p4", hour, subject = "Football on Saturday", account = "Business")))
        assertNull(MailLinkRules.why(t1, item("t3", hour, thread = "y")))
    }

    @Test
    fun linkedEmailsShareOneRowOnTheCard() {
        val items = listOf(
            item("o1", hour, from = "Shop", subject = "Order 112-4455 confirmed"),
            item("a", 2 * hour, subject = "Lunch?"),
            item("o2", 3 * hour, from = "Parcel Co", subject = "On its way", preview = "Your parcel for order 112-4455 is out"),
            item("t1", 4 * hour, thread = "x", subject = "Plans"),
            item("t2", 5 * hour, from = "Alex Reed", thread = "x", subject = "Re: Plans"),
        )
        val card = assertNotNull(MailNeedsRules.card(items, now, cal))
        assertEquals("5 emails need you", card.headline)
        assertEquals(listOf("o1", "a", "t1"), card.rows.map { it.id })
        assertEquals(listOf("o2"), card.rows[0].linked.map { it.id })
        assertEquals("+ 1 linked · same order 112-4455", card.rows[0].linkedLine)
        assertEquals("Personal · 05:30", card.rows[0].linked[0].line)
        assertNull(card.rows[1].linkedLine)
        assertTrue(card.rows[1].linked.isEmpty())
        assertEquals("+ 1 more in this thread", card.rows[2].linkedLine)
        assertTrue(card.rows[2].spoken.contains("+ 1 more in this thread"))
        // A chain mixing reasons.
        val chain = listOf(
            item("c1", hour, thread = "z", subject = "Your order 7788990"),
            item("c2", 2 * hour, from = "Parcel Co", subject = "Shipped", preview = "order 7788990"),
            item("c3", 3 * hour, thread = "z", subject = "Re: Your order 7788990"),
        )
        assertEquals("+ 2 linked emails", MailNeedsRules.card(chain, now, cal)!!.rows.single().linkedLine)
        assertEquals("+ 1 more from Sam Taylor about this", MailLinkRules.linkedLine(listOf(item("p1", hour, subject = "Re: Kit"), item("p2", 2 * hour, subject = "Kit"))))
    }

    @Test
    fun groupIdsPutTheTappedOneFirstAndSkipWhatIsNotShowing() {
        val items = listOf(
            item("t1", hour, thread = "x"),
            item("t2", 2 * hour, thread = "x"),
            item("t3", 3 * hour, thread = "x", extra = MailNeedsRules.doneFields(now)),
        )
        assertEquals(listOf("t2", "t1"), MailLinkRules.groupIds(items, "t2", now))
        assertEquals(listOf("nope"), MailLinkRules.groupIds(items, "nope", now))
        assertEquals("Done · 2 emails archived at Navilife's next check", MailLinkRules.doneLine(2))
        assertEquals(MailNeedsRules.DONE_LINE, MailLinkRules.doneLine(1))
    }
}
