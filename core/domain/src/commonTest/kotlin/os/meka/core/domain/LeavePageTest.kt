package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every page under Ask in one shape, slice 9: Ask → Annual leave as the page shape (both apps). */
class LeavePageTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val sat = d(10, 10)
    private val work = WorkHours(WorkSchedule.DEFAULT, HolidayCalendar(mapOf(d(12, 25) to "Christmas Day", d(12, 28) to "Boxing Day (substitute day)", d(1, 1, 2027) to "New Year’s Day")))
    private fun item(id: String, title: String, a: Long, b: Long) = LeaveItem(id, title, a, b, 1L)
    private fun off(id: String, title: String, a: Long, b: Long) =
        SchoolItem(id, SchoolKind.OFF, title, "", a, b, 0, null, emptyList(), 1L)

    private val octLeave = item("l1", LeaveRules.TITLE, d(10, 27), d(10, 30))
    private val spain = item("l2", "Spain", d(12, 18), d(1, 4, 2027))
    private val august = item("l3", LeaveRules.TITLE, d(8, 3), d(8, 7))
    private val halfTerm = off("s1", "Half term", d(10, 26), d(10, 30))
    private val inset = off("s2", "INSET day", d(11, 2), d(11, 2))
    private val christmas = off("s3", "Christmas holidays", d(12, 21), d(1, 1, 2027))

    private fun view(items: List<LeaveItem> = listOf(octLeave, spain, august), school: List<SchoolItem> = listOf(halfTerm, inset, christmas), today: Long = sat) =
        LeaveRules.view(items, school, work, LeaveSettings(), today)

    @Test
    fun theThreeNumbersAreTheDaysLeftTheNextLeaveAndTheHolidaysToBook() {
        val v = view()
        val p = LeavePageRules.page(v, sat)
        assertEquals("Annual leave", p.title)
        assertEquals(listOf("${v.daysLeft}", "2 wks", "2"), p.tiles.map { it.number })
        assertEquals(listOf("days left", "to your next leave", "holidays to book"), p.tiles.map { it.label })
        // The INSET day within eight weeks with nothing booked is the nudge, so the third number is lit.
        assertEquals(listOf(false, false, true), p.tiles.map { it.lit })
        assertEquals(listOf(LeavePageRules.BOOKED, LeavePageRules.HOLIDAYS, LeavePageRules.TAKEN, LeavePageRules.ALLOWANCE), p.cards.map { it.key })
        assertEquals(listOf("A stretch of leave"), p.addChoices.map { it.label })
        assertEquals(LeaveRules.ADD_HINT, LeavePageRules.hint(LeavePageRules.ADD_LEAVE))
    }

    @Test
    fun bookedIsSoonestFirstWithHowFarOffAndTheNextLeaveYearSaid() {
        val p = LeavePageRules.page(view(), sat)
        val booked = p.cards.first { it.key == LeavePageRules.BOOKED }.rows
        assertEquals(listOf("Annual leave", "Spain"), booked.map { it.name })
        assertEquals(listOf("2 wks", "2 mths"), booked.map { it.whenText })
        assertEquals(listOf(false, false), booked.map { it.lit })
        assertTrue(booked[0].line.filter { it.isLetterOrDigit() }.endsWith("4days"), booked[0].line)
        val taken = p.cards.first { it.key == LeavePageRules.TAKEN }.rows
        assertEquals(listOf("Annual leave"), taken.map { it.name })
        assertEquals("", taken[0].whenText)
        // A leave year from April: a stretch in next April is next year's; the row's line stays one line (the cover
        // screen cut "… · Next leave year") and its sheet says so.
        val april = LeaveRules.view(listOf(item("l4", "Ski", d(4, 2, 2027), d(4, 9, 2027))), emptyList(), work, LeaveSettings(25, 4), sat)
        val ski = LeavePageRules.page(april, sat).cards.first().rows.single()
        assertTrue(!ski.line.contains("Next leave year"), ski.line)
        assertEquals("Next leave year", LeavePageRules.leave(april, ski.key)?.note)
    }

    @Test
    fun onLeaveNowAndLeaveWithinAWeekAreBrass() {
        val now = LeavePageRules.page(view(today = d(10, 28)), d(10, 28))
        assertEquals("Now", now.tiles[1].number)
        assertEquals("on leave", now.tiles[1].label)
        assertTrue(now.tiles[1].lit)
        val row = now.cards.first().rows.first()
        assertEquals("Now", row.whenText)
        assertTrue(row.lit)
        val soon = LeavePageRules.page(view(today = d(10, 23)), d(10, 23))
        assertEquals("4 days", soon.cards.first().rows.first().whenText)
        assertTrue(soon.cards.first().rows.first().lit)
        assertTrue(soon.tiles[1].lit)
    }

    @Test
    fun aHolidaySaysBookedPartBookedOrBookSoonOnTheRight() {
        val rows = LeavePageRules.page(view(), sat).cards.first { it.key == LeavePageRules.HOLIDAYS }.rows
        assertEquals(listOf("Half term", "INSET day", "Christmas holidays"), rows.map { it.name })
        assertEquals(listOf("Part booked", "Book soon", "Booked"), rows.map { it.whenText })
        assertEquals(listOf(false, true, false), rows.map { it.lit })
        // A holiday answered with cover says so.
        val covered = LeaveRules.view(emptyList(), listOf(halfTerm.copy(cover = SchoolRules.COVER_COVERED)), work, LeaveSettings(), sat)
        assertEquals("Covered", LeavePageRules.page(covered, sat).cards.first { it.key == LeavePageRules.HOLIDAYS }.rows.single().whenText)
    }

    @Test
    fun overTheAllowanceTheFirstNumberSaysByHowMuchInBrass() {
        val v = LeaveRules.view(listOf(octLeave, spain, august), emptyList(), work, LeaveSettings(10, 1), sat)
        val t = LeavePageRules.page(v, sat).tiles[0]
        assertEquals("${-v.daysLeft} over", t.number)
        assertEquals("your 10 days", t.label)
        assertTrue(t.lit)
    }

    @Test
    fun theEmptyPageSaysItInWordsWithTheAllowanceStillThere() {
        val p = LeavePageRules.page(view(emptyList(), emptyList()), sat)
        assertEquals(listOf("25", "None", "None"), p.tiles.map { it.number })
        assertEquals(listOf(LeavePageRules.BOOKED, LeavePageRules.ALLOWANCE), p.cards.map { it.key })
        assertEquals(LeavePageRules.BOOKED_EMPTY, p.cards.first().empty)
        assertTrue(p.cards.first().rows.isEmpty())
        val a = p.cards.last().rows.single()
        assertEquals("25 days a year", a.name)
        assertEquals("The leave year starts 1 Jan", a.line)
        assertEquals(LeavePageRules.KEY_ALLOWANCE, a.key)
    }

    @Test
    fun aRowsStretchAndHolidayAreFoundAsTheyAreNow() {
        val v = view()
        assertEquals("Spain", LeavePageRules.leave(v, LeavePageRules.leaveKey("l2"))?.title)
        assertEquals(d(8, 3), LeavePageRules.leave(v, LeavePageRules.leaveKey("l3"))?.startDay)
        assertEquals("Book 1 day", LeavePageRules.holiday(v, LeavePageRules.holidayKey("s1"))?.bookLabel)
        assertNull(LeavePageRules.leave(v, LeavePageRules.leaveKey("gone")))
        assertNull(LeavePageRules.holiday(v, LeavePageRules.leaveKey("s1")))
        assertNull(LeavePageRules.leave(v, LeavePageRules.KEY_ALLOWANCE))
        assertEquals("1 Apr", LeavePageRules.yearStartLabel(4))
        assertEquals(12, LeavePageRules.monthBefore(1))
        assertEquals(1, LeavePageRules.monthAfter(12))
    }

    @Test
    fun sixStretchesShowFiveWithSeeAll() {
        val many = (0 until 6).map { i -> item("m$i", "Trip $i", d(11, 2) + i * 7, d(11, 2) + i * 7) }
        val card = LeavePageRules.page(view(many, emptyList()), sat).cards.first()
        assertEquals(6, card.count)
        assertEquals(LifePageRules.MAX_ROWS, card.rows.size)
        assertEquals(LifePageRules.SEE_ALL, card.more)
        assertEquals(6, LeavePageRules.allRows(view(many, emptyList()), LeavePageRules.BOOKED, sat).size)
    }

    @Test
    fun steppersShareTheWidestValueSoTheirButtonsLineUp() {
        assertEquals("60 days", LeavePageRules.STEPPER_WIDEST)
        val widest = LeavePageRules.STEPPER_WIDEST.length
        LeavePageRules.stepperValues().forEach { v ->
            kotlin.test.assertTrue(v.length <= widest, "$v is wider than the steppers' shared width")
        }
    }
}
