package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** School emails first, slice 1b: the school's emails in their own box, first, and a heads-up for each new one. */
class SchoolNeedsTest {
    private val cal = LocalCalendar.UTC
    private val now = 1_764_000_000_000L
    private val hour = 3_600_000L

    private fun item(id: String, from: String, subject: String, agoMs: Long, school: Boolean) = assertNotNull(
        MailNeedsRules.read(id, MailNeedsRules.serverFields("Personal", from, null, subject, "Hello", now - agoMs, 1, school = school)),
    )

    @Test fun theFlagRoundTripsAndIsLeftOutForOtherMail() {
        val f = MailNeedsRules.serverFields("Personal", "Oakfield Primary", null, "Christmas fair", null, now, 1, school = true)
        assertEquals(os.meka.core.sync.FieldValue.Bool(true), f[MailItemFields.SCHOOL])
        assertTrue(MailItemFields.SCHOOL !in MailNeedsRules.serverFields("Personal", "Sam", null, "Hi", null, now, 1))
        assertTrue(item("a", "Oakfield Primary", "Christmas fair", 0, true).school)
        assertTrue(!item("b", "Sam", "Hi", 0, false).school)
    }

    @Test fun theSchoolsRowsLeaveMailToAnswerForTheirOwnBox() {
        val items = listOf(
            item("s1", "Oakfield Primary", "Christmas fair", hour, true),
            item("p1", "Sam Taylor", "Saturday", 2 * hour, false),
            item("s2", "Oakfield Primary", "Trip letter", 3 * hour, true),
            item("p2", "Jo", "Lunch", 4 * hour, false),
        )
        val card = assertNotNull(MailNeedsRules.card(items, now, cal))
        assertEquals(4, card.count)
        val (school, rest) = SchoolNeedsRules.split(card)
        assertEquals(listOf("s1", "s2"), school!!.every.map { it.id })
        assertEquals(2, school.count)
        assertEquals("2 emails from the school", school.headline)
        assertEquals(listOf("p1", "p2"), rest!!.every.map { it.id })
        assertEquals(2, rest.count)
        assertEquals("2 emails need you", rest.headline)
        assertEquals("Christmas fair, Trip letter", SchoolNeedsRules.peek(school))
        // The two boxes add up to the card.
        assertEquals(card.count, school.count + rest.count)
    }

    @Test fun aSchoolRowCarriesItsSummaryCardAndSaysIt() {
        val lines = listOf(
            SchoolSummaryLine(SchoolBodyRules.WHEN, "Sat 28 Nov, 12:00–16:00"),
            SchoolSummaryLine(SchoolBodyRules.WHERE, "the school hall"),
            SchoolSummaryLine(SchoolBodyRules.COST, "£1 per adult"),
        )
        val school = assertNotNull(MailNeedsRules.read("s1", MailNeedsRules.serverFields(
            "Personal", "Oakfield PTA", "pta@oakfield.sch.uk", "Christmas Fair", "We are", now - hour, 1, school = true, schoolSummary = lines)))
        val other = item("p1", "Sam Taylor", "Saturday", 2 * hour, false).copy(schoolSummary = lines)
        val card = assertNotNull(MailNeedsRules.card(listOf(school, other), now, cal))
        val row = card.every.first { it.id == "s1" }
        assertEquals(lines, row.summary)
        assertTrue("When: Sat 28 Nov, 12:00–16:00. Where: the school hall. Cost: £1 per adult" in row.spoken, row.spoken)
        // Only the school's emails wear a card; a read not yet done leaves the row as it was.
        assertTrue(card.every.first { it.id == "p1" }.summary.isEmpty())
        val unread = assertNotNull(MailNeedsRules.card(listOf(item("s2", "Oakfield Primary", "Trip", hour, true)), now, cal))
        assertTrue(unread.every.single().summary.isEmpty())
        assertNull(SchoolBodyRules.spoken(emptyList()))
        // The split keeps the card on the school's box.
        assertEquals(lines, SchoolNeedsRules.split(card).first!!.every.single().summary)
        // The thin row in From the school shows it and says it after who and when.
        val thin = NeedsYouGroupRules.mailRow(row)
        assertEquals(lines, thin.summary)
        assertTrue(thin.spoken.startsWith("Oakfield PTA, Christmas Fair"), thin.spoken)
        assertTrue(thin.spoken.endsWith(". When: Sat 28 Nov, 12:00–16:00. Where: the school hall. Cost: £1 per adult"), thin.spoken)
        assertTrue(NeedsYouGroupRules.mailRow(card.every.first { it.id == "p1" }).summary.isEmpty())
    }

    @Test fun noSchoolMailLeavesTheCardAsItWas() {
        val card = assertNotNull(MailNeedsRules.card(listOf(item("p1", "Sam", "Hi", hour, false)), now, cal))
        assertEquals(null to card, SchoolNeedsRules.split(card))
        assertEquals(null to null, SchoolNeedsRules.split(null))
        val only = assertNotNull(MailNeedsRules.card(listOf(item("s1", "Oakfield Primary", "Fair", hour, true)), now, cal))
        assertNull(SchoolNeedsRules.rest(only))
        assertEquals("1 email from the school", SchoolNeedsRules.school(only)!!.headline)
    }

    @Test fun aNewSchoolEmailIsAHeadsUpAtOnce() {
        val fresh = item("s1", "Oakfield Primary", "Christmas fair", 10 * 60_000L, true)
        val old = item("s2", "Oakfield Primary", "Term dates", 20 * hour, true)
        val person = item("p1", "Sam", "Hi", 60_000L, false)
        val done = item("s3", "Oakfield Primary", "Trip", 60_000L, true).copy(doneAskedAtMs = now)
        val n = SchoolNeedsRules.notices(listOf(fresh, old, person, done), now).single()
        assertEquals("schoolmail:s1", n.key)
        assertEquals(NoticeSource.SCHOOL_MAIL, n.source)
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals("From the school", n.title)
        assertEquals("Christmas fair · Oakfield Primary · in Needs you", n.text)
        assertEquals(NoticeTarget.NEEDS_YOU, n.target)
        // Never held for Work mode's stretched hold.
        assertTrue(!AlertHoldRules.holds(n))
        // Posted straight away through the governor (outside quiet hours), once.
        val g = Governor.evaluate(listOf(n), NotificationSettings(), DeviceAlerts.ALL, GovernorState(), now, cal)
        assertEquals(listOf("schoolmail:s1"), g.post.map { it.key })
        val again = Governor.evaluate(listOf(n), NotificationSettings(), DeviceAlerts.ALL, g.state, now + 60_000L, cal)
        assertTrue(again.post.isEmpty())
    }

    @Test fun theSchoolLeadsTheSubtitle() {
        assertEquals("1 from the school · 2 to decide", SchoolNeedsRules.withSchool("2 to decide", 1))
        assertEquals("3 from the school", SchoolNeedsRules.withSchool(NeedsYouLayoutRules.ALL_CLEAR, 3))
        assertEquals("2 to decide", SchoolNeedsRules.withSchool("2 to decide", 0))
    }
}
