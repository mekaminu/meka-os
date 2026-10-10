package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Work wins, slice 1: a line a week kept for reviews, Friday's question, and the quarter week by week. */
class WorkWinsTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon5 = d(10, 5)
    private val fri9 = d(10, 9)
    private val mon12 = d(10, 12)
    private val fri16 = d(10, 16)
    private fun at(day: Long, h: Int, min: Int = 0) = cal.toEpochMs(day, h * 60 + min)
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private var n = 0
    private fun log(dev: Device) = WorkWins(dev.replica, { "w${n++}" }, { world.clock.nowMs }, cal)
    private val wFold = log(fold)
    private val wMac = log(mac)

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    init { world.clock.nowMs = at(fri16, 16, 5) }

    @Test
    fun aLineIsTidiedAndTheQuarterIsTheCalendarQuarter() {
        assertEquals("Shipped the Q3 report", WinRules.read("  - shipped   the Q3 report "))
        assertNull(WinRules.read("   "))
        assertTrue(WinRules.read("word ".repeat(80))!!.length <= WinRules.MAX_TEXT)
        assertEquals(Triple(d(10, 1), d(12, 31), "Oct – Dec"), WinRules.quarter(fri16))
        assertEquals(Triple(d(1, 1), d(3, 31), "Jan – Mar"), WinRules.quarter(d(2, 14)))
        // Today in the week in progress; a past week's win is dated its Friday.
        assertEquals(fri16, WinRules.dayFor(mon12, fri16))
        assertEquals(fri9, WinRules.dayFor(mon5, mon12))
    }

    @Test
    fun winsKeptOnTheFoldShowOnTheMacInTheirWeekAndTheQuarter() {
        val empty = wFold.view(mon12)
        assertEquals(WinRules.NONE_THIS_WEEK, empty.weekLine)
        assertEquals(WinRules.HINT_THIS_WEEK, empty.hint)
        assertTrue(empty.asking) // Friday after 16:00, nothing kept

        val added = wFold.add("closed the Henley deal", mon12)
        assertEquals("Kept for today: Closed the Henley deal", added.line)
        assertFalse(added.refused)
        assertEquals(WinAdded(WinRules.ALREADY, false), wFold.add("Closed the Henley deal.", mon12))
        assertEquals(WinAdded(WinRules.NOT_READ, true), wFold.add("  ", mon12))
        // Backfilling last week while reviewing it: dated that Friday.
        assertEquals("Kept for Fri 9 Oct: Fixed the build", wFold.add("Fixed the build", mon5).line)
        // Last quarter's win stays out of this quarter.
        world.clock.nowMs = at(d(9, 30), 12)
        wFold.add("Q3 launch", GoalRules.weekStart(d(9, 30)))
        world.clock.nowMs = at(fri16, 16, 10)

        sync()
        val v = wMac.view(mon12)
        assertFalse(v.asking)
        assertEquals("1 win this week", v.weekLine)
        assertEquals(listOf("Closed the Henley deal"), v.week.map { it.text })
        assertEquals("Fri 16 Oct", v.week.single().dayLabel)
        assertEquals("Friday 16 October: Closed the Henley deal", v.week.single().spoken)
        assertEquals("This quarter · Oct – Dec · 2 wins", v.quarterTitle)
        assertEquals(listOf("Week of 12 Oct", "Week of 5 Oct"), v.quarter.map { it.label })
        assertEquals("1 win that week", wMac.view(mon5).weekLine)
        assertEquals(WinRules.HINT_PAST_WEEK, wMac.view(mon5).hint)

        // Removed on the Mac: gone on the Fold too.
        assertTrue(wMac.remove(v.week.single().id))
        assertFalse(wMac.remove(v.week.single().id))
        sync()
        assertEquals(WinRules.NONE_THIS_WEEK, wFold.view(mon12).weekLine)
        assertEquals(1, wFold.view(mon12).quarterCount)
    }

    @Test
    fun fridaysQuestionAsksAt16UntilAWinIsKept() {
        world.clock.nowMs = at(mon12, 9)
        val notice = WinRules.notices(wFold.all(), world.clock.nowMs, cal).single()
        assertEquals(NoticeSource.WINS, notice.source)
        assertEquals(NoticeTier.HEADS_UP, notice.tier)
        assertEquals(NoticeTarget.REVIEW, notice.target)
        assertEquals(WinRules.NOTICE_TITLE, notice.title)
        assertEquals(at(fri16, 16), notice.atMs)
        assertEquals(at(fri16 + 1, 0), notice.expiresAtMs)
        assertEquals("wins:$mon12", notice.key)
        // The review isn't lit before Friday 16:00, and is through Sunday.
        assertFalse(WinRules.asking(wFold.all(), fri16, 15 * 60 + 59))
        assertTrue(WinRules.asking(wFold.all(), fri16 + 2, 20 * 60))
        // Not asked on Saturday (it was Friday's question).
        assertTrue(WinRules.notices(wFold.all(), at(fri16 + 1, 9), cal).isEmpty())

        // A win kept on Tuesday: no question that week.
        world.clock.nowMs = at(mon12 + 1, 18)
        wFold.add("Ran the planning session", mon12)
        assertTrue(WinRules.notices(wFold.all(), at(fri16, 15), cal).isEmpty())
        assertFalse(WinRules.asking(wFold.all(), fri16, 17 * 60))
        // Next week asks again.
        assertEquals(at(fri16 + 7, 16), WinRules.notices(wFold.all(), at(fri16 + 3, 9), cal).single().atMs)
    }
}
