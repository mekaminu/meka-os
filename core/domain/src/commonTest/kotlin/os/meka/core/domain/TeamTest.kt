package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_764L // Sat 7 Nov 2026
    private fun at(h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val now = at(18)

    private fun item(
        id: String, atMs: Long, kind: ActivityKind, summary: String, source: String? = null, why: String = "Why", mailTidy: Boolean = false,
    ) = ActivityItem(id, atMs, kind, summary, null, why, source, null, emptyList(), null, null, mailTidy = mailTidy)

    private val activity = listOf(
        item("a", at(8, 2), ActivityKind.REMINDED, "Reminded you: Bins out tonight", "notice:BINS"),
        item("b", at(8, 5), ActivityKind.SPOKE, "Said: your 08:30 is cancelled", "notice:EVENT_REMINDER"),
        item("c", at(7, 30), ActivityKind.CHANGED, "Archived 31 emails", ActivityRules.MAIL_TIDY_SOURCE, mailTidy = true),
        item("d", at(11), ActivityKind.CHANGED, "Unsubscribed from a newsletter", ActivityRules.UNSUBSCRIBE_SOURCE),
        item("e", at(10, 12), ActivityKind.SCREENED, "Blocked a known spam number"),
        item("f", at(14, 37), ActivityKind.CALL, "Took a message from an unknown caller"),
        item("g", at(9), ActivityKind.REMINDED, "Reminded you: a bill is due", "notice:BILLS"),
        item("h", at(9, 40), ActivityKind.APPROVAL, "Declined: Buy 0.05 BTC", "approvals", why = "Kestrel asked · nothing was done"),
        item("i", at(12), ActivityKind.APPROVAL, "Approved: Pay the plumber", "approvals", why = "Bank asked · you approved"),
        item("j", at(12), ActivityKind.CALENDAR, "Moved Dentist", "calendar_edit"),
        // Yesterday's: not today's work.
        item("y", at(9) - CivilDate.DAY_MS, ActivityKind.REMINDED, "Reminded you: yesterday", "notice:BINS"),
    )

    @Test fun eachEntryBelongsToOneHelper() {
        val byId = activity.associate { it.id to TeamRules.agentOf(it) }
        assertEquals(TeamAgent.OBI, byId["a"])
        assertEquals(TeamAgent.OBI, byId["b"])
        assertEquals(TeamAgent.INBOX, byId["c"])
        assertEquals(TeamAgent.INBOX, byId["d"])
        assertEquals(TeamAgent.CALLS, byId["e"])
        assertEquals(TeamAgent.CALLS, byId["f"])
        assertEquals(TeamAgent.MONEY, byId["g"])
        assertEquals(TeamAgent.KESTREL, byId["h"])
        assertNull(byId["i"], "another asker's answer is nobody's on the team")
        assertNull(byId["j"], "his own calendar edits are his")
    }

    @Test fun todaysCardsTilesAndSheets() {
        val v = TeamRules.view(
            TeamInputs(activity, requestsWaiting = 1, mailWait = "2 emails need you", moneyIdeas = 1, moneyAccounts = 6, moneyUpdated = "06:10"),
            now, cal,
        )
        assertEquals(listOf("Obi", "Inbox", "Calls", "Money", "Kestrel"), v.cards.map { it.title })
        val obi = v.card("obi")!!
        assertEquals("1 reminder · 1 said out loud", obi.today)
        assertEquals("1 card to tap", obi.wait)
        assertEquals(TeamTarget.NEEDS_YOU, obi.review)
        assertEquals(listOf("08:02", "08:05"), obi.log.map { it.time }, "oldest first, today only")
        val inbox = v.card("inbox")!!
        assertEquals("1 tidy · 1 unsubscribe", inbox.today)
        assertEquals(TeamStatus.WORKING, inbox.status)
        assertEquals("2 emails", inbox.wait)
        assertEquals("2 emails need you", inbox.waitLine)
        val calls = v.card("calls")!!
        assertEquals("1 screened · 1 message", calls.today)
        assertFalse(calls.waits)
        assertEquals(TeamRules.NOTHING, calls.wait)
        assertNull(calls.review)
        val money = v.card("money")!!
        assertEquals("6 accounts · updated 06:10 · 1 heads-up", money.today)
        assertEquals(TeamTarget.MONEY, money.review)
        val kestrel = v.card("kestrel")!!
        assertTrue(kestrel.readOnly)
        assertEquals(TeamStatus.IDLE, kestrel.status)
        assertEquals("No trades asked", kestrel.today)
        assertEquals(listOf("Declined: Buy 0.05 BTC"), kestrel.log.map { it.text })
        assertEquals(listOf("WORKING", "NEED YOU", "DONE TODAY"), v.tiles.map { it.label })
        assertEquals(listOf("5", "3", "8"), v.tiles.map { it.value })
        assertTrue(v.tiles[1].lit)
        assertEquals(3, v.needCount)
    }

    @Test fun aQuietDayAndNothingSetUp() {
        val v = TeamRules.view(TeamInputs(emptyList()), now, cal)
        assertTrue(v.cards.all { it.today == TeamRules.NOTHING_YET || it.agent == TeamAgent.KESTREL })
        assertTrue(v.cards.none { it.waits })
        assertEquals(TeamStatus.NOT_SET_UP, v.card("money")!!.status)
        assertEquals(listOf("4", "0", "0"), v.tiles.map { it.value })
        assertFalse(v.tiles[1].lit)
        assertEquals("Nobody needs you", v.tiles[1].spoken)
    }

    @Test fun kestrelsWaitingTradeIsReadOnlyAndAnsweredInNeedsYou() {
        val k = TradingKestrelView(
            head = "KESTREL", status = "1 trade waiting for your OK", lit = true, waitingTitle = "Buy 0.05 BTC at market",
            waitingLine = "About £2,410", approvalId = "p1", review = TradingKestrel.REVIEW, tally = "Today: 1 asked", spoken = "",
        )
        val c = TeamRules.view(TeamInputs(emptyList(), kestrel = k, tradesWaiting = 1), now, cal).card("kestrel")!!
        assertEquals(TeamStatus.WATCHING, c.status)
        assertEquals("1 asked", c.today)
        assertEquals("1 trade", c.wait)
        assertEquals("Buy 0.05 BTC at market · answer in Needs you", c.waitLine)
        assertEquals(TeamTarget.NEEDS_YOU, c.review)
        assertTrue(c.spoken.startsWith("Kestrel, Trading agent"))
    }

    @Test fun theLogKeepsTheDaysLastEntries() {
        val many = (0 until 12).map { item("r$it", at(6, it), ActivityKind.REMINDED, "Reminder $it", "notice:TASK_REMINDER") }
        val log = TeamRules.view(TeamInputs(many), now, cal).card("obi")!!.log
        assertEquals(TeamRules.LOG_MAX, log.size)
        assertEquals("Reminder 11", log.last().text)
    }
}
