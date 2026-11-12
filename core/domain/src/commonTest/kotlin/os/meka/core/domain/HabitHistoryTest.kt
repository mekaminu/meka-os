package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Habits screen, slice 3: a habit's history (four weeks) and its daily reminder. */
class HabitHistoryTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private fun ids(): String = "H${n++}"

    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val g = Goals(fold.replica, ::ids, { world.clock.nowMs })
    private val cal = LocalCalendar.UTC

    init {
        // Thu 12 Nov 2026, 06:00 UTC.
        world.clock.nowMs = CivilDate.toEpochDay(2026, 11, 12) * dayMs + 6 * 3_600_000L
    }

    private fun today() = world.clock.nowMs.floorDiv(dayMs)
    private fun view() = g.view(fold.tasks.all())
    private fun row(id: String) = HabitBoardRules.board(view()).groups.flatMap { it.rows }.single { it.id == id }

    private fun addedDaysAgo(title: String, days: Int, perWeek: Int = 7): String {
        val now = world.clock.nowMs
        world.clock.nowMs = now - days * dayMs
        val id = g.addHabit(title, perWeek = perWeek)
        world.clock.nowMs = now
        return id
    }

    @Test
    fun theHistoryIsFourWeeksMondayToSundayWithTheDoneLineAndTheBestRun() {
        val h = addedDaysAgo("Drink water", 40)
        val oct20 = CivilDate.toEpochDay(2026, 10, 20)
        for (d in oct20..oct20 + 9) g.setHabitDone(h, true, d) // Tue 20 – Thu 29 Oct: ten in a row
        for (d in today() - 4..today()) g.setHabitDone(h, true, d) // Sun 8 – Thu 12 Nov
        g.setHabitMark(h, today() - 5, HabitMark.MISSED) // Sat 7 Nov
        val hist = row(h).history
        assertEquals(4, hist.weeks.size)
        assertTrue(hist.weeks.all { it.size == 7 })
        val first = hist.weeks.first().first()
        assertEquals(CivilDate.toEpochDay(2026, 10, 19), first.day) // Mon 19 Oct
        assertEquals(HabitMark.CLEAR, first.mark)
        assertEquals(HabitMark.DONE, hist.weeks[0][1].mark)
        assertEquals("Tue 20, done", hist.weeks[0][1].label)
        val week = hist.weeks.last()
        assertEquals(HabitMark.MISSED, hist.weeks.flatten().first { it.day == today() - 5 }.mark)
        assertTrue(week.first { it.day == today() }.isToday)
        assertEquals(listOf(null, null, null), week.takeLast(3).map { it.mark }) // Fri 13 – Sun 15: still to come
        assertEquals("Fri 13, still to come", week[4].label)
        assertEquals("Done 15 of the last 28 days · 1 missed", hist.doneLine)
        assertEquals("Best run 10 days", hist.bestLine)
    }

    @Test
    fun aNewerHabitCountsFromTheDayItWasAddedAndAnAvoidHabitIsKeptOff() {
        val h = addedDaysAgo("No biscuits", 2)
        g.setHabitAvoid(h, true)
        g.setHabitDone(h, true)
        val hist = row(h).history
        assertEquals("Kept off 1 of 3 days since you added it", hist.doneLine)
        assertNull(hist.bestLine)
        val before = hist.weeks.last().first { it.day == today() - 3 }
        assertNull(before.mark)
        assertEquals("Mon 9, before you added it", before.label)
        assertEquals("Thu 12, kept off", hist.weeks.last().first { it.day == today() }.label)

        val fresh = g.addHabit("Stretch")
        assertEquals("Added today · its days build up here", row(fresh).history.doneLine)
    }

    @Test
    fun theBestRunIsTheLongestRunOfDaysInARow() {
        assertEquals(0, HabitBoardRules.bestRun(emptyList()))
        assertEquals(1, HabitBoardRules.bestRun(listOf(5L)))
        assertEquals(3, HabitBoardRules.bestRun(listOf(1L, 2L, 3L, 7L, 9L, 10L)))
        assertEquals(2, HabitBoardRules.bestRun(listOf(10L, 9L, 4L)))
    }

    @Test
    fun aReminderIsSetShownAndSyncedAndOffClearsIt() {
        val h = addedDaysAgo("Brush teeth", 3)
        assertEquals(HabitBoardRules.NO_REMINDER, row(h).reminderLine)
        g.setHabitReminder(h, 7 * 60 + 30)
        assertEquals("Reminder 07:30", row(h).reminderLine)
        assertEquals(450, row(h).remindAt)
        fold.sync(); mac.sync()
        val macView = Goals(mac.replica, ::ids, { world.clock.nowMs }).view(mac.tasks.all())
        assertEquals(450, macView.habits.single().remindAt)
        g.setHabitReminder(h, null)
        assertNull(row(h).remindAt)
        assertFailsWith<ValidationException> { g.setHabitReminder(h, 24 * 60) }
        assertFailsWith<ValidationException> { g.setHabitReminder(h, -1) }
    }

    @Test
    fun theReminderComesAtItsTimeWhileTodayIsUnmarked() {
        val h = addedDaysAgo("Drink water", 3)
        val keep = addedDaysAgo("No crisps", 3)
        g.setHabitAvoid(keep, true)
        g.setHabitReminder(h, 7 * 60 + 30)
        g.setHabitReminder(keep, 21 * 60 + 30)
        addedDaysAgo("Read", 3) // no reminder: nothing
        val ns = HabitBoardRules.reminderNotices(view(), world.clock.nowMs, cal)
        assertEquals(2, ns.size)
        val water = ns.single { it.title == "Drink water" }
        assertEquals("habit-remind:$h:${today()}", water.key)
        assertEquals(NoticeSource.HABITS, water.source)
        assertEquals(cal.toEpochMs(today(), 450), water.atMs)
        assertEquals(NoticeTarget.GOALS, water.target)
        assertEquals(HabitBoardRules.REMINDER_TEXT_DO, water.text)
        assertEquals(HabitBoardRules.REMINDER_TEXT_AVOID, ns.single { it.title == "No crisps" }.text)
        assertTrue(water.expiresAtMs!! > water.atMs)

        // A tick or a cross stops it.
        g.setHabitDone(h, true)
        g.setHabitMark(keep, today(), HabitMark.MISSED)
        assertTrue(HabitBoardRules.reminderNotices(view(), world.clock.nowMs, cal).isEmpty())
    }

    @Test
    fun aWeekAlreadyMetIsNotNaggedAndNeitherIsAHabitAddedLater() {
        val twice = addedDaysAgo("Long walk", 3, perWeek = 1)
        g.setHabitDone(twice, true, today() - 1)
        g.setHabitReminder(twice, 9 * 60)
        assertTrue(HabitBoardRules.reminderNotices(view(), world.clock.nowMs, cal).isEmpty())
        assertEquals(7 * 60 + 30, HabitBoardRules.defaultReminder(HabitBoardRules.MORNING))
        assertEquals(21 * 60 + 30, HabitBoardRules.defaultReminder(HabitBoardRules.NIGHT))
        assertEquals(9 * 60, HabitBoardRules.defaultReminder("Weekend"))
    }

    @Test
    fun theReminderIsCollectedWithTheOtherNotices() {
        val h = addedDaysAgo("Drink water", 3)
        g.setHabitReminder(h, 7 * 60)
        assertTrue(HabitBoardRules.reminderNotices(view(), world.clock.nowMs, cal).any { it.key.startsWith("habit-remind:$h") })
        assertTrue(NoticeSource.HABITS in NoticeSource.ADJUSTABLE)
    }
}
