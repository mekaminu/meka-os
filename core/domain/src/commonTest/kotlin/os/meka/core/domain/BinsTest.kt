package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bin days, slice 1: each bin typed once, collections worked out (a day later after a bank holiday), the evening before. */
class BinsTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val sat = d(10, 10)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val bFold = Bins(fold.replica, { "bin${n++}" }, { world.clock.nowMs }, cal)
    private val bMac = Bins(mac.replica, { "bin${n++}" }, { world.clock.nowMs }, cal)
    private val christmas = HolidayCalendar(mapOf(
        d(12, 25) to "Christmas Day", d(12, 28) to "Boxing Day (substitute day)", d(1, 1, 2027) to "New Year's Day",
    ))

    init { world.clock.nowMs = cal.toEpochMs(sat, 10 * 60) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private fun bin(name: String, weekday: Int, every: Int, anchor: Long, id: String = name) = BinItem(id, name, weekday, every, anchor, 0)

    @Test
    fun aBinIsReadFromOneLineWithItsDayAndPattern() {
        assertEquals(BinEntry("Black bin", 2, 2, d(10, 13)), BinRules.read("Black bin Tue fortnightly from 13 Oct", sat))
        assertEquals(BinEntry("Food caddy", 2, 1, d(10, 13)), BinRules.read("Food caddy every Tuesday", sat))
        assertEquals(BinEntry("Food caddy", 3, 1, d(10, 14)), BinRules.read("food waste weds", sat))
        // A date alone fixes the weekday and the week.
        assertEquals(BinEntry("Blue bin", 2, 2, d(10, 20)), BinRules.read("blue bin 20 Oct every other week", sat))
        assertEquals(BinEntry("Blue bin", 2, 2, d(10, 20)), BinRules.read("Blue every 2 weeks, next 20/10", sat))
        // Fortnightly with only a weekday: the next one, flagged as a guess.
        assertEquals(BinEntry("Garden waste", 5, 2, d(10, 16), dated = false), BinRules.read("Garden bin alternate Fridays", sat))
        // Nothing to read: no day, no bin, a range.
        assertNull(BinRules.read("Black bin", sat))
        assertNull(BinRules.read("every other Tuesday", sat))
        assertNull(BinRules.read("Black bin 12–14 Oct", sat))
        assertNull(BinRules.read("", sat))
    }

    @Test
    fun collectionsFollowThePatternAndMoveADayAfterABankHoliday() {
        val black = bin("Black bin", 2, 2, d(10, 13))
        val caddy = bin("Food caddy", 2, 1, d(10, 13))
        val none = HolidayCalendar.NONE
        assertEquals(listOf(black, caddy), BinRules.onDay(listOf(black, caddy), d(10, 13), none))
        assertEquals(listOf(caddy), BinRules.onDay(listOf(black, caddy), d(10, 20), none))
        assertEquals(listOf(black, caddy), BinRules.onDay(listOf(black, caddy), d(10, 27), none))
        // Works backwards from the anchor too.
        assertEquals(d(9, 29), BinRules.collectionIn(black, d(9, 28), none))
        assertNull(BinRules.collectionIn(black, d(10, 5), none))
        // Christmas Friday comes after Tuesday: Tuesday stays.
        assertEquals(d(12, 22), BinRules.collectionIn(caddy, d(12, 21), christmas))
        // Boxing Day's Monday: Tuesday's goes Wednesday.
        assertEquals(d(12, 30), BinRules.collectionIn(caddy, d(12, 28), christmas))
        assertTrue(BinRules.shifted(caddy, d(12, 30)))
        // Two bank holidays before a Friday collection: never past Saturday.
        assertEquals(d(1, 2, 2027), BinRules.collectionIn(bin("Blue bin", 5, 1, d(10, 16)), d(12, 28), christmas))
        // A Christmas Friday collection moves to Saturday.
        assertEquals(d(12, 26), BinRules.collectionIn(bin("Blue bin", 5, 1, d(10, 16)), d(12, 21), christmas))
        assertEquals(d(12, 30), BinRules.next(caddy, d(12, 23), christmas))
    }

    @Test
    fun theEveningBeforeTodayAndAHeadsUpSayWhichBinsGoOut() {
        val bins = listOf(bin("Black bin", 2, 2, d(10, 13)), bin("Food caddy", 2, 1, d(10, 13)))
        val mon = d(10, 12)
        assertNull(BinRules.tonightLine(bins, mon, 15 * 60 + 59, HolidayCalendar.NONE))
        assertEquals(BinLine("Black bin and food caddy out tonight", "Black bin and food caddy go out tonight."),
            BinRules.tonightLine(bins, mon, 16 * 60, HolidayCalendar.NONE))
        assertNull(BinRules.tonightLine(bins, d(10, 13), 20 * 60, HolidayCalendar.NONE))
        assertEquals("Food caddy out tonight · a day later for the bank holiday",
            BinRules.tonightLine(bins, d(12, 29), 18 * 60, christmas)?.text)

        val notices = BinRules.notices(bins, cal.toEpochMs(sat, 10 * 60), cal, HolidayCalendar.NONE)
        assertEquals(listOf("bins:${d(10, 13)}"), notices.map { it.key })
        val first = notices.single()
        assertEquals("Black bin and food caddy out tonight", first.title)
        assertEquals("Collected tomorrow, Tue 13 Oct", first.text)
        assertEquals(NoticeSource.BINS, first.source)
        assertEquals(cal.toEpochMs(mon, 19 * 60), first.atMs)
        assertEquals(cal.toEpochMs(d(10, 13), 7 * 60), first.expiresAtMs)
        assertTrue(BinRules.notices(emptyList(), cal.toEpochMs(sat, 600), cal, HolidayCalendar.NONE).isEmpty())
        // The week ahead's line.
        assertEquals("Black bin and food caddy", BinRules.weekLine(bins, d(10, 13), HolidayCalendar.NONE))
        assertNull(BinRules.weekLine(bins, d(10, 14), HolidayCalendar.NONE))
    }

    @Test
    fun binsSyncAndTheSameNameUpdatesRatherThanAddsAgain() {
        assertEquals(BinsView.EMPTY, bFold.view(HolidayCalendar.NONE))
        assertEquals(BinAdded("Added Black bin · every other Tuesday · next Tue 13 Oct", false), bFold.add("Black bin Tue fortnightly from 13 Oct"))
        assertEquals(BinAdded("Added Food caddy · every Tuesday · next Tue 13 Oct", false), bFold.add("Food caddy every Tue"))
        assertEquals(BinAdded("Added Blue bin · every other Tuesday · next Tue 13 Oct · wrong week? Type it again with a collection date", false),
            bFold.add("Blue bin every other Tuesday"))
        assertTrue(bFold.add("bins").refused)
        sync()
        // The Mac sets the blue bin's week; it is one bin still.
        assertEquals("Updated Blue bin · every other Tuesday · next Tue 20 Oct", bMac.add("blue bin fortnightly 20 Oct").line)
        sync()
        val v = bFold.view(HolidayCalendar.NONE)
        assertEquals(listOf("Black bin", "Blue bin", "Food caddy"), v.rows.map { it.name }) // added together: by name
        assertEquals(listOf("Every other Tuesday", "Every other Tuesday", "Every Tuesday"), v.rows.map { it.line })
        assertEquals(listOf("Next: Tue 13 Oct", "Next: Tue 20 Oct", "Next: Tue 13 Oct"), v.rows.map { it.note })
        assertEquals("Next: Black bin and food caddy · Tue 13 Oct", v.summary)

        val blue = v.rows[1].id
        assertTrue(bMac.remove(blue))
        assertFalse(bMac.remove(blue))
        sync()
        assertEquals(listOf("Black bin", "Food caddy"), bFold.items().map { it.name })
    }
}
