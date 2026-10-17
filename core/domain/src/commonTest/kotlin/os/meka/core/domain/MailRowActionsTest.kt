package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Junk fix slice 3: every answer looks tappable (the rest under ⋯ More) and a link he emailed himself is a note to self. */
class MailRowActionsTest {
    private val cal = LocalCalendar.UTC
    private val now = 20_000L * 86_400_000L + 12 * 3_600_000L

    private fun item(
        id: String = "mi1", subject: String = "Invoice 42", preview: String = "Here is the invoice", warning: String = "",
        unsub: Boolean = false, note: String = "", from: String = "Sam Taylor",
    ) = assertNotNull(MailNeedsRules.read(id, MailNeedsRules.serverFields("Personal", from, "x@example.com", subject, preview, now - 60_000, 1,
        warning = warning, canUnsubscribe = unsub, note = note)))

    private fun row(i: MailItem) = MailNeedsRules.card(listOf(i), now, cal)!!.rows.single()

    @Test
    fun replyDoneAndTaskShowAndTheRestWaitUnderMore() {
        val a = MailRowActionRules.actions(row(item(unsub = true)))
        assertEquals(listOf(MailRowAction.REPLY, MailRowAction.DONE, MailRowAction.TASK), a.shown)
        assertEquals(listOf(MailRowAction.WAITING, MailRowAction.SNOOZE, MailRowAction.UNSUBSCRIBE, MailRowAction.ALWAYS_TIDY, MailRowAction.BLOCK), a.more)
        // A surface that offers no tidy, block or unsubscribe leaves them out.
        assertEquals(listOf(MailRowAction.WAITING, MailRowAction.SNOOZE), MailRowActionRules.actions(row(item(unsub = true)), tidy = false, block = false, unsubscribe = false).more)
        assertEquals("⋯ More", MailRowActionRules.MORE)
    }

    @Test
    fun aPossibleScamShowsBinAndBlockInTheCriticalColour() {
        val r = row(item(warning = "⚠ Possible scam · sent from mail-x.example, not paypal.com"))
        val a = MailRowActionRules.actions(r)
        assertEquals(MailRowAction.BIN, a.shown.last())
        assertFalse(MailRowAction.BLOCK in a.more)
        assertEquals(MailSenderRuleRules.BIN, MailRowActionRules.label(MailRowAction.BIN, r))
        assertTrue(MailRowActionRules.critical(MailRowAction.BIN))
        assertTrue(MailRowAction.entries.filter { it != MailRowAction.BIN }.none(MailRowActionRules::critical))
    }

    @Test
    fun findsTheLinkAndItsSite() {
        assertEquals("https://www.example.com/a?b=1", MailNoteRules.url("Look", "see https://www.example.com/a?b=1, later"))
        assertEquals("example.com", MailNoteRules.site("https://www.example.com/a?b=1"))
        assertEquals("news.example.co.uk", MailNoteRules.site("www.news.example.co.uk/x"))
        assertNull(MailNoteRules.url("Buy milk", "and bread"))
        // A link the preview cut isn't whole, but its site still makes a note.
        assertNull(MailNoteRules.url(null, "read https://example.com/a-very-long-pa…"))
        assertEquals("example.com", MailNoteRules.noteSite(MailSortReason.OWN, null, "read https://example.com/a-very-long-pa…"))
        // Only his own emails make notes.
        assertEquals("", MailNoteRules.noteSite(MailSortReason.PERSON, "https://example.com", null))
        assertEquals("", MailNoteRules.noteSite(MailSortReason.OWN, "Buy milk", "and bread"))
    }

    @Test
    fun aNoteToSelfOffersSaveAsTaskAndIsntCountedAsNeedingHim() {
        val note = item(id = "mi2", subject = "https://example.com/article", preview = "", note = "example.com", from = "Alex Doe")
        val r = MailNeedsRules.card(listOf(note), now, cal)!!.rows.single()
        assertEquals("Note to self · a link to example.com", r.note)
        assertFalse(r.canBlock)
        assertFalse(r.canUnsubscribe)
        val a = MailRowActionRules.actions(r)
        assertEquals(listOf(MailRowAction.TASK, MailRowAction.DONE), a.shown)
        assertEquals(listOf(MailRowAction.SNOOZE), a.more)
        assertEquals("Save as task", MailRowActionRules.label(MailRowAction.TASK, r))
        assertTrue(r.spoken.startsWith("Note to self"))
        // The headline counts the notes apart.
        assertEquals("1 note to self", MailNeedsRules.card(listOf(note), now, cal)!!.headline)
        assertEquals("1 email needs you · 1 note to self", MailNeedsRules.card(listOf(note, item()), now, cal)!!.headline)
        assertEquals("2 emails need you", MailNeedsRules.card(listOf(item(), item(id = "mi3")), now, cal)!!.headline)
        // The task: named after the site when the subject is only the link, the whole link in its notes.
        assertEquals("Look at example.com", MailNeedsRules.taskTitle(note))
        assertTrue(MailNeedsRules.taskNotes(note, cal).startsWith("Link you emailed yourself: https://example.com/article · Personal"))
        val titled = item(id = "mi4", subject = "Fwd: Recipe for Sunday", preview = "https://example.com/recipe", note = "example.com")
        assertEquals("Recipe for Sunday", MailNeedsRules.taskTitle(titled))
        // Ordinary emails keep the ordinary task.
        assertEquals("Invoice 42", MailNeedsRules.taskTitle(item()))
        assertTrue(MailNeedsRules.taskNotes(item(), cal).startsWith("Email from Sam Taylor"))
    }

    @Test
    fun theNoteIsWrittenOnlyWhenThereIsOne() {
        val f = MailNeedsRules.serverFields("Personal", "Me", "me@example.com", "x", "", now, 1, note = "example.com")
        assertEquals(FieldValue.Text("example.com"), f[MailItemFields.NOTE])
        assertFalse(MailItemFields.NOTE in MailNeedsRules.serverFields("Personal", "Me", "me@example.com", "x", "", now, 1))
    }
}
