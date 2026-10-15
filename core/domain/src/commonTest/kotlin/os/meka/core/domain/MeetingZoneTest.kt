package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scheduling assistant, slice 3: their time zone, and an answer that turns his times down. */
class MeetingZoneTest {
    private val cal = LocalCalendar.fixedOffset(0)
    private val hourMs = 3_600_000L
    private val newYork = LocalCalendar.fixedOffset(-4 * hourMs)
    private val thu = CivilDate.toEpochDay(2026, 10, 15) // a Thursday
    private val work = WorkHours(WorkSchedule.WEEKDAYS)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private fun label(s: FreeSlot) = MeetingRequestRules.slotLabel(s, cal)

    @Test
    fun readsAZoneOnlyFromThePlacesListAndCapitals() {
        fun named(t: String) = MeetingZoneRules.named(listOf(t))
        assertEquals(NamedZone("America/New_York", "New York"), named("Happy to chat, I'm based in New York"))
        assertEquals(NamedZone("America/New_York", "New York"), named("Anything after 9am EST works"))
        assertEquals(NamedZone("Asia/Singapore", "Singapore"), named("We’re in Singapore so mornings are best"))
        assertEquals(NamedZone("Europe/Paris", "Paris"), named("How about 10:00 CET?"))
        assertEquals(NamedZone("Europe/Paris", "Paris"), named("Afternoons Paris time suit me"))
        assertEquals(NamedZone("America/Los_Angeles", "Los Angeles"), named("I'm on Pacific time"))
        assertNull(named("Photos from the Paris trip attached"))
        assertNull(named("The company was est. 2010, the pt rate is low"))
        assertNull(named("Can we meet next week?"))
        // The first one named wins; the subject before the preview.
        assertEquals("Tokyo", MeetingZoneRules.named(listOf("Call from Tokyo time", "I'm in Sydney"))?.label)
    }

    @Test
    fun timesStayInsideTheirWorkingDayAndAvoidTheDaysTheyTurnedDown() {
        val now = at(thu, 16)
        val plain = MeetingRequestRules.slots(now, work, emptyList(), { emptyList() }, cal)
        val ny = MeetingRequestRules.slots(now, work, emptyList(), { emptyList() }, cal, theirs = newYork)
        assertEquals(3, ny.size)
        assertTrue(ny.all { MeetingZoneRules.inTheirDay(it.startMs, 60, newYork) }, ny.map(::label).toString())
        assertTrue(ny.all { cal.minuteOfDay(it.startMs) >= 12 * 60 })
        // Tokyo's working day never meets his: the times are as they'd be without it.
        val tokyo = MeetingRequestRules.slots(now, work, emptyList(), { emptyList() }, cal, theirs = LocalCalendar.fixedOffset(9 * hourMs))
        assertEquals(plain, tokyo)
        // Times he offered before: their days are left out.
        val again = MeetingRequestRules.slots(now, work, emptyList(), { emptyList() }, cal, avoid = plain)
        val avoided = plain.map { cal.epochDayOf(it.startMs) }.toSet()
        assertTrue(again.none { cal.epochDayOf(it.startMs) in avoided })
        assertEquals(3, again.size)
    }

    @Test
    fun theDraftWritesTheirTimeAndSaysNoProblemTheSecondTime() {
        val slots = listOf(FreeSlot(at(thu + 1, 13), at(thu + 1, 14)))
        val ny = TheirZone(newYork, "New York")
        assertEquals(
            "Hi Sam,\n\nHappy to meet. I'm free at this time (UK time):\n\n" +
                "• Friday 16 October, 13:00–14:00 (09:00–10:00 New York time)\n\n" +
                "Let me know if that suits you and I'll put it in the diary.",
            MeetingRequestRules.draft("Sam Taylor", slots, cal, "UK time", null, ny),
        )
        val sydney = TheirZone(LocalCalendar.fixedOffset(11 * hourMs), "Sydney")
        assertEquals(" (Sat 00:00–01:00 Sydney time)", MeetingZoneRules.theirPart(slots[0], cal, sydney))
        assertEquals("", MeetingZoneRules.theirPart(slots[0], cal, TheirZone(cal, "Lisbon")))
        val again = MeetingRequestRules.draft("Sam Taylor", slots + FreeSlot(at(thu + 5, 10), at(thu + 5, 11)), cal, "UK time", "Alex", again = true)!!
        assertTrue(again.startsWith("Hi Sam,\n\nNo problem. How about one of these instead (UK time):\n\n• Friday 16 October, 13:00–14:00\n"), again)
    }

    @Test
    fun theirTimeInHisSentReplyIsReadBackAndPicks() {
        val sent = "Hi Sam,\n\n• Friday 16 October, 13:00–14:00 (09:00–10:00 New York time)\n" +
            "• Tuesday 20 October, 15:00–16:00 (11:00–12:00 New York time)\n\nAlex"
        val offered = MeetingPickRules.offered(sent, at(thu, 10), cal)
        assertEquals(listOf(FreeSlot(at(thu + 1, 13), at(thu + 1, 14), 9 * 60), FreeSlot(at(thu + 5, 15), at(thu + 5, 16), 11 * 60)), offered)
        assertEquals(offered[0], MeetingPickRules.pick("9am my time works great", offered, cal))
        assertEquals(offered[1], MeetingPickRules.pick("Tuesday 11am please", offered, cal))
        assertEquals(offered[1], MeetingPickRules.pick("Tuesday at 3pm your time", offered, cal))
        assertNull(MeetingPickRules.pick("10am?", offered, cal))
    }

    private fun item(id: String, subject: String, preview: String?, receivedAtMs: Long, replyText: String? = null, replySentAtMs: Long? = null) = MailItem(
        id = id, account = "Business", from = "Sam Taylor", subject = subject, preview = preview, receivedAtMs = receivedAtMs,
        threadCount = 1, goneAtMs = null, doneAtMs = null, undoneAtMs = null,
        doneAskedAtMs = replySentAtMs?.minus(30_000), undoAskedAtMs = null, snoozedUntilMs = null,
        replyText = replyText, replyAskedAtMs = replySentAtMs?.minus(30_000), replySentAtMs = replySentAtMs, threadKey = "th1",
    )

    @Test
    fun anAnswerTurningTheTimesDownOffersSuggestTimesAgainUntilTheOfferClears() {
        assertTrue(MeetingPickRules.declines("None of those work for me, sorry"))
        assertTrue(MeetingPickRules.declines("Can't make any of them unfortunately. Any other times next week?"))
        assertTrue(MeetingPickRules.declines("Those times don't work, what about next week?"))
        assertFalse(MeetingPickRules.declines("Tuesday works, thanks"))
        assertFalse(MeetingPickRules.declines("Great. On Thu Alex wrote: none of those work"))

        val sent = "Hi Sam,\n\n• Monday 19 October, 11:30–12:30\n• Tuesday 20 October, 13:00–14:00\n\nAlex"
        val offer = item("o", "Catch up?", "Can we meet next week?", at(thu, 9), replyText = sent, replySentAtMs = at(thu, 10))
        val answer = item("a", "Re: Catch up?", "Sorry, none of those work for me. Any other times?", at(thu, 15))
        val row = MailNeedsRules.card(listOf(offer, answer), at(thu, 16), cal)!!.rows.single()
        assertEquals("Sam can't make the times you offered · Suggest times finds others", row.pickedLine)
        assertTrue(row.asksToMeet)
        assertFalse(row.canBook)
        assertFalse(row.spoken.contains(MeetingRequestRules.ASKS))
        // A pick still wins over a decline of the rest.
        val picked = answer.copy(preview = "Monday doesn't work but Tuesday at 1pm is good")
        assertTrue(MailNeedsRules.card(listOf(offer, picked), at(thu, 16), cal)!!.rows.single().canBook)
        // Cleared: an answer more than 14 days after his reply, or after every time has passed, isn't read against it.
        val late = answer.copy(receivedAtMs = at(thu, 10) + 15 * 24 * hourMs)
        assertFalse(MeetingPickRules.declinedFor(late, listOf(offer, late), late.receivedAtMs, cal))
        assertNull(MailNeedsRules.card(listOf(offer, late), late.receivedAtMs, cal)!!.rows.single().pickedLine)
        assertFalse(MeetingPickRules.declinedFor(answer, listOf(offer, answer), at(thu + 6, 9), cal))
        assertNull(MeetingPickRules.pickFor(answer.copy(preview = "Tuesday please", receivedAtMs = at(thu, 10) + 15 * 24 * hourMs), listOf(offer, answer), at(thu, 16), cal))
    }
}
