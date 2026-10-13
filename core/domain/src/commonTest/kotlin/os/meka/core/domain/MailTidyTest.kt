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
        assertEquals("Navilife/Newsletters", MailTidy.NEWSLETTER.label)
        assertEquals("Navilife/Receipts", MailTidy.RECEIPT.label)
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
            "Autopilot, dry run · would tidy 52 · 30 newsletters → Navilife/Newsletters · 12 receipts and orders → Navilife/Receipts · " +
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
        assertEquals("Autopilot, dry run · would tidy 2 · 2 newsletters → Navilife/Newsletters · nothing moved yet", MailAccountRules.row(a, emptyList(), 0L).tidyLine)
        assertNull(MailAccountRules.row(a.copy(wouldTidy = null), emptyList(), 0L).tidyLine)
    }

    @Test
    fun aThreadMekaWroteInIsNeverTidied() {
        assertEquals(MailTidy.KEEP, MailTidyRules.plan(MailTidyFacts("orders@shop.example", true, sure(MailSort.RECEIPT), replied = true)))
        assertEquals(MailTidy.RECEIPT, MailTidyRules.plan(MailTidyFacts("orders@shop.example", true, sure(MailSort.RECEIPT), replied = false)))
    }

    @Test
    fun autopilotWaitsForTheSwitchTheDryRunDayAndNoPause() {
        val day = MailAutopilotRules.DRY_RUN_MS
        val connected = 1_000_000L
        val on = MailAutopilotSetting(on = true, changedAtMs = connected + 1000)
        // Off, paused, never connected, or still on its first day: nothing moves.
        assertNull(MailAutopilotRules.since(MailAutopilotSetting(false, null), false, connected, connected + 2 * day))
        assertNull(MailAutopilotRules.since(on, true, connected, connected + 2 * day))
        assertNull(MailAutopilotRules.since(on, false, null, connected + 2 * day))
        assertNull(MailAutopilotRules.since(on, false, connected, connected + day - 1))
        // After the day: only mail from the end of the dry run (or from when it was turned on, if later).
        assertEquals(connected + day, MailAutopilotRules.since(on, false, connected, connected + day))
        val later = MailAutopilotSetting(true, connected + 3 * day)
        assertEquals(connected + 3 * day, MailAutopilotRules.since(later, false, connected, connected + 4 * day))
        // Absent setting reads as off.
        assertEquals(MailAutopilotSetting(false, null), MailAutopilotRules.read(null))
        assertEquals(MailAutopilotSetting(true, 5L), MailAutopilotRules.read(MailAutopilotRules.fields(true, 5L)))
    }

    @Test
    fun aBatchSaysWhatMovedAndRoundTrips() {
        val counts = mapOf(MailTidy.NEWSLETTER to 3, MailTidy.RECEIPT to 2, MailTidy.ARCHIVE to 1, MailTidy.KEEP to 9)
        val (summary, detail) = MailAutopilotRules.lines(counts, "Personal")
        assertEquals("Tidied 6 emails · Personal", summary)
        assertEquals("3 newsletters → Navilife/Newsletters · 2 receipts and orders → Navilife/Receipts · 1 notification or promo archived", detail)
        assertEquals("Tidied 1 email · Business", MailAutopilotRules.lines(mapOf(MailTidy.ARCHIVE to 1), "Business").first)
        val id = MailAutopilotRules.batchId("acc1", 42L)
        assertEquals(id, MailAutopilotRules.batchId("acc1", 42L))
        assertTrue(id != MailAutopilotRules.batchId("acc2", 42L))
        val b = MailAutopilotRules.readBatch(id, MailAutopilotRules.batchFields(42L, "Personal", counts))!!
        assertEquals(6, b.count)
        assertEquals(summary, b.summary)
        assertTrue(b.canUndo)
        assertTrue(!MailAutopilotRules.undoDue(b, 50L))
        val asked = b.copy(undoAskedAtMs = 60L)
        assertTrue(!asked.canUndo)
        assertTrue(MailAutopilotRules.undoDue(asked, 70L))
        assertTrue(!MailAutopilotRules.undoDue(asked, 42L + 31 * 86_400_000L))
        assertTrue(!MailAutopilotRules.undoDue(asked.copy(undoneAtMs = 80L), 90L))
        assertEquals("Put back 6 emails in the inbox", MailAutopilotRules.undoneNote(6, 6))
        assertEquals("Put back 4 of 6 · 2 had gone", MailAutopilotRules.undoneNote(4, 6))
    }
}
