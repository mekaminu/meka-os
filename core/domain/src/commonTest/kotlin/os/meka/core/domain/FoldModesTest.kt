package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoldModesTest {
    private val cal = LocalCalendar.UTC
    private val dayMs = CivilDate.DAY_MS
    private val hourMs = 3_600_000L
    private val minMs = 60_000L

    /** Wednesday 7 October 2026. */
    private val wed = CivilDate.toEpochDay(2026, 10, 7)
    private fun at(day: Long, h: Int, m: Int = 0) = day * dayMs + h * hourMs + m * minMs

    private val emptyToday = Today(emptyList(), null, emptyList(), emptyList())
    private val brief = MorningBriefView.EMPTY.copy(
        daySummary = "2 events · 4 tasks · first at 09:30", workLine = "Work 09:00–17:30", dateLabel = "Wed 7 Oct",
    )
    private val shutdown = ShutdownView.EMPTY.copy(
        tomorrow = TomorrowPreview(
            "Tomorrow · Thu 8 Oct", "Work 09:00–17:30", emptyList(), 3, 2, "3 events · 2 tasks · first at 09:00",
            glance = "Tomorrow: first thing 09:00 Standup · 3 events · 2 tasks",
        ),
    )

    private fun bedside(
        now: Long, alarm: Long? = null, today: Today = emptyToday, b: MorningBriefView = brief, s: ShutdownView = shutdown,
        quiet: QuietHours = QuietHours.DEFAULT,
    ) = FoldModeRules.bedside(now, cal, alarm, quiet, today, b, s)

    private fun task(id: String, title: String) =
        Task(id, title, null, Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false)

    @Test
    fun halfFoldedOnATableIsTheBedsideClockWhateverTheWidth() {
        assertEquals(FoldMode.BEDSIDE, FoldModeRules.mode(820f, FoldPosture.TABLETOP))
        assertEquals(FoldMode.BEDSIDE, FoldModeRules.mode(400f, FoldPosture.TABLETOP))
        assertEquals(FoldMode.UNFOLDED, FoldModeRules.mode(820f, FoldPosture.FLAT))
        assertEquals(FoldMode.UNFOLDED, FoldModeRules.mode(700f, FoldPosture.BOOK))
        assertEquals(FoldMode.UNFOLDED, FoldModeRules.mode(600f, FoldPosture.FLAT))
        assertEquals(FoldMode.COVER, FoldModeRules.mode(599f, FoldPosture.FLAT))
    }

    @Test
    fun theScreenStaysOnOnlyAtTheBedsideWhileCharging() {
        assertTrue(FoldModeRules.keepScreenOn(FoldMode.BEDSIDE, charging = true))
        assertFalse(FoldModeRules.keepScreenOn(FoldMode.BEDSIDE, charging = false))
        assertFalse(FoldModeRules.keepScreenOn(FoldMode.UNFOLDED, charging = true))
        assertFalse(FoldModeRules.keepScreenOn(FoldMode.COVER, charging = true))
    }

    @Test
    fun theAlarmLineSaysWhenAndHowLongUntil() {
        val now = at(wed, 22, 42)
        assertEquals("Alarm 06:30 · in 7 h 48", FoldModeRules.alarmLine(now, at(wed + 1, 6, 30), cal))
        assertEquals("Alarm 06:42 · in 8 h", FoldModeRules.alarmLine(now, at(wed + 1, 6, 42), cal))
        assertEquals("Alarm 23:00 · in 18 min", FoldModeRules.alarmLine(now, at(wed, 23, 0), cal))
        // 30 seconds away rounds up rather than saying "in 0 min".
        assertEquals("Alarm 22:42 · in 1 min", FoldModeRules.alarmLine(now, now + 30_000, cal))
        // More than a day off: the weekday instead.
        assertEquals("Alarm Fri 07:00", FoldModeRules.alarmLine(now, at(wed + 2, 7, 0), cal))
        // None, or one that has already gone off.
        assertEquals(FoldModeRules.NO_ALARM, FoldModeRules.alarmLine(now, null, cal))
        assertEquals(FoldModeRules.NO_ALARM, FoldModeRules.alarmLine(now, now - minMs, cal))
        assertEquals(FoldModeRules.NO_ALARM, FoldModeRules.alarmLine(now, now, cal))
    }

    @Test
    fun theClockFollowsTheLocalTimeAndDate() {
        val v = bedside(at(wed, 22, 42), alarm = at(wed + 1, 6, 30))
        assertEquals("22:42", v.time)
        assertEquals("Wednesday 7 October", v.dateLabel)
        assertTrue(v.alarmSet)
        assertEquals("Alarm 06:30 · in 7 h 48", v.alarmLine)
        // An hour ahead of UTC, the same instant is 23:42.
        val bst = FoldModeRules.bedside(at(wed, 22, 42), LocalCalendar.fixedOffset(hourMs), null, QuietHours.DEFAULT, emptyToday, brief, shutdown)
        assertEquals("23:42", bst.time)
        assertFalse(bst.alarmSet)
        assertEquals(FoldModeRules.NO_ALARM, bst.alarmLine)
    }

    @Test
    fun itDimsInQuietHours() {
        assertTrue(bedside(at(wed, 22, 30)).dim)
        assertTrue(bedside(at(wed, 3, 0)).dim)
        assertFalse(bedside(at(wed, 7, 0)).dim)
        assertFalse(bedside(at(wed, 14, 0)).dim)
        assertFalse(bedside(at(wed, 23, 0), quiet = QuietHours(false, 22 * 60, 7 * 60)).dim)
    }

    @Test
    fun theMorningShowsTheBriefUntilItIsRead() {
        val v = bedside(at(wed, 7, 10), b = brief.copy(offered = true, waitingLine = "Waiting on 3 things · 1 to chase today"))
        assertEquals(BedsideSection.MORNING, v.section)
        assertEquals("Morning brief", v.heading)
        assertEquals(listOf("2 events · 4 tasks · first at 09:30", "Work 09:00–17:30", "Waiting on 3 things · 1 to chase today"), v.lines)
        assertEquals(BedsideOpens.BRIEF, v.opens)
        // At most three lines.
        val full = bedside(at(wed, 7, 10), b = brief.copy(offered = true, waitingLine = "Waiting on 3 things", fastingLine = "Fasting since 20:05"))
        assertEquals(FoldModeRules.MAX_LINES, full.lines.size)
    }

    @Test
    fun theEveningShowsTomorrowAtAGlance() {
        val v = bedside(at(wed, 21, 0), s = shutdown.copy(evening = true))
        assertEquals(BedsideSection.EVENING, v.section)
        assertEquals("Tomorrow · Thu 8 Oct", v.heading)
        assertEquals(listOf("First thing 09:00 Standup · 3 events · 2 tasks", "Work 09:00–17:30"), v.lines)
        assertEquals(BedsideOpens.SHUTDOWN, v.opens)
        // Nothing planned yet reads as a sentence too.
        val empty = bedside(at(wed, 21, 0), s = shutdown.copy(evening = true, tomorrow = shutdown.tomorrow.copy(glance = ShutdownRules.GLANCE_EMPTY, workLine = null)))
        assertEquals(listOf("Nothing planned yet"), empty.lines)
    }

    @Test
    fun theClockSaysTheWeatherForTheDayItShows() {
        // Weather slice 2: the morning reads today's, the evening tomorrow's, each after the day's first line.
        val morning = bedside(at(wed, 7, 10), b = brief.copy(offered = true, weatherLine = "9–15°, light rain from 15:00 — take a coat",
            waitingLine = "Waiting on 3 things"))
        assertEquals(listOf("2 events · 4 tasks · first at 09:30", "9–15°, light rain from 15:00 — take a coat", "Work 09:00–17:30"), morning.lines)
        val evening = bedside(at(wed, 21, 0), s = shutdown.copy(evening = true, tomorrow = shutdown.tomorrow.copy(weatherLine = "7–13°, cloudy")))
        assertEquals(listOf("First thing 09:00 Standup · 3 events · 2 tasks", "7–13°, cloudy", "Work 09:00–17:30"), evening.lines)
        val early = bedside(at(wed, 5, 0), b = brief.copy(startMinute = 7 * 60, weatherLine = "9–15°, cloudy"))
        assertEquals(listOf("2 events · 4 tasks · first at 09:30", "9–15°, cloudy", "Work 09:00–17:30"), early.lines)
    }

    @Test
    fun afterMidnightItShowsWhatTodayHolds() {
        // 02:00: the evening flag belongs to yesterday's view; today's brief hasn't started.
        val v = bedside(at(wed, 2, 0), b = brief.copy(startMinute = 7 * 60))
        assertEquals(BedsideSection.EARLY, v.section)
        assertEquals("Today · Wed 7 Oct", v.heading)
        assertEquals(listOf("2 events · 4 tasks · first at 09:30", "Work 09:00–17:30"), v.lines)
        assertNull(v.opens)
    }

    @Test
    fun theDayShowsWhatIsNext() {
        val event = CalendarEvent("e1", "Call with Tunde", at(wed, 14, 0), at(wed, 14, 30), false, null, "google", null, null)
        val today = emptyToday.copy(
            upNext = task("t1", "Send the invoice"),
            timeline = DayTimeline.EMPTY.copy(nextEvent = UpNextEvent(event, "Call with Tunde in 25 min", 25, "14:00–14:30")),
        )
        val v = bedside(at(wed, 13, 35), today = today)
        assertEquals(BedsideSection.DAY, v.section)
        assertEquals("Up next", v.heading)
        assertEquals(listOf("Call with Tunde in 25 min", "Next: Send the invoice"), v.lines)
        assertNull(v.opens)
        // Nothing left: says so rather than an empty half.
        assertEquals(listOf(FoldModeRules.NOTHING_NEXT), bedside(at(wed, 13, 35)).lines)
    }

    @Test
    fun theBriefWinsOverAnEveningFlagAndTheEveningOverEarly() {
        assertEquals(BedsideSection.MORNING, bedside(at(wed, 7, 30), b = brief.copy(offered = true), s = shutdown.copy(evening = true)).section)
        assertEquals(BedsideSection.EVENING, bedside(at(wed, 23, 30), b = brief.copy(startMinute = 7 * 60), s = shutdown.copy(evening = true)).section)
    }

    @Test
    fun durationsReadNaturally() {
        assertEquals("45 min", FoldModeRules.inWords(45))
        assertEquals("1 h", FoldModeRules.inWords(60))
        assertEquals("7 h 48", FoldModeRules.inWords(468))
    }

    @Test
    fun theBedsideRingIsLargerThanTodaysAndOnlyWhereItFitsBesideTheTime() {
        // The open Fold half folded (~840 × 350 dp a half): four fifths of the half's height.
        assertEquals(280f * 0.8f, FoldModeRules.bedsideRingDp(280f, 840f))
        // Never larger than 260 dp, never smaller than 150 dp.
        assertEquals(FoldModeRules.BEDSIDE_RING_MAX_DP, FoldModeRules.bedsideRingDp(400f, 840f))
        assertEquals(null, FoldModeRules.bedsideRingDp(150f, 840f))
        // Too narrow to sit beside the 96 sp digits: the clock alone.
        assertEquals(null, FoldModeRules.bedsideRingDp(350f, 400f))
        // Larger than Today's (196 dp) wherever the half allows it.
        assertTrue((FoldModeRules.bedsideRingDp(350f, 840f) ?: 0f) > 196f)
    }
}
