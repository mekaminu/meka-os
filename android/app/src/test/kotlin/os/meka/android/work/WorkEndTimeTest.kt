package os.meka.android.work

import os.meka.core.domain.LocalClock
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkEndTimeTest {
    private val london = ZoneId.of("Europe/London")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, london)

    @Test
    fun laterTodayTomorrowAndNextWeek() {
        val tuesdayMorning = at(2026, 10, 6, 10, 15) // a Tuesday
        assertEquals(at(2026, 10, 6, 17, 30), WorkEndTime.next(tuesdayMorning, LocalClock(2, 17 * 60 + 30)))
        assertEquals(at(2026, 10, 7, 7, 0), WorkEndTime.next(tuesdayMorning, LocalClock(3, 7 * 60)))
        // Same weekday, time already gone: a week on.
        assertEquals(at(2026, 10, 13, 9, 0), WorkEndTime.next(tuesdayMorning, LocalClock(2, 9 * 60)))
        // Night shift ending Saturday morning, asked on Friday night.
        assertEquals(at(2026, 10, 10, 6, 0), WorkEndTime.next(at(2026, 10, 9, 22, 0), LocalClock(6, 6 * 60)))
    }

    @Test
    fun keepsWallClockAcrossTheClocksGoingBack() {
        // UK clocks go back on Sun 25 Oct 2026; a Monday 17:30 end stays 17:30 local.
        val end = WorkEndTime.next(at(2026, 10, 23, 12, 0), LocalClock(1, 17 * 60 + 30))
        assertEquals(at(2026, 10, 26, 17, 30), end)
        assertEquals("Z", end.offset.id)
    }
}
