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

    // ---- Slice 2: asks without a date become tasks ----

    @Test
    fun asksWithoutADateAreReadAsTasks() {
        val coat = assertNotNull(read("Cold weather", "Please send in a named coat for Alex as the weather is turning."))
        assertTrue(coat.ask)
        assertEquals("Send in a named coat for Alex", coat.title)
        assertEquals("Alex", coat.who)
        assertEquals(received, coat.startDay)
        assertEquals(received + SchoolMailRules.ASK_DAYS, coat.endDay)
        assertEquals("Return the reply slip", assertNotNull(read("Reply slip", "Please could you return the reply slip.")).title)
        assertEquals("Pack wellies and a waterproof coat", assertNotNull(read("Forest school", "Children will need wellies and a waterproof coat.")).title)
        assertEquals("Label all uniform", assertNotNull(read(null, "Please remember to label all uniform, thank you.")).title)
        // An ask with a day of its own (and none of the dated kinds) is due that day.
        val dated = assertNotNull(read("Harvest", "Please send in a tin of food by Friday 23 October."))
        assertTrue(dated.ask)
        assertEquals("Send in a tin of food", dated.title)
        assertEquals(CivilDate.toEpochDay(2026, 10, 23), dated.startDay)
        assertEquals(dated.startDay, dated.endDay)
        // The dated kinds still win.
        assertFalse(assertNotNull(read("Trip payment", "Please pay £12 for the trip by 20 Nov.")).ask)
        // Not asks.
        assertNull(read("Newsletter", "Please find attached this week's newsletter."))
        assertNull(read("Allergies", "Please do not send in any nuts."))
        assertNull(read("Pick-up", "Children need to be collected from the side gate."))
        assertNull(read("Year 3", "Children have been learning about the Romans."))
        assertNull(read("Portal", "Please sign in to the portal with your password."))
        assertNull(read("Link", "Please book via https://example.org/slots"))
        // Long things are cut at a word.
        val long = assertNotNull(read(null, "Please send in old cardboard boxes, yoghurt pots and kitchen roll tubes for our model making week."))
        assertEquals("Send in old cardboard boxes", long.title)
        val longer = assertNotNull(read(null, "Please send in old cardboard boxes and yoghurt pots and kitchen roll tubes and egg boxes for making models."))
        assertTrue(longer.title.endsWith("…") && longer.title.length <= 60, longer.title)
    }

    @Test
    fun anAsksFindShowsAndMakesATask() {
        val s = assertNotNull(read("Cold weather", "Please send in a named coat for Alex."))
        val f = find(s)
        assertEquals(SchoolMailRules.ASK, f.schoolKind)
        assertTrue(SchoolMailRules.isAsk(f))
        assertTrue(SchoolMailRules.undated(f))
        val row = SchoolMailRules.row(f, received)
        assertEquals("Send in a named coat for Alex · no date given", row.line)
        assertEquals("Make Send in a named coat for Alex a task", row.addSpoken)
        val card = assertNotNull(MailRenewalRules.card(listOf(f), emptyList(), received * 86_400_000L + 3_600_000L, LocalCalendar.UTC))
        assertEquals("1 from the school · nothing is added until you tap Add", card.headline)
        // Offered for a week, then gone; answered, gone.
        assertTrue(SchoolMailRules.showable(f, emptyList(), received + SchoolMailRules.ASK_DAYS))
        assertFalse(SchoolMailRules.showable(f, emptyList(), received + SchoolMailRules.ASK_DAYS + 1))
        assertFalse(SchoolMailRules.showable(find(s, MailFindState.ADDED), emptyList(), received))
        // The task names the child once, says where it came from, and is due the next school morning at 08:00.
        assertEquals("Send in a named coat for Alex", SchoolMailRules.taskTitle(f))
        assertTrue(SchoolMailRules.taskNotes(f).startsWith("From Oakfield Primary's email “Subject” · Personal mail."), SchoolMailRules.taskNotes(f))
        val cal = LocalCalendar.UTC
        val thu = received * 86_400_000L
        assertEquals(received, SchoolMailRules.taskDueDay(f, thu + 7 * 3_600_000L, cal)) // Thu 07:00 → today
        assertEquals(received + 1, SchoolMailRules.taskDueDay(f, thu + 9 * 3_600_000L, cal)) // Thu 09:00 → Fri
        assertEquals(received + 4, SchoolMailRules.taskDueDay(f, thu + 86_400_000L + 9 * 3_600_000L, cal)) // Fri 09:00 → Mon
        assertEquals("Task added: Send in a named coat for Alex · due tomorrow, 08:00", SchoolMailRules.askAddedLine(f, thu + 9 * 3_600_000L, cal))
        // A dated ask is due that morning; a reminder within the fortnight is the same find.
        val dated = find(assertNotNull(read("Harvest", "Please send in a tin of food by Friday 23 October.")))
        assertFalse(SchoolMailRules.undated(dated))
        assertEquals(CivilDate.toEpochDay(2026, 10, 23), SchoolMailRules.taskDueDay(dated, thu, cal))
        val again = assertNotNull(SchoolMailRules.read("Reminder", "Please send in a named coat for Alex.", received + 2, kids))
        assertEquals(SchoolMailRules.findId("a", s), SchoolMailRules.findId("a", again))
    }

    // ---- Slice 3: weekly kit days into School ----

    private fun weekly(subject: String?, preview: String? = null) = SchoolMailRules.readAll(subject, preview, received, kids)

    @Test
    fun weeklyKitDaysAreReadOnePerWeekday() {
        val pe = weekly("PE this term", "Alex's class will have PE on Tuesdays and Thursdays this term.")
        assertEquals(2, pe.size)
        assertTrue(pe.all { it.kind == SchoolKind.WEEKLY && it.title == "PE" && it.who == "Alex" })
        // Thu 15 Oct: the first Thursday is today, the first Tuesday is the 20th.
        assertEquals(listOf(CivilDate.toEpochDay(2026, 10, 20), received), pe.map { it.startDay })
        assertEquals(pe[0].startDay + SchoolMailRules.WEEKLY_OFFER_DAYS, pe[0].endDay)
        assertEquals("Swimming", assertNotNull(read("Swimming", "Year 3 go swimming every Friday, please send a towel.")).title)
        assertEquals(SchoolKind.WEEKLY, assertNotNull(read("Forest school", "Forest school days are Wednesday this half term.")).kind)
        val p = weekly(null, "P.E. will be every Monday.").single()
        assertEquals("PE", p.title)
        assertEquals("", p.who)
        // A start date in the sentence is where it starts; the one-off date isn't a weekly day.
        val swim = weekly("Swimming lessons", "Swimming lessons start on Monday 2 November and will be every Monday.").single()
        assertEquals(CivilDate.toEpochDay(2026, 11, 2), swim.startDay)
        // Not weekly: one-offs, cancellations, no kit topic.
        assertTrue(weekly("PE", "There is PE on Tuesday 20 October.").none { it.kind == SchoolKind.WEEKLY })
        assertTrue(weekly("PE", "There will be no PE on Fridays any more.").none { it.kind == SchoolKind.WEEKLY })
        assertTrue(weekly("Clubs", "Chess club is every Tuesday.").none { it.kind == SchoolKind.WEEKLY })
        // Still read the other kinds: one find from readAll.
        assertEquals("Trip payment", weekly("Trip payment", "Please pay £12 for the trip by 20 Nov.").single().title)
    }

    @Test
    fun aWeeklyFindShowsAddsToSchoolAndStopsWhenSchoolHasIt() {
        val s = weekly("PE this term", "Alex's class will have PE on Tuesdays.").single()
        val f = find(s)
        assertTrue(SchoolMailRules.isWeekly(f))
        assertFalse(SchoolMailRules.isAsk(f))
        assertEquals("PE · every Tuesday · Alex", SchoolMailRules.row(f, received).line)
        val e = SchoolMailRules.entry(f)
        assertEquals(SchoolKind.WEEKLY, e.kind)
        assertEquals(2, e.weekday)
        assertEquals("Alex", e.who)
        assertEquals("In School: PE · every Tuesday · Kids shows “Alex: PE kit” the day before", SchoolMailRules.addedLine(f, received))
        // Add's line makes School's weekly item; on Monday evening it reads "Alex: PE kit".
        val added = SchoolRules.items(mapOf(SchoolMailRules.schoolItemId(f.id) to SchoolMailRules.schoolFields(f, 0L)))
        assertEquals(SchoolKind.WEEKLY, added.single().kind)
        assertFalse(SchoolMailRules.showable(f, added, received))
        // His own "Alex PE Tue" counts as having it; a different day or child doesn't.
        fun mine(title: String, who: String, weekday: Int) = SchoolItem("x", SchoolKind.WEEKLY, title, who, received - 30, received - 30, weekday, null, emptyList(), 0L)
        assertFalse(SchoolMailRules.showable(f, listOf(mine("PE kit", "Alex", 2)), received))
        assertTrue(SchoolMailRules.showable(f, listOf(mine("PE", "Sam", 2)), received))
        assertTrue(SchoolMailRules.showable(f, listOf(mine("PE", "Alex", 3)), received))
        // Offered until two weeks after its first day; next week's reminder lands on the same find.
        assertTrue(SchoolMailRules.showable(f, emptyList(), s.startDay + SchoolMailRules.WEEKLY_OFFER_DAYS))
        assertFalse(SchoolMailRules.showable(f, emptyList(), s.startDay + SchoolMailRules.WEEKLY_OFFER_DAYS + 1))
        val again = SchoolMailRules.readAll("Reminder", "Alex's class will have PE on Tuesdays.", received + 7, kids).single()
        assertEquals(SchoolMailRules.findId("a", s), SchoolMailRules.findId("a", again))
        // A thing with no kit says so plainly.
        val lib = find(weekly(null, "Library books go back every Wednesday.").single())
        assertEquals("In School: Library books · every Wednesday · in Kids' week", SchoolMailRules.addedLine(lib, received))
    }
}
