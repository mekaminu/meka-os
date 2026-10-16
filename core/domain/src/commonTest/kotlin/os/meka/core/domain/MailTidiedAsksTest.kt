package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Archived for you" shows which emails, slice 2: tap an email → its preview, Put back, Always keep, Unsubscribe. */
class MailTidiedAsksTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_000L
    private fun at(minute: Int, d: Long = day) = cal.toEpochMs(d, minute)

    private fun email(
        id: String, from: String, subject: String, tidy: MailTidy = MailTidy.ARCHIVE, sort: MailSort? = MailSort.PROMO,
        minute: Int = 9 * 60, unsubscribe: Boolean = false, preview: String? = null,
    ) = MailTidiedEmail(MailTidiedRules.key("acc1", id), tidy, sort, at(minute), from, "shop.example", subject, null, unsubscribe, preview)

    private fun batch(emails: List<MailTidiedEmail>, extra: Map<String, FieldValue> = emptyMap(), id: String = "mt1"): MailTidyBatch {
        val fields = MailAutopilotRules.batchFields(at(12 * 60), "Personal", emails.groupingBy { it.tidy }.eachCount()) +
            MailTidiedRules.fields(emails) + extra
        return MailAutopilotRules.readBatch(id, fields)!!
    }

    private fun MailTidyBatch.with(f: Map<String, FieldValue>?): MailTidyBatch {
        val fields = MailAutopilotRules.batchFields(atMs, account, kinds) + MailTidiedRules.fields(emails) +
            (emailAsks.takeIf { it.isNotEmpty() }?.let { mapOf(MailTidyBatchFields.EMAIL_ASKS to FieldValue.Text(MailTidiedAskRules.encodeAsks(it))) } ?: emptyMap()) +
            (emailDone.takeIf { it.isNotEmpty() }?.let { mapOf(MailTidyBatchFields.EMAIL_DONE to FieldValue.Text(MailTidiedAskRules.encodeDone(it))) } ?: emptyMap()) +
            (undoAskedAtMs?.let { mapOf(MailTidyBatchFields.UNDO_ASKED_AT to FieldValue.Int64(it)) } ?: emptyMap()) +
            assertNotNull(f)
        return MailAutopilotRules.readBatch(id, fields)!!
    }

    @Test
    fun thePreviewAndTheUnsubscribeFlagRoundTripAndOlderLinesStillRead() {
        val e = email("m1", "Deliveroo", "2 for 1 tonight", unsubscribe = true, preview = "Your favourites\tare back,\norder by 9pm")
        val back = MailTidiedRules.decode(MailTidiedRules.encode(listOf(e))).single()
        assertTrue(back.unsubscribe)
        assertEquals("Your favourites are back, order by 9pm", back.preview)
        // A slice-1 line (nine columns) reads with no preview and no unsubscribe.
        val old = MailTidiedRules.line(e).split('\t').take(9).joinToString("\t")
        val o = MailTidiedRules.decode(old).single()
        assertFalse(o.unsubscribe)
        assertNull(o.preview)
        // A long preview is cut.
        val long = email("m2", "Shop", "Sale", preview = "x".repeat(500))
        assertEquals(MailTidiedRules.MAX_PREVIEW, MailTidiedRules.decode(MailTidiedRules.encode(listOf(long))).single().preview!!.length)
    }

    @Test
    fun whenPreviewsWouldCrowdEmailsOutTheListKeepsEveryEmailWithoutThem() {
        val es = (1..100).map { email("m$it", "Sender $it", "S".repeat(80), minute = it, preview = "p".repeat(200)) }
        val text = assertNotNull(MailTidiedRules.encode(es))
        assertTrue(text.length <= MailTidiedRules.MAX_CHARS)
        val back = MailTidiedRules.decode(text)
        assertEquals(100, back.size)
        assertTrue(back.all { it.preview == null })
        // A short tidy keeps them.
        assertTrue(MailTidiedRules.decode(MailTidiedRules.encode(es.take(5))).all { it.preview != null })
    }

    @Test
    fun thePreviewOffersWhatFitsTheEmail() {
        val promo = email("m1", "Deliveroo", "2 for 1 tonight", unsubscribe = true, preview = "Your favourites are back")
        val receipt = email("m2", "Amazon", "Your order", MailTidy.RECEIPT, null, unsubscribe = true)
        val b = batch(listOf(promo, receipt))
        val now = at(12 * 60 + 30)
        val p = assertNotNull(MailTidiedAskRules.preview(b, promo.key, now, cal))
        assertEquals("Deliveroo", p.from)
        assertEquals("shop.example · Personal", p.meta)
        assertEquals("Promo", p.chip)
        assertEquals("Your favourites are back", p.lines)
        assertTrue(p.time.endsWith("· 09:00"))
        assertEquals("Put back", p.putBack)
        assertEquals("Always keep from Deliveroo", p.keep)
        assertEquals("Unsubscribe from Deliveroo", p.unsubscribe)
        assertNull(p.state)
        // A receipt: never Unsubscribe from here; no preview kept says so when spoken.
        val r = assertNotNull(MailTidiedAskRules.preview(b, receipt.key, now, cal))
        assertNull(r.unsubscribe)
        assertNull(r.lines)
        assertTrue(r.spoken.contains(MailTidiedAskRules.NO_LINES))
        assertNull(MailTidiedAskRules.preview(b, "te-gone", now, cal))
    }

    @Test
    fun junkTidiesPutBackInJunkAndNeverOfferKeepOrUnsubscribe() {
        val spam = email("j1", "Prize Desk", "You won", MailTidy.JUNK_TRASH, null, unsubscribe = true)
        val b = batch(listOf(spam))
        val p = assertNotNull(MailTidiedAskRules.preview(b, spam.key, at(13 * 60), cal))
        assertEquals("Put back in Junk", p.putBack)
        assertNull(p.keep)
        assertNull(p.unsubscribe)
    }

    @Test
    fun anAskShowsPendingThenTheServersAnswerAndCanOnlyBeAskedOnce() {
        val promo = email("m1", "Deliveroo", "2 for 1 tonight", unsubscribe = true)
        val b = batch(listOf(promo))
        val now = at(12 * 60 + 30)
        val b1 = b.with(MailTidiedAskRules.ask(b, promo.key, MailTidiedAction.PUT_BACK, now))
        assertEquals(MailTidiedAction.PUT_BACK.pending, MailTidiedAskRules.state(b1, promo.key))
        assertNull(MailTidiedAskRules.ask(b1, promo.key, MailTidiedAction.PUT_BACK, now + 1))
        val p = assertNotNull(MailTidiedAskRules.preview(b1, promo.key, now + 1, cal))
        assertNull(p.putBack)
        assertEquals("Always keep from Deliveroo", p.keep) // still offered: the rule is worth having
        // The card's row says it too.
        val (groups, _) = MailTidiedRules.groups(b1, b1.emails, now + 1, cal)
        assertEquals(MailTidiedAction.PUT_BACK.pending, groups.single().rows.single().state)
        assertEquals(b1.id, groups.single().rows.single().batchId)
        // The server sees it pending, acts and answers.
        assertEquals(listOf(MailTidiedAction.PUT_BACK), MailTidiedAskRules.pending(b1).map { it.action })
        val b2 = b1.with(MailTidiedAskRules.doneFields(b1, listOf(MailTidiedDone(promo.key, MailTidiedAction.PUT_BACK, now + 60_000, "Back in the inbox"))))
        assertTrue(MailTidiedAskRules.pending(b2).isEmpty())
        assertEquals("Back in the inbox", MailTidiedAskRules.state(b2, promo.key))
        // Undo after the server acted changes nothing; before, it takes the ask off.
        assertNull(MailTidiedAskRules.unask(b2, promo.key, MailTidiedAction.PUT_BACK))
        val b3 = b2.with(MailTidiedAskRules.ask(b2, promo.key, MailTidiedAction.UNSUBSCRIBE, now + 120_000))
        assertEquals(MailTidiedAction.UNSUBSCRIBE.pending, MailTidiedAskRules.state(b3, promo.key))
        val b4 = b3.with(MailTidiedAskRules.unask(b3, promo.key, MailTidiedAction.UNSUBSCRIBE))
        assertEquals("Back in the inbox", MailTidiedAskRules.state(b4, promo.key))
        assertNotNull(MailTidiedAskRules.preview(b4, promo.key, now + 130_000, cal)?.unsubscribe)
    }

    @Test
    fun keepAlsoPutsBackSoPutBackIsntOfferedAfterIt() {
        val promo = email("m1", "Deliveroo", "2 for 1 tonight")
        val b = batch(listOf(promo))
        val now = at(12 * 60 + 30)
        val b1 = b.with(MailTidiedAskRules.ask(b, promo.key, MailTidiedAction.KEEP, now))
        val p = assertNotNull(MailTidiedAskRules.preview(b1, promo.key, now, cal))
        assertNull(p.putBack)
        assertNull(p.keep)
        assertNull(p.unsubscribe) // it offered none
        assertEquals("Deliveroo stays in the inbox from now on · this email goes back at Navilife's next check",
            MailTidiedAskRules.barLine(MailTidiedAction.KEEP, "Deliveroo"))
    }

    @Test
    fun aWholeTidyPutBackOrTooOldOffersNoPutBack() {
        val promo = email("m1", "Deliveroo", "2 for 1 tonight", unsubscribe = true)
        val b = batch(listOf(promo), mapOf(MailTidyBatchFields.UNDO_ASKED_AT to FieldValue.Int64(at(12 * 60 + 5))))
        val p = assertNotNull(MailTidiedAskRules.preview(b, promo.key, at(12 * 60 + 30), cal))
        assertNull(p.putBack)
        assertNotNull(p.unsubscribe)
        val old = batch(listOf(promo))
        val late = old.atMs + (MailAutopilotRules.UNDO_DAYS + 1) * 86_400_000L
        val q = assertNotNull(MailTidiedAskRules.preview(old, promo.key, late, cal))
        assertNull(q.putBack ?: q.keep ?: q.unsubscribe)
        assertNull(MailTidiedAskRules.ask(old, promo.key, MailTidiedAction.KEEP, late))
    }

    @Test
    fun answersNeverCarryTabsOrNewlines() {
        val d = MailTidiedDone("te1", MailTidiedAction.UNSUBSCRIBE, 5, "Unsubscribed\t· the sender\nconfirmed it")
        val back = MailTidiedAskRules.decodeDone(MailTidiedAskRules.encodeDone(listOf(d))).single()
        assertEquals("Unsubscribed · the sender confirmed it", back.note)
        assertTrue(MailTidiedAskRules.decodeAsks("broken\nte1\tNOPE\t5").isEmpty())
    }
}
