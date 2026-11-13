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
        assertEquals(listOf("None", "None", "None"), p.tiles.map { it.number })
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
        // The next day off is 3 days away and lit; one day off to cover; one weekly thing.
        assertEquals(PageTile("3 days", "to INSET day", true), p.tiles[0])
        assertEquals(PageTile("1", "day off to cover", true), p.tiles[1])
        assertEquals(PageTile("1", "every week", false), p.tiles[2])
        val off = p.cards.first { it.key == SchoolPageRules.OFF }
        val inset = off.rows.first()
        assertEquals("INSET day", inset.name)
        assertEquals(SchoolPageRules.COVER_ASK, inset.line)
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
}
