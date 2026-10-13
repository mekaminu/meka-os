package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyLinesTest {
    private val day = 20_739L // Tue 13 Oct 2026
    private val cal = LocalCalendar.UTC

    private fun lines(i: DailyInputs) = DailyRules.lines(i)

    @Test
    fun briefSaysTomorrowAfterItsStartAndTodayBefore() {
        val evening = DailyInputs(nowMinute = 20 * 60, briefStartMinute = 6 * 60 + 40, todayThings = 3, tomorrowThings = 5)
        assertEquals(DailyLine("Tomorrow 06:40 · 5 things", false), lines(evening).of(DailyRow.BRIEF))
        val early = evening.copy(nowMinute = 5 * 60)
        assertEquals(DailyLine("Today 06:40 · 3 things", false), lines(early).of(DailyRow.BRIEF))
        assertEquals("Tomorrow 06:40", lines(evening.copy(tomorrowThings = 0)).of(DailyRow.BRIEF)?.text)
        assertEquals("Tomorrow 06:40 · 1 thing", lines(evening.copy(tomorrowThings = 1)).of(DailyRow.BRIEF)?.text)
    }

    @Test
    fun anUnreadBriefIsReadyAndLit() {
        val l = lines(DailyInputs(nowMinute = 7 * 60, briefOffered = true)).of(DailyRow.BRIEF)
        assertEquals(DailyLine(DailyRules.READY, true), l)
    }

    @Test
    fun weekAheadSaysSundayUntilItsReady() {
        assertEquals(DailyLine("Sunday 18:00", false), lines(DailyInputs(nowMinute = 600)).of(DailyRow.WEEK_AHEAD))
        assertEquals(DailyLine(DailyRules.READY, true), lines(DailyInputs(nowMinute = 600, weekAheadReady = true)).of(DailyRow.WEEK_AHEAD))
        assertNull(lines(DailyInputs(nowMinute = 600, weekAheadOn = false)).of(DailyRow.WEEK_AHEAD))
    }

    @Test
    fun newsLeadsWithAFixtureElseCountsStories() {
        assertEquals(DailyLine("Barça v Girona tomorrow", false),
            lines(DailyInputs(nowMinute = 0, fixture = "Barça v Girona tomorrow", stories = 12)).of(DailyRow.NEWS))
        assertTrue(lines(DailyInputs(nowMinute = 0, fixture = "Barça v Girona · 21:00 · in 3 h", fixtureToday = true)).of(DailyRow.NEWS)!!.lit)
        assertEquals("12 stories", lines(DailyInputs(nowMinute = 0, stories = 12)).of(DailyRow.NEWS)?.text)
        assertEquals("1 story", lines(DailyInputs(nowMinute = 0, stories = 1)).of(DailyRow.NEWS)?.text)
        assertNull(lines(DailyInputs(nowMinute = 0)).of(DailyRow.NEWS))
    }

    @Test
    fun shutdownSaysWhatsLeftLitOnceTheEveningOffersIt() {
        assertEquals(DailyLine("2 left", false), lines(DailyInputs(nowMinute = 600, shutdownLeft = 2)).of(DailyRow.SHUTDOWN))
        assertEquals(DailyLine("2 left", true), lines(DailyInputs(nowMinute = 1100, shutdownLeft = 2, shutdownOffered = true)).of(DailyRow.SHUTDOWN))
        assertEquals("Day shut down at 18:42",
            lines(DailyInputs(nowMinute = 1200, shutdownLeft = 2, shutdownDoneLine = "Day shut down at 18:42")).of(DailyRow.SHUTDOWN)?.text)
        assertTrue(lines(DailyInputs(nowMinute = 1100, shutdownOffered = true)).of(DailyRow.SHUTDOWN)!!.lit)
        assertNull(lines(DailyInputs(nowMinute = 600)).of(DailyRow.SHUTDOWN))
    }

    @Test
    fun trainQueueCountsWhatsSaved() {
        assertEquals("2 saved", lines(DailyInputs(nowMinute = 0, queueWaiting = 2)).of(DailyRow.TRAIN_QUEUE)?.text)
        assertNull(lines(DailyInputs(nowMinute = 0)).of(DailyRow.TRAIN_QUEUE))
    }

    @Test
    fun lifeAdminNamesTheDayTimeAndJobs() {
        val base = DailyInputs(nowMinute = 0, todayDay = day, lifeAdminOn = true, lifeAdminMinute = 19 * 60, lifeAdminJobs = 3)
        assertEquals(DailyLine("Thu 19:00 · 3 jobs", false), lines(base.copy(lifeAdminDay = day + 2)).of(DailyRow.LIFE_ADMIN))
        assertEquals("Tomorrow 19:00 · 3 jobs", lines(base.copy(lifeAdminDay = day + 1)).of(DailyRow.LIFE_ADMIN)?.text)
        assertEquals(DailyLine("Today 19:00 · 3 jobs", true), lines(base.copy(lifeAdminDay = day)).of(DailyRow.LIFE_ADMIN))
        assertEquals("Thu 19:00", lines(base.copy(lifeAdminDay = day + 2, lifeAdminJobs = 0)).of(DailyRow.LIFE_ADMIN)?.text)
        assertNull(lines(base.copy(lifeAdminDay = day + 2, lifeAdminOn = false)).of(DailyRow.LIFE_ADMIN))
        assertNull(lines(base).of(DailyRow.LIFE_ADMIN))
    }

    @Test
    fun bedsideSaysWhetherTheScreenSaverIsOn() {
        assertEquals(DailyRules.SCREEN_SAVER_ON, DailyRules.bedside(true)?.text)
        assertEquals(DailyRules.SCREEN_SAVER_OFF, DailyRules.bedside(false)?.text)
        assertNull(DailyRules.bedside(null))
    }

    private fun fixture(id: String, title: String, startMs: Long) =
        CalendarEvent(id, title, startMs, startMs + 2 * 3_600_000L, false, null, "fixtures", null, "FC Barcelona")

    @Test
    fun fixtureLooksAWeekAhead() {
        val now = cal.toEpochMs(day, 10 * 60)
        assertNull(DailyRules.fixture(emptyList(), now, cal))
        val tomorrow = fixture("a", "Barça v Girona", cal.toEpochMs(day + 1, 20 * 60))
        assertEquals("Barça v Girona tomorrow" to false, DailyRules.fixture(listOf(tomorrow), now, cal))
        val sat = fixture("b", "Sevilla v Barça", cal.toEpochMs(day + 4, 17 * 60))
        assertEquals("Sevilla v Barça Sat" to false, DailyRules.fixture(listOf(sat), now, cal))
        val tonight = fixture("c", "Barça v Inter", cal.toEpochMs(day, 20 * 60))
        val (line, today) = DailyRules.fixture(listOf(sat, tonight, tomorrow), now, cal)!!
        assertTrue(today)
        assertTrue(line.startsWith("Barça v Inter · 20:00"), line)
        assertNull(DailyRules.fixture(listOf(fixture("d", "Far off", cal.toEpochMs(day + 9, 20 * 60))), now, cal))
        val notFixture = tomorrow.copy(provider = "google")
        assertNull(DailyRules.fixture(listOf(notFixture), now, cal))
    }

    @Test
    fun exampleChipsOnlyWithTheOneLineHint() {
        assertEquals(listOf("What's tomorrow?", "Add milk to shopping"), AskFieldRules.examples(canAsk = true, used = true))
        assertTrue(AskFieldRules.examples(canAsk = true, used = false).isEmpty())
        assertTrue(AskFieldRules.examples(canAsk = false, used = true).isEmpty())
        assertFalse(AskFieldRules.EXAMPLES.any { it.isBlank() })
    }
}
