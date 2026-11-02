package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ada plans with you, part 3 slice 2: the Inbox keeper and School & clubs as standing errands ([WorkingOnRules]). */
class WorkingOnInboxSchoolTest {
    private val now = 1_790_985_600_000L + 12 * 3_600_000L
    private val hour = 3_600_000L
    private val cal = LocalCalendar.UTC
    private val today = cal.epochDayOf(now)

    private val outlook = MailAccountFacts(MailProviders.OUTLOOK, "alex@example.com", "ok", now - 60_000L, "PERSONAL")
    private val gmail = MailAccountFacts(MailProviders.GMAIL, "alex@example.org", "error", null, "PERSONAL", why = MailCheckRules.API_OFF)

    private fun tidy(at: Long, count: Int, backlog: Boolean = false, undone: Boolean = false) = MailTidyBatch(
        id = "t-$at", atMs = at, account = "Personal", summary = "", detail = null, count = count,
        undoAskedAtMs = null, undoneAtMs = if (undone) at + 1 else null, undoNote = null, backlog = backlog,
    )

    private fun waitingBacklog() = MailBacklog(
        id = "mb", account = "Personal", startedAtMs = now - 2 * hour, scanned = 4_000, readyAtMs = now - hour,
        counts = mapOf(MailBacklogKind.ARCHIVE to 3_000, MailBacklogKind.KEEP to 200), top = null, approvedAtMs = null,
    )

    private fun needs(n: Int) = MailNeedsCard(
        title = "From your mail", headline = "$n emails need you",
        rows = (1..n).map { MailNeedsRow("m$it", "Sam", "Personal · 09:12", "Subject $it", null, false, "row $it") },
        moreLine = null, snoozedLine = null,
    )

    @Test
    fun theInboxKeeperReportsTodaysTidiesAndWhatNeedsHim() {
        assertNull(WorkingOnRules.inbox(emptyList(), emptyList(), null, null, now, cal))
        val tidies = listOf(
            tidy(now - hour, 9), tidy(now - 2 * hour, 5),
            tidy(now - 3 * 24 * hour, 20),
            tidy(now - hour, 3_505, backlog = true), // the clean-up is not today's autopilot
            tidy(now - hour, 4, undone = true), // put back: not counted
            tidy(now - 9 * 24 * hour, 100), // older than a week
        )
        val calm = WorkingOnRules.inbox(tidies, emptyList(), listOf(outlook), null, now, cal)!!
        assertEquals("Tidied 14 emails today · 34 this week · reading 1 of 1 account", calm.did)
        assertNull(calm.need)
        assertEquals(StandingErrand.INBOX, calm.errand)

        // Order of what needs him: an Approve first, then the emails that need him (counts only), then accounts it can't read.
        assertEquals("1 mail clean-up waits for your Approve in Needs you",
            WorkingOnRules.inbox(tidies, listOf(waitingBacklog()), listOf(outlook, gmail), needs(3), now, cal)!!.need)
        assertEquals("3 emails need you · in Needs you", WorkingOnRules.inbox(tidies, emptyList(), listOf(outlook, gmail), needs(3), now, cal)!!.need)
        val unread = WorkingOnRules.inbox(emptyList(), emptyList(), listOf(outlook, gmail), null, now, cal)!!
        assertEquals("Nothing tidied yet · reading 1 of 2 accounts", unread.did)
        assertEquals("I can't read Personal Gmail yet · Mail says why", unread.need)
        // No subject or sender ever reaches the card (untrusted mail stays on its own card).
        assertTrue("Subject" !in WorkingOnRules.inbox(tidies, emptyList(), listOf(outlook), needs(2), now, cal)!!.spoken)
        assertEquals("Nothing to tidy yet today · 20 emails this week",
            WorkingOnRules.inbox(listOf(tidy(now - 3 * 24 * hour, 20)), emptyList(), null, null, now, cal)!!.did)
    }

    private fun schoolDay(title: String, inDays: Long, who: String = "") = SchoolItem(
        id = title, kind = SchoolKind.DAY, title = title, who = who, startDay = today + inDays, endDay = today + inDays,
        weekday = 0, cover = null, coverDays = emptyList(), addedAtMs = 0L,
    )

    private val week = KidWeek("3 things in the next 7 days · 1 today or tomorrow", listOf(
        KidWeekDay(today + 1, "Tomorrow", listOf(
            KidWeekItem("Alex: Training 17:00", false, "x", fromCalendar = true),
            KidWeekItem("Alex: PE kit", true, "x"),
        )),
        KidWeekDay(today + 3, "Thu 5 Nov", listOf(KidWeekItem("Sam: Swimming kit", false, "x"))),
    ))

    @Test
    fun schoolAndClubsSaysTheWeekAndAsksWhoIsCovering() {
        assertNull(WorkingOnRules.school(null, emptyList(), emptyList(), today))
        val calm = WorkingOnRules.school(week, emptyList(), emptyList(), today)!!
        // The first thing named is his own (a club session's title is the calendar's words, never quoted).
        assertEquals("3 things in the next 7 days · 1 today or tomorrow · next: Tomorrow, Alex: PE kit", calm.did)
        assertNull(calm.need)
        assertEquals("Nothing for the children in the next 7 days",
            WorkingOnRules.school(KidWeek(KidsWeekRules.EMPTY, emptyList()), emptyList(), emptyList(), today)!!.did)

        val trip = schoolDay("Trip payment", 2, who = "Sam")
        assertEquals("Sam: Trip payment ${RenewalRules.dayWord(today + 2, today)} · pay, sign or send it back by then",
            WorkingOnRules.school(week, emptyList(), listOf(schoolDay("Non-uniform day", 1), trip, schoolDay("Consent form", 12)), today)!!.need)
        val cover = SchoolCover("c", "Alex and Sam are off Mon 9 Nov", "INSET day · in 5 days",
            "You're in the office that day. Who's covering?", "d", listOf(today + 5), spoken = "s")
        assertEquals("Alex and Sam are off Mon 9 Nov · You're in the office that day. Who's covering?",
            WorkingOnRules.school(week, listOf(cover), listOf(trip), today)!!.need)
    }

    @Test
    fun theNewErrandsJoinTheViewAndSwitchOff() {
        val inbox = WorkingOnRules.inbox(emptyList(), emptyList(), listOf(gmail), null, now, cal)
        val school = WorkingOnRules.school(week, emptyList(), emptyList(), today)
        val v = WorkingOnRules.view(WorkingOnSetting(), RenewalsView.EMPTY, GiftsView.EMPTY, emptyList(), today, inbox, school)
        assertEquals(listOf(StandingErrand.INBOX, StandingErrand.SCHOOL), v.cards.map { it.errand })
        assertEquals("Keeping an eye on 2 things · 1 needs you", v.summary)
        val off = WorkingOnRules.view(WorkingOnSetting(setOf(StandingErrand.INBOX)), RenewalsView.EMPTY, GiftsView.EMPTY, emptyList(), today, inbox, school)
        assertEquals(listOf(StandingErrand.SCHOOL), off.cards.map { it.errand })
        val lines = WorkingOnRules.askLines(off, full = true)
        assertTrue(lines.contains("Inbox keeper · off"), lines.toString())
        assertTrue(lines.any { it.startsWith("School & clubs · 3 things in the next 7 days") }, lines.toString())
        assertEquals(StandingErrand.SCHOOL, StandingErrand.of("school"))
        assertEquals(StandingErrand.INBOX, StandingErrand.of("inbox"))
    }
}
