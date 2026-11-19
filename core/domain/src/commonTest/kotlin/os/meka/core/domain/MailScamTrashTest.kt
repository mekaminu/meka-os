package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Move to Trash on a possible scam: just this email to Trash, no block (Scam email never a task, slice 3). */
class MailScamTrashTest {
    private val now = 20_000L * 86_400_000L + 12 * 3_600_000L
    private val cal = LocalCalendar.UTC
    private val scam = "⚠ Possible scam · sent from mail-x.example, not invest.example"

    private fun item(warning: String = scam) =
        assertNotNull(MailNeedsRules.read("a", MailNeedsRules.serverFields("Personal", "Invest Co", "x@mail-x.example", "Our bank details are changing", null, now - 3_600_000L, 1, warning = warning)))

    private fun row(i: MailItem = item()) = assertNotNull(MailScamListRules.card(listOf(i), now, cal)).rows.single()

    @Test
    fun aPossibleScamOffersMoveToTrashBesideJunkItAndBlockAndNeverTask() {
        val a = MailRowActionRules.actions(row(), notScam = true, trash = true)
        assertEquals(listOf(MailRowAction.BIN, MailRowAction.TRASH, MailRowAction.NOT_SCAM, MailRowAction.DONE), a.shown)
        assertFalse(MailRowAction.TASK in a.shown + a.more)
        // Not offered where the surface has no Trash, and never on an email that isn't a possible scam.
        assertFalse(MailRowAction.TRASH in MailRowActionRules.actions(row(), notScam = true).shown)
        val plain = assertNotNull(MailNeedsRules.card(listOf(item(warning = "")), now, cal)).rows.single()
        assertFalse(MailRowAction.TRASH in MailRowActionRules.actions(plain, trash = true).let { it.shown + it.more })
        assertEquals("Move to Trash", MailRowActionRules.label(MailRowAction.TRASH, row()))
        assertEquals("Move Invest Co's email to Trash, kept there 30 days. The sender isn't blocked", MailRowActionRules.spoken(MailRowAction.TRASH, row()))
        assertTrue(MailRowActionRules.critical(MailRowAction.TRASH))
        // The reader's scam answers too.
        assertEquals(
            listOf(MailRowAction.BIN, MailRowAction.TRASH, MailRowAction.NOT_SCAM, MailRowAction.DONE),
            MailRowActionRules.readerScam(row(), notScam = true, trash = true),
        )
        assertEquals(listOf(MailRowAction.BIN, MailRowAction.NOT_SCAM, MailRowAction.DONE), MailRowActionRules.readerScam(row(), notScam = true))
    }

    @Test
    fun itsDoneSendsJustThisEmailToTrashAndALaterPlainDoneArchives() {
        val f = MailScamTrashRules.fields(now)
        assertEquals(FieldValue.Int64(now), f[MailItemFields.DONE_ASKED_AT])
        assertEquals(FieldValue.Int64(now), f[MailItemFields.TRASH_ASKED_AT])
        val trashed = item().copy(doneAskedAtMs = now, trashAskedAtMs = now)
        assertTrue(MailScamTrashRules.trashing(trashed))
        assertEquals(MailTidy.TRASH, MailSenderRuleRules.cardTidy(trashed, protected = false))
        // His own tap on one email: Trash even for a sender Navilife would never block (it's recoverable for 30 days).
        assertEquals(MailTidy.TRASH, MailSenderRuleRules.cardTidy(trashed, protected = true))
        // No sender rule is asked for.
        assertFalse(MailSenderRuleRules.ruleDue(trashed))
        // Undo, then a plain Done later: archived as usual.
        val undone = trashed.copy(undoAskedAtMs = now + 1)
        assertFalse(MailScamTrashRules.trashing(undone))
        val again = undone.copy(doneAskedAtMs = now + 2)
        assertFalse(MailScamTrashRules.trashing(again))
        assertEquals(MailTidy.ARCHIVE, MailSenderRuleRules.cardTidy(again, protected = false))
        // Never on account-security mail.
        assertFalse(MailScamTrashRules.trashing(trashed.copy(security = "Google")))
        assertEquals("Moving Invest Co's email to Trash · kept there 30 days · Undo brings it back", MailScamTrashRules.line("Invest Co"))
    }

    @Test
    fun theFieldReadsBack() {
        val f = MailNeedsRules.serverFields("Personal", "Invest Co", "x@mail-x.example", "s", null, now, 1, warning = scam) +
            MailScamTrashRules.fields(now + 5)
        val i = assertNotNull(MailNeedsRules.read("a", f))
        assertEquals(now + 5, i.trashAskedAtMs)
        assertTrue(MailScamTrashRules.trashing(i))
        assertTrue(MailNeedsRules.archiveDue(i))
    }
}
