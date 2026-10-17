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
        // Slice 3b-2: absent reads as on (Meka's decision); each account's first day is still a dry run.
        assertEquals(MailAutopilotSetting(true, null), MailAutopilotRules.read(null))
        assertNull(MailAutopilotRules.since(MailAutopilotRules.read(null), false, connected, connected + day - 1))
        assertEquals(connected + day, MailAutopilotRules.since(MailAutopilotRules.read(null), false, connected, connected + 2 * day))
        assertEquals(MailAutopilotSetting(false, 6L), MailAutopilotRules.read(MailAutopilotRules.fields(false, 6L)))
        assertEquals(MailAutopilotSetting(true, 5L), MailAutopilotRules.read(MailAutopilotRules.fields(true, 5L)))
    }

    @Test
    fun anUnsubscribedSendersNewslettersAndPromosGoToTrash() {
        fun p(s: MailSorted, from: String = "news@shop.example", replied: Boolean = false, inInbox: Boolean = true) =
            MailTidyRules.plan(MailTidyFacts(from, inInbox, s, unsubscribed = true, replied = replied))
        assertEquals(MailTidy.TRASH, p(sure(MailSort.NEWSLETTER)))
        assertEquals(MailTidy.TRASH, p(sure(MailSort.PROMO)))
        // Unsure still goes: he already said he doesn't want them.
        assertEquals(MailTidy.TRASH, p(MailSorted(MailSort.NEWSLETTER, MailSortReason.MAILING_LIST, false)))
        // A receipt or a notification from the same address is tidied as usual; a person's email stays.
        assertEquals(MailTidy.RECEIPT, p(sure(MailSort.RECEIPT)))
        assertEquals(MailTidy.ARCHIVE, p(sure(MailSort.NOTIFICATION)))
        assertEquals(MailTidy.KEEP, p(sure(MailSort.NEEDS_YOU)))
        // The never-touch rules still win.
        assertEquals(MailTidy.KEEP, p(sure(MailSort.NEWSLETTER), replied = true))
        assertEquals(MailTidy.KEEP, p(sure(MailSort.NEWSLETTER), inInbox = false))
        assertEquals(MailTidy.KEEP, p(sure(MailSort.PROMO), from = "offers@barclays.co.uk"))
        // Not unsubscribed: a newsletter is archived.
        assertEquals(MailTidy.NEWSLETTER, plan(sure(MailSort.NEWSLETTER)))
        // The lines say Trash.
        val (summary, detail) = MailAutopilotRules.lines(mapOf(MailTidy.NEWSLETTER to 1, MailTidy.TRASH to 2), "Personal")
        assertEquals("Tidied 3 emails · Personal", summary)
        assertEquals("1 newsletter → Navilife/Newsletters · 2 from blocked or unsubscribed senders → Trash", detail)
        assertEquals("1 from a blocked or unsubscribed sender → Trash", MailAutopilotRules.lines(mapOf(MailTidy.TRASH to 1), "Personal").second)
        val b = MailAutopilotRules.readBatch("t", MailAutopilotRules.batchFields(1L, "Personal", mapOf(MailTidy.TRASH to 2)))!!
        assertEquals(mapOf(MailTidy.TRASH to 2), b.kinds)
        assertEquals("Tidied 2 · 2 from blocked or unsubscribed senders", ArchivedForYouRules.headline(listOf(b)))
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
        // Slice 4: each kind's count rides along for "Archived for you" (KEEP never).
        assertEquals(mapOf(MailTidy.NEWSLETTER to 3, MailTidy.RECEIPT to 2, MailTidy.ARCHIVE to 1), b.kinds)
        assertEquals(emptyMap(), MailAutopilotRules.readBatch(id, MailAutopilotRules.batchFields(42L, "Personal", counts)
            .filterKeys { it !in setOf(MailTidyBatchFields.NEWSLETTERS, MailTidyBatchFields.RECEIPTS, MailTidyBatchFields.ARCHIVED) })!!.kinds)
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

    // ---- Slice 3b-2: the apps' half ----

    private val hour = 3_600_000L
    private val today = 20_000L * 86_400_000L
    private fun batch(id: String, atMs: Long, account: String = "Personal", count: Int = 3, asked: Long? = null, undone: Long? = null, note: String? = null) =
        MailTidyBatch(id, atMs, account, "Tidied $count emails · $account", "3 newsletters → Navilife/Newsletters", count, asked, undone, note)

    @Test
    fun thePanelSaysWhatAutopilotDoesAndWhenItIsPaused() {
        val on = MailAutopilotPanelRules.panel(MailAutopilotSetting(true, null), paused = false)
        assertEquals(MailAutopilotPanelRules.ON_LINE, on.line)
        assertEquals("Turn off", on.action)
        assertEquals(MailAutopilotPanelRules.PAUSED_LINE, MailAutopilotPanelRules.panel(MailAutopilotSetting(true, null), paused = true).line)
        val off = MailAutopilotPanelRules.panel(MailAutopilotSetting(false, 1L), paused = true)
        assertEquals(MailAutopilotPanelRules.OFF_LINE, off.line)
        assertEquals("Turn on", off.action)
        assertTrue(MailAutopilotPanelRules.switchedLine(false).startsWith("Autopilot is off"))
    }

    @Test
    fun anAccountLineCountsTodaysTidiesThatStillStand() {
        val on = MailAutopilotSetting(true, null)
        val now = today + 15 * hour
        val todays = MailAutopilotPanelRules.todays(
            listOf(batch("a", today + 9 * hour), batch("b", today + 10 * hour, count = 4), batch("c", today + 11 * hour, asked = now),
                batch("d", today - hour), batch("e", today + 12 * hour, account = "Business")),
            "Personal", now, LocalCalendar.UTC,
        )
        assertEquals(listOf("a", "b", "c"), todays.map { it.id })
        assertEquals("Autopilot · on · tidied 7 today", MailAutopilotPanelRules.accountLine(on, false, null, todays))
        assertEquals("Autopilot · on · nothing tidied today · new mail is tidied as it arrives", MailAutopilotPanelRules.accountLine(on, false, null, emptyList()))
        // Off or paused: the dry run's line, as before.
        val would = mapOf("NEWSLETTER" to 2)
        assertEquals(MailTidyRules.dryRunLine(would), MailAutopilotPanelRules.accountLine(MailAutopilotSetting(false, 1L), false, would, todays))
        assertEquals(MailTidyRules.dryRunLine(would), MailAutopilotPanelRules.accountLine(on, true, would, todays))
        // Completeness: on, but the sign-in ended: paused until Reconnect (off or the kill switch still say the dry run).
        assertEquals(MailAutopilotPanelRules.SIGN_IN_ENDED_LINE, MailAutopilotPanelRules.accountLine(on, false, would, todays, signInEnded = true))
        assertEquals(MailTidyRules.dryRunLine(would), MailAutopilotPanelRules.accountLine(on, true, would, todays, signInEnded = true))
    }

    @Test
    fun mailSaysAutopilotIsPausedOnAnAccountWhoseSignInEnded() {
        val on = MailAutopilotSetting(true, null)
        val now = today + 15 * hour
        fun facts(email: String, label: String, status: String) = MailAccountFacts(MailProviders.GMAIL, email, status, now - 60_000L, label)
        val v = MailAccountRules.view(
            listOf(facts("alex@example.com", "PERSONAL", "needs_reconnect"), facts("sam@example.com", "BUSINESS", "ok")),
            now, emptyList(), on, false, listOf(batch("a", today + 9 * hour), batch("b", today + 10 * hour, account = "Business")), LocalCalendar.UTC,
        )
        assertEquals(MailAutopilotPanelRules.SIGN_IN_ENDED_LINE, v.rows.first { it.label == MailLabel.PERSONAL }.tidyLine)
        assertTrue(v.rows.first { it.label == MailLabel.PERSONAL }.needsReconnect)
        assertEquals("Autopilot · on · tidied 3 today", v.rows.first { it.label == MailLabel.BUSINESS }.tidyLine)
    }

    @Test
    fun tidyRowsAreTheLastWeekNewestFirstWithUndoAndTheirState() {
        val now = today + 15 * hour
        val rows = MailAutopilotPanelRules.rows(
            listOf(
                batch("old", today - 8 * 86_400_000L),
                batch("y", today - 14 * hour),
                batch("t", today + 9 * hour + 5 * 60_000L),
                batch("asked", today + 10 * hour, asked = now - 60_000L),
                batch("done", today + 11 * hour, asked = now - 120_000L, undone = now - 60_000L, note = "Put back 3 emails in the inbox"),
            ),
            now, LocalCalendar.UTC,
        )
        assertEquals(listOf("done", "asked", "t", "y"), rows.map { it.id })
        assertEquals("09:05", rows[2].time)
        assertTrue(rows[3].time.endsWith(" · 10:00"), rows[3].time)
        assertTrue(rows[2].canUndo)
        assertEquals(null, rows[2].state)
        assertTrue(!rows[1].canUndo)
        assertEquals(MailAutopilotPanelRules.PUTTING_BACK, rows[1].state)
        assertEquals("Put back 3 emails in the inbox", rows[0].state)
        assertTrue(!rows[0].canUndo)
    }

    @Test
    fun mailViewCarriesTheSwitchTheLinesAndTheTidies() {
        val now = today + 15 * hour
        val facts = listOf(
            MailAccountFacts(MailProviders.GMAIL, "alex@example.com", "ok", now - 60_000L, "PERSONAL", mapOf("NEWSLETTER" to 3), mapOf("NEWSLETTER" to 3)),
            MailAccountFacts(MailProviders.OUTLOOK, "alex@example.net", "ok", now - 60_000L, "OUTLOOK"),
        )
        val v = MailAccountRules.view(facts, now, emptyList(), MailAutopilotSetting(true, null), false, listOf(batch("t", today + 9 * hour)), LocalCalendar.UTC)
        assertEquals(MailAutopilotPanelRules.ON_LINE, v.autopilot?.line)
        assertEquals("Autopilot · on · tidied 3 today", v.rows[0].tidyLine)
        assertEquals(listOf("t"), v.rows[0].tidies.map { it.id })
        assertTrue(v.rows[1].tidies.isEmpty())
        assertNull(MailAccountRules.view(emptyList(), now, emptyList(), MailAutopilotSetting(true, null), false, emptyList(), LocalCalendar.UTC).autopilot)
        // The old overload keeps the dry-run line and no switch.
        assertNull(MailAccountRules.view(facts, now).autopilot)
    }

    @Test
    fun whatNavilifeMayDoHasTheAutopilotRow() {
        val v = AutonomyRules.view(emptyMap(), AutonomySetting(false, null), LocalCalendar.UTC, today, MailAutopilotSetting(true, null))
        val row = v.sections.flatMap { it.rows }.single { it.key == AutonomyRules.MAIL_AUTOPILOT }
        assertEquals("Inbox autopilot", row.title)
        assertEquals("Turn off", row.action)
        assertTrue(row.onItsOwn)
        assertEquals(AutonomyRules.NONE_LINE, v.summary)
        val paused = AutonomyRules.view(emptyMap(), AutonomySetting(true, 1L), LocalCalendar.UTC, today, MailAutopilotSetting(true, null))
        val p = paused.sections.flatMap { it.rows }.single { it.key == AutonomyRules.MAIL_AUTOPILOT }
        assertEquals(AutonomyRules.PAUSED_LINE, p.line)
        assertTrue(!p.onItsOwn)
        val off = AutonomyRules.mailRow(MailAutopilotSetting(false, 1L), false)
        assertEquals(AutonomyRules.MAIL_OFF_LINE, off.line)
        assertEquals("Turn on", off.action)
    }

    @Test
    fun tidiedMailIsMarkedReadUnlessHeTurnedItOffAndARescueNeverIs() {
        assertTrue(MailAutopilotRules.read(null).markRead)
        assertTrue(MailAutopilotRules.read(MailAutopilotRules.fields(true, 5L)).markRead)
        val off = MailAutopilotRules.read(MailAutopilotRules.fields(true, 5L) + MailAutopilotRules.markReadFields(false))
        assertEquals(false, off.markRead)
        assertTrue(off.on)
        for (t in listOf(MailTidy.NEWSLETTER, MailTidy.RECEIPT, MailTidy.ARCHIVE, MailTidy.TRASH, MailTidy.JUNK_TRASH)) assertTrue(t.marksRead, t.name)
        assertEquals(false, MailTidy.RESCUE.marksRead)
        assertEquals(false, MailTidy.KEEP.marksRead)
    }
}
