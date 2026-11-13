package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every page under Ask in one shape, slice 5: Bins' three numbers, its three cards, + Add's choices and the row keys. */
class BinsPageTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon = d(10, 12) // Mon 12 Oct 2026
    private val none = HolidayCalendar.NONE

    private fun bin(name: String, weekday: Int, every: Int, anchor: Long, moves: List<BinMove> = emptyList()) =
        BinItem(name, name, weekday, every, anchor, 0, moves)

    private fun view(today: Long, vararg b: BinItem, holidays: HolidayCalendar = none) = BinRules.view(b.toList(), today, holidays)

    @Test
    fun threeNumbersTheNextCollectionTheBinsAndTheOneOffs() {
        val p = BinsPageRules.page(
            view(mon, bin("Black bin", 2, 2, d(10, 13)), bin("Food caddy", 2, 1, d(10, 13)), bin("Green bin", 5, 1, d(10, 16))),
            mon,
        )
        assertEquals("Bins", p.title)
        assertEquals(3, p.tileCount)
        // Both Tuesday bins go out tonight for tomorrow: lit.
        assertEquals(PageTile("Tomorrow", "Black bin and food caddy", true), p.tileAt(0))
        assertEquals(PageTile("3", "bins on your list", false), p.tileAt(1))
        assertEquals(PageTile("None", "one-offs to come", false), p.tileAt(2))
        assertEquals(listOf("bin", "one-off", "council"), p.addChoices.map { it.key })
        assertFalse(BinsPageRules.takesText(BinsPageRules.ADD_COUNCIL))
        assertTrue(BinsPageRules.takesText(BinsPageRules.ADD_ONE_OFF))
    }

    @Test
    fun yourBinsAreSoonestFirstWithTheDayOnTheRight() {
        val p = BinsPageRules.page(view(mon, bin("Green bin", 5, 1, d(10, 16)), bin("Black bin", 2, 2, d(10, 20))), mon)
        val yours = p.cardAt(0)
        assertEquals("Your bins", yours.title)
        assertEquals(listOf("Green bin", "Black bin"), yours.rows.map { it.name })
        assertEquals("Fri", yours.rowAt(0).whenText)
        assertFalse(yours.rowAt(0).lit)
        assertEquals("Every Friday", yours.rowAt(0).line)
        assertEquals("20 Oct", yours.rowAt(1).whenText)
        assertEquals("Every other Tuesday", yours.rowAt(1).line)
        assertEquals(PageTile("Fri", "Green bin", false), p.tileAt(0))
    }

    @Test
    fun aBankHolidayShiftSaysSoOnTheRow() {
        val xmas = HolidayCalendar(mapOf(d(12, 25) to "Christmas Day"))
        val today = d(12, 21)
        val v = view(today, bin("Black bin", 5, 1, d(12, 4)), holidays = xmas)
        val row = v.rows.single()
        assertNotNull(row.nextDay)
        val p = BinsPageRules.page(v, today)
        assertEquals(BinsPageRules.dayWord(row.nextDay!!, today), p.cardAt(0).rowAt(0).whenText)
        if (row.shiftNote != null) assertTrue(p.cardAt(0).rowAt(0).line.endsWith(" · ${row.shiftNote}"))
    }

    @Test
    fun oneOffsAndTheReminderAndCouncilRows() {
        val black = bin("Black bin", 2, 1, d(10, 13), listOf(BinMove("m1", "Black bin", d(10, 20), null)))
        val p = BinsPageRules.page(view(mon, black), mon)
        assertEquals(PageTile("1", "one-off to come", false), p.tileAt(2))
        val oneOffs = p.cardAt(1)
        assertEquals("One-offs", oneOffs.title)
        assertEquals("move:m1", oneOffs.rowAt(0).key)
        assertEquals("No collection on Tue 20 Oct", oneOffs.rowAt(0).line)
        val settings = p.cardAt(2)
        assertEquals(listOf(BinsPageRules.KEY_REMIND, BinsPageRules.KEY_COUNCIL), settings.rows.map { it.key })
        assertEquals("19:00", settings.rowAt(0).whenText)
        assertEquals(BinsPageRules.OWN_DAYS, settings.rowAt(1).line)
    }

    @Test
    fun emptySaysItInWordsNeverADash() {
        val p = BinsPageRules.page(BinsView.EMPTY, mon)
        assertEquals(PageTile("No bins", "added yet", false), p.tileAt(0))
        assertEquals(PageTile("0", "bins on your list", false), p.tileAt(1))
        assertEquals(PageTile("None", "one-offs to come", false), p.tileAt(2))
        assertTrue(p.cardAt(0).rows.isEmpty() && p.cardAt(0).empty != null)
        assertTrue(p.cardAt(1).rows.isEmpty() && p.cardAt(1).empty != null)
        assertTrue(p.tiles.none { it.number.trim() == "—" || it.number.isBlank() })
    }

    @Test
    fun moreThanFiveBinsShowFiveWithSeeAll() {
        val many = (1..7).map { bin("Bin $it", 1 + it % 7, 1, d(10, 12) + it % 7) }.toTypedArray()
        val yours = BinsPageRules.page(view(mon, *many), mon).cardAt(0)
        assertEquals(7, yours.count)
        assertEquals(5, yours.rowCount)
        assertEquals("See all", yours.more)
    }

    @Test
    fun aRowOpensItsBinOrOneOff() {
        val black = bin("Black bin", 2, 1, d(10, 13), listOf(BinMove("m1", "Black bin", d(10, 20), null)))
        val v = view(mon, black)
        val p = BinsPageRules.page(v, mon)
        assertEquals("bin:Black bin", p.cardAt(0).rowAt(0).key)
        assertNotNull(BinsPageRules.bin(v, p.cardAt(0).rowAt(0).key))
        assertNotNull(BinsPageRules.oneOff(v, "move:m1"))
        assertNull(BinsPageRules.bin(v, "bin:gone"))
        assertNull(BinsPageRules.binId("move:m1"))
        assertEquals("No black bin 29 Dec", BinsPageRules.hint(BinsPageRules.ADD_ONE_OFF))
    }
}
