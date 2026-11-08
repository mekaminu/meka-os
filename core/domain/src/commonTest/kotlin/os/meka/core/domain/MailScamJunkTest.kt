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

    // ---- Slice 5: the 30-day look back ----

    @Test
    fun theLookBackTakesMailFromTwoToFourWeeksAgo() {
        val day = 86_400_000L
        val now = 100 * day
        assertFalse(MailScamJunkRules.inSweep(now - 3 * day, now))
        assertFalse(MailScamJunkRules.inSweep(now - 14 * day, now))
        assertTrue(MailScamJunkRules.inSweep(now - 14 * day - 1, now))
        assertTrue(MailScamJunkRules.inSweep(now - 30 * day, now))
        assertFalse(MailScamJunkRules.inSweep(now - 30 * day - 1, now))
    }

    @Test
    fun theLookBackNeverReadsHisPeoplesOrSecurityMail() {
        val day = 86_400_000L
        val now = 100 * day
        val old = now - 20 * day
        val unsure = MailSorted(MailSort.NEEDS_YOU, MailSortReason.PERSON, false)
        assertTrue(MailScamJunkRules.sweepDue(old, true, unsure, false, now))
        assertFalse(MailScamJunkRules.sweepDue(old, true, unsure, true, now))
        assertFalse(MailScamJunkRules.sweepDue(old, false, unsure, false, now))
        assertFalse(MailScamJunkRules.sweepDue(old, true, MailSorted(MailSort.NEEDS_YOU, MailSortReason.KNOWN_PERSON, true), false, now))
        assertFalse(MailScamJunkRules.sweepDue(old, true, MailSorted(MailSort.NEEDS_YOU, MailSortReason.SECURITY, true), false, now))
        assertFalse(MailScamJunkRules.sweepDue(now - day, true, unsure, false, now))
    }

    @Test
    fun theLookBacksBatchIsOnePerAccountWithItsCount() {
        assertEquals(MailScamJunkRules.sweepBatchId("a1"), MailScamJunkRules.sweepBatchId("a1"))
        assertNotEquals(MailScamJunkRules.sweepBatchId("a1"), MailScamJunkRules.sweepBatchId("a2"))
        assertNotEquals(MailScamJunkRules.sweepBatchId("a1"), MailScamJunkRules.batchId("a1", 20_000))
        assertEquals("Looked back 30 days · moved 1 scam email to Junk · Personal", MailScamJunkRules.sweepSummary(1, "Personal"))
        val f = MailScamJunkRules.sweepFields(5L, "Work", 3)
        assertEquals(os.meka.core.sync.FieldValue.Text("Looked back 30 days · moved 3 scam emails to Junk · Work"), f[MailTidyBatchFields.SUMMARY])
        val b = MailAutopilotRules.readBatch("x", f)!!
        assertEquals(3, b.count)
        assertTrue(MailScamJunkRules.scamBatch(b))
    }
}
