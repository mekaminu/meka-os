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

    @Test
    fun whenYouLastMetEachGuest() {
        val now = at(13, 50)
        val meeting = event("a", 14, listOf("Sam Taylor", "Alex Green", "Robin Lee"))
        val events = listOf(
            meeting,
            event("old", 10, listOf("Sam Taylor"), title = "Quote kickoff").copy(startAtMs = at(10, d = day - 9), endAtMs = at(11, d = day - 9)),
            event("newer", 10, listOf("Sam"), title = "Site visit").copy(startAtMs = at(10, d = day - 1), endAtMs = at(11, d = day - 1)),
            event("hidden", 9, listOf("Sam Taylor"), title = "Hidden one"),
            event("later", 16, listOf("Alex Green"), title = "Later today"),
            event("alexToday", 9, listOf("Alex Green"), title = "Stand-up"),
            event("solo", 8, emptyList(), title = "Focus"),
            event("ancient", 10, listOf("Robin Lee")).copy(startAtMs = at(10, d = day - 400), endAtMs = at(11, d = day - 400)),
        )
        val marks = EventMarks(hidden = setOf("hidden"), prepTasks = emptyMap())
        val p = MeetingPrepRules.prep(meeting, emptyList(), emptyList(), emptyList(), now, cal, events, marks)
        assertEquals("Last met yesterday · “Site visit”", p.people[0].lastMet)
        assertEquals("Last met earlier today · “Stand-up”", p.people[1].lastMet)
        assertNull(p.people[2].lastMet) // over a year ago
        assertEquals(listOf("Sam Taylor · last met yesterday · “Site visit”", "Alex Green · last met earlier today · “Stand-up”"), p.lines)
        assertEquals("Last met Tue 6 Oct · “Quote kickoff”", MeetingPrepRules.lastMet("Sam Taylor", meeting, events - events[2], marks, now, cal))
        // With no email held, the heads-up says when they last met.
        val n = MeetingPrepRules.notices(events, marks, emptyList(), emptyList(), emptyList(), at(10), cal).first { it.key == "prep:a:${at(14)}" }
        assertEquals("With Sam Taylor, Alex Green and Robin Lee · last met yesterday", n.text)
    }

    @Test
    fun hisLeadTimeOrOff() {
        val events = listOf(event("a", 14, listOf("Sam Taylor")))
        fun card(now: Long, lead: Int) = MeetingPrepRules.card(events, noMarks, emptyList(), emptyList(), emptyList(), now, cal, lead)
        assertNull(card(at(13, 29), 30))
        assertEquals("Prep · Quote review at 14:00 · in 30 min", card(at(13, 30), 30)!!.headline)
        assertNull(card(at(13, 54), 5))
        assertEquals("Prep · Quote review at 14:00 · in 5 min", card(at(13, 55), 5)!!.headline)
        assertNull(card(at(13, 58), 0))
        assertEquals(at(13, 30), MeetingPrepRules.notices(events, noMarks, emptyList(), emptyList(), emptyList(), at(10), cal, 30).single().atMs)
        assertTrue(MeetingPrepRules.notices(events, noMarks, emptyList(), emptyList(), emptyList(), at(10), cal, 0).isEmpty())
        // The setting: absent is 15 min; stored minutes come back to their chip; 0 is Off.
        assertEquals(MeetingPrepLead.FIFTEEN, MeetingPrepRules.read(null))
        assertEquals(MeetingPrepLead.THIRTY, MeetingPrepRules.read(MeetingPrepRules.settingFields(MeetingPrepLead.THIRTY, 1L)))
        assertEquals(MeetingPrepLead.OFF, MeetingPrepRules.read(MeetingPrepRules.settingFields(MeetingPrepLead.OFF, 1L)))
        assertEquals(MeetingPrepLead.TEN, MeetingPrepLead.ofMinutes(7))
        assertEquals(MeetingPrepLead.THIRTY, MeetingPrepLead.ofMinutes(90))
        assertEquals("Off · no prep on Today and no heads-up before meetings", MeetingPrepRules.settingLine(MeetingPrepLead.OFF))
    }
}
