package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeetingRequestTest {
    private val cal = LocalCalendar.fixedOffset(0)
    private val thu = CivilDate.toEpochDay(2026, 10, 15) // a Thursday
    private val work = WorkHours(WorkSchedule.WEEKDAYS)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private fun event(day: Long, from: Int, to: Int, title: String = "Busy", allDay: Boolean = false) = CalendarEvent(
        id = "e$day$from", title = title, startAtMs = if (allDay) cal.toEpochMs(day, 0) else cal.toEpochMs(day, from),
        endAtMs = if (allDay) cal.toEpochMs(day + 1, 0) else cal.toEpochMs(day, to), allDay = allDay,
        location = null, provider = "google", account = null, calendarName = null,
    )
    private fun label(s: FreeSlot) = MeetingRequestRules.slotLabel(s, cal)

    @Test
    fun asksToMeetFromThePersonsWordsButNotInvitesOrNewsletters() {
        assertTrue(MeetingRequestRules.asks("Catch up?", "Hi Alex, can we meet next week to go over the plan?"))
        assertTrue(MeetingRequestRules.asks("Quick question", "Are you free for a quick call on Tuesday?"))
        assertTrue(MeetingRequestRules.asks("Project", "When works for you? Happy to come to the office."))
        assertTrue(MeetingRequestRules.asks("Coffee", "Fancy grabbing a coffee sometime?"))
        assertFalse(MeetingRequestRules.asks("Invitation: Weekly sync @ Tue 20 Oct", "Sam has invited you to Weekly sync"))
        assertFalse(MeetingRequestRules.asks("Catch up on this week's news", "Our newsletter is here"))
        assertFalse(MeetingRequestRules.asks("Accepted: Can we meet?", null))
        assertFalse(MeetingRequestRules.asks("Your order", "Thanks for your order, it ships tomorrow"))
        assertEquals(30, MeetingRequestRules.minutes("Quick call?", "Are you free for a quick call?"))
        assertEquals(15, MeetingRequestRules.minutes(null, "Could we have a 15 min chat?"))
        assertEquals(60, MeetingRequestRules.minutes("Plan", "Can we meet for an hour?"))
    }

    @Test
    fun threeFreeTimesOnDifferentWorkDaysClearOfEventsMorningThenAfternoon() {
        val now = at(thu, 16, 0) // Thursday 16:00: today's hours are nearly gone
        val events = listOf(
            event(thu + 1, 9 * 60, 11 * 60), // Fri 09:00–11:00 → free from 11:15, so 11:30
            event(thu + 4, 0, 0, title = "Annual leave", allDay = true), // Mon off
        )
        val slots = MeetingRequestRules.slots(now, work, events, { emptyList() }, cal)
        assertEquals(
            listOf("Friday 16 October, 11:30–12:30", "Tuesday 20 October, 13:00–14:00", "Wednesday 21 October, 09:00–10:00"),
            slots.map(::label),
        )
    }

    @Test
    fun protectedTimeAndWorkHoursAreKeptAndShortMeetingsFitGaps() {
        val now = at(thu, 8, 0)
        // Thursday is busy until 14:45 (15:00 with the buffer) and protected from 15:30: no hour there, but 15 minutes fit.
        val events = listOf(event(thu, 9 * 60, 14 * 60 + 45))
        val protected = { d: Long -> if (d == thu) listOf(DayPlanner.Slot(at(thu, 15, 30), cal.toEpochMs(thu + 1, 0))) else emptyList() }
        val hour = MeetingRequestRules.slots(now, work, events, protected, cal, 60)
        assertEquals("Friday 16 October, 09:00–10:00", label(hour[0]))
        val half = MeetingRequestRules.slots(now, work, events, protected, cal, 15)
        assertEquals("Thursday 15 October, 15:00–15:15", label(half[0]))
    }

    @Test
    fun theDraftGreetsByFirstNameListsTheTimesAndSignsWithHisName() {
        val slots = listOf(FreeSlot(at(thu + 1, 10), at(thu + 1, 11)), FreeSlot(at(thu + 5, 14), at(thu + 5, 15)))
        val text = MeetingRequestRules.draft("Sam Taylor", slots, cal, MeetingRequestRules.zoneLabel("Europe/London"), "Alex")!!
        assertEquals(
            "Hi Sam,\n\nHappy to meet. I'm free at any of these times (UK time):\n\n" +
                "• Friday 16 October, 10:00–11:00\n• Tuesday 20 October, 14:00–15:00\n\n" +
                "Let me know which suits you and I'll put it in the diary.\n\nAlex",
            text,
        )
        assertTrue(MeetingRequestRules.draft("bookings@example.com", slots, cal, null, null)!!.startsWith("Hi,\n\n"))
        assertNull(MeetingRequestRules.draft("Sam", emptyList(), cal, null, null))
        assertEquals("Hi,", MeetingRequestRules.greeting("Oakfield School Office Team"))
    }

    @Test
    fun theRowSaysAsksToMeetUntilHeReplies() {
        val now = at(thu, 10)
        val item = MailItem(
            id = "m1", account = "Personal", from = "Sam Taylor", subject = "Catch up?", preview = "Can we meet next week?",
            receivedAtMs = now - 3_600_000L, threadCount = 1, goneAtMs = null, doneAtMs = null, undoneAtMs = null,
            doneAskedAtMs = null, undoAskedAtMs = null, snoozedUntilMs = null,
        )
        val row = MailNeedsRules.card(listOf(item), now, cal)!!.rows.single()
        assertTrue(row.asksToMeet)
        assertTrue(row.spoken.contains(MeetingRequestRules.ASKS))
        val other = item.copy(id = "m2", subject = "Invoice", preview = "Please find attached")
        assertFalse(MailNeedsRules.card(listOf(other), now, cal)!!.rows.single().asksToMeet)
    }
}
