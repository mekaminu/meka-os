package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Junk fix slice 2: Block sender, Bin & block, Unsubscribe on a card, and Always tidy said plainly. */
class MailBlockTest {
    private val cal = LocalCalendar.UTC
    private val now = 20_000L * 86_400_000L + 12 * 3_600_000L

    private fun sure(s: MailSort) = MailSorted(s, MailSortReason.MAILING_LIST, true)
    private fun plan(s: MailSorted, from: String = "promo@prizes.example", keep: Set<String> = emptySet(), replied: Boolean = false) =
        MailTidyRules.plan(MailTidyFacts(from, true, s, keep, replied = replied, rule = MailSenderRule.BLOCK))

    private fun fields(extra: Map<String, FieldValue> = emptyMap(), warning: String = "", security: String = "", unsub: Boolean = false) =
        MailNeedsRules.serverFields("Personal", "Prize Team", "promo@prizes.example", "You've won!", "Claim now", now - 60_000, 1,
            security = security, warning = warning, canUnsubscribe = unsub) + extra

    private fun item(f: Map<String, FieldValue>) = assertNotNull(MailNeedsRules.read("mi1", f))

    @Test
    fun aBlockedSendersMailGoesToTrashWhateverItIs() {
        for (s in listOf(MailSort.NEEDS_YOU, MailSort.FYI, MailSort.PROMO, MailSort.NEWSLETTER, MailSort.RECEIPT, MailSort.NOTIFICATION)) {
            assertEquals(MailTidy.TRASH, plan(sure(s)), s.name)
        }
    }

    @Test
    fun theNeverTouchRulesBeatABlock() {
        for (from in listOf("alerts@barclays.co.uk", "office@oakfield.sch.uk", "x@tax.service.gov.uk")) {
            assertEquals(MailTidy.KEEP, plan(sure(MailSort.NOTIFICATION), from = from), from)
        }
        // His partner and the people he writes to, and a thread he wrote in.
        assertEquals(MailTidy.KEEP, plan(sure(MailSort.NEEDS_YOU), from = "sam@home.example", keep = setOf("sam@home.example")))
        assertEquals(MailTidy.KEEP, plan(sure(MailSort.NEWSLETTER), replied = true))
        // Account-security mail is never moved.
        assertEquals(MailTidy.KEEP, plan(MailSorted(MailSort.NEEDS_YOU, MailSortReason.SECURITY, true), from = "no-reply@accounts.google.com"))
    }

    @Test
    fun blockIsOfferedOnOrdinaryAndScamRowsButNotOnSecurityOrARealCompanysNotice() {
        val plainRow = MailNeedsRules.card(listOf(item(fields())), now, cal)!!.rows.single()
        assertTrue(plainRow.canBlock)
        val scam = MailNeedsRules.card(listOf(item(fields(warning = "⚠ Possible scam · sent from mail-x.example, not paypal.com"))), now, cal)!!.rows.single()
        assertTrue(scam.scam)
        assertTrue(scam.canBlock)
        val real = MailNeedsRules.card(listOf(item(fields(warning = "From PayPal's real address · log in at paypal.com yourself, not through the email's link"))), now, cal)!!.rows.single()
        assertFalse(real.canBlock)
        val security = MailNeedsRules.card(listOf(item(fields(security = "Google"))), now, cal)!!.rows.single()
        assertFalse(security.canBlock)
        assertFalse(security.canUnsubscribe)
    }

    @Test
    fun theCardAsksForTheBlockAndItsOwnEmailGoesToTrash() {
        val asked = item(fields(MailSenderRuleRules.cardFields(now, MailSenderRule.BLOCK)))
        assertEquals(MailSenderRule.BLOCK, MailSenderRuleRules.asked(asked))
        assertTrue(MailSenderRuleRules.ruleDue(asked))
        assertEquals(MailTidy.TRASH, MailSenderRuleRules.cardTidy(asked, protected = false))
        // A bank (or anyone protected) is only archived, like a Done.
        assertEquals(MailTidy.ARCHIVE, MailSenderRuleRules.cardTidy(asked, protected = true))
        // Always tidy (and an older card without the field) archives.
        val tidy = item(fields(MailSenderRuleRules.cardFields(now)))
        assertEquals(MailSenderRule.TIDY, MailSenderRuleRules.asked(tidy))
        assertEquals(MailTidy.ARCHIVE, MailSenderRuleRules.cardTidy(tidy, protected = false))
        val old = item(fields(MailNeedsRules.doneFields(now) + (MailItemFields.TIDY_ASKED_AT to FieldValue.Int64(now))))
        assertEquals(MailSenderRule.TIDY, MailSenderRuleRules.asked(old))
        // The sender's entity gets the block rule; Undo takes off only the card's own block.
        val sender = assertNotNull(MailSenderRuleRules.senderFieldsForCard(null, "Personal", "Prize Team", "promo@prizes.example", now, now, MailSenderRule.BLOCK))
        assertEquals(MailSenderRule.BLOCK, MailSenderRuleRules.read(sender))
        val cleared = assertNotNull(MailSenderRuleRules.clearFieldsForCard(sender, now, now + 1, MailSenderRule.BLOCK))
        assertEquals(null, MailSenderRuleRules.read(cleared))
        assertEquals(null, MailSenderRuleRules.clearFieldsForCard(sender, now, now + 1, MailSenderRule.TIDY))
    }

    @Test
    fun laterMailFromABlockedSenderIsDueOnlyAfterTheBlock() {
        data class H(val from: String, val at: Long, val plan: MailTidy)
        val inbox = listOf(
            H("promo@prizes.example", now - 10, MailTidy.TRASH), // before the block: left
            H("Promo@Prizes.example", now + 10, MailTidy.TRASH), // after: due (address case ignored)
            H("promo@prizes.example", now + 20, MailTidy.KEEP), // protected by the never-touch rules: left
            H("someone@else.example", now + 30, MailTidy.TRASH), // not blocked
        )
        val due = MailSenderRuleRules.blockedDue(inbox, { it.from }, { it.at }, mapOf("promo@prizes.example" to now)) { it.plan }
        assertEquals(listOf(inbox[1]), due)
    }

    @Test
    fun unsubscribeOnACardIsADoneAndTheServerTriesItOnce() {
        val row = MailNeedsRules.card(listOf(item(fields(unsub = true))), now, cal)!!.rows.single()
        assertTrue(row.canUnsubscribe)
        assertFalse(MailNeedsRules.card(listOf(item(fields())), now, cal)!!.rows.single().canUnsubscribe)
        val asked = item(fields(MailCardUnsubRules.askFields(now), unsub = true))
        assertTrue(MailNeedsRules.done(asked))
        assertTrue(MailCardUnsubRules.due(asked))
        val tried = item(fields(MailCardUnsubRules.askFields(now) + MailCardUnsubRules.triedFields(UnsubscribeState.DONE.note, now + 5), unsub = true))
        assertFalse(MailCardUnsubRules.due(tried))
        assertEquals(UnsubscribeState.DONE.note, tried.unsubNote)
        // Undo before the server acted calls it off and brings the card back.
        val undone = item(fields(MailCardUnsubRules.askFields(now) + MailCardUnsubRules.undoFields(now + 1), unsub = true))
        assertFalse(MailCardUnsubRules.due(undone))
        assertFalse(MailNeedsRules.done(undone))
    }

    @Test
    fun alwaysTidySaysWhatItDoes() {
        assertEquals("Always tidy · archive their future mail", MailSenderRuleRules.ALWAYS_TIDY)
        assertEquals("Done · Prize Team's emails will skip your inbox and go to Archive · change it in Mail → Your rules", MailSenderRuleRules.cardLine("Prize Team"))
        assertTrue(MailSenderRuleRules.blockLine("Prize Team").startsWith("Blocked Prize Team · "))
        assertTrue(MailSenderRuleRules.binLine("Prize Team").startsWith("Binned · Prize Team is blocked"))
        assertEquals("Prize Team is blocked · its emails go straight to Trash", MailSenderRuleRules.setLine("Prize Team", MailSenderRule.BLOCK))
    }
}
