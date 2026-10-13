package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailTidyTest {
    private fun sure(s: MailSort) = MailSorted(s, MailSortReason.MAILING_LIST, true)
    private fun plan(s: MailSorted?, from: String? = "news@shop.example", inInbox: Boolean = true, keep: Set<String> = emptySet()) =
        MailTidyRules.plan(MailTidyFacts(from, inInbox, s, keep))

    @Test
    fun eachTidyBucketHasItsAction() {
        assertEquals(MailTidy.NEWSLETTER, plan(sure(MailSort.NEWSLETTER)))
        assertEquals(MailTidy.RECEIPT, plan(sure(MailSort.RECEIPT)))
        assertEquals(MailTidy.ARCHIVE, plan(sure(MailSort.NOTIFICATION)))
        assertEquals(MailTidy.ARCHIVE, plan(sure(MailSort.PROMO)))
        assertEquals("MEKA/Newsletters", MailTidy.NEWSLETTER.label)
        assertEquals("MEKA/Receipts", MailTidy.RECEIPT.label)
        assertNull(MailTidy.ARCHIVE.label)
    }

    @Test
    fun needsYouFyiAndJunkAreNeverTidied() {
        for (s in listOf(MailSort.NEEDS_YOU, MailSort.FYI, MailSort.JUNK)) assertEquals(MailTidy.KEEP, plan(sure(s)), s.name)
    }

    @Test
    fun unsortedUnsureOrOutOfTheInboxStays() {
        assertEquals(MailTidy.KEEP, plan(null))
        assertEquals(MailTidy.KEEP, plan(MailSorted(MailSort.NEWSLETTER, MailSortReason.MAILING_LIST, false)))
        assertEquals(MailTidy.KEEP, plan(sure(MailSort.NEWSLETTER), inInbox = false))
    }

    @Test
    fun protectedSendersStayWhateverTheyWereSortedAs() {
        for (from in listOf("alerts@barclays.co.uk", "news@email.monzo.com", "x@tax.service.gov.uk", "office@oakfield.sch.uk", "Info@NHS.net")) {
            assertEquals(MailTidy.KEEP, plan(sure(MailSort.NOTIFICATION), from = from), from)
            assertTrue(MailTidyRules.protectedSender(from, emptySet()), from)
        }
        // A kept address (his partner's, in his data) stays too, case and angle brackets aside.
        assertEquals(MailTidy.KEEP, plan(sure(MailSort.NEWSLETTER), from = "<Sam@Example.com>", keep = setOf("sam@example.com")))
        // Only the real domain counts.
        assertEquals(MailTidy.ARCHIVE, plan(sure(MailSort.PROMO), from = "deals@notbarclays.co.uk"))
    }

    @Test
    fun theDryRunLineCountsInOrderAndSaysNothingMoved() {
        assertNull(MailTidyRules.dryRunLine(null))
        assertEquals(MailTidyRules.NOTHING_TO_TIDY, MailTidyRules.dryRunLine(emptyMap()))
        assertEquals(MailTidyRules.NOTHING_TO_TIDY, MailTidyRules.dryRunLine(mapOf("KEEP" to 40)))
        assertEquals(
            "Autopilot, dry run · would tidy 52 · 30 newsletters → MEKA/Newsletters · 12 receipts and orders → MEKA/Receipts · " +
                "10 notifications and promos archived · nothing moved yet",
            MailTidyRules.dryRunLine(mapOf("ARCHIVE" to 10, "RECEIPT" to 12, "NEWSLETTER" to 30, "KEEP" to 7)),
        )
        assertEquals(
            "Autopilot, dry run · would tidy 1 · 1 notification or promo archived · nothing moved yet",
            MailTidyRules.dryRunLine(mapOf("ARCHIVE" to 1, "NEWSLETTER" to 0)),
        )
    }

    @Test
    fun anAccountsRowCarriesTheDryRun() {
        val a = MailAccountFacts("gmail", "alex@example.com", "ok", 0L, "PERSONAL", sorted = mapOf("NEWSLETTER" to 2), wouldTidy = mapOf("NEWSLETTER" to 2))
        assertEquals("Autopilot, dry run · would tidy 2 · 2 newsletters → MEKA/Newsletters · nothing moved yet", MailAccountRules.row(a, emptyList(), 0L).tidyLine)
        assertNull(MailAccountRules.row(a.copy(wouldTidy = null), emptyList(), 0L).tidyLine)
    }
}
