package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The unread count went up (2026-10-18): Today names the accounts it reads and the ones it can't; Health shows each. */
class MailHealthTest {
    private val now = 1_790_985_600_000L
    private val min = 60_000L
    private val cal = LocalCalendar.UTC

    private val outlook = MailAccountFacts(MailProviders.OUTLOOK, "alex@example.com", "ok", now - 3 * min, "PERSONAL",
        inboxTotal = 12_437, inboxUnread = 136)
    private val gmail = MailAccountFacts(MailProviders.GMAIL, "alex@example.org", "error", null, "PERSONAL", why = MailCheckRules.API_OFF)
    private val business = MailAccountFacts(MailProviders.GMAIL, "work@example.net", "error", null, "BUSINESS", why = MailCheckRules.API_OFF)

    private fun backlog(account: String, unread: Int?, applied: Int = 0, approved: Boolean = false, finished: Boolean = false, putBack: Int = 0) = MailBacklog(
        id = "mb-$account", account = account, startedAtMs = now - 86_400_000L, scanned = 4_000, readyAtMs = now - 80_000_000L,
        counts = mapOf(MailBacklogKind.ARCHIVE to 3_000, MailBacklogKind.JUNK to 505, MailBacklogKind.KEEP to 200), top = null,
        approvedAtMs = if (approved) now - 70_000_000L else null, applied = applied, finishedAtMs = if (finished) now - 1_000L else null,
        putBack = putBack, inboxUnread = unread, tidyUnread = 0, unreadCountedAtMs = now - 5 * min,
    )

    private fun tidy(account: String, at: Long, count: Int, backlog: Boolean = false, undoAsked: Long? = null) = MailTidyBatch(
        id = "t-$at", atMs = at, account = account, summary = "", detail = null, count = count,
        undoAskedAtMs = undoAsked, undoneAtMs = null, undoNote = null, backlog = backlog,
    )

    @Test
    fun todayNamesTheAccountItReadsAndTheOnesItCant() {
        val l = MailUnreadRules.todayLine(listOf(backlog("Personal", 1_075)), now, listOf(outlook, gmail, business))!!
        // The server's fresh count (136), not the backlog's older 1,075; the label alone would read as his Gmail.
        assertEquals("Personal Outlook · 136 unread · Personal Gmail and Business Gmail not read yet", l.text)
        assertEquals("136 unread emails in Personal Outlook. Navilife can't read Personal Gmail and Business Gmail yet.", l.spoken)
        // Nothing countable at all: the accounts it can't read, never silence.
        assertEquals("Mail · Personal Gmail and Business Gmail not read yet · see Health",
            MailUnreadRules.todayLine(emptyList(), now, listOf(gmail, business))!!.text)
        // Every account read: as before, with "Outlook" alone when that's its label.
        val o = outlook.copy(label = "OUTLOOK")
        assertEquals("Outlook · 136 unread", MailUnreadRules.todayLine(listOf(backlog("Outlook", 140)), now, listOf(o))!!.text)
        // Without the account list (an older server): exactly the old line.
        assertEquals("Personal · 1,075 unread", MailUnreadRules.todayLine(listOf(backlog("Personal", 1_075)), now)!!.text)
    }

    @Test
    fun healthHasARowPerMailAccount() {
        val tidies = listOf(
            tidy("Personal", now, 14), tidy("Personal", now - 2 * 86_400_000L, 50),
            tidy("Personal", now - 60 * min, 3_505, backlog = true),
        )
        val rows = MailHealthRules.healthRows(listOf(business, outlook, gmail), listOf(backlog("Personal", 1_075, applied = 3_505, approved = true, finished = true)), tidies, now, cal)
        assertEquals(listOf("Mail · Personal Gmail", "Mail · Personal Outlook", "Mail · Business Gmail"), rows.map { it.title })
        val o = rows[1]
        assertEquals("Inbox 12,437 · 136 unread · tidied today 14 · clean-up 3,505 done · checked 3 min ago", o.line)
        assertEquals(HealthState.OK, o.state)
        val g = rows[0]
        assertEquals(HealthState.BAD, g.state)
        assertTrue(g.line.startsWith("Never read yet, so nothing here has been tidied · The Gmail API is off"), g.line)
        // A sign-in that ended says Reconnect.
        val ended = MailHealthRules.healthRows(listOf(outlook.copy(status = "needs_reconnect")), emptyList(), emptyList(), now, cal).single()
        assertEquals(HealthState.BAD, ended.state)
        // Health's own Today line leaves mail to Today's unread line.
        val view = HealthRules.view(HealthFacts(
            device = DeviceHealth(mac = true), connected = true, lastSyncedMs = now, server = null, accounts = emptyList(), ai = null,
            callAssistantOn = false, nowMs = now, mail = listOf(gmail),
        ), cal)
        assertTrue(view.rows.any { it.key.startsWith(MailHealthRules.KEY_PREFIX) })
        assertTrue(view.todayLine?.contains("Gmail") != true)
    }

    @Test
    fun theCleanUpsStateAndTheReportsCounts() {
        assertNull(MailHealthRules.backlogState(null))
        assertEquals("clean-up waiting for your Approve (3,505)", MailHealthRules.backlogState(backlog("Personal", 0)))
        assertEquals("clean-up 1,200 of 3,505 done · 2,305 left", MailHealthRules.backlogState(backlog("Personal", 0, applied = 1_200, approved = true)))
        assertEquals("clean-up undone · 900 put back", MailHealthRules.backlogState(backlog("Personal", 0, applied = 900, approved = true, putBack = 900)))
        val tidies = listOf(tidy("Personal", now - 30 * min, 14), tidy("Personal", now - 10 * min, 2, undoAsked = now - 5 * min), tidy("Business", now, 9))
        assertEquals("tidied 24h 16 · clean-up 3,505 done · undo 72h 1",
            MailHealthRules.reportExtra("Personal", backlog("Personal", 0, applied = 3_505, approved = true, finished = true), tidies, now))
        val r = MailCheckRules.Report("Personal", MailProviders.OUTLOOK, "ok", null, now, now, 12_437, 136, extra = "tidied 24h 16")
        assertTrue(MailCheckRules.reportLine(r, now) { "t" }.startsWith("Personal Outlook · ok · checked t · ok t · inbox 12437 · 136 unread"))
        assertTrue(MailCheckRules.reportLine(r, now) { "t" }.endsWith("tidied 24h 16"))
    }

    @Test
    fun ciAndServiceAlertsAreNotifications() {
        val ci = MailSortRules.sort(MailSortFacts("notifications@github.com", "Alex", "[alex/app] Run failed: ci - main (abc123)",
            listOf("INBOX", "UNREAD"), "<mailto:unsub@github.com>", "<app.alex.github.com>"))
        assertEquals(MailSort.NOTIFICATION, ci.sort)
        assertEquals(MailSortReason.SERVICE_ALERT, ci.reason)
        assertTrue(ci.sure)
        assertEquals(MailSort.NOTIFICATION, MailSortRules.sort(MailSortFacts("no-reply@vinted.co.uk", "Vinted", "Sam favourited your item", listOf("INBOX"), null, null)).sort)
        // A person at the same company is still a person.
        assertEquals(MailSort.NEEDS_YOU, MailSortRules.sort(MailSortFacts("sam@github.com", "Sam", "Quick question", listOf("INBOX"), null, null)).sort)
    }
}
