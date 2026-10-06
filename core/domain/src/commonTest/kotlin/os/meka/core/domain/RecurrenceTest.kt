package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecurrenceTest {
    private fun day(y: Int, m: Int, d: Int) = CivilDate.toEpochDay(y, m, d)
    private fun ymd(epochDay: Long) = CivilDate.fromEpochDay(epochDay).let { "${it.year}-${it.month}-${it.day}" }

    @Test
    fun civilDateRoundTripsAndKnowsWeekdays() {
        assertEquals(0L, day(1970, 1, 1))
        assertEquals(4, CivilDate.isoDayOfWeek(0)) // Thursday
        assertEquals(2, CivilDate.isoDayOfWeek(day(2026, 10, 6))) // Tuesday
        for (d in listOf(-800_000L, -1L, 0L, 59L, 10_956L, 20_000L, 2_932_896L)) {
            val c = CivilDate.fromEpochDay(d)
            assertEquals(d, day(c.year, c.month, c.day))
        }
        assertEquals("2024-2-29", ymd(day(2024, 2, 28) + 1))
        assertEquals("2023-3-1", ymd(day(2023, 2, 28) + 1))
        assertEquals("Tue 6 Oct", CivilDate.shortLabel(day(2026, 10, 6)))
    }

    @Test
    fun dailyAndEveryNDays() {
        assertEquals("2026-10-7", ymd(Recurrence.Daily().next(day(2026, 10, 6))))
        assertEquals("2026-10-9", ymd(Recurrence.Daily(3).next(day(2026, 10, 6))))
    }

    @Test
    fun weekdaysSkipTheWeekend() {
        val r = Recurrence.Weekly(1, Recurrence.WEEKDAYS)
        assertEquals("2026-10-12", ymd(r.next(day(2026, 10, 9)))) // Fri → Mon
        assertEquals("2026-10-12", ymd(r.firstOnOrAfter(day(2026, 10, 10)))) // Sat → Mon
        assertEquals("Every weekday", r.describe())
    }

    @Test
    fun everyOtherTuesdayKeepsItsRhythm() {
        val r = Recurrence.Weekly(2, setOf(2))
        assertEquals("2026-10-20", ymd(r.next(day(2026, 10, 6))))
        assertEquals("Every 2 weeks on Tue", r.describe())
        // Several days a week, every other week: Mon → Wed, then Wed → Mon two weeks on.
        val mw = Recurrence.Weekly(2, setOf(1, 3))
        assertEquals("2026-10-7", ymd(mw.next(day(2026, 10, 5))))
        assertEquals("2026-10-19", ymd(mw.next(day(2026, 10, 7))))
    }

    @Test
    fun secondTuesdayOfTheMonth() {
        val r = Recurrence.MonthlyOnWeekday(1, 2, 2)
        assertEquals("2026-10-13", ymd(r.firstOnOrAfter(day(2026, 10, 1))))
        assertEquals("2026-11-10", ymd(r.next(day(2026, 10, 13))))
        assertEquals("2026-12-8", ymd(r.next(day(2026, 11, 10))))
        assertEquals("Monthly on the 2nd Tue", r.describe())
    }

    @Test
    fun lastFridayOfTheMonth() {
        val r = Recurrence.MonthlyOnWeekday(1, -1, 5)
        assertEquals("2026-10-30", ymd(r.firstOnOrAfter(day(2026, 10, 1))))
        assertEquals("2026-11-27", ymd(r.next(day(2026, 10, 30))))
        assertEquals("Monthly on the last Fri", r.describe())
    }

    @Test
    fun monthlyOnThe31stUsesShortMonthsLastDayAndComesBack() {
        val r = Recurrence.MonthlyOnDay(1, 31)
        assertEquals("2026-11-30", ymd(r.next(day(2026, 10, 31))))
        assertEquals("2026-12-31", ymd(r.next(day(2026, 11, 30))))
        assertEquals("2027-2-28", ymd(r.next(day(2027, 1, 31))))
        assertEquals("2027-1-31", ymd(Recurrence.MonthlyOnDay(3, 31).next(day(2026, 10, 31))))
        assertEquals("Monthly on the 31st", r.describe())
        assertEquals("Every 3 months on the 1st", Recurrence.MonthlyOnDay(3, 1).describe())
    }

    @Test
    fun yearlyAndLeapDays() {
        val r = Recurrence.Yearly(1, 2, 29)
        assertEquals("2025-2-28", ymd(r.next(day(2024, 2, 29))))
        assertEquals("2028-2-29", ymd(r.next(day(2027, 2, 28))))
        assertEquals("Every year on 14 Mar", Recurrence.Yearly(1, 3, 14).describe())
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val rules = listOf(
            Recurrence.Daily(1), Recurrence.Daily(5), Recurrence.Weekly(1, Recurrence.WEEKDAYS), Recurrence.Weekly(2, setOf(2)),
            Recurrence.MonthlyOnDay(1, 15), Recurrence.MonthlyOnWeekday(1, 2, 2), Recurrence.MonthlyOnWeekday(2, -1, 5),
            Recurrence.Yearly(1, 10, 6),
        )
        for (r in rules) assertEquals(r, Recurrence.decode(r.encode()))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU", Recurrence.Weekly(2, setOf(2)).encode())
        assertEquals("FREQ=MONTHLY;INTERVAL=1;BYDAY=-1FR", Recurrence.MonthlyOnWeekday(1, -1, 5).encode())
    }

    @Test
    fun unreadableRulesAreNullNotGuessed() {
        assertNull(Recurrence.decode(null))
        assertNull(Recurrence.decode("FREQ=HOURLY"))
        assertNull(Recurrence.decode("FREQ=DAILY;COUNT=3")) // a newer feature: kept as text, not misread
        assertNull(Recurrence.decode("FREQ=WEEKLY;BYDAY=XX"))
        assertNull(Recurrence.decode("FREQ=MONTHLY;BYDAY=5TU"))
        assertNull(Recurrence.decode("FREQ=DAILY;INTERVAL=0"))
        assertNull(Recurrence.decode("FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=30"))
        assertFailsWith<IllegalArgumentException> { Recurrence.Weekly(1, emptySet()) }
    }

    @Test
    fun presetsForTheTaskDay() {
        val p = Recurrence.presets(day(2026, 10, 6)).map { it.describe() }
        assertEquals(
            listOf("Every day", "Every weekday", "Every Tue", "Every 2 weeks on Tue", "Monthly on the 6th", "Monthly on the 1st Tue", "Every year on 6 Oct"),
            p,
        )
        // The 27th of a 31-day month on a Tuesday is the last one, so "the last Tue" is offered.
        assertTrue("Monthly on the last Tue" in Recurrence.presets(day(2026, 10, 27)).map { it.describe() })
        assertTrue("Monthly on the 4th Thu" in Recurrence.presets(day(2026, 10, 22)).map { it.describe() })
    }

    @Test
    fun everyRuleAlwaysMovesForward() {
        val rules = Recurrence.presets(day(2024, 1, 31)) + Recurrence.presets(day(2024, 2, 29)) + Recurrence.MonthlyOnDay(1, 30)
        for (r in rules) {
            var d = r.firstOnOrAfter(day(2024, 1, 1))
            repeat(60) {
                val n = r.next(d)
                assertTrue(n > d, "${r.encode()} stalled at ${ymd(d)}")
                assertTrue(r.matchesPattern(n), "${r.encode()} produced ${ymd(n)}")
                d = n
            }
        }
    }
}
