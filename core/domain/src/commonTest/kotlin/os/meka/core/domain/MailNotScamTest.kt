package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phishing in the body, slice 4: Junk it & block goes to Junk, and Not a scam takes the warning off and keeps the sender. */
class MailNotScamTest {
    private val cal = LocalCalendar.UTC
    private val now = 20_000L * 86_400_000L + 12 * 3_600_000L
    private val scamLine = "⚠ Possible scam · sent from formlab.example, not microsoft.com"

    private fun fields(extra: Map<String, FieldValue> = emptyMap(), warning: String = scamLine, security: String = "") =
        MailNeedsRules.serverFields("Outlook", "Outlook Session", "formazione@formlab.example", "Is Your Account Still Active?",
            "Confirm within 7 days", now - 60_000, 1, security = security, warning = warning) + extra

    private fun item(f: Map<String, FieldValue>) = assertNotNull(MailNeedsRules.read("mi1", f))
    private fun row(i: MailItem) = MailNeedsRules.card(listOf(i), now, cal)!!.rows.single()

    @Test
    fun junkItAndBlockGoesToJunkNotTrash() {
        val junk = item(fields(MailSenderRuleRules.cardFields(now, MailSenderRule.BLOCK, junk = true)))
        assertTrue(junk.junkAsked)
        assertEquals(MailTidy.SCAM_JUNK, MailSenderRuleRules.cardTidy(junk, protected = false))
        // A plain Block sender still goes to Trash; a protected sender is only archived.
        val block = item(fields(MailSenderRuleRules.cardFields(now, MailSenderRule.BLOCK), warning = ""))
        assertFalse(block.junkAsked)
        assertEquals(MailTidy.TRASH, MailSenderRuleRules.cardTidy(block, protected = false))
        assertEquals(MailTidy.ARCHIVE, MailSenderRuleRules.cardTidy(junk, protected = true))
        // Junk is only asked with the block, never with Always tidy.
        assertFalse(item(fields(MailSenderRuleRules.cardFields(now, MailSenderRule.TIDY, junk = true))).junkAsked)
        assertTrue(MailSenderRuleRules.binLine("Outlook Session").startsWith("Moved to Junk · Outlook Session is blocked"))
    }

    @Test
    fun notAScamTakesTheRedLineOffAndAsksTheServerToKeepTheSender() {
        assertTrue(row(item(fields())).scam)
        val said = item(fields(MailNotScamRules.fields(now)))
        assertTrue(MailNotScamRules.notScam(said))
        val r = row(said)
        assertFalse(r.scam)
        assertNull(r.warning)
        // Back to the ordinary answers: Reply · Done · Task, Block sender under More.
        assertEquals(listOf(MailRowAction.REPLY, MailRowAction.DONE, MailRowAction.TASK), MailRowActionRules.actions(r, notScam = true).shown)
        assertTrue(MailNotScamRules.keepDue(said))
        assertFalse(MailNotScamRules.clearDue(said))
        // A real company's "log in yourself" line stays: Not a scam only takes off a scam line.
        val real = "From Microsoft's real address · if it asks you to act, sign in yourself"
        assertEquals(real, row(item(fields(MailNotScamRules.fields(now), warning = real))).warning)
    }

    @Test
    fun theServerKeepsOnceAndUndoTakesItOffAgain() {
        val kept = item(fields(MailNotScamRules.fields(now) + MailNotScamRules.keptFields(now + 10)))
        assertFalse(MailNotScamRules.keepDue(kept))
        // Undo: the line is back and the keep comes off once.
        val undone = item(fields(MailNotScamRules.fields(now) + MailNotScamRules.keptFields(now + 10) + MailNotScamRules.undoFields(now + 20)))
        assertFalse(MailNotScamRules.notScam(undone))
        assertTrue(row(undone).scam)
        assertTrue(MailNotScamRules.clearDue(undone))
        val cleared = item(fields(MailNotScamRules.fields(now) + MailNotScamRules.keptFields(now + 10) + MailNotScamRules.undoFields(now + 20) +
            MailNotScamRules.clearedFields(now + 30)))
        assertFalse(MailNotScamRules.clearDue(cleared))
        // Undo before the server acted needs nothing.
        val early = item(fields(MailNotScamRules.fields(now) + MailNotScamRules.undoFields(now + 5)))
        assertFalse(MailNotScamRules.keepDue(early))
        assertFalse(MailNotScamRules.clearDue(early))
        // Said again after the Undo: kept again.
        val again = item(fields(MailNotScamRules.fields(now + 40) + MailNotScamRules.keptFields(now + 10) + MailNotScamRules.undoFields(now + 20)))
        assertTrue(MailNotScamRules.keepDue(again))
        assertFalse(MailNotScamRules.clearDue(again))
    }

    @Test
    fun theReaderLeadsWithJunkItAndBlockThenNotAScam() {
        val r = row(item(fields(warning = "")))
        assertEquals(listOf(MailRowAction.BIN, MailRowAction.NOT_SCAM, MailRowAction.DONE), MailRowActionRules.readerScam(r, notScam = true))
        assertEquals(listOf(MailRowAction.DONE), MailRowActionRules.readerScam(r, block = false))
        // Account-security mail is never junked from the reader.
        val security = row(item(fields(warning = "", security = "Microsoft")))
        assertFalse(MailRowAction.BIN in MailRowActionRules.readerScam(security, notScam = true))
    }

    @Test
    fun theReaderDropsTheBannerOnceHeSaysNotAScam() {
        val answer = MailMessageAnswer(
            state = MailMessageAnswer.OK, from = MailPerson("Outlook Session", "formazione@formlab.example"), to = emptyList(), cc = emptyList(),
            atMs = now, subject = "Is Your Account Still Active?", html = null,
            text = "Microsoft Outlook. Your account will be closed. Confirm within 7 days https://login.formlab.example/confirm",
            attachments = emptyList(), blockedImages = 0,
        )
        val fallback = MailReaderFallback("Outlook Session", "Is Your Account Still Active?", null, now, "Outlook")
        assertNotNull(MailReaderRules.view(answer, fallback, now, cal, images = false, warning = scamLine).scam)
        assertNull(MailReaderRules.view(answer, fallback, now, cal, images = false, warning = scamLine, notScam = true).scam)
    }
}
