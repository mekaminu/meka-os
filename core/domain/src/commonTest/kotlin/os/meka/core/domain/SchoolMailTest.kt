package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** School and family logistics from email, slice 1. Invented school, senders and children (rule 13). */
class SchoolMailTest {
    /** Thu 15 Oct 2026. */
    private val received = CivilDate.toEpochDay(2026, 10, 15)
    private val kids = listOf("Alex", "Sam")

    private fun read(subject: String?, preview: String? = null) = SchoolMailRules.read(subject, preview, received, kids)

    private fun find(s: SchoolMailFind, state: MailFindState = MailFindState.OPEN, id: String = "mf1"): MailFind {
        val fields = SchoolMailRules.serverFields(s, "Oakfield Primary", "Personal", "Subject", received) +
            MailRenewalRules.stateFields(state, 0L)
        return assertNotNull(MailRenewalRules.read(id, fields))
    }

    @Test
    fun aDayOffWithARange() {
        val f = assertNotNull(read("INSET days", "Reminder: the school is closed to pupils for INSET days 2–3 Nov."))
        assertEquals(SchoolKind.OFF, f.kind)
        assertEquals("INSET days", f.title)
        assertEquals(CivilDate.toEpochDay(2026, 11, 2), f.startDay)
        assertEquals(CivilDate.toEpochDay(2026, 11, 3), f.endDay)
        assertEquals("", f.who)
        val closed = assertNotNull(read("School closure Friday 23 October", "Due to strike action the school will be closed."))
        assertEquals(SchoolKind.OFF, closed.kind)
        assertEquals("School closed · strike", closed.title)
        assertEquals(CivilDate.toEpochDay(2026, 10, 23), closed.startDay)
    }

    @Test
    fun aPaymentWithAmountAndChild() {
        val f = assertNotNull(read("Year 3 trip to the Science Museum", "Please make the trip payment of £12.50 for Alex by 20 Nov on the school's payment site."))
        assertEquals(SchoolKind.DAY, f.kind)
        assertEquals("Trip payment", f.title)
        assertEquals(1250L, f.pence)
        assertEquals("Alex", f.who)
        assertEquals(CivilDate.toEpochDay(2026, 11, 20), f.startDay)
        assertTrue(SchoolRules.isDue(f.title))
    }

    @Test
    fun aFormATripAndAnEvent() {
        val form = assertNotNull(read("Consent form", "Please sign and return the consent form for the farm visit by Fri 6 Nov."))
        assertEquals("Trip consent form back", form.title)
        assertEquals(CivilDate.toEpochDay(2026, 11, 6), form.startDay)
        val trip = assertNotNull(read("Trip to Oakfield Farm on 18 November"))
        assertEquals("Trip to Oakfield Farm", trip.title)
        assertEquals(CivilDate.toEpochDay(2026, 11, 18), trip.startDay)
        assertFalse(SchoolRules.isDue(trip.title))
        val event = assertNotNull(read("Non-uniform day Friday 13 Nov", "Children may wear their own clothes for a £1 donation."))
        assertEquals("Non-uniform day", event.title)
        assertEquals(CivilDate.toEpochDay(2026, 11, 13), event.startDay)
        val evening = assertNotNull(read("Parents' evening", "Parents' evening is on Tuesday; book a slot for Sam."))
        assertEquals("Parents' evening", evening.title)
        assertEquals(CivilDate.toEpochDay(2026, 10, 20), evening.startDay)
        assertEquals("Sam", evening.who)
    }

    @Test
    fun nothingWithoutADateOrWhenOverOrNoise() {
        assertNull(read("Newsletter", "This week in Year 3 we learnt about the Romans."))
        assertNull(read("Trip payment received", "Thank you for your payment of £12.50 for the trip on 20 Nov."))
        assertNull(read("Your login code", "Your sign-in code is 123456 for the parent portal, valid until 20 Nov."))
        assertNull(read("Sports day cancelled", "Sports day on 20 Nov has been cancelled."))
        // A date before the email is gone.
        assertNull(read("Non-uniform day 2 Oct"))
        // Both children named: everyone's.
        assertEquals("", assertNotNull(read("Trip to the zoo 20 Nov for Alex and Sam")).who)
    }

    @Test
    fun onlyTheSchoolsSenders() {
        val a = AboutYou(schoolWebsite = "https://www.oakfield.sch.uk/term-dates", schoolMail = "news@parentmail.example, office@other.example")
        val s = SchoolMailRules.senders(a)
        assertEquals(setOf("oakfield.sch.uk", "news@parentmail.example", "office@other.example"), s)
        assertTrue(SchoolMailRules.fromSchool("office@oakfield.sch.uk", s))
        assertTrue(SchoolMailRules.fromSchool("<head@mail.oakfield.sch.uk>", s))
        assertTrue(SchoolMailRules.fromSchool("News@ParentMail.example", s))
        assertFalse(SchoolMailRules.fromSchool("other@parentmail.example", s))
        assertFalse(SchoolMailRules.fromSchool("office@notoakfield.sch.uk", s))
        assertFalse(SchoolMailRules.fromSchool("office@oakfield.sch.uk", emptySet()))
        assertTrue(SchoolMailRules.senders(AboutYou()).isEmpty())
    }

    @Test
    fun aboutYouKeepsTheSendersClean() {
        assertEquals("office@oakfield.sch.uk, parentmail.example", SchoolMailRules.cleanSenders("Office@Oakfield.sch.uk; https://www.parentmail.example/"))
        assertEquals("office@oakfield.sch.uk", SchoolMailRules.cleanSenders("office@oakfield.sch.uk office@oakfield.sch.uk"))
        assertNull(SchoolMailRules.cleanSenders("not a sender"))
        assertNull(SchoolMailRules.cleanSenders("a.example b.example c.example d.example e.example f.example g.example"))
        assertEquals("parentmail.example", AboutYouRules.clean(AboutField.SCHOOL_MAIL, "parentmail.example"))
        assertEquals("", AboutYouRules.clean(AboutField.SCHOOL_MAIL, "  "))
        assertNull(AboutYouRules.clean(AboutField.SCHOOL_MAIL, "<script>"))
        val read = AboutYouRules.read(mapOf(AboutYouFields.SCHOOL_MAIL to FieldValue.Text("office@oakfield.sch.uk")))
        assertEquals("office@oakfield.sch.uk", read.schoolMail)
        assertFalse(read.isEmpty)
    }

    @Test
    fun theFindRoundTripsAndShows() {
        val s = assertNotNull(read("Trip payment due", "Please pay £12 for Alex's trip by 20 Nov."))
        val f = find(s)
        assertEquals(SchoolMailRules.KIND_SCHOOL, f.kind)
        assertEquals("day", f.schoolKind)
        assertEquals("Alex", f.who)
        val today = received
        val row = SchoolMailRules.row(f, today)
        assertEquals("Oakfield Primary", row.title)
        assertEquals("Trip payment · £12 · due Fri 20 Nov · Alex", row.line)
        assertEquals("Add Trip payment to School", row.addSpoken)
        val card = assertNotNull(MailRenewalRules.card(listOf(f), emptyList(), today * 86_400_000L + 3_600_000L, LocalCalendar.UTC))
        assertEquals(SchoolMailRules.TITLE, card.title)
        assertEquals("1 to add to School · nothing goes in until you tap Add", card.headline)
        // Added: the line is in School and the find no longer shows.
        val e = SchoolMailRules.entry(f)
        assertEquals("Trip payment · £12", e.title)
        val item = SchoolRules.items(mapOf(SchoolMailRules.schoolItemId(f.id) to SchoolMailRules.schoolFields(f, 1L))).single()
        assertEquals(SchoolMailRules.SOURCE, item.source)
        assertEquals("Alex", item.who)
        assertEquals(SchoolMailRules.SOURCE_LINE, SchoolTerms.sourceLine(item))
        assertFalse(SchoolMailRules.showable(f, listOf(item), today))
        assertTrue(SchoolMailRules.showable(f, emptyList(), today))
        // One he typed himself for the same day is the same thing.
        val typed = item.copy(id = "typed", title = "Trip payment", source = null)
        assertFalse(SchoolMailRules.showable(f, listOf(typed), today))
        // Answered or over: not shown.
        assertFalse(SchoolMailRules.showable(find(s, MailFindState.DISMISSED), emptyList(), today))
        assertFalse(SchoolMailRules.showable(f, emptyList(), CivilDate.toEpochDay(2026, 11, 21)))
        assertTrue(SchoolMailRules.addedLine(f, today).startsWith("In School: Trip payment · due Fri 20 Nov · reminds you Wed 18 Nov"))
    }

    @Test
    fun aDayOffFindAndTheSameIdForAReminder() {
        val s = assertNotNull(read("INSET day Monday 2 November"))
        val again = assertNotNull(SchoolMailRules.read("Reminder: INSET day on 2 Nov", null, received + 7, kids))
        assertEquals(SchoolMailRules.findId("acct", s), SchoolMailRules.findId("acct", again))
        val f = find(s)
        assertTrue(SchoolMailRules.line(f, received).startsWith("INSET day · Mon 2 Nov · no school"))
        // Any day off he already has overlapping it covers it.
        val council = SchoolItem("c", SchoolKind.OFF, "October half term", "", CivilDate.toEpochDay(2026, 10, 26), CivilDate.toEpochDay(2026, 11, 2), 0, null, emptyList(), 0L, "council:x")
        assertFalse(SchoolMailRules.showable(f, listOf(council), received))
    }
}
