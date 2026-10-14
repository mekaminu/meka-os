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

    // ---- Slice 10b ----

    private fun item(vararg extra: Pair<String, FieldValue>) = assertNotNull(MailNeedsRules.read("mi1",
        MailNeedsRules.serverFields("Personal", "Tool Reports", "reports@tool.example", "Weekly usage", null, now - 3_600_000L, 1) + extra.toMap()))

    @Test
    fun alwaysTidyOnACardAsksOnceAndUndoTakesTheRuleOff() {
        val asked = now
        val card = MailSenderRuleRules.cardFields(asked)
        var i = item(*card.toList().toTypedArray())
        assertEquals(asked, i.tidyAskedAtMs)
        assertEquals(true, MailNeedsRules.done(i))
        assertEquals(true, MailSenderRuleRules.ruleDue(i))
        assertEquals(false, MailSenderRuleRules.clearDue(i))
        // The server wrote the rule: not due again.
        i = item(*(card + MailSenderRuleRules.ruleWrittenFields(asked + 10)).toList().toTypedArray())
        assertEquals(false, MailSenderRuleRules.ruleDue(i))
        assertEquals(false, MailSenderRuleRules.clearDue(i))
        // Undo after that: the rule comes off, once.
        val undone = card + MailSenderRuleRules.ruleWrittenFields(asked + 10) + MailNeedsRules.undoDoneFields(asked + 20)
        i = item(*undone.toList().toTypedArray())
        assertEquals(false, MailSenderRuleRules.ruleDue(i))
        assertEquals(true, MailSenderRuleRules.clearDue(i))
        i = item(*(undone + MailSenderRuleRules.ruleClearedFields(asked + 30)).toList().toTypedArray())
        assertEquals(false, MailSenderRuleRules.clearDue(i))
        // Undo before the server acted: nothing to write and nothing to take off.
        i = item(*(card + MailNeedsRules.undoDoneFields(asked + 5)).toList().toTypedArray())
        assertEquals(false, MailSenderRuleRules.ruleDue(i))
        assertEquals(false, MailSenderRuleRules.clearDue(i))
        // A plain Done never asks for a rule.
        assertEquals(false, MailSenderRuleRules.ruleDue(item(*MailNeedsRules.doneFields(asked).toList().toTypedArray())))
    }

    @Test
    fun theServerMakesThePersonsSenderAndNeverOverwritesALaterRule() {
        val asked = now
        // No entity yet (a person, not a newsletter): made with the account, name and domain, never the address.
        val made = assertNotNull(MailSenderRuleRules.senderFieldsForCard(null, "Personal", "Tool Reports", "reports@tool.example", now - 1, asked))
        val s = assertNotNull(MailUnsubscribeRules.read("ms1", made))
        assertEquals("Tool Reports", s.name)
        assertEquals("tool.example", s.domain)
        assertEquals(MailSenderRule.TIDY, s.rule)
        assertEquals(UnsubscribeMethod.NONE, s.method)
        assertEquals(false, made.values.any { it == FieldValue.Text("reports@tool.example") })
        // It isn't listed under Newsletters and promos, but is under Your rules.
        assertEquals(emptyList(), MailSenderPanelRules.rows(listOf(s), "Personal", now, cal))
        // An existing newsletter sender only gets the rule.
        val newsletter = MailUnsubscribeRules.senderFields("Personal", "Weekly Digest", "digest.example.com", UnsubscribeMethod.ONE_CLICK, now, 8)
        assertEquals(setOf(MailSenderFields.RULE, MailSenderFields.RULE_AT),
            MailSenderRuleRules.senderFieldsForCard(newsletter, "Personal", "Weekly", "x@digest.example.com", now, asked)?.keys)
        // He set a rule on that sender after the tap: it stands.
        assertNull(MailSenderRuleRules.senderFieldsForCard(newsletter + MailSenderRuleRules.fields(MailSenderRule.KEEP, asked + 1), "Personal", null, null, now, asked))
        // Undo: off only while it's still the card's rule.
        assertNotNull(MailSenderRuleRules.clearFieldsForCard(made, asked, asked + 5))
        assertNull(MailSenderRuleRules.clearFieldsForCard(made + MailSenderRuleRules.fields(MailSenderRule.TIDY, asked + 1), asked, asked + 5))
        assertNull(MailSenderRuleRules.clearFieldsForCard(made + MailSenderRuleRules.fields(MailSenderRule.KEEP, asked), asked, asked + 5))
        assertNull(MailSenderRuleRules.clearFieldsForCard(null, asked, asked + 5))
    }

    @Test
    fun yourRulesListsTheAccountsRuledSendersByName() {
        fun sender(id: String, account: String, name: String, rule: MailSenderRule?) = assertNotNull(MailUnsubscribeRules.read(id,
            MailUnsubscribeRules.senderFields(account, name, null, UnsubscribeMethod.NONE, now, 1) + MailSenderRuleRules.fields(rule, now)))
        val all = listOf(
            sender("a", "Personal", "weekly Digest", MailSenderRule.KEEP),
            sender("b", "Personal", "Tool Reports", MailSenderRule.TIDY),
            sender("c", "Personal", "No Rule Ltd", null),
            sender("d", "Business", "Supplier", MailSenderRule.TIDY),
        )
        val rows = MailSenderRuleRules.rows(all, "Personal")
        assertEquals(listOf("Tool Reports", "weekly Digest"), rows.map { it.name })
        assertEquals(MailSenderRule.TIDY.line, rows[0].line)
        assertEquals(MailSenderRuleRules.CLEAR_ACTION, rows[0].action)
        assertEquals("", rows[0].sets)
        assertEquals("Your rules · 2 senders", MailSenderRuleRules.header(rows))
        assertEquals("Your rules · 1 sender", MailSenderRuleRules.header(MailSenderRuleRules.rows(all, "Business")))
        assertNull(MailSenderRuleRules.header(emptyList()))
    }
}
