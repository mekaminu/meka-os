package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week board, item (10): pressure warnings — a day stacking up, its line and its one-tap fixes. */
class BoardPressureTest {
    private val dayMs = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    // Wed 21 Oct 2026, 10:00 UTC.
    private val today = CivilDate.toEpochDay(2026, 10, 21)
    private val now = today * dayMs + 10 * 3_600_000L
    private val thu = today + 1
    private val fri = today + 2

    private fun at(day: Long, hour: Int, minute: Int = 0) = day * dayMs + hour * 3_600_000L + minute * 60_000L

    private fun task(
        id: String, title: String = id, due: Long? = null, scheduled: Long? = null, deferred: Long? = null, rank: Long? = null,
        priority: Int = 0, created: Long = 0,
    ) = Task(id, title, null, Lifecycle.ACTIVE, due, scheduled, null, priority, null, null, created, null, false, deferredToDay = deferred, boardRank = rank)

    private fun event(id: String, title: String, start: Long, end: Long = start + 3_600_000L) =
        CalendarEvent(id, title, start, end, false, null, "google", "a", "Personal")

    private fun board(tasks: List<Task>, events: List<CalendarEvent> = emptyList(), bills: List<PressureBill> = emptyList(), nowMs: Long = now) =
        WeekBoardRules.build(tasks, events, nowMs, cal, bills = bills)

    private val friday = listOf(
        task("trip", "Trip form", due = at(fri, 17)),
        task("mot", "Book MOT", scheduled = at(fri, 12)),
    )
    private val fridayBills = listOf(PressureBill("Council tax", fri))
    private val fridayGym = listOf(event("gym", "Gym", at(fri, 19)))

    @Test
    fun threeDueThingsOnADayStackItUp() {
        val b = board(friday, fridayGym, fridayBills)
        val p = assertNotNull(b.day(fri)!!.pressure)
        assertEquals("Friday is stacking up: Trip form, Council tax and Gym", p.line)
        assertEquals("Stacking up: Trip form, Council tax and Gym", p.short)
        assertEquals("Friday is stacking up: Trip form, Council tax and Gym.", p.spoken)
        assertEquals(listOf(PressureKind.DUE, PressureKind.BILL, PressureKind.LATE), p.items.map { it.kind })
        assertTrue(b.day(fri)!!.accessibilityLabel.endsWith("stacking up: trip form, council tax and gym"))
        // Every other day is fine; two things aren't stacking up.
        assertEquals(listOf(fri), b.days.filter { it.pressure != null }.map { it.epochDay })
        assertNull(board(friday, fridayGym).day(fri)!!.pressure)
    }

    @Test
    fun theFixesMoveOneToALighterDayOrDoOneToday() {
        val b = board(friday, fridayGym, fridayBills)
        val fixes = b.day(fri)!!.pressure!!.fixes
        assertEquals(listOf("Move “Book MOT” to Sat", "Do “Trip form” today"), fixes.map { it.label })
        assertEquals(listOf(fri + 1, today), fixes.map { it.toDay })
        assertEquals("Moved “Book MOT” to Sat", fixes[0].doneLine)
        assertEquals("“Trip form” is on for today", fixes[1].doneLine)
        // After 17:00 it's tonight; a task due that day is never moved later.
        val evening = board(friday, fridayGym, fridayBills, nowMs = at(today, 18))
        assertEquals("Do “Trip form” tonight", evening.day(fri)!!.pressure!!.fixes.last().label)
        val allDue = board(listOf(task("trip", "Trip form", due = at(fri, 17), scheduled = at(fri, 9))), fridayGym, fridayBills)
        assertEquals(listOf("Do “Trip form” today"), allDue.day(fri)!!.pressure!!.fixes.map { it.label })
        // The fix's move is an ordinary Board move; "do it today" on a card already in Today's column places it there.
        val claim = assertNotNull(PressureRules.claim(b, "trip", today))
        assertTrue("trip" in claim.ranks)
        assertNull(PressureRules.claim(b, "mot", today))
    }

    @Test
    fun aDueTaskHePlacedEarlierDoesNotCount() {
        // Given a day of its own before it's due, or a place on the Board's Today column, it's handled.
        val deferred = friday.map { if (it.id == "trip") it.copy(deferredToDay = thu) else it }
        assertNull(board(deferred, fridayGym, fridayBills).day(fri)!!.pressure)
        val placed = friday.map { if (it.id == "trip") it.copy(boardRank = 1_000) else it }
        assertNull(board(placed, fridayGym, fridayBills).day(fri)!!.pressure)
        // Planned on its due day it still counts.
        val onTheDay = friday.map { if (it.id == "trip") it.copy(scheduledAtMs = at(fri, 9)) else it }
        assertNotNull(board(onTheDay, fridayGym, fridayBills).day(fri)!!.pressure)
    }

    @Test
    fun anEveningCanStackUpOnItsOwn() {
        val events = listOf(
            event("p", "Parents' evening", at(thu, 18)),
            event("c", "Club pick-up", at(thu, 19)),
            event("s", "Standup", at(thu, 9)),
        )
        val tasks = listOf(task("call", "Call the garage", scheduled = at(thu, 20)), task("bins", "Bins out", scheduled = at(thu, 21)))
        val p = assertNotNull(board(tasks, events).day(thu)!!.pressure)
        assertTrue(p.evening)
        assertEquals("Tomorrow evening is stacking up: Call the garage, Bins out, Parents' evening and 1 more", p.line)
        assertEquals("Evening stacking up: Call the garage, Bins out, Parents' evening and 1 more", p.short)
        assertEquals("This evening", PressureRules.dayName(today, today, evening = true))
        assertEquals("Thu 29 Oct", PressureRules.dayName(today + 8, today, evening = false))
    }

    @Test
    fun soonestBillsAndTheHello() {
        val b = board(friday, fridayGym, fridayBills)
        val all = b.days.mapNotNull { it.pressure }.associateBy { it.epochDay }
        val soon = assertNotNull(PressureRules.soonest(all, today))
        assertEquals(fri, soon.epochDay)
        assertNull(PressureRules.soonest(all, today - 1))
        // Subscriptions and warranties renew by themselves; bills, renewals and club fees count.
        fun renewal(kind: ObligationKind, child: String? = null) = RenewalItem(
            kind.name, kind.name.lowercase(), kind, null, null, fri, null, 7, null, null, "Every year", RenewalState.SOON, "", "Paid", "Stop tracking", false,
            forChild = child,
        )
        assertEquals(
            listOf("bill", "car_tax", "subscription"),
            PressureRules.bills(listOf(renewal(ObligationKind.BILL), renewal(ObligationKind.CAR_TAX), renewal(ObligationKind.SUBSCRIPTION, child = "c-alex"), renewal(ObligationKind.WARRANTY))).map { it.title },
        )
        // The hello mentions it, with a card that opens the Calendar.
        val g = HelloRules.greeting(HelloFacts(minuteOfDay = 600, pressure = soon))
        assertEquals(listOf(HelloKind.PRESSURE), g.points.map { it.kind })
        assertEquals(HelloCard(HelloKind.PRESSURE, "STACKING UP", "Friday", "3 things", HelloOpen.CALENDAR, warm = true), g.cards.single())
        assertTrue(g.spoken.contains("Friday is stacking up: Trip form, Council tax and Gym."))
    }
}
