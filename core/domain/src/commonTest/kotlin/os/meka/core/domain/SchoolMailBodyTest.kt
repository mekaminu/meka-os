package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** School emails first, slice 2: the whole email read. Invented school, senders and children (rule 13). */
class SchoolMailBodyTest {
    /** Tue 24 Nov 2026. */
    private val received = CivilDate.toEpochDay(2026, 11, 24)
    private val kids = listOf("Alex", "Sam")
    private val sat = CivilDate.toEpochDay(2026, 11, 28)

    private val fair = """
        Dear Parents and Carers,

        We are delighted to invite you to the Oakfield PTA Christmas Fair! It will take place on Saturday 28th November from 12pm-4pm in the school hall.
        Entry is £1 per adult, children free. There will be a tombola, games and Santa's grotto.
        Please bring a bottle for the tombola to the school office by Friday 27 November.
        Children may wear their Christmas jumpers on the day.
        Please note there is no parking at school; please walk or park at the leisure centre.
        For more details visit https://example.org/fair.

        Many thanks,
        The PTA
    """.trimIndent()

    @Test
    fun aLongEmailIsReadWholeWithItsTimePlaceAndNotes() {
        val r = SchoolBodyRules.read("Christmas Fair", fair, received, kids)
        val event = assertNotNull(r.finds.firstOrNull { it.find.title == "School fair" })
        assertEquals(sat, event.find.startDay)
        assertEquals(12 * 60, event.startMinute)
        assertEquals(16 * 60, event.endMinute)
        assertEquals("the school hall", event.place)
        assertTrue(event.notes.any { it.startsWith("Bring a bottle") }, event.notes.toString())
        assertTrue(event.notes.any { it.contains("parking", ignoreCase = true) }, event.notes.toString())

        val card = r.summary.associate { it.label to it.value }
        assertEquals("Sat 28 Nov, 12:00–16:00", card[SchoolBodyRules.WHEN])
        assertEquals("The school hall", card[SchoolBodyRules.WHERE])
        assertEquals("£1 per adult", card[SchoolBodyRules.COST])
        assertEquals("Wear Christmas jumpers · No parking at school", card[SchoolBodyRules.BRING])
        // The bottle to bring by Friday is its own find and the card's Deadline, not repeated under Bring / do.
        assertEquals("Bring a bottle for the tombola to the school office · Fri 27 Nov", card[SchoolBodyRules.DEADLINE])
        assertTrue(r.finds.any { it.find.ask && it.find.startDay == CivilDate.toEpochDay(2026, 11, 27) })
        assertTrue(r.summary.size <= SchoolBodyRules.MAX_LINES)
        // Nothing from the link.
        assertTrue(r.summary.none { "http" in it.value })
    }

    @Test
    fun severalDatesInOneEmailAreSeveralFinds() {
        val body = """
            Dear Parents,
            Here are the dates for the rest of term.
            Christmas jumper day is on Friday 4 December.
            The Year 2 nativity will be on Wednesday 9 December at 2.30pm in the hall.
            Please return the consent form for the pantomime trip by Monday 7 December.
            School closes for Christmas on Friday 18 December at 1.30pm.
        """.trimIndent()
        val r = SchoolBodyRules.read("Dates for your diary", body, received, kids)
        val titles = r.finds.map { it.find.title }
        assertTrue("Christmas jumper day" in titles, titles.toString())
        assertTrue("Christmas performance" in titles, titles.toString())
        val nativity = r.finds.first { it.find.title == "Christmas performance" }
        assertEquals(CivilDate.toEpochDay(2026, 12, 9), nativity.find.startDay)
        assertEquals(14 * 60 + 30, nativity.startMinute)
        assertNull(nativity.endMinute)
        assertTrue(r.finds.any { it.find.title.contains("consent form back") && it.find.startDay == CivilDate.toEpochDay(2026, 12, 7) }, titles.toString())
        assertTrue(r.finds.size in 3..SchoolBodyRules.MAX_FINDS)
        // Sorted by day.
        assertEquals(r.finds.map { it.find.startDay }.sorted(), r.finds.map { it.find.startDay })
        val card = r.summary.associate { it.label to it.value }
        assertTrue(card[SchoolBodyRules.WHEN]!!.contains("more"), card.toString())
        assertTrue(card[SchoolBodyRules.DEADLINE]!!.contains("Mon 7 Dec"), card.toString())
    }

    @Test
    fun timesAreReadLikeAPersonWould() {
        assertEquals(12 * 60 to 16 * 60, SchoolBodyRules.times("from 12-4pm"))
        assertEquals(9 * 60 + 15 to 10 * 60 + 30, SchoolBodyRules.times("9.15am to 10.30am"))
        assertEquals(10 * 60 to 14 * 60, SchoolBodyRules.times("10am – 2pm"))
        assertEquals(18 * 60 to 20 * 60, SchoolBodyRules.times("6-8pm"))
        assertEquals(15 * 60 + 15 to 16 * 60 + 30, SchoolBodyRules.times("15:15 - 16:30"))
        assertEquals((12 * 60) to null, SchoolBodyRules.times("starts at 12 noon"))
        assertEquals((14 * 60 + 30) to null, SchoolBodyRules.times("at 2.30pm"))
        // Dates, years and money are not times.
        assertNull(SchoolBodyRules.times("INSET days 2–3 Nov"))
        assertNull(SchoolBodyRules.times("Years 1-2 and £2.50"))
        assertEquals("12:00–16:00", SchoolBodyRules.timeLabel(720, 960))
        assertEquals("09:05", SchoolBodyRules.timeLabel(545, null))
    }

    @Test
    fun whoItIsForAndNothingFromLinks() {
        assertEquals("Year 3", SchoolBodyRules.forWhom("A letter for Year 3 parents"))
        assertEquals("Whole school", SchoolBodyRules.forWhom("open to the whole school"))
        assertEquals("KS2", SchoolBodyRules.forWhom("all ks2 pupils"))
        assertTrue(SchoolBodyRules.notes("Please click here to pay online at www.example.org").isEmpty())
        assertNull(SchoolBodyRules.cost("Pay £5 at https://example.org"))
        assertNull(SchoolBodyRules.place("Log in at the parent portal"))
    }

    @Test
    fun aPastDateOrAPlainNewsletterProposesNothing() {
        val r = SchoolBodyRules.read("Newsletter", "It was lovely to see so many of you at the harvest festival on 2 October. Thank you for your support.", received, kids)
        assertTrue(r.finds.isEmpty(), r.finds.toString())
        assertTrue(r.summary.none { it.label == SchoolBodyRules.WHEN })
    }

    @Test
    fun theCardTravelsAsOneTextField() {
        val lines = listOf(SchoolSummaryLine("When", "Sat 28 Nov, 12:00–16:00"), SchoolSummaryLine("Where", "The\thall\nupstairs"))
        val decoded = SchoolBodyRules.decode(SchoolBodyRules.encode(lines))
        assertEquals("Sat 28 Nov, 12:00–16:00", decoded[0].value)
        assertEquals("The hall upstairs", decoded[1].value)
        assertTrue(SchoolBodyRules.decode(null).isEmpty())
        assertTrue(SchoolBodyRules.decode("garbage").isEmpty())
        // On the email's card: written only for a school email, read back by both apps.
        val f = MailNeedsRules.serverFields("Personal", "Oakfield PTA", "pta@oakfield.sch.uk", "Christmas Fair", "We are", 1L, 1, school = true, schoolSummary = lines)
        val item = assertNotNull(MailNeedsRules.read("m1", f))
        assertEquals(2, item.schoolSummary.size)
        val plain = MailNeedsRules.serverFields("Personal", "Shop", "a@shop.com", "Sale", "x", 1L, 1, schoolSummary = lines)
        assertTrue(MailItemFields.SCHOOL_SUMMARY !in plain)
    }

    @Test
    fun aBodyFindCarriesItsDetailsOnTheFind() {
        val r = SchoolBodyRules.read("Christmas Fair", fair, received, kids)
        val b = r.finds.first { it.find.title == "School fair" }
        val fields = SchoolMailRules.serverFields(b.find, "Oakfield PTA", "Personal", "Christmas Fair", received) +
            SchoolBodyRules.findFields(b) + MailRenewalRules.stateFields(MailFindState.OPEN, 0L)
        val f = assertNotNull(MailRenewalRules.read("mf1", fields))
        assertEquals(720, f.startMinute)
        assertEquals(960, f.endMinute)
        assertEquals("the school hall", f.place)
        assertTrue(f.notes!!.contains("Bring a bottle"))
        assertTrue((fields[MailFindFields.TRUST] as FieldValue.Text).value == Trust.UNTRUSTED_EXTERNAL.name)
    }

    @Test
    fun htmlBodiesAreMadePlain() {
        val facts = MailMessageFacts(
            from = MailPerson("PTA", "pta@oakfield.sch.uk"), to = emptyList(), cc = emptyList(), atMs = 0L, subject = "Fair",
            html = "<p>The fair is on <b>Saturday 28 November</b>.</p><script>evil()</script><p>Entry £1.</p>", text = null, attachments = emptyList(),
        )
        val t = SchoolBodyRules.plain(facts)
        assertTrue("Saturday 28 November" in t && "evil" !in t, t)
    }
}
