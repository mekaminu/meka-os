package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** Bin days: the council's own dates from its calendar file (.ics), read on the device. Invented council and address. */
class BinCouncilTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon = d(11, 2)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val bFold = Bins(fold.replica, { "b${n++}" }, { world.clock.nowMs }, cal)
    private val bMac = Bins(mac.replica, { "b${n++}" }, { world.clock.nowMs }, cal)
    private val christmas = HolidayCalendar(mapOf(d(12, 25) to "Christmas Day", d(12, 28) to "Boxing Day (substitute day)", d(1, 1, 2027) to "New Year's Day"))

    init { world.clock.nowMs = cal.toEpochMs(mon, 18 * 60) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private fun ymd(day: Long): String {
        val (y, m, dd) = CivilDate.fromEpochDay(day)
        return "$y${m.toString().padStart(2, '0')}${dd.toString().padStart(2, '0')}"
    }

    private fun event(day: Long, summary: String, extra: String = "") =
        "BEGIN:VEVENT\r\nUID:${summary.hashCode()}-$day@oakfield.example\r\nDTSTART;VALUE=DATE:${ymd(day)}\r\n" +
            "DTEND;VALUE=DATE:${ymd(day + 1)}\r\nSUMMARY:$summary\r\nDESCRIPTION:Please put your bin out by 7am at 1 Example Road\\, AB1 2CD\r\n$extra" +
            "END:VEVENT\r\n"

    private fun ics(vararg events: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Oakfield Council//Bins//EN\r\n" + events.joinToString("") + "END:VCALENDAR\r\n"

    /** An invented council: black bin every other Tuesday from 3 Nov, food caddy every Tuesday; Christmas week on Sat 2 Jan instead of 29 Dec. */
    private fun oakfield(): String {
        val ev = mutableListOf<String>()
        var t = d(11, 3)
        while (t <= d(2, 2, 2027)) {
            val day = when (t) { d(12, 29) -> d(1, 2, 2027); else -> t }
            ev += if ((t - d(11, 3)) % 14 == 0L) event(day, "Black bin and food caddy collection") else event(day, "Food Caddy")
            t += 7
        }
        return ics(*ev.toTypedArray())
    }

    @Test
    fun theCalendarsBinsAreReadFromTheirSummaries() {
        assertEquals(listOf("Black bin", "Food caddy"), BinCouncilRules.names("Black bin and food caddy collection"))
        assertEquals(listOf("Refuse"), BinCouncilRules.names("Refuse collection day"))
        assertEquals(listOf("Recycling"), BinCouncilRules.names("Recycling (blue bin)"))
        assertEquals(listOf("Garden waste", "Food caddy"), BinCouncilRules.names("Garden waste & food waste"))
        assertEquals(emptyList(), BinCouncilRules.names("Collection"))
        // Folded lines (RFC 5545) join up.
        assertEquals(listOf("SUMMARY:Black bin"), BinCouncilRules.unfold("SUMMARY:Black\r\n  bin"))
        assertEquals(d(11, 3), BinCouncilRules.day("20261103T070000Z"))
        assertNull(BinCouncilRules.day("2026-11-03"))
    }

    @Test
    fun thePatternAndTheChristmasOneOffComeFromTheCouncilsDates() {
        val read = BinCouncilRules.read(oakfield(), mon, christmas) as CouncilRead.Bins
        val black = read.bins.first { it.name == "Black bin" }
        assertEquals(2, black.weekday)
        assertEquals(2, black.everyWeeks)
        assertEquals(d(11, 3), black.anchorDay)
        // The pattern would go on Wed 30 Dec (a day later for the bank holiday); the council goes Sat 2 Jan.
        assertEquals(listOf(d(12, 30) to d(1, 2, 2027)), black.moves)
        val caddy = read.bins.first { it.name == "Food caddy" }
        assertEquals(1, caddy.everyWeeks)
        assertEquals(listOf(d(12, 30) to d(1, 2, 2027)), caddy.moves)
    }

    @Test
    fun aBankHolidayTheCouncilDoesntMoveForBecomesAOneOffBack() {
        // Monday collections; the council still collects on the bank holiday Monday 28 Dec.
        val days = (0..8).map { d(11, 30) + it * 7L }
        val bin = BinCouncilRules.pattern("Recycling", days, christmas)
        assertEquals(1, bin.weekday)
        // The pattern would shift 28 Dec to Tue 29 Dec for the bank holiday; the council says the 28th.
        assertEquals(listOf(d(12, 29) to d(12, 28)), bin.moves)
    }

    @Test
    fun aWeeklyRepeatIsExpandedAndItsExceptionsSkipped() {
        val text = ics(event(d(11, 6), "Green bin", "RRULE:FREQ=WEEKLY;INTERVAL=2;UNTIL=20270130\r\nEXDATE;VALUE=DATE:20261218\r\n"))
        val days = BinCouncilRules.collect(text, mon)!!.getValue("Green bin")
        assertEquals(d(11, 6), days.first())
        assertTrue(d(12, 18) !in days)
        assertEquals(d(1, 29, 2027), days.last())
        val bin = BinCouncilRules.pattern("Green bin", days, HolidayCalendar.NONE)
        assertEquals(listOf(d(12, 18) to null), bin.moves)
    }

    @Test
    fun anythingElseIsRefusedWithAPlainLine() {
        assertEquals(CouncilRead.Refused(BinCouncilRules.NOT_A_CALENDAR), BinCouncilRules.read("Date,Bin\n3 Nov,Black", mon, christmas))
        assertEquals(CouncilRead.Refused(BinCouncilRules.NO_COLLECTIONS), BinCouncilRules.read(ics(event(d(1, 6), "Black bin")), mon, christmas))
        assertEquals(CouncilRead.Refused(BinCouncilRules.TOO_BIG), BinCouncilRules.read("x".repeat(BinCouncilRules.MAX_CHARS + 1), mon, christmas))
        assertTrue(bFold.useCouncil("not a calendar", christmas).refused)
        assertTrue(bFold.items().isEmpty())
    }

    @Test
    fun usingTheCouncilsDatesFillsTheBinsOnBothDevicesAndKeepsTheTypedPatternForUndo() {
        // Typed wrong: black bin on the other week, and a typed one-off inside the calendar's dates.
        bFold.add("Black bin Tue fortnightly from 10 Nov", christmas)
        bFold.add("Black bin Sat 28 Nov instead of 24 Nov", christmas)
        val said = bFold.useCouncil(oakfield(), christmas)
        assertFalse(said.refused)
        assertTrue(said.canUndo)
        assertTrue(said.line.startsWith("Council dates for Black bin and food caddy · "), said.line)
        assertTrue(said.line.endsWith("· 2 changes from the usual day"), said.line)
        sync()
        val bins = bMac.items()
        assertEquals(listOf("Black bin", "Food caddy"), bins.map { it.name })
        assertTrue(bins.all { it.council })
        val black = bins.first()
        assertEquals(d(11, 3), black.anchorDay)
        // The council's Christmas week holds, the typed one-off gave way.
        assertEquals(listOf(d(11, 3), d(11, 17), d(12, 1), d(12, 15), d(1, 2, 2027)),
            BinRules.collections(black, d(11, 1), d(1, 5, 2027), christmas))
        val v = bMac.view(christmas)
        assertEquals("Council dates · read Mon 2 Nov", v.councilLine)
        assertEquals(BinRules.COUNCIL_IN_USE, v.footLine)
        assertEquals("Every other Tuesday · council dates", v.rows.first().line)
        // The council's one-offs don't crowd ONE-OFFS; the row says the move when it's next.
        assertTrue(v.oneOffs.isEmpty())
        assertTrue(BinRules.askLines(bins, mon, christmas).first().contains("from the council's calendar"))
        // Tomorrow is a collection: tonight's line follows the council.
        assertEquals("Black bin and food caddy out tonight", BinRules.tonightLine(bins, mon, 17 * 60, christmas)?.text)

        // Use my own days: the black bin goes back to the typed week, the caddy (only the council had it) goes.
        val back = bMac.useTyped()
        assertEquals("Back to the days you typed · Removed 1 bin only the council's calendar had", back.line)
        sync()
        val after = bFold.items()
        assertEquals(listOf("Black bin"), after.map { it.name })
        assertEquals(d(11, 10), after.first().anchorDay)
        assertFalse(after.first().council)
        assertTrue(after.first().moves.none { it.council })
        assertNull(bFold.view(christmas).councilLine)
        assertEquals(BinCouncilRules.NOTHING_TO_UNDO, bFold.useTyped().line)
    }

    @Test
    fun aNewerCalendarReplacesTheCouncilsOneOffsAndTypingAPatternTakesTheBinBack() {
        bFold.useCouncil(oakfield(), christmas)
        val first = bFold.items().first().moves.count { it.council }
        bFold.useCouncil(oakfield(), christmas)
        assertEquals(first, bFold.items().first().moves.count { it.council })
        bFold.add("Black bin Wed fortnightly from 4 Nov", christmas)
        val black = bFold.items().first { it.name == "Black bin" }
        assertFalse(black.council)
        assertEquals(3, black.weekday)
    }
}
