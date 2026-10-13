package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailBacklogTest {
    private val now = 1_790_985_600_000L
    private val day = 86_400_000L
    private fun sure(s: MailSort) = MailSorted(s, MailSortReason.MAILING_LIST, true)
    private fun plan(
        s: MailSorted?, ageDays: Long = 45, from: String? = "news@shop.example", inInbox: Boolean = true,
        keep: Set<String> = emptySet(), replied: Boolean = false,
    ) = MailBacklogRules.plan(MailBacklogFacts(from, inInbox, s, now - ageDays * day, keep, replied), now)

    @Test
    fun olderTidyKindMailIsPlannedLikeAutopilot() {
        assertEquals(MailBacklogKind.NEWSLETTER, plan(sure(MailSort.NEWSLETTER)))
        assertEquals(MailBacklogKind.RECEIPT, plan(sure(MailSort.RECEIPT)))
        assertEquals(MailBacklogKind.ARCHIVE, plan(sure(MailSort.NOTIFICATION)))
        assertEquals(MailBacklogKind.ARCHIVE, plan(sure(MailSort.PROMO)))
        assertEquals(MailTidy.NEWSLETTER, MailBacklogKind.NEWSLETTER.tidy)
        assertEquals(MailTidy.TRASH, MailBacklogKind.JUNK.tidy)
    }

    @Test
    fun onlyMailOlderThanThirtyDaysIsArchived() {
        assertEquals(MailBacklogKind.KEEP, plan(sure(MailSort.NEWSLETTER), ageDays = 29))
        assertEquals(MailBacklogKind.NEWSLETTER, plan(sure(MailSort.NEWSLETTER), ageDays = 30))
        // Junk goes whatever its age.
        assertEquals(MailBacklogKind.JUNK, plan(sure(MailSort.JUNK), ageDays = 1))
    }

    @Test
    fun peopleProtectedSendersHisThreadsAndUnsureMailAreKept() {
        for (s in listOf(MailSort.NEEDS_YOU, MailSort.FYI)) assertEquals(MailBacklogKind.KEEP, plan(sure(s)), s.name)
        assertEquals(MailBacklogKind.KEEP, plan(null))
        assertEquals(MailBacklogKind.KEEP, plan(MailSorted(MailSort.NEWSLETTER, MailSortReason.MAILING_LIST, false)))
        assertEquals(MailBacklogKind.KEEP, plan(sure(MailSort.NEWSLETTER), inInbox = false))
        assertEquals(MailBacklogKind.KEEP, plan(sure(MailSort.NOTIFICATION), from = "alerts@barclays.co.uk"))
        assertEquals(MailBacklogKind.KEEP, plan(sure(MailSort.JUNK), from = "office@oakfield.sch.uk"))
        assertEquals(MailBacklogKind.KEEP, plan(sure(MailSort.PROMO), from = "alex@example.com", keep = setOf("alex@example.com")))
        assertEquals(MailBacklogKind.KEEP, plan(sure(MailSort.RECEIPT), replied = true))
    }

    @Test
    fun theBusiestSendersAreNamed() {
        val movers = List(5) { "Weekly Digest" to "editor@weekly.example" } + List(3) { null to "deals@oakfield.example" } +
            List(3) { "Alpha" to "a@alpha.example" } + listOf("Zed" to "z@zed.example")
        assertEquals("Weekly Digest 5 · Alpha 3 · oakfield.example 3", MailBacklogRules.topSenders(movers))
        assertNull(MailBacklogRules.topSenders(emptyList()))
    }

    @Test
    fun theFieldsRoundTripAndTheRowSaysThePlan() {
        val counts = mapOf(MailBacklogKind.NEWSLETTER to 2100, MailBacklogKind.RECEIPT to 900, MailBacklogKind.ARCHIVE to 412,
            MailBacklogKind.JUNK to 640, MailBacklogKind.KEEP to 212)
        val f = MailBacklogRules.fields("Personal", now, 4264, counts, "Weekly Digest 412", readyAtMs = now + 1)
        val b = assertNotNull(MailBacklogRules.read("mb1", f))
        assertTrue(b.ready)
        assertEquals(4052, b.toMove)
        assertNull(b.approvedAtMs)
        val r = MailBacklogRules.row(b)
        assertEquals("Archive 3,412 · Trash 640 junk · keep 212 that may need you", r.line)
        assertEquals(
            "2,100 newsletters → Navilife/Newsletters · 900 receipts and orders → Navilife/Receipts · " +
                "412 notifications and promos archived · only mail older than 30 days\n" +
                "Junk → Trash, recoverable for 30 days\nMost from Weekly Digest 412",
            r.detail,
        )
        assertEquals(MailBacklogRules.NOTE, r.note)
        // A recount can lower a kind to zero: every kind is written.
        val again = MailBacklogRules.read("mb1", MailBacklogRules.fields("Personal", now, 10, mapOf(MailBacklogKind.KEEP to 3), null, now))!!
        assertEquals(mapOf(MailBacklogKind.KEEP to 3), again.counts)
        assertNull(again.top)
        assertEquals("Nothing to clean up · keep 3 that may need you", MailBacklogRules.row(again).line)
    }

    @Test
    fun whileScanningTheRowSaysHowFarItGot() {
        val b = MailBacklogRules.read("mb1", MailBacklogRules.fields("Business", now, 1240, emptyMap(), null, null))!!
        assertFalse(b.ready)
        val r = MailBacklogRules.row(b)
        assertEquals("Looking through the older mail in the inbox · 1,240 so far…", r.line)
        assertNull(r.detail)
        assertFalse(r.ready)
        assertEquals(r, MailBacklogRules.rowFor(listOf(b), "Business"))
        assertNull(MailBacklogRules.rowFor(listOf(b), "Personal"))
        assertEquals(MailBacklogRules.entityId("acc1"), MailBacklogRules.entityId("acc1"))
        assertTrue(MailBacklogRules.entityId("acc1") != MailBacklogRules.entityId("acc2"))
    }

    @Test
    fun mailShowsEachAccountsPlan() {
        val facts = listOf(MailAccountFacts(MailProviders.GMAIL, "a@example.com", "ok", now, "PERSONAL"))
        val b = MailBacklogRules.read("mb1", MailBacklogRules.fields("Personal", now, 5, mapOf(MailBacklogKind.NEWSLETTER to 5), null, now))!!
        val v = MailAccountRules.view(facts, now, emptyList(), MailAutopilotSetting(true, null), false, emptyList(), LocalCalendar.UTC, emptyList(), listOf(b))
        assertEquals("Archive 5", v.rows.single().backlog?.line)
        val none = MailAccountRules.view(facts, now, emptyList(), MailAutopilotSetting(true, null), false, emptyList(), LocalCalendar.UTC)
        assertNull(none.rows.single().backlog)
    }
}
