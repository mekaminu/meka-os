package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every page under Ask in one shape, slice 11: Ask → Date night as the page shape (both apps). */
class DateNightPageTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    // Mon 16 Nov; every other Friday from 19:00, the first Fri 20 Nov.
    private val today = d(11, 16)
    private val friday = d(11, 20)
    private fun setting(skipped: Set<Long> = emptySet(), booked: Set<Long> = emptySet()) =
        DateNightSetting(5, 19 * 60, friday, skipped, booked)
    private fun view(s: DateNightSetting? = setting(skipped = setOf(d(12, 4)))) = DateNightRules.view(s, today, 9 * 60)

    @Test
    fun theThreeNumbersAreHowSoonWhetherItsBookedAndTheEvening() {
        val p = DateNightPageRules.page(view(), today)
        assertEquals("Date night", p.title)
        assertEquals(listOf("4 days", DateNightPageRules.TO_BOOK, "19:00"), p.tiles.map { it.number })
        assertEquals(listOf("to date night", "somewhere and cover", "every other Friday"), p.tiles.map { it.label })
        assertEquals(listOf(true, true, false), p.tiles.map { it.lit })
        assertEquals(listOf(DateNightPageRules.COMING_UP, DateNightPageRules.EVENING), p.cards.map { it.key })
    }

    @Test
    fun comingUpSaysToBookForTheNudgedNightSkippedForASkippedOneAndHowFarOffTheRest() {
        val rows = DateNightPageRules.page(view(), today).cards.first().rows
        assertEquals(4, rows.size)
        assertEquals(listOf("Fri 20 Nov", "Fri 4 Dec", "Fri 18 Dec", "Fri 1 Jan"), rows.map { DateGlue.plain(it.name) })
        assertEquals(listOf(DateNightPageRules.TO_BOOK, DateNightPageRules.SKIPPED, "4 wks", "6 wks"), rows.map { it.whenText })
        assertEquals(listOf("From 19:00 · kept clear", DateNightPageRules.FREE, "From 19:00 · kept clear", "From 19:00 · kept clear"), rows.map { it.line })
        assertEquals(listOf(true, false, false, false), rows.map { it.lit })
        assertEquals(DateNightPageRules.nightKey(friday), rows[0].key)
    }

    @Test
    fun aBookedNightSaysBookedAndTheTileFollows() {
        val v = view(setting(booked = setOf(friday)))
        val p = DateNightPageRules.page(v, today)
        assertEquals(DateNightPageRules.BOOKED, p.cards.first().rows[0].whenText)
        assertFalse(p.cards.first().rows[0].lit)
        assertEquals(DateNightPageRules.BOOKED, p.tiles[1].number)
        val night = DateNightPageRules.night(v, p.cards.first().rows[0].key)!!
        assertTrue(night.booked)
        assertEquals(DateNightPageRules.NOT_BOOKED, DateNightPageRules.bookLabel(night))
        assertEquals(DateNightRules.SKIP_LABEL, DateNightPageRules.skipLabel(night))
    }

    @Test
    fun offShowsHowToStartAndNoEveningCard() {
        val p = DateNightPageRules.page(view(null), today)
        assertEquals(listOf("Off", "None", "Not set"), p.tiles.map { it.number })
        assertEquals(listOf(DateNightPageRules.COMING_UP), p.cards.map { it.key })
        assertTrue(p.cards.first().rows.isEmpty())
        assertEquals(DateNightPageRules.COMING_EMPTY, p.cards.first().empty)
        assertEquals("Pick the weekday and time, kept clear every two weeks", p.addChoices.single().hint)
    }

    @Test
    fun theEveningRowAndAddSayTheWeekdayAndTime() {
        val p = DateNightPageRules.page(view(), today)
        val row = p.cards.first { it.key == DateNightPageRules.EVENING }.rows.single()
        assertEquals(DateNightPageRules.EVENING_KEY, row.key)
        assertEquals("Every other Friday", row.name)
        assertEquals(DateNightPageRules.CHANGE, row.whenText)
        assertEquals("Every other Friday from 19:00 · change it", p.addChoices.single().hint)
        assertNull(DateNightPageRules.night(view(), row.key))
    }

    @Test
    fun aSkippedNightOffersKeepItAndNoBooking() {
        val v = view()
        val skipped = DateNightPageRules.night(v, DateNightPageRules.nightKey(d(12, 4)))!!
        assertFalse(DateNightPageRules.canBook(skipped))
        assertEquals(DateNightRules.KEEP_LABEL, DateNightPageRules.skipLabel(skipped))
    }

    @Test
    fun tonightIsLitAndTheTileSaysTonight() {
        val v = DateNightRules.view(setting(), friday, 12 * 60)
        val p = DateNightPageRules.page(v, friday)
        assertEquals("Tonight", p.tiles[0].number)
        assertEquals("Tonight", p.cards.first().rows[0].name)
        assertTrue(p.cards.first().rows[0].lit)
    }

    @Test
    fun everyLineOnTheCoverScreenStaysShort() {
        // A card row's line is one line on the cover screen (ci runs 43395185015, 43402124758).
        DateNightPageRules.page(view(), today).cards.flatMap { it.rows }.forEach { assertTrue(it.line.length <= 24, it.line) }
    }
}
