package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandCentreTest {
    private val hour = 3_600_000L
    private val cal = LocalCalendar.fixedOffset(hour)
    private val tue6 = CivilDate.toEpochDay(2026, 10, 6)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private fun ev(id: String, from: Long, to: Long, allDay: Boolean = false, provider: String = "google") =
        CalendarEvent(id, id, from, to, allDay, null, provider, null, null)

    private fun allDay(id: String, day: Long) = ev(id, day * CivilDate.DAY_MS, (day + 1) * CivilDate.DAY_MS, allDay = true)

    private fun task(id: String, scheduled: Long? = null) =
        Task(id, id, null, Lifecycle.ACTIVE, null, scheduled, 30, 0, null, null, 0, null, false)

    private fun view(tasks: List<Task> = emptyList(), events: List<CalendarEvent> = emptyList()) =
        CalendarAgenda.build(tasks, events, at(tue6, 10), cal)

    @Test
    fun widthPicksTheLayout() {
        assertEquals(CommandLayout.SINGLE, CommandCentreRules.layout(412f))
        assertEquals(CommandLayout.SINGLE, CommandCentreRules.layout(599.9f))
        assertEquals(CommandLayout.TWO, CommandCentreRules.layout(600f))
        // The open Fold upright, after the rail.
        assertEquals(CommandLayout.TWO, CommandCentreRules.layout(620f))
        assertEquals(CommandLayout.THREE, CommandCentreRules.layout(900f))
        assertEquals(CommandLayout.THREE, CommandCentreRules.layout(1200f))
    }

    @Test
    fun anOpenTaskTakesNeedsYousPlace() {
        assertEquals(listOf(CommandColumn.TODAY), CommandCentreRules.columns(CommandLayout.SINGLE, taskOpen = true))
        assertEquals(listOf(CommandColumn.TODAY), CommandCentreRules.columns(CommandLayout.SINGLE, taskOpen = false))
        assertEquals(
            listOf(CommandColumn.TODAY, CommandColumn.NEEDS_YOU_AND_COMING_UP),
            CommandCentreRules.columns(CommandLayout.TWO, taskOpen = false),
        )
        assertEquals(listOf(CommandColumn.TODAY, CommandColumn.DETAIL), CommandCentreRules.columns(CommandLayout.TWO, taskOpen = true))
        assertEquals(
            listOf(CommandColumn.TODAY, CommandColumn.NEEDS_YOU, CommandColumn.COMING_UP),
            CommandCentreRules.columns(CommandLayout.THREE, taskOpen = false),
        )
        assertEquals(
            listOf(CommandColumn.TODAY, CommandColumn.DETAIL, CommandColumn.COMING_UP),
            CommandCentreRules.columns(CommandLayout.THREE, taskOpen = true),
        )
    }

    @Test
    fun needsYouIsNeverListedTwice() {
        assertTrue(CommandCentreRules.todayListsNeedsYou(CommandLayout.SINGLE))
        assertFalse(CommandCentreRules.todayListsNeedsYou(CommandLayout.TWO))
        assertFalse(CommandCentreRules.todayListsNeedsYou(CommandLayout.THREE))
        assertEquals(0f, CommandCentreRules.sideShare(CommandLayout.SINGLE))
        assertTrue(CommandCentreRules.sideShare(CommandLayout.THREE) > CommandCentreRules.sideShare(CommandLayout.TWO))
    }

    @Test
    fun headingCountsLikeTheBadge() {
        assertEquals("Needs you", CommandCentreRules.needsYouHeading(0))
        assertEquals("Needs you · 3", CommandCentreRules.needsYouHeading(3))
        assertEquals("Needs you · 9+", CommandCentreRules.needsYouHeading(12))
    }

    @Test
    fun comingUpStartsTomorrowSkipsEmptyDaysAndCuts() {
        val v = view(
            tasks = listOf(task("Send invoice", at(tue6 + 1, 11)), task("Today's task", at(tue6, 15))),
            events = listOf(
                ev("Standup", at(tue6, 9), at(tue6, 9, 15)),                 // today: Today's own timeline
                ev("Dentist", at(tue6 + 1, 9), at(tue6 + 1, 9, 30)),
                allDay("Bank holiday", tue6 + 1),
                ev("Gym", at(tue6 + 3, 7), at(tue6 + 3, 8)),
                ev("A", at(tue6 + 4, 9), at(tue6 + 4, 10)),
                ev("B", at(tue6 + 4, 10), at(tue6 + 4, 11)),
                ev("C", at(tue6 + 4, 11), at(tue6 + 4, 12)),
                ev("D", at(tue6 + 4, 12), at(tue6 + 4, 13)),
                ev("Late", at(tue6 + 9, 18), at(tue6 + 9, 19)),
            ),
        )
        val c = CommandCentreRules.comingUp(v, maxDays = 3, maxLines = 3)
        assertNull(c.emptyLine)
        assertEquals(listOf("Tomorrow", "Fri 9 Oct", "Sat 10 Oct"), c.days.map { it.title })
        val tomorrow = c.days[0]
        // All-day first, then events and planned tasks in time order.
        assertEquals(listOf("Bank holiday", "Dentist", "Send invoice"), tomorrow.lines.map { it.title })
        assertEquals(listOf("All day", "09:00–09:30", "11:00"), tomorrow.lines.map { it.time })
        assertEquals(listOf(false, false, true), tomorrow.lines.map { it.isTask })
        assertEquals("Dentist", tomorrow.lines[1].event?.id)
        assertNull(tomorrow.lines[2].event)
        assertNull(tomorrow.moreLine)
        // Thursday is empty and folded away; Saturday has four and shows three.
        assertEquals(listOf("A", "B", "C"), c.days[2].lines.map { it.title })
        assertEquals("+1 more", c.days[2].moreLine)
        // No day shown twice, no today, stable ids.
        assertEquals(c.days.map { it.id }.distinct(), c.days.map { it.id })
        assertTrue(c.days.none { it.epochDay == tue6 })
        assertEquals(c.days.flatMap { d -> d.lines.map { it.id } }.distinct().size, c.days.sumOf { it.lines.size })
        // Alone in its column it shows more.
        val alone = CommandCentreRules.comingUp(v, CommandCentreRules.DAYS_ALONE, CommandCentreRules.LINES_ALONE)
        assertEquals(listOf("Tomorrow", "Fri 9 Oct", "Sat 10 Oct", "Thu 15 Oct"), alone.days.map { it.title })
        assertNull(alone.days[2].moreLine)
    }

    @Test
    fun hiddenEventsAndAnEmptyWindowSayNothingPlanned() {
        val c = CommandCentreRules.comingUp(view(events = listOf(ev("Standup", at(tue6, 9), at(tue6, 9, 15)))), 3, 3)
        assertTrue(c.days.isEmpty())
        assertEquals("Nothing planned in the next 30 days", c.emptyLine)
    }

    private fun item(id: String, topic: String) = NewsItem(id, id, null, "Sport", topic, "Sport · 1 h ago", null, 0L)
    private fun lane(topic: String, vararg ids: String) = NewsLane(topic, topic, "From Sport", ids.map { item(it, topic) })

    @Test
    fun newsUnderComingUpTakesTheTopStoryOfEachLaneInTurn() {
        val place = NewsPlace(listOf(lane("barca", "b1", "b2", "b3"), lane("ai", "a1"), lane("top", "t1", "t2")), emptyList(), null)
        assertEquals(listOf("b1", "a1"), CommandCentreRules.newsGlance(place, CommandCentreRules.NEWS_SHARED)!!.items.map { it.id })
        assertEquals(listOf("b1", "a1", "t1", "b2"), CommandCentreRules.newsGlance(place, CommandCentreRules.NEWS_ALONE)!!.items.map { it.id })
        assertEquals(listOf("b1", "a1", "t1", "b2", "t2", "b3"), CommandCentreRules.newsGlance(place, 10)!!.items.map { it.id })
        assertNull(CommandCentreRules.newsGlance(place, 2)!!.matchday)
    }

    @Test
    fun newsUnderComingUpIsLeftOutWhenThereIsNothingAndKeepsTheMatch() {
        assertNull(CommandCentreRules.newsGlance(NewsPlace.EMPTY, CommandCentreRules.NEWS_ALONE))
        assertNull(CommandCentreRules.newsGlance(NewsPlace(emptyList(), emptyList(), "No topics chosen · pick some below"), 4))
        val e = ev("espn-1", at(tue6, 21), at(tue6, 23), provider = "fixtures")
        val md = NewsMatchday("espn-1", "Barça v Real Madrid", "Barça v Real Madrid · 21:00 · in 11 h", false, e)
        val g = CommandCentreRules.newsGlance(NewsPlace(emptyList(), emptyList(), null, md), 4)!!
        assertEquals(md, g.matchday)
        assertTrue(g.items.isEmpty())
    }
}
