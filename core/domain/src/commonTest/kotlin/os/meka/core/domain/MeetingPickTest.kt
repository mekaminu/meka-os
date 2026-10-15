package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeetingPickTest {
    private val cal = LocalCalendar.fixedOffset(0)
    private val thu = CivilDate.toEpochDay(2026, 10, 15) // a Thursday
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val hour = 3_600_000L

    /** What Suggest times wrote (slice 1), as he sent it. */
    private val sent = "Hi Sam,\n\nHappy to meet. I'm free at any of these times (UK time):\n\n" +
        "• Monday 19 October, 11:30–12:30\n• Tuesday 20 October, 13:00–14:00\n• Wednesday 21 October, 09:00–10:00\n\n" +
        "Let me know which suits you and I'll put it in the diary.\n\nAlex"
    private val mon = FreeSlot(at(thu + 4, 11, 30), at(thu + 4, 12, 30))
    private val tue = FreeSlot(at(thu + 5, 13), at(thu + 5, 14))
    private val wed = FreeSlot(at(thu + 6, 9), at(thu + 6, 10))
    private val offered = listOf(mon, tue, wed)

    private fun item(
        id: String, subject: String, preview: String?, receivedAtMs: Long, thread: String? = "th1",
        replyText: String? = null, replySentAtMs: Long? = null, editId: String? = null, bookedAt: Long? = null,
    ) = MailItem(
        id = id, account = "Business", from = "Sam Taylor", subject = subject, preview = preview, receivedAtMs = receivedAtMs,
        threadCount = 1, goneAtMs = null, doneAtMs = null, undoneAtMs = null,
        doneAskedAtMs = replySentAtMs?.minus(30_000), undoAskedAtMs = null, snoozedUntilMs = null,
        replyText = replyText, replyAskedAtMs = replySentAtMs?.minus(30_000), replySentAtMs = replySentAtMs,
        threadKey = thread, meetingEditId = editId, meetingAtMs = bookedAt,
    )

    @Test
    fun readsBackTheTimesHeOfferedAsHeSentThem() {
        assertEquals(offered, MeetingPickRules.offered(sent, at(thu, 10), cal))
        // His edit counts: a line he changed is read as changed, one he deleted is gone, his own prose isn't a time.
        val edited = "Hi Sam,\n\n- Tuesday 20 October, 15:00–15:30\nor Friday 23 October maybe?\n\nAlex"
        assertEquals(listOf(FreeSlot(at(thu + 5, 15), at(thu + 5, 15, 30))), MeetingPickRules.offered(edited, at(thu, 10), cal))
        // A weekday that doesn't fit the date is not read; nothing in an ordinary reply.
        assertEquals(emptyList(), MeetingPickRules.offered("• Monday 20 October, 13:00–14:00", at(thu, 10), cal))
        assertEquals(emptyList(), MeetingPickRules.offered("Thanks, see you then!", at(thu, 10), cal))
        // Across the new year: a January date after a December reply is next year's.
        val dec = CivilDate.toEpochDay(2026, 12, 30)
        val jan = CivilDate.toEpochDay(2027, 1, 4) // a Monday
        assertEquals(listOf(FreeSlot(at(jan, 10), at(jan, 11))), MeetingPickRules.offered("• Monday 4 January, 10:00–11:00", at(dec, 9), cal))
    }

    @Test
    fun picksTheOneOfferedTimeTheirWordsNameAndNothingAmbiguous() {
        fun pick(text: String) = MeetingPickRules.pick(text, offered, cal)
        assertEquals(tue, pick("Tuesday at 1pm works for me, thanks!"))
        assertEquals(tue, pick("Tue 13:00 is great"))
        assertEquals(wed, pick("Let's do the 21st"))
        assertEquals(wed, pick("21 Oct please"))
        assertEquals(mon, pick("11:30 is perfect"))
        assertEquals(mon, pick("The first one suits me"))
        assertEquals(wed, pick("Last option please"))
        assertEquals(wed, pick("Tuesday doesn't work for me, but Wednesday is fine"))
        assertEquals(wed, pick("No problem, Wednesday it is"))
        // A time he didn't offer, two of his times, or no time at all: nothing.
        assertNull(pick("Tuesday at 9am?"))
        assertNull(pick("Monday or Tuesday both work"))
        assertNull(pick("Thanks, I'll get back to you"))
        assertNull(pick("Can't do any of those, sorry"))
        // His own list quoted under their reply doesn't count.
        assertEquals(tue, pick("Tuesday please. On Thu, 15 Oct 2026 at 10:00, Alex wrote: • Monday 19 October, 11:30–12:30 • Wednesday 21 October"))
        assertNull(pick("On Thu, 15 Oct 2026 at 10:00, Alex wrote: • Monday 19 October, 11:30–12:30"))
    }

    @Test
    fun theirAnswerInTheSameThreadOffersBookItThenSaysBooked() {
        val offer = item("o", "Catch up?", "Can we meet next week?", at(thu, 9), replyText = sent, replySentAtMs = at(thu, 10))
        val answer = item("a", "Re: Catch up?", "Tuesday at 1pm works. On Thu Alex wrote: …", at(thu, 15))
        val p = MeetingPickRules.pickFor(answer, listOf(offer, answer), at(thu, 16), cal)!!
        assertEquals(MeetingPick("o", tue, "Meeting with Sam Taylor"), p)
        assertEquals("Sam picked Tue 20 Oct, 13:00–14:00 · one of the times you offered", MeetingPickRules.pickedLine("Sam Taylor", p.slot, cal))
        assertEquals(EventDraft("Meeting with Sam Taylor", tue.startMs, tue.endMs, false, notes = "Booked by Navilife from your Business email “Re: Catch up?”"),
            MeetingPickRules.draft(p, "Business", answer.subject))

        // On the card: the row says who picked what with Book it, and "Asks to meet" isn't offered on an answer.
        val row = MailNeedsRules.card(listOf(offer, answer), at(thu, 16), cal)!!.rows.single()
        assertEquals("Sam picked Tue 20 Oct, 13:00–14:00 · one of the times you offered", row.pickedLine)
        assertTrue(row.canBook)
        assertFalse(row.asksToMeet)
        // Booked (on any device): the line says so and Book it goes.
        val booked = answer.copy(meetingEditId = "ed1", meetingAtMs = tue.startMs)
        val bookedRow = MailNeedsRules.card(listOf(offer, booked), at(thu, 16), cal)!!.rows.single()
        assertEquals("Booked · Tue 20 Oct, 13:00–14:00 · in your calendar", bookedRow.pickedLine)
        assertFalse(bookedRow.canBook)

        // Not the same conversation, an answer from before his reply, or a time that has passed: nothing.
        assertNull(MeetingPickRules.pickFor(answer.copy(threadKey = "th2"), listOf(offer, answer), at(thu, 16), cal))
        assertNull(MeetingPickRules.pickFor(answer.copy(receivedAtMs = at(thu, 9, 30)), listOf(offer, answer), at(thu, 16), cal))
        assertNull(MeetingPickRules.pickFor(answer, listOf(offer, answer), tue.endMs, cal))
        // Without a thread key: the same sender and subject (Re: aside).
        assertEquals(tue, MeetingPickRules.pickFor(answer.copy(threadKey = null), listOf(offer.copy(threadKey = null), answer), at(thu, 16), cal)?.slot)
        // Too long after his reply.
        assertNull(MeetingPickRules.pickFor(answer.copy(receivedAtMs = at(thu, 10) + 22 * 24 * hour), listOf(offer, answer), at(thu, 16), cal))
    }
}
