package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailScamJunkTest {
    private val scam = MailSorted(MailSort.NEEDS_YOU, MailSortReason.SCAM, true)

    private fun facts(from: String, sorted: MailSorted? = scam, keep: Set<String> = emptySet(), replied: Boolean = false, rule: MailSenderRule? = null) =
        MailTidyFacts(from, true, sorted, keep, replied = replied, rule = rule)

    @Test
    fun hisScreenshotsEmailIsSure() {
        // "Outlook Session" <formazione@formlab.it>, "Is Your Account Still Active?" (domain invented here).
        val from = "formazione@formlab.example"
        val sorted = MailSortRules.sort(MailSortFacts(from, "Outlook Session", "Is Your Account Still Active?", emptyList(), null, null))
        assertEquals(MailSortReason.SCAM, sorted.reason)
        assertTrue(MailScamJunkRules.sure(sorted, from, "Outlook Session", null))
        assertTrue(MailScamJunkRules.moves(facts(from, sorted), "Outlook Session", null))
    }

    @Test
    fun aCompanyNamedOnlyInTheSubjectStaysUnsure() {
        assertFalse(MailScamJunkRules.sure(scam, "news@deals-mail.example", "Deals Desk", null))
        // Unless the body check found it.
        assertTrue(MailScamJunkRules.sure(scam, "news@deals-mail.example", "Deals Desk", "⚠ Possible scam · says PayPal, sent from deals-mail.example"))
        assertFalse(MailScamJunkRules.sure(scam, "news@deals-mail.example", "Deals Desk", ""))
    }

    @Test
    fun theCompanysOwnDomainIsNoNameClaim() {
        assertFalse(MailScamRules.nameClaim("alerts@email.microsoft.com", "Microsoft Outlook"))
        assertTrue(MailScamRules.nameClaim("alerts@micros0ft-mail.example", "Microsoft Outlook"))
        assertFalse(MailScamRules.nameClaim("sam@example.com", "Sam"))
    }

    @Test
    fun onlyAScamSortMoves() {
        val other = MailSorted(MailSort.NEEDS_YOU, MailSortReason.AI_NEEDS_YOU, true)
        assertFalse(MailScamJunkRules.moves(facts("x@formlab.example", other), "Outlook Session", null))
        assertFalse(MailScamJunkRules.moves(facts("x@formlab.example", null), "Outlook Session", null))
        val security = MailSorted(MailSort.NEEDS_YOU, MailSortReason.SECURITY, true)
        assertFalse(MailScamJunkRules.moves(facts("x@formlab.example", security), "Outlook Session", null))
    }

    @Test
    fun anyoneHeKnowsOrKeepsIsNeverMoved() {
        val from = "x@formlab.example"
        assertFalse(MailScamJunkRules.moves(facts(from, keep = setOf(from)), "Outlook Session", null))
        assertFalse(MailScamJunkRules.moves(facts(from, replied = true), "Outlook Session", null))
        assertFalse(MailScamJunkRules.moves(facts(from, rule = MailSenderRule.KEEP), "Outlook Session", null))
        // A bank's domain is never moved, whatever it says.
        assertFalse(MailScamJunkRules.moves(facts("alerts@barclays.co.uk"), "Outlook Session", null))
        assertFalse(MailScamJunkRules.moves(MailTidyFacts(from, false, scam), "Outlook Session", null))
        // Already blocked: still moves (to Junk, not left in the inbox).
        assertTrue(MailScamJunkRules.moves(facts(from, rule = MailSenderRule.BLOCK), "Outlook Session", null))
    }

    @Test
    fun theDaysBatchAndItsLines() {
        assertEquals(MailScamJunkRules.batchId("acc", 100), MailScamJunkRules.batchId("acc", 100, 1))
        assertNotEquals(MailScamJunkRules.batchId("acc", 100), MailScamJunkRules.batchId("acc", 100, 2))
        assertNotEquals(MailScamJunkRules.batchId("acc", 100), MailScamJunkRules.batchId("acc", 101))
        val f = MailScamJunkRules.batchFields(5_000L, "Personal", 3)
        val b = MailAutopilotRules.readBatch("b", f)!!
        assertEquals("Moved 3 scam emails to Junk · Personal", b.summary)
        assertEquals(MailScamJunkRules.DETAIL_MANY, b.detail)
        assertEquals(mapOf(MailTidy.SCAM_JUNK to 3), b.kinds)
        assertTrue(MailScamJunkRules.scamBatch(b))
        assertEquals("Moved 1 scam email to Junk · Outlook", MailScamJunkRules.summary(1, "Outlook"))
        assertEquals("→ Junk", MailTidy.SCAM_JUNK.destination)
        assertEquals("Tidied 3 · 3 scam emails", ArchivedForYouRules.headline(listOf(b)))
    }

    @Test
    fun undoTakesOffOnlyTheBlockItSet() {
        val set = MailSenderRuleRules.fields(MailSenderRule.BLOCK, 2_000L)
        assertEquals(MailSenderRuleRules.fields(null, 9_000L), MailScamJunkRules.unblockFields(set, 1_000L, 5_000L, 9_000L))
        // Blocked by him before the batch, or after the Undo: stands.
        assertNull(MailScamJunkRules.unblockFields(MailSenderRuleRules.fields(MailSenderRule.BLOCK, 500L), 1_000L, 5_000L, 9_000L))
        assertNull(MailScamJunkRules.unblockFields(MailSenderRuleRules.fields(MailSenderRule.BLOCK, 6_000L), 1_000L, 5_000L, 9_000L))
        // Kept since: stands.
        assertNull(MailScamJunkRules.unblockFields(MailSenderRuleRules.fields(MailSenderRule.KEEP, 2_000L), 1_000L, 5_000L, 9_000L))
    }

    @Test
    fun theTidiedEmailSaysWhereItWent() {
        val e = MailTidiedEmail("k", MailTidy.SCAM_JUNK, null, 0L, "Outlook Session", "formlab.example", "Is Your Account Still Active?", null)
        assertEquals("Scam · Junk, sender blocked", MailTidiedRules.chip(e))
        assertEquals(MailTidy.SCAM_JUNK, MailTidiedRules.decode(MailTidiedRules.encode(listOf(e))).single().tidy)
    }
}
