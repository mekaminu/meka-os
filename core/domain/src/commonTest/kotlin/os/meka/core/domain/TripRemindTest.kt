package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trip lists, slice 3a: a Before we leave job's reminder, typed with the job, shown on its row and as a heads-up. */
class TripRemindTest {
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon = d(10, 26)
    private val tue = d(10, 27)
    private val wed = d(10, 28)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val tFold = Trips(fold.replica, { "t${n++}" }, { world.clock.nowMs }, cal)
    private val tMac = Trips(mac.replica, { "t${n++}" }, { world.clock.nowMs }, cal)
    private val lFold = TripLists(fold.replica, { "i${n++}" }, { world.clock.nowMs }, Shopping(fold.replica, { "s${n++}" }, { world.clock.nowMs }, cal))
    private val lMac = TripLists(mac.replica, { "i${n++}" }, { world.clock.nowMs }, Shopping(mac.replica, { "s${n++}" }, { world.clock.nowMs }, cal))

    init { world.clock.nowMs = cal.toEpochMs(mon, 10 * 60) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private fun lisbon(): TripItem {
        assertFalse(tFold.add("Lisbon 28 Oct – 1 Nov").refused)
        return tFold.items().single()
    }

    private fun add(t: TripItem, text: String, open: TripList = TripList.LEAVE) =
        lFold.add(t.id, text, open, tripExists = true, startDay = t.startDay, leaveMinute = t.leaveMinute)

    @Test
    fun aTimeAtTheEndOfAJobIsItsReminder() {
        val e = TripListRules.read("before we leave: put the bins out at 9pm", TripList.PACK)!!
        assertEquals(TripList.LEAVE, e.list)
        assertEquals("Put the bins out", e.text)
        assertEquals("The house", e.group)
        assertEquals(TripRemind(-1, 21 * 60), e.remind)
        // A morning time is on the day; an evening one the night before.
        assertEquals(TripRemind(0, 6 * 60 + 30), TripListRules.read("check in online at 06:30", TripList.LEAVE)!!.remind)
        assertEquals(TripRemind(-1, 20 * 60), TripListRules.read("charge everything 20:00", TripList.LEAVE)!!.remind)
        assertEquals(TripRemind(-1, 21 * 60 + 30), TripListRules.read("bags by the door, remind me 9.30pm", TripList.LEAVE)!!.remind)
        // The day words either side of the time, or alone.
        assertEquals(TripRemind(-1, 7 * 60), TripListRules.read("water the plants the day before at 7am", TripList.LEAVE)!!.remind)
        assertEquals(TripRemind(0, 18 * 60), TripListRules.read("lock the back door at 6pm on the day", TripList.LEAVE)!!.remind)
        assertEquals(TripRemind(-1, 21 * 60), TripListRules.read("empty the fridge the night before", TripList.LEAVE)!!.remind)
        assertEquals(TripRemind(0, 7 * 60), TripListRules.read("passports in the bag on the day", TripList.LEAVE)!!.remind)
        assertEquals("Passports in the bag", TripListRules.read("passports in the bag on the day", TripList.LEAVE)!!.text)
    }

    @Test
    fun theLeaveTimeDecidesWhichDayAndWhenOnTheDay() {
        val leave = 6 * 60 + 30
        assertEquals(TripRemind(0, 6 * 60), TripListRules.read("taxi at 6", TripList.LEAVE, leave)!!.remind)
        assertEquals(TripRemind(-1, 7 * 60), TripListRules.read("hoover at 7", TripList.LEAVE, leave)!!.remind)
        assertEquals(TripRemind(0, 6 * 60), TripListRules.read("bags in the car on the day", TripList.LEAVE, leave)!!.remind)
    }

    @Test
    fun otherListsAndPlainJobsKeepTheirWords() {
        assertNull(TripListRules.read("put the bins out", TripList.LEAVE)!!.remind)
        val buy = TripListRules.read("buy sun cream at 9pm", TripList.PACK)!!
        assertNull(buy.remind)
        assertEquals("Sun cream at 9pm", buy.text)
        // Nothing but a time isn't a job.
        assertNull(TripListRules.readRemind("at 9pm"))
        assertNull(TripListRules.read("before we leave: at 9pm", TripList.PACK)?.remind)
    }

    @Test
    fun theRowSaysWhenAndBothDevicesHaveIt() {
        val t = lisbon()
        val added = add(t, "put the bins out at 9pm")
        assertEquals("Added Put the bins out to Before we leave · The house · remind Tue 21:00", added.line)
        sync()
        val tMacTrip = tMac.items().single()
        val row = TripListRules.view(tMacTrip, lMac.items(), mon).tab(TripList.LEAVE).groups.single().rows.single()
        assertEquals("Remind Tue 21:00", row.sub)
        assertEquals("Put the bins out, remind Tue 21:00", row.spoken)
        // Ticked, the reminder line goes.
        assertTrue(lMac.tick(row.id, true))
        assertEquals("", TripListRules.view(tMacTrip, lMac.items(), mon).tab(TripList.LEAVE).groups.single().rows.single().sub)
    }

    @Test
    fun aReminderIsAHeadsUpAtItsTimeUntilTicked() {
        val t = lisbon()
        add(t, "put the bins out at 9pm")
        add(t, "check in online at 06:30")
        add(t, "water the plants")
        val notices = TripListRules.notices(tFold.items(), lFold.items(), world.clock.nowMs, cal)
        assertEquals(2, notices.size)
        val bins = notices.first { it.title.endsWith("Put the bins out") }
        assertEquals("Before Lisbon: Put the bins out", bins.title)
        assertEquals("Lisbon trip · Wed 28 Oct", bins.text)
        assertEquals(NoticeSource.TRIP_JOBS, bins.source)
        assertEquals(cal.toEpochMs(tue, 21 * 60), bins.atMs)
        assertEquals(cal.toEpochMs(wed, 6 * 60 + 30), notices.first { it.title.endsWith("Check in online") }.atMs)
        // Ticked, it goes.
        assertTrue(lFold.tick(lFold.items().first { it.text == "Put the bins out" }.id, true))
        assertEquals(1, TripListRules.notices(tFold.items(), lFold.items(), world.clock.nowMs, cal).size)
        // Long gone once the trip is over.
        world.clock.nowMs = cal.toEpochMs(d(11, 2), 9 * 60)
        assertTrue(TripListRules.notices(tFold.items(), lFold.items(), world.clock.nowMs, cal).isEmpty())
    }

    @Test
    fun aReminderMovesWithTheTripsDatesAndCopiesToTheNextTrip() {
        val t = lisbon()
        add(t, "put the bins out at 9pm")
        val rome = TripItem("r", "Rome", d(11, 10), d(11, 12), 0)
        assertFalse(lFold.copyFrom(t, rome).refused)
        val copy = lFold.items().single { it.tripId == rome.id }
        assertEquals(TripRemind(-1, 21 * 60), copy.remind)
        assertEquals("Remind Mon 21:00", TripListRules.view(rome, lFold.items(), mon).tab(TripList.LEAVE).groups.single().rows.single().sub)
        // Saved as a template, the reminder comes too.
        assertFalse(lFold.saveTemplate(t).refused)
        assertEquals(TripRemind(-1, 21 * 60), TripListRules.templateEntries(
            fold.replica.entities(EntityTypes.TRIP_ITEM).associate { it.ref.entityId to it.fields },
        ).values.single().single().remind)
    }
}
