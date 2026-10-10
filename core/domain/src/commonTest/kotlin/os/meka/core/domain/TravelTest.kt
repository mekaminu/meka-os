package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Weekend football, slice 2b: the leave-by from the drive the server worked out with traffic (Google Routes). */
class TravelTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private val thu = CivilDate.toEpochDay(2026, 10, 8)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val eaFold = EventActions(fold.replica, fold.tasks, { world.clock.nowMs }, cal)
    private val eaMac = EventActions(mac.replica, mac.tasks, { world.clock.nowMs }, cal)

    init { world.clock.nowMs = at(thu, 12) }

    // Saturday 10:00 at Bury Field.
    private fun event(
        id: String = "ev1",
        title: String = "BUFC U7s v Arlesey",
        start: Long = at(thu + 2, 10),
        place: String? = "Bury Field, Biggleswade",
        allDay: Boolean = false,
    ) = CalendarEvent(id, title, start, start + hour, allDay, place, "google", "meka@gmail.com", "Personal")

    private fun travel(e: CalendarEvent, drive: Int, checkedAt: Long, key: String = FootballRules.venueKey(e.location)!!, kickOff: Long = e.startAtMs) =
        TravelTime(e.id, key, drive, TravelRules.leaveMs(e, drive, cal), checkedAt, kickOff)

    /** What the server writes, put on the Fold's replica here to stand in for it. */
    private fun serverAnswers(e: CalendarEvent, drive: Int) {
        val l = TravelRules.due(listOf(e), emptySet(), emptyMap(), world.clock.nowMs, cal).single()
        fold.replica.commitLocal(EntityTypes.TRAVEL_TIME, TravelRules.entityId(e.id), TravelRules.fields(l, drive, world.clock.nowMs))
    }

    @Test
    fun leaveByIsKickOffLessMeetingEarlyTheDriveAndTenMinutesToSpare() {
        assertEquals(50, TravelRules.travelMin(25))
        assertEquals(at(thu + 2, 9, 10), TravelRules.leaveMs(event(), 25, cal))
        assertEquals(1, TravelRules.driveMin(0))
        assertEquals(25, TravelRules.driveMin(24 * 60 + 1))
        assertEquals(25, TravelRules.driveMin(25 * 60))
        assertNull(TravelRules.driveMin(-1))
        assertNull(TravelRules.driveMin(181 * 60L))
        assertEquals("25 min drive", TravelRules.driveLabel(25))
        assertEquals("1 h 10 min drive", TravelRules.driveLabel(70))
        assertEquals("1 h drive", TravelRules.driveLabel(60))
    }

    @Test
    fun theServerAsksOnFirstSightTheEveningBeforeAndAnHourBeforeLeavingOnlyForClubFixturesWithAPlace() {
        val e = event()
        val now = world.clock.nowMs
        // First sight: due, with the departure worked out from a 30-minute guess and only the place to send.
        val first = TravelRules.due(listOf(e), emptySet(), emptyMap(), now, cal).single()
        assertEquals("Bury Field, Biggleswade", first.place)
        assertEquals("bury field biggleswade", first.placeKey)
        assertEquals(at(thu + 2, 9, 5), first.departAtMs)
        assertEquals(e.startAtMs, first.kickOffMs)

        val k = travel(e, 25, now)
        assertFalse(TravelRules.isDue(e, k, now + hour, cal))
        // From 18:00 on Friday.
        assertFalse(TravelRules.isDue(e, k, at(thu + 1, 17, 59), cal))
        assertTrue(TravelRules.isDue(e, k, at(thu + 1, 18), cal))
        val evening = travel(e, 25, at(thu + 1, 18, 5))
        assertFalse(TravelRules.isDue(e, evening, at(thu + 1, 23), cal))
        // An hour before leaving (09:10 → 08:10) on the day.
        assertFalse(TravelRules.isDue(e, evening, at(thu + 2, 8, 9), cal))
        assertTrue(TravelRules.isDue(e, evening, at(thu + 2, 8, 10), cal))
        assertFalse(TravelRules.isDue(e, travel(e, 25, at(thu + 2, 8, 12)), at(thu + 2, 8, 40), cal))
        // Once leaving has gone, never.
        assertFalse(TravelRules.isDue(e, evening, at(thu + 2, 9, 10), cal))
        // A new place or a moved kick-off asks again.
        assertTrue(TravelRules.isDue(e, travel(e, 25, now, key = "potton rec"), now + hour, cal))
        assertTrue(TravelRules.isDue(e, travel(e, 25, now, kickOff = at(thu + 2, 11)), now + hour, cal))
        // A long drive found at first sight doesn't ask again and again.
        assertFalse(TravelRules.isDue(e, travel(e, 90, now), now + 10 * min, cal))

        // Not: no place, a call link, all day, not a club, hidden, already started, more than 8 days off.
        val none = listOf(
            event(id = "a", place = null), event(id = "b", place = "https://meet.google.com/abc"),
            event(id = "c", allDay = true, start = cal.toEpochMs(thu + 2, 0)), event(id = "d", title = "Dentist"),
            event(id = "e"), event(id = "f", start = now - hour), event(id = "g", start = at(thu + 9, 10)),
        )
        assertEquals(emptyList(), TravelRules.due(none, setOf("e"), emptyMap(), now, cal))
        // Soonest first.
        val later = event(id = "x", start = at(thu + 7, 10))
        assertEquals(listOf("ev1", "x"), TravelRules.due(listOf(later, e), emptySet(), emptyMap(), now, cal).map { it.eventId })
    }

    @Test
    fun theDetailOffersTheServersDriveOnBothDevicesAndItRingsUnlessTheGroundSaidNot() {
        val e = event()
        serverAnswers(e, 25)
        val r = assertNotNull(eaFold.marks().routes[e.id])
        assertEquals(25, r.driveMin)
        val offer = assertNotNull(FootballRules.leaveOffer(e, eaFold.marks(), world.clock.nowMs, cal))
        assertEquals(50, offer.travelMin)
        assertTrue(offer.rings)
        assertEquals("Leave by 09:10 · 25 min drive", offer.label)
        assertEquals("Leave by 09:10 · 25 min drive · alarm", offer.line)
        val d = EventDetails.build(e, world.clock.nowMs, cal, eaFold.marks())
        assertEquals("Leave by 09:10 · 25 min drive", d.leaveOfferLabel)
        assertEquals(50, d.leaveOfferMin)

        fold.sync(); mac.sync()
        assertEquals("Leave by 09:10 · 25 min drive", FootballRules.leaveOffer(e, eaMac.marks(), world.clock.nowMs, cal)?.label)

        // Last time at this ground Leave by didn't ring: neither does this one.
        val before = event(id = "ev0", start = at(thu - 5, 10))
        eaFold.setLeaveBy(before.id, 40)
        assertTrue(eaFold.rememberVenue(before))
        assertFalse(assertNotNull(FootballRules.leaveOffer(e, eaFold.marks(), world.clock.nowMs, cal)).rings)

        // Once Meka set his own travel time, no offer.
        eaFold.setLeaveBy(e.id, 50)
        assertNull(FootballRules.leaveOffer(e, eaFold.marks(), world.clock.nowMs, cal))
    }

    @Test
    fun aDriveForAnOldPlaceOrPastLeavingFallsBackToTheGroundsOwnTime() {
        val e = event()
        serverAnswers(e, 25)
        // The fixture moved to Potton: the old drive isn't used; the remembered time there is.
        val moved = e.copy(location = "Potton Rec")
        assertNull(FootballRules.leaveOffer(moved, eaFold.marks(), world.clock.nowMs, cal))
        val there = event(id = "ev0", start = at(thu - 5, 10), place = "Potton Rec")
        eaFold.setLeaveBy(there.id, 30)
        eaFold.rememberVenue(there)
        assertEquals("Leave by 09:30 · as last time", FootballRules.leaveOffer(moved, eaFold.marks(), world.clock.nowMs, cal)?.label)
        // Leaving by the drive has gone (09:10) but by the remembered time it hasn't: nothing from the drive.
        world.clock.nowMs = at(thu + 2, 9, 15)
        assertNull(FootballRules.leaveOffer(e, eaFold.marks(), world.clock.nowMs, cal))
        // Something that isn't a drive (another entity's fields, a drive out of range) is never read as one.
        assertNull(TravelRules.read("tt0", TravelRules.fields(TravelRules.Lookup("ev1", "x", "x", 0, 0), 25, 0)))
        assertNull(TravelRules.read(TravelRules.entityId("ev1"), TravelRules.fields(TravelRules.Lookup("ev1", "x", "x", 0, 0), 500, 0)))
    }

    /** The server's answer for [e] at the current time, written as the server writes it (any time, due or not). */
    private fun answer(e: CalendarEvent, drive: Int) {
        val key = FootballRules.venueKey(e.location)!!
        val l = TravelRules.Lookup(e.id, e.location!!, key, TravelRules.leaveMs(e, drive, cal), e.startAtMs)
        fold.replica.commitLocal(EntityTypes.TRAVEL_TIME, TravelRules.entityId(e.id), TravelRules.fields(l, drive, world.clock.nowMs))
    }

    @Test
    fun aLeaveByTakenFromTheDriveFollowsTheTrafficOnBothDevicesUntilMekaPicksHisOwn() {
        val e = event()
        answer(e, 25)
        // Thursday: "Leave by 09:10 · 25 min drive" taken, ringing as an alarm.
        val offer = assertNotNull(FootballRules.leaveOffer(e, eaFold.marks(), world.clock.nowMs, cal))
        assertEquals("bury field biggleswade", offer.driveKey)
        eaFold.setLeaveBy(e.id, offer.travelMin, offer.driveKey)
        eaFold.setLeaveAlarm(e.id, true)
        var marks = eaFold.marks()
        assertEquals(50, marks.travel[e.id])
        assertEquals(25, marks.drives[e.id]?.driveMin)
        assertEquals("Leave by 09:10 · 25 min drive · alarm", ReminderRules.line(e, marks, cal))
        assertEquals(LeaveAlarmRules.id(e.id, e.startAtMs, 50), LeaveAlarmRules.alarms(listOf(e), marks, world.clock.nowMs, cal).single().id)
        assertEquals(emptyList(), TravelRules.trafficNotices(listOf(e), marks, world.clock.nowMs, cal))

        // Friday 18:05 the evening check says 40 min: Leave by moves to 08:55, the alarm with it, and a heads-up says so.
        world.clock.nowMs = at(thu + 1, 18, 5)
        answer(e, 40)
        marks = eaFold.marks()
        assertEquals(65, marks.travel[e.id])
        assertEquals("Leave by 08:55 · 40 min drive · alarm", ReminderRules.line(e, marks, cal))
        val alarm = LeaveAlarmRules.alarms(listOf(e), marks, world.clock.nowMs, cal).single()
        assertEquals(at(thu + 2, 8, 55), alarm.atMs)
        assertEquals(LeaveAlarmRules.id(e.id, e.startAtMs, 65), alarm.id)
        val notice = ReminderRules.notices(listOf(e), marks, world.clock.nowMs, cal).single()
        assertEquals("Leave 15 min earlier for BUFC U7s v Arlesey", notice.title)
        assertEquals("Traffic · leave by 08:55 · 40 min drive", notice.text)
        assertEquals(world.clock.nowMs, notice.atMs)
        assertEquals(at(thu + 2, 8, 55), notice.expiresAtMs)
        assertEquals(NoticeTier.HEADS_UP, notice.tier)
        // The Mac follows the same answer (the server's entity and the mark both sync).
        fold.sync(); mac.sync()
        assertEquals(65, eaMac.marks().travel[e.id])
        assertEquals(EventDetails.build(e, world.clock.nowMs, cal, eaMac.marks()).travelMin, 65)

        // Saturday 08:00, an hour before: 28 min. Later than he set? 3 min earlier only: no heads-up, the time just moves.
        world.clock.nowMs = at(thu + 2, 8)
        answer(e, 28)
        marks = eaFold.marks()
        assertEquals(53, marks.travel[e.id])
        assertEquals(emptyList(), TravelRules.trafficNotices(listOf(e), marks, world.clock.nowMs, cal))
        assertEquals("Leave by 09:07 · 28 min drive · alarm", ReminderRules.line(e, marks, cal))

        // Meka picks 45 min himself: it stays his, whatever the traffic says next.
        eaFold.setLeaveBy(e.id, 45)
        answer(e, 35)
        marks = eaFold.marks()
        assertEquals(45, marks.travel[e.id])
        assertNull(marks.drives[e.id])
        assertEquals("Leave by 09:15 · 45 min away · alarm", ReminderRules.line(e, marks, cal))
        // Off: nothing follows.
        eaFold.setLeaveBy(e.id, null)
        assertNull(eaFold.marks().travel[e.id])
        assertTrue(eaFold.marks().drives.isEmpty())
    }

    @Test
    fun anAnswerForAnotherPlaceOrAfterLeavingIsNotFollowed() {
        val key = "bury field biggleswade"
        val kick = at(thu + 2, 10)
        fun route(drive: Int, checked: Long, place: String = key) = TravelTime("ev1", place, drive, 0L, checked, kick)
        val set = mapOf("ev1" to 50)
        val keys = mapOf("ev1" to key)
        assertEquals(40, TravelRules.follow(set, keys, mapOf("ev1" to route(40, at(thu + 1, 18))))["ev1"]?.driveMin)
        // The fixture moved ground: the old answer isn't for it; Meka's 50 stays until the new place is asked.
        assertNull(TravelRules.follow(set, keys, mapOf("ev1" to route(40, at(thu + 1, 18), place = "potton rec")))["ev1"])
        // Worked out at or after 09:10 (when he set to leave): he's gone or the alarm rang, so nothing moves.
        assertNull(TravelRules.follow(set, keys, mapOf("ev1" to route(40, at(thu + 2, 9, 10))))["ev1"])
        assertEquals(40, TravelRules.follow(set, keys, mapOf("ev1" to route(40, at(thu + 2, 9, 9))))["ev1"]?.driveMin)
        // No travel time set, or no key (his own time): nothing to follow.
        assertTrue(TravelRules.follow(emptyMap(), keys, mapOf("ev1" to route(40, 0))).isEmpty())
        assertTrue(TravelRules.follow(set, emptyMap(), mapOf("ev1" to route(40, 0))).isEmpty())
        // The ground's "as last time" offer is Meka's own time, so it never follows.
        assertNull(FootballRules.leaveOffer(event(place = "Potton Rec"), EventMarks.NONE.copy(
            venues = mapOf("potton rec" to VenueTravel("Potton Rec", 30, false, 0L)),
        ), world.clock.nowMs, cal)?.driveKey)
        assertEquals(15, DriveFollow(40, 50, 0L).earlierMin)
    }

    @Test
    fun withoutTheKeySetupAsksForItAndHealthDoesNotCallItTrouble() {
        val off = HealthAccount(TravelRules.PROVIDER, TravelRules.LABEL, TravelRules.LABEL, TravelRules.STATUS_OFF, null)
        assertEquals("Travel times", CalendarAccountRules.providerLabel(TravelRules.PROVIDER))
        assertEquals("Travel times · off · add the Google key (see Setup)", CalendarAccountRules.statusLine(TravelRules.PROVIDER, TravelRules.LABEL, TravelRules.STATUS_OFF, null))
        fun step(accounts: List<HealthAccount>?) = SetupRules.view(
            SetupFacts(SetupDevice(mac = true), connected = true, server = null, accounts = accounts, ai = null, callAssistantOn = false,
                voiceMessageSeen = false, voiceChosen = null, nowMs = world.clock.nowMs),
        ).steps.first { it.key == SetupRules.TRAVEL_KEY }
        assertEquals(SetupState.TODO, step(listOf(off)).state)
        assertEquals(SetupRules.TRAVEL_ADD_KEY, step(listOf(off)).line)
        assertEquals(SetupState.DONE, step(listOf(off.copy(status = "ok"))).state)
        assertEquals(SetupState.LATER, step(emptyList()).state)
        assertEquals(SetupState.UNKNOWN, step(null).state)
    }
}
