package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Weekend football logistics, slice 1: the kit reminder the evening before a club fixture. */
class FootballTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    // Fixed +1 h (London in October before the clocks go back).
    private val cal = LocalCalendar.fixedOffset(hour)
    private val thu = CivilDate.toEpochDay(2026, 10, 8)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun actions(d: Device) = EventActions(d.replica, d.tasks, { world.clock.nowMs }, cal)
    private val eaFold = actions(fold)
    private val eaMac = actions(mac)

    init { world.clock.nowMs = at(thu, 12) }

    private fun event(
        id: String = "ev1",
        title: String = "BUFC U9s v Arlesey",
        start: Long = at(thu + 2, 10),
        calendarName: String? = "Personal",
        provider: String = "google",
        allDay: Boolean = false,
    ) = CalendarEvent(id, title, start, start + hour, allDay, "Bury Field", provider, "meka@gmail.com", calendarName)

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun clubFixturesAreTheClubsNamedAsWholeWordsNeverBarcaOrLookAlikes() {
        assertEquals("BUFC", FootballRules.club(event()))
        assertEquals("SJFC", FootballRules.club(event(title = "Match", calendarName = "sjfc u11s")))
        assertEquals("SJFC", FootballRules.club(event(title = "SJFC: away at Potton")))
        assertNull(FootballRules.club(event(title = "BUFCX training")))
        assertNull(FootballRules.club(event(title = "Training", calendarName = "Club")))
        // The fixtures feed (Barça) never counts.
        assertNull(FootballRules.club(event(title = "BUFC v Barça", provider = "fixtures")))
    }

    @Test
    fun kitReminderPlansThePackingAt19TheEveningBeforeWithTheDefaultList() {
        val e = event()
        val id = assertNotNull(eaFold.addKit(e))
        assertEquals(FootballRules.kitTaskId("ev1"), id)
        val t = assertNotNull(fold.tasks.get(id))
        assertEquals("Pack the kit for BUFC U9s v Arlesey", t.title)
        assertEquals(at(thu + 1, 19), t.scheduledAtMs)
        assertEquals(at(thu + 1, 19), t.remindAtMs)
        assertEquals(at(thu + 2, 10), t.dueAtMs)
        assertEquals("ev1", t.eventId)
        assertEquals(FootballRules.DEFAULT_KIT, t.checklist.map { it.text })

        val d = EventDetails.build(e, world.clock.nowMs, cal, eaFold.marks())
        assertFalse(d.canKit)
        assertEquals(id, d.kitTaskId)
        assertEquals("Kit reminder tomorrow 19:00 · 0 of 5 packed", d.kitLine)
        // The kit task is not the event's prep task.
        assertNull(eaFold.marks().prepTasks["ev1"])
        // Further ahead it names the day.
        assertEquals("Kit reminder Fri 19:00 · 0 of 5 packed", EventDetails.build(e, at(thu - 1, 12), cal, eaFold.marks()).kitLine)
        assertEquals("Kit reminder tomorrow 19:00", FootballRules.addedLine(FootballRules.plan(e, at(thu, 9), cal), at(thu, 9), cal))

        // A second tap leaves the open one as it is.
        fold.tasks.setChecklistItemChecked(t.checklist.first().id, true)
        assertEquals(id, eaFold.addKit(e))
        assertEquals("Kit reminder tomorrow 19:00 · 1 of 5 packed", EventDetails.build(e, world.clock.nowMs, cal, eaFold.marks()).kitLine)
    }

    @Test
    fun onlyClubFixturesStillToComeOfferIt() {
        assertTrue(EventDetails.build(event(), world.clock.nowMs, cal, eaFold.marks()).canKit)
        assertFalse(EventDetails.build(event(title = "Dentist"), world.clock.nowMs, cal, eaFold.marks()).canKit)
        assertNull(eaFold.addKit(event(title = "Dentist")))
        val started = event(start = at(thu, 11))
        assertFalse(EventDetails.build(started, world.clock.nowMs, cal, eaFold.marks()).canKit)
        assertNull(eaFold.addKit(started))
    }

    @Test
    fun tooLateForTheEveningBeforeItIsJustTheListDueAtKickOff() {
        world.clock.nowMs = at(thu + 1, 20)
        val e = event()
        val id = assertNotNull(eaFold.addKit(e))
        val t = assertNotNull(fold.tasks.get(id))
        assertNull(t.scheduledAtMs)
        assertNull(t.remindAtMs)
        assertEquals(at(thu + 2, 10), t.dueAtMs)
        assertEquals("Kit list · 0 of 5 packed", EventDetails.build(e, world.clock.nowMs, cal, eaFold.marks()).kitLine)
        assertEquals("Kit list added", FootballRules.addedLine(FootballRules.plan(e, world.clock.nowMs, cal), world.clock.nowMs, cal))
        t.checklist.forEach { fold.tasks.setChecklistItemChecked(it.id, true) }
        assertEquals("Kit packed", EventDetails.build(e, world.clock.nowMs, cal, eaFold.marks()).kitLine)
    }

    @Test
    fun anAllDayTournamentIsRemindedTheEveningBeforeItsFirstDay() {
        val sat = thu + 2
        val e = event(title = "SJFC tournament", start = sat * CivilDate.DAY_MS, allDay = true)
            .copy(endAtMs = (sat + 1) * CivilDate.DAY_MS)
        val t = assertNotNull(fold.tasks.get(assertNotNull(eaFold.addKit(e))))
        assertEquals(at(thu + 1, 19), t.remindAtMs)
        assertEquals(at(sat, 9), t.dueAtMs)
    }

    @Test
    fun mekasOwnListCarriesOnToTheNextFixture() {
        val first = assertNotNull(fold.tasks.get(assertNotNull(eaFold.addKit(event()))))
        fold.tasks.deleteChecklistItem(first.checklist.last().id) // Coat
        fold.tasks.addChecklistItem(first.id, "Gloves")
        world.clock.nowMs += 1_000

        val next = event(id = "ev2", title = "BUFC U9s v Potton", start = at(thu + 9, 10))
        val t = assertNotNull(fold.tasks.get(assertNotNull(eaFold.addKit(next))))
        assertEquals(listOf("Boots", "Shin pads", "Kit and socks", "Water bottle", "Gloves"), t.checklist.map { it.text })
        assertEquals(listOf("Boots", "Shin pads", "Kit and socks", "Water bottle", "Gloves"), FootballRules.kitList(fold.tasks.all()))
    }

    @Test
    fun bothDevicesOfflineMakeOneKitTaskWithOneList() {
        eaFold.addKit(event())
        eaMac.addKit(event())
        sync()
        val kits = fold.tasks.all().filter { FootballRules.isKitTask(it) }
        assertEquals(1, kits.size)
        assertEquals(FootballRules.DEFAULT_KIT, kits.single().checklist.map { it.text })
        assertEquals(FootballRules.DEFAULT_KIT, assertNotNull(mac.tasks.get(kits.single().id)).checklist.map { it.text })
    }

    @Test
    fun deletedAndAddedAgainItComesBackWithTheListUnticked() {
        val e = event()
        val id = assertNotNull(eaFold.addKit(e))
        fold.tasks.get(id)!!.checklist.forEach { fold.tasks.setChecklistItemChecked(it.id, true) }
        fold.tasks.delete(id)
        assertNull(EventDetails.build(e, world.clock.nowMs, cal, eaFold.marks()).kitLine)
        assertTrue(EventDetails.build(e, world.clock.nowMs, cal, eaFold.marks()).canKit)

        assertEquals(id, eaFold.addKit(e))
        val back = assertNotNull(fold.tasks.get(id))
        assertEquals(FootballRules.DEFAULT_KIT, back.checklist.map { it.text })
        assertTrue(back.checklist.none { it.checked })
        assertEquals(at(thu + 1, 19), back.remindAtMs)
    }

    // ---- Slice 2: the travel time Meka set for each ground ----

    @Test
    fun aGroundsKeyIgnoresCaseAndPunctuationAndALinkIsNoGround() {
        assertEquals("arlesey town fc hitchin rd", FootballRules.venueKey("  Arlesey Town FC,  Hitchin Rd. "))
        assertEquals(FootballRules.venueKey("arlesey town fc hitchin rd"), FootballRules.venueKey("Arlesey Town FC, Hitchin Rd"))
        assertEquals("camp nou", FootballRules.venueKey("Camp Nou!"))
        assertNull(FootballRules.venueKey("https://meet.google.com/abc"))
        assertNull(FootballRules.venueKey("  "))
        assertNull(FootballRules.venueKey(null))
        assertEquals(FootballRules.venueId("bury field"), FootballRules.venueId("bury field"))
    }

    @Test
    fun settingLeaveByOnAFixtureRemembersTheGroundAndTheNextFixtureThereOffersIt() {
        val first = event()
        eaFold.setLeaveBy(first.id, 25)
        assertTrue(eaFold.rememberVenue(first))
        assertFalse(eaFold.rememberVenue(first)) // nothing changed: nothing written
        val next = event(id = "ev2", title = "BUFC U9s v Potton", start = at(thu + 9, 10))
        val offer = assertNotNull(FootballRules.leaveOffer(next, eaFold.marks(), world.clock.nowMs, cal))
        assertEquals(25, offer.travelMin)
        assertFalse(offer.rings)
        assertEquals("Leave by 09:35 · as last time", offer.label)
        assertEquals("Leave by 09:35 · 25 min away", offer.line)
        // The detail carries it, and the first fixture (which has its own) offers nothing.
        val d = EventDetails.build(next, world.clock.nowMs, cal, eaFold.marks())
        assertEquals("Leave by 09:35 · as last time", d.leaveOfferLabel)
        assertEquals(25, d.leaveOfferMin)
        assertNull(FootballRules.leaveOffer(first, eaFold.marks(), world.clock.nowMs, cal))

        // The alarm switch is remembered too, and the Mac sees the ground after a sync.
        eaFold.setLeaveAlarm(first.id, true)
        assertTrue(eaFold.rememberVenue(first))
        sync()
        val onMac = assertNotNull(FootballRules.leaveOffer(next, eaMac.marks(), world.clock.nowMs, cal))
        assertTrue(onMac.rings)
        assertEquals("Leave by 09:35 · 25 min away · alarm", onMac.line)
    }

    @Test
    fun noOfferForAnotherGroundAPlainEventAnAllDayOneOrWhenLeavingHasGone() {
        val first = event()
        eaFold.setLeaveBy(first.id, 30)
        eaFold.rememberVenue(first)
        val marks = eaFold.marks()
        val now = world.clock.nowMs
        // Another ground.
        assertNull(FootballRules.leaveOffer(event(id = "a", start = at(thu + 9, 10)).copy(location = "Potton Rec"), marks, now, cal))
        // Not a club fixture: the dentist at Bury Field isn't remembered or offered.
        assertNull(FootballRules.leaveOffer(event(id = "b", title = "Dentist", start = at(thu + 9, 10)), marks, now, cal))
        assertFalse(eaFold.rememberVenue(event(id = "b", title = "Dentist")))
        // All day.
        assertNull(FootballRules.leaveOffer(event(id = "c", allDay = true, start = cal.toEpochMs(thu + 9, 0)), marks, now, cal))
        // Leaving would already be gone (kick-off in 20 min, 30 min away).
        assertNull(FootballRules.leaveOffer(event(id = "d", start = now + 20 * 60_000L), marks, now, cal))
        // A fixture with no travel time remembers nothing.
        assertFalse(eaFold.rememberVenue(event(id = "e")))
    }

    @Test
    fun runningLateIsDraftedAroundKickOffForMekaToSendHimself() {
        val e = event(start = at(thu + 2, 10))
        // Too early: nothing, nor on an event that isn't a club fixture.
        assertTrue(FootballRules.lateDrafts(e, at(thu + 2, 7, 59), cal).isEmpty())
        assertTrue(FootballRules.lateDrafts(event(title = "Dentist"), at(thu + 2, 9, 30), cal).isEmpty())
        // From two hours before kick-off: by kick-off plus the minutes.
        val before = FootballRules.lateDrafts(e, at(thu + 2, 8), cal)
        assertEquals(listOf(5, 10, 15, 20), before.map { it.minutes })
        assertEquals("10 min", before[1].label)
        assertEquals("Hi, sorry, running about 10 min late for BUFC U9s v Arlesey. Should be there by 10:10.", before[1].text)
        // After kick-off: now plus the minutes, rounded up to five.
        val after = FootballRules.lateDrafts(e, at(thu + 2, 10, 7), cal)
        assertEquals("Hi, sorry, running about 5 min late for BUFC U9s v Arlesey. Should be there by 10:15.", after[0].text)
        // Half an hour after kick-off it's gone.
        assertTrue(FootballRules.lateDrafts(e, at(thu + 2, 10, 31), cal).isEmpty())
        // The detail carries them.
        assertEquals(4, EventDetails.build(e, at(thu + 2, 9), cal, eaFold.marks()).lateDrafts.size)
        assertTrue(EventDetails.build(e, at(thu, 12), cal, eaFold.marks()).lateDrafts.isEmpty())
        // A long title is cut at a word.
        val long = FootballRules.lateDrafts(event(title = "BUFC U9s v Arlesey Town Youth in the county cup quarter final replay"), at(thu + 2, 9), cal)
        assertTrue(long[0].text.contains("for BUFC U9s v Arlesey Town Youth in the county cup quarter…. Should"), long[0].text)
    }
}
