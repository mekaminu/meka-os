package os.meka.core.domain

import os.meka.core.sync.EntityRef
import os.meka.core.sync.EntitySnapshot
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Meeting prep, slice 1: fifteen minutes before a meeting with other people, who's coming, each guest's last email and
 * what's open between them, on Today and as one heads-up. Invented people only (rule 13).
 */
class MeetingPrepTest {
    private val cal = LocalCalendar.fixedOffset(0)
    private val day = CivilDate.toEpochDay(2026, 10, 15)
    private fun at(h: Int, m: Int = 0, d: Long = day) = cal.toEpochMs(d, h * 60 + m)
    private val noMarks = EventMarks(hidden = emptySet(), prepTasks = emptyMap())

    private fun event(id: String, h: Int, guests: List<String>, title: String = "Quote review", allDay: Boolean = false) =
        CalendarEvent(id, title, at(h), at(h + 1), allDay, null, "google", "a", "Work", guests = guests)

    private fun mail(id: String, from: String, received: Long, subject: String = "Re: quote", replied: Long? = null) = MailItem(
        id = id, account = "Business", from = from, subject = subject, preview = null, receivedAtMs = received, threadCount = 1,
        goneAtMs = null, doneAtMs = null, undoneAtMs = null, doneAskedAtMs = null, undoAskedAtMs = null, snoozedUntilMs = null,
        replySentAtMs = replied,
    )

    private val promise = PromiseItem("p1", "Send the figures", "Sam", null, 0L, day, DueState.DUE, "Sam · due today", false)
    private val waitingFor = WaitingItem("w1", "Signed contract", "Sam Taylor", null, 0L, null, day + 1, DueState.LATER,
        "Sam Taylor · chase Fri 16 Oct", false)

    @Test
    fun guestsAreReadFromTheEventAndOnlyMeetingsWithOtherPeopleCount() {
        val snap = EntitySnapshot(
            EntityRef(EntityTypes.EVENT, "e1"), "hh",
            mapOf(
                EventFields.TITLE to FieldValue.Text("Quote review"), EventFields.START_AT to FieldValue.Int64(at(14)),
                EventFields.GUESTS to FieldValue.Text("Sam Taylor\nAlex Green\n\nSam Taylor"),
            ),
            Hlc(1, 0, "server"),
        )
        assertEquals(listOf("Sam Taylor", "Alex Green"), CalendarEvent.from(snap)!!.guests)
        assertTrue(MeetingPrepRules.isMeeting(event("a", 14, listOf("Sam"))))
        assertTrue(!MeetingPrepRules.isMeeting(event("b", 14, emptyList())))
        assertTrue(!MeetingPrepRules.isMeeting(event("c", 14, listOf("Sam"), allDay = true)))
    }

    @Test
    fun theCardShowsFromFifteenMinutesBeforeUntilTenAfterTheStart() {
        val events = listOf(event("a", 14, listOf("Sam Taylor")), event("solo", 13, emptyList()))
        fun card(now: Long) = MeetingPrepRules.card(events, noMarks, emptyList(), emptyList(), emptyList(), now, cal)
        assertNull(card(at(13, 44)))
        assertEquals("Prep · Quote review at 14:00 · in 12 min", card(at(13, 48))!!.headline)
        assertEquals("Prep · Quote review · started 14:00", card(at(14, 5))!!.headline)
        assertNull(card(at(14, 10)))
        // Hidden events stay quiet.
        assertNull(MeetingPrepRules.card(events, EventMarks(hidden = setOf("a"), prepTasks = emptyMap()), emptyList(), emptyList(), emptyList(), at(13, 50), cal))
        assertEquals(listOf(MeetingPrepRules.NOTHING_LINE), card(at(13, 50))!!.lines)
    }

    @Test
    fun eachGuestsLastEmailAndWhatsOpenBetweenYou() {
        val now = at(13, 50)
        val mails = listOf(
            mail("m1", "Sam Taylor", at(9, d = day - 2), "Quote"),
            mail("m2", "Sam Taylor", at(16, d = day - 1), "Re: quote", replied = at(9, 40)),
            mail("m3", "Robin Lee", at(8), "Lunch"),
        )
        val p = MeetingPrepRules.prep(event("a", 14, listOf("Sam Taylor", "Alex Green")), mails, listOf(promise), listOf(waitingFor), now, cal)
        assertEquals("With Sam Taylor and Alex Green", p.whoLine)
        val sam = p.people.first()
        assertEquals("You replied 09:40 · “Re: quote”", sam.lastEmail)
        assertEquals(listOf("You promised: Send the figures · due today", "Waiting for: Signed contract · chase Fri 16 Oct"), sam.owed)
        assertEquals(PrepPerson("Alex Green", null, emptyList()), p.people[1])
        assertEquals(
            listOf(
                "Sam Taylor · you replied 09:40 · “Re: quote”",
                "Sam Taylor · you promised: Send the figures · due today",
                "Sam Taylor · waiting for: Signed contract · chase Fri 16 Oct",
            ),
            p.lines,
        )
        assertTrue(p.spoken.startsWith("Prep. Quote review at 14:00. in 10 min. With Sam Taylor and Alex Green. Sam Taylor."))
    }

    @Test
    fun whoLineAndLinesAreCapped() {
        assertEquals("With A, B and C", MeetingPrepRules.whoLine(listOf("A", "B", "C")))
        assertEquals("With A, B and 3 more", MeetingPrepRules.whoLine(listOf("A", "B", "C", "D", "E")))
        val people = listOf(PrepPerson("A", "Last email 09:00", listOf("You promised: X", "Waiting for: Y")), PrepPerson("B", "Last email 08:00", emptyList()))
        assertEquals(listOf("A · last email 09:00", "A · you promised: X", "A · waiting for: Y", "B · last email 08:00"), MeetingPrepRules.lines(people))
        val more = people + PrepPerson("C", "Last email 07:00", emptyList())
        assertEquals("+2 more", MeetingPrepRules.lines(more).last())
        assertEquals(MeetingPrepRules.MAX_LINES, MeetingPrepRules.lines(more).size)
    }

    @Test
    fun oneHeadsUpFifteenMinutesBefore() {
        val events = listOf(event("a", 14, listOf("Sam Taylor")), event("b", 15, emptyList()), event("tomorrow", 14, listOf("Sam")).copy(startAtMs = at(14, d = day + 2), endAtMs = at(15, d = day + 2)))
        val n = MeetingPrepRules.notices(events, noMarks, listOf(mail("m1", "Sam Taylor", at(9))), listOf(promise), emptyList(), at(10), cal).single()
        assertEquals("prep:a:${at(14)}", n.key)
        assertEquals(NoticeSource.MEETING_PREP, n.source)
        assertEquals("Prep · Quote review at 14:00", n.title)
        assertEquals("With Sam Taylor · last email 09:00 · you promised 1", n.text)
        assertEquals(at(13, 45), n.atMs)
        assertEquals(at(14, 10), n.expiresAtMs)
        assertEquals(NoticeTarget.TODAY, n.target)
        assertTrue(NoticeSource.MEETING_PREP in NoticeSource.ADJUSTABLE)
    }
}
