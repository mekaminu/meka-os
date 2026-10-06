package os.meka.core.domain

import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import os.meka.core.sync.fv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventDetailTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    // London, BST (+1 h). Tue 6 Oct 2026 local midnight = 5 Oct 23:00 UTC.
    private val cal = LocalCalendar.fixedOffset(hour)
    private val oct6 = CivilDate.toEpochDay(2026, 10, 6)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val utcMidnight = { day: Long -> day * CivilDate.DAY_MS }

    private fun ev(
        start: Long, end: Long, allDay: Boolean = false, location: String? = null, provider: String = "google",
        description: String? = null, joinUrl: String? = null, calendarName: String? = "Personal",
    ) = CalendarEvent("e1", "Call with Tunde", start, end, allDay, location, provider, "meka@gmail.com", calendarName, description, joinUrl)

    @Test
    fun timedEventSaysWhenHowLongAndHowSoon() {
        val e = ev(at(oct6, 14), at(oct6, 15, 30))
        val d = EventDetails.build(e, at(oct6, 13, 35), cal)
        assertEquals("Tue 6 Oct · 14:00–15:30", d.whenLine)
        assertEquals("1 h 30", d.duration)
        assertEquals("In 25 min", d.status)
        assertTrue(d.statusLit)
        assertEquals("Personal · meka@gmail.com", d.calendarLine)

        assertEquals("In 4 h", EventDetails.build(e, at(oct6, 10), cal).status)
        assertFalse(EventDetails.build(e, at(oct6, 10), cal).statusLit)
        assertEquals("Now · ends 15:30", EventDetails.build(e, at(oct6, 14, 10), cal).status)
        assertEquals("Ended", EventDetails.build(e, at(oct6, 15, 30), cal).status)
        // More than a day ahead but tomorrow by date; then nothing further out.
        val tomorrowLate = ev(at(oct6 + 1, 20), at(oct6 + 1, 21))
        assertEquals("Tomorrow", EventDetails.build(tomorrowLate, at(oct6, 9), cal).status)
        assertNull(EventDetails.build(ev(at(oct6 + 5, 9), at(oct6 + 5, 10)), at(oct6, 9), cal).status)
    }

    @Test
    fun overnightAndZeroLengthEvents() {
        val overnight = EventDetails.build(ev(at(oct6, 22), at(oct6 + 1, 1)), at(oct6, 9), cal)
        assertEquals("Tue 6 Oct 22:00 – Wed 7 Oct 01:00", overnight.whenLine)
        assertEquals("3 h", overnight.duration)
        // Ending exactly at midnight stays on its own day.
        assertEquals("Tue 6 Oct · 23:00–00:00", EventDetails.build(ev(at(oct6, 23), at(oct6 + 1, 0)), at(oct6, 9), cal).whenLine)
        val point = EventDetails.build(ev(at(oct6, 9), at(oct6, 9)), at(oct6, 8), cal)
        assertEquals("Tue 6 Oct · 09:00", point.whenLine)
        assertNull(point.duration)
    }

    @Test
    fun allDayEventsUseCalendarDates() {
        val one = ev(utcMidnight(oct6), utcMidnight(oct6 + 1), allDay = true)
        val d = EventDetails.build(one, at(oct6, 0, 30), cal) // 5 Oct 23:30 UTC, already 6 Oct locally
        assertEquals("Tue 6 Oct · All day", d.whenLine)
        assertNull(d.duration)
        assertEquals("Today", d.status)
        assertTrue(d.statusLit)

        val trip = ev(utcMidnight(oct6), utcMidnight(oct6 + 3), allDay = true)
        val t = EventDetails.build(trip, at(oct6 - 1, 12), cal)
        assertEquals("Tue 6 – Thu 8 Oct · All day", t.whenLine)
        assertEquals("3 days", t.duration)
        assertEquals("Tomorrow", t.status)
        assertEquals("Ended", EventDetails.build(trip, at(oct6 + 3, 12), cal).status)
    }

    @Test
    fun providerConferenceLinkWinsAndIsLabelled() {
        val d = EventDetails.build(ev(at(oct6, 14), at(oct6, 15), joinUrl = "https://meet.google.com/abc-defg-hij"), at(oct6, 9), cal)
        assertEquals(JoinLink("https://meet.google.com/abc-defg-hij", "Join Google Meet"), d.join)
        val teams = EventDetails.build(ev(at(oct6, 14), at(oct6, 15), joinUrl = "https://teams.microsoft.com/l/meetup-join/19%3a"), at(oct6, 9), cal)
        assertEquals("Join Teams", teams.join?.label)
        // An unknown host from the provider is still a call link, just generically labelled.
        assertEquals("Join call", EventDetails.build(ev(0, 1, joinUrl = "https://calls.example.org/x"), 0, cal).join?.label)
        // Never anything that isn't https.
        assertNull(EventDetails.build(ev(0, 1, joinUrl = "http://meet.google.com/abc"), 0, cal).join)
        assertNull(EventDetails.build(ev(0, 1, joinUrl = "javascript:alert(1)"), 0, cal).join)
    }

    @Test
    fun callLinksInNotesOrLocationOnlyForKnownServices() {
        val notes = "Agenda below.\nJoin: https://us02web.zoom.us/j/123456?pwd=xyz.\nOther: https://evil.example/zoom.us"
        val d = EventDetails.build(ev(0, 1, description = notes), 0, cal)
        assertEquals(JoinLink("https://us02web.zoom.us/j/123456?pwd=xyz", "Join Zoom"), d.join)
        // Look-alike hosts and user-info tricks are not call links.
        assertNull(EventDetails.findCallLink("https://meet.google.com.evil.example/x"))
        assertNull(EventDetails.findCallLink("https://meet.google.com@evil.example/x"))
        assertNull(EventDetails.findCallLink("https://notzoom.us/j/1"))
        assertNull(EventDetails.findCallLink("see http://meet.google.com/abc"))
        // A location that is just a call link joins, and isn't offered to Maps.
        val loc = EventDetails.build(ev(0, 1, location = "https://teams.live.com/meet/9382"), 0, cal)
        assertEquals("Join Teams", loc.join?.label)
        assertNull(loc.mapsQuery)
        assertEquals("https://teams.live.com/meet/9382", loc.location)
    }

    @Test
    fun placesGoToMapsAndNotesAreTidiedPlainText() {
        val d = EventDetails.build(ev(0, 1, location = " Camp Nou, Barcelona ", description = "Line one  \r\n\r\n\r\n\nLine two\n   \n"), 0, cal)
        assertEquals("Camp Nou, Barcelona", d.location)
        assertEquals("Camp Nou, Barcelona", d.mapsQuery)
        assertEquals("Line one\n\nLine two", d.notes)
        assertNull(EventDetails.build(ev(0, 1, description = " \n "), 0, cal).notes)
        val long = EventDetails.cleanNotes("x".repeat(EventDetails.MAX_NOTES + 50))!!
        assertEquals(EventDetails.MAX_NOTES + 1, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun fixturesAndOutlookSayWhereTheyComeFrom() {
        val fixture = EventDetails.build(ev(0, 1, provider = "fixtures", calendarName = "FC Barcelona"), 0, cal)
        assertEquals("FC Barcelona · Fixture", fixture.calendarLine)
        assertTrue(fixture.isFixture)
        val outlook = EventDetails.build(ev(0, 1, provider = "microsoft", calendarName = "Calendar"), 0, cal)
        assertEquals("Calendar · Outlook · meka@gmail.com", outlook.calendarLine)
    }

    @Test
    fun notesAndJoinLinkAreReadFromTheMirroredEvent() {
        var n = 0
        val replica = Replica("hh", "server", HlcClock("server", { 1_000L }), InMemoryReplicaStore(), MekaSchema) { "op" + n++ }
        replica.commitLocal(EntityTypes.EVENT, "ev1", mapOf(
            EventFields.TITLE to "Standup".fv(), EventFields.START_AT to 0L.fv(), EventFields.END_AT to 1L.fv(),
            EventFields.DESCRIPTION to "Daily sync".fv(), EventFields.JOIN_URL to "https://meet.google.com/x".fv(),
        ))
        replica.commitLocal(EntityTypes.EVENT, "ev2", mapOf(
            EventFields.TITLE to "Old".fv(), EventFields.START_AT to 0L.fv(), EventFields.DESCRIPTION to "  ".fv(),
        ))
        val all = CalendarEvents(replica).all().associateBy { it.id }
        assertEquals("Daily sync", all.getValue("ev1").description)
        assertEquals("https://meet.google.com/x", all.getValue("ev1").joinUrl)
        // Events mirrored before notes existed simply have none.
        assertNull(all.getValue("ev2").description)
        assertNull(all.getValue("ev2").joinUrl)
    }
}
