package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** School emails first, slice 6: Obi, the brief and the week ahead know what's on at school. */
class SchoolAskTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val wed = d(11, 25) // Wed 25 Nov 2026
    private val none = HolidayCalendar.NONE

    private fun item(id: String, kind: SchoolKind, title: String, start: Long, end: Long = start, who: String = "Alex", weekday: Int = 0, mail: Boolean = true, added: Long = 0) =
        SchoolItem(id, kind, title, who, start, end, weekday, null, emptyList(), added, if (mail) SchoolMailRules.SOURCE else null)

    private val fair = item("fair", SchoolKind.DAY, "School fair", d(11, 28))
    private val pe = item("pe", SchoolKind.WEEKLY, "PE", d(9, 1), weekday = 4, mail = false)
    private val nativity = item("nat", SchoolKind.DAY, "Nativity play", d(12, 9))
    private val items = listOf(fair, pe, nativity)

    @Test
    fun schoolQuestionsAreKnown() {
        assertTrue(SchoolAskRules.isAbout("what's on at school this week?"))
        assertTrue(SchoolAskRules.isAbout("When's half term?"))
        assertTrue(SchoolAskRules.isWeek("What's on at school this week?"))
        assertTrue(SchoolAskRules.isWeek("anything coming up at school?"))
        assertFalse(SchoolAskRules.isWeek("what did the school say?"))
        assertFalse(SchoolAskRules.isAbout("what's on this week?"))
        // The rules Ask asks first leave it to School's lines.
        val q = "what's on at school this week?"
        assertFalse(MailAskRules.isAbout(q)); assertFalse(PlanWeekNudgeRules.isRequest(q))
        assertFalse(MoneyAskRules.isAbout(q)); assertFalse(TradingAskRules.isAbout(q))
    }

    @Test
    fun theWeekListsEachSchoolThingByDayAndMarksTheSchoolsEmail() {
        val lines = SchoolAskRules.weekLines(items, wed, none)
        assertEquals(
            listOf(
                "School this week · today to Tue 1 Dec · 2 things",
                "tomorrow · PE (every Thursday) · Alex",
                "Sat 28 Nov · School fair · Alex · from the school's email",
            ),
            lines,
        )
        // The nativity, two weeks off, is the school year's (SchoolRules.askLines), not this week's.
        assertTrue(lines.none { "Nativity" in it })
        assertEquals(listOf("School this week · today to Tue 1 Dec · nothing on"), SchoolAskRules.weekLines(listOf(nativity), wed, none))
        assertTrue(SchoolAskRules.weekLines(emptyList(), wed, none).isEmpty())
    }

    @Test
    fun aDayOffAcrossTheWeekIsListedOnce() {
        val off = item("ht", SchoolKind.OFF, "Half term", d(11, 30), d(12, 4), who = "", mail = false)
        val lines = SchoolAskRules.weekLines(listOf(off), wed, none)
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("Day off · Half term"), lines[1])
        assertEquals(1, lines.count { "Half term" in it })
    }

    @Test
    fun withoutTheAiTheDeviceSaysWhatsOnAtSchool() {
        assertEquals(
            "At school this week: Alex has PE tomorrow and Alex has School fair on Sat 28 Nov.",
            SchoolAskRules.localAnswer("what's on at school this week?", items, wed, none),
        )
        assertEquals("Nothing on at school this week.", SchoolAskRules.localAnswer("what's on at school this week?", listOf(nativity), wed, none))
        assertNull(SchoolAskRules.localAnswer("what did the school say?", items, wed, none))
        assertNull(SchoolAskRules.localAnswer("what's on at school this week?", emptyList(), wed, none))
    }

    @Test
    fun theBriefNamesTheSchoolsNextDatesOfTheComingDays() {
        val line = SchoolAskRules.briefLine(items, wed, none)!!
        assertEquals("Coming up · Alex: School fair Sat 28 Nov", line.text)
        assertEquals("Coming up at school: Alex has School fair on Sat 28 Nov.", line.spoken)
        // On Thursday, today's PE leads, then the fair.
        val thu = SchoolAskRules.briefLine(items, wed + 1, none)!!
        assertEquals("Alex: PE today · Alex: School fair Sat 28 Nov", thu.text)
        assertTrue(thu.spoken.startsWith("School today: Alex has PE. Coming up at school:"), thu.spoken)
        // The day before, it is "tomorrow"; on the day, today's line alone.
        assertEquals("Coming up · Alex: School fair tomorrow", SchoolAskRules.briefLine(listOf(fair), d(11, 27), none)!!.text)
        assertEquals(SchoolRules.todayLine(listOf(fair), d(11, 28), none), SchoolAskRules.briefLine(listOf(fair), d(11, 28), none))
        // Nothing within six days: no line; the same thing twice (both accounts) is named once.
        assertNull(SchoolAskRules.briefLine(listOf(nativity), wed, none))
        val twice = listOf(fair, fair.copy(id = "fair2", addedAtMs = 5))
        assertEquals("Coming up · Alex: School fair Sat 28 Nov", SchoolAskRules.briefLine(twice, wed, none)!!.text)
    }

    @Test
    fun linesFromTheSchoolsEmailMakeTheAskContextUntrusted() {
        assertTrue(SchoolAskRules.fromEmail(items))
        assertFalse(SchoolAskRules.fromEmail(listOf(pe)))
        val cal = LocalCalendar.UTC
        val now = cal.toEpochMs(wed, 8 * 60)
        val today = Today(emptyList(), null, emptyList(), emptyList())
        val lines = SchoolAskRules.weekLines(items, wed, none)
        assertTrue(AskRules.context(today, now, cal, school = lines, schoolFromOthers = true).untrusted)
        assertFalse(AskRules.context(today, now, cal, school = lines, schoolFromOthers = false).untrusted)
        // Asked about school, the school's lines lead the tail, so a full context never cuts them.
        val asked = AskRules.context(today, now, cal, school = lines, schoolAsked = true)
        assertTrue(asked.items.any { it.kind == AskItemKind.SCHOOL && "School fair" in it.line })
    }
}
