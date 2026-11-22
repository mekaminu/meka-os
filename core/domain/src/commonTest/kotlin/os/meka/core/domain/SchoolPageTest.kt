package os.meka.core.domain

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every page under Ask in one shape, slice 14: Ask → School as the page shape. */
class SchoolPageTest {
    @BeforeTest
    fun household() { Household.useTyped("Max, U7\nFelix, U10", "") }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val sat = d(10, 10)
    private val work = WorkHours(WorkSchedule.DEFAULT)
    private var n = 0

    private fun item(kind: SchoolKind, title: String, start: Long, end: Long = start, who: String = "", weekday: Int = 0, cover: String? = null) =
        SchoolItem("s${n++}", kind, title, who, start, end, weekday, cover, emptyList(), n.toLong())

    private fun view(vararg items: SchoolItem) = SchoolRules.view(items.toList(), sat, work)

    @Test
    fun anEmptySchoolIsThreeWordsAndFourEmptyCardsAtMost() {
        val p = SchoolPageRules.page(SchoolView.EMPTY, sat)
        assertEquals(3, p.tiles.size)
        assertEquals(listOf("No", "No", "None"), p.tiles.map { it.number })
        assertTrue(p.tiles.all(LifePageRules::readsWhole))
        assertTrue(p.tiles.none { it.lit })
        assertEquals(listOf("Days off", "Dates", "Every week"), p.cards.map { it.title })
        assertEquals(listOf(SchoolPageRules.OFF_EMPTY, SchoolPageRules.DATES_EMPTY, SchoolPageRules.WEEKLY_EMPTY), p.cards.map { it.empty })
        assertEquals(SchoolPageRules.ADD_CHOICES, p.addChoices)
        assertEquals("Add to School", p.addTitle)
    }

    @Test
    fun aDayOffWithinAWeekOnAnOfficeDayAsksWhosCoveringInBrass() {
        val v = view(
            item(SchoolKind.OFF, "INSET day", d(10, 13)),
            item(SchoolKind.OFF, "Half term", d(10, 26), d(10, 30)),
            item(SchoolKind.DAY, "Felix's trip", d(10, 15), who = "Felix"),
            item(SchoolKind.WEEKLY, "PE", d(10, 1), who = "Max", weekday = 2),
        )
        val p = SchoolPageRules.page(v, sat)
        // The next day off is 3 days away and lit; one office day to cover; one weekly thing.
        assertEquals(PageTile("3 days", "to INSET day", true), p.tiles[0])
        assertEquals(PageTile("1", "office day to cover", true), p.tiles[1])
        assertEquals(PageTile("1", "every week", false), p.tiles[2])
        val off = p.cards.first { it.key == SchoolPageRules.OFF }
        val inset = off.rows.first()
        assertEquals("INSET day", inset.name)
        // Its date stays beside the question, so "3 days" on the right reads as how far away it is.
        assertEquals("Tue 13 Oct · ${SchoolPageRules.COVER_ASK}", inset.line)
        assertEquals("3 days", inset.whenText)
        assertTrue(inset.lit)
        // Half term is more than a week off: its days on the left, how far on the right, not lit.
        val half = off.rows[1]
        assertTrue(half.line.startsWith("Mon 26"), half.line)
        assertEquals("2 wks", half.whenText)
        assertFalse(half.lit)
        // The cover question is behind the row's key; answering it is the existing cover.
        assertNotNull(SchoolPageRules.cover(v, inset.key))
        assertNull(SchoolPageRules.cover(v, half.key))
        val date = p.cards.first { it.key == SchoolPageRules.DATES }.rows.single()
        assertEquals("5 days", date.whenText)
        assertTrue(date.lit)
        val pe = p.cards.first { it.key == SchoolPageRules.WEEKLY }.rows.single()
        assertEquals("", pe.whenText)
        assertTrue(pe.line.startsWith("Every Tuesday"), pe.line)
    }

    @Test
    fun withNoQuestionTheMiddleNumberIsTheDatesAhead() {
        val v = view(
            item(SchoolKind.OFF, "Half term", d(10, 26), d(10, 30)),
            item(SchoolKind.DAY, "Non-uniform day", d(11, 6)),
            item(SchoolKind.DAY, "Trip payment", d(11, 14)),
        )
        val p = SchoolPageRules.page(v, sat)
        assertEquals(PageTile("2", "school dates ahead", false), p.tiles[1])
        assertEquals("2 wks", p.tiles[0].number)
        assertEquals("to half term", p.tiles[0].label)
        assertFalse(p.tiles[0].lit)
        assertEquals(PageTile("None", "weekly yet", false), p.tiles[2])
    }

    @Test
    fun aRowsKeyFindsItAsItIsNowAndNothingOnceRemoved() {
        val a = item(SchoolKind.DAY, "Sports day", d(10, 20))
        val v = view(a)
        val key = SchoolPageRules.allRows(v, SchoolPageRules.DATES, sat).single().key
        assertEquals("Sports day", SchoolPageRules.row(v, key)?.title)
        assertEquals("School date", SchoolPageRules.partLabel(v, SchoolPageRules.row(v, key)!!))
        assertNull(SchoolPageRules.row(view(), key))
        assertNull(SchoolPageRules.row(v, SchoolPageRules.councilKey("x")))
        assertTrue(SchoolPageRules.isCouncil(SchoolPageRules.councilKey("x")))
    }

    @Test
    fun aCardShowsFiveAndSeeAllHasEveryRow() {
        val items = (0 until 7).map { item(SchoolKind.DAY, "Thing $it", d(10, 12) + it) }.toTypedArray()
        val v = view(*items)
        val card = SchoolPageRules.page(v, sat).cards.first { it.key == SchoolPageRules.DATES }
        assertEquals(5, card.rows.size)
        assertEquals(7, card.count)
        assertEquals(LifePageRules.SEE_ALL, card.more)
        assertEquals(7, SchoolPageRules.allRows(v, SchoolPageRules.DATES, sat).size)
    }

    @Test
    fun aLongLineIsCutToItsDaysThenItsWords() {
        assertEquals(PhraseGlue.kept("Mon 26 – Fri 30 Oct"), SchoolPageRules.shortLine("Mon 26 – Fri 30 Oct · Maximilian and Felix-Alexander"))
        val cut = SchoolPageRules.shortLine("A very long line with no dot in it at all, going on and on")
        assertTrue(cut.endsWith("…") && cut.length <= SchoolPageRules.SHORT_LINE, cut)
        assertEquals(PhraseGlue.kept("Fri 15 Oct · Felix"), SchoolPageRules.shortLine("Fri 15 Oct · Felix"))
    }

    @Test
    fun addChoicesSayWhichReaderTakesTheLine() {
        assertTrue(SchoolPageRules.isInset(SchoolPageRules.ADD_INSET))
        assertFalse(SchoolPageRules.isInset(SchoolPageRules.ADD_OFF))
        assertEquals("Every week, for example Alex PE Tue", SchoolPageRules.spokenHint(SchoolPageRules.ADD_WEEKLY))
        assertTrue(SchoolPageRules.isNotRead(SchoolRules.NOT_READ))
        assertTrue(SchoolPageRules.isNotRead(SchoolTerms.INSET_NONE))
        assertFalse(SchoolPageRules.isNotRead("Added"))
        // Each hint is one short example.
        SchoolPageRules.ADD_CHOICES.forEach { assertTrue(it.hint.length <= 40, it.hint) }
    }

    @Test
    fun theDaysToCoverLeaveOutBookedLeaveAndCountTheInsetDayAfter() {
        // Walk-through 2026-11-22, A7: School said "5 office days to cover" for half term while Annual leave had Tue 27 –
        // Fri 30 Oct booked, and left out the INSET day on the Monday back. Booked leave isn't an office day.
        val wed = d(10, 21)
        val items = listOf(item(SchoolKind.OFF, "Half term", d(10, 26), d(10, 30)), item(SchoolKind.OFF, "INSET day", d(11, 2)))
        val leave = (d(10, 27)..d(10, 30)).toSet()
        val v = SchoolRules.view(items, wed, WorkHours(WorkSchedule.DEFAULT, leaveDays = leave))
        assertEquals(listOf(d(10, 26)), v.covers.single().days)
        assertEquals(2, v.toCover)
        assertEquals(PageTile("2", "office days to cover", true), SchoolPageRules.page(v, wed).tiles[1])
        // Beyond two weeks a day off isn't counted yet; an answered one never is.
        val later = SchoolRules.view(listOf(item(SchoolKind.OFF, "INSET day", d(11, 20))), wed, work)
        assertEquals(0, later.toCover)
        val answered = SchoolRules.view(listOf(item(SchoolKind.OFF, "INSET day", d(10, 26), cover = SchoolRules.COVER_COVERED)), wed, work)
        assertEquals(0, answered.toCover)
        // Not asked yet (12 days away), so it isn't lit.
        val notAsked = SchoolRules.view(listOf(item(SchoolKind.OFF, "INSET day", d(11, 2))), wed, work)
        assertEquals(PageTile("1", "office day to cover", false), SchoolPageRules.page(notAsked, wed).tiles[1])
    }

    @Test
    fun aHalfTermWaitingOnItsQuestionCountsItsOfficeDaysAndKeepsItsDates() {
        // Walk-through 2026-11-15, A10: a five-day half term said "1 day off to cover" and its row "5 days" alone.
        val mon = d(10, 19)
        val v = SchoolRules.view(listOf(item(SchoolKind.OFF, "Half term", d(10, 26), d(10, 30))), mon, work)
        val officeDays = v.covers.single().days.size
        assertTrue(officeDays > 1, "the default week has more than one office day in a school week")
        val p = SchoolPageRules.page(v, mon)
        assertEquals(PageTile("$officeDays", "office days to cover", true), p.tiles[1])
        val row = p.cards.first { it.key == SchoolPageRules.OFF }.rows.single()
        assertEquals("26–30 Oct · ${SchoolPageRules.COVER_ASK}", row.line)
        assertEquals("7 days", row.whenText)
        // Across a month's end the months are both said, and it still fits the cover screen's line.
        val across = SchoolRow("x", "Half term", "", null, "", startDay = d(10, 28), endDay = d(11, 3))
        assertEquals("28 Oct–3 Nov · ${SchoolPageRules.COVER_ASK}", SchoolPageRules.askLine(across))
        assertTrue(SchoolPageRules.askLine(across).length <= SchoolPageRules.SHORT_LINE)
    }
}
