package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trip lists, slice 2d: who's going and the leave time on a trip, in the lists' header, and on both devices. */
class TripGoingTest {
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon = d(10, 26)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val tFold = Trips(fold.replica, { "t${n++}" }, { world.clock.nowMs }, cal)
    private val tMac = Trips(mac.replica, { "t${n++}" }, { world.clock.nowMs }, cal)

    init { world.clock.nowMs = cal.toEpochMs(mon, 10 * 60) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private val family = AboutYou(partnerName = "Jo Smith", children = listOf(AboutChild("Alex"), AboutChild("Sam")))

    private fun lisbon(): TripItem {
        assertFalse(tFold.add("Lisbon 28 Oct – 1 Nov").refused)
        return tFold.items().single()
    }

    @Test
    fun aLeaveTimeReadsTheWaysHeTypesIt() {
        assertEquals(6 * 60 + 30, TripListRules.readLeave("06:30"))
        assertEquals(6 * 60 + 30, TripListRules.readLeave("6:30"))
        assertEquals(6 * 60 + 30, TripListRules.readLeave("6.30am"))
        assertEquals(6 * 60 + 30, TripListRules.readLeave("0630"))
        assertEquals(7 * 60, TripListRules.readLeave("leave at 7"))
        assertEquals(18 * 60 + 15, TripListRules.readLeave("6:15 pm"))
        assertEquals(0, TripListRules.readLeave("12am"))
        assertEquals(12 * 60, TripListRules.readLeave("12pm"))
        assertNull(TripListRules.readLeave("25:00"))
        assertNull(TripListRules.readLeave("6:75"))
        assertNull(TripListRules.readLeave("13pm"))
        assertNull(TripListRules.readLeave("early"))
    }

    @Test
    fun aNewTripOffersSetUpUntilWhoOrWhenIsSaid() {
        val t = lisbon()
        val v = TripListRules.view(t, emptyList(), mon, about = family)
        assertTrue(v.needsSetUp)
        assertEquals("Wed 28 Oct – Sun 1 Nov · 5 days", v.dates)
        assertEquals("2 days to go", v.countdown)
        assertEquals(listOf("Me", "Jo", "Alex and Sam"), v.going.map { it.label })
        assertTrue(v.going.none { it.on })
        assertEquals(TripWho.entries, v.whoFilters)
        assertEquals("", v.leave)
    }

    @Test
    fun whosGoingCountsThePeopleAndSyncs() {
        val t = lisbon()
        assertTrue(tFold.setGoing(t.id, TripGoer.ME, true))
        assertEquals(setOf(TripGoer.ME), tFold.items().single().going)
        assertEquals("Wed 28 Oct – Sun 1 Nov · 5 days · Just you", TripListRules.view(tFold.items().single(), emptyList(), mon, about = family).dates)
        assertTrue(tFold.setGoing(t.id, TripGoer.PARTNER, true))
        assertTrue(tFold.setGoing(t.id, TripGoer.KIDS, true))
        assertFalse(tFold.setGoing(t.id, TripGoer.KIDS, true))
        val v = TripListRules.view(tFold.items().single(), emptyList(), mon, about = family)
        assertEquals("Wed 28 Oct – Sun 1 Nov · 5 days · 4 people", v.dates)
        assertFalse(v.needsSetUp)
        assertTrue(v.going.all { it.on })
        assertEquals("Alex and Sam, going", v.going.last().spoken)
        // Without the children in About you, it still reads.
        assertEquals("2 people and the kids", TripListRules.people(tFold.items().single(), AboutYou.EMPTY))
        assertEquals("Partner", TripListRules.view(tFold.items().single(), emptyList(), mon).going[1].label)
        // On the Mac.
        sync()
        assertEquals(TripGoer.entries.toSet(), tMac.items().single().going)
        // The kids off: To pack drops its Kids filter (nothing of theirs is packed).
        assertTrue(tMac.setGoing(t.id, TripGoer.KIDS, false))
        sync()
        val adults = TripListRules.view(tFold.items().single(), emptyList(), mon, about = family)
        assertEquals("Wed 28 Oct – Sun 1 Nov · 5 days · 2 people", adults.dates)
        assertEquals(listOf(TripWho.EVERYONE, TripWho.ME), adults.whoFilters)
        // Everyone off: no count, but he has chosen, so no set-up offer.
        tFold.setGoing(t.id, TripGoer.ME, false)
        tFold.setGoing(t.id, TripGoer.PARTNER, false)
        val nobody = TripListRules.view(tFold.items().single(), emptyList(), mon, about = family)
        assertEquals("Wed 28 Oct – Sun 1 Nov · 5 days", nobody.dates)
        assertFalse(nobody.needsSetUp)
    }

    @Test
    fun theKidsFilterStaysWhileSomethingOfTheirsIsPacked() {
        val t = lisbon()
        tFold.setGoing(t.id, TripGoer.ME, true)
        val kidsThing = TripListItem("i", t.id, TripList.PACK, "Hats", "Kids", null, TripWho.KIDS, false, null, 0)
        assertEquals(TripWho.entries, TripListRules.view(tFold.items().single(), listOf(kidsThing), mon).whoFilters)
    }

    @Test
    fun theLeaveTimeShowsInTheCountdownUntilTheFirstDayIsOver() {
        val t = lisbon()
        val saved = tFold.setLeave(t.id, "6.30am")
        assertFalse(saved.refused)
        assertEquals("Leaving home 06:30 on Wed 28 Oct", saved.line)
        val trip = tFold.items().single()
        assertEquals(6 * 60 + 30, trip.leaveMinute)
        assertEquals("06:30", TripListRules.view(trip, emptyList(), mon).leave)
        assertFalse(TripListRules.view(trip, emptyList(), mon).needsSetUp)
        assertEquals("2 days to go · leave home 06:30 Wednesday", TripListRules.countdown(trip, mon))
        assertEquals("Tomorrow · leave home 06:30", TripListRules.countdown(trip, mon + 1))
        assertEquals("Day 1 of 5 · leave home 06:30", TripListRules.countdown(trip, mon + 2))
        assertEquals("Day 2 of 5", TripListRules.countdown(trip, mon + 3))
        // Synced, and refused when it isn't a time (nothing written).
        sync()
        assertEquals(6 * 60 + 30, tMac.items().single().leaveMinute)
        val bad = tMac.setLeave(t.id, "early")
        assertTrue(bad.refused)
        assertEquals(TripListRules.LEAVE_NOT_READ, bad.line)
        assertEquals(6 * 60 + 30, tMac.items().single().leaveMinute)
        // Blank clears it.
        assertEquals(TripListRules.LEAVE_CLEARED, tMac.setLeave(t.id, " ").line)
        assertNull(tMac.items().single().leaveMinute)
        assertEquals("2 days to go", TripListRules.countdown(tMac.items().single(), mon))
        // A gone trip.
        assertTrue(tFold.setLeave("nope", "07:00").refused)
        assertFalse(tFold.setGoing("nope", TripGoer.ME, true))
    }

    @Test
    fun retypingTheTripKeepsWhoAndWhen() {
        val t = lisbon()
        tFold.setGoing(t.id, TripGoer.ME, true)
        tFold.setLeave(t.id, "06:30")
        assertFalse(tFold.add("Lisbon 28 Oct – 2 Nov").refused)
        val again = tFold.items().single()
        assertEquals(d(11, 2), again.endDay)
        assertEquals(setOf(TripGoer.ME), again.going)
        assertEquals(6 * 60 + 30, again.leaveMinute)
    }
}
