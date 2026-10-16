package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drafted replies, slice 3: every reply he sends shows in Activity — held with Undo, then Sent, or why it didn't go. */
class MailSentActivityTest {
    private val now = 1_792_225_800_000L
    private val minute = 60_000L

    private fun mail(id: String = "m1", from: String = "Sam Taylor", subject: String = "Saturday", thread: String? = null, extra: Map<String, FieldValue> = emptyMap()): MailItem =
        MailNeedsRules.read(
            id, MailNeedsRules.serverFields("Personal", from, "sam@example.com", subject, "Can you drive on Saturday?", now - 60 * minute, 1, thread) + extra,
        )!!

    private val sent = MailNeedsRules.replyFields("Yes, I can drive.\nSee you then, Alex", now)

    @Test
    fun noRowUntilHeTapsSend() {
        assertNull(MailSentActivityRules.item(mail()))
        assertNull(MailSentActivityRules.item(mail(extra = MailAutoDraftRules.draftedFields("Hi Sam, yes.", now))))
    }

    @Test
    fun aHeldReplyCanBeStoppedFromActivity() {
        val row = MailSentActivityRules.item(mail(extra = sent))!!
        assertEquals("Sending reply to Sam Taylor · Personal", row.summary)
        assertEquals("Re: Saturday · “Yes, I can drive.” · ${MailSentActivityRules.HOLD_NOTE}", row.detail)
        assertEquals(MailSentActivityRules.WHY_OWN, row.why)
        assertEquals(ActivityKind.CHANGED, row.kind)
        assertEquals(now, row.atMs)
        assertTrue(row.canUndo)
        assertEquals("s" + "m1", row.id)
    }

    @Test
    fun aSentReplyIsSentWithNoUndo() {
        val row = MailSentActivityRules.item(mail(subject = "Re: Saturday", extra = sent + MailNeedsRules.replySentFields(now + minute)))!!
        assertEquals("Replied to Sam Taylor · Personal", row.summary)
        assertEquals("Re: Saturday · “Yes, I can drive.” · Sent", row.detail)
        assertEquals(now + minute, row.atMs)
        assertFalse(row.canUndo)
        assertNull(row.undoneAtMs)
    }

    @Test
    fun aFailedReplySaysWhyAndKeepsHisWords() {
        val row = MailSentActivityRules.item(mail(extra = sent + MailNeedsRules.replyFailedFields(now + minute, MailNeedsRules.whyUnreachable("Gmail"))))!!
        assertEquals("Reply to Sam Taylor didn't send · couldn't reach Gmail", row.summary)
        assertEquals("Re: Saturday · ${MailSentActivityRules.KEPT_NOTE}", row.detail)
        assertFalse(row.canUndo)
    }

    @Test
    fun aReplyTakenBackShowsAsUndone() {
        val row = MailSentActivityRules.item(mail(extra = sent + MailNeedsRules.undoDoneFields(now + 5_000)))!!
        assertEquals("Reply to Sam Taylor not sent · Personal", row.summary)
        assertEquals(now + 5_000, row.undoneAtMs)
        assertFalse(row.canUndo)
        // Sent again later: held again with Undo.
        val again = MailSentActivityRules.item(mail(extra = sent + MailNeedsRules.undoDoneFields(now + 5_000) + MailNeedsRules.replyFields("OK", now + 9 * minute)))!!
        assertTrue(again.canUndo)
        assertTrue(again.summary.startsWith("Sending reply"))
    }

    @Test
    fun navilifesDraftSentAsWrittenSaysSo() {
        val draft = "Hi Sam,\n\nYes, I can drive on Saturday.\n\nAlex"
        val asIs = mail(extra = MailAutoDraftRules.draftedFields(draft, now - minute) + MailNeedsRules.replyFields(draft, now))
        assertEquals(MailSentActivityRules.WHY_DRAFT, MailSentActivityRules.item(asIs)!!.why)
        assertEquals("“Hi Sam,”", MailSentActivityRules.quote(draft))
        val edited = mail(extra = MailAutoDraftRules.draftedFields(draft, now - minute) + MailNeedsRules.replyFields("Sorry, can't this week.", now))
        assertEquals(MailSentActivityRules.WHY_OWN, MailSentActivityRules.item(edited)!!.why)
    }

    @Test
    fun longRepliesAreCutAndTheReceiptDoesntCountThem() {
        val long = "a".repeat(200)
        assertEquals(62, MailSentActivityRules.quote(long).length)
        assertTrue(MailSentActivityRules.quote(long).endsWith("…”"))
        val row = MailSentActivityRules.item(mail(extra = sent + MailNeedsRules.replySentFields(now + minute)))!!
        assertFalse(ReceiptRules.counts(row))
    }

    @Test
    fun undoTakesBackTheThreadEmailsTheSameSendArchived() {
        val reply = mail("m2", thread = "t1", extra = sent)
        val linked = mail("m1", thread = "t1", extra = MailNeedsRules.doneFields(now + 3))
        val earlier = mail("m0", thread = "t1", extra = MailNeedsRules.doneFields(now - 60 * minute))
        val other = mail("m9", thread = "t2", extra = MailNeedsRules.doneFields(now))
        assertEquals(listOf("m2", "m1"), MailSentActivityRules.undoGroup(reply, listOf(reply, linked, earlier, other)))
        assertEquals(listOf("m5"), MailSentActivityRules.undoGroup(mail("m5", extra = sent), listOf(linked)))
    }
}
