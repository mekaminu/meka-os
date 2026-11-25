package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * School emails first, slice 4a: an email for another year group is summarised but nothing is added, and the same thing
 * sent again is added once. Invented school and children (rule 13).
 */
class SchoolMailCareTest {
    /** Tue 24 Nov 2026. */
    private val today = CivilDate.toEpochDay(2026, 11, 24)
    private val sat = today + 4

    private fun find(
        id: String, what: String, day: Long, forWhom: String? = null, kind: String = SchoolKind.DAY.wire,
        who: String = "", autoAtMs: Long? = null, receivedDay: Long = today - 1,
    ) = MailFind(
        id = id, kind = SchoolMailRules.KIND_SCHOOL, account = "Personal", merchant = "Oakfield Primary", renewDay = day,
        pence = null, obligationKind = ObligationKind.SUBSCRIPTION, repeat = RenewalRepeat.NONE, receivedDay = receivedDay,
        subject = "News", state = MailFindState.OPEN, decidedAtMs = 0L, what = what, autoAtMs = autoAtMs,
        endDay = day, schoolKind = kind, who = who, forWhom = forWhom,
    )

    /** An under-7 and an under-10: Year 2 and Year 5. */
    private val kids = listOf(AboutChild("Alex", 7), AboutChild("Sam", 10))

    @Test
    fun clubAgeGroupsGiveTheSchoolYears() {
        assertEquals(setOf(2, 5), SchoolYearRules.childYears(kids))
        assertEquals(0, SchoolYearRules.yearOf(5))
        // A child with no age group: not known, so nothing is held back.
        assertNull(SchoolYearRules.childYears(kids + AboutChild("Jo")))
        assertNull(SchoolYearRules.childYears(emptyList()))
    }

    @Test
    fun theYearsAnEmailNames() {
        assertEquals(setOf(3), SchoolYearRules.years("Year 3"))
        assertEquals(setOf(5, 6), SchoolYearRules.years("Years 5 and 6"))
        assertEquals((3..6).toSet(), SchoolYearRules.years("Years 3-6"))
        assertEquals(setOf(0), SchoolYearRules.years("Reception"))
        assertEquals(setOf(1, 2), SchoolYearRules.years("KS1"))
        assertEquals((3..6).toSet(), SchoolYearRules.years("KS2"))
        assertNull(SchoolYearRules.years("Whole school"))
        assertNull(SchoolYearRules.years("All pupils"))
        assertNull(SchoolYearRules.years(null))
    }

    @Test
    fun anotherYearsTripIsNotAddedButSaysSo() {
        val y4 = find("a", "Museum trip", today + 7, forWhom = "Year 4")
        val y5 = find("b", "Swimming gala", today + 8, forWhom = "Years 5 and 6")
        val all = find("c", "School fair", sat, forWhom = "Whole school")
        val none = find("d", "Disco", today + 9)
        val years = SchoolYearRules.childYears(kids)
        assertTrue(SchoolYearRules.otherYear(y4, years))
        assertEquals(listOf("c", "b", "d"), SchoolAutoRules.due(listOf(y4, y5, all, none), emptyList(), today, 0, years).map { it.id })
        // Not known (a child with no age group): added as before.
        assertEquals(4, SchoolAutoRules.due(listOf(y4, y5, all, none), emptyList(), today, 0, null).size)
        // The card says it and still offers Add.
        val row = SchoolMailRules.row(y4, today, SchoolYearRules.otherYear(y4, years))
        assertTrue(row.line.endsWith(" · " + SchoolYearRules.NOT_YOURS), row.line)
        val card = MailRenewalRules.card(listOf(y4), emptyList(), LocalCalendar.UTC.toEpochMs(today, 600), LocalCalendar.UTC, schoolYears = years)
        assertTrue(card!!.rows.single().line.contains(SchoolYearRules.NOT_YOURS))
    }

    @Test
    fun theSameThingSentAgainIsAddedOnce() {
        val fair = find("f1", "Christmas fair", sat, receivedDay = today - 3)
        val reminder = find("f2", "Reminder: Christmas fair", sat, receivedDay = today - 1)
        val otherAccount = find("f3", "Christmas Fair", sat).copy(account = "Business")
        val payment = find("f4", "Christmas fair payment", sat)
        // One batch: the first email's, and the payment (a deadline is its own thing).
        assertEquals(listOf("f1", "f4"), SchoolAutoRules.due(listOf(reminder, fair, otherAccount, payment), emptyList(), today, 0).map { it.id })
        // Already added on its own (or added and undone): the reminder never adds it again.
        val added = fair.copy(autoAtMs = 1L, state = MailFindState.ADDED)
        assertEquals(listOf("f4"), SchoolAutoRules.due(listOf(added, reminder, otherAccount, payment), emptyList(), today, 0).map { it.id })
        // Another day, or another child, is another thing.
        assertFalse(SchoolAutoRules.sameThing(fair, find("f5", "Christmas fair", sat + 7)))
        assertFalse(SchoolAutoRules.sameThing(find("x", "Football club", sat, who = "Alex"), find("y", "Football club", sat, who = "Sam")))
    }

    @Test
    fun theBodyKeepsWhoEachDateIsFor() {
        val body = """
            Dear Parents,
            Year 4 will visit the museum on Thursday 3 December.
            The whole school will enjoy a pantomime on Friday 11 December.
        """.trimIndent()
        val r = SchoolBodyRules.read("Dates for December", body, today)
        val trip = r.finds.first { it.find.startDay == CivilDate.toEpochDay(2026, 12, 3) }
        assertEquals("Year 4", trip.forWhom)
        assertEquals("Year 4", (SchoolBodyRules.findFields(trip)[MailFindFields.FOR_WHOM] as FieldValue.Text).value)
        // One year named for the whole email: every date takes it.
        val one = SchoolBodyRules.read("Year 2 trip", "Our trip to the farm is on Thursday 3 December. Please bring a packed lunch.", today)
        assertTrue(one.finds.isNotEmpty())
        assertTrue(one.finds.all { it.forWhom == "Year 2" }, one.finds.toString())
    }
}
