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

    @Test
    fun askAndTalkHearEachBinAndTheComingCollections() {
        assertEquals(emptyList(), BinRules.askLines(emptyList(), sat, HolidayCalendar()))
        val bins = listOf(bin("Black bin", 2, 2, d(10, 13)), bin("Food caddy", 2, 1, d(10, 13)))
        assertEquals(listOf(
            "Bins · 2 bins · typed by Meka",
            "Black bin · every other Tuesday · next Tue 13 Oct (next week) · then Tue 27 Oct",
            "Food caddy · every Tuesday · next Tue 13 Oct (next week) · then Tue 20 Oct",
            "Collection · Tue 13 Oct (next week) · Black bin and food caddy · out the evening before",
            "Collection · Tue 20 Oct · Food caddy · out the evening before",
            "Collection · Tue 27 Oct · Black bin and food caddy · out the evening before",
        ), BinRules.askLines(bins, sat, HolidayCalendar()))
        // Monday: tomorrow's.
        assertTrue("Collection · Tue 13 Oct (tomorrow) · Black bin and food caddy · out the evening before" in BinRules.askLines(bins, d(10, 12), HolidayCalendar()))
        // Christmas week: Friday's collection moves to Saturday.
        val friday = listOf(bin("Green bin", 5, 1, d(10, 16)))
        assertEquals(listOf(
            "Bins · 1 bin · typed by Meka",
            "Green bin · every Friday · next Sat 26 Dec (this week) · ${BinRules.SHIFT_NOTE} · then Sat 2 Jan",
            "Collection · Sat 26 Dec (this week) · Green bin · out the evening before · ${BinRules.SHIFT_NOTE}",
            "Collection · Sat 2 Jan (next week) · Green bin · out the evening before · ${BinRules.SHIFT_NOTE}",
        ), BinRules.askLines(friday, d(12, 21), christmas))
    }

    @Test
    fun christmasOneOffsMoveOrDropOneCollectionEverywhereThePatternShows() {
        world.clock.nowMs = cal.toEpochMs(d(12, 21), 10 * 60) // Mon 21 Dec
        bFold.add("Black bin Tue fortnightly from 12 Jan", christmas) // 29 Dec is a black bin week
        bFold.add("Food caddy every Tuesday", christmas)
        // Boxing Day's Monday puts Tuesday's on Wednesday 30th; the council says Saturday 2 Jan instead.
        assertEquals(BinAdded("Moved Black bin to Sat 2 Jan instead of Wed 30 Dec", false),
            bFold.add("Black bin Sat 2 Jan instead of 29 Dec", christmas))
        assertEquals(BinAdded("No collection for food caddy on Wed 30 Dec", false), bFold.add("No food caddy 30 Dec", christmas))
        // Refused: a day the bin doesn't go, a bin that isn't there, too far, nothing readable.
        assertTrue(bFold.add("Black bin Sat 9 Jan instead of 5 Jan", christmas).line.startsWith("Black bin isn't collected on Tue 5 Jan"))
        assertTrue(bFold.add("Green bin Sat 2 Jan instead of 29 Dec", christmas).line.startsWith("No bin by that name"))
        assertTrue(bFold.add("Black bin 30 Jan instead of 29 Dec", christmas).refused)
        assertTrue(bFold.add("Black bin moved instead of Tuesday", christmas).refused)
        sync()

        val bins = bMac.items()
        val today = d(12, 21)
        assertEquals(listOf(d(1, 2, 2027), d(1, 12, 2027)), BinRules.collections(bins[0], today, d(1, 14, 2027), christmas))
        assertEquals(listOf(d(12, 22), d(1, 5, 2027), d(1, 12, 2027)), BinRules.collections(bins[1], today, d(1, 14, 2027), christmas))
        assertEquals(emptyList(), BinRules.onDay(bins, d(12, 30), christmas))
        assertEquals("Black bin · moved from Wed 30 Dec", BinRules.weekLine(bins, d(1, 2, 2027), christmas))
        // Tuesday evening: nothing out by habit; Friday evening: the black bin.
        assertEquals("Black bin moved to Sat 2 Jan · No food caddy collection · not out tonight",
            BinRules.tonightLine(bins, d(12, 29), 18 * 60, christmas)?.text)
        assertEquals("Black bin out tonight · moved from Wed 30 Dec", BinRules.tonightLine(bins, d(1, 1, 2027), 18 * 60, christmas)?.text)
        val v = bMac.view(christmas)
        assertEquals(listOf("Next: Sat 2 Jan · moved from Wed 30 Dec", "Next: Tomorrow"), v.rows.map { it.note })
        assertEquals(listOf("Sat 2 Jan instead of Wed 30 Dec", "No collection on Wed 30 Dec"), v.oneOffs.map { it.line })
        assertTrue("One-off · Black bin · Sat 2 Jan instead of Wed 30 Dec" in BinRules.askLines(bins, today, christmas))

        // Typing it again changes the same one-off; Remove takes it back and the pattern returns.
        bMac.add("Black bin Thu 31 Dec instead of 30 Dec", christmas)
        sync()
        assertEquals(listOf("Thu 31 Dec instead of Wed 30 Dec", "No collection on Wed 30 Dec"), bFold.view(christmas).oneOffs.map { it.line })
        val caddyMove = bFold.view(christmas).oneOffs[1].id
        assertTrue(bFold.removeMove(caddyMove))
        assertFalse(bFold.removeMove(caddyMove))
        sync()
        assertEquals(listOf("Food caddy"), BinRules.onDay(bMac.items(), d(12, 30), christmas).map { it.name })
        // Past one-offs leave the list.
        assertTrue(BinRules.oneOffRows(bMac.items(), d(1, 1, 2027)).isEmpty())
    }

    @Test
    fun aStartDateJustGoneIsThisYearsAndATypedWeekdayWins() {
        val wed = d(10, 21)
        // Typed on Wed 21 Oct: "from 13 Oct" is the Tuesday a week ago, not 13 Oct 2027 (a Wednesday).
        assertEquals(BinEntry("Black bin", 2, 2, d(10, 13)), BinRules.read("Black bin Tue fortnightly from 13 Oct", wed))
        assertEquals(BinEntry("Recycling", 2, 2, d(10, 20)), BinRules.read("Recycling fortnightly from 20 Oct", wed))
        val black = bin("Black bin", 2, 2, d(10, 13))
        assertEquals(d(10, 27), BinRules.next(black, wed, HolidayCalendar(emptyMap())))
        // A date more than half a year back is next year's; a typed year stands.
        assertEquals(d(3, 2, 2027), BinRules.read("Garden waste fortnightly from 2 Mar", wed)!!.anchorDay)
        assertEquals(d(9, 1), BinRules.read("Garden waste every Tue from 1/9/2026", wed)!!.anchorDay)
        // A weekday that disagrees with the date: the first such weekday from that date.
        assertEquals(BinEntry("Food caddy", 2, 1, d(10, 20)), BinRules.read("Food caddy Tue from 14 Oct", wed))
    }
}
