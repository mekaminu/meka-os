package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MailSenderRuleTest {
    private fun sure(s: MailSort) = MailSorted(s, MailSortReason.MAILING_LIST, true)
    private fun unsure(s: MailSort) = MailSorted(s, MailSortReason.MAILING_LIST, false)
    private fun plan(
        s: MailSorted, rule: MailSenderRule?, from: String = "news@shop.example", replied: Boolean = false,
        unsubscribed: Boolean = false, inInbox: Boolean = true, keep: Set<String> = emptySet(),
    ) = MailTidyRules.plan(MailTidyFacts(from, inInbox, s, keep, replied = replied, unsubscribed = unsubscribed, rule = rule))

    @Test
    fun keepLeavesEverythingFromTheSenderInTheInbox() {
        for (s in MailSort.entries) {
            assertEquals(MailTidy.KEEP, plan(sure(s), MailSenderRule.KEEP), s.name)
        }
        // Even after an unsubscribe went through: his "keep" wins over Trash.
        assertEquals(MailTidy.KEEP, plan(sure(MailSort.NEWSLETTER), MailSenderRule.KEEP, unsubscribed = true))
        // Without a rule the usual plan stands.
        assertEquals(MailTidy.NEWSLETTER, plan(sure(MailSort.NEWSLETTER), null))
        assertEquals(MailTidy.TRASH, plan(sure(MailSort.NEWSLETTER), null, unsubscribed = true))
    }

    @Test
    fun tidyArchivesSureOrNotKeepingItsKind() {
        assertEquals(MailTidy.NEWSLETTER, plan(unsure(MailSort.NEWSLETTER), MailSenderRule.TIDY))
        assertEquals(MailTidy.RECEIPT, plan(unsure(MailSort.RECEIPT), MailSenderRule.TIDY))
        assertEquals(MailTidy.ARCHIVE, plan(sure(MailSort.NEEDS_YOU), MailSenderRule.TIDY, from = "reports@tool.example"))
        assertEquals(MailTidy.ARCHIVE, plan(unsure(MailSort.FYI), MailSenderRule.TIDY))
        assertEquals(MailTidy.ARCHIVE, plan(sure(MailSort.NOTIFICATION), MailSenderRule.TIDY))
        // Junk is left for the daily review.
        assertEquals(MailTidy.KEEP, plan(sure(MailSort.JUNK), MailSenderRule.TIDY))
        // An unsubscribed sender's newsletter still goes to Trash.
        assertEquals(MailTidy.TRASH, plan(sure(MailSort.PROMO), MailSenderRule.TIDY, unsubscribed = true))
    }

    @Test
    fun theNeverTouchRulesBeatAlwaysTidy() {
        for (from in listOf("alerts@barclays.co.uk", "office@oakfield.sch.uk", "x@tax.service.gov.uk")) {
            assertEquals(MailTidy.KEEP, plan(sure(MailSort.NOTIFICATION), MailSenderRule.TIDY, from = from), from)
        }
        assertEquals(MailTidy.KEEP, plan(sure(MailSort.NEEDS_YOU), MailSenderRule.TIDY, from = "alex@home.example", keep = setOf("alex@home.example")))
        assertEquals(MailTidy.KEEP, plan(sure(MailSort.NEWSLETTER), MailSenderRule.TIDY, replied = true))
        assertEquals(MailTidy.KEEP, plan(sure(MailSort.NEWSLETTER), MailSenderRule.TIDY, inInbox = false))
    }

    private val cal = LocalCalendar.UTC
    private val now = 20_000L * 86_400_000L + 12 * 3_600_000L

    @Test
    fun theRuleIsSyncedOnTheSenderAndShownOnItsRow() {
        val base = MailUnsubscribeRules.senderFields("Personal", "Weekly Digest", "digest.example.com", UnsubscribeMethod.ONE_CLICK, now, 8)
        val plain = assertNotNull(MailUnsubscribeRules.read("ms1", base))
        assertNull(plain.rule)
        val row = MailSenderPanelRules.rows(listOf(plain), "Personal", now, cal).single()
        assertNull(row.ruleLine)
        assertEquals(MailSenderRuleRules.KEEP_ACTION, row.ruleAction)
        assertEquals("KEEP", row.ruleSets)

        val kept = assertNotNull(MailUnsubscribeRules.read("ms1", base + MailSenderRuleRules.fields(MailSenderRule.KEEP, now)))
        assertEquals(MailSenderRule.KEEP, kept.rule)
        val keptRow = MailSenderPanelRules.rows(listOf(kept), "Personal", now, cal).single()
        assertEquals(MailSenderRule.KEEP.line, keptRow.ruleLine)
        assertEquals(MailSenderRuleRules.CLEAR_ACTION, keptRow.ruleAction)
        assertEquals("", keptRow.ruleSets)

        // Taken off: "" reads as no rule; an unknown name too.
        val cleared = assertNotNull(MailUnsubscribeRules.read("ms1", base + MailSenderRuleRules.fields(null, now + 1)))
        assertNull(cleared.rule)
        assertNull(MailUnsubscribeRules.read("ms1", base + mapOf(MailSenderFields.RULE to FieldValue.Text("SOMETIMES")))?.rule)
        // The server's own fields never carry a rule, so its next write can't take his off.
        assertNull(base[MailSenderFields.RULE])
    }

    @Test
    fun whatTheBarSays() {
        assertEquals("Weekly Digest stays in the inbox · Autopilot won't tidy its emails", MailSenderRuleRules.setLine("Weekly Digest", MailSenderRule.KEEP))
        assertEquals("Autopilot will tidy Weekly Digest's emails as they arrive", MailSenderRuleRules.setLine("Weekly Digest", MailSenderRule.TIDY))
        assertEquals("Autopilot decides about Weekly Digest's emails again", MailSenderRuleRules.setLine("Weekly Digest", null))
    }
}
