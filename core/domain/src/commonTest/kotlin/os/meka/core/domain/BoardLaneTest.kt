package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week board, slice 4: each week's lane of tasks with no day yet, and the Someday tray. */
class BoardLaneTest {
    private val dayMs = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    // Wed 21 Oct 2026, 10:00 UTC; this week starts Mon 19 Oct.
    private val today = CivilDate.toEpochDay(2026, 10, 21)
    private val monday = today - 2
    private val now = today * dayMs + 10 * 3_600_000L

    private fun task(
        id: String, lifecycle: Lifecycle = Lifecycle.ACTIVE, scheduled: Long? = null, created: Long = 0, rank: Long? = null,
        week: Long? = null, kind: SomedayKind? = null, rule: String? = null, occurrence: Long? = null,
    ) = Task(
        id, id, null, lifecycle, null, scheduled, null, 0, null, kind, created, null, false,
        recurrenceRule = rule, occurrenceDay = occurrence, boardRank = rank, boardWeek = week,
    )

    @Test
    fun parkedTasksSitInTheirWeeksLaneNotOnADayAndEarlierWeeksJoinThisOne() {
        val b = WeekBoardRules.build(
            listOf(
                task("thisWeek", week = monday, created = 2),
                task("old", week = monday - 14, created = 1),
                task("lastWeek", week = monday - 7, created = 3),
                task("next", week = monday + 7),
                task("dayed", week = monday, scheduled = today * dayMs + 15 * 3_600_000L),
                task("plain"),
            ),
            emptyList(), now, cal,
        )
        assertEquals(listOf("old", "lastWeek", "thisWeek"), b.weeks[0].lane.map { it.id })
        assertEquals("From 2 weeks ago", b.weeks[0].lane[0].line)
        assertEquals("From last week", b.weeks[0].lane[1].line)
        assertNull(b.weeks[0].lane[2].line)
        assertEquals("3 to place", b.weeks[0].laneLine)
        assertEquals(listOf("next"), b.weeks[1].lane.map { it.id })
        assertEquals(WeekBoardRules.LANE_EMPTY, b.weeks[2].laneLine)
        assertEquals("This week, no day yet, 3 tasks", b.weeks[0].laneAccessibilityLabel)
        // A day wins over a stale week; a task with no week sits on Today as before.
        assertEquals(setOf("dayed", "plain"), b.day(today)!!.cards.map { it.id }.toSet())
        assertTrue(b.days.none { d -> d.cards.any { it.id in setOf("thisWeek", "old", "lastWeek", "next") } })
    }

    @Test
    fun theSomedayTrayHoldsSomedayTasksNewestFirstWithTheirKind() {
        val b = WeekBoardRules.build(
            listOf(
                task("a", Lifecycle.SOMEDAY, created = 1, kind = SomedayKind.BOOK),
                task("b", Lifecycle.SOMEDAY, created = 2),
                task("open"),
            ),
            emptyList(), now, cal,
        )
        assertEquals(listOf("b", "a"), b.someday.map { it.id })
        assertEquals("To read", b.someday[1].line)
        assertEquals("Ideas", b.someday[0].line)
        assertEquals("Someday · 2", b.somedayTitle)
        assertEquals("Someday", WeekBoardRules.build(emptyList(), emptyList(), now, cal).somedayTitle)
    }

    @Test
    fun parkedTasksStayOffTodayButStillComeUpWhenOverdue() {
        val window = DayWindow(today * dayMs, (today + 1) * dayMs)
        val t = TodayProjection.project(listOf(task("parked", week = monday), task("plain")), now, window)
        assertEquals("plain", t.upNext?.id)
        assertTrue(t.yourDay.none { it.id == "parked" })
    }

    @Test
    fun parkAndMoveWorkOutPlacesAndRefuseRepeatsAndNoChange() {
        val b = WeekBoardRules.build(
            listOf(
                task("x", week = monday, created = 1),
                task("y", week = monday, created = 2),
                task("dayed", scheduled = (today + 1) * dayMs + 9 * 3_600_000L),
                task("rep", rule = "FREQ=DAILY", occurrence = today),
                task("idea", Lifecycle.SOMEDAY),
            ),
            emptyList(), now, cal,
        )
        val park = assertNotNull(WeekBoardRules.park(b, "dayed", monday, 1))
        assertEquals(BoardSource.DAY, park.from)
        assertEquals(mapOf("dayed" to 2_000L, "y" to 3_000L, "x" to 1_000L), park.ranks)
        assertEquals("Moved “Dayed” to This week", WeekBoardRules.parkLine("dayed", park, b, false))
        assertNull(WeekBoardRules.park(b, "rep", monday, 0), "a repeat belongs to its days")
        assertNull(WeekBoardRules.park(b, "x", monday, 0), "already there")
        val up = assertNotNull(WeekBoardRules.park(b, "y", monday, 0))
        assertEquals("Moved “Y” up", WeekBoardRules.parkLine("y", up, b, true))
        assertEquals("Moved “Idea” to Next week", WeekBoardRules.parkLine("idea", WeekBoardRules.park(b, "idea", monday + 7, 0)!!, b, false))

        val fromLane = assertNotNull(WeekBoardRules.move(b, "x", today + 2, 0, today, cal))
        assertEquals(BoardSource.WEEK, fromLane.from)
        assertTrue(fromLane.dayChanged)
        assertNull(fromLane.minuteOfDay)
        val fromTray = assertNotNull(WeekBoardRules.move(b, "idea", today, 0, today, cal))
        assertEquals(BoardSource.SOMEDAY, fromTray.from)

        assertTrue(WeekBoardRules.canSomeday(b, "x"))
        assertFalse(WeekBoardRules.canSomeday(b, "rep"))
        assertFalse(WeekBoardRules.canSomeday(b, "idea"))
    }

    @Test
    fun parkingSomedayAndBackSyncAndUndo() {
        val world = SyncWorld()
        val d = world.device("android")
        val nowMs = world.clock.nowMs
        val todayDay = cal.epochDayOf(nowMs)
        val mon = todayDay - (CivilDate.isoDayOfWeek(todayDay) - 1)
        val a = d.tasks.create(NewTask("Book MOT", scheduledAtMs = nowMs + 26 * 3_600_000L))
        fun board() = WeekBoardRules.build(d.tasks.all(), emptyList(), nowMs, cal)

        // Into this week's lane: no day, no time, off the day.
        val undoPark = d.tasks.parkOnBoard(WeekBoardRules.park(board(), a, mon, 0)!!)
        val parked = d.tasks.get(a)!!
        assertNull(parked.scheduledAtMs)
        assertEquals(mon, parked.boardWeek)
        assertEquals(listOf(a), board().weeks[0].lane.map { it.id })
        val mac = world.device("mac")
        d.sync(); mac.sync()
        assertEquals(listOf(a), WeekBoardRules.build(mac.tasks.all(), emptyList(), nowMs, cal).weeks[0].lane.map { it.id })

        assertTrue(d.tasks.undoBoard(undoPark))
        assertNull(d.tasks.get(a)!!.boardWeek)
        assertEquals(todayDay + 1, cal.epochDayOf(d.tasks.get(a)!!.scheduledAtMs!!))

        // Onto the Someday tray, then back onto a day from it.
        val undoSomeday = d.tasks.somedayOnBoard(a)
        assertEquals(Lifecycle.SOMEDAY, d.tasks.get(a)!!.lifecycle)
        assertEquals(listOf(a), board().someday.map { it.id })
        assertTrue(d.tasks.undoBoard(undoSomeday))
        assertEquals(Lifecycle.ACTIVE, d.tasks.get(a)!!.lifecycle)
        assertNull(d.tasks.get(a)!!.somedayKind)

        d.tasks.somedayOnBoard(a)
        val back = WeekBoardRules.move(board(), a, todayDay + 2, 0, todayDay, cal)!!
        val undoBack = d.tasks.placeOnBoard(back)
        assertEquals(Lifecycle.ACTIVE, d.tasks.get(a)!!.lifecycle)
        assertEquals(listOf(a), board().day(todayDay + 2)!!.cards.map { it.id })
        assertTrue(d.tasks.undoBoard(undoBack))
        assertEquals(Lifecycle.SOMEDAY, d.tasks.get(a)!!.lifecycle)
    }
}
