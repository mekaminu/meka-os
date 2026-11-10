package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The approved Needs you look's compact TO DECIDE cards: icon, what, from whom, Add · Change · No. */
class NeedsYouDecideTest {
    private val cal = LocalCalendar.UTC
    private val day = CivilDate.toEpochDay(2026, 12, 14) // Mon 14 Dec 2026

    private fun card(person: String, text: String, p: RequestProposal, shared: Boolean = false) = MessageRequestRules.card(
        RequestMessage(if (shared) HandleThisRules.MESSAGE_PREFIX + "m1" else "m1", person, text, cal.toEpochMs(day, 9 * 60)), 0, p, day, cal,
    )

    @Test
    fun workFromHomeReadsAsTheMockup() {
        val c = NeedsYouDecideRules.card(card("Alex", "the Christmas show is that day",
            RequestProposal(RequestKind.WORK_FROM_HOME, "Work from home", day + 2, null)))
        assertEquals(NeedsYouDecideIcon.HOME, c.icon)
        assertEquals("Work from home · Wed 16 Dec", c.what)
        assertEquals("Alex · “the Christmas show is that day”", c.from)
        assertEquals("Add", c.yesLabel)
        assertNull(c.changeLabel) // nothing to edit on a work-from-home day
        assertEquals("No", c.noLabel)
        assertTrue(c.spoken.endsWith("Add, Not needed."))
    }

    @Test
    fun aTaskSaysTaskNotAddTask() {
        val c = NeedsYouDecideRules.card(card("Sam", "can you check the algorithm?",
            RequestProposal(RequestKind.TASK, "Check the algorithm", day, null)))
        assertEquals(NeedsYouDecideIcon.TASK, c.icon)
        assertEquals("Task: Check the algorithm · Today", c.what)
        assertEquals("Change", c.changeLabel)
        assertTrue(c.spoken.contains("Add, Change, Not a task."))
    }

    @Test
    fun eventsRemindersAndIdeasReadAsNouns() {
        assertTrue(NeedsYouDecideRules.card(card("Sam", "x", RequestProposal(RequestKind.EVENT, "Parents' evening", day + 1, 18 * 60))).what
            .startsWith("Event: Parents' evening"))
        assertTrue(NeedsYouDecideRules.card(card("Sam", "x", RequestProposal(RequestKind.REMINDER, "Call the garage", day, null))).what
            .startsWith("Reminder: Call the garage"))
        assertEquals(NeedsYouDecideIcon.REMINDER, NeedsYouDecideRules.icon(RequestKind.REMINDER))
    }

    @Test
    fun aSharedCardSaysYouShared() {
        val c = NeedsYouDecideRules.card(card("You", "Return the slip by Friday",
            RequestProposal(RequestKind.TASK, "Return the slip", day, null), shared = true))
        assertTrue(c.from.startsWith(HandleThisRules.PERSON + " · “"))
    }

    @Test
    fun aReplyKeepsItsFullCardForTheDraft() {
        val reply = card("You", "Can you confirm?", RequestProposal(RequestKind.REPLY, "Reply", null, null, reply = "Yes, that works."), shared = true)
        assertFalse(NeedsYouDecideRules.compact(reply))
        assertTrue(NeedsYouDecideRules.compact(card("Sam", "x", RequestProposal(RequestKind.TASK, "Check", day, null))))
    }

    @Test
    fun everyIconHasAPath() {
        RequestKind.entries.forEach { assertTrue(NeedsYouDecideRules.icon(it).path.startsWith("M")) }
    }

    @Test
    fun aSchoolDayOffIsOneCompactCard() {
        val cover = SchoolCover(
            id = "inset-1", title = "Alex and Sam are off Mon 21 Dec", line = "INSET day · in 7 days",
            question = "You're in the office that day. Who's covering?", detail = "Mon 21 Dec shows as work from home",
            days = listOf(day + 7), spoken = "Alex and Sam are off Monday 21 December.",
        )
        val c = NeedsYouDecideRules.school(cover)
        assertEquals(NeedsYouDecideIcon.SCHOOL, c.icon)
        assertEquals("Alex and Sam are off Mon 21 Dec", c.what)
        assertEquals("INSET day · in 7 days · You're in the office that day. Who's covering?", c.from)
        assertEquals(SchoolRules.HOME_LABEL, c.yesLabel)
        assertNull(c.changeLabel)
        assertEquals(SchoolRules.COVERED_LABEL, c.noLabel)
        assertTrue(c.more.isEmpty())
        assertEquals("Alex and Sam are off Monday 21 December. I'll work from home, Covered.", c.spoken)
    }

    @Test
    fun dateNightKeepsItsOwnTwoTaps() {
        val nudge = DateNightNudge(day + 7, "In 7 days · from 19:00", "Date night · Mon 21 Dec", "Book somewhere and arrange cover",
            "Booked takes this off Needs you.", "Date night in 7 days")
        val c = NeedsYouDecideRules.dateNight(nudge)
        assertEquals(NeedsYouDecideIcon.NIGHT, c.icon)
        assertEquals("Date night · Mon 21 Dec", c.what)
        assertEquals("In 7 days · from 19:00 · Book somewhere and arrange cover", c.from)
        assertEquals(DateNightRules.BOOKED_LABEL, c.yesLabel)
        assertEquals(DateNightRules.SKIP_LABEL, c.noLabel)
        assertEquals("Date night in 7 days. ${DateNightRules.BOOKED_LABEL}, ${DateNightRules.SKIP_LABEL}.", c.spoken)
        assertTrue(c.key != NeedsYouDecideRules.dateNight(nudge.copy(day = day + 21)).key)
    }

    @Test
    fun anAppointmentHasThreeTapsAndAQuietSecondRow() {
        val a = AppointmentNudge("dentist", "Due Mon 14 Dec · every 6 months", "Dentist", "Last went Sun 14 Jun. Booked the next one?", "", "Dentist due today.")
        val c = NeedsYouDecideRules.appointment(a)
        assertEquals(NeedsYouDecideIcon.APPOINTMENT, c.icon)
        assertEquals("Dentist", c.what)
        assertEquals("Due Mon 14 Dec · every 6 months · Last went Sun 14 Jun. Booked the next one?", c.from)
        assertEquals(listOf("Booked…", "Went", "Not yet"), listOf(c.yesLabel, c.changeLabel, c.noLabel))
        assertEquals(listOf("Call to book", "Copy email"), c.more)
        assertTrue(c.spoken.endsWith("Booked…, Went, Not yet, Call to book, Copy email."))
    }

    @Test
    fun anApprovalKeepsWhatWouldHappenWholeAndItsFingerprintNote() {
        val a = ApprovalCard(
            id = "a1", kind = ApprovalKind.TRADE, label = "TRADE · KESTREL", title = "Buy 0.05 BTC",
            lines = listOf("At market, about £2,100", " ", "Stop at £39,000"), why = "Asked 21:40 · expires in 14 min",
            biometric = true, urgent = true, approveLabel = ApprovalRules.APPROVE, declineLabel = ApprovalRules.DECLINE,
            spoken = "Kestrel asks: Trade. Buy 0.05 BTC. At market, about £2,100. Asked 21:40 · expires in 14 min",
        )
        val c = NeedsYouDecideRules.approval(a)
        assertEquals("approval-a1", c.key)
        assertEquals(NeedsYouDecideIcon.TRADE, c.icon)
        assertEquals("Buy 0.05 BTC", c.what)
        assertEquals("Trade · Kestrel · Asked 21:40 · expires in 14 min", c.from)
        assertEquals(listOf("At market, about £2,100", "Stop at £39,000"), c.details)
        assertEquals(ApprovalRules.APPROVE, c.yesLabel)
        assertEquals(null, c.changeLabel)
        assertEquals(ApprovalRules.DECLINE, c.noLabel)
        assertTrue(c.urgent)
        assertEquals(ApprovalRules.LOCK_FOLD, c.note)
        assertEquals(ApprovalRules.LOCK_MAC, NeedsYouDecideRules.approval(a, ApprovalRules.LOCK_MAC).note)
        assertTrue(c.spoken.endsWith("expires in 14 min. ${ApprovalRules.APPROVE} with your fingerprint, ${ApprovalRules.DECLINE}."))
        assertEquals(NeedsYouDecideIcon.PAYMENT, NeedsYouDecideRules.approval(a.copy(kind = ApprovalKind.PAYMENT)).icon)
        assertEquals(NeedsYouDecideIcon.SHARE, NeedsYouDecideRules.approval(a.copy(kind = ApprovalKind.SHARE)).icon)
        assertEquals("Payment · Bank app", NeedsYouDecideRules.sentence("PAYMENT · BANK APP"))
        assertEquals("Share · Drive", NeedsYouDecideRules.sentence("Share · Drive"))
    }
}
