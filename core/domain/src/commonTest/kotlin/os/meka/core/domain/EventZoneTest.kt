package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edit your calendars, completeness slice 6: an event in the zone it was set in. */
class EventZoneTest {
    private val hour = 3_600_000L
    private val tue = CivilDate.toEpochDay(2026, 10, 20)
    private val bst = LocalCalendar.fixedOffset(hour)
    private val newYork = LocalCalendar.fixedOffset(-4 * hour)
    private val tokyo = LocalCalendar.fixedOffset(9 * hour)

    private fun event(zone: String?, startMin: Int, lengthMin: Int = 60, allDay: Boolean = false): CalendarEvent {
        val start = bst.toEpochMs(tue, startMin)
        return CalendarEvent("ev1", "Call with Sam", start, start + lengthMin * 60_000L, allDay, null, "google", "alex@example.com", null, timeZone = zone)
    }

    @Test
    fun anEventSetInAnotherZoneShowsItsTimeThere() {
        // 14:00 here (UK) is 09:00 in New York.
        assertEquals("New York time · 09:00–10:00", EventZoneRules.line(event("America/New_York", 14 * 60), bst, newYork))
        // 18:00 here is 02:00 the next day in Tokyo, so the day is named.
        assertEquals("Tokyo time · Wed 21 Oct · 02:00–03:00", EventZoneRules.line(event("Asia/Tokyo", 18 * 60), bst, tokyo))
        // Crossing midnight there: the end names its own day.
        assertEquals(
            "Tokyo time · 14:00 – Wed 21 Oct 01:00",
            EventZoneRules.line(event("Asia/Tokyo", 6 * 60, lengthMin = 11 * 60), bst, tokyo),
        )
    }

    @Test
    fun abroadAHomeEventShowsUkTime() {
        // The phone has moved to New York's zone; a 14:00 UK meeting reads 09:00 here and "UK time · 14:00–15:00".
        assertEquals("UK time · 14:00–15:00", EventZoneRules.line(event("Europe/London", 14 * 60), newYork, bst))
    }

    @Test
    fun nothingWhenTheClocksAgreeTheZoneIsUnknownOrAllDay() {
        assertNull(EventZoneRules.line(event("Europe/London", 14 * 60), bst, bst))
        // Lisbon keeps the UK's clock: same times, so nothing to add.
        assertNull(EventZoneRules.line(event("Europe/Lisbon", 14 * 60), bst, LocalCalendar.fixedOffset(hour)))
        assertNull(EventZoneRules.line(event(null, 14 * 60), bst, newYork))
        assertNull(EventZoneRules.line(event("America/New_York", 14 * 60), bst, null))
        assertNull(EventZoneRules.line(event("America/New_York", 0, allDay = true), bst, newYork))
    }

    @Test
    fun labelsReadLikeAPerson() {
        assertEquals("New York time", EventZoneRules.label("America/New_York"))
        assertEquals("Buenos Aires time", EventZoneRules.label("America/Argentina/Buenos_Aires"))
        assertEquals("UK time", EventZoneRules.label("Europe/London"))
        assertEquals("UTC", EventZoneRules.label("Etc/UTC"))
        assertEquals("UTC−5", EventZoneRules.label("Etc/GMT+5"))
        assertEquals("UTC+3", EventZoneRules.label("Etc/GMT-3"))
        assertTrue(EventZoneRules.validId("America/New_York"))
        assertTrue(EventZoneRules.validId("UTC"))
        assertFalse(EventZoneRules.validId("Eastern Standard Time"))
        assertFalse(EventZoneRules.validId("../etc"))
    }

    @Test
    fun theFieldIsReadFromTheMirror() {
        val world = SyncWorld()
        val fold = world.device("android")
        val now = bst.toEpochMs(tue, 10 * 60)
        fold.replica.commitLocal(EntityTypes.EVENT, "ev1", mapOf(
            EventFields.TITLE to "Call".fv(), EventFields.START_AT to now.fv(), EventFields.PROVIDER to "google".fv(),
            EventFields.TIME_ZONE to "America/New_York".fv(),
        ))
        fold.replica.commitLocal(EntityTypes.EVENT, "ev2", mapOf(
            EventFields.TITLE to "Gym".fv(), EventFields.START_AT to now.fv(), EventFields.PROVIDER to "google".fv(),
        ))
        fold.replica.commitLocal(EntityTypes.EVENT, "ev3", mapOf(
            EventFields.TITLE to "Odd".fv(), EventFields.START_AT to now.fv(), EventFields.PROVIDER to "google".fv(),
            EventFields.TIME_ZONE to "Eastern Standard Time".fv(),
        ))
        val events = CalendarEvents(fold.replica).all().associateBy { it.id }
        assertEquals("America/New_York", events.getValue("ev1").timeZone)
        assertNull(events.getValue("ev2").timeZone)
        assertNull(events.getValue("ev3").timeZone)
    }
}
