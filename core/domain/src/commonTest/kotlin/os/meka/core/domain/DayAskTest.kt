package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The companion, slice 4: the days a question names, looked up on the device in his calendar and tasks. */
class DayAskTest {
    private val tue = CivilDate.toEpochDay(2026, 10, 20) // a Tuesday
    private val day = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC

    private fun r(q: String, today: Long = tue) = DayAskRules.range(q, today)
    private fun d(m: Int, dd: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, dd)

    @Test
    fun theQuestionsDaysAreRead() {
        assertEquals(AskDays(tue + 1, tue + 1), r("what's on tomorrow?"))
        assertEquals(AskDays(tue + 2, tue + 2), r("anything the day after tomorrow?"))
        assertEquals(AskDays(tue - 1, tue - 1), r("what did I do yesterday?"))
        assertEquals(AskDays(d(10, 22), d(10, 22)), r("what's on Thursday?"))
        assertEquals(AskDays(d(10, 22), d(10, 22)), r("am I busy on thurs"))
        assertEquals(AskDays(d(10, 29), d(10, 29)), r("what about next Thursday?"))
        assertEquals(AskDays(d(10, 26), d(10, 26)), r("free monday morning?"))
        assertEquals(AskDays(d(10, 24), d(10, 25)), r("am I free this weekend?"))
        assertEquals(AskDays(d(10, 24), d(10, 25)), r("what's happening at the weekend"))
        assertEquals(AskDays(d(10, 31), d(11, 1)), r("plans next weekend?"))
        assertEquals(AskDays(d(10, 26), d(11, 1)), r("what have I got next week?"))
        assertEquals(AskDays(tue, d(10, 25)), r("how busy is this week?"))
        assertEquals(AskDays(d(10, 23), d(10, 23)), r("anything on the 23rd?"))
        assertEquals(AskDays(d(11, 3), d(11, 3)), r("what's on the 3rd?"))
        assertEquals(AskDays(d(11, 14), d(11, 14)), r("what's on 14 November?"))
        assertEquals(AskDays(d(11, 14), d(11, 14)), r("Nov 14th?"))
        assertEquals(AskDays(d(12, 2), d(12, 2)), r("am I free 2/12"))
        assertEquals(AskDays(d(3, 1, 2027), d(3, 1, 2027)), r("1st of March?"))
    }

    @Test
    fun todayAloneAndWordsThatArentDaysCarryNothing() {
        assertNull(r("what's on today?"))
        assertNull(r("what's on Tuesday?")) // today is a Tuesday
        assertNull(r("why is the sun hot?"))
        assertNull(r("I sat down and thought about it"))
        assertNull(r("explain how a heat pump works"))
        assertNull(r("support that's 24/7"))
        // At the weekend, "this weekend" is what's left of it.
        val sat = d(10, 24)
        assertEquals(AskDays(sat, sat + 1), r("this weekend", sat))
        assertNull(r("this weekend", sat + 1))
    }

    @Test
    fun theDaysCarryTheirEventsAndTasksAndSayWhenTheyreClear() {
        val world = SyncWorld()
        val fold = world.device("android")
        val today = world.clock.nowMs.floorDiv(day)
        world.clock.nowMs = today * day + 10 * 60 * 60_000L
        fun ms(dd: Long, minute: Int) = dd * day + minute * 60_000L
        fun ev(id: String, title: String, dd: Long, from: Int, to: Int, account: String = "alex@example.com") =
            CalendarEvent(id, title, ms(dd, from), ms(dd, to), false, null, "google", account, "Personal")
        val dentist = ev("g1", "Dentist", today + 2, 15 * 60, 16 * 60)
        val standup = ev("g2", "Standup", today, 9 * 60, 9 * 60 + 15)
        val away = CalendarEvent("g3", "Away", ms(today + 3, 0), ms(today + 5, 0), true, null, "google", "alex@example.com", "Family")
        val far = ev("g4", "Far off", today + 20, 9 * 60, 10 * 60)
        val mot = fold.tasks.create(NewTask("Book MOT"))
        fold.tasks.setWhen(mot, today + 2, 10 * 60)
        val todays = fold.tasks.create(NewTask("Today's job"))
        fold.tasks.setWhen(todays, today, 12 * 60)
        val range = AskDays(today + 1, today + 4)
        val events = DayAskRules.events(range, listOf(standup, far, away, dentist), today, cal)
        val tasks = DayAskRules.tasks(range, fold.tasks.all(), today, cal)
        assertEquals(listOf("g1", "g3"), events.map { it.id })
        assertEquals(listOf(mot), tasks.map { it.id })

        val lines = DayAskRules.askLines(range, events, tasks, today, cal)
        assertEquals("Days asked about · ${CivilDate.shortLabel(today + 1)} – ${CivilDate.shortLabel(today + 4)} · 2 events · 1 task", lines[0])
        assertEquals("${CivilDate.shortLabel(today + 1)} · nothing in the calendar and no tasks", lines[1])
        assertEquals("${CivilDate.shortLabel(today + 2)} · 1 event · 1 task", lines[2])
        assertEquals("${CivilDate.shortLabel(today + 3)} · 1 event", lines[3])
        assertEquals("${CivilDate.shortLabel(today + 4)} · 1 event", lines[4])
        assertEquals("${CivilDate.shortLabel(today + 3)} – ${CivilDate.shortLabel(today + 4)} · all day · Away · Family", DayAskRules.eventLine(away, range, cal))

        // In the context: the event Navilife may move and the task keep handles; the event makes it untrusted.
        val project = TodayProjection.project(fold.tasks.all(), world.clock.nowMs, DayWindow(today * day, (today + 1) * day)).copy(events = listOf(standup))
        val ctx = AskRules.context(project, world.clock.nowMs, cal, days = range, dayLines = lines, dayEvents = events, dayTasks = tasks,
            movable = { e -> EventAskRules.movable(e, world.clock.nowMs, cal) { _, _ -> true } })
        val sent = ctx.items.map { "${it.ref}|${it.kind.wire}|${it.line}" }
        assertTrue(sent.any { it.startsWith("|days|Days asked about") }, sent.toString())
        assertTrue("e1|event|${CivilDate.shortLabel(today + 2)} 15:00–16:00 · Dentist · Personal" in sent, sent.toString())
        assertTrue(sent.any { it.matches(Regex("t\\d\\|task\\|Book MOT · planned ${CivilDate.shortLabel(today + 2)} · 10:00")) }, sent.toString())
        assertEquals("g1", ctx.eventRefs["e1"]?.eventId)
        assertTrue(mot in ctx.taskIds.values)
        assertTrue(ctx.untrusted)
        // Today's event isn't sent twice.
        assertEquals(1, sent.count { "Standup" in it })
    }

    @Test
    fun aDayGoneByShowsWhatWasDoneAndADayFarAheadSaysTheCalendarIsntReadYet() {
        val world = SyncWorld()
        val fold = world.device("android")
        val today = world.clock.nowMs.floorDiv(day)
        world.clock.nowMs = (today - 1) * day + 15 * 60 * 60_000L
        val rang = fold.tasks.create(NewTask("Ring the school"))
        fold.tasks.complete(rang)
        world.clock.nowMs = today * day + 9 * 60 * 60_000L
        val y = AskDays(today - 1, today - 1)
        val done = DayAskRules.tasks(y, fold.tasks.all(), today, cal)
        assertEquals(listOf(rang), done.map { it.id })
        assertEquals(listOf("Days asked about · ${CivilDate.shortLabel(today - 1)} · 1 done"), DayAskRules.askLines(y, emptyList(), done, today, cal))
        assertEquals("done ${CivilDate.shortLabel(today - 1)}", DayAskRules.taskWhen(done.single(), today, cal))

        val far = AskDays(today + 40, today + 40)
        val lines = DayAskRules.askLines(far, emptyList(), emptyList(), today, cal)
        assertEquals("${CivilDate.shortLabel(today + 40)} · no tasks · the calendar isn't read this far ahead yet", lines.last())
        assertFalse(lines.first().contains("not counting"))
    }
}
