package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Life audit, slice 2: the review adds up what the mail turned up, says what was read, and never acts. */
class LifeAuditReviewTest {
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    /** Fri 16 Oct 2026, 10:00 UTC. */
    private val today = d(10, 16)
    private val cal = LocalCalendar.UTC
    private val now = cal.toEpochMs(today, 10 * 60)

    private fun email(id: String, from: String, day: Long, reply: Boolean = false, account: String = "Personal") = MailItem(
        id = id, account = account, from = from, subject = "Saturday", preview = null, receivedAtMs = cal.toEpochMs(day, 9 * 60),
        threadCount = 1, goneAtMs = null, doneAtMs = null, undoneAtMs = null, doneAskedAtMs = null, undoAskedAtMs = null,
        snoozedUntilMs = null, replyNeeded = reply,
    )

    private fun radar(id: String, title: String, kind: ObligationKind, due: Long, pence: Long?, repeat: RenewalRepeat?) = RenewalItem(
        id = id, title = title, kind = kind, subject = "Personal mail", notes = null, dueDay = due, cancelByDay = null, leadDays = 3,
        costPence = pence, repeats = repeat, repeatLabel = "", state = RenewalState.LATER, meta = "", doneLabel = "Done",
        stopLabel = "Stop tracking", hasConflict = false,
    )

    private fun sub(id: String, merchant: String, pence: Long, repeat: RenewalRepeat = RenewalRepeat.MONTHLY, account: String = "Personal") = MailFind(
        id, LifeAuditRules.KIND_SUBSCRIPTION, account, merchant, d(11, 8), pence, ObligationKind.SUBSCRIPTION, repeat, d(10, 8),
        "Your receipt", MailFindState.OPEN, 0, seen = 2,
    )

    private fun waiting(id: String) = WaitingItem(id, "Signed contract", "Sam", null, now, null, null, DueState.LATER, "", false)

    @Test
    fun nothingConnectedNoReview() {
        assertNull(LifeAuditReviewRules.review(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), now, cal))
    }

    @Test
    fun aQuietMailboxSaysSo() {
        val r = LifeAuditReviewRules.review(listOf("Personal"), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), now, cal)!!
        assertEquals("Life audit", r.title)
        assertEquals("Nothing needs you from 60 days of mail ✓", r.summary)
        assertEquals(LifeAuditReviewRules.KEYS, r.lines.map { it.key })
        assertTrue(r.lines.all { it.clear })
        assertEquals("None · you're up to date", r.lines[1].line)
        assertTrue(r.read.startsWith("60 days of your Personal mail · sender, subject and the short preview, never the whole email"))
        assertTrue(r.read.endsWith("nothing in your mail was changed"))
    }

    @Test
    fun everythingAddsUp() {
        val items = listOf(
            email("1", "Alex Green", d(10, 6), reply = true),
            email("2", "Sam Taylor", d(10, 12), reply = true),
            email("3", "Alex Green", d(10, 14), reply = true), // the same person once
            email("4", "Newsroom", d(10, 15)),
            email("5", "Old", d(7, 1)), // past the window: not counted
        )
        val renewals = listOf(
            radar("s1", "Musicly", ObligationKind.SUBSCRIPTION, d(11, 1), 1199, RenewalRepeat.MONTHLY),
            radar("s2", "Cloud Co", ObligationKind.SUBSCRIPTION, d(3, 1) + 365, 12000, RenewalRepeat.YEARLY),
            radar("w1", "Warranty ends · Shopco order", ObligationKind.WARRANTY, d(12, 3), 25000, null),
            radar("w0", "Warranty ends · Old order", ObligationKind.WARRANTY, d(10, 1), 9000, null), // ended: not counted
            radar("b1", "Water bill", ObligationKind.BILL, d(10, 20), 4210, null),
            radar("b2", "Energy bill", ObligationKind.BILL, d(11, 5), null, null),
            radar("b3", "Council tax", ObligationKind.BILL, d(12, 30), 15000, null), // past 30 days
        )
        val finds = listOf(sub("f1", "Streamco", 1099))
        val r = LifeAuditReviewRules.review(
            listOf("Personal", "Business"), items, finds, renewals, listOf(waiting("w")), emptyList(), emptyList(), now, cal,
        )!!
        // £11.99 + £120/12 (£10) + £10.99 found.
        assertEquals("2 on Renewals · 1 found to add · about £32.98 a month", r.lines[0].line)
        assertEquals("Needs you · Lists → Renewals", r.lines[0].place)
        assertEquals("4 emails · 3 need a reply, from Alex Green and Sam Taylor · oldest Tue 6 Oct", r.lines[1].line)
        assertEquals("1 on Waiting for", r.lines[2].line)
        assertEquals("1 warranty · the next ends Thu 3 Dec · Shopco order", r.lines[3].line)
        assertEquals("2 bills due in the next 30 days · £42.10 known · the next Tue 20 Oct · Water bill", r.lines[4].line)
        assertEquals("3 subscriptions · 4 emails waiting on you · 2 bills due", r.summary)
        assertTrue(r.read.startsWith("60 days of your Personal and Business mail"))
        assertTrue(r.spoken.contains("Subscriptions. 2 on Renewals"))
        assertTrue(r.spoken.endsWith("Navilife never pays, cancels or replies on its own."))
    }

    @Test
    fun aSubscriptionAlreadyAddedIsNotCountedTwice() {
        val f = sub("f1", "Streamco", 1099)
        val radarFields = listOf(MailRenewalRules.obligationFields(f, now, cal))
        val onRadar = listOf(radar("s", "Streamco", ObligationKind.SUBSCRIPTION, d(11, 8), 1099, RenewalRepeat.MONTHLY))
        val r = LifeAuditReviewRules.review(listOf("Personal"), emptyList(), listOf(f), onRadar, emptyList(), radarFields, emptyList(), now, cal)!!
        assertEquals("1 on Renewals · about £10.99 a month", r.lines[0].line)
        assertEquals("Lists → Renewals", r.lines[0].place)
    }

    @Test
    fun namesAndAccounts() {
        assertEquals("Alex", LifeAuditReviewRules.names(listOf("Alex")))
        assertEquals("Alex and Sam", LifeAuditReviewRules.names(listOf("Alex", "Sam")))
        assertEquals("Alex, Sam and 2 more", LifeAuditReviewRules.names(listOf("Alex", "Sam", "Jo", "Kit")))
        val facts = listOf(MailAccountFacts("outlook", "a@example.com", "ok", null, "OUTLOOK"), MailAccountFacts("google", "b@example.com", "ok", null, "PERSONAL"))
        assertEquals(listOf("Personal", "Business", "Outlook"), LifeAuditReviewRules.accounts(facts, listOf("Business", "Personal")))
        assertEquals("Personal, Business and Outlook", LifeAuditReviewRules.accountsLabel(listOf("Personal", "Business", "Outlook")))
        assertEquals(1000L, LifeAuditReviewRules.monthlyPence(12000, RenewalRepeat.YEARLY))
        assertNull(LifeAuditReviewRules.monthlyPence(500, RenewalRepeat.NONE))
    }
}
