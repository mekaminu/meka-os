package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Habits screen rebuilt to the approved design (`docs/design/habits/Habits.dc.html`). */
class HabitBoardTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private fun ids(): String = "H${n++}"
    private fun goals(d: Device) = Goals(d.replica, ::ids, { world.clock.nowMs })

    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val g = goals(fold)

    init {
        // Thu 12 Nov 2026, 08:00 UTC.
        world.clock.nowMs = CivilDate.toEpochDay(2026, 11, 12) * dayMs + 8 * 3_600_000L
    }

    private fun today() = world.clock.nowMs.floorDiv(dayMs)
    private fun view() = g.view(fold.tasks.all())
    private fun board(sel: Int = 6, list: String = HabitBoardRules.ALL) = HabitBoardRules.board(view(), sel, list)

    /** Added a week ago, so every day on the strip counts. */
    private fun added(title: String, timing: HabitTiming = HabitTiming.ANYTIME): String {
        val now = world.clock.nowMs
        world.clock.nowMs = now - 6 * dayMs
        val id = g.addHabit(title, timing = timing)
        world.clock.nowMs = now
        return id
    }

    @Test
    fun theStripIsTheSevenDaysEndingTodayWithEachDaysShareDone() {
        val teeth = added("Brush teeth", HabitTiming.MORNING)
        val water = added("Drink water", HabitTiming.MORNING)
        g.setHabitDone(teeth, true)
        g.setHabitMark(water, today() - 1, HabitMark.DONE)
        val b = board()
        assertEquals(listOf("Fri", "Sat", "Sun", "Mon", "Tue", "Wed", "Thu"), b.days.map { it.weekday })
        assertEquals(listOf(6, 7, 8, 9, 10, 11, 12), b.days.map { it.dayOfMonth })
        assertEquals(50, b.days[6].pct)
        assertEquals(50, b.days[5].pct)
        assertTrue(b.days[6].selected && b.days[6].isToday)
        assertEquals("Thu 12, 1 of 2 done", b.days[6].label)
        assertEquals("1 of 2 done today", b.doneLine)
        assertEquals(50, b.pct)
    }

    @Test
    fun theCircleCyclesDoneMissedClearAndAnEarlierDayCanBeMarked() {
        val h = added("Walk 20 minutes")
        assertEquals(HabitMark.DONE, HabitMark.CLEAR.next())
        assertEquals(HabitMark.MISSED, HabitMark.DONE.next())
        assertEquals(HabitMark.CLEAR, HabitMark.MISSED.next())
        val tue = today() - 2
        g.setHabitMark(h, tue, HabitMark.MISSED)
        var row = board(sel = 4).groups.single().rows.single()
        assertEquals(HabitMark.MISSED, row.mark)
        assertEquals("Missed, tap to clear", row.markLabel)
        assertEquals("0 of 1 done · Tue 10", board(sel = 4).doneLine)
        g.setHabitMark(h, tue, HabitMark.DONE)
        row = board(sel = 4).groups.single().rows.single()
        assertEquals(HabitMark.DONE, row.mark)
        assertEquals(listOf(HabitMark.CLEAR, HabitMark.CLEAR, HabitMark.CLEAR, HabitMark.CLEAR, HabitMark.DONE, HabitMark.CLEAR, HabitMark.CLEAR), row.week)
        g.setHabitMark(h, tue, HabitMark.CLEAR)
        assertEquals(HabitMark.CLEAR, board(sel = 4).groups.single().rows.single().mark)
        // Only the strip's days: not tomorrow, not before last Friday.
        assertFailsWith<ValidationException> { g.setHabitMark(h, today() + 1, HabitMark.DONE) }
        assertFailsWith<ValidationException> { g.setHabitMark(h, today() - 7, HabitMark.DONE) }
    }

    @Test
    fun habitsAreBoxedByListInOrderWithCountsAndWhen() {
        added("Read 10 pages", HabitTiming.EVENING)
        val teeth = added("Brush teeth", HabitTiming.MORNING)
        added("Walk", HabitTiming.AFTERNOON)
        val gym = added("Stretch")
        g.setHabitList(gym, "  gym   days ")
        g.setHabitDone(teeth, true)
        val b = board()
        assertEquals(listOf("Morning", "All day", "Night time", "gym days"), b.groups.map { it.title })
        assertEquals(listOf("before work", "any time", "before bed", ""), b.groups.map { it.whenLine })
        assertEquals("1 of 1", b.groups[0].count)
        assertEquals(listOf("All", "Morning", "All day", "Night time", "gym days"), b.chips.map { it.name })
        val night = board(list = "Night time")
        assertEquals(listOf("Night time"), night.groups.map { it.title })
        assertEquals("0 of 1 done today", night.doneLine)
        assertTrue(night.chips.single { it.selected }.name == "Night time")
        // A list that no longer exists falls back to All.
        assertEquals(4, board(list = "Weekend").groups.size)
    }

    @Test
    fun aDefaultListSetsThePartOfTheDayAndClearsHisOwn() {
        val h = added("Stretch")
        g.setHabitList(h, "Weekend")
        assertEquals("Weekend", view().habits.single().list)
        g.setHabitList(h, "night TIME")
        val item = view().habits.single()
        assertNull(item.list)
        assertEquals(HabitTiming.EVENING, item.timing)
        assertEquals("Night time", HabitBoardRules.listName(item))
        assertFailsWith<ValidationException> { g.setHabitList(h, "   ") }
        assertNull(HabitBoardRules.cleanList("all"))
    }

    @Test
    fun avoidHabitsSayAvoidAndTheirStreakCountsDaysKeptClear() {
        val h = added("No biscuits")
        g.setHabitAvoid(h, true)
        var row = board().groups.single().rows.single()
        assertTrue(row.avoid)
        assertEquals("Avoid · start today", row.meta)
        g.setHabitMark(h, today() - 2, HabitMark.DONE)
        g.setHabitMark(h, today() - 1, HabitMark.DONE)
        g.setHabitMark(h, today(), HabitMark.DONE)
        row = board().groups.single().rows.single()
        assertEquals("Avoid · 3 day streak", row.meta)
        assertEquals("best streak 3 days", board().streakLine)
        g.setHabitAvoid(h, false)
        assertEquals("Do · 3 day streak", board().groups.single().rows.single().meta)
    }

    @Test
    fun aHabitAddedTodayCountsOnlyFromToday() {
        added("Old one")
        g.addHabit("New one")
        val b = board()
        assertEquals(1, b.days[0].total)
        assertEquals(2, b.days[6].total)
        assertEquals("0 of 1 done · Fri 6", board(sel = 0).doneLine)
        // A list whose habits all started later: no "0 of 0".
        g.setHabitList(view().habits.first { it.title == "New one" }.id, "Weekend")
        val fri = board(sel = 0, list = "Weekend")
        assertEquals("No habits yet · Fri 6", fri.doneLine)
        assertEquals("", fri.groups.single().count)
    }

    @Test
    fun marksSyncAndTheLatestMarkOfADayWins() {
        val h = added("Drink water")
        fold.sync(); mac.sync()
        val onMac = goals(mac)
        g.setHabitMark(h, today(), HabitMark.DONE)
        world.clock.advance(1_000)
        onMac.setHabitMark(h, today(), HabitMark.MISSED)
        fold.sync(); mac.sync(); fold.sync()
        assertEquals(HabitMark.MISSED, board().groups.single().rows.single().mark)
        assertEquals(HabitMark.MISSED, HabitBoardRules.board(onMac.view(mac.tasks.all())).groups.single().rows.single().mark)
    }

    @Test
    fun aTapShowsAtOnceOverTheCoresView() {
        val h = added("Drink water")
        val v = view()
        assertTrue(v === HabitBoardRules.shown(v, emptyMap()))
        val shown = HabitBoardRules.shown(v, mapOf(HabitBoardRules.key(h, today() - 1) to HabitMark.MISSED))
        assertEquals(HabitMark.MISSED, shown.habits.single().recent[5])
        // Today's instant tick from the other screens lights today's dot too.
        val ticked = InstantHabitRules.shown(v, mapOf(h to true))
        assertEquals(HabitMark.DONE, ticked.habits.single().recent[6])
    }

    @Test
    fun noHabitsIsAnEmptyBoard() {
        val b = board()
        assertTrue(b.empty)
        assertEquals("No habits yet", b.doneLine)
        assertEquals("Thu 12, no habits yet", b.days[6].label)
        assertTrue(b.groups.isEmpty())
        assertNull(b.streakLine)
    }
}
